package app.twinfile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.twinfile.fs.Cap
import app.twinfile.fs.FEntry
import app.twinfile.fs.FileSource
import app.twinfile.fs.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

enum class Kind {
    FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, APK, PDF, DOC, SHEET, SLIDE, EBOOK, MARKDOWN, CODE, SCRIPT, TEXT, OTHER;

    /** Opens in the built-in text viewer. */
    val textual get() = this == TEXT || this == CODE || this == SCRIPT || this == MARKDOWN
}

object Kinds {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "ico")
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "ts", "m4v", "wmv", "rmvb")
    private val audio = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "amr", "ape", "mid")
    private val archive = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apks", "xapk", "zst", "jar", "tgz", "iso")
    private val doc = setOf("doc", "docx", "odt", "rtf", "wps", "pages")
    private val sheet = setOf("xls", "xlsx", "ods", "et", "numbers")
    private val slide = setOf("ppt", "pptx", "odp", "dps", "key")
    private val ebook = setOf("epub", "mobi", "azw3", "fb2", "djvu")
    private val code = setOf("kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "cs", "py", "js", "mjs", "jsx", "tsx",
        "rs", "go", "lua", "php", "rb", "swift", "dart", "vue", "html", "htm", "css", "scss", "xml", "json", "gradle",
        "smali", "sql")
    private val script = setOf("sh", "bash", "zsh", "bat", "cmd", "ps1")
    private val text = setOf("txt", "log", "ini", "conf", "cfg", "yaml", "yml", "toml", "csv", "tsv", "properties",
        "srt", "ass", "lrc", "nfo", "prop", "rc", "gitignore")

    fun of(e: FEntry): Kind = if (e.isDir) Kind.FOLDER else of(e.name)

    fun of(name: String): Kind = when (Paths.extension(name)) {
        in image -> Kind.IMAGE
        in video -> Kind.VIDEO
        in audio -> Kind.AUDIO
        "apk" -> Kind.APK
        in archive -> Kind.ARCHIVE
        "pdf" -> Kind.PDF
        in doc -> Kind.DOC
        in sheet -> Kind.SHEET
        in slide -> Kind.SLIDE
        in ebook -> Kind.EBOOK
        "md", "markdown" -> Kind.MARKDOWN
        in code -> Kind.CODE
        in script -> Kind.SCRIPT
        in text -> Kind.TEXT
        else -> Kind.OTHER
    }
}

/** Loads small previews with bounded parallelism and a byte-sized memory cache. */
object ThumbLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val permits = Semaphore(4)

    fun cached(key: String): Bitmap? = cache.get(key)

    suspend fun load(context: Context, source: FileSource, path: String, e: FEntry, px: Int): Bitmap? {
        val key = key(source, path, e)
        cache.get(key)?.let { return it }
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(key) ?: try {
                    decode(context, source, path, e, px)?.also { cache.put(key, it) }
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    fun key(source: FileSource, path: String, e: FEntry) = "${source.id}:$path:${e.mtime}:${e.size}"

    private fun decode(context: Context, source: FileSource, path: String, e: FEntry, px: Int): Bitmap? {
        val kind = Kinds.of(e)
        if (source.id != "local") {
            if (Cap.THUMB !in source.caps || (kind != Kind.IMAGE && kind != Kind.VIDEO)) return null
            val bytes = source.thumb(path, px) ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        return when (kind) {
            Kind.IMAGE -> decodeSampled(path, px)
            Kind.VIDEO -> if (Build.VERSION.SDK_INT >= 29) {
                ThumbnailUtils.createVideoThumbnail(File(path), Size(px, px), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
            }
            Kind.APK -> {
                val pm = context.packageManager
                val info = pm.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return null
                info.sourceDir = path
                info.publicSourceDir = path
                info.loadIcon(pm).toBitmap(px, px)
            }
            else -> null
        }
    }

    private fun decodeSampled(path: String, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}

fun kindIcon(kind: Kind): ImageVector = when (kind) {
    Kind.FOLDER -> Icons.Rounded.Folder
    Kind.IMAGE -> Icons.Filled.Image
    Kind.VIDEO -> Icons.Filled.Movie
    Kind.AUDIO -> Icons.Filled.Audiotrack
    Kind.ARCHIVE -> Icons.Filled.FolderZip
    Kind.APK -> Icons.Filled.Android
    Kind.PDF -> Icons.Filled.PictureAsPdf
    Kind.DOC -> Icons.Filled.Description
    Kind.SHEET -> Icons.Filled.TableChart
    Kind.SLIDE -> Icons.Filled.Slideshow
    Kind.EBOOK -> Icons.Filled.AutoStories
    Kind.MARKDOWN -> Icons.AutoMirrored.Filled.Article
    Kind.CODE -> Icons.Filled.Code
    Kind.SCRIPT -> Icons.Filled.Terminal
    Kind.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    Kind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
fun kindColor(kind: Kind): Color {
    val c = LocalTwin.current
    return when (kind) {
        Kind.FOLDER -> c.folder
        Kind.IMAGE -> c.image
        Kind.VIDEO -> c.video
        Kind.AUDIO -> c.audio
        Kind.ARCHIVE -> c.archive
        Kind.APK -> c.apk
        Kind.PDF -> c.pdf
        Kind.DOC -> c.doc
        Kind.SHEET -> c.sheet
        Kind.SLIDE -> c.slide
        Kind.EBOOK -> c.audio
        Kind.MARKDOWN -> c.doc
        Kind.CODE, Kind.SCRIPT -> c.code
        Kind.TEXT -> c.text
        Kind.OTHER -> c.other
    }
}

/** Thumbnail for images/videos/APKs, otherwise a tinted type icon. */
@Composable
fun FileIcon(source: FileSource, dir: String, e: FEntry, size: Dp) {
    val kind = Kinds.of(e)
    val tint = kindColor(kind)
    val shape = RoundedCornerShape(size / 4)
    val thumbable = kind == Kind.IMAGE || kind == Kind.VIDEO || kind == Kind.APK
    val thumb: ImageBitmap? = if (thumbable) {
        val context = LocalContext.current
        val px = with(LocalDensity.current) { size.roundToPx() }
        val path = Paths.join(dir, e.pathName)
        val key = ThumbLoader.key(source, path, e)
        val state by produceState(ThumbLoader.cached(key)?.asImageBitmap(), key) {
            if (value == null) value = ThumbLoader.load(context, source, path, e, px)?.asImageBitmap()
        }
        state
    } else null
    Box(
        Modifier.size(size).clip(shape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(thumb, null, Modifier.size(size), contentScale = if (kind == Kind.APK) ContentScale.Fit else ContentScale.Crop)
        } else if (officeLetter(kind) != null) {
            // Office files: the letter everyone knows reads faster than a generic glyph.
            Text(officeLetter(kind)!!, color = tint, fontWeight = FontWeight.Black,
                fontSize = with(LocalDensity.current) { (size * 0.46f).toSp() })
        } else {
            Icon(kindIcon(kind), null, Modifier.size(size * 0.58f), tint = tint)
        }
    }
}

private fun officeLetter(kind: Kind): String? = when (kind) {
    Kind.DOC -> "W"
    Kind.SHEET -> "X"
    Kind.SLIDE -> "P"
    else -> null
}

@Composable
fun ParentIcon(size: Dp) {
    val tint = LocalTwin.current.muted
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.SubdirectoryArrowLeft, null, Modifier.size(size * 0.55f), tint = tint)
    }
}
