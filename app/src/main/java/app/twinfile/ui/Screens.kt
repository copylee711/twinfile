package app.twinfile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.twinfile.fs.Paths
import app.twinfile.fs.Volumes
import app.twinfile.ops.Trash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SimpleScaffold(title: String, back: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            actions()
        }
        content()
    }
}

@Composable
fun TrashScreen(vm: MainViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Trash.Item?>(null) }
    val items by produceState(emptyList<Trash.Item>(), version) {
        value = withContext(Dispatchers.IO) { vm.app.trash.items(Volumes.list(context).map { it.path }) }
    }
    fun act(block: () -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                vm.say(e.message ?: "操作失败")
            }
            version++
            vm.refreshAll()
        }
    }
    SimpleScaffold("回收站", back, actions = {
        if (items.isNotEmpty()) TextButton({ confirmEmpty = true }) { Text("清空") }
    }) {
        if (items.isEmpty()) {
            Text("回收站是空的", Modifier.padding(24.dp), color = LocalTwin.current.muted)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.trashDir.path + it.id }) { item ->
                val kind = if (item.isDir) Kind.FOLDER else Kinds.of(item.name)
                Row(
                    Modifier.fillMaxWidth().clickable { picked = item }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kindIcon(kind), null, tint = kindColor(kind))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Format.time(item.deletedAt)} 删除 · ${Paths.parent(item.originalPath)}",
                            fontSize = 12.sp, color = LocalTwin.current.muted, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                    if (!item.isDir) Text(Format.size(item.size), fontSize = 12.sp, color = LocalTwin.current.muted)
                }
            }
        }
        val autoDays = vm.prefs.trashAutoCleanDays
        Text(if (autoDays > 0) "超过 $autoDays 天的项目会自动清理" else "不会自动清理",
            Modifier.padding(16.dp), fontSize = 12.sp, color = LocalTwin.current.muted)
    }
    picked?.let { item ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(item.name) },
            text = { Text("原位置：${item.originalPath}") },
            confirmButton = { TextButton({ picked = null; act { vm.app.trash.restore(item) } }) { Text("恢复") } },
            dismissButton = {
                TextButton({ picked = null; act { vm.app.trash.purge(item) } }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空回收站") },
            text = { Text("${items.size} 项将被永久删除。") },
            confirmButton = {
                TextButton({ confirmEmpty = false; act { items.forEach { vm.app.trash.purge(it) } } }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ confirmEmpty = false }) { Text("取消") } },
        )
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, back: () -> Unit) {
    val p = vm.prefs
    var cleanDialog by remember { mutableStateOf(false) }
    SimpleScaffold("设置", back) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Group("外观")
            ThemePicker(vm)
            Group("界面")
            Toggle("双栏", "竖屏左右两栏；关闭后只显示一栏", p.dualPane) { p.update { dualPane = it } }
            Toggle("紧凑列表", "更小的图标和行距", p.compact) { p.update { compact = it } }
            Toggle("显示隐藏文件", "以 . 开头的文件和文件夹", p.showHidden) { p.update { showHidden = it } }
            Group("文件操作")
            Toggle("保留修改时间", "复制、解压时保持原文件的修改时间", p.keepMtime) { p.update { keepMtime = it } }
            Group("回收站")
            Toggle("启用回收站", "删除本地文件时可以先移到回收站", p.trashEnabled) { p.update { trashEnabled = it } }
            Toggle("默认移到回收站", "删除对话框里默认勾选", p.trashByDefault) { p.update { trashByDefault = it } }
            Item("自动清理", if (p.trashAutoCleanDays > 0) "${p.trashAutoCleanDays} 天后" else "不自动清理") { cleanDialog = true }
            Group("首页")
            Item("左窗口首页", p.homeLeft.ifEmpty { "内部存储" }) { p.update { homeLeft = "" } }
            Item("右窗口首页", p.homeRight.ifEmpty { "内部存储" }) { p.update { homeRight = "" } }
            Text("在主界面菜单里点「设为首页」修改；点这里恢复为内部存储。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp, color = LocalTwin.current.muted)
            Group("关于")
            Item("TwinFile", "开源免费的双栏文件管理器 · MIT 许可") {}
        }
    }
    if (cleanDialog) {
        AlertDialog(
            onDismissRequest = { cleanDialog = false },
            title = { Text("自动清理回收站") },
            text = {
                Column {
                    listOf(0, 7, 30, 90).forEach { d ->
                        Row(Modifier.fillMaxWidth().clickable { p.update { trashAutoCleanDays = d }; cleanDialog = false }.padding(12.dp)) {
                            Text(if (d == 0) "不自动清理" else "$d 天后清理",
                                color = if (p.trashAutoCleanDays == d) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun ThemePicker(vm: MainViewModel) {
    val p = vm.prefs
    val dark = isDark(DarkMode.of(p.darkMode))
    Text("主题色", Modifier.padding(start = 16.dp, top = 6.dp), fontSize = 15.sp)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Palette.entries.filter { it != Palette.DYNAMIC || Palette.dynamicSupported }.forEach { pal ->
            val selected = Palette.of(p.palette) == pal
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { p.update { palette = pal.name } }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val color = if (pal == Palette.DYNAMIC) MaterialTheme.colorScheme.primary
                else Color(if (dark) pal.dark else pal.light)
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (pal == Palette.DYNAMIC) Brush.sweepGradient(listOf(Color(0xFF6E8BD6), Color(0xFF7FB89A), Color(0xFFD4B07A), Color(0xFFC98AA0), Color(0xFF6E8BD6))) else SolidColor(color))
                        .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Text(pal.label, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else LocalTwin.current.muted)
            }
        }
    }
    Text("深色模式", Modifier.padding(start = 16.dp, top = 4.dp), fontSize = 15.sp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        DarkMode.entries.forEachIndexed { i, m ->
            val on = DarkMode.of(p.darkMode) == m
            val shape = when (i) {
                0 -> RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
                DarkMode.entries.size - 1 -> RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
                else -> RoundedCornerShape(0.dp)
            }
            Box(
                Modifier.weight(1f).clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { p.update { darkMode = m.name } }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(m.label, fontSize = 14.sp, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp), fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(sub, fontSize = 12.sp, color = LocalTwin.current.muted)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun Item(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, fontSize = 15.sp)
        Text(sub, fontSize = 12.sp, color = LocalTwin.current.muted)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = LocalTwin.current.pane)
}
