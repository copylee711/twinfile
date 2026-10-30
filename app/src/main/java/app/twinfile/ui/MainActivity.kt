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
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.twinfile.cloud.CloudAccount
import app.twinfile.cloud.CloudProvider
import app.twinfile.cloud.CloudLoginCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.twinfile.fs.FEntry
import app.twinfile.ops.Archive
import app.twinfile.ops.Ops
import app.twinfile.ops.ArchiveFormat
import java.io.File

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_LOCATE = "app.twinfile.LOCATE"
    }

    private val vm: MainViewModel by viewModels()
    /** SyncBoard's approval dialog; it needs a result-style launch to know who is asking. */
    private val syncBoardApproval = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        vm.syncBoardApproval?.invoke(r.resultCode == RESULT_OK)
        vm.syncBoardApproval = null
    }
    private var granted by mutableStateOf(false)
    private val googleAuthorization = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            vm.cloudBusy = false
            vm.cloudStatus = "已取消登录"
        } else {
            val token = runCatching { vm.app.cloud.auth.googleResult(result.data) }
            token.onSuccess(::finishGoogleLogin).onFailure { cloudLoginFailed() }
        }
    }

    private fun cloudLoginFailed(cause: Throwable? = null) {
        vm.cloudBusy = false
        vm.cloudStatus = if (cause is CloudLoginCancelled) "已取消登录" else "云盘登录失败，请检查网络、账号授权和应用配置后重试"
    }

    private fun finishGoogleLogin(token: String) {
        lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) { vm.app.cloud.auth.identifyGoogle(token).also { vm.app.cloud.validate(it) } }
                vm.cloudConnected(account)
            } catch (_: Exception) { cloudLoginFailed() }
        }
    }

    private fun connectCloud(provider: CloudProvider) {
        if (vm.cloudBusy) return
        vm.cloudBusy = true
        vm.cloudStatus = null
        val auth = vm.app.cloud.auth
        if (provider == CloudProvider.GOOGLE) {
            auth.startGoogle(this, { pending ->
                try { googleAuthorization.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
                catch (_: Exception) { cloudLoginFailed() }
            }) { result -> result.onSuccess(::finishGoogleLogin).onFailure { cloudLoginFailed() } }
        } else lifecycleScope.launch {
            try {
                val client = withContext(Dispatchers.IO) { auth.microsoft() }
                auth.startMicrosoft(this@MainActivity, client) { result ->
                    lifecycleScope.launch {
                        try {
                            val account = result.getOrThrow()
                            withContext(Dispatchers.IO) { vm.app.cloud.validate(account) }
                            vm.cloudConnected(account)
                        } catch (e: Exception) { cloudLoginFailed(e) }
                    }
                }
            } catch (_: Exception) { cloudLoginFailed() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferHighRefreshRate()
        granted = hasStorage()
        handleLocate(intent)
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
                vm.update?.let { UpdateDialog(vm, it) }
                val passwordRequest = Ops.jobs.firstNotNullOfOrNull { it.passwords.request }
                    ?: vm.archivePasswords.request
                passwordRequest?.let { ArchivePasswordDialog(it) }
                androidx.compose.runtime.LaunchedEffect(vm.syncBoardApproval) {
                    if (vm.syncBoardApproval != null) {
                        try {
                            syncBoardApproval.launch(app.twinfile.pc.SyncBoardBridge.approvalIntent())
                        } catch (_: android.content.ActivityNotFoundException) {
                            vm.say("请先把 SyncBoard 更新到最新版本")
                            vm.syncBoardApproval = null
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLocate(intent)
    }

    /** From [LocateActivity]: show the file selected in the active pane. */
    private fun handleLocate(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_LOCATE) ?: return
        intent.removeExtra(EXTRA_LOCATE)
        vm.viewer = null
        vm.locateTick++
        vm.openBookmark(vm.active, "local", path, isDir = File(path).isDirectory)
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

    /**
     * Some ROMs (OriginOS among them) keep apps at 60 Hz unless the window asks for more.
     * Request the fastest mode that keeps the current resolution.
     */
    private fun preferHighRefreshRate() {
        @Suppress("DEPRECATION")
        val display = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.refreshRate <= current.refreshRate + 1f && best.modeId == current.modeId) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
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
        androidx.compose.runtime.LaunchedEffect(vm.locateTick) { if (vm.locateTick > 0) screen = Screen.MAIN }
        val back = { screen = Screen.MAIN }
        when (screen) {
            Screen.MAIN -> {
                MainScreen(vm, { screen = it }, ::openFile, actions)
                when (val v = vm.viewer) {
                    is Viewer.Images -> ImageViewer(v, { vm.viewer = null },
                        share = { Opener.share(this, listOf(it)) }, openExternal = { openExternal(it, choose = true) })
                    is Viewer.Text -> TextViewer(v, { vm.viewer = null },
                        openExternal = if (v.source === vm.app.local) ({ openExternal(File(v.path), choose = true) }) else null,
                        onSaved = { vm.refreshShowing(v.source, app.twinfile.fs.Paths.parent(v.path) ?: "/"); vm.say("已保存") })
                    null -> {}
                }
                vm.openRequest?.let { req ->
                    OpenWithDialog(req, setDefault = { k, c -> vm.prefs.update { openers[k] = c.flattenToString() } },
                        close = { vm.openRequest = null })
                }
            }
            Screen.TRASH -> TrashScreen(vm, back)
            Screen.SETTINGS -> SettingsScreen(vm, back)
            Screen.APPS -> AppsScreen(vm, back) { f ->
                // Show the extracted file highlighted in the active pane.
                screen = Screen.MAIN
                vm.openBookmark(vm.active, "local", f.path, isDir = false)
                vm.say("已提取到 ${f.parent}")
            }
            Screen.NETWORK -> NetworkScreen(vm, back) { c ->
                screen = Screen.MAIN
                vm.openSource(vm.active, c.sourceId)
            }
            Screen.CLOUD -> CloudScreen(vm, back, ::connectCloud) { account ->
                screen = Screen.MAIN
                vm.openSource(vm.active, account.sourceId)
            }
            Screen.PC -> PcScreen(vm, back) { pc ->
                screen = Screen.MAIN
                vm.openSource(vm.active, pc.sourceId)
            }
        }
    }

    private fun openFile(pane: PaneState, e: FEntry) {
        val format = Archive.format(e.name)
        if (!e.isDir && pane.source === vm.app.local && format != null && format != ArchiveFormat.ZIP) {
            actions.extract(pane, e, false)
            return
        }
        vm.openFile(pane, e) { f -> openExternal(f, choose = false) }
    }

    /** Opens [f] with its remembered default app, or asks with the 打开方式 dialog. */
    private fun openExternal(f: File, choose: Boolean) {
        val mime = Opener.mime(f.name)
        if (!choose) {
            val saved = vm.prefs.openers[Opener.defaultKey(f, mime)]?.let { android.content.ComponentName.unflattenFromString(it) }
            if (saved != null && Opener.launch(this, f, mime, saved)) return
            // No default of our own: let the system pick (its own default, or its chooser).
            if (Opener.launch(this, f, mime, null)) return
        }
        vm.openRequest = OpenRequest(f, mime)
    }

    private val actions = object : FileActions {
        override fun openWith(pane: PaneState, e: FEntry) {
            vm.withLocalCopy(pane, e) { f -> openExternal(f, choose = true) }
        }

        override fun share(pane: PaneState, entries: List<FEntry>) {
            Opener.share(this@MainActivity, entries.map { localFile(pane, it) })
        }

        override fun compress(pane: PaneState, entries: List<FEntry>, name: String, format: ArchiveFormat, password: CharArray?) {
            val n = name.trim()
            if (n.isEmpty() || '/' in n || '\\' in n) { password?.fill('\u0000'); return vm.say("名称无效") }
            val dir = pane.path
            val files = entries.map { localFile(pane, it) }
            pane.selected = emptySet()
            Ops.start("压缩为 $n", { job ->
                try { Archive.compress(files, File(dir, n), job.stats, format, password) }
                finally { password?.fill('\u0000') }
            }) {
                runOnUiThread { vm.refreshShowing(vm.app.local, dir) }
            }
        }

        override fun extract(pane: PaneState, e: FEntry, toOther: Boolean) {
            val zip = localFile(pane, e)
            val target = if (toOther && vm.other.source === vm.app.local) vm.other.path else pane.path
            Ops.start("智能解压 ${e.name}", { job ->
                job.passwords.run(zip, { job.stats.cancelled }) { password -> Archive.extract(zip, File(target), job.stats, password) }
            }) {
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
