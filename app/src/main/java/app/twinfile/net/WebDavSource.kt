package app.twinfile.net

import app.twinfile.fs.Cap
import app.twinfile.fs.FEntry
import app.twinfile.fs.FsException
import app.twinfile.fs.Paths
import app.twinfile.fs.Progress
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/**
 * WebDAV on plain OkHttp: PROPFIND/MKCOL/MOVE/COPY/DELETE/GET/PUT. [NetConfig.path] is the
 * server path the location starts at, e.g. "/dav/files/alice".
 */
class WebDavSource(config: NetConfig) : NetSource<Unit>(config, maxConnections = 4) {
    override val caps = setOf(Cap.SERVER_MOVE, Cap.SERVER_COPY)

    private val http: OkHttpClient by lazy {
        val b = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
        if (config.trustAll) {
            b.sslSocketFactory(TrustAll.sslContext().socketFactory, TrustAll as X509TrustManager)
            b.hostnameVerifier { _, _ -> true }
        }
        if (config.user.isNotBlank()) {
            val cred = Credentials.basic(config.user, config.password, Charsets.UTF_8)
            b.addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", cred).build()) }
        }
        b.build()
    }

    private val base: HttpUrl by lazy {
        val scheme = if (config.tls) "https" else "http"
        "$scheme://${config.host}:${config.port}/".toHttpUrl()
    }

    override fun connect() = Unit
    override fun disconnect(c: Unit) {}

    override fun close() {
        super.close()
        http.connectionPool.evictAll()
    }

    private fun url(path: String, dir: Boolean = false): HttpUrl {
        val b = base.newBuilder()
        val segs = Paths.normalize(path).trim('/').split('/').filter { it.isNotEmpty() }
        segs.forEach { b.addPathSegment(it) }
        if (dir && segs.isNotEmpty()) b.addPathSegment("")
        return b.build()
    }

    private fun exec(req: Request, what: String): Response {
        val r = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw map(e)
        }
        if (!r.isSuccessful && r.code != 207) {
            r.close()
            throw FsException("$what：${statusText(r.code)}")
        }
        return r
    }

    private fun statusText(code: Int) = when (code) {
        401 -> "用户名或密码不正确（401）"
        403 -> "没有权限（403）"
        404 -> "不存在（404）"
        405 -> "服务器不允许此操作（405）"
        409 -> "上级文件夹不存在（409）"
        412 -> "目标已存在（412）"
        423 -> "文件被锁定（423）"
        507 -> "服务器空间不足（507）"
        else -> "HTTP $code"
    }

    private val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

    private fun propfind(path: String, depth: Int): List<Pair<String, FEntry>> {
        lastUseTouch()
        val req = Request.Builder().url(url(path, dir = depth == 1))
            .method("PROPFIND", propfindBody.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth.toString()).build()
        exec(req, "无法读取 $path").use { r -> return parse(r.body!!.byteStream()) }
    }

    /** Parses a multistatus response into (decoded path, entry) pairs. */
    private fun parse(input: InputStream): List<Pair<String, FEntry>> {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(input, null)
        val out = ArrayList<Pair<String, FEntry>>()
        var href = ""
        var isDir = false
        var size = -1L
        var mtime = 0L
        var text = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "response" -> { href = ""; isDir = false; size = -1; mtime = 0 }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: -1
                    "getlastmodified" -> mtime = parseDate(text.toString().trim())
                    "response" -> if (href.isNotEmpty()) {
                        val path = Paths.normalize(decodeHref(href))
                        out += path to FEntry(Paths.name(path), isDir, if (isDir) -1 else size, mtime)
                    }
                }
            }
        }
        return out
    }

    private fun decodeHref(href: String): String {
        val raw = if (href.startsWith("http://") || href.startsWith("https://")) href.toHttpUrl().encodedPath else href
        return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    }

    private fun parseDate(s: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time ?: 0
    } catch (_: Exception) {
        0
    }

    override fun list(dir: String): List<FEntry> {
        val self = Paths.normalize(dir)
        return propfind(dir, 1).filter { it.first != self }.map { it.second }
    }

    override fun stat(path: String): FEntry? = try {
        propfind(path, 0).firstOrNull()?.second?.copy(name = Paths.name(path))
    } catch (e: FsException) {
        if ("404" in (e.message ?: "")) null else throw e
    }

    override fun mkdir(path: String) {
        exec(Request.Builder().url(url(path, true)).method("MKCOL", null).build(), "无法创建文件夹").close()
    }

    override fun createFile(path: String) {
        exec(Request.Builder().url(url(path)).put(ByteArray(0).toRequestBody(null)).build(), "无法创建文件").close()
    }

    override fun rename(path: String, newName: String): String {
        val to = Paths.join(Paths.parent(path) ?: "/", newName)
        move(path, to)
        return to
    }

    private fun isDirPath(path: String) = stat(path)?.isDir == true

    override fun move(path: String, dstPath: String) {
        val dir = isDirPath(path)
        exec(Request.Builder().url(url(path, dir)).method("MOVE", null)
            .header("Destination", url(dstPath, dir).toString()).header("Overwrite", "F").build(), "移动失败").close()
    }

    override fun copy(path: String, dstPath: String, progress: Progress) {
        val dir = isDirPath(path)
        exec(Request.Builder().url(url(path, dir)).method("COPY", null)
            .header("Destination", url(dstPath, dir).toString()).header("Overwrite", "F").build(), "复制失败").close()
    }

    override fun delete(path: String, progress: Progress) {
        if (!progress.step(0, Paths.name(path))) throw app.twinfile.fs.CancelledException()
        exec(Request.Builder().url(url(path, isDirPath(path))).delete().build(), "删除失败").close()
    }

    override fun openRead(path: String): InputStream {
        lastUseTouch()
        val r = exec(Request.Builder().url(url(path)).get().build(), "无法读取")
        return r.body!!.byteStream()
    }

    /** PUT needs the body up front; a pipe lets the copy engine write while OkHttp uploads. */
    override fun openWrite(path: String, size: Long): OutputStream {
        lastUseTouch()
        val pipeIn = PipedInputStream(256 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        var error: IOException? = null
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = if (size >= 0) size else -1
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(64 * 1024)
                pipeIn.use { while (true) { val n = it.read(buf); if (n < 0) break; sink.write(buf, 0, n) } }
            }
        }
        val worker = Thread({
            try {
                exec(Request.Builder().url(url(path)).put(body).build(), "上传失败").close()
            } catch (e: IOException) {
                error = e
                runCatching { pipeIn.close() }
            }
        }, "webdav-put").apply { start() }
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                error?.let { throw it }
                try { pipeOut.write(b, off, len) } catch (e: IOException) { throw error ?: e }
            }
            override fun close() {
                runCatching { pipeOut.close() }
                worker.join()
                error?.let { throw it }
            }
        }
    }

    private fun lastUseTouch() {
        // Keep NetSource's idle clock honest; WebDAV has no pooled connection objects.
        call { }
    }
}
