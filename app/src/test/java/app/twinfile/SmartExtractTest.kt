package app.twinfile

import app.twinfile.ops.Archive
import app.twinfile.ops.ArchiveFormat
import app.twinfile.ops.TransferStats
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SmartExtractTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, vararg entries: String): File = File(tmp.newFolder(), name).apply {
        ZipOutputStream(outputStream()).use { out ->
            for (entry in entries) {
                out.putNextEntry(ZipEntry(entry))
                if (!entry.endsWith('/')) out.write(entry.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test fun sameNameWrapperIsNotDuplicatedAndImplicitFoldersWork() {
        for (entry in listOf("name/file.txt", "./name/file.txt")) {
            val dst = tmp.newFolder()
            val out = Archive.extract(zip("name.zip", entry), dst, TransferStats())
            assertEquals("name", out.name)
            assertTrue(File(out, "file.txt").isFile)
            assertFalse(File(out, "name").exists())
            assertEquals(listOf("name"), dst.list()!!.toList())
        }
    }

    @Test fun differentSoleFolderKeepsItsNameAndMeaningfulNestedFolders() {
        val out = Archive.extract(zip("download.zip", "project/project/data.txt"), tmp.newFolder(), TransferStats())
        assertEquals("project", out.name)
        assertTrue(File(out, "project/data.txt").isFile)
    }

    @Test fun looseFilesAndMixedRootsAreContained() {
        for (entries in listOf(arrayOf("a.txt"), arrayOf("a.txt", "b.txt"), arrayOf("folder/a.txt", "b.txt"), arrayOf("a/x", "b/y"))) {
            val dst = tmp.newFolder()
            val out = Archive.extract(zip("bundle.zip", *entries), dst, TransferStats())
            assertEquals("bundle", out.name)
            assertEquals(listOf("bundle"), dst.list()!!.toList())
            entries.forEach { assertTrue(File(out, it).isFile) }
        }
    }

    @Test fun existingDirectoryAndFileArePreservedWithoutMerging() {
        for (directory in listOf(true, false)) {
            val dst = tmp.newFolder()
            val existing = File(dst, "name")
            if (directory) { existing.mkdir(); File(existing, "keep").writeText("original") }
            else existing.writeText("original")
            val out = Archive.extract(zip("name.zip", "name/new.txt"), dst, TransferStats())
            assertNotEquals(existing.canonicalFile, out.canonicalFile)
            assertEquals("original", (if (directory) File(existing, "keep") else existing).readText())
            assertTrue(File(out, "new.txt").isFile)
            assertFalse(dst.list()!!.any { it.startsWith(".twinfile-extract-") })
        }
    }

    @Test fun allFormatsContainLooseEntriesAndHandleSoleEmptyFolder() {
        for (format in ArchiveFormat.entries) {
            for (emptyFolder in listOf(true, false)) {
                val src = tmp.newFolder()
                val entry = File(src, if (emptyFolder) "empty" else "file.txt")
                if (emptyFolder) entry.mkdir() else entry.writeText("payload")
                val archive = File(tmp.newFolder(), "bundle${format.suffix}")
                Archive.compress(listOf(entry), archive, TransferStats(), format)
                val dst = tmp.newFolder()
                val out = Archive.extract(archive, dst, TransferStats())
                assertEquals(if (emptyFolder) "empty" else "bundle", out.name)
                if (emptyFolder) assertTrue(out.listFiles()!!.isEmpty()) else assertEquals("payload", File(out, "file.txt").readText())
                assertEquals(1, dst.listFiles()!!.size)
            }
        }
    }

    @Test fun emptyZipAndTgzUseArchiveBaseWithoutCompoundSuffix() {
        val dst = tmp.newFolder()
        assertEquals("empty", Archive.extract(zip("empty.zip"), dst, TransferStats()).name)
        val input = tmp.newFile().apply { writeText("x") }
        val tgz = File(tmp.newFolder(), "bundle.tgz")
        Archive.compress(listOf(input), tgz, TransferStats(), ArchiveFormat.TAR_GZ)
        assertEquals("bundle", Archive.extract(tgz, tmp.newFolder(), TransferStats()).name)
    }

    @Test fun traversalFailureDoesNotModifyExistingDirectoryOrLeaveStaging() {
        val dst = tmp.newFolder()
        val keep = File(dst, "safe").apply { mkdir() }
        File(keep, "keep.txt").writeText("original")
        val stats = TransferStats()
        assertTrue(runCatching { Archive.extract(zip("safe.zip", "safe/good.txt", "../escape.txt"), dst, stats) }.isFailure)
        assertEquals(listOf("safe"), dst.list()!!.toList())
        assertEquals(listOf("keep.txt"), keep.list()!!.toList())
        assertEquals(0, stats.doneFiles)
    }
}
