package app.twinfile.fs

import app.twinfile.ops.Archive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import net.lingala.zip4j.model.FileHeader
import app.twinfile.ops.ArchivePasswordException

/** A zip archive browsed like a read-only folder tree. Copying out goes through the copy engine. */
class ZipSource(val file: File, password: CharArray? = null) : FileSource {
    private val secret = password?.copyOf()
    private val zip = Archive.openZip(file, secret)
    private val dirs = HashMap<String, MutableMap<String, FEntry>>()
    private val entries = HashMap<String, FileHeader>()

    override val id = "zip:${file.path}"
    override val title = file.name
    override val caps = emptySet<Cap>()
    override val container: String = file.path
    override val readOnly = true

    init {
        if (zip.isEncrypted && password == null) {
            zip.close()
            throw ArchivePasswordException()
        }
        // ZIPs can mix plaintext and encrypted members. Verify the smallest encrypted
        // member through EOF (including CRC/MAC), rather than decrypting the entire
        // archive just to display a directory. Later reads validate their own member.
        try {
            zip.fileHeaders.filter { it.isEncrypted && !it.isDirectory }.minByOrNull { it.uncompressedSize }?.let { e ->
                zip.getInputStream(e).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (input.read(buffer) >= 0) { }
                }
            }
        } catch (e: Exception) {
            zip.close(); secret?.fill('\u0000')
            if (e is net.lingala.zip4j.exception.ZipException && e.type == net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD)
                throw ArchivePasswordException(true)
            throw e
        }
        dirs["/"] = LinkedHashMap()
        for (e in zip.fileHeaders) {
            val clean = Paths.normalize(e.fileName)
            if (clean == "/") continue
            if (e.isDirectory) ensureDir(clean, e.lastModifiedTimeEpoch) else {
                val parent = Paths.parent(clean) ?: "/"
                ensureDir(parent, e.lastModifiedTimeEpoch)
                entries[clean] = e
                dirs.getValue(parent)[Paths.name(clean)] = FEntry(Paths.name(clean), false, e.uncompressedSize, e.lastModifiedTimeEpoch)
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
        secret?.fill('\u0000')
    }
}
