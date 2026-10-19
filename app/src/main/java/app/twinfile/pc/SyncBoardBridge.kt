package app.twinfile.pc

import android.content.Context
import android.content.Intent
import app.twinfile.fs.FsException
import app.twinfile.pc.proto.Message
import app.twinfile.priv.ExecResult
import app.twinfile.priv.PrivShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The SyncBoard app's phone service, when it is installed and running: its PC sessions
 * (no second pairing) and its shell-user rights (Android/data). It answers on
 * 127.0.0.1:53318 and only to apps the user approved in SyncBoard.
 *
 * Frames: u32 length + JSON; raw data goes as u32 length + bytes, length 0 ending a stream.
 */
object SyncBoardBridge {
    const val PACKAGE = "app.syncboard"
    private const val PORT = 53318
    private const val CHUNK = 256 * 1024

    enum class State { NOT_INSTALLED, NOT_RUNNING, NOT_APPROVED, READY }

    class NotApproved : FsException("需要在 SyncBoard 里允许 TwinFile 使用它的连接")

    fun installed(context: Context) =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Blocking. */
    fun state(context: Context): State {
        if (!installed(context)) return State.NOT_INSTALLED
        return try {
            open(JSONObject().put("cmd", "hello")).use { State.READY }
        } catch (_: NotApproved) {
            State.NOT_APPROVED
        } catch (_: IOException) {
            State.NOT_RUNNING
        }
    }

    /** Shows SyncBoard's approval dialog; start it for a result. */
    fun approvalIntent(): Intent = Intent("app.syncboard.action.AUTHORIZE_CLIENT").setPackage(PACKAGE)

    /** An open request: the first reply frame already read into [first]. */
    class Call(val sock: Socket, val input: DataInputStream, val out: DataOutputStream, val first: JSONObject) : AutoCloseable {
        override fun close() = runCatching { sock.close() }.let { }
    }

    private fun open(req: JSONObject, timeoutMs: Int = 20_000): Call {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", PORT), 1500)
            s.soTimeout = timeoutMs
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            writePlain(out, req)
            val first = readPlain(input)
            if (first.optString("event") == "denied") throw NotApproved()
            return Call(s, input, out, first)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    /** PCs SyncBoard is connected to right now: (id, name). Blocking. */
    fun pcs(): List<Pair<String, String>> = open(JSONObject().put("cmd", "pcs")).use { c ->
        val a = c.first.optJSONArray("pcs") ?: JSONArray()
        List(a.length()) { a.getJSONObject(it).let { o -> o.getString("id") to o.optString("name", "电脑") } }
    }

    private fun writePlain(out: DataOutputStream, o: JSONObject) {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
        out.flush()
    }

    private fun readPlain(input: DataInputStream): JSONObject {
        val n = input.readInt()
        if (n < 0 || n > 16 * 1024 * 1024) throw IOException("bad frame")
        val b = ByteArray(n)
        input.readFully(b)
        return JSONObject(String(b, Charsets.UTF_8))
    }

    private fun failIfError(o: JSONObject, what: String) {
        if (o.has("ok") && !o.optBoolean("ok")) throw FsException(o.optString("error").ifEmpty { what })
    }

    /** Reads chunks until the empty one. */
    private class ChunkInput(private val call: Call, private val onEnd: (Call) -> Unit) : InputStream() {
        private var left = 0
        private var ended = false

        private fun next(): Boolean {
            while (left == 0) {
                if (ended) return false
                left = call.input.readInt()
                if (left == 0) {
                    ended = true
                    onEnd(call)
                    return false
                }
            }
            return true
        }

        override fun read(): Int = if (!next()) -1 else call.input.read().also { if (it >= 0) left-- }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val n = call.input.read(b, off, minOf(len, left))
            if (n > 0) left -= n
            return n
        }

        override fun close() = call.close()
    }

    /** Buffers into chunks; close() sends the end and waits for [onEnd]. */
    private class ChunkOutput(private val call: Call, private val onEnd: (Call) -> Unit) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var closed = false

        private fun flushChunk() {
            if (n == 0) return
            call.out.writeInt(n)
            call.out.write(buf, 0, n)
            n = 0
        }

        override fun write(b: Int) {
            buf[n++] = b.toByte()
            if (n == buf.size) flushChunk()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                val k = minOf(l, buf.size - n)
                System.arraycopy(b, o, buf, n, k)
                n += k; o += k; l -= k
                if (n == buf.size) flushChunk()
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushChunk()
                call.out.writeInt(0)
                call.out.flush()
                onEnd(call)
            } finally {
                call.close()
            }
        }
    }

    /** A PC reached through SyncBoard's session with it. */
    class Transport(private val pcId: String, private val name: String) : PcTransport {
        override val sourceId get() = sourceIdOf(pcId)
        override val display get() = name
        private val lastUse = AtomicLong(System.currentTimeMillis())
        @Volatile private var busy = 0

        override fun request(op: String, args: JSONObject, timeoutMs: Long): Message {
            lastUse.set(System.currentTimeMillis())
            val req = JSONObject().put("cmd", "pcFs").put("pc", pcId).put("timeout", timeoutMs)
                .put("req", JSONObject(args.toString()).put("op", op))
            return open(req, (timeoutMs + 5_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).use { c ->
                failIfError(c.first, "电脑端操作失败")
                val n = c.input.readInt()
                val body = ByteArray(n).also { c.input.readFully(it) }
                Message(c.first, body)
            }
        }

        override fun pull(path: String): InputStream {
            val c = open(JSONObject().put("cmd", "pcPull").put("pc", pcId).put("path", path), 60_000)
            try {
                failIfError(c.first, "读取失败")
            } catch (e: Exception) {
                c.close()
                throw e
            }
            busy++
            return object : InputStream() {
                val inner = ChunkInput(c) { call -> failIfError(readPlain(call.input), "读取失败") }
                override fun read() = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
                override fun close() {
                    inner.close()
                    busy--
                    lastUse.set(System.currentTimeMillis())
                }
            }
        }

        override fun push(dir: String, name: String, size: Long): OutputStream {
            val c = open(JSONObject().put("cmd", "pcPush").put("pc", pcId).put("dir", dir).put("name", name)
                .put("size", size).put("mtime", 0), 130_000)
            try {
                failIfError(c.first, "电脑没有接收")
            } catch (e: Exception) {
                c.close()
                throw e
            }
            busy++
            val inner = ChunkOutput(c) { call -> failIfError(readPlain(call.input), "上传失败") }
            return object : OutputStream() {
                override fun write(b: Int) = inner.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = inner.write(b, off, len)
                override fun close() {
                    try {
                        inner.close()
                    } finally {
                        busy--
                        lastUse.set(System.currentTimeMillis())
                    }
                }
            }
        }

        override fun idleFor(): Long = if (busy > 0) 0 else System.currentTimeMillis() - lastUse.get()

        // Each call is its own short connection; nothing stays open.
        override fun close() {}
    }

    fun sourceIdOf(pcId: String) = "pc:sb:$pcId"

    /** Commands run by SyncBoard's phone service, which has the shell user's rights. */
    class Shell : PrivShell {
        override val label = "SyncBoard"

        override fun exec(argv: List<String>, input: InputStream?, output: OutputStream?): ExecResult {
            open(JSONObject().put("cmd", "exec").put("argv", JSONArray(argv)).put("stdin", input != null), 0).use { c ->
                failIfError(c.first, "无法执行命令")
                val feeder = input?.let { src ->
                    thread(isDaemon = true) {
                        try {
                            src.use {
                                val buf = ByteArray(CHUNK)
                                while (true) {
                                    val n = it.read(buf)
                                    if (n < 0) break
                                    if (n == 0) continue
                                    c.out.writeInt(n)
                                    c.out.write(buf, 0, n)
                                    c.out.flush()
                                }
                            }
                            c.out.writeInt(0)
                            c.out.flush()
                        } catch (_: IOException) {
                        }
                    }
                }
                val collected = java.io.ByteArrayOutputStream()
                val sink = output ?: collected
                val buf = ByteArray(CHUNK)
                while (true) {
                    var n = c.input.readInt()
                    if (n == 0) break
                    while (n > 0) {
                        val r = c.input.read(buf, 0, minOf(n, buf.size))
                        if (r < 0) throw IOException("SyncBoard 服务断开")
                        sink.write(buf, 0, r)
                        n -= r
                    }
                }
                val end = readPlain(c.input)
                feeder?.join(5000)
                return ExecResult(end.optInt("exit", -1), collected.toString(Charsets.UTF_8.name()), end.optString("err"))
            }
        }
    }
}
