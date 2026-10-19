package app.twinfile.ops

import app.twinfile.fs.CancelledException
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** ZIP compress/extract on local files with java.util.zip (no extra dependency). */
object Archive {
    fun compress(files: List<File>, out: File, stats: TransferStats) {
        if (out.exists()) throw FsException("已存在同名文件：${out.name}")
        for (f in files) measure(f, stats)
        val buf = ByteArray(256 * 1024)
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
                for (f in files) add(zip, f, f.name, buf, stats, out)
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    private fun measure(f: File, stats: TransferStats) {
        if (f.isDirectory) f.listFiles()?.forEach { measure(it, stats) }
        else {
            stats.totalFiles++
            stats.totalBytes += f.length()
        }
    }

    private fun add(zip: ZipOutputStream, f: File, name: String, buf: ByteArray, stats: TransferStats, out: File) {
        if (stats.cancelled) throw CancelledException()
        if (f == out) return
        if (f.isDirectory) {
            zip.putNextEntry(ZipEntry("$name/").apply { time = f.lastModified() })
            zip.closeEntry()
            for (c in f.listFiles() ?: emptyArray()) add(zip, c, "$name/${c.name}", buf, stats, out)
            return
        }
        stats.current = f.name
        zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
        BufferedInputStream(FileInputStream(f)).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zip.write(buf, 0, n)
                stats.doneBytes += n
                if (stats.cancelled) throw CancelledException()
            }
        }
        zip.closeEntry()
        stats.doneFiles++
    }

    /**
     * Extracts [zipFile] into a new folder named after it inside [dstDir]. Entry names are
     * decoded as UTF-8, falling back to GBK (archives made on Chinese Windows).
     */
    fun extract(zipFile: File, dstDir: File, stats: TransferStats): File {
        val zip = openZip(zipFile)
        zip.use { z ->
            val base = zipFile.name.substringBeforeLast('.').ifEmpty { zipFile.name }
            val name = Paths.freeName(base, true) { File(dstDir, it).exists() }
            val root = File(dstDir, name)
            if (!root.mkdirs()) throw FsException("无法创建文件夹：$name")
            val entries = z.entries().toList()
            entries.forEach { if (!it.isDirectory) { stats.totalFiles++; stats.totalBytes += maxOf(0, it.size) } }
            val rootPath = root.canonicalPath + File.separator
            val buf = ByteArray(256 * 1024)
            for (e in entries) {
                if (stats.cancelled) throw CancelledException()
                val target = File(root, e.name)
                // Zip-slip guard: entries must stay inside the target folder.
                if (!target.canonicalPath.startsWith(rootPath) && target.canonicalPath + File.separator != rootPath) {
                    throw FsException("压缩包包含非法路径：${e.name}")
                }
                if (e.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                stats.current = target.name
                z.getInputStream(e).use { input ->
                    FileOutputStream(target).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            stats.doneBytes += n
                            if (stats.cancelled) throw CancelledException()
                        }
                    }
                }
                if (e.time > 0) target.setLastModified(e.time)
                stats.doneFiles++
            }
            return root
        }
    }

    fun openZip(f: File): ZipFile {
        val utf8 = ZipFile(f, Charsets.UTF_8)
        return try {
            utf8.entries().toList()
            utf8
        } catch (_: IllegalArgumentException) {
            // Malformed UTF-8 names: the archive was written with a legacy code page.
            utf8.close()
            ZipFile(f, Charset.forName("GBK"))
        }
    }
}
