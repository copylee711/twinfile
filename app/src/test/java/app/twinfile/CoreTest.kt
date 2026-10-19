package app.twinfile

import app.twinfile.fs.FEntry
import app.twinfile.fs.LocalSource
import app.twinfile.fs.NaturalOrder
import app.twinfile.fs.Paths
import app.twinfile.fs.Root
import app.twinfile.fs.SortBy
import app.twinfile.fs.SortSpec
import app.twinfile.fs.sortedBy
import app.twinfile.ops.Archive
import app.twinfile.ops.Resolution
import app.twinfile.ops.Transfer
import app.twinfile.ops.TransferStats
import app.twinfile.ops.Trash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun local() = LocalSource({ listOf(Root("tmp", tmp.root.path)) })

    @Test fun paths() {
        assertEquals("/a/b", Paths.join("/a", "b"))
        assertEquals("/b", Paths.join("/", "b"))
        assertEquals("/a", Paths.parent("/a/b"))
        assertEquals("/", Paths.parent("/a"))
        assertEquals(null, Paths.parent("/"))
        assertEquals("/a/c", Paths.normalize("/a/./b/../c/"))
        assertTrue(Paths.isInside("/a/b/c", "/a/b"))
        assertFalse(Paths.isInside("/a/bc", "/a/b"))
        assertEquals("x (1).txt", Paths.numbered("x.txt", 1, false))
        assertEquals("x (3).txt", Paths.numbered("x (2).txt", 3, false))
        assertEquals("v1.2 (1)", Paths.numbered("v1.2", 1, true))
        assertEquals("a (2).txt", Paths.freeName("a.txt", false) { it == "a.txt" || it == "a (1).txt" })
    }

    @Test fun naturalSort() {
        val names = listOf("file10", "File2", "file1", "a", "b")
        assertEquals(listOf("a", "b", "file1", "File2", "file10"), names.sortedWith(NaturalOrder))
    }

    @Test fun foldersFirstAndReverse() {
        val es = listOf(FEntry("z", true, -1, 1), FEntry("a.txt", false, 5, 3), FEntry("b.txt", false, 9, 2))
        assertEquals(listOf("z", "a.txt", "b.txt"), es.sortedBy(SortSpec()).map { it.name })
        assertEquals(listOf("z", "b.txt", "a.txt"), es.sortedBy(SortSpec(SortBy.NAME, true)).map { it.name })
        assertEquals(listOf("z", "b.txt", "a.txt"), es.sortedBy(SortSpec(SortBy.SIZE)).map { it.name })
    }

    private fun tree(root: File) {
        File(root, "d/sub").mkdirs()
        File(root, "d/one.txt").writeText("one")
        File(root, "d/sub/two.txt").writeText("two")
        File(root, "top.txt").writeText("top")
    }

    @Test fun copyAndConflicts() {
        val src = tmp.newFolder("src").also(::tree)
        val dst = tmp.newFolder("dst")
        val s = local()
        Transfer(s, listOf("${src.path}/d", "${src.path}/top.txt"), s, dst.path, move = false) { null }.run()
        assertEquals("two", File(dst, "d/sub/two.txt").readText())
        assertEquals("top", File(dst, "top.txt").readText())

        // Same names again: keep both.
        val stats = TransferStats()
        Transfer(s, listOf("${src.path}/top.txt"), s, dst.path, false, true, stats) { Resolution.RENAME to true }.run()
        assertEquals("top", File(dst, "top (1).txt").readText())

        // Overwrite merges folders.
        File(src, "d/new.txt").writeText("new")
        File(src, "d/one.txt").writeText("ONE")
        Transfer(s, listOf("${src.path}/d"), s, dst.path, move = false) { Resolution.OVERWRITE to true }.run()
        assertEquals("ONE", File(dst, "d/one.txt").readText())
        assertEquals("new", File(dst, "d/new.txt").readText())
        assertEquals("two", File(dst, "d/sub/two.txt").readText())
    }

    @Test fun copyIntoItselfFails() {
        val src = tmp.newFolder("src").also(::tree)
        val s = local()
        val r = runCatching { Transfer(s, listOf("${src.path}/d"), s, "${src.path}/d/sub", move = false) { null }.run() }
        assertTrue(r.isFailure)
    }

    @Test fun duplicateInSameFolder() {
        val src = tmp.newFolder("src").also(::tree)
        val s = local()
        Transfer(s, listOf("${src.path}/top.txt"), s, src.path, move = false) { null }.run()
        assertEquals("top", File(src, "top (1).txt").readText())
    }

    @Test fun moveSkipKeepsSource() {
        val src = tmp.newFolder("src").also(::tree)
        val dst = tmp.newFolder("dst")
        File(dst, "top.txt").writeText("old")
        val s = local()
        Transfer(s, listOf("${src.path}/top.txt", "${src.path}/d"), s, dst.path, move = true) { Resolution.SKIP to false }.run()
        assertTrue(File(src, "top.txt").exists())
        assertEquals("old", File(dst, "top.txt").readText())
        assertFalse(File(src, "d").exists())
        assertEquals("two", File(dst, "d/sub/two.txt").readText())
    }

    @Test fun trashRoundTrip() {
        val vol = tmp.newFolder("vol").also(::tree)
        val trash = Trash { vol.path }
        trash.moveToTrash("${vol.path}/d")
        assertFalse(File(vol, "d").exists())
        val items = trash.items(listOf(vol.path))
        assertEquals(1, items.size)
        assertEquals("${vol.path}/d", items[0].originalPath)
        File(vol, "d").mkdir() // name taken meanwhile
        val back = trash.restore(items[0])
        assertEquals(File(vol, "d (1)"), File(back))
        assertEquals("two", File(vol, "d (1)/sub/two.txt").readText())
        assertTrue(trash.items(listOf(vol.path)).isEmpty())
    }

    @Test fun zipRoundTrip() {
        val src = tmp.newFolder("src").also(::tree)
        val zip = File(tmp.root, "out.zip")
        Archive.compress(listOf(File(src, "d"), File(src, "top.txt")), zip, TransferStats())
        val out = Archive.extract(zip, tmp.newFolder("x"), TransferStats())
        assertEquals("out", out.name)
        assertEquals("two", File(out, "d/sub/two.txt").readText())
        assertEquals("top", File(out, "top.txt").readText())
    }
}
