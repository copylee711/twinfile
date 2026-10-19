package app.twinfile.net

import app.twinfile.fs.Cap
import app.twinfile.fs.FEntry
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import app.twinfile.fs.Progress
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Accepts any certificate; only used when the user ticks「信任所有证书」. */
object TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(TrustAll), SecureRandom()) }
}

/** FTP and FTPS (explicit AUTH TLS, or implicit when [NetConfig.tls]) through commons-net. */
class FtpSource(config: NetConfig) : NetSource<FTPClient>(config) {
    override val caps = setOf(Cap.SERVER_MOVE, Cap.MTIME)
    private val mlsd = HashMap<FTPClient, Boolean>()

    override fun connect(): FTPClient {
        val c = if (config.type == NetType.FTPS) {
            (if (config.trustAll) FTPSClient(config.tls, TrustAll.sslContext()) else FTPSClient("TLS", config.tls))
                .also { if (config.trustAll) it.setTrustManager(TrustAll) }
        } else FTPClient()
        c.controlEncoding = config.encoding
        c.connectTimeout = 10_000
        c.setDefaultTimeout(20_000)
        c.setDataTimeout(Duration.ofSeconds(30))
        c.setControlKeepAliveTimeout(Duration.ofSeconds(60))
        c.isRemoteVerificationEnabled = false
        c.connect(config.host, config.port)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            c.disconnect()
            throw FsException("服务器拒绝连接：${c.replyString.trim()}")
        }
        if (c is FTPSClient) {
            c.execPBSZ(0)
            c.execPROT("P")
        }
        val user = config.user.ifBlank { "anonymous" }
        val pass = if (config.user.isBlank()) "twinfile@" else config.password
        if (!c.login(user, pass)) {
            val msg = c.replyString.trim()
            c.disconnect()
            throw FsException("登录失败：$msg")
        }
        if (config.encoding.equals("UTF-8", true)) c.sendCommand("OPTS UTF8 ON")
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
        // Servers that list MLST in FEAT support MLSD too; both report times in UTC.
        synchronized(mlsd) { mlsd[c] = c.hasFeature("MLSD") || c.hasFeature("MLST") }
        return c
    }

    override fun disconnect(c: FTPClient) {
        synchronized(mlsd) { mlsd.remove(c) }
        try { c.logout() } catch (_: Exception) {}
        try { c.disconnect() } catch (_: Exception) {}
    }

    override fun alive(c: FTPClient): Boolean = c.isConnected && try { c.sendNoOp() } catch (_: Exception) { false }

    private fun entry(f: FTPFile, name: String = f.name): FEntry = FEntry(
        name = name,
        isDir = f.isDirectory,
        size = if (f.isDirectory) -1 else f.size,
        mtime = f.timestamp?.timeInMillis ?: 0,
        isLink = f.isSymbolicLink,
    )

    private fun check(c: FTPClient, ok: Boolean, what: String) {
        if (!ok) throw FsException("$what：${c.replyString?.trim()}")
    }

    override fun list(dir: String): List<FEntry> = call { c ->
        val files = if (synchronized(mlsd) { mlsd[c] } == true) c.mlistDir(dir) else c.listFiles(dir)
        if (files == null) throw FsException("无法读取：$dir")
        files.filterNotNull().filter { it.name != "." && it.name != ".." && it.name.isNotEmpty() }
            .map { entry(it, Paths.name(it.name)) }
    }

    override fun stat(path: String): FEntry? {
        if (path == "/") return FEntry("/", true, -1, 0)
        val parent = Paths.parent(path) ?: "/"
        val name = Paths.name(path)
        return call { c ->
            if (synchronized(mlsd) { mlsd[c] } == true) {
                c.mlistFile(path)?.let { return@call entry(it, name) }
                return@call null
            }
            // Without MLST, list the parent folder.
            c.listFiles(parent)?.firstOrNull { it != null && Paths.name(it.name) == name }?.let { entry(it, name) }
        }
    }

    override fun mkdir(path: String) = call { c -> check(c, c.makeDirectory(path), "无法创建文件夹") }

    override fun createFile(path: String) = call { c ->
        check(c, c.storeFile(path, ByteArrayInputStream(ByteArray(0))), "无法创建文件")
    }

    override fun rename(path: String, newName: String): String {
        val to = Paths.join(Paths.parent(path) ?: "/", newName)
        call { c -> check(c, c.rename(path, to), "重命名失败") }
        return to
    }

    override fun move(path: String, dstPath: String) = call { c -> check(c, c.rename(path, dstPath), "移动失败") }

    override fun delete(path: String, progress: Progress) = call { c ->
        deleteTree(path, progress,
            // Leave the folder again: many servers refuse to remove the working directory.
            isDir = { p -> c.changeWorkingDirectory(p).also { if (it) c.changeWorkingDirectory("/") } },
            children = { p -> c.listFiles(p)?.filterNotNull()?.map { Paths.name(it.name) }?.filter { it != "." && it != ".." } ?: emptyList() },
            rmFile = { p -> check(c, c.deleteFile(p), "无法删除") },
            rmDir = { p -> check(c, c.removeDirectory(p), "无法删除文件夹") })
    }

    override fun openRead(path: String): InputStream {
        val c = borrow()
        val input = try {
            c.retrieveFileStream(path) ?: throw FsException("无法读取：${c.replyString?.trim()}")
        } catch (e: Throwable) {
            giveBack(c, broken = e !is FsException)
            throw map(e)
        }
        return pooledInput(c, input) { check(it, it.completePendingCommand(), "传输未完成") }
    }

    override fun openWrite(path: String, size: Long): OutputStream {
        val c = borrow()
        val out = try {
            c.storeFileStream(path) ?: throw FsException("无法写入：${c.replyString?.trim()}")
        } catch (e: Throwable) {
            giveBack(c, broken = e !is FsException)
            throw map(e)
        }
        return pooledOutput(c, out) { check(it, it.completePendingCommand(), "上传未完成") }
    }

    override fun setMtime(path: String, mtime: Long) {
        if (mtime <= 0) return
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        runCatching { call { c -> c.setModificationTime(path, fmt.format(mtime)) } }
    }
}
