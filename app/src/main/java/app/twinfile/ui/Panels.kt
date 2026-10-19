package app.twinfile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.twinfile.Bookmark
import app.twinfile.fs.Paths
import app.twinfile.fs.Volumes
import app.twinfile.ops.OpJob
import app.twinfile.ops.Ops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun Drawer(vm: MainViewModel, close: () -> Unit, navigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val twin = LocalTwin.current
    val volumes by produceState(emptyList<Volumes.Volume>(), vm.storageVersion) {
        value = withContext(Dispatchers.IO) { Volumes.list(context) }
    }
    ModalDrawerSheet(Modifier.width(300.dp)) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text("TwinFile", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Section("本地")
            volumes.forEach { v ->
                VolumeRow(if (v.removable) Icons.Filled.SdCard else Icons.Filled.PhoneAndroid, v.name, v.total, v.free) {
                    vm.openBookmark(vm.active, "local", v.path); close()
                }
            }
            VolumeRow(Icons.Filled.Storage, "根目录", Volumes.total("/"), Volumes.free("/")) {
                vm.openBookmark(vm.active, "local", "/"); close()
            }
            if (vm.prefs.bookmarks.isNotEmpty()) {
                Section("书签")
                vm.prefs.bookmarks.take(8).forEach { b ->
                    DrawerItem(Icons.Filled.Bookmark, b.name, b.path) { vm.openBookmark(vm.active, b.source, b.path); close() }
                }
            }
            Section("工具")
            DrawerItem(Icons.Filled.Lan, "网络位置") { close(); navigate(Screen.NETWORK) }
            DrawerItem(Icons.Filled.Computer, "电脑") { close(); navigate(Screen.PC) }
            DrawerItem(Icons.Filled.DeleteOutline, "回收站") { close(); navigate(Screen.TRASH) }
            DrawerItem(Icons.Filled.Apps, "已安装应用") { close(); navigate(Screen.APPS) }
            DrawerItem(Icons.Filled.Settings, "设置") { close(); navigate(Screen.SETTINGS) }
            Spacer(Modifier.height(8.dp))
            Text("开源 · MIT", Modifier.padding(horizontal = 20.dp), fontSize = 12.sp, color = twin.muted)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp), fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun VolumeRow(icon: ImageVector, name: String, total: Long, free: Long, onClick: () -> Unit) {
    val twin = LocalTwin.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = twin.folder)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp)
            if (total > 0) {
                val used = total - free
                LinearProgressIndicator({ (used.toFloat() / total).coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
                Text("已用 ${Format.size(used)} · 可用 ${Format.size(free)}", fontSize = 12.sp, color = twin.muted)
            }
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, sub: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 11.sp, color = LocalTwin.current.muted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
    }
}

/** Pulled up from the bottom bar: recent places and bookmarks. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarkSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(if (vm.prefs.bookmarks.isEmpty()) 0 else 1) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("最近") })
            Tab(tab == 1, { tab = 1 }, text = { Text("书签") })
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp)) {
            if (tab == 0) {
                if (vm.prefs.recent.isEmpty()) item { Empty("还没有访问记录") }
                itemsIndexed(vm.prefs.recent, key = { _, r -> r.source + r.path }) { _, r ->
                    SheetRow(r.path, Paths.name(r.path), r.isDir, onLong = null) {
                        vm.openBookmark(vm.active, r.source, r.path, r.isDir); onDismiss()
                    }
                }
            } else {
                if (vm.prefs.bookmarks.isEmpty()) item { Empty("在菜单里点「添加书签」，或长按文件选择「添加书签」") }
                itemsIndexed(vm.prefs.bookmarks, key = { _, b -> b.source + b.path }) { _, b ->
                    SheetRow(b.path, b.name, true, onLong = { editing = b }) {
                        vm.openBookmark(vm.active, b.source, b.path); onDismiss()
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    editing?.let { b -> BookmarkEditor(vm, b) { editing = null } }
}

@Composable
private fun Empty(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(32.dp), color = LocalTwin.current.muted, fontSize = 14.sp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SheetRow(path: String, name: String, isDir: Boolean, onLong: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val kind = if (isDir) Kind.FOLDER else Kinds.of(name)
        Icon(kindIcon(kind), null, tint = kindColor(kind))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(path, fontSize = 12.sp, color = LocalTwin.current.muted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
    }
}

@Composable
private fun BookmarkEditor(vm: MainViewModel, b: Bookmark, close: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    val list = vm.prefs.bookmarks
    if (renaming) {
        InputDialog("重命名书签", b.name, onDismiss = close, buttons = listOf("确定" to { n: String ->
            val i = list.indexOf(b)
            if (i >= 0 && n.isNotBlank()) vm.prefs.update { list[i] = b.copy(name = n.trim()) }
            close()
        }))
        return
    }
    fun moveBy(d: Int) {
        val i = list.indexOf(b)
        val j = i + d
        if (i < 0 || j !in list.indices) return
        vm.prefs.update { list.removeAt(i); list.add(j, b) }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(b.name) },
        text = {
            Column {
                Text(b.path, fontSize = 13.sp, color = LocalTwin.current.muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ moveBy(-1) }) { Text("上移") }
                    TextButton({ moveBy(1) }) { Text("下移") }
                    TextButton({ renaming = true }) { Text("重命名") }
                }
            }
        },
        confirmButton = { TextButton(close) { Text("完成") } },
        dismissButton = {
            TextButton({ vm.prefs.update { list.remove(b) }; close() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
    )
}

/** Running and finished jobs above the bottom bar. */
@Composable
fun OpsPanel() {
    if (Ops.jobs.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Ops.jobs.toList().forEach { JobCard(it) }
    }
}

@Composable
private fun JobCard(job: OpJob) {
    val twin = LocalTwin.current
    // Poll the worker's counters; they are plain fields, not Compose state.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(job, job.state) {
        if (job.state == OpJob.State.RUNNING) while (true) { delay(250); tick++ }
        if (job.state == OpJob.State.DONE || job.state == OpJob.State.CANCELLED) { delay(1500); Ops.dismiss(job) }
    }
    val s = job.stats
    tick // read here so the card recomposes on every poll
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when (job.state) {
                        OpJob.State.RUNNING -> buildString {
                            append("${s.doneFiles}/${s.totalFiles}")
                            if (s.totalBytes > 0) append(" · ${Format.size(s.doneBytes)} / ${Format.size(s.totalBytes)}")
                            if (s.current.isNotEmpty()) append(" · ${s.current}")
                        }
                        OpJob.State.DONE -> "完成"
                        OpJob.State.CANCELLED -> "已取消"
                        OpJob.State.FAILED -> "失败：${job.error}"
                    }
                    Text(status, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (job.state == OpJob.State.FAILED) MaterialTheme.colorScheme.error else twin.muted)
                }
                IconButton({ if (job.state == OpJob.State.RUNNING) job.cancel() else Ops.dismiss(job) }, Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, if (job.state == OpJob.State.RUNNING) "取消" else "关闭", Modifier.size(18.dp))
                }
            }
            if (job.state == OpJob.State.RUNNING) {
                val p = when {
                    s.totalBytes > 0 -> (s.doneBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f)
                    s.totalFiles > 0 -> (s.doneFiles.toFloat() / s.totalFiles).coerceIn(0f, 1f)
                    else -> null
                }
                Box(Modifier.padding(end = 8.dp, top = 4.dp)) {
                    if (p != null) LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

