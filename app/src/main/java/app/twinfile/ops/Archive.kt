package app.twinfile.ops

import app.twinfile.fs.CancelledException
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.model.enums.AesKeyStrength
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Files

class ArchivePasswordException(val retry: Boolean = false) : FsException(
    if (retry) "密码不正确或压缩包已损坏，请重新输入密码" else "此压缩包需要密码",
)

enum class ArchiveFormat(val label: String, val suffix: String) {
    ZIP("ZIP", ".zip"), SEVEN_Z("7z", ".7z"), TAR("TAR", ".tar"), TAR_GZ("TAR.GZ", ".tar.gz");
}

/** Local archives. Failed extraction cleans up only the new destination folder. */
object Archive {
    fun format(name: String): ArchiveFormat? = when {
        name.endsWith(".tar.gz", true) || name.endsWith(".tgz", true) -> ArchiveFormat.TAR_GZ
        name.endsWith(".tar", true) -> ArchiveFormat.TAR
        name.endsWith(".7z", true) -> ArchiveFormat.SEVEN_Z
        Paths.extension(name) in setOf("zip", "apks", "xapk", "jar") -> ArchiveFormat.ZIP
        else -> null
    }

    fun compress(files: List<File>, out: File, stats: TransferStats,
                 format: ArchiveFormat = ArchiveFormat.ZIP, password: CharArray? = null) {
        if ((password != null && password.isNotEmpty()) && format != ArchiveFormat.ZIP)
            throw FsException("创建带密码的压缩包请选择 ZIP")
        if (!out.createNewFile()) throw FsException("已存在同名文件：${out.name}")
        try {
            val entries = mutableListOf<Pair<File, String>>()
            fun collect(f: File, name: String) {
                checkCancelled(stats)
                if (f.canonicalFile == out.canonicalFile) return
                if (Files.isSymbolicLink(f.toPath())) throw FsException("暂不支持压缩符号链接：${f.name}")
                entries += f to name
                if (f.isDirectory) (f.listFiles() ?: throw FsException("无法读取文件夹：${f.name}"))
                    .forEach { collect(it, "$name/${it.name}") }
                else { stats.totalFiles++; stats.totalBytes += f.length() }
            }
            files.forEach { collect(it, it.name) }
            if (format != ArchiveFormat.ZIP) ExtraArchives.compress(entries, out, format, stats)
            else ZipOutputStream(out.outputStream().buffered(), password).use { zip ->
                for ((file, name) in entries) {
                    checkCancelled(stats)
                    zip.putNextEntry(ZipParameters().apply {
                        fileNameInZip = name + if (file.isDirectory) "/" else ""
                        lastModifiedFileTime = file.lastModified()
                        if (!file.isDirectory && (password != null && password.isNotEmpty())) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    })
                    if (!file.isDirectory) file.inputStream().buffered().use { copy(it, zip, stats, name) }
                    zip.closeEntry()
                    if (!file.isDirectory) stats.doneFiles++
                }
            }
        } catch (e: Exception) { out.delete(); throw e }
    }

    fun extract(file: File, dstDir: File, stats: TransferStats, password: CharArray? = null): File {
        val format = format(file.name) ?: throw FsException("不支持此压缩格式")
        val suffix = when {
            file.name.endsWith(".tgz", true) -> ".tgz"
            format == ArchiveFormat.ZIP -> "." + Paths.extension(file.name)
            else -> format.suffix
        }
        val base = file.name.dropLast(suffix.length).ifEmpty { "解压文件" }
        val root = File(dstDir, Paths.freeName(base, true) { File(dstDir, it).exists() })
        val parent = dstDir.canonicalFile
        if (root.canonicalFile.parentFile != parent) throw FsException("解压目标无效")
        if (!root.mkdir()) throw FsException("无法创建文件夹：${root.name}")
        val totals = stats.totalBytes to stats.totalFiles
        val done = stats.doneBytes to stats.doneFiles
        try {
            if (format != ArchiveFormat.ZIP) ExtraArchives.extract(file, root, format, stats, password)
            else openZip(file, password).use { zip ->
                if (zip.isEncrypted && password == null) throw ArchivePasswordException()
                for (entry in zip.fileHeaders) {
                    checkCancelled(stats)
                    val target = target(root, entry.fileName, entry.isDirectory)
                    if (entry.isDirectory) { mkdir(target); continue }
                    stats.totalFiles++; stats.totalBytes += maxOf(0, entry.uncompressedSize)
                    mkdir(target.parentFile!!)
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { copy(input, it, stats, entry.fileName) }
                    }
                    if (entry.lastModifiedTimeEpoch > 0) target.setLastModified(entry.lastModifiedTimeEpoch)
                    stats.doneFiles++
                }
            }
            return root
        } catch (e: Exception) {
            if (root.canonicalFile.parentFile == parent) root.deleteRecursively()
            stats.totalBytes = totals.first; stats.totalFiles = totals.second
            stats.doneBytes = done.first; stats.doneFiles = done.second
            if (e is ZipException && e.type == ZipException.Type.WRONG_PASSWORD) throw ArchivePasswordException(true)
            throw e
        }
    }

    /** Explicit UTF-8 first; malformed legacy Chinese names fall back to GBK. */
    fun openZip(file: File, password: CharArray? = null): ZipFile {
        val zip = ZipFile(file, password).apply { charset = Charsets.UTF_8 }
        try {
            if (zip.fileHeaders.any { '\uFFFD' in it.fileName }) {
                zip.close()
                return ZipFile(file, password).apply { charset = Charset.forName("GBK"); fileHeaders }
            }
            return zip
        } catch (e: Exception) { zip.close(); throw e }
    }

    internal fun checkCancelled(stats: TransferStats) { if (stats.cancelled) throw CancelledException() }
    internal fun mkdir(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw FsException("无法创建文件夹：${dir.name}")
    }
    internal fun target(root: File, name: String, directory: Boolean = false): File {
        val clean = name.replace('\\', '/')
        val f = File(root, clean)
        if (clean.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(clean) ||
            !(directory && f.canonicalFile == root.canonicalFile) &&
            !f.canonicalPath.startsWith(root.canonicalPath + File.separator))
            throw FsException("压缩包包含非法路径：$name")
        return f
    }
    internal fun copy(input: InputStream, output: OutputStream, stats: TransferStats, name: String) {
        stats.current = name
        val buffer = ByteArray(256 * 1024)
        while (true) {
            checkCancelled(stats)
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            stats.doneBytes += n
        }
    }
}
