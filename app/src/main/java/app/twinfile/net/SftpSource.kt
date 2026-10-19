package app.twinfile.net

import app.twinfile.fs.Cap
import app.twinfile.fs.FEntry
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import app.twinfile.fs.Progress
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.security.Security
import java.util.EnumSet

/** The server's host key is not pinned yet; the UI asks the user to trust [fingerprint]. */
class HostKeyUnknown(val fingerprint: String) : FsException("首次连接，需要确认主机指纹：$fingerprint")

class HostKeyChanged(val fingerprint: String) :
    FsException("主机指纹已变化（现在是 $fingerprint），可能遭到中间人攻击。如确认服务器重装过，请在网络位置里重新确认指纹")

/** SFTP over sshj. One SSH session per pooled connection, each with its own SFTP channel. */
class SftpSource(config: NetConfig) : NetSource<SftpSource.Conn>(config) {
    class Conn(val ssh: SSHClient, val sftp: SFTPClient)

    override val caps = setOf(Cap.SERVER_MOVE, Cap.MTIME)

    override fun home(): String = Paths.normalize(config.path.ifBlank { "/" })

    override fun connect(): Conn {
        ensureBouncyCastle()
        val ssh = SSHClient(DefaultConfig())
        ssh.connectTimeout = 10_000
        ssh.timeout = 30_000
        var seen: String? = null
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
                val fp = fingerprint(key)
                seen = fp
                return config.hostKey.isNotEmpty() && config.hostKey == fp
            }
            override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
        })
        try {
            ssh.connect(config.host, config.port)
        } catch (e: TransportException) {
            runCatching { ssh.disconnect() }
            val fp = seen
            if (fp != null && config.hostKey != fp) throw if (config.hostKey.isEmpty()) HostKeyUnknown(fp) else HostKeyChanged(fp)
            throw e
        }
        try {
            if (config.keyFile.isNotBlank()) {
                val keys = if (config.password.isNotEmpty()) ssh.loadKeys(config.keyFile, config.password) else ssh.loadKeys(config.keyFile)
                ssh.authPublickey(config.user, keys)
            } else {
                ssh.authPassword(config.user, config.password)
            }
        } catch (e: UserAuthException) {
            runCatching { ssh.disconnect() }
            throw FsException("登录失败：用户名、密码或密钥不正确", e)
        }
        return Conn(ssh, ssh.newSFTPClient())
    }

    override fun disconnect(c: Conn) {
        runCatching { c.sftp.close() }
        runCatching { c.ssh.disconnect() }
    }

    override fun alive(c: Conn) = c.ssh.isConnected && c.ssh.isAuthenticated

    private fun entry(name: String, a: FileAttributes) = FEntry(
        name = name,
        isDir = a.type == FileMode.Type.DIRECTORY,
        size = if (a.type == FileMode.Type.DIRECTORY) -1 else a.size,
        mtime = a.mtime * 1000,
        perms = a.mode?.let { permString(it.permissionsMask) },
        isLink = a.type == FileMode.Type.SYMLINK,
    )

    override fun list(dir: String): List<FEntry> = call { c ->
        c.sftp.ls(dir).map { r ->
            var a = r.attributes
            // Follow symlinks so linked folders open like folders.
            if (a.type == FileMode.Type.SYMLINK) a = runCatching { c.sftp.stat(r.path) }.getOrDefault(a)
            entry(r.name, a).copy(isLink = r.attributes.type == FileMode.Type.SYMLINK)
        }
    }

    override fun stat(path: String): FEntry? = try {
        call { c -> entry(Paths.name(path), c.sftp.stat(path)) }
    } catch (e: FsException) {
        if ((e.cause as? SFTPException)?.statusCode == net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE) null else throw e
    }

    override fun mkdir(path: String) = call { c -> c.sftp.mkdir(path) }

    override fun createFile(path: String) = call { c ->
        c.sftp.open(path, EnumSet.of(OpenMode.CREAT, OpenMode.EXCL, OpenMode.WRITE)).close()
    }

    override fun rename(path: String, newName: String): String {
        val to = Paths.join(Paths.parent(path) ?: "/", newName)
        call { c -> c.sftp.rename(path, to) }
        return to
    }

    override fun move(path: String, dstPath: String) = call { c -> c.sftp.rename(path, dstPath) }

    override fun delete(path: String, progress: Progress) = call { c ->
        deleteTree(path, progress,
            isDir = { p -> c.sftp.lstat(p).type == FileMode.Type.DIRECTORY },
            children = { p -> c.sftp.ls(p).map { it.name } },
            rmFile = { p -> c.sftp.rm(p) },
            rmDir = { p -> c.sftp.rmdir(p) })
    }

    override fun openRead(path: String): InputStream {
        val c = borrow()
        return try {
            val f = c.sftp.open(path, EnumSet.of(OpenMode.READ))
            // Read-ahead keeps several requests in flight; much faster on high-latency links.
            pooledInput(c, f.ReadAheadRemoteFileInputStream(16)) { f.close() }
        } catch (e: Throwable) {
            giveBack(c, broken = e !is SFTPException)
            throw map(e)
        }
    }

    override fun openWrite(path: String, size: Long): OutputStream {
        val c = borrow()
        return try {
            val f = c.sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
            pooledOutput(c, f.RemoteFileOutputStream(0, 16)) { f.close() }
        } catch (e: Throwable) {
            giveBack(c, broken = e !is SFTPException)
            throw map(e)
        }
    }

    override fun setMtime(path: String, mtime: Long) {
        if (mtime <= 0) return
        runCatching {
            call { c ->
                val t = mtime / 1000
                c.sftp.setattr(path, FileAttributes.Builder().withAtimeMtime(t, t).build())
            }
        }
    }

    override fun map(e: Throwable): IOException = when (e) {
        is SFTPException -> FsException(when (e.statusCode) {
            net.schmizz.sshj.sftp.Response.StatusCode.NO_SUCH_FILE -> "文件不存在"
            net.schmizz.sshj.sftp.Response.StatusCode.PERMISSION_DENIED -> "没有权限"
            net.schmizz.sshj.sftp.Response.StatusCode.FILE_ALREADY_EXISTS -> "已存在同名文件"
            else -> e.message ?: "SFTP 错误"
        }, e)
        else -> super.map(e)
    }

    companion object {
        private var bcReady = false

        /** Android's built-in "BC" provider is a stripped copy; sshj needs the full one. */
        @Synchronized
        fun ensureBouncyCastle() {
            if (bcReady) return
            Security.removeProvider("BC")
            Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            SecurityUtils.setRegisterBouncyCastle(false)
            bcReady = true
        }

        /** OpenSSH-style "SHA256:<base64>", comparable with `ssh-keygen -lf`. */
        fun fingerprint(key: PublicKey): String {
            val blob = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        fun permString(mask: Int): String {
            val chars = "rwxrwxrwx"
            return buildString { for (i in 0 until 9) append(if (mask and (1 shl (8 - i)) != 0) chars[i] else '-') } +
                " (${"%o".format(mask and 0x1ff)})"
        }
    }
}
