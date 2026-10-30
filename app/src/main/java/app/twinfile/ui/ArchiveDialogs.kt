package app.twinfile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.twinfile.ops.ArchiveFormat
import app.twinfile.ops.ArchivePasswordRequest

@Composable
private fun ArchivePasswordField(value: String, change: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, change, label = { Text("密码") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton({ visible = !visible }) { Text(if (visible) "隐藏" else "显示") } })
}

@Composable
fun ArchivePasswordDialog(request: ArchivePasswordRequest) {
    var password by remember(request) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { request.answer(null) }, title = { Text("压缩包密码") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.name)
                request.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ArchivePasswordField(password) { password = it }
            }
        },
        confirmButton = { TextButton({
            val secret = password.toCharArray(); password = ""; request.answer(secret)
        }, enabled = password.isNotEmpty()) { Text("继续") } },
        dismissButton = { TextButton({ password = ""; request.answer(null) }) { Text("取消") } })
}

@Composable
fun CreateArchiveDialog(initial: String, close: () -> Unit,
                        create: (String, ArchiveFormat, CharArray?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var encrypted by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && name.trim() !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it == '\u0000' }
    AlertDialog(onDismissRequest = close, title = { Text("创建压缩包") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), isError = !valid,
                    supportingText = { Text(if (valid) "文件名：${name.trim()}${format.suffix}" else "请填写名称，不能包含路径分隔符") })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArchiveFormat.entries.forEach { f ->
                        FilterChip(format == f, { format = f; if (f != ArchiveFormat.ZIP) { encrypted = false; password = "" } }, label = { Text(f.label) })
                    }
                }
                if (format == ArchiveFormat.ZIP) {
                    CheckRow("设置密码（AES-256）", encrypted) { encrypted = it; if (!it) password = "" }
                    if (encrypted) ArchivePasswordField(password) { password = it }
                    Text("设置密码后，解压需要输入密码；文件名仍可见。", style = MaterialTheme.typography.bodySmall)
                } else Text("此格式暂不支持设置密码；如需加密请选择 ZIP。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({
            val secret = if (encrypted) password.toCharArray() else null
            password = ""; create(name.trim() + format.suffix, format, secret)
        }, enabled = valid && (!encrypted || password.isNotEmpty())) { Text("压缩") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}
