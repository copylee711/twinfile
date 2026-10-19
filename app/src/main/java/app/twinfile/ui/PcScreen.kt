package app.twinfile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.twinfile.pc.PcInfo
import app.twinfile.pc.PcPairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Paired SyncBoard PCs: open one in a pane, pair a new one by QR or by code. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PcScreen(vm: MainViewModel, back: () -> Unit, openIn: (PcInfo) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pcm = vm.app.pc
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<PcInfo?>(null) }
    var renaming by remember { mutableStateOf<PcInfo?>(null) }
    var found by remember { mutableStateOf<List<JSONObject>?>(null) }
    var code by remember { mutableStateOf<Pair<String, String>?>(null) }
    var manual by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else error = "需要相机权限才能扫码；也可以用「局域网配对」"
    }

    fun run(label: String, block: suspend () -> Unit) {
        busy = label
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            busy = null
            code = null
        }
    }

    if (scanning) {
        SimpleScaffold("扫描电脑上的二维码", { scanning = false }) {
            Text("在电脑上打开 SyncBoard，点「添加手机」，扫描显示的二维码。", Modifier.padding(horizontal = 16.dp),
                fontSize = 14.sp, color = LocalTwin.current.muted)
            Spacer(Modifier.height(12.dp))
            QrScanner(
                Modifier.fillMaxWidth().padding(16.dp).aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                accept = { PcPairing.parseQr(it) != null },
            ) { text ->
                scanning = false
                run("正在配对…") {
                    val pc = withContext(Dispatchers.IO) { pcm.pairQr(text) }
                    vm.say("已与「${pc.display}」配对")
                }
            }
        }
        return
    }

    SimpleScaffold("电脑", back, actions = {
        IconButton({
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
            else camera.launch(Manifest.permission.CAMERA)
        }) { Icon(Icons.Filled.Add, "扫码配对") }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (pcm.pcs.isEmpty()) {
                Column(Modifier.padding(24.dp)) {
                    Text("还没有配对的电脑", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. 在电脑上安装并打开 SyncBoard\n2. 在电脑的 SyncBoard 设置里打开「允许已配对设备浏览这台电脑的文件」\n3. 点「添加手机」，用这里扫码\n\n配对后，电脑的磁盘会像本地文件夹一样出现在两个窗口里，可以直接复制、移动。",
                        fontSize = 14.sp, color = LocalTwin.current.muted, lineHeight = 21.sp,
                    )
                }
            }
            pcm.pcs.forEach { pc ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { openIn(pc) }, onLongClick = { menuFor = pc })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Computer, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pc.display, fontSize = 15.sp)
                        val online = pcm.source(pc)?.link?.online == true
                        Text((if (online) "已连接 · " else "") + pc.hosts.firstOrNull().orEmpty(), fontSize = 12.sp, color = LocalTwin.current.muted)
                    }
                    TextButton({ menuFor = pc }) { Text("管理") }
                }
            }
            Row(Modifier.padding(16.dp)) {
                Button({
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                    else camera.launch(Manifest.permission.CAMERA)
                }) { Text("扫码配对") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton({
                    run("正在查找局域网里的电脑…") { found = withContext(Dispatchers.IO) { pcm.discover() } }
                }) { Text("局域网配对") }
            }
            TextButton({ manual = true }, Modifier.padding(horizontal = 8.dp)) { Text("找不到电脑？输入电脑的 IP 地址配对") }
            busy?.let {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(it, fontSize = 14.sp)
                }
            }
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        }
    }

    found?.let { list ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("局域网里的电脑") },
            text = {
                Column {
                    if (list.isEmpty()) Text("没有找到。确认电脑上的 SyncBoard 正在运行、和手机在同一网络；也可以扫码配对。")
                    list.forEach { o ->
                        Box(Modifier.fillMaxWidth().clickable {
                            found = null
                            run("请在电脑上确认配对…") {
                                val pc = withContext(Dispatchers.IO) {
                                    pcm.pairCode(o.getString("addr"), o.optInt("port", 53317)) { c, name -> code = c to name }
                                }
                                vm.say("已与「${pc.display}」配对")
                            }
                        }.padding(vertical = 12.dp)) {
                            Text("${o.optString("name")}  ${o.optString("addr")}")
                        }
                    }
                }
            },
            confirmButton = { TextButton({ found = null }) { Text("关闭") } },
        )
    }
    if (manual) {
        InputDialog("输入电脑地址", hint = "例如 192.168.1.5 或 192.168.1.5:53317", onDismiss = { manual = false },
            buttons = listOf("配对" to { text: String ->
                manual = false
                val t = text.trim()
                val host = t.substringBefore(':')
                val port = t.substringAfter(':', "").toIntOrNull() ?: 53317
                if (host.isNotEmpty()) run("请在电脑上确认配对…") {
                    val pc = withContext(Dispatchers.IO) { pcm.pairCode(host, port) { c, name -> code = c to name } }
                    vm.say("已与「${pc.display}」配对")
                }
            }))
    }
    code?.let { (c, name) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("在电脑上确认") },
            text = { Text("「$name」上应显示同样的配对码：\n\n$c\n\n一致时在电脑上点「接受」。", fontSize = 16.sp) },
            confirmButton = {},
        )
    }
    menuFor?.let { pc ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(pc.display) },
            text = { Text("地址：${pc.hosts.joinToString()}") },
            confirmButton = { TextButton({ menuFor = null; renaming = pc }) { Text("备注") } },
            dismissButton = {
                TextButton({ menuFor = null; pcm.unpair(pc) }) { Text("取消配对", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    renaming?.let { pc ->
        InputDialog("备注", pc.alias.ifBlank { pc.name }, onDismiss = { renaming = null },
            buttons = listOf("确定" to { n: String -> pcm.rename(pc, n); renaming = null }))
    }
}
