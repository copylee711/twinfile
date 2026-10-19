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
import app.twinfile.update.Updater
import app.twinfile.priv.RootShell
import app.twinfile.priv.ShellSource
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
    /** File waiting in the 打开方式 dialog. */
    var openRequest by mutableStateOf<OpenRequest?>(null)
    /** Bumped when something outside asks to show a file; the main screen comes to the front. */
    var locateTick by mutableIntStateOf(0)
    /** Bumped when storage usage should be re-read for the title line. */
    var storageVersion by mutableIntStateOf(0)

    val active get() = panes[focus]
    val other get() = panes[1 - focus]

    init {
        val internal = Volumes.internalPath()
        open(panes[0], prefs.homeLeft.ifEmpty { internal }, push = false)
        open(panes[1], prefs.homeRight.ifEmpty { internal }, push = false)
        if (prefs.autoUpdate) viewModelScope.launch {
            kotlinx.coroutines.delay(2500) // let the first listing settle
            checkUpdate(manual = false)
        }
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

    // ---- updates ----

    /** A newer release to offer; non-null shows the update dialog. */
    var update by mutableStateOf<Updater.Release?>(null)
    /** Download progress 0..1 while fetching the APK. */
    var updateProgress by mutableStateOf<Float?>(null)
    /** Why the last download or install attempt stopped; shown inside the dialog. */
    var updateError by mutableStateOf<String?>(null)
    private var updateJob: kotlinx.coroutines.Job? = null

    /** Silent at startup; [manual] also reports "up to date" and errors. */
    fun checkUpdate(manual: Boolean) {
        viewModelScope.launch {
            if (manual) say("正在检查更新…")
            try {
                val r = withContext(Dispatchers.IO) { Updater.check() }
                if (r != null) update = r else if (manual) say("已是最新版本 v${Updater.current}")
            } catch (e: Exception) {
                FileLog.w("update check", e)
                if (manual) say("检查更新失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun downloadUpdate(r: Updater.Release) {
        if (updateJob?.isActive == true) return
        updateProgress = 0f
        updateError = null
        updateJob = viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(app, r) { done, total ->
                        if (total > 0) updateProgress = (done.toFloat() / total).coerceIn(0f, 1f)
                        updateJob?.isActive != false
                    }
                }
                updateProgress = null
                if (!Updater.install(app, apk)) updateError = "请在打开的设置里允许 TwinFile 安装应用，然后再点「更新」"
            } catch (e: Exception) {
                updateProgress = null
                if (e !is java.util.concurrent.CancellationException) {
                    FileLog.w("update download", e)
                    updateError = when (e) {
                        is java.net.SocketException, is java.net.SocketTimeoutException, is java.net.UnknownHostException ->
                            "连接 GitHub 下载失败（${e.message ?: e.javaClass.simpleName}）。可以重试，或在浏览器中下载"
                        else -> e.message ?: "下载失败"
                    }
                }
            }
        }
    }

    fun cancelUpdate() {
        updateJob?.cancel()
        updateProgress = null
        updateError = null
        update = null
    }

    // ---- navigation ----

    fun open(pane: PaneState, path: String, requested: FileSource = pane.source, push: Boolean = true) {
        val source = route(requested, path)
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

    /**
     * Android/data and obb go through root/Shizuku when enabled; once back in ordinary folders
     * the local source takes over again (faster, with thumbnails and trash).
     */
    private fun route(source: FileSource, path: String): FileSource {
        val priv = app.priv ?: return source
        val restricted = isRestricted(path)
        return when {
            source === app.local && restricted -> priv
            source === priv && !restricted && VOLUME.matches(path) -> app.local
            else -> source
        }
    }

    private fun isRestricted(path: String) = Volumes.isRestricted(path)

    fun setPrivMode(mode: String) {
        fun apply() {
            prefs.update { privMode = mode }
            app.applyPrivMode()
            // Panes showing the old privileged source move to the new one (or back to local).
            panes.forEach { p -> if (p.source.id == ShellSource.ID) open(p, p.path, app.local, push = false) else refresh(p) }
            say(when (mode) { "ROOT" -> "已开启 Root 访问"; "SHIZUKU" -> "已开启 Shizuku 访问"; else -> "已关闭高级访问" })
        }
        when (mode) {
            "ROOT" -> viewModelScope.launch {
                say("正在请求 Root 权限…")
                if (withContext(Dispatchers.IO) { RootShell.available() }) apply() else say("没有获得 Root 权限")
            }
            "SHIZUKU" -> {
                if (!rikka.shizuku.Shizuku.pingBinder()) return say("Shizuku 未运行：请先安装 Shizuku 并启动服务")
                if (rikka.shizuku.Shizuku.isPreV11()) return say("Shizuku 版本过旧，请更新")
                if (rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) return apply()
                val listener = object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this)
                        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) apply() else say("没有获得 Shizuku 授权")
                    }
                }
                rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener)
                rikka.shizuku.Shizuku.requestPermission(SHIZUKU_REQUEST)
            }
            else -> apply()
        }
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
                pane.error = if (it.size <= 1 && source === app.local && isRestricted(path))
                    "Android 11 起，系统不允许应用直接访问 Android/data 和 obb。\n在「设置 → 高级访问」开启 Shizuku 或 Root 后即可浏览。"
                else null
                val reveal = pane.reveal?.let { n -> pane.visible.indexOfFirst { e -> e.name == n } }?.takeIf { i -> i >= 0 }
                pane.reveal = null
                if (reveal != null) pane.listState.scrollToItem(maxOf(0, reveal - 3))
                if (!keepScroll) {
                    if (reveal == null) pane.listState.scrollToItem(restore.first, restore.second)
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
            pane.reveal = Paths.name(container)
            open(pane, Paths.parent(container) ?: "/", app.local)
            pane.flash = Paths.name(container)
            pane.flashTick++
            return
        }
        val parent = Paths.parent(pane.path) ?: return
        val from = Paths.name(pane.path)
        // Land on the folder we came from.
        pane.reveal = from
        open(pane, parent)
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
            Kinds.of(e).textual || (e.size in 0..64 * 1024 && Paths.extension(e.name).isEmpty()) ->
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

    /** Breadcrumb segments for the title bar: (label, path), root first. */
    fun crumbs(pane: PaneState): List<Pair<String, String>> {
        val p = pane.path
        val src = pane.source
        val internal = Volumes.internalPath()
        val (rootLabel, rootPath) = when {
            src.container != null -> Paths.name(src.container!!) to "/"
            src.phonePaths && Paths.isInside(p, internal) -> "内部存储" to internal
            src.phonePaths -> "根目录" to "/"
            else -> src.title to "/"
        }
        val out = mutableListOf(rootLabel to rootPath)
        var cur = rootPath
        p.removePrefix(rootPath).split('/').filter { it.isNotEmpty() }.forEach { seg ->
            cur = Paths.join(cur, seg)
            out += seg to cur
        }
        return out
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
        if (!toSamePane && from.source === to.source && from.path == to.path && move) return say("两个窗口是同一个文件夹")
        from.selected = emptySet()
        runTransfer(from.source, from.path, entries, to.source, to.path, move)
    }

    private fun runTransfer(src: FileSource, srcDir: String, entries: List<FEntry>, dst: FileSource, dstDir: String, move: Boolean) {
        val paths = entries.map { Paths.join(srcDir, it.name) }
        val verb = if (move) "移动" else "复制"
        Ops.start("$verb ${entries.size} 项到 ${Paths.name(dstDir)}", { job ->
            Transfer(src, paths, dst, dstDir, move, prefs.keepMtime, job.stats) { job.ask(it) }.run()
        }) {
            viewModelScope.launch { refreshShowing(src, srcDir); refreshShowing(dst, dstDir) }
        }
    }

    // ---- clipboard (single-pane mode) ----

    /** Files picked with 复制/剪切, waiting to be pasted into some folder. */
    data class Clip(val source: FileSource, val dir: String, val entries: List<FEntry>, val move: Boolean)

    var clip by mutableStateOf<Clip?>(null)

    fun toClip(pane: PaneState, entries: List<FEntry>, move: Boolean) {
        clip = Clip(pane.source, pane.path, entries, move)
        pane.selected = emptySet()
    }

    fun paste(pane: PaneState) {
        val c = clip ?: return
        if (c.source === pane.source && c.dir == pane.path) {
            if (c.move) return say("文件已在这个文件夹")
            // Copy into the same folder: duplicates with numbered names.
        } else if (c.source === pane.source && c.entries.any { Paths.isInside(pane.path, Paths.join(c.dir, it.name)) && it.isDir }) {
            return say("不能粘贴到它自己里面")
        }
        if (c.move) clip = null
        runTransfer(c.source, c.dir, c.entries, pane.source, pane.path, c.move)
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
            pane.reveal = Paths.name(path)
            open(pane, Paths.parent(path) ?: "/", src)
            // Light it up instead of selecting, so a tap still opens it.
            pane.flash = Paths.name(path)
            pane.flashTick++
        }
    }

    private companion object {
        val ARCHIVES = setOf("zip", "apks", "xapk", "jar")
        val VOLUME = Regex("""^/storage/(emulated/\d+|[^/]+)(/.*)?$""")
        const val SHIZUKU_REQUEST = 7
    }
}
