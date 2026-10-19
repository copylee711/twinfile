package app.twinfile.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.twinfile.ops.ApkExtract
import app.twinfile.ops.Ops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class AppItem(val label: String, val pkg: String, val version: String, val size: Long, val system: Boolean, val info: ApplicationInfo)

private object AppIcons {
    val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    val permits = Semaphore(4)
}

/** Where extracted APKs go. */
fun apkOutDir(): File = File(Environment.getExternalStorageDirectory(), "TwinFile/apks")

@Composable
fun AppsScreen(vm: MainViewModel, back: () -> Unit, onExtracted: (File) -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<AppItem?>(null) }
    val all by produceState<List<AppItem>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }
    SimpleScaffold("已安装应用", back, actions = {
        androidx.compose.material3.IconButton({ searching = !searching; if (!searching) query = "" }) {
            Icon(Icons.Filled.Search, "搜索")
        }
    }) {
        if (searching) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true, placeholder = { Text("名称或包名") })
        }
        val list = all
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("用户应用" + (list?.count { !it.system }?.let { " $it" } ?: "")) })
            Tab(tab == 1, { tab = 1 }, text = { Text("系统应用" + (list?.count { it.system }?.let { " $it" } ?: "")) })
        }
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@SimpleScaffold
        }
        val q = query.trim()
        val shown = list.filter { it.system == (tab == 1) }
            .filter { q.isEmpty() || it.label.contains(q, true) || it.pkg.contains(q, true) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { app ->
                Row(
                    Modifier.fillMaxWidth().clickable { picked = app }.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app, 44)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${app.version} · ${Format.size(app.size)}" + if (ApkExtract.isSplit(app.info)) " · 分包" else "",
                            fontSize = 12.sp, color = LocalTwin.current.muted)
                        Text(app.pkg, fontSize = 12.sp, color = LocalTwin.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    picked?.let { app -> AppDialog(vm, app, { picked = null }, onExtracted) }
}

@Composable
private fun AppIcon(app: AppItem, sizeDp: Int) {
    val context = LocalContext.current
    val bmp by produceState(AppIcons.cache.get(app.pkg), app.pkg) {
        if (value == null) value = AppIcons.permits.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val px = (sizeDp * context.resources.displayMetrics.density).toInt()
                    app.info.loadIcon(context.packageManager).toBitmap(px, px).also { AppIcons.cache.put(app.pkg, it) }
                }.getOrNull()
            }
        }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(sizeDp.dp))
    else Icon(Icons.Filled.Android, null, Modifier.size(sizeDp.dp), tint = LocalTwin.current.apk)
}

private fun loadApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
    else pm.getInstalledPackages(0)
    return pkgs.mapNotNull { p ->
        val ai = p.applicationInfo ?: return@mapNotNull null
        val system = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        AppItem(
            label = ai.loadLabel(pm).toString(),
            pkg = p.packageName,
            version = p.versionName ?: "",
            size = ApkExtract.files(ai).sumOf { it.length() },
            system = system,
            info = ai,
        )
    }.sortedWith(compareBy<AppItem, String>(app.twinfile.fs.NaturalOrder) { it.label })
}

private data class Details(val pkg: PackageInfo, val certSha256: String?, val certSubject: String?)

@Suppress("DEPRECATION")
private fun details(context: Context, pkg: String): Details {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val p = pm.getPackageInfo(pkg, flags)
    val sig = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.let {
        if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
    }?.firstOrNull() else p.signatures?.firstOrNull()
    val bytes = sig?.toByteArray()
    val cert = bytes?.let { runCatching { CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as X509Certificate }.getOrNull() }
    val sha = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString(":") { b -> "%02X".format(b) } }
    return Details(p, sha, cert?.subjectX500Principal?.name)
}

@Composable
private fun AppDialog(vm: MainViewModel, app: AppItem, close: () -> Unit, onExtracted: (File) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val d by produceState<Details?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) { runCatching { details(context, app.pkg) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = close,
        icon = { AppIcon(app, 48) },
        title = { Text(app.label, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable fun row(k: String, v: String?) {
                    if (v.isNullOrEmpty()) return
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(k, Modifier.width(76.dp), fontSize = 13.sp, color = LocalTwin.current.muted)
                        SelectionContainer { Text(v, fontSize = 13.sp) }
                    }
                }
                val p = d?.pkg
                row("包名", app.pkg)
                row("版本", p?.let {
                    val code = if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
                    "${it.versionName} ($code)"
                })
                row("安装包", Format.size(app.size) + if (ApkExtract.isSplit(app.info)) "（${ApkExtract.files(app.info).size} 个分包）" else "")
                row("目标系统", "API ${app.info.targetSdkVersion}")
                if (Build.VERSION.SDK_INT >= 24) row("最低系统", "API ${app.info.minSdkVersion}")
                row("UID", app.info.uid.toString())
                row("首次安装", p?.let { Format.fullTime(it.firstInstallTime) })
                row("最后更新", p?.let { Format.fullTime(it.lastUpdateTime) })
                row("APK 路径", app.info.sourceDir)
                row("数据目录", app.info.dataDir)
                row("签名", d?.certSubject)
                row("SHA-256", d?.certSha256)
            }
        },
        confirmButton = {
            TextButton({
                close()
                val label = "${app.label}_${app.version}"
                var out: File? = null
                Ops.start("提取 ${app.label}", { job -> out = ApkExtract.extract(app.info, apkOutDir(), label, job.stats) }) {
                    out?.let { f -> android.os.Handler(android.os.Looper.getMainLooper()).post { onExtracted(f) } }
                }
            }) { Text("提取安装包") }
        },
        dismissButton = {
            Box {
                TextButton({ menu = true }) { Text("更多") }
                DropdownMenu(menu, { menu = false }) {
                    context.packageManager.getLaunchIntentForPackage(app.pkg)?.let { launch ->
                        DropdownMenuItem({ Text("启动") }, { menu = false; context.startActivity(launch) })
                    }
                    DropdownMenuItem({ Text("系统应用详情") }, {
                        menu = false
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                    })
                    DropdownMenuItem({ Text("在窗口中打开 APK 所在位置") }, {
                        menu = false; close()
                        vm.openBookmark(vm.active, "local", app.info.sourceDir, isDir = false)
                        vm.locateTick++ // back to the file panes
                    })
                    if (!app.system) DropdownMenuItem({ Text("卸载") }, {
                        menu = false; close()
                        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                    })
                }
            }
        },
    )
}
