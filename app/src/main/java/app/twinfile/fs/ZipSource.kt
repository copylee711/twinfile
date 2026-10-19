package app.twinfile.fs

import app.twinfile.ops.Archive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A zip archive browsed like a read-only folder tree. Copying out goes through the copy engine. */
class ZipSource(val file: File) : FileSource {
    private val zip: ZipFile = Archive.openZip(file)
    private val dirs = HashMap<String, MutableMap<String, FEntry>>()
    private val entries = HashMap<String, ZipEntry>()

    override val id = "zip:${file.path}"
    override val title = file.name
    override val caps = emptySet<Cap>()
    override val container: String = file.path
    override val readOnly = true

    init {
        dirs["/"] = LinkedHashMap()
        for (e in zip.entries()) {
            val clean = Paths.normalize(e.name)
            if (clean == "/") continue
            if (e.isDirectory) ensureDir(clean, e.time) else {
                val parent = Paths.parent(clean) ?: "/"
                ensureDir(parent, e.time)
                entries[clean] = e
                dirs.getValue(parent)[Paths.name(clean)] = FEntry(Paths.name(clean), false, e.size, e.time)
            }
        }
    }

    /** Registers [dir] and every missing ancestor; zips often omit folder entries. */
    private fun ensureDir(dir: String, time: Long) {
        if (dirs.containsKey(dir)) return
        dirs[dir] = LinkedHashMap()
        val parent = Paths.parent(dir) ?: return
        ensureDir(parent, time)
        dirs.getValue(parent)[Paths.name(dir)] = FEntry(Paths.name(dir), true, -1, time)
    }

    override fun roots() = listOf(Root(file.name, "/"))

    override fun list(dir: String): List<FEntry> =
        dirs[dir]?.values?.toList() ?: throw FsException("压缩包中没有此文件夹：$dir")

    override fun stat(path: String): FEntry? {
        if (path == "/") return FEntry(file.name, true, -1, file.lastModified())
        val parent = Paths.parent(path) ?: return null
        return dirs[parent]?.get(Paths.name(path))
    }

    private fun ro(): Nothing = throw FsException("压缩包是只读的，请先解压")

    override fun mkdir(path: String) = ro()
    override fun createFile(path: String) = ro()
    override fun rename(path: String, newName: String) = ro()
    override fun delete(path: String, progress: Progress) = ro()
    override fun openWrite(path: String, size: Long): OutputStream = ro()

    override fun openRead(path: String): InputStream {
        val e = entries[path] ?: throw FsException("压缩包中没有此文件：$path")
        return zip.getInputStream(e)
    }

    override fun close() {
        zip.close()
    }
}
