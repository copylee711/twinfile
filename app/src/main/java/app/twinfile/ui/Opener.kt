package app.twinfile.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import app.twinfile.fs.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file waiting for the user to pick an app in [OpenWithDialog]. */
data class OpenRequest(val file: File, val mime: String)

/** Hands local files to other apps through our FileProvider. */
object Opener {
    fun mime(name: String): String {
        val ext = Paths.extension(name)
        return when (ext) {
            "apks", "xapk" -> "application/octet-stream"
            "md", "markdown" -> "text/markdown"
            "" -> "*/*"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun viewIntent(context: Context, file: File, mime: String): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri(context, file), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    /** Key for the remembered default app: the extension, or the MIME type for files without one. */
    fun defaultKey(file: File, mime: String) = Paths.extension(file.name).ifEmpty { mime }

    /** Apps that can view [mime], ours excluded. */
    fun handlers(context: Context, file: File, mime: String): List<android.content.pm.ResolveInfo> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(viewIntent(context, file, mime), PackageManager.MATCH_ALL)
        return list.filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .sortedBy { it.loadLabel(pm).toString() }
    }

    /** Starts [component] (or the system's choice when null). */
    fun launch(context: Context, file: File, mime: String, component: ComponentName?): Boolean {
        val i = viewIntent(context, file, mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (component != null) i.component = component
        return try {
            context.startActivity(i)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uri(context, it) })
        val types = files.map { mime(it.name) }.distinct()
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        i.type = types.singleOrNull() ?: "*/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(i, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private class AppChoice(val component: ComponentName, val label: String, val icon: ImageBitmap)

private val TYPES = listOf(
    "文本" to "text/plain", "图片" to "image/*", "音频" to "audio/*", "视频" to "video/*",
    "压缩包" to "application/zip", "其他" to "*/*",
)

/**
 * In-app chooser: tap an app to open; with 「始终使用」 checked (or a long press) it becomes the
 * default for this file type and later opens skip the dialog. 「类型」 opens the file as text,
 * image... when its real type has no handler.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpenWithDialog(req: OpenRequest, setDefault: (String, ComponentName) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var mime by remember { mutableStateOf(req.mime) }
    var always by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    val apps by produceState<List<AppChoice>?>(null, mime) {
        value = null
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val px = (48 * context.resources.displayMetrics.density).toInt()
            Opener.handlers(context, req.file, mime).map {
                AppChoice(
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    it.loadLabel(pm).toString(),
                    it.loadIcon(pm).toBitmap(px, px).asImageBitmap(),
                )
            }
        }
    }
    val key = Opener.defaultKey(req.file, mime)
    fun pick(c: AppChoice, remember: Boolean) {
        // Only the file's own type can become a default; a 「类型」 override is one-off.
        if (remember && mime == req.mime) setDefault(key, c.component)
        if (!Opener.launch(context, req.file, mime, c.component)) Opener.launch(context, req.file, mime, null)
        close()
    }
    AlertDialog(
        onDismissRequest = close,
        title = {
            Column {
                Text("打开方式")
                Text(req.file.name, fontSize = 13.sp, color = LocalTwin.current.muted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        },
        text = {
            Column {
                when (val list = apps) {
                    null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Text("正在查找应用…") }
                    else -> if (list.isEmpty()) {
                        Text("没有能打开这种文件的应用。点「类型」换一种方式打开。", color = LocalTwin.current.muted)
                    } else {
                        LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(list, key = { it.component.flattenToString() }) { c ->
                                Column(
                                    Modifier.clip(RoundedCornerShape(12.dp))
                                        .combinedClickable(onLongClick = { pick(c, true) }) { pick(c, always) }
                                        .padding(vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Image(c.icon, null, Modifier.size(44.dp))
                                    Spacer(Modifier.height(4.dp))
                                    Text(c.label, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, textAlign = TextAlign.Center,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (mime == req.mime) {
                    CheckRow(if (Paths.extension(req.file.name).isNotEmpty()) "始终用此应用打开 .${Paths.extension(req.file.name)}" else "始终用此应用打开此类型",
                        always) { always = it }
                } else {
                    Text("按「${TYPES.firstOrNull { it.second == mime }?.first ?: mime}」打开，不会记住", fontSize = 12.sp,
                        color = LocalTwin.current.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        dismissButton = {
            Box {
                TextButton({ typeMenu = true }) { Text("类型") }
                DropdownMenu(typeMenu, { typeMenu = false }) {
                    TYPES.forEach { (label, m) -> DropdownMenuItem({ Text(label) }, { mime = m; typeMenu = false }) }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton({ Opener.launch(context, req.file, mime, null); close() }) { Text("系统选择") }
                TextButton(close) { Text("关闭") }
            }
        },
    )
}
