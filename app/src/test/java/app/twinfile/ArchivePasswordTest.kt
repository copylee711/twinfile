package app.twinfile

import app.twinfile.fs.FsException
import app.twinfile.ops.Archive
import app.twinfile.ops.TransferStats
import app.twinfile.ops.ArchiveFormat
import app.twinfile.ops.ArchivePasswordException
import app.twinfile.fs.ZipSource
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchivePasswordTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun encrypted(method: EncryptionMethod): File {
        val input = tmp.newFile("秘密.txt").apply { writeText("encrypted content") }
        val archive = File(tmp.root, "secret.zip")
        ZipFile(archive, "correct".toCharArray()).use {
            it.addFile(input, ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = method
            })
        }
        return archive
    }

    @Test fun missingPasswordGivesActionableErrorWithoutOutput() {
        val archive = encrypted(EncryptionMethod.AES)
        val dst = tmp.newFolder("out")
        val error = runCatching { Archive.extract(archive, dst, TransferStats()) }.exceptionOrNull()
        assertTrue("Expected password guidance, got $error", error is FsException && error.message.orEmpty().contains("密码"))
        assertTrue(dst.listFiles()!!.isEmpty())
    }

    @Test fun aesAndZipCryptoExtractAndBrowse() {
        for (method in listOf(EncryptionMethod.AES, EncryptionMethod.ZIP_STANDARD)) {
            val dir = tmp.newFolder()
            val input = File(dir, "秘密.txt").apply { writeText("encrypted content") }
            val archive = File(dir, "secret.zip")
            ZipFile(archive, "correct".toCharArray()).use { zip ->
                zip.addFile(input, ZipParameters().apply { isEncryptFiles = true; encryptionMethod = method })
            }
            val dst = tmp.newFolder()
            val stats = TransferStats()
            val bad = runCatching { Archive.extract(archive, dst, stats, "wrong".toCharArray()) }.exceptionOrNull()
            assertTrue("$method: $bad", bad is ArchivePasswordException)
            assertTrue(dst.listFiles()!!.isEmpty())
            val out = Archive.extract(archive, dst, stats, "correct".toCharArray())
            assertEquals("encrypted content", File(out, input.name).readText())
            assertEquals(1, stats.doneFiles)
            assertTrue(runCatching { ZipSource(archive) }.exceptionOrNull() is ArchivePasswordException)
            assertTrue(runCatching { ZipSource(archive, "wrong".toCharArray()) }.exceptionOrNull() is ArchivePasswordException)
            ZipSource(archive, "correct".toCharArray()).useSource {
                assertEquals(input.name, it.list("/").single().name)
                assertEquals("encrypted content", it.openRead("/${input.name}").use { String(it.readBytes()) })
            }
        }
    }

    @Test fun createsEncryptedZipThatRequiresPassword() {
        val input = tmp.newFile("payload.txt").apply { writeText("private data") }
        val archive = File(tmp.root, "created.zip")
        Archive.compress(listOf(input), archive, TransferStats(), password = "new password".toCharArray())
        ZipFile(archive).use { assertTrue(it.isEncrypted) }
        val dst = tmp.newFolder()
        assertTrue(runCatching { Archive.extract(archive, dst, TransferStats()) }.exceptionOrNull() is ArchivePasswordException)
        val root = Archive.extract(archive, dst, TransferStats(), "new password".toCharArray())
        assertEquals("private data", File(root, input.name).readText())
    }

    @Test fun externallyCreatedEncryptedSevenZWithAndWithoutEncryptedHeaders() {
        for (name in listOf("encrypted-headers.7z", "encrypted-data.7z")) {
            val archive = File(tmp.newFolder(), name)
            javaClass.getResourceAsStream("/archives/$name")!!.use { input -> archive.outputStream().use { input.copyTo(it) } }
            val dst = tmp.newFolder()
            assertTrue(runCatching { Archive.extract(archive, dst, TransferStats()) }.exceptionOrNull() is ArchivePasswordException)
            assertTrue(dst.listFiles()!!.isEmpty())
            val bad = runCatching { Archive.extract(archive, dst, TransferStats(), "wrong".toCharArray()) }.exceptionOrNull()
            assertTrue("$name: $bad", bad is ArchivePasswordException)
            assertTrue(dst.listFiles()!!.isEmpty())
            val root = Archive.extract(archive, dst, TransferStats(), "correct".toCharArray())
            assertEquals("encrypted 7z content", File(root, "secret.txt").readText())
        }
    }

    @Test fun allFormatsRoundTripAndKeepEmptyFolders() {
        for (format in ArchiveFormat.entries) {
            val dir = tmp.newFolder()
            val src = File(dir, "目录").apply { mkdir() }
            File(src, "empty").mkdir()
            val payload = File(src, "嵌套/data.bin").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(320_000) { (it % 251).toByte() }) }
            val archive = File(dir, "bundle${format.suffix}")
            Archive.compress(listOf(src), archive, TransferStats(), format)
            val dst = tmp.newFolder()
            val root = Archive.extract(archive, dst, TransferStats())
            assertArrayEquals(format.name, payload.readBytes(), File(root, "嵌套/data.bin").readBytes())
            assertTrue(File(root, "empty").isDirectory)
            assertEquals("目录", root.name)
        }
    }

    @Test fun legacyGbkAndTraversal() {
        val archive = File(tmp.root, "legacy.zip")
        java.util.zip.ZipOutputStream(archive.outputStream(), java.nio.charset.Charset.forName("GBK")).use {
            it.putNextEntry(java.util.zip.ZipEntry("中文.txt")); it.write("hello".toByteArray()); it.closeEntry()
        }
        assertEquals("hello", File(Archive.extract(archive, tmp.newFolder(), TransferStats()), "中文.txt").readText())
        for (name in listOf("../escape.txt", "..\\escape.txt", "/absolute.txt", "C:/escape.txt")) {
            val malicious = File(tmp.newFolder(), "bad.zip")
            java.util.zip.ZipOutputStream(malicious.outputStream()).use {
                it.putNextEntry(java.util.zip.ZipEntry(name)); it.write(1); it.closeEntry()
            }
            val dst = tmp.newFolder()
            assertTrue(runCatching { Archive.extract(malicious, dst, TransferStats()) }.isFailure)
            assertTrue(dst.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationCleansOutputAndDoesNotOverwriteExistingArchive() {
        val input = tmp.newFile("data").apply { writeText("payload") }
        for (format in ArchiveFormat.entries) {
            val out = File(tmp.newFolder(), "bundle${format.suffix}")
            assertTrue(runCatching { Archive.compress(listOf(input), out, TransferStats().apply { cancelled = true }, format) }.isFailure)
            assertFalse(out.exists())
            Archive.compress(listOf(input), out, TransferStats(), format)
            val bytes = out.readBytes()
            assertTrue(runCatching { Archive.compress(listOf(input), out, TransferStats(), format) }.isFailure)
            assertArrayEquals(bytes, out.readBytes())
            val dst = tmp.newFolder()
            assertTrue(runCatching { Archive.extract(out, dst, TransferStats().apply { cancelled = true }) }.isFailure)
            assertTrue(dst.listFiles()!!.isEmpty())
        }
    }

    @Test fun tarWithDotRootAndSymlinks() {
        val archive = File(tmp.root, "posix.tar")
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(archive.outputStream()).use { tar ->
            tar.putArchiveEntry(org.apache.commons.compress.archivers.tar.TarArchiveEntry("./")); tar.closeArchiveEntry()
            tar.putArchiveEntry(org.apache.commons.compress.archivers.tar.TarArchiveEntry("./data").apply { size = 1 })
            tar.write(65); tar.closeArchiveEntry()
        }
        val root = Archive.extract(archive, tmp.newFolder(), TransferStats())
        assertEquals("A", File(root, "data").readText())
        val malicious = File(tmp.root, "link.tar")
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(malicious.outputStream()).use { tar ->
            tar.putArchiveEntry(org.apache.commons.compress.archivers.tar.TarArchiveEntry("link", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK).apply { linkName = "../outside" })
            tar.closeArchiveEntry()
        }
        val dst = tmp.newFolder()
        assertTrue(runCatching { Archive.extract(malicious, dst, TransferStats()) }.isFailure)
        assertTrue(dst.listFiles()!!.isEmpty())
    }

    private fun ZipSource.useSource(block: (ZipSource) -> Unit) { try { block(this) } finally { close() } }
}
