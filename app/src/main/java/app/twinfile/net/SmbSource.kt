package app.twinfile.net

import app.twinfile.fs.Cap
import app.twinfile.fs.FEntry
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import app.twinfile.fs.Progress
import app.twinfile.fs.Root
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 2/3 via smbj. Paths look like "/share/dir/file": the first segment picks the share.
 * smbj cannot enumerate shares, so "/" lists only the share named in the location's path.
 */
class SmbSource(config: NetConfig) : NetSource<SmbSource.Conn>(config, maxConnections = 2) {
    class Conn(val client: SMBClient, val connection: Connection, val session: Session) {
        val shares = HashMap<String, DiskShare>()
    }

    override val caps = setOf(Cap.SERVER_MOVE, Cap.MTIME)

    private val defaultShare get() = Paths.normalize(config.path).trim('/').substringBefore('/')

    override fun roots() = listOf(Root(config.display, home()))
    override fun home(): String = Paths.normalize(config.path.ifBlank { "/" }).let { if (it == "/" && defaultShare.isNotEmpty()) "/$defaultShare" else it }

    override fun connect(): Conn {
        val cfg = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
        val client = SMBClient(cfg)
        val conn = client.connect(config.host, config.port)
        val auth = if (config.user.isBlank()) AuthenticationContext.anonymous()
        else AuthenticationContext(config.user, config.password.toCharArray(), config.domain.ifBlank { null })
        val session = try {
            conn.authenticate(auth)
        } catch (e: SMBApiException) {
            runCatching { conn.close() }
            throw FsException("登录失败：${status(e)}", e)
        }
        return Conn(client, conn, session)
    }

    override fun disconnect(c: Conn) {
        c.shares.values.forEach { runCatching { it.close() } }
        runCatching { c.session.close() }
        runCatching { c.connection.close() }
        runCatching { c.client.close() }
    }

    override fun alive(c: Conn) = c.connection.isConnected

    /** Splits "/share/a/b" into the share and "a\b". */
    private fun split(path: String): Pair<String, String> {
        val p = Paths.normalize(path).trim('/')
        if (p.isEmpty()) throw FsException("请在路径中填写共享名，例如 /share")
        val share = p.substringBefore('/')
        val rest = if ('/' in p) p.substringAfter('/').replace('/', '\\') else ""
        return share to rest
    }

    private fun share(c: Conn, name: String): DiskShare =
        c.shares[name]?.takeIf { it.isConnected } ?: (c.session.connectShare(name) as? DiskShare
            ?: throw FsException("$name 不是磁盘共享")).also { c.shares[name] = it }

    override fun list(dir: String): List<FEntry> {
        if (Paths.normalize(dir) == "/") {
            return if (defaultShare.isNotEmpty()) listOf(FEntry(defaultShare, true, -1, 0)) else emptyList()
        }
        val (s, p) = split(dir)
        return call { c ->
            share(c, s).list(p).filter { it.fileName != "." && it.fileName != ".." }.map {
                val isDir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                FEntry(
                    name = it.fileName, isDir = isDir, size = if (isDir) -1 else it.endOfFile,
                    mtime = it.lastWriteTime.toEpochMillis(),
                    hidden = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L || it.fileName.startsWith("."),
                )
            }
        }
    }

    override fun stat(path: String): FEntry? {
        if (Paths.normalize(path) == "/") return FEntry("/", true, -1, 0)
        val (s, p) = split(path)
        if (p.isEmpty()) return FEntry(s, true, -1, 0)
        return call { c ->
            val sh = share(c, s)
            if (!sh.fileExists(p) && !sh.folderExists(p)) return@call null
            val info = sh.getFileInformation(p)
            val isDir = info.standardInformation.isDirectory
            FEntry(Paths.name(path), isDir, if (isDir) -1 else info.standardInformation.endOfFile,
                info.basicInformation.lastWriteTime.toEpochMillis())
        }
    }

    override fun mkdir(path: String) {
        val (s, p) = split(path)
        call { c -> share(c, s).mkdir(p) }
    }

    override fun createFile(path: String) {
        val (s, p) = split(path)
        call { c ->
            share(c, s).openFile(p, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE, null).close()
        }
    }

    override fun rename(path: String, newName: String): String {
        val to = Paths.join(Paths.parent(path) ?: "/", newName)
        move(path, to)
        return to
    }

    override fun move(path: String, dstPath: String) {
        val (s, p) = split(path)
        val (s2, p2) = split(dstPath)
        if (s != s2) throw FsException("不能跨共享移动")
        call { c ->
            val sh = share(c, s)
            val dir = sh.folderExists(p)
            val handle = if (dir) sh.openDirectory(p, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            else sh.openFile(p, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            handle.use { it.rename(p2, false) }
        }
    }

    override fun delete(path: String, progress: Progress) {
        val (s, p) = split(path)
        if (p.isEmpty()) throw FsException("不能删除整个共享")
        call { c ->
            val sh = share(c, s)
            if (!progress.step(0, Paths.name(path))) throw app.twinfile.fs.CancelledException()
            if (sh.folderExists(p)) sh.rmdir(p, true) else sh.rm(p)
        }
    }

    override fun openRead(path: String): InputStream {
        val (s, p) = split(path)
        val c = borrow()
        return try {
            val f = share(c, s).openFile(p, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null)
            pooledInput(c, f.inputStream) { f.close() }
        } catch (e: Throwable) {
            giveBack(c, broken = e !is SMBApiException)
            throw map(e)
        }
    }

    override fun openWrite(path: String, size: Long): OutputStream {
        val (s, p) = split(path)
        val c = borrow()
        return try {
            val f = share(c, s).openFile(p, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))
            pooledOutput(c, f.outputStream) { f.close() }
        } catch (e: Throwable) {
            giveBack(c, broken = e !is SMBApiException)
            throw map(e)
        }
    }

    override fun setMtime(path: String, mtime: Long) {
        if (mtime <= 0) return
        val (s, p) = split(path)
        runCatching {
            call { c ->
                val sh = share(c, s)
                val info = sh.getFileInformation(p).basicInformation
                val t = FileTime.ofEpochMillis(mtime)
                sh.setFileInformation(p, FileBasicInformation(info.creationTime, info.lastAccessTime, t, info.changeTime, info.fileAttributes))
            }
        }
    }

    private fun status(e: SMBApiException): String = when (e.status) {
        NtStatus.STATUS_LOGON_FAILURE -> "用户名或密码不正确"
        NtStatus.STATUS_ACCESS_DENIED -> "没有权限"
        NtStatus.STATUS_ACCOUNT_DISABLED -> "账户已停用"
        else -> e.status.name
    }

    override fun map(e: Throwable): IOException = when (e) {
        is SMBApiException -> FsException(when (e.status) {
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "文件不存在"
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> "已存在同名文件"
            NtStatus.STATUS_BAD_NETWORK_NAME -> "找不到共享：请检查路径中的共享名"
            NtStatus.STATUS_SHARING_VIOLATION -> "文件正被其他程序使用"
            else -> status(e)
        }, e)
        else -> super.map(e)
    }
}
