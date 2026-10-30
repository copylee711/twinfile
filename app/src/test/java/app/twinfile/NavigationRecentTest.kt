package app.twinfile

import app.twinfile.fs.*
import app.twinfile.ui.Loc
import app.twinfile.ui.PaneScrollMemory
import app.twinfile.ui.ScrollPosition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NavigationRecentTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun restoresExactOffsetAndFollowsAnchorAfterNewFilesArrive() {
        val memory = PaneScrollMemory()
        val directory = Loc("local", "/storage/Documents")
        val keys = (0..50).map { "doc$it" }
        memory.save(directory, ScrollPosition(25, 37, "doc25"))
        assertEquals(ScrollPosition(25, 37, "doc25"), memory.restore(directory, keys, true))
        assertEquals(ScrollPosition(26, 37, "doc25"), memory.restore(directory, listOf("new") + keys, true))
        assertEquals(ScrollPosition(), memory.restore(directory, keys, false))
        assertEquals(ScrollPosition(), memory.restore(directory, emptyList(), true))
    }

    @Test fun paneAndSourcePositionsAreIndependentAndDeletedAnchorsAreClamped() {
        val first = PaneScrollMemory(2)
        val second = PaneScrollMemory()
        val local = Loc("local", "/same")
        val network = Loc("smb:test", "/same")
        first.save(local, ScrollPosition(100, 7, "removed"))
        first.save(network, ScrollPosition(0, 9, "a"))
        assertEquals(ScrollPosition(1, 0, "b"), first.restore(local, listOf("a", "b"), true))
        assertEquals(ScrollPosition(0, 9, "a"), first.restore(network, listOf("a", "b"), true))
        assertEquals(ScrollPosition(), second.restore(local, listOf("a", "b"), true))
        first.save(Loc("local", "/other"), ScrollPosition())
        assertEquals(ScrollPosition(), first.restore(local, listOf("a", "b"), true))
    }

    @Test fun recentCollectionKeepsDifferentPathsAndUsesNewerSystemAddedTime() {
        val index = RecentFileIndex(2)
        fun item(path: String, time: Long) = RecentFile(path, FEntry("same.docx", false, 10, 1), time)
        index.offer(item("/a/same.docx", 100))
        index.offer(item("/b/same.docx", 200))
        index.offer(item("/c/same.docx", 50))
        index.offer(item("/a/same.docx", 1)) // filesystem pass must not undo DATE_ADDED
        assertEquals(listOf("/b/same.docx", "/a/same.docx"), index.snapshot().map { it.path })
        index.offer(item("/c/same.docx", 300))
        assertEquals(listOf(300L, 200L), index.snapshot().map { it.changedAt })
    }

    @Test fun filesystemPassFindsUnindexedDocumentsAndExcludesPrivateAndTrashData() {
        val root = tmp.newFolder()
        fun file(path: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(path) }
        file("Download/from-chrome.png")
        file("Tencent/Downloads/from-wechat.docx")
        file("Android/media/public.txt")
        file("Android/data/private.txt")
        file("Android/obb/private.bin")
        file(".TwinFileTrash/id/deleted.docx")
        file(".hidden/hidden.txt")
        val index = RecentFileIndex()
        index.scan(listOf(root), false, {})
        assertEquals(setOf("from-chrome.png", "from-wechat.docx", "public.txt"), index.snapshot().map { it.entry.name }.toSet())
        val hidden = RecentFileIndex()
        hidden.scan(listOf(root), true, {})
        assertEquals(4, hidden.snapshot().size)
        assertFalse(RecentFileIndex.allowed(File(tmp.root, "outside.txt"), listOf(root), true))
    }

    @Test fun filesystemPassIsCancellableWithoutDiscardingIndexedResults() {
        val index = RecentFileIndex()
        index.offer(RecentFile("/already.docx", FEntry("already.docx", false, 1, 100), 100))
        val error = runCatching { index.scan(listOf(tmp.root), false, { throw java.util.concurrent.CancellationException() }) }.exceptionOrNull()
        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals("/already.docx", index.snapshot().single().path)
    }

    @Test fun openHistoryHasItsOwnBoundAndRecordingCanBeDisabled() {
        val settings = tmp.newFolder()
        val prefs = Prefs(settings)
        assertTrue(prefs.stickyParent)
        assertFalse(prefs.recentHintShown)
        prefs.addRecent("local", "/old.docx", false)
        repeat(100) { prefs.addRecent("local", "/folder$it", true) }
        assertEquals("/old.docx", prefs.openedFiles.single().path)
        repeat(205) { prefs.addRecent("local", "/document$it.docx", false) }
        assertEquals(200, prefs.openedFiles.size)
        prefs.addRecent("local", "/document100.docx", false)
        assertEquals(200, prefs.openedFiles.size)
        assertEquals("/document100.docx", prefs.openedFiles.first().path)
        prefs.update { quickPaths["Download"] = "/custom/downloads"; stickyParent = false; recentHintShown = true; recordOpened = false }
        prefs.addRecent("local", "/disabled.docx", false)
        assertFalse(prefs.openedFiles.any { it.path == "/disabled.docx" })
        assertFalse(prefs.recent.any { it.path == "/disabled.docx" })
        val saved = File(settings, "settings.json")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (runCatching { !org.json.JSONObject(saved.readText()).optBoolean("recordOpened", true) }.getOrDefault(false)) break
            Thread.sleep(10)
        }
        val reloaded = Prefs(settings)
        assertEquals("/custom/downloads", reloaded.quickPaths["Download"])
        assertFalse(reloaded.quickPaths.containsKey("Pictures"))
        assertTrue(reloaded.recentHintShown)
        assertFalse(reloaded.stickyParent)
        assertFalse(reloaded.recordOpened)
        assertEquals(200, reloaded.openedFiles.size)
        assertEquals("/document100.docx", reloaded.openedFiles.first().path)
    }
}
