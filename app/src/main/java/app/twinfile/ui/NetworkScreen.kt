package app.twinfile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.twinfile.net.HostKeyUnknown
import app.twinfile.net.NetConfig
import app.twinfile.net.NetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetworkScreen(vm: MainViewModel, back: () -> Unit, openIn: (NetConfig) -> Unit) {
    var editing by remember { mutableStateOf<NetConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<NetConfig?>(null) }
    val net = vm.app.net
    if (editing != null || adding) {
        NetEditor(vm, editing, close = { editing = null; adding = false })
        return
    }
    SimpleScaffold("网络位置", back, actions = {
        IconButton({ adding = true }) { Icon(Icons.Filled.Add, "添加") }
    }) {
        if (net.locations.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("还没有网络位置", fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text("支持 FTP、FTPS、SFTP、SMB（Windows 共享 / NAS）和 WebDAV，全部免费。",
                    color = LocalTwin.current.muted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Button({ adding = true }) { Text("添加网络位置") }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            net.locations.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { openIn(c) }, onLongClick = { menuFor = c })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Lan, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.display, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.type.label} · ${c.user.ifBlank { if (c.type == NetType.SMB) "访客" else "匿名" }}@${c.host}:${c.port}${c.path}",
                            fontSize = 12.sp, color = LocalTwin.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ editing = c }) { Text("编辑") }
                }
            }
            if (net.locations.isNotEmpty()) {
                Text("点击在当前窗口打开；空闲 2 分钟后自动断开连接。", Modifier.padding(16.dp), fontSize = 12.sp,
                    color = LocalTwin.current.muted)
            }
        }
    }
    menuFor?.let { c ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(c.display) },
            text = { Text("${c.type.label}  ${c.host}:${c.port}") },
            confirmButton = { TextButton({ menuFor = null; editing = c }) { Text("编辑") } },
            dismissButton = {
                TextButton({ menuFor = null; net.remove(c) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun NetEditor(vm: MainViewModel, initial: NetConfig?, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(initial?.type ?: NetType.SMB) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var path by remember { mutableStateOf(initial?.path ?: "") }
    var encoding by remember { mutableStateOf(initial?.encoding ?: "UTF-8") }
    var tls by remember { mutableStateOf(initial?.tls ?: false) }
    var trustAll by remember { mutableStateOf(initial?.trustAll ?: false) }
    var keyFile by remember { mutableStateOf(initial?.keyFile ?: "") }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var hostKey by remember { mutableStateOf(initial?.hostKey ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var askTrust by remember { mutableStateOf<String?>(null) }

    fun build() = NetConfig(
        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
        type = type, name = name.trim(), host = host.trim(),
        port = port.toIntOrNull() ?: if (type == NetType.FTPS && tls) 990 else if (type == NetType.WEBDAV && tls) 443 else type.defaultPort,
        user = user.trim(), password = password, path = path.trim().replace('\\', '/').ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" },
        encoding = encoding, tls = tls, trustAll = trustAll, keyFile = keyFile.trim(), hostKey = hostKey, domain = domain.trim(),
    )

    fun test(then: (() -> Unit)? = null) {
        if (host.isBlank()) { status = "请填写主机地址"; return }
        if (type == NetType.SMB && app.twinfile.fs.Paths.normalize(build().path) == "/") {
            status = "连接失败：请填写共享路径，例如 /share；暂不支持自动发现共享"
            return
        }
        testing = true
        status = "正在连接…"
        val cfg = build()
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                val src = vm.app.net.create(cfg)
                try {
                    val n = src.list(src.home()).size
                    Result.success(n)
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    src.close()
                }
            }
            testing = false
            r.onSuccess { status = "连接成功，${cfg.path} 下有 $it 项"; then?.invoke() }
            r.onFailure { e ->
                if (e is HostKeyUnknown) { askTrust = e.fingerprint; status = null }
                else status = "连接失败：${e.message}"
            }
        }
    }

    SimpleScaffold(if (initial == null) "添加网络位置" else "编辑网络位置", close) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetType.entries.forEach { t ->
                    FilterChip(type == t, { type = t; hostKey = "" }, label = { Text(t.label) })
                }
            }
            Field("名称（可选）", name) { name = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("主机", host, Modifier.weight(2f), hint = "192.168.1.10") { host = it; hostKey = "" }
                Field("端口", port, Modifier.weight(1f), hint = type.defaultPort.toString(), number = true) { port = it }
            }
            Field("用户名" + when (type) { NetType.SMB -> "（留空为访客）"; NetType.FTP -> "（留空为匿名）"; else -> "" }, user) { user = it }
            Field(if (type == NetType.SFTP && keyFile.isNotBlank()) "私钥密码" else "密码", password, password = true) { password = it }
            Field(
                when (type) {
                    NetType.SMB -> "共享路径，如 /share 或 /share/文件夹"
                    NetType.WEBDAV -> "服务器路径，如 /dav 或 /remote.php/dav/files/用户名"
                    else -> "初始路径"
                }, path, hint = if (type == NetType.SMB) "/share" else "/",
            ) { path = it }
            if (type == NetType.SMB) Text("必须填写共享名，例如 Windows 共享名为 Public 时填写 /Public。暂不支持自动发现共享。",
                fontSize = 12.sp, color = LocalTwin.current.muted)
            when (type) {
                NetType.SFTP -> Field("私钥文件（可选，手机上的路径）", keyFile, hint = "/sdcard/.ssh/id_ed25519") { keyFile = it }
                NetType.SMB -> Field("域（可选）", domain) { domain = it }
                NetType.FTP, NetType.FTPS -> {
                    Text("编码", Modifier.padding(top = 10.dp), fontSize = 13.sp, color = LocalTwin.current.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("UTF-8", "GBK").forEach { e -> FilterChip(encoding == e, { encoding = e }, label = { Text(e) }) }
                    }
                }
                else -> {}
            }
            if (type == NetType.FTPS) CheckRow("隐式 TLS（通常端口 990）", tls) { tls = it }
            if (type == NetType.WEBDAV) CheckRow("使用 HTTPS", tls) { tls = it }
            if (type == NetType.FTPS || type == NetType.WEBDAV) CheckRow("信任所有证书（自签名证书时使用）", trustAll) { trustAll = it }
            if (type == NetType.SFTP && hostKey.isNotEmpty()) {
                Text("主机指纹：$hostKey", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = LocalTwin.current.muted)
            }
            status?.let { Text(it, Modifier.padding(top = 12.dp), fontSize = 13.sp,
                color = if (it.startsWith("连接失败")) MaterialTheme.colorScheme.error else LocalTwin.current.muted) }
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ test() }, enabled = !testing) { Text("测试连接") }
                Button({
                    if (type == NetType.SMB || (type == NetType.SFTP && hostKey.isEmpty())) test { vm.app.net.upsert(build()); close() }
                    else { vm.app.net.upsert(build()); close() }
                }, enabled = !testing && host.isNotBlank()) { Text("保存") }
            }
            Text("密码使用 Android Keystore 加密后保存在本机。", fontSize = 12.sp, color = LocalTwin.current.muted)
            Spacer(Modifier.height(24.dp))
        }
    }
    askTrust?.let { fp ->
        AlertDialog(
            onDismissRequest = { askTrust = null },
            title = { Text("确认主机指纹") },
            text = { Text("第一次连接 $host，服务器的指纹是：\n\n$fp\n\n确认与服务器一致后再信任。以后指纹变化时会拒绝连接。") },
            confirmButton = { TextButton({ askTrust = null; hostKey = fp; test() }) { Text("信任") } },
            dismissButton = { TextButton({ askTrust = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), hint: String = "",
                  password: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, modifier.padding(top = 8.dp), label = { Text(label) }, singleLine = true,
        placeholder = { if (hint.isNotEmpty()) Text(hint) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            password -> KeyboardType.Password
            number -> KeyboardType.Number
            else -> KeyboardType.Uri
        }),
    )
}

