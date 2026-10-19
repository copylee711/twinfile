package app.twinfile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.twinfile.fs.Paths
import java.io.File

/** Hands local files to other apps through our FileProvider. */
object Opener {
    fun mime(name: String): String {
        val ext = Paths.extension(name)
        return when (ext) {
            "apks", "xapk" -> "application/octet-stream"
            "" -> "*/*"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** [asType] overrides the MIME type ("text/plain", "image/ *"...) for 打开方式. */
    fun open(context: Context, file: File, asType: String? = null): Boolean {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), asType ?: mime(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(i, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
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
