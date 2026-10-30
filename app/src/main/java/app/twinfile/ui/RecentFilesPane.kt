package app.twinfile.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.twinfile.fs.*
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentFilesPane(vm: MainViewModel, pane: PaneState, openFile: (PaneState, FEntry) -> Unit,
                    fileActions: FileActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var generation by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var checked by remember { mutableIntStateOf(0) }
    var records by remember { mutableStateOf<List<RecentFile>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var worker by remember { mutableStateOf<Job?>(null) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var clear by remember { mutableStateOf(false) }
    val modifiedList = pane.recentModifiedList
    val openedList = pane.recentOpenedList
    val history = vm.prefs.openedFiles.toList()
    val hidden = vm.prefs.showHidden

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(pane.recentOpened, hidden, refresh, vm.storageVersion, if (pane.recentOpened) history else emptyList()) {
        val token = ++generation
        scanning = true; checked = 0; records = emptyList(); note = null
        worker = coroutineContext[Job]
        try {
            val result = withContext(Dispatchers.IO) {
                val task = coroutineContext
                if (pane.recentOpened) {
                    history.mapNotNull { h ->
                        task.ensureActive()
                        val file = File(h.path)
                        val entry = runCatching { vm.app.local.stat(h.path) }.getOrNull()
                        if (entry == null || entry.isDir || !file.canRead() || (!hidden && file.invariantSeparatorsPath.split('/').any { it.startsWith('.') })) null
                        else RecentFile(h.path, entry, h.time)
                    }
                } else {
                    val roots = Volumes.list(context).map { File(it.path).canonicalFile }.distinct()
                    val index = RecentFileIndex(RecentFiles.LIMIT)
                    val available = RecentFiles.indexed(context, roots, hidden, index) { task.ensureActive() }
                    val initial = index.snapshot()
                    withContext(Dispatchers.Main) { if (generation == token) records = initial }
                    var publishedAt = 0L
                    index.scan(roots, hidden, { task.ensureActive() }) { count ->
                        val now = System.nanoTime()
                        if (now - publishedAt > 350_000_000L) {
                            publishedAt = now
                            val snapshot = index.snapshot()
                            scope.launch {
                                if (generation == token) { records = snapshot; checked = count }
                            }
                        }
                    }
                    if (!available) withContext(Dispatchers.Main) {
                        if (generation == token) note = "系统索引不可用，当前按文件修改时间收集"
                    }
                    index.snapshot()
                }
            }
            if (generation == token) records = result
        } catch (e: CancellationException) {
            if (generation == token) note = "扫描已停止，当前结果可能不完整"
            throw e
        }
        catch (_: Exception) { if (generation == token) note = "部分文件无法读取，可刷新重试" }
        finally { if (generation == token) scanning = false }
    }

    val visible = records
    val twin = LocalTwin.current
    val focused = vm.focus == pane.index
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(if (focused) twin.paneFocused else twin.pane)
        .border(if (focused) 1.5.dp else 0.dp, if (focused) twin.paneBorder.copy(alpha = 0.55f) else twin.pane, shape)
        .pointerInput(pane.index) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                vm.focus = pane.index
            }
        }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ pane.recentView = false }, Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回目录", Modifier.size(18.dp)) }
            Text("最近文件", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (scanning) IconButton({ worker?.cancel() }, Modifier.size(36.dp)) { Icon(Icons.Filled.Stop, "停止扫描", Modifier.size(18.dp)) }
            else IconButton({ refresh++ }, Modifier.size(36.dp)) { Icon(Icons.Filled.Refresh, "刷新最近文件", Modifier.size(18.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(!pane.recentOpened, { pane.recentOpened = false }, modifier = Modifier.weight(1f), label = { Text("新增/修改", fontSize = 11.sp, maxLines = 1) })
            FilterChip(pane.recentOpened, { pane.recentOpened = true }, modifier = Modifier.weight(1f), label = { Text("最近打开", fontSize = 11.sp, maxLines = 1) })
        }
        Text(if (pane.recentOpened) "本机打开记录 · ${visible.size} 项${if (!vm.prefs.recordOpened) " · 已暂停记录" else ""}"
            else if (scanning) "正在收集 · 已检查 $checked 个文件 · ${visible.size} 项"
            else "按系统收录或修改时间排序 · 最近 ${RecentFiles.LIMIT} 项中的 ${visible.size} 项",
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = LocalTwin.current.muted, fontSize = 12.sp)
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        note?.let { Text(it, Modifier.padding(horizontal = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = if (pane.recentOpened) openedList else modifiedList, modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.path }) { item ->
                    val parent = Paths.parent(item.path) ?: "/"
                    val showMenu = {
                        vm.focus = pane.index
                        val origin = PaneState(pane.index, vm.app.local) { vm.prefs.showHidden }.apply {
                            path = parent; raw = listOf(item.entry)
                        }
                        dialog = Dlg.Menu(origin, listOf(item.entry)) { vm.openRecentFile(item.path, pane) }
                    }
                    Row(Modifier.fillMaxWidth().combinedClickable(
                        onClick = {
                            vm.openRecentFile(item.path, pane) { realPane, entry ->
                                realPane.recentView = true
                                openFile(realPane, entry)
                            }
                        },
                        onLongClick = showMenu).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileIcon(vm.app.local, parent, item.entry, 32.dp)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(Format.time(item.changedAt), Modifier.weight(1f), fontSize = 10.sp,
                                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = twin.muted)
                                Spacer(Modifier.width(4.dp))
                                Text(Format.size(item.entry.size).replace(' ', '\u00A0'), fontSize = 10.sp,
                                    maxLines = 1, softWrap = false, color = twin.muted)
                            }
                            Text(parent, maxLines = 1, overflow = TextOverflow.StartEllipsis, fontSize = 11.sp, color = LocalTwin.current.muted)
                        }
                        IconButton(showMenu, Modifier.size(28.dp)) { Icon(Icons.Filled.MoreVert, "文件操作", Modifier.size(18.dp)) }
                    }
                }
            }
            if (visible.isEmpty()) Text(if (scanning) "正在查找文件…" else if (pane.recentOpened) "暂无匹配的打开记录" else "暂无匹配文件",
                Modifier.align(Alignment.Center).padding(24.dp), color = LocalTwin.current.muted)
        }
        if (pane.recentOpened && history.isNotEmpty()) TextButton({ clear = true }) { Text("清空打开记录") }

    }
    dialog?.let { DialogHost(vm, it, { dialog = null }, { dialog = it }, fileActions) }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空打开记录") },
        text = { Text("只清除本机记录，文件会保留。") },
        confirmButton = { TextButton({ vm.prefs.update { openedFiles.clear(); recent.removeAll { !it.isDir && it.source == "local" } }; clear = false }) { Text("清空记录") } },
        dismissButton = { TextButton({ clear = false }) { Text("取消") } })
}
