package app.twinfile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.twinfile.fs.FEntry
import app.twinfile.ops.Archive
import app.twinfile.ops.Ops
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        granted = hasStorage()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        setContent {
            val prefs = vm.prefs
            val dark = isDark(DarkMode.of(prefs.darkMode))
            androidx.compose.runtime.LaunchedEffect(dark) {
                // Status/navigation bar icons follow the app's own light/dark choice.
                val style = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            TwinTheme(Palette.of(prefs.palette), DarkMode.of(prefs.darkMode)) {
                if (!granted) PermissionScreen(::requestStorage)
                else AppNav()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val now = hasStorage()
        if (now && !granted) vm.refreshAll()
        granted = now
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStorage() && !granted) {
            granted = true
            vm.refreshAll()
        }
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    @Composable
    private fun AppNav() {
        var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
        BackHandler(screen != Screen.MAIN) { screen = Screen.MAIN }
        val back = { screen = Screen.MAIN }
        when (screen) {
            Screen.MAIN -> {
                MainScreen(vm, { screen = it }, ::openFile, actions)
                when (val v = vm.viewer) {
                    is Viewer.Images -> ImageViewer(v, { vm.viewer = null },
                        share = { Opener.share(this, listOf(it)) }, openExternal = { Opener.open(this, it) })
                    is Viewer.Text -> TextViewer(v, { vm.viewer = null },
                        openExternal = if (v.source === vm.app.local) ({ Opener.open(this, File(v.path), "text/plain") }) else null)
                    null -> {}
                }
            }
            Screen.TRASH -> TrashScreen(vm, back)
            Screen.SETTINGS -> SettingsScreen(vm, back)
            Screen.APPS, Screen.NETWORK, Screen.PC -> ComingSoon(screen, back)
        }
    }

    private fun openFile(pane: PaneState, e: FEntry) {
        vm.openFile(pane, e) { f -> if (!Opener.open(this, f)) vm.say("没有可以打开此文件的应用") }
    }

    private val actions = object : FileActions {
        override fun openWith(pane: PaneState, e: FEntry) {
            vm.withLocalCopy(pane, e) { f -> if (!Opener.open(this@MainActivity, f, "*/*")) vm.say("没有可用的应用") }
        }

        override fun share(pane: PaneState, entries: List<FEntry>) {
            Opener.share(this@MainActivity, entries.map { localFile(pane, it) })
        }

        override fun compress(pane: PaneState, entries: List<FEntry>, name: String) {
            val n = name.trim().let { if (it.endsWith(".zip", true)) it else "$it.zip" }
            if ('/' in n) return vm.say("名称无效")
            val dir = pane.path
            val files = entries.map { localFile(pane, it) }
            pane.selected = emptySet()
            Ops.start("压缩为 $n", { job -> Archive.compress(files, File(dir, n), job.stats) }) {
                runOnUiThread { vm.refreshShowing(vm.app.local, dir) }
            }
        }

        override fun extract(pane: PaneState, e: FEntry, toOther: Boolean) {
            val zip = localFile(pane, e)
            val target = if (toOther && vm.other.source === vm.app.local) vm.other.path else pane.path
            Ops.start("解压 ${e.name}", { job -> Archive.extract(zip, File(target), job.stats) }) {
                runOnUiThread { vm.refreshShowing(vm.app.local, target) }
            }
        }
    }
}

@Composable
private fun PermissionScreen(request: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TwinFile", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "文件管理器需要「所有文件访问权限」才能浏览和管理手机上的文件。\n文件只在本机处理，不会上传。",
            textAlign = TextAlign.Center, color = LocalTwin.current.muted,
        )
        Spacer(Modifier.height(24.dp))
        Button(request) { Text("授予权限") }
    }
}

@Composable
private fun ComingSoon(screen: Screen, back: () -> Unit) {
    val title = when (screen) {
        Screen.APPS -> "已安装应用"
        Screen.NETWORK -> "网络位置"
        Screen.PC -> "电脑"
        else -> ""
    }
    SimpleScaffold(title, back) {
        Text("开发中", Modifier.padding(24.dp), color = LocalTwin.current.muted)
    }
}

