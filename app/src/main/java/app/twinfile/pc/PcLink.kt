package app.twinfile.pc

import app.twinfile.fs.FsException
import app.twinfile.pc.proto.CHUNK_SIZE
import app.twinfile.pc.proto.Crypto
import app.twinfile.pc.proto.Ecdh
import app.twinfile.pc.proto.Message
import app.twinfile.pc.proto.RecordCipher
import app.twinfile.pc.proto.SecureReader
import app.twinfile.pc.proto.SecureWriter
import app.twinfile.pc.proto.readPlain
import app.twinfile.pc.proto.toHex
import app.twinfile.pc.proto.writePlain
import app.twinfile.util.FileLog
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

/** This app's identity towards SyncBoard PCs. */
data class Me(val id: String, val name: String)

/**
 * Pairing with a SyncBoard PC (protocol §2) and discovery (§5). TwinFile pairs like a phone
 * but announces `"app":"twinfile"`, so the PC treats it as a file client.
 */
object PcPairing {
    class Rejected(msg: String) : FsException(msg)

    /** Parses a scanned QR: SyncBoard's `syncboard://pair?...` or its `WIFI:T:ADB;...` form. */
    fun parseQr(text: String): Triple<List<String>, Int, ByteArray>? {
        val uri = when {
            text.startsWith("syncboard://pair") -> text
            else -> AdbQr.parse(text)?.let { AdbQr.toPairUri(it) } ?: return null
        }
        val q = java.net.URI(uri).rawQuery ?: return null
        val params = q.split('&').mapNotNull { kv ->
            val i = kv.indexOf('='); if (i > 0) kv.substring(0, i) to java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8") else null
        }.toMap()
        val hosts = params["h"].orEmpty().split(',').filter { it.isNotBlank() }
        val port = params["p"]?.toIntOrNull() ?: PcStore.PORT
        val token = runCatching { Base64.getUrlDecoder().decode(params["t"].orEmpty()) }.getOrNull() ?: return null
        if (hosts.isEmpty() || token.size != 32) return null
        return Triple(hosts, port, token)
    }

    /** QR mode tries each host; code mode calls [onCode] with the 6 digits to compare on the PC. */
    fun pair(me: Me, hosts: List<String>, port: Int, token: ByteArray?, onCode: (String, String) -> Unit = { _, _ -> }): PcInfo {
        var last: Exception? = null
        for (host in hosts) {
            try {
                return pairWith(me, host, port, token, hosts, onCode)
            } catch (e: Rejected) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw FsException("无法连接电脑（${hosts.joinToString()}）：${last?.message}", last)
    }

    private fun pairWith(me: Me, host: String, port: Int, token: ByteArray?, all: List<String>, onCode: (String, String) -> Unit): PcInfo {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), 4000)
            sock.soTimeout = if (token == null) 130_000 else 15_000
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            val ecdh = Ecdh()
            val nonceC = Crypto.random(32)
            writePlain(out, JSONObject().put("t", "pair").put("v", 1).put("mode", if (token != null) "qr" else "code")
                .put("id", me.id).put("name", me.name).put("platform", "android").put("app", "twinfile")
                .put("pub", b64(ecdh.public)).put("nonce", b64(nonceC)))
            val resp = readPlain(input)
            val pubS = unb64(resp.getString("pub"))
            val nonceS = unb64(resp.getString("nonce"))
            val shared = ecdh.agree(pubS)
            val th = Crypto.transcript(ecdh.public, pubS, nonceC, nonceS)
            val pcName = resp.optString("name", "PC")
            if (token != null) {
                writePlain(out, JSONObject().put("t", "pair_proof").put("mac", b64(Crypto.hmac(token, "client".toByteArray(), th))))
            } else {
                onCode(Crypto.pairingCode(th), pcName)
            }
            val ok = readPlain(input)
            if (ok.optString("t") != "pair_ok") throw Rejected(if (token != null) "二维码无效或已过期，请在电脑上重新打开「添加手机」" else "电脑拒绝了配对")
            if (token != null && !Crypto.ctEq(unb64(ok.optString("mac")), Crypto.hmac(token, "server".toByteArray(), th))) {
                throw Rejected("电脑身份校验失败")
            }
            return PcInfo(resp.getString("id"), pcName, Crypto.derivePsk(shared, token, th), (listOf(host) + all).distinct(), port)
        }
    }

    /** LAN broadcast (§5): PCs answering within [waitMs], each with "id", "name", "addr", "port". */
    fun discover(me: Me, waitMs: Int): List<JSONObject> {
        val found = LinkedHashMap<String, JSONObject>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 300
                val probe = JSONObject().put("t", "discover").put("v", 1).put("id", me.id).toString().toByteArray()
                broadcastAddresses().forEach { a -> runCatching { sock.send(DatagramPacket(probe, probe.size, a, PcStore.PORT)) } }
                val deadline = System.currentTimeMillis() + waitMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: SocketTimeoutException) { continue }
                    runCatching {
                        val o = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                        if (o.optString("t") == "here") found[o.getString("id")] = o.put("addr", p.address.hostAddress)
                    }
                }
            }
        }
        return found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
            }
        }
        return out.distinct()
    }
}

/**
 * The encrypted session (§3) with one paired PC, opened on demand and closed when idle.
 * Requests (§8.1) wait for their `fs.res`; pulls and uploads stream 256 KiB chunks.
 */
class PcLink(@Volatile var pc: PcInfo, private val me: Me, private val onHost: (PcInfo) -> Unit, private val onUnpaired: () -> Unit) {
    private inner class Session(val sock: Socket, val reader: SecureReader, val writer: SecureWriter) {
        @Volatile var closed = false
        fun send(m: Message) {
            try {
                writer.write(m)
            } catch (e: IOException) {
                close()
                throw FsException("与电脑的连接已断开", e)
            }
        }
        fun close() {
            if (closed) return
            closed = true
            runCatching { sock.close() }
            failAll(FsException("与电脑的连接已断开"))
        }
    }

    private class Pending { val q = LinkedBlockingQueue<Message>(1) }

    /** A pull's incoming chunks; [END] ends it, an error message string fails it. */
    private class Pull(val id: String) {
        val q = LinkedBlockingQueue<Any>(32)
        @Volatile var cancelled = false
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
    }

    private class Push(val id: String) { val q = LinkedBlockingQueue<Message>(4) }

    @Volatile private var session: Session? = null
    private val connectLock = Object()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val pulls = ConcurrentHashMap<String, Pull>()
    private val pushes = ConcurrentHashMap<String, Push>()
    @Volatile private var lastUse = System.currentTimeMillis()
    @Volatile var online = false
        private set

    fun idleFor(): Long = if (pulls.isEmpty() && pushes.isEmpty()) System.currentTimeMillis() - lastUse else 0

    fun close() {
        session?.close()
        session = null
        online = false
    }

    private fun failAll(e: FsException) {
        online = false
        pending.values.forEach { it.q.offer(Message.of("t" to "fs.res", "ok" to false, "error" to e.message)) }
        pulls.values.forEach { p -> p.q.clear(); p.q.offer(e.message ?: "连接断开") }
        pushes.values.forEach { it.q.offer(Message.of("t" to "file.failed", "error" to e.message)) }
    }

    private fun session(): Session {
        lastUse = System.currentTimeMillis()
        session?.takeIf { !it.closed }?.let { return it }
        synchronized(connectLock) {
            session?.takeIf { !it.closed }?.let { return it }
            val s = connectAny()
            session = s
            online = true
            Thread({ readLoop(s) }, "pc-read").apply { isDaemon = true }.start()
            Thread({ pingLoop(s) }, "pc-ping").apply { isDaemon = true }.start()
            return s
        }
    }

    private fun connectAny(): Session {
        var last: Exception? = null
        for (host in pc.hosts) {
            try {
                return connect(host)
            } catch (e: FsException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        // The PC may have a new address: ask the LAN.
        PcPairing.discover(me, 1200).firstOrNull { it.optString("id") == pc.id }?.let { d ->
            try {
                return connect(d.getString("addr"))
            } catch (e: FsException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw FsException("连不上电脑「${pc.display}」：确认电脑上的 SyncBoard 正在运行，并且和手机在同一网络（${last?.message ?: "无响应"}）", last)
    }

    private fun connect(host: String): Session {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, pc.port), 3000)
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = 15_000
            val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 64 * 1024))
            val nonceC = Crypto.random(32)
            writePlain(out, JSONObject().put("t", "hello").put("v", 1).put("id", me.id).put("nonce", b64(nonceC)))
            val resp = readPlain(input)
            if (resp.optString("t") != "hello") {
                if (resp.optString("code") == "unknown_device") throw FsException("电脑「${pc.display}」已取消与本机的配对，请重新扫码配对")
                throw IOException("refused: $resp")
            }
            val id = resp.optString("id")
            if (id.isNotEmpty() && id != pc.id) throw IOException("another PC answers at $host")
            val (c2s, s2c) = Crypto.sessionKeys(pc.psk, nonceC, unb64(resp.getString("nonce")))
            val reader = SecureReader(input, RecordCipher(s2c))
            val writer = SecureWriter(out, RecordCipher(c2s))
            writer.write(Message.of("t" to "auth", "name" to me.name, "platform" to "android", "app" to "twinfile"))
            val auth = try {
                reader.read()
            } catch (e: GeneralSecurityException) {
                throw FsException("与电脑「${pc.display}」的配对信息不一致，请重新扫码配对", e)
            }
            if (auth.kind != "auth") throw IOException("expected auth")
            sock.soTimeout = 90_000
            if (pc.hosts.firstOrNull() != host) {
                pc = pc.withHost(host)
                onHost(pc)
            }
            FileLog.i("connected to ${pc.display} at $host")
            return Session(sock, reader, writer)
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
    }

    private fun pingLoop(s: Session) {
        while (!s.closed) {
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { return }
            if (s.closed) return
            runCatching { s.send(Message.of("t" to "ping")) }
        }
    }

    private fun readLoop(s: Session) {
        try {
            while (!s.closed) {
                val m = s.reader.read()
                val id = m.str("id")
                when (m.kind) {
                    "ping" -> s.send(Message.of("t" to "pong"))
                    "fs.res" -> {
                        val rid = m.str("rid")
                        val pull = pulls[rid]
                        if (pull != null) {
                            if (!m.header.optBoolean("ok")) pull.q.put(m.header.optString("error", "读取失败"))
                            else pull.q.put(m)
                        } else pending.remove(rid)?.q?.offer(m)
                    }
                    "chunk" -> pulls[id]?.let { p ->
                        if (p.cancelled) return@let
                        p.md.update(m.body)
                        // Blocks while the consumer is behind; it frees space or cancels.
                        while (!p.cancelled && !p.q.offer(m.body, 1, TimeUnit.SECONDS)) {}
                    }
                    "end" -> pulls[id]?.let { p ->
                        if (p.cancelled) return@let
                        val ok = p.md.digest().toHex() == m.str("sha256")
                        p.q.put(if (ok) END else "校验失败，文件可能已损坏")
                    }
                    "file.cancel" -> pulls[id]?.let { p -> if (!p.cancelled) p.q.put("电脑取消了传输") }
                    "file.accept", "file.reject", "file.done", "file.failed" -> pushes[id]?.q?.offer(m)
                    "unpaired" -> {
                        onUnpaired()
                        s.close()
                    }
                }
            }
        } catch (e: Exception) {
            if (!s.closed) FileLog.w("pc session ended: ${e.message}")
        } finally {
            s.close()
            if (session === s) session = null
        }
    }

    /** An `fs` request; returns the successful `fs.res` or throws its error. */
    fun request(op: String, args: JSONObject = JSONObject(), timeoutMs: Long = 20_000): Message {
        val s = session()
        val rid = Crypto.randomId()
        val p = Pending()
        pending[rid] = p
        try {
            args.put("t", "fs").put("rid", rid).put("op", op)
            s.send(Message(args))
            val r = p.q.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw FsException("电脑没有响应（电脑上的 SyncBoard 可能需要更新）")
            if (!r.header.optBoolean("ok")) throw FsException(r.header.optString("error", "电脑端操作失败"))
            return r
        } finally {
            pending.remove(rid)
            lastUse = System.currentTimeMillis()
        }
    }

    /** Streams a PC file (`pull`). Closing early cancels the transfer. */
    fun pull(path: String): InputStream {
        val s = session()
        val rid = Crypto.randomId()
        val p = Pull(rid)
        pulls[rid] = p
        s.send(Message.of("t" to "fs", "rid" to rid, "op" to "pull", "path" to path))
        val head = p.q.poll(20, TimeUnit.SECONDS)
        if (head !is Message) {
            pulls.remove(rid)
            throw FsException(head as? String ?: "电脑没有响应")
        }
        return object : InputStream() {
            private var cur: ByteArray? = null
            private var pos = 0
            private var done = false

            private fun fill(): Boolean {
                while (cur == null || pos >= cur!!.size) {
                    if (done) return false
                    val next = p.q.poll(90, TimeUnit.SECONDS) ?: throw FsException("电脑传输超时")
                    when (next) {
                        END -> { done = true; pulls.remove(rid); return false }
                        is ByteArray -> { cur = next; pos = 0 }
                        is String -> { pulls.remove(rid); throw FsException(next) }
                    }
                }
                return true
            }

            override fun read(): Int = if (!fill()) -1 else cur!![pos++].toInt() and 0xff

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (!fill()) return -1
                val n = minOf(len, cur!!.size - pos)
                System.arraycopy(cur!!, pos, b, off, n)
                pos += n
                lastUse = System.currentTimeMillis()
                return n
            }

            override fun close() {
                if (!done && pulls.containsKey(rid)) {
                    p.cancelled = true
                    p.q.clear()
                    runCatching { session?.send(Message.of("t" to "file.cancel", "id" to rid)) }
                }
                pulls.remove(rid)
            }
        }
    }

    /** Uploads into the PC folder [dir] as [name]; close() returns once the PC confirmed it. */
    fun push(dir: String, name: String, size: Long): OutputStream {
        val s = session()
        val id = Crypto.randomId()
        val p = Push(id)
        pushes[id] = p
        s.send(Message.of("t" to "file.offer", "id" to id, "name" to name, "size" to size, "dir" to dir))
        val answer = p.q.poll(30, TimeUnit.SECONDS)
        if (answer?.kind != "file.accept") {
            pushes.remove(id)
            throw FsException(answer?.header?.optString("error")?.takeIf { it.isNotEmpty() } ?: "电脑拒绝接收文件")
        }
        val md = MessageDigest.getInstance("SHA-256")
        return object : OutputStream() {
            private val buf = ByteArray(CHUNK_SIZE)
            private var n = 0
            private var seq = 0
            private var closed = false

            private fun flushChunk() {
                if (n == 0) return
                val body = buf.copyOf(n)
                md.update(body)
                // The PC may fail the upload mid-way (disk full...).
                p.q.poll()?.let { m -> if (m.kind == "file.failed") throw FsException(m.header.optString("error", "上传失败")) }
                s.send(Message.of("t" to "chunk", "id" to id, "seq" to seq++, body = body))
                n = 0
                lastUse = System.currentTimeMillis()
            }

            override fun write(b: Int) {
                buf[n++] = b.toByte()
                if (n == buf.size) flushChunk()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off
                var left = len
                while (left > 0) {
                    val k = minOf(left, buf.size - n)
                    System.arraycopy(b, o, buf, n, k)
                    n += k; o += k; left -= k
                    if (n == buf.size) flushChunk()
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    flushChunk()
                    s.send(Message.of("t" to "end", "id" to id, "sha256" to md.digest().toHex()))
                    val r = p.q.poll(120, TimeUnit.SECONDS) ?: throw FsException("电脑没有确认收到文件")
                    if (r.kind != "file.done") throw FsException(r.header.optString("error", "上传失败"))
                } finally {
                    pushes.remove(id)
                }
            }
        }
    }

    /** Tells the PC we removed it, then forgets the session. */
    fun unpair() {
        runCatching { session?.send(Message.of("t" to "unpaired")) }
        close()
    }

    private companion object {
        val END = Any()
    }
}

