package app.twinfile.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import app.twinfile.TwinApp
import app.twinfile.fs.Paths
import app.twinfile.fs.Volumes
import app.twinfile.util.FileLog
import java.io.File
import kotlin.concurrent.thread

/**
 * 「定位所在位置」 in other apps' open-with and share menus: works out where the file lives
 * and shows it highlighted in TwinFile. Has no UI of its own.
 *
 * Files in Android/data (QQ and WeChat keep received files there) open in place when root or
 * Shizuku access is on; otherwise, like MT, a copy goes to Download/TwinFile and that is shown.
 */
class LocateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent.data
        }
        if (uri == null) return finish()
        val app = application as TwinApp
        // Off the main thread: the root/Shizuku check and a copy can take a moment.
        thread {
            val path = resolve(uri)
            val reachable = path != null && (File(path).exists() ||
                (Volumes.isRestricted(path) && app.priv?.let { runCatching { it.stat(path) }.getOrNull() } != null))
            val result: Pair<String?, String?> = when {
                reachable -> path to null
                uri.scheme == "content" -> try {
                    val copy = copyOut(uri)
                    copy to (if (path != null && Volumes.isRestricted(path) && app.priv == null)
                        "原文件在 Android/data 里，已复制到 Download/TwinFile。开启 Shizuku 或 Root 后可以直接定位原文件"
                    else "这个应用没有提供文件的真实位置，已复制到 Download/TwinFile")
                } catch (e: Exception) {
                    FileLog.w("locate copy", e)
                    null to "无法定位：${e.message ?: "读取失败"}"
                }
                else -> null to "无法定位：这个应用没有提供文件的真实位置"
            }
            runOnUiThread {
                result.second?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                result.first?.let {
                    startActivity(Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_LOCATE, it)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }
                finish()
            }
        }
    }

    /** Copies what [uri] points at into Download/TwinFile under a free name. */
    private fun copyOut(uri: Uri): String {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val dir = File(Volumes.internalPath(), "Download/TwinFile").apply { mkdirs() }
        val target = File(dir, Paths.freeName(name.replace('/', '_'), false) { File(dir, it).exists() })
        contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it, 256 * 1024) } }
        return target.path
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Best effort: file URIs, the storage documents provider, MediaStore and path-shaped
     * FileProvider URIs. Paths inside Android/data are returned even though we can't check
     * them here.
     */
    private fun resolve(uri: Uri): String? {
        fun ok(p: String) = File(p).exists() || Volumes.isRestricted(p)
        if (uri.scheme == "file") return uri.path?.takeIf(::ok)
        if (uri.scheme != "content") return null
        try {
            if (DocumentsContract.isDocumentUri(this, uri) && uri.authority == "com.android.externalstorage.documents") {
                val id = DocumentsContract.getDocumentId(uri)
                val volume = id.substringBefore(':')
                val rel = id.substringAfter(':', "")
                val base = if (volume == "primary") Volumes.internalPath() else "/storage/$volume"
                return "$base/$rel".trimEnd('/').takeIf(::ok)
            }
        } catch (_: Exception) {
        }
        // MediaStore and some providers still answer the old _data column.
        try {
            @Suppress("DEPRECATION")
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (ok(it)) return it }
            }
        } catch (_: Exception) {
        }
        // FileProvider URIs often embed the path: /root/storage/emulated/0/..., /external/Android/data/...
        val p = Uri.decode(uri.encodedPath ?: return null)
        for (marker in listOf("/storage/", "/sdcard/")) {
            val i = p.indexOf(marker)
            if (i >= 0) {
                val candidate = p.substring(i).let { if (it.startsWith("/sdcard/")) Volumes.internalPath() + it.removePrefix("/sdcard") else it }
                if (ok(candidate)) return candidate
            }
        }
        val rest = p.trimStart('/').substringAfter('/', "")
        if (rest.isNotEmpty()) {
            val candidate = Volumes.internalPath() + "/" + rest
            if (ok(candidate)) return candidate
        }
        return null
    }
}
