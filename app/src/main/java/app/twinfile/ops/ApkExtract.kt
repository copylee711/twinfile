package app.twinfile.ops

import android.content.pm.ApplicationInfo
import app.twinfile.fs.CancelledException
import app.twinfile.fs.Paths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Copies an installed app's APK out; split apps become one .apks (zip of base + splits). */
object ApkExtract {
    fun files(info: ApplicationInfo): List<File> =
        listOf(File(info.sourceDir)) + (info.splitSourceDirs?.map { File(it) } ?: emptyList())

    fun isSplit(info: ApplicationInfo) = !info.splitSourceDirs.isNullOrEmpty()

    /** Writes into [dir] and returns the created file. [baseName] has no extension. */
    fun extract(info: ApplicationInfo, dir: File, baseName: String, stats: TransferStats): File {
        dir.mkdirs()
        val parts = files(info)
        stats.totalFiles = parts.size
        stats.totalBytes = parts.sumOf { it.length() }
        val safe = baseName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val ext = if (parts.size > 1) "apks" else "apk"
        val name = Paths.freeName("$safe.$ext", false) { File(dir, it).exists() }
        val out = File(dir, name)
        try {
            if (parts.size == 1) {
                copy(parts[0], FileOutputStream(out), stats)
            } else {
                ZipOutputStream(FileOutputStream(out)).use { zip ->
                    for (p in parts) {
                        // Installers (SAI, Shizuku-based) expect base.apk and split_*.apk at the root.
                        zip.putNextEntry(ZipEntry(if (p == parts[0]) "base.apk" else p.name))
                        copy(p, zip, stats, close = false)
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return out
    }

    private fun copy(src: File, out: java.io.OutputStream, stats: TransferStats, close: Boolean = true) {
        val buf = ByteArray(256 * 1024)
        stats.current = src.name
        FileInputStream(src).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                stats.doneBytes += n
                if (stats.cancelled) throw CancelledException()
            }
        }
        if (close) out.close()
        stats.doneFiles++
    }
}
