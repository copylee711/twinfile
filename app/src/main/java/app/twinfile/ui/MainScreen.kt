package app.twinfile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.ControlPointDuplicate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.twinfile.fs.FEntry
import app.twinfile.fs.Paths
import app.twinfile.fs.Volumes
import app.twinfile.ops.Ops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Dialogs the main screen can show; one at a time. */
sealed interface Dlg {
    data class NewItem(val pane: PaneState) : Dlg
    data class Rename(val pane: PaneState, val e: FEntry) : Dlg
    data class Jump(val pane: PaneState) : Dlg
    data class Filter(val pane: PaneState) : Dlg
    data class Sort(val pane: PaneState) : Dlg
    data class Keyword(val pane: PaneState) : Dlg
    data class Props(val pane: PaneState, val entries: List<FEntry>) : Dlg
    data class Delete(val pane: PaneState, val entries: List<FEntry>) : Dlg
    data class Menu(val pane: PaneState, val entries: List<FEntry>, val locate: (() -> Unit)? = null) : Dlg
    data class Compress(val pane: PaneState, val entries: List<FEntry>) : Dlg
}

enum class Screen { MAIN, TRASH, APPS, NETWORK, CLOUD, PC, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, navigate: (Screen) -> Unit, openFile: (PaneState, FEntry) -> Unit,
               fileActions: FileActions) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    val sheet = remember { PullSheetState() }
    val prefs = vm.prefs

    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            vm.toast = null
            snack.showSnackbar(it)
        }
    }

    val active = vm.active
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(!drawer.isOpen && active.selecting) { vm.clearSelection(active) }
    BackHandler(!drawer.isOpen && !active.selecting && active.filter.isNotEmpty()) { active.filter = "" }
    BackHandler(!drawer.isOpen && !active.recentView && !active.selecting && active.filter.isEmpty() && vm.canGoUp(active)) { vm.up(active) }

    BackHandler(!drawer.isOpen && active.recentView) { active.recentView = false }

    ModalNavigationDrawer(
        drawerState = drawer,
        // While the bookmarks panel is up, sideways swipes belong to its tabs.
        gesturesEnabled = drawer.isOpen || !sheet.visible,
        drawerContent = {
            Drawer(vm, close = { scope.launch { drawer.close() } }, navigate = navigate)
        },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopBar(vm, onMenu = { scope.launch { drawer.open() } }, show = { dlg = it }, navigate = navigate)
                val onLong: (PaneState) -> (FEntry) -> Unit = { p ->
                    { e ->
                        val entries = if (e.pathName in p.selected) p.selectedEntries() else listOf(e)
                        dlg = Dlg.Menu(p, entries)
                    }
                }
                if (prefs.dualPane) {
                    Row(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        vm.panes.forEach { p ->
                            if (p.recentView) RecentFilesPane(vm, p, openFile, fileActions, Modifier.weight(1f).fillMaxSize())
                            else PaneView(vm, p, vm.focus == p.index, onLong(p), { openFile(p, it) }, Modifier.weight(1f).fillMaxSize())
                        }
                    }
                } else {
                    // One pane: whichever had focus when switching; the other is simply not shown.
                    if (active.recentView) RecentFilesPane(vm, active, openFile, fileActions,
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp))
                    else PaneView(vm, active, true, onLong(active), { openFile(active, it) },
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp), showHeader = false)
                }
                OpsPanel()
                vm.clip?.let { c -> if (!active.selecting && !active.recentView) PasteBar(vm, c, active) }
                if (active.selecting) SelectionBar(vm, active, show = { dlg = it })
                else BottomBar(vm, active, show = { dlg = it }, sheet = sheet, recent = { vm.showRecent() })
            }
            BookmarkPanel(vm, sheet)
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp))
        }
    }

    Ops.jobs.firstOrNull { it.conflict != null }?.let { job ->
        ConflictDialog(job.conflict!!) { r, all -> job.answer(r, all) }
    }
    dlg?.let { d -> DialogHost(vm, d, close = { dlg = null }, show = { dlg = it }, fileActions = fileActions) }
}

/** Platform-dependent actions implemented by the activity. */
interface FileActions {
    fun openWith(pane: PaneState, e: FEntry)
    fun share(pane: PaneState, entries: List<FEntry>)
    fun compress(pane: PaneState, entries: List<FEntry>, name: String, format: app.twinfile.ops.ArchiveFormat, password: CharArray?)
    fun extract(pane: PaneState, e: FEntry, toOther: Boolean)
}

@Composable
fun DialogHost(vm: MainViewModel, d: Dlg, close: () -> Unit, show: (Dlg) -> Unit, fileActions: FileActions) {
    val context = LocalContext.current
    when (d) {
        is Dlg.NewItem -> InputDialog(
            "新建", hint = "名称", onDismiss = close,
            buttons = listOf(
                "文件" to { n: String -> vm.create(d.pane, n, false); close() },
                "文件夹" to { n: String -> vm.create(d.pane, n, true); close() },
            ),
        )
        is Dlg.Rename -> InputDialog(
            "重命名", d.e.name, selectStem = !d.e.isDir, onDismiss = close,
            buttons = listOf("确定" to { n: String -> vm.rename(d.pane, d.e, n); close() }),
        )
        is Dlg.Jump -> InputDialog(
            "跳转到", d.pane.source.displayPath(d.pane.path), onDismiss = close,
            buttons = listOf("前往" to { p: String ->
                close()
                var path = Paths.normalize(p.trim().replace('／', '/').replace('\\', '/'))
                // Resolve links such as /sdcard so volume features (trash, usage) apply.
                if (d.pane.source === vm.app.local) path = runCatching { File(path).canonicalPath }.getOrDefault(path)
                vm.jump(d.pane, path)
            }),
        )
        is Dlg.Filter -> InputDialog(
            "过滤当前文件夹", d.pane.filter, hint = "文件名包含…", onDismiss = close,
            leftButton = "清除" to { _: String -> d.pane.filter = ""; close() },
            buttons = listOf("确定" to { f: String -> d.pane.filter = f.trim(); close() }),
        )
        is Dlg.Keyword -> InputDialog(
            "按关键词选择", hint = "文件名包含…", onDismiss = close,
            buttons = listOf("选择" to { k: String -> vm.selectByKeyword(d.pane, k); close() }),
        )
        is Dlg.Sort -> SortDialog(d.pane, vm.hasFolderSort(d.pane), close) { spec, folder ->
            vm.setSort(d.pane, spec, folder); close()
        }
        is Dlg.Props -> PropertiesDialog(d.pane.source, d.pane.path, d.entries, close)
        is Dlg.Delete -> DeleteDialog(
            d.entries.size, d.entries.first().name, vm.canTrash(d.pane), vm.prefs.trashByDefault, close,
            cloudTrash = d.pane.source is app.twinfile.cloud.CloudSource,
        ) { toTrash -> vm.delete(d.pane, d.entries, toTrash); close() }
        is Dlg.Compress -> CreateArchiveDialog(
            (if (d.entries.size == 1) d.entries[0].name.substringBeforeLast('.').ifEmpty { d.entries[0].name } else Paths.name(d.pane.path)).ifEmpty { "压缩文件" }, close,
        ) { name, format, password -> fileActions.compress(d.pane, d.entries, name, format, password); close() }
        is Dlg.Menu -> {
            val p = d.pane
            val es = d.entries
            val single = es.singleOrNull()
            val otherSame = vm.other.recentView || (vm.other.source === p.source && vm.other.path == p.path)
            val isLocal = p.source === vm.app.local
            val actions = buildList {
                d.locate?.let { locate -> add(MenuAction("定位所在位置", Icons.Filled.MyLocation) { locate() }) }
                if (vm.prefs.dualPane) {
                    add(MenuAction("复制 →", Icons.Filled.ContentCopy, !otherSame) { vm.transfer(p, es, move = false) })
                    add(MenuAction("移动 →", Icons.AutoMirrored.Filled.DriveFileMove, !otherSame) { vm.transfer(p, es, move = true) })
                } else {
                    // One pane: pick up the files, then paste them wherever the user goes.
                    add(MenuAction("复制", Icons.Filled.ContentCopy) { vm.toClip(p, es, move = false) })
                    add(MenuAction("剪切", Icons.Filled.ContentCut) { vm.toClip(p, es, move = true) })
                }
                add(MenuAction("删除", Icons.Filled.Delete) { show(Dlg.Delete(p, es)) })
                add(MenuAction("重命名", Icons.Filled.DriveFileRenameOutline, single != null) { show(Dlg.Rename(p, single!!)) })
                add(MenuAction("创建副本", Icons.Filled.ControlPointDuplicate) { vm.transfer(p, es, move = false, toSamePane = true) })
                if (single != null && !single.isDir && app.twinfile.ops.Archive.format(single.name) != null) {
                    if (vm.prefs.dualPane) add(MenuAction("解压 →", Icons.Filled.Unarchive, isLocal && !vm.other.recentView) { fileActions.extract(p, single, toOther = true) })
                    add(MenuAction("解压到此处", Icons.Filled.Unarchive, isLocal) { fileActions.extract(p, single, toOther = false) })
                }
                add(MenuAction("压缩", Icons.Filled.Archive, isLocal) { show(Dlg.Compress(p, es)) })
                add(MenuAction("属性", Icons.Filled.Info) { show(Dlg.Props(p, es)) })
                add(MenuAction("分享", Icons.Filled.Share, isLocal && es.none { it.isDir }) { fileActions.share(p, es) })
                add(MenuAction("打开方式", Icons.AutoMirrored.Filled.OpenInNew, single != null && !single.isDir) { fileActions.openWith(p, single!!) })
                add(MenuAction("添加书签", Icons.Filled.Bookmark, single != null) { vm.addBookmark(p, single) })
                add(MenuAction("复制路径", Icons.Filled.Link) {
                    val text = es.joinToString("\n") { p.source.displayPath(Paths.join(p.path, it.pathName)) }
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("path", text))
                    vm.say("已复制路径")
                })
                if (es.size == 2) add(MenuAction("交换文件名", Icons.Filled.SwapVert) { vm.swapNames(p, es[0], es[1]) })
            }
            ItemMenu(single?.name ?: "已选 ${es.size} 项", actions, close)
        }
    }
}

@Composable
private fun TopBar(vm: MainViewModel, onMenu: () -> Unit, show: (Dlg) -> Unit, navigate: (Screen) -> Unit) {
    val pane = vm.active
    val twin = LocalTwin.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val usage by produceState<Pair<Long, Long>?>(null, pane.path, pane.source, vm.storageVersion) {
        value = if (pane.source.phonePaths) withContext(Dispatchers.IO) {
            Volumes.of(context, pane.path)?.let { (it.total - it.free) to it.total }
                ?: (Volumes.total(pane.path) - Volumes.free(pane.path) to Volumes.total(pane.path))
        } else null
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onMenu) { Icon(Icons.Filled.Menu, "菜单") }
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            if (pane.recentView) {
                Text("最近文件", Modifier.padding(start = 6.dp, top = 3.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("本地文件聚合", Modifier.padding(start = 6.dp), fontSize = 12.sp, color = twin.muted)
            } else {
                Breadcrumb(vm, pane, onLong = { show(Dlg.Jump(pane)) })
                val dirs = pane.visible.count { it.isDir }
                val files = pane.visible.size - dirs
                val parts = buildList {
                    add("$dirs 个文件夹")
                    add("$files 个文件")
                    if (pane.selecting) add("已选 ${pane.selected.size}")
                    usage?.let { (u, t) -> if (t > 0) add("${Format.size(u)} / ${Format.size(t)}") }
                }
                Text(parts.joinToString(" · "), Modifier.padding(start = 6.dp), fontSize = 12.sp, color = twin.muted, maxLines = 1)
            }
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
            DropdownMenu(menu, { menu = false }) {
                fun item(label: String, action: () -> Unit) = @Composable {
                    DropdownMenuItem({ Text(label) }, { menu = false; action() })
                }
                item("刷新") { vm.refreshAll() }()
                if (!pane.recentView) {
                    item("全选") { vm.selectAll(pane) }()
                    item("过滤") { show(Dlg.Filter(pane)) }()
                    item("排序方式") { show(Dlg.Sort(pane)) }()
                    item(if (vm.prefs.showHidden) "不显示隐藏文件" else "显示隐藏文件") { vm.prefs.update { showHidden = !showHidden } }()
                    item("添加书签") { vm.addBookmark(pane) }()
                    if (pane.source.phonePaths) item("设为首页") { vm.setHome(pane) }()
                }
                if (pane.recentView) item("返回目录") { pane.recentView = false }()
                if (vm.prefs.dualPane) item("交换窗口") { vm.swapPanes() }()
                item(if (vm.prefs.dualPane) "切换为单栏" else "切换为双栏") { vm.prefs.update { dualPane = !dualPane } }()
                HorizontalDivider()
                item("设置") { navigate(Screen.SETTINGS) }()
            }
        }
    }
}

/** The folder path as tappable segments; long press types a path instead. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Breadcrumb(vm: MainViewModel, pane: PaneState, onLong: () -> Unit) {
    val twin = LocalTwin.current
    val crumbs = vm.crumbs(pane)
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Keep the current folder in view.
    LaunchedEffect(pane.path, pane.source) { state.scrollToItem(crumbs.lastIndex) }
    LazyRow(state = state, verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(crumbs, key = { _, c -> c.second }) { i, (label, path) ->
            val last = i == crumbs.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = twin.muted)
                Text(
                    label,
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .combinedClickable(onLongClick = onLong) {
                            if (!last) vm.open(pane, path)
                            else scope.launch { pane.listState.animateScrollToItem(0) } // current folder: back to top
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    fontSize = if (last) 16.sp else 14.sp,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (last) MaterialTheme.colorScheme.onSurface else twin.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Shown while files wait on the clipboard: paste them into the current folder. */
@Composable
private fun PasteBar(vm: MainViewModel, c: MainViewModel.Clip, pane: PaneState) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                val what = c.entries.singleOrNull()?.name ?: "${c.entries.size} 项"
                Text("${if (c.move) "已剪切" else "已复制"} $what", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text("来自 ${Paths.name(c.dir)}", fontSize = 12.sp, color = LocalTwin.current.muted, maxLines = 1)
            }
            TextButton({ vm.clip = null }) { Text("取消") }
            TextButton({ vm.paste(pane) }, enabled = !pane.source.readOnly) { Text("粘贴到这里") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarButton(icon: ImageVector, label: String, enabled: Boolean = true, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else LocalTwin.current.muted.copy(alpha = 0.4f)
    Box(
        Modifier.size(52.dp, 48.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLong, onClickLabel = label),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint) }
}

@Composable
private fun BottomBar(vm: MainViewModel, pane: PaneState, show: (Dlg) -> Unit, sheet: PullSheetState, recent: () -> Unit) {
    // Dragging up pulls the bookmarks panel out with the finger.
    Column(
        Modifier.fillMaxWidth().draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { sheet.dragBy(it) },
            onDragStopped = { v -> sheet.settle(v) },
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Hint that the bar can be pulled up for bookmarks.
        Box(Modifier.padding(top = 4.dp).size(28.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(LocalTwin.current.muted.copy(alpha = 0.35f)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BarButton(Icons.AutoMirrored.Filled.ArrowBack, "后退", pane.canBack || pane.recentView) { if (pane.recentView) pane.recentView = false else vm.back(pane) }
            BarButton(Icons.AutoMirrored.Filled.ArrowForward, "前进", pane.canForward && !pane.recentView) { vm.forward(pane) }
            BarButton(Icons.Filled.Add, "新建", !pane.recentView) { show(Dlg.NewItem(pane)) }
            if (vm.prefs.dualPane) BarButton(Icons.Filled.SwapHoriz, "同步到另一栏（长按交换）", onLong = { vm.swapPanes() }) { vm.syncOther() }
            else BarButton(Icons.Filled.Refresh, "刷新") { vm.refreshAll() }
            BarButton(Icons.Filled.History, "最近文件") { recent() }
        }
    }
}

@Composable
private fun SelectionBar(vm: MainViewModel, pane: PaneState, show: (Dlg) -> Unit) {
    var more by remember { mutableStateOf(false) }
    val dual = vm.prefs.dualPane
    val otherSame = vm.other.recentView || (vm.other.source === pane.source && vm.other.path == pane.path)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        // Two panes: straight to the other one. One pane: onto the clipboard, paste anywhere.
        if (dual) {
            BarButton(Icons.Filled.ContentCopy, "复制", !otherSame) { vm.transfer(pane, pane.selectedEntries(), move = false) }
            BarButton(Icons.AutoMirrored.Filled.DriveFileMove, "移动", !otherSame) { vm.transfer(pane, pane.selectedEntries(), move = true) }
        } else {
            BarButton(Icons.Filled.ContentCopy, "复制") { vm.toClip(pane, pane.selectedEntries(), move = false) }
            BarButton(Icons.Filled.ContentCut, "剪切") { vm.toClip(pane, pane.selectedEntries(), move = true) }
        }
        BarButton(Icons.Filled.Delete, "删除") { show(Dlg.Delete(pane, pane.selectedEntries())) }
        BarButton(Icons.Filled.Bolt, "更多操作") { show(Dlg.Menu(pane, pane.selectedEntries())) }
        Box {
            BarButton(Icons.Filled.SelectAll, "选择") { more = true }
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem({ Text("全选") }, { more = false; vm.selectAll(pane) })
                DropdownMenuItem({ Text("反选") }, { more = false; vm.invert(pane) })
                DropdownMenuItem({ Text("选中两项之间的全部") }, { more = false; vm.selectRange(pane) })
                HorizontalDivider()
                listOf(
                    "选择同一天" to MainViewModel.Period.DAY, "选择同一周" to MainViewModel.Period.WEEK,
                    "选择同一月" to MainViewModel.Period.MONTH, "选择同一年" to MainViewModel.Period.YEAR,
                ).forEach { (l, p) -> DropdownMenuItem({ Text(l) }, { more = false; vm.selectSamePeriod(pane, p) }) }
                DropdownMenuItem({ Text("按关键词选择…") }, { more = false; show(Dlg.Keyword(pane)) })
            }
        }
        BarButton(Icons.Filled.Close, "取消") { vm.clearSelection(pane) }
    }
}

/** The local file behind a pane entry. */
fun localFile(pane: PaneState, e: FEntry) = File(Paths.join(pane.path, e.name))
