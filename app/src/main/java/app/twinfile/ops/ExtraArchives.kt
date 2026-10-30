package app.twinfile.ops

import app.twinfile.fs.FsException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Streaming TAR/GZIP and seekable 7z; no full archive is loaded into memory. */
internal object ExtraArchives {
    fun compress(entries: List<Pair<File, String>>, out: File, format: ArchiveFormat, stats: TransferStats) {
        if (format == ArchiveFormat.SEVEN_Z) {
            SevenZOutputFile(out).use { zip ->
                for ((file, name) in entries) {
                    Archive.checkCancelled(stats)
                    zip.putArchiveEntry(zip.createArchiveEntry(file, name))
                    if (!file.isDirectory) file.inputStream().buffered().use { input ->
                        Archive.copy(input, object : OutputStream() {
                            override fun write(b: Int) = zip.write(b)
                            override fun write(b: ByteArray, off: Int, len: Int) = zip.write(b, off, len)
                        }, stats, name)
                    }
                    zip.closeArchiveEntry()
                    if (!file.isDirectory) stats.doneFiles++
                }
            }
        } else {
            out.outputStream().buffered().use { raw ->
                val stream = if (format == ArchiveFormat.TAR_GZ) GZIPOutputStream(raw) else raw
                TarArchiveOutputStream(stream, "UTF-8").use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    for ((file, name) in entries) {
                        Archive.checkCancelled(stats)
                        tar.putArchiveEntry(TarArchiveEntry(file, name))
                        if (!file.isDirectory) file.inputStream().buffered().use { Archive.copy(it, tar, stats, name) }
                        tar.closeArchiveEntry()
                        if (!file.isDirectory) stats.doneFiles++
                    }
                }
            }
        }
    }

    fun extract(file: File, root: File, format: ArchiveFormat, stats: TransferStats, password: CharArray?) {
        if (format == ArchiveFormat.SEVEN_Z) {
            try {
                SevenZFile.builder().setFile(file).setPassword(password)
                    .setMaxMemoryLimitKiB(64 * 1024).get().use { zip ->
                        while (true) {
                            Archive.checkCancelled(stats)
                            val entry = zip.nextEntry ?: break
                            val target = Archive.target(root, entry.name ?: throw FsException("7z 条目缺少名称"), entry.isDirectory)
                            if (entry.isDirectory) { Archive.mkdir(target); continue }
                            if (entry.isAntiItem) throw FsException("不支持 7z 删除条目")
                            stats.totalFiles++; stats.totalBytes += maxOf(0, entry.size)
                            Archive.mkdir(target.parentFile!!)
                            target.outputStream().buffered().use { out ->
                                Archive.copy(object : InputStream() {
                                    override fun read() = zip.read()
                                    override fun read(b: ByteArray, off: Int, len: Int) = zip.read(b, off, len)
                                }, out, stats, entry.name)
                            }
                            if (entry.hasLastModifiedDate) target.setLastModified(entry.lastModifiedDate.time)
                            stats.doneFiles++
                        }
                    }
            } catch (e: PasswordRequiredException) { throw ArchivePasswordException() }
            catch (e: IOException) {
                if (e is org.apache.commons.compress.MemoryLimitException)
                    throw FsException("7z 解码需要的内存超过 64 MiB，请用较小字典重新压缩或在电脑上解压", e)
                if (password != null && e !is FsException) throw ArchivePasswordException(true)
                throw e
            }
        } else file.inputStream().buffered().use { raw ->
            val stream = if (format == ArchiveFormat.TAR_GZ) GZIPInputStream(raw) else raw
            TarArchiveInputStream(stream, "UTF-8").use { tar ->
                while (true) {
                    Archive.checkCancelled(stats)
                    val entry = tar.nextEntry ?: break
                    val target = Archive.target(root, entry.name, entry.isDirectory)
                    if (entry.isDirectory) { Archive.mkdir(target); continue }
                    // Never create archive-supplied symlinks, hardlinks, devices or FIFOs.
                    if (!entry.isFile || entry.isSymbolicLink || entry.isLink || !tar.canReadEntryData(entry))
                        throw FsException("不支持的 TAR 条目：${entry.name}")
                    stats.totalFiles++; stats.totalBytes += maxOf(0, entry.size)
                    Archive.mkdir(target.parentFile!!)
                    target.outputStream().buffered().use { Archive.copy(tar, it, stats, entry.name) }
                    target.setLastModified(entry.modTime.time)
                    stats.doneFiles++
                }
            }
        }
    }
}
