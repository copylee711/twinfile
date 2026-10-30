package app.twinfile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.twinfile.fs.FEntry
import app.twinfile.fs.FileSource
import app.twinfile.fs.Paths
import app.twinfile.fs.SortBy
import app.twinfile.fs.SortSpec
import app.twinfile.ops.Conflict
import app.twinfile.ops.Resolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext

/**
 * A one-line input dialog. [selectStem] preselects the name without its extension, the way
 * renaming usually wants. [buttons] are (label, action) pairs shown on the right.
 */
@Composable
fun InputDialog(
    title: String,
    initial: String = "",
    hint: String = "",
    selectStem: Boolean = false,
    onDismiss: () -> Unit,
    leftButton: Pair<String, (String) -> Unit>? = null,
    buttons: List<Pair<String, (String) -> Unit>>,
) {
    val dot = initial.lastIndexOf('.')
    val end = if (selectStem && dot > 0) dot else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it }, singleLine = true,
                placeholder = { if (hint.isNotEmpty()) Text(hint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { buttons.last().second(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        dismissButton = {
            Row {
                leftButton?.let { (l, a) -> TextButton({ a(value.text) }) { Text(l) } }
            }
        },
        confirmButton = {
            Row {
                TextButton(onDismiss) { Text("取消") }
                buttons.forEach { (l, a) -> TextButton({ a(value.text) }) { Text(l) } }
            }
        },
    )
}

@Composable
fun SortDialog(pane: PaneState, folderOnlyInitially: Boolean, onDismiss: () -> Unit, onApply: (SortSpec, Boolean) -> Unit) {
    var by by remember { mutableStateOf(pane.sort.by) }
    var reverse by remember { mutableStateOf(pane.sort.reverse) }
    var folderOnly by remember { mutableStateOf(folderOnlyInitially) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序 · ${if (pane.index == 0) "左" else "右"}窗口") },
        text = {
            Column {
                val labels = listOf(SortBy.NAME to "名称", SortBy.DATE to "修改时间", SortBy.SIZE to "大小", SortBy.TYPE to "类型")
                labels.chunked(2).forEach { row ->
                    Row {
                        row.forEach { (k, l) ->
                            Row(Modifier.weight(1f).clickable { by = k }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(by == k, { by = k })
                                Text(l)
                            }
                        }
                    }
                }
                CheckRow("逆向排序", reverse) { reverse = it }
                CheckRow("仅用于此文件夹", folderOnly) { folderOnly = it }
            }
        },
        confirmButton = { TextButton({ onApply(SortSpec(by, reverse), folderOnly) }) { Text("确定") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

@Composable
fun DeleteDialog(count: Int, firstName: String, canTrash: Boolean, trashDefault: Boolean,
                 onDismiss: () -> Unit, cloudTrash: Boolean = false, onDelete: (toTrash: Boolean) -> Unit) {
    var toTrash by remember { mutableStateOf(canTrash && trashDefault) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除") },
        text = {
            Column {
                Text(if (count == 1) "删除「$firstName」？" else "删除选中的 $count 项？")
                if (canTrash) CheckRow("移到回收站", toTrash) { toTrash = it }
                if (cloudTrash) {
                    Text("文件将移入云盘回收站，保留期限由云盘服务决定。", fontSize = 13.sp)
                } else if (!toTrash) {
                    Spacer(Modifier.height(6.dp))
                    Text("将永久删除，无法恢复", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton({ onDelete(toTrash) }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
fun ConflictDialog(c: Conflict, onAnswer: (Resolution?, Boolean) -> Unit) {
    var all by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("已存在同名${if (c.isDir) "文件夹" else "文件"}") },
        text = {
            Column {
                Text(c.name, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("目标：${Format.time(c.existing.mtime)}  ${Format.size(c.existing.size)}", fontSize = 13.sp)
                if (c.isDir) Text("覆盖文件夹会合并内容，里面的同名文件再逐个处理", fontSize = 12.sp)
                CheckRow("对后续冲突都这样处理", all) { all = it }
            }
        },
        confirmButton = {
            Row {
                TextButton({ onAnswer(Resolution.SKIP, all) }) { Text("跳过") }
                TextButton({ onAnswer(Resolution.RENAME, all) }) { Text("保留两者") }
                TextButton({ onAnswer(Resolution.OVERWRITE, all) }) { Text("覆盖") }
            }
        },
        dismissButton = { TextButton({ onAnswer(null, false) }) { Text("取消任务") } },
    )
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("TwinFile", text))
}

/** Size, time, permissions; for a single file also MD5/SHA-1/SHA-256/CRC32 on demand. */
@Composable
fun PropertiesDialog(source: FileSource, dir: String, entries: List<FEntry>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val single = entries.singleOrNull()
    val stat by androidx.compose.runtime.produceState<FEntry?>(null, single) {
        if (single != null) value = withContext(Dispatchers.IO) { runCatching { source.stat(Paths.join(dir, single.pathName)) }.getOrNull() }
    }
    // Recursive size of folders, counted in the background.
    val total by androidx.compose.runtime.produceState(Triple(0L, 0, 0) to false, entries) {
        withContext(Dispatchers.IO) {
            var bytes = 0L
            var files = 0
            var dirs = 0
            suspend fun walk(path: String, e: FEntry) {
                if (!coroutineContext.isActive) return
                if (e.isDir) {
                    dirs++
                    for (c in runCatching { source.list(path) }.getOrDefault(emptyList())) walk(Paths.join(path, c.pathName), c)
                    value = Triple(bytes, files, dirs) to false
                } else {
                    files++
                    bytes += maxOf(0, e.size)
                }
            }
            for (e in entries) walk(Paths.join(dir, e.pathName), e)
            value = Triple(bytes, files, dirs) to true
        }
    }
    var hashing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("属性") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (single != null) {
                    Prop("名称", single.name)
                    Prop("位置", source.displayPath(dir))
                    Prop("类型", if (single.isDir) "文件夹" else "文件（${Paths.extension(single.name).ifEmpty { "无扩展名" }}）")
                } else {
                    Prop("项目", "${entries.size} 项")
                    Prop("位置", source.displayPath(dir))
                }
                val (t, done) = total
                val (bytes, files, dirs) = t
                Prop("大小", "${Format.size(bytes)}（${Format.bytesExact(bytes)}）${if (done) "" else " …"}")
                if (entries.any { it.isDir }) Prop("包含", "$files 个文件，${(dirs - entries.count { it.isDir }).coerceAtLeast(0)} 个文件夹")
                if (single != null) {
                    Prop("修改时间", Format.fullTime(single.mtime))
                    stat?.perms?.let { Prop("权限", it) }
                    if (stat?.isLink == true) Prop("链接", "符号链接")
                }
                if (single != null && !single.isDir) {
                    Spacer(Modifier.height(8.dp))
                    if (!hashing) TextButton({ hashing = true }) { Text("计算校验值") }
                    else HashBlock(source, Paths.join(dir, single.pathName), single.size) { copyText(context, it) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } },
        dismissButton = {
            TextButton({ copyText(context, source.displayPath(if (single != null) Paths.join(dir, single.pathName) else dir)) }) { Text("复制路径") }
        },
    )
}

@Composable
private fun Prop(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, Modifier.width(72.dp), color = LocalTwin.current.muted, fontSize = 14.sp)
        SelectionContainer { Text(value, fontSize = 14.sp) }
    }
}

@Composable
private fun HashBlock(source: FileSource, path: String, size: Long, copy: (String) -> Unit) {
    data class H(val md5: String, val sha1: String, val sha256: String, val crc: String)
    var progress by remember { mutableStateOf(0f) }
    var upper by remember { mutableStateOf(false) }
    var compare by remember { mutableStateOf("") }
    val result by androidx.compose.runtime.produceState<H?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val md5 = MessageDigest.getInstance("MD5")
            val sha1 = MessageDigest.getInstance("SHA-1")
            val sha256 = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            val buf = ByteArray(256 * 1024)
            var done = 0L
            runCatching {
                source.openRead(path).use { input ->
                    while (coroutineContext.isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md5.update(buf, 0, n); sha1.update(buf, 0, n); sha256.update(buf, 0, n); crc.update(buf, 0, n)
                        done += n
                        if (size > 0) progress = done.toFloat() / size
                    }
                }
                H(md5.digest().hex(), sha1.digest().hex(), sha256.digest().hex(), "%08x".format(crc.value))
            }.getOrNull()
        }
    }
    val r = result
    if (r == null) {
        Bar({ progress }, Modifier.fillMaxWidth())
        return
    }
    fun f(s: String) = if (upper) s.uppercase() else s
    val c = compare.trim().lowercase()
    listOf("MD5" to r.md5, "SHA-1" to r.sha1, "SHA-256" to r.sha256, "CRC32" to r.crc).forEach { (k, v) ->
        val match = c.isNotEmpty() && c == v
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(k + if (match) "  ✓ 一致" else "", fontSize = 12.sp,
                    color = if (match) LocalTwin.current.image else LocalTwin.current.muted)
                SelectionContainer { Text(f(v), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
            IconButton({ copy(f(v)) }, Modifier.size(36.dp)) { Icon(Icons.Filled.ContentCopy, "复制", Modifier.size(18.dp)) }
        }
    }
    OutlinedTextField(compare, { compare = it }, singleLine = true, label = { Text("粘贴校验值比对") },
        modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
    if (c.isNotEmpty() && listOf(r.md5, r.sha1, r.sha256, r.crc).none { it == c }) {
        Text("✗ 不一致", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    }
    CheckRow("大写字母", upper) { upper = it }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/** A grid of actions for the long-pressed item(s). */
@Composable
fun ItemMenu(
    title: String,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, fontSize = 17.sp) },
        text = {
            BoxWithConstraints {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp)
            val needed = actions.maxOfOrNull { measurer.measure(AnnotatedString(it.label), textStyle).size.width } ?: 0
            val columns = if (with(density) { maxWidth.toPx() } >= 2 * (needed + with(density) { 38.dp.toPx() })) 2 else 1
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                actions.chunked(columns).forEach { row ->
                    Row {
                        row.forEach { a ->
                            Row(
                                Modifier.weight(1f).clickable(enabled = a.enabled) { onDismiss(); a.run() }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val color = if (a.enabled) MaterialTheme.colorScheme.onSurface else LocalTwin.current.muted.copy(alpha = 0.5f)
                                Icon(a.icon, null, Modifier.size(20.dp), tint = if (a.enabled) MaterialTheme.colorScheme.primary else color)
                                Spacer(Modifier.width(10.dp))
                                Text(a.label, color = color, style = textStyle, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                        if (columns == 2 && row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            }
        },
        confirmButton = {},
    )
}

data class MenuAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

/** A newer release from GitHub: update now, later, or stop checking. */
@Composable
fun UpdateDialog(vm: MainViewModel, r: app.twinfile.update.Updater.Release) {
    val progress = vm.updateProgress
    AlertDialog(
        onDismissRequest = { if (progress == null) vm.update = null },
        title = {
            Column {
                Text("发现新版本 v${r.version}")
                Text("当前 v${app.twinfile.update.Updater.current} · 安装包 ${Format.size(r.apkSize)}", fontSize = 13.sp,
                    color = LocalTwin.current.muted)
            }
        },
        text = {
            Column {
                if (r.notes.isNotBlank()) {
                    Box(Modifier.heightIn(max = 300.dp)) { MarkdownView(r.notes) }
                }
                vm.updateError?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
                }
                if (progress != null) {
                    Bar({ progress }, Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text("正在下载 ${(progress * 100).toInt()}%", fontSize = 12.sp, color = LocalTwin.current.muted,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            if (progress == null) TextButton({ vm.downloadUpdate(r) }) { Text(if (vm.updateError != null) "重试" else "更新") }
            else TextButton({ vm.cancelUpdate() }) { Text("取消下载") }
        },
        dismissButton = {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (progress == null) Row {
                if (vm.updateError != null) {
                    // The browser may have a proxy or a better route to GitHub.
                    TextButton({ app.twinfile.update.Updater.openPage(context, r.page) }) { Text("在浏览器中下载") }
                } else {
                    TextButton({ vm.prefs.update { autoUpdate = false }; vm.update = null; vm.say("已关闭自动检查更新，可在设置里重新打开") }) {
                        Text("不再检查")
                    }
                }
                TextButton({ vm.cancelUpdate() }) { Text("以后再说") }
            }
        },
    )
}
