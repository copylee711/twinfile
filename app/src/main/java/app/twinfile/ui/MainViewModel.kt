package app.twinfile.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.twinfile.Bookmark
import app.twinfile.TwinApp
import app.twinfile.fs.FEntry
import app.twinfile.fs.FileSource
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import app.twinfile.fs.SortSpec
import app.twinfile.fs.Volumes
import app.twinfile.ops.Ops
import app.twinfile.ops.Transfer
import app.twinfile.util.FileLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.twinfile.fs.ZipSource
import java.io.File
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as TwinApp
    val prefs = app.prefs
    val panes = listOf(
        PaneState(0, app.local) { prefs.showHidden },
        PaneState(1, app.local) { prefs.showHidden },
    )
    var focus by mutableIntStateOf(0)
    /** One-line message shown at the bottom (snackbar). */
    var toast by mutableStateOf<String?>(null)
    /** Full-screen image/text viewer, if open. */
    var viewer by mutableStateOf<Viewer?>(null)
    /** Bumped when storage usage should be re-read for the title line. */
    var storageVersion by mutableIntStateOf(0)

    val active get() = panes[focus]
    val other get() = panes[1 - focus]

    init {
        val internal = Volumes.internalPath()
        open(panes[0], prefs.homeLeft.ifEmpty { internal }, push = false)
        open(panes[1], prefs.homeRight.ifEmpty { internal }, push = false)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.trash.autoClean(Volumes.list(app).map { it.path }, prefs.trashAutoCleanDays)
            } catch (e: Exception) {
                FileLog.w("trash auto-clean", e)
            }
        }
    }

    fun say(msg: String) {
        toast = msg
    }

    // ---- navigation ----

    fun open(pane: PaneState, path: String, source: FileSource = pane.source, push: Boolean = true) {
        val target = Loc(source.id, path)
        if (pane.path.isNotEmpty()) {
            pane.scrollMemory[pane.loc] = pane.listState.firstVisibleItemIndex to pane.listState.firstVisibleItemScrollOffset
            if (push && target != pane.loc) {
                pane.back.addLast(pane.loc)
                pane.forward.clear()
                pane.historyVersion++
            }
        }
        val sameFolder = target == pane.loc
        pane.pendingDir = if (pane.path.isNotEmpty() && target.source == pane.source.id && Paths.isInside(path, pane.path)) 1 else -1
        if (push && !sameFolder && source.container == null) prefs.addRecent(source.id, path, true)
        pane.source = source
        pane.path = path
        pane.selected = emptySet()
        if (!sameFolder) pane.filter = ""
        pane.sort = sortFor(pane)
        load(pane, restore = pane.scrollMemory[target] ?: (0 to 0), keepScroll = sameFolder)
    }

    private fun load(pane: PaneState, restore: Pair<Int, Int>, keepScroll: Boolean) {
        val gen = ++pane.loadGen
        val source = pane.source
        val path = pane.path
        pane.loading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(source.list(path))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (gen != pane.loadGen) return@launch
            pane.loading = false
            result.onSuccess {
                pane.raw = it
                pane.error = null
                if (!keepScroll) {
                    pane.listState.scrollToItem(restore.first, restore.second)
                    pane.navDir = pane.pendingDir
                    pane.navTick++
                }
            }.onFailure {
                if (!keepScroll) { pane.navDir = pane.pendingDir; pane.navTick++ }
                FileLog.w("list $path", it)
                pane.raw = emptyList()
                pane.error = it.message ?: "无法打开"
            }
        }
    }

    fun refresh(pane: PaneState) = load(pane, 0 to 0, keepScroll = true)

    /** Reloads every pane that shows [dir] (after an operation changed it). */
    fun refreshShowing(source: FileSource, vararg dirs: String) {
        storageVersion++
        for (p in panes) if (p.source === source && dirs.any { it == p.path }) refresh(p)
    }

    fun refreshAll() {
        storageVersion++
        panes.forEach { refresh(it) }
    }

    fun canGoUp(pane: PaneState) = Paths.parent(pane.path) != null || pane.source.container != null

    fun up(pane: PaneState) {
        val container = pane.source.container
        if (pane.path == "/" && container != null) {
            // Leave the archive to the folder that holds it.
            open(pane, Paths.parent(container) ?: "/", app.local)
            pane.selected = setOf(Paths.name(container))
            return
        }
        val parent = Paths.parent(pane.path) ?: return
        val from = Paths.name(pane.path)
        open(pane, parent)
        // Land on the folder we came from.
        viewModelScope.launch {
            kotlinx.coroutines.delay(60)
            val i = pane.visible.indexOfFirst { it.name == from }
            if (i >= 0) pane.listState.scrollToItem(maxOf(0, i - 3))
        }
    }

    fun back(pane: PaneState) {
        val loc = pane.back.removeLastOrNull() ?: return
        pane.forward.addLast(pane.loc)
        pane.historyVersion++
        go(pane, loc)
    }

    fun forward(pane: PaneState) {
        val loc = pane.forward.removeLastOrNull() ?: return
        pane.back.addLast(pane.loc)
        pane.historyVersion++
        go(pane, loc)
    }

    private fun go(pane: PaneState, loc: Loc) {
        val src = app.source(loc.source) ?: return
        open(pane, loc.path, src, push = false)
    }

    /** The other pane jumps to this pane's folder. */
    fun syncOther() {
        open(other, active.path, active.source)
    }

    fun swapPanes() {
        val a = panes[0].loc
        val b = panes[1].loc
        go(panes[0], b)
        go(panes[1], a)
    }

    fun enter(pane: PaneState, e: FEntry) {
        focus = pane.index
        if (e.isDir) {
            open(pane, Paths.join(pane.path, e.name))
        }
    }

    /** Opens a file: built-in viewers for images/text, archives as folders, else [external]. */
    fun openFile(pane: PaneState, e: FEntry, external: (File) -> Unit) {
        focus = pane.index
        val path = Paths.join(pane.path, e.name)
        val local = pane.source === app.local
        if (local) prefs.addRecent("local", path, false)
        when {
            Kinds.of(e) == Kind.IMAGE -> {
                val images = pane.visible.filter { !it.isDir && Kinds.of(it) == Kind.IMAGE }
                viewer = Viewer.Images(pane.source, pane.path, images, images.indexOfFirst { it.name == e.name }.coerceAtLeast(0))
            }
            Kinds.of(e) == Kind.TEXT || (e.size in 0..64 * 1024 && Paths.extension(e.name).isEmpty()) ->
                viewer = Viewer.Text(pane.source, path, e)
            Paths.extension(e.name) in ARCHIVES -> withLocalCopy(pane, e) { openArchive(pane, it) }
            else -> withLocalCopy(pane, e, external)
        }
    }

    /** Runs [block] with a local file for [e], downloading remote files to the cache first. */
    fun withLocalCopy(pane: PaneState, e: FEntry, block: (File) -> Unit) {
        if (pane.source === app.local) return block(File(Paths.join(pane.path, e.name)))
        val source = pane.source
        val path = Paths.join(pane.path, e.name)
        say("正在读取 ${e.name}…")
        io("读取失败") {
            val dir = File(app.cacheDir, "open/${(source.id + path).hashCode().toUInt()}").apply { mkdirs() }
            val f = File(dir, e.name)
            if (!(f.isFile && f.length() == e.size && f.lastModified() == e.mtime)) {
                source.openRead(path).use { input -> f.outputStream().use { input.copyTo(it, 256 * 1024) } }
                if (e.mtime > 0) f.setLastModified(e.mtime)
            }
            withContext(Dispatchers.Main) { block(f) }
        }
    }

    fun openArchive(pane: PaneState, file: File) {
        io("无法打开压缩包") {
            val src = app.sources["zip:${file.path}"] ?: ZipSource(file).also { app.sources[it.id] = it }
            withContext(Dispatchers.Main) { open(pane, "/", src) }
        }
    }

    /** Short label for a pane: volume names instead of "0", archive names at their root. */
    fun label(pane: PaneState): String {
        val p = pane.path
        if (p == "/") return pane.source.container?.let { Paths.name(it) } ?: if (pane.source === app.local) "根目录" else pane.source.title
        if (pane.source === app.local && p == Volumes.internalPath()) return "内部存储"
        return Paths.name(p)
    }

    // ---- sorting ----

    private fun sortKey(pane: PaneState) = "${pane.source.id}:${pane.path}"

    private fun sortFor(pane: PaneState): SortSpec =
        prefs.folderSort[sortKey(pane)] ?: if (pane.index == 0) prefs.sortLeft else prefs.sortRight

    fun hasFolderSort(pane: PaneState) = prefs.folderSort.containsKey(sortKey(pane))

    fun setSort(pane: PaneState, spec: SortSpec, folderOnly: Boolean) {
        prefs.update {
            if (folderOnly) folderSort[sortKey(pane)] = spec
            else {
                folderSort.remove(sortKey(pane))
                if (pane.index == 0) sortLeft = spec else sortRight = spec
            }
        }
        pane.sort = spec
    }

    // ---- selection ----

    fun toggle(pane: PaneState, name: String) {
        focus = pane.index
        pane.selected = if (name in pane.selected) pane.selected - name else pane.selected + name
    }

    fun select(pane: PaneState, name: String, on: Boolean) {
        pane.selected = if (on) pane.selected + name else pane.selected - name
    }

    fun selectAll(pane: PaneState) {
        pane.selected = pane.visible.map { it.name }.toSet()
    }

    fun invert(pane: PaneState) {
        pane.selected = pane.visible.map { it.name }.toSet() - pane.selected
    }

    fun clearSelection(pane: PaneState) {
        pane.selected = emptySet()
    }

    /** Selects everything between the first and last selected items. */
    fun selectRange(pane: PaneState) {
        val idx = pane.visible.withIndex().filter { it.value.name in pane.selected }.map { it.index }
        if (idx.size < 2) {
            say("先选中两个文件，再选择它们之间的全部")
            return
        }
        pane.selected = pane.visible.subList(idx.first(), idx.last() + 1).map { it.name }.toSet()
    }

    enum class Period { DAY, WEEK, MONTH, YEAR }

    /** Selects files modified in the same day/week/... as the already selected ones. */
    fun selectSamePeriod(pane: PaneState, period: Period) {
        val ref = pane.selectedEntries().ifEmpty {
            say("先选中一个文件作为参照")
            return
        }
        fun key(t: Long): Int {
            val c = Calendar.getInstance().apply { timeInMillis = t }
            val y = c.get(Calendar.YEAR)
            return when (period) {
                Period.DAY -> y * 1000 + c.get(Calendar.DAY_OF_YEAR)
                Period.WEEK -> c.get(Calendar.YEAR) * 100 + c.get(Calendar.WEEK_OF_YEAR)
                Period.MONTH -> y * 100 + c.get(Calendar.MONTH)
                Period.YEAR -> y
            }
        }
        val keys = ref.map { key(it.mtime) }.toSet()
        pane.selected = pane.selected + pane.visible.filter { key(it.mtime) in keys }.map { it.name }
    }

    fun selectByKeyword(pane: PaneState, keyword: String) {
        if (keyword.isBlank()) return
        pane.selected = pane.selected + pane.visible.filter { it.name.contains(keyword, true) }.map { it.name }
    }

    // ---- file operations ----

    private fun io(errorPrefix: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                FileLog.w(errorPrefix, e)
                say(if (e is FsException) e.message!! else "$errorPrefix：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun create(pane: PaneState, name: String, folder: Boolean) {
        val n = name.trim()
        if (n.isEmpty() || '/' in n) return say("名称无效")
        val path = Paths.join(pane.path, n)
        io(if (folder) "新建文件夹失败" else "新建文件失败") {
            if (folder) pane.source.mkdir(path) else pane.source.createFile(path)
            withContext(Dispatchers.Main) {
                refreshShowing(pane.source, pane.path)
                pane.selected = setOf(n)
            }
        }
    }

    fun rename(pane: PaneState, e: FEntry, newName: String) {
        val n = newName.trim()
        if (n.isEmpty() || '/' in n || n == e.name) return
        io("重命名失败") {
            pane.source.rename(Paths.join(pane.path, e.name), n)
            withContext(Dispatchers.Main) {
                refreshShowing(pane.source, pane.path)
                pane.selected = setOf(n)
            }
        }
    }

    /** Swaps the names of two selected entries. */
    fun swapNames(pane: PaneState, a: FEntry, b: FEntry) {
        io("交换文件名失败") {
            val s = pane.source
            val tmp = Paths.freeName(".twinfile-swap", false) { s.exists(Paths.join(pane.path, it)) }
            s.rename(Paths.join(pane.path, a.name), tmp)
            s.rename(Paths.join(pane.path, b.name), a.name)
            s.rename(Paths.join(pane.path, tmp), b.name)
            withContext(Dispatchers.Main) { refreshShowing(s, pane.path) }
        }
    }

    fun canTrash(pane: PaneState): Boolean =
        prefs.trashEnabled && pane.source === app.local && app.trash.supports(pane.path + "/x")

    fun delete(pane: PaneState, entries: List<FEntry>, toTrash: Boolean) {
        val source = pane.source
        val dir = pane.path
        val paths = entries.map { Paths.join(dir, it.name) }
        pane.selected = emptySet()
        val title = if (toTrash) "移到回收站" else "删除"
        Ops.start("$title ${entries.size} 项", { job ->
            job.stats.totalFiles = paths.size
            for (p in paths) {
                if (job.stats.cancelled) break
                job.stats.current = Paths.name(p)
                if (toTrash) app.trash.moveToTrash(p)
                else source.delete(p) { _, name -> job.stats.current = name; !job.stats.cancelled }
                job.stats.doneFiles++
            }
        }) { viewModelScope.launch { refreshShowing(source, dir) } }
    }

    /** Copies or moves [entries] of [from] into the other pane's folder. */
    fun transfer(from: PaneState, entries: List<FEntry>, move: Boolean, toSamePane: Boolean = false) {
        val to = if (toSamePane) from else panes[1 - from.index]
        val src = from.source
        val dst = to.source
        val srcDir = from.path
        val dstDir = to.path
        if (!toSamePane && src === dst && srcDir == dstDir && move) return say("两个窗口是同一个文件夹")
        val paths = entries.map { Paths.join(srcDir, it.name) }
        from.selected = emptySet()
        val verb = if (move) "移动" else "复制"
        Ops.start("$verb ${entries.size} 项到 ${Paths.name(dstDir)}", { job ->
            Transfer(src, paths, dst, dstDir, move, prefs.keepMtime, job.stats) { job.ask(it) }.run()
        }) {
            viewModelScope.launch { refreshShowing(src, srcDir); refreshShowing(dst, dstDir) }
        }
    }

    // ---- bookmarks & home ----

    fun addBookmark(pane: PaneState, entry: FEntry? = null) {
        val path = if (entry != null) Paths.join(pane.path, entry.name) else pane.path
        if (prefs.bookmarks.any { it.source == pane.source.id && it.path == path }) return say("已在书签中")
        prefs.update { bookmarks.add(0, Bookmark(Paths.name(path), pane.source.id, path)) }
        say("已添加书签")
    }

    fun setHome(pane: PaneState) {
        prefs.update { if (pane.index == 0) homeLeft = pane.path else homeRight = pane.path }
        say("已设为${if (pane.index == 0) "左" else "右"}窗口首页")
    }

    /** Shows a source's home folder in [pane]. */
    fun openSource(pane: PaneState, sourceId: String) {
        val src = app.source(sourceId) ?: return say("位置不可用")
        focus = pane.index
        open(pane, src.home(), src)
    }

    fun openBookmark(pane: PaneState, sourceId: String, path: String, isDir: Boolean = true) {
        val src = app.source(sourceId) ?: return say("位置不可用")
        focus = pane.index
        if (isDir) open(pane, path, src)
        else {
            open(pane, Paths.parent(path) ?: "/", src)
            pane.selected = setOf(Paths.name(path))
        }
    }

    private companion object {
        val ARCHIVES = setOf("zip", "apks", "xapk", "jar")
    }
}
