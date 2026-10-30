package app.twinfile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.twinfile.cloud.CloudAccount
import app.twinfile.cloud.CloudProvider

@Composable
fun CloudScreen(vm: MainViewModel, back: () -> Unit, connect: (CloudProvider) -> Unit, open: (CloudAccount) -> Unit) {
    val cloud = vm.app.cloud
    var removing by remember { mutableStateOf<CloudAccount?>(null) }
    SimpleScaffold("云盘", back) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("连接后，可以在双栏中浏览、复制、移动和管理云端文件。授权由服务商处理，文件在手机与云盘之间直接传输。",
                style = MaterialTheme.typography.bodyMedium)
            CloudProvider.entries.forEach { provider ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(provider.label, style = MaterialTheme.typography.titleMedium)
                        }
                        Text(if (provider == CloudProvider.GOOGLE) "访问完整的「我的云端硬盘」，包含已有文件。" else "支持个人账号，以及允许授权的工作或学校账号。",
                            style = MaterialTheme.typography.bodySmall)
                        val available = cloud.auth.enabled(provider)
                        Button({ connect(provider) }, enabled = available && !vm.cloudBusy) { Text("连接 ${provider.label}") }
                        if (!available) Text("此版本暂未开放登录。", style = MaterialTheme.typography.bodySmall, color = LocalTwin.current.muted)
                    }
                }
            }
            if (vm.cloudBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text("正在处理云盘授权…")
                }
            }
            vm.cloudStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text("已连接的账号", style = MaterialTheme.typography.titleMedium)
            if (cloud.accounts.isEmpty()) Text("还没有连接云盘。", color = LocalTwin.current.muted)
            cloud.accounts.forEach { account ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !vm.cloudBusy) { open(account) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(account.provider.label)
                        Text(account.display, style = MaterialTheme.typography.bodySmall, color = LocalTwin.current.muted)
                    }
                    TextButton({ removing = account }, enabled = !vm.cloudBusy) { Text("移除") }
                }
            }
            Text("Google 文档、表格、幻灯片和绘图会导出为 PDF。共享快捷方式暂不支持打开；上传需要足够的本机缓存空间。",
                style = MaterialTheme.typography.bodySmall, color = LocalTwin.current.muted)
        }
    }
    removing?.let { account ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除 ${account.provider.label}") },
            text = { Text("将从 TwinFile 移除 ${account.display}，清除本机预览缓存并尝试撤销应用授权。云端文件会保留。") },
            confirmButton = { TextButton({ removing = null; vm.disconnectCloud(account) }) { Text("移除") } },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } })
    }
}
