package app.twinfile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.twinfile.fs.FEntry
import app.twinfile.fs.FileSource
import app.twinfile.fs.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** What the full-screen viewer shows. */
sealed interface Viewer {
    data class Images(val source: FileSource, val dir: String, val items: List<FEntry>, val start: Int) : Viewer
    data class Text(val source: FileSource, val path: String, val entry: FEntry) : Viewer
}

@Composable
fun ImageViewer(v: Viewer.Images, close: () -> Unit, share: (File) -> Unit, openExternal: (File) -> Unit) {
    BackHandler(onBack = close)
    val pager = rememberPagerState(v.start) { v.items.size }
    var chrome by remember { mutableStateOf(true) }
    val maxPx = with(LocalDensity.current) {
        val c = LocalConfiguration.current
        maxOf(c.screenWidthDp, c.screenHeightDp).dp.roundToPx() * 2
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { v.items[it].name }) { page ->
            val e = v.items[page]
            ZoomableImage(v.source, Paths.join(v.dir, e.name), e, maxPx) { chrome = !chrome }
        }
        if (chrome) {
            val e = v.items[pager.currentPage]
            val isLocal = v.source.id == "local"
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(e.name, color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text("${Format.time(e.mtime)} · ${Format.size(e.size)} · ${pager.currentPage + 1}/${v.items.size}",
                        color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
                if (isLocal) {
                    val f = File(Paths.join(v.dir, e.name))
                    IconButton({ share(f) }) { Icon(Icons.Filled.Share, "分享", tint = Color.White) }
                    IconButton({ openExternal(f) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "用其他应用打开", tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(source: FileSource, path: String, e: FEntry, maxPx: Int, onTap: () -> Unit) {
    val bitmap by produceState<Result<Bitmap>?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { decodeFull(source, path, e, maxPx) } }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
            }) }
            .pointerInput(Unit) {
                // Only claim gestures while zoomed or pinching, so the pager can still swipe.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2 || scale > 1f) {
                            scale = (scale * ev.calculateZoom()).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + ev.calculatePan()
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val r = bitmap) {
            null -> CircularProgressIndicator(color = Color.White)
            else -> r.onSuccess { bmp ->
                Image(bmp.asImageBitmap(), e.name, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                })
            }.onFailure {
                Text("无法显示此图片", color = Color.White)
            }
        }
    }
}

private fun decodeFull(source: FileSource, path: String, e: FEntry, maxPx: Int): Bitmap {
    val bytes: ByteArray? = if (source.id == "local") null else source.openRead(path).use { it.readBytes() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) else BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
    val o = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) else BitmapFactory.decodeFile(path, o)
    return bmp ?: error("decode failed: ${e.name}")
}

private const val TEXT_LIMIT = 1024 * 1024

/** Read-only text preview of the first MiB, UTF-8 or GBK. */
@Composable
fun TextViewer(v: Viewer.Text, close: () -> Unit, openExternal: (() -> Unit)?) {
    BackHandler(onBack = close)
    var wrap by remember { mutableStateOf(true) }
    val content by produceState<Result<Pair<List<String>, String>>?>(null, v.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = v.source.openRead(v.path).use { input ->
                    val buf = ByteArray(TEXT_LIMIT)
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    buf.copyOf(n)
                }
                val (text, cs) = decodeText(bytes)
                text.lines().let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it } to cs
            }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text(v.entry.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val info = content?.getOrNull()?.let { (lines, cs) ->
                    "${lines.size} 行 · $cs" + if (v.entry.size > TEXT_LIMIT) " · 只显示前 1 MB" else ""
                } ?: Format.size(v.entry.size)
                Text(info, fontSize = 12.sp, color = LocalTwin.current.muted)
            }
            IconButton({ wrap = !wrap }) { Icon(Icons.AutoMirrored.Filled.WrapText, "自动换行") }
            if (openExternal != null) IconButton(openExternal) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "用其他应用打开") }
        }
        when (val r = content) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> r.onSuccess { (lines, _) ->
                val h = rememberScrollState()
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(h)).padding(horizontal = 8.dp)) {
                        itemsIndexed(lines) { i, line ->
                            Row {
                                Text("${i + 1}", Modifier.padding(end = 8.dp), fontSize = 11.sp, color = LocalTwin.current.muted,
                                    fontFamily = FontFamily.Monospace)
                                Text(line, fontSize = 13.sp, fontFamily = FontFamily.Monospace, softWrap = wrap)
                            }
                        }
                    }
                }
            }.onFailure { Text("无法读取：${it.message}", Modifier.padding(16.dp)) }
        }
    }
}

/** Strict UTF-8 first, then GBK, then lossy UTF-8. A cut multi-byte tail is tolerated. */
fun decodeText(bytes: ByteArray): Pair<String, String> {
    fun strict(cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, trimPartial(bytes))).toString()
    } catch (_: CharacterCodingException) {
        null
    }
    val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
    if (start == 3) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8 BOM"
    strict(Charsets.UTF_8)?.let { return it to "UTF-8" }
    strict(Charset.forName("GBK"))?.let { return it to "GBK" }
    return String(bytes, Charsets.UTF_8) to "UTF-8"
}

/** Length without a trailing incomplete UTF-8 sequence. */
private fun trimPartial(b: ByteArray): Int {
    var i = b.size - 1
    var back = 0
    while (i >= 0 && back < 4 && (b[i].toInt() and 0xC0) == 0x80) { i--; back++ }
    if (i < 0) return b.size
    val lead = b[i].toInt() and 0xFF
    val need = when {
        lead >= 0xF0 -> 4
        lead >= 0xE0 -> 3
        lead >= 0xC0 -> 2
        else -> 1
    }
    return if (need > back + 1) i else b.size
}
