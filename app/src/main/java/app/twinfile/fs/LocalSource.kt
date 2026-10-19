package app.twinfile.fs

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The phone's own file system through java.io.File. [permsOf] renders the mode string
 * ("rwxr-x---"); it is injected because android.system.Os is unavailable in JVM tests.
 */
class LocalSource(
    private val rootsProvider: () -> List<Root>,
    private val permsOf: (File) -> String? = { null },
) : FileSource {
    override val id = "local"
    override val title = "本地"
    override val phonePaths: Boolean get() = true
    override val caps = setOf(Cap.SERVER_MOVE, Cap.SERVER_COPY, Cap.TRASH, Cap.THUMB, Cap.MTIME)

    override fun roots() = rootsProvider()

    override fun list(dir: String): List<FEntry> {
        val f = File(dir)
        val names = f.list() ?: virtualChildren(dir)
            ?: throw FsException(if (f.isDirectory) "无法读取此文件夹：$dir" else "文件夹不存在：$dir")
        return names.map { entry(File(f, it), withPerms = false) }
    }

    /**
     * Folders like /storage/emulated can't be listed on Android 11+, yet volumes live below
     * them. Show the next path segment of every volume root so the user can walk back down.
     */
    private fun virtualChildren(dir: String): Array<String>? {
        val prefix = if (dir.endsWith("/")) dir else "$dir/"
        val names = roots().map { it.path }.filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }.distinct()
        return names.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    override fun stat(path: String): FEntry? {
        val f = File(path)
        return if (f.exists() || isBrokenLink(f)) entry(f, withPerms = true) else null
    }

    private fun entry(f: File, withPerms: Boolean): FEntry {
        val dir = f.isDirectory
        return FEntry(
            name = f.name.ifEmpty { "/" },
            isDir = dir,
            size = if (dir) -1 else f.length(),
            mtime = f.lastModified(),
            perms = if (withPerms) permsOf(f) else null,
            isLink = isLink(f),
        )
    }

    private fun isLink(f: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    } catch (_: Throwable) {
        false
    }

    private fun isBrokenLink(f: File) = isLink(f) && !f.exists()

    override fun mkdir(path: String) {
        val f = File(path)
        if (f.exists()) throw FsException("已存在同名文件：${f.name}")
        if (!f.mkdirs()) throw FsException("无法创建文件夹：${f.name}")
    }

    override fun createFile(path: String) {
        val f = File(path)
        if (f.exists()) throw FsException("已存在同名文件：${f.name}")
        if (!f.createNewFile()) throw FsException("无法创建文件：${f.name}")
    }

    override fun rename(path: String, newName: String): String {
        require(newName.isNotBlank() && '/' !in newName) { "invalid name" }
        val f = File(path)
        val to = File(f.parentFile, newName)
        if (to.exists() && !to.path.equals(f.path, ignoreCase = true)) throw FsException("已存在同名文件：$newName")
        if (!f.renameTo(to)) throw FsException("重命名失败：${f.name}")
        return to.path
    }

    override fun delete(path: String, progress: Progress) {
        deleteRec(File(path), progress)
    }

    private fun deleteRec(f: File, progress: Progress) {
        if (!progress.step(0, f.name)) throw CancelledException()
        if (f.isDirectory && !isLink(f)) f.listFiles()?.forEach { deleteRec(it, progress) }
        if (!f.delete() && f.exists()) throw FsException("无法删除：${f.path}")
    }

    override fun openRead(path: String): InputStream = FileInputStream(path)

    override fun openWrite(path: String, size: Long): OutputStream = FileOutputStream(path)

    override fun setMtime(path: String, mtime: Long) {
        if (mtime > 0) File(path).setLastModified(mtime)
    }

    override fun move(path: String, dstPath: String) {
        val from = File(path)
        val to = File(dstPath)
        if (to.exists()) throw FsException("已存在同名文件：${to.name}")
        if (!from.renameTo(to)) throw FsException("移动失败：${from.name}")
    }

    override fun copy(path: String, dstPath: String, progress: Progress) {
        val from = File(path)
        val to = File(dstPath)
        if (from.isDirectory) {
            if (Paths.isInside(to.path, from.path)) throw FsException("不能把文件夹复制到它自己里面")
            if (!to.mkdirs() && !to.isDirectory) throw FsException("无法创建文件夹：${to.name}")
            for (c in from.listFiles() ?: emptyArray()) copy(c.path, File(to, c.name).path, progress)
            to.setLastModified(from.lastModified())
        } else {
            copyFile(from, to, progress)
        }
    }

    private fun copyFile(from: File, to: File, progress: Progress) {
        FileInputStream(from).channel.use { src ->
            FileOutputStream(to).channel.use { dst ->
                val size = src.size()
                var pos = 0L
                while (pos < size) {
                    val n = src.transferTo(pos, minOf(CHUNK, size - pos), dst)
                    if (n <= 0) break
                    pos += n
                    if (!progress.step(n, from.name)) {
                        to.delete()
                        throw CancelledException()
                    }
                }
            }
        }
        to.setLastModified(from.lastModified())
    }

    private companion object {
        const val CHUNK = 4L * 1024 * 1024
    }
}
