package app.twinfile.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import app.twinfile.fs.Volumes
import java.io.File

/**
 * 「定位所在位置」 in other apps' open-with and share menus: works out where the file lives
 * and shows it selected in TwinFile. Has no UI of its own.
 */
class LocateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent.data
        }
        val path = uri?.let { resolve(it) }
        if (path == null) {
            Toast.makeText(this, "无法定位：这个应用没有提供文件的真实位置", Toast.LENGTH_LONG).show()
        } else {
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_LOCATE, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }

    /** Best effort: file URIs, the storage documents provider, MediaStore and path-shaped FileProvider URIs. */
    private fun resolve(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.takeIf { File(it).exists() }
        if (uri.scheme != "content") return null
        try {
            if (DocumentsContract.isDocumentUri(this, uri) && uri.authority == "com.android.externalstorage.documents") {
                val id = DocumentsContract.getDocumentId(uri)
                val volume = id.substringBefore(':')
                val rel = id.substringAfter(':', "")
                val base = if (volume == "primary") Volumes.internalPath() else "/storage/$volume"
                return "$base/$rel".trimEnd('/').takeIf { File(it).exists() }
            }
        } catch (_: Exception) {
        }
        // MediaStore and some providers still answer the old _data column.
        try {
            @Suppress("DEPRECATION")
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { if (File(it).exists()) return it }
            }
        } catch (_: Exception) {
        }
        // FileProvider URIs often embed the path: /root/storage/emulated/0/..., /external_files/Download/...
        val p = Uri.decode(uri.encodedPath ?: return null)
        for (marker in listOf("/storage/", "/sdcard/")) {
            val i = p.indexOf(marker)
            if (i >= 0) {
                val candidate = p.substring(i).let { if (it.startsWith("/sdcard/")) Volumes.internalPath() + it.removePrefix("/sdcard") else it }
                if (File(candidate).exists()) return candidate
            }
        }
        val rest = p.trimStart('/').substringAfter('/', "")
        if (rest.isNotEmpty()) {
            val candidate = Volumes.internalPath() + "/" + rest
            if (File(candidate).exists()) return candidate
        }
        return null
    }
}
