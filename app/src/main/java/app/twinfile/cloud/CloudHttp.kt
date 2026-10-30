package app.twinfile.cloud

import app.twinfile.fs.FsException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class CloudAuthRequired : FsException("云盘授权已失效，请在「云盘」中重新登录")
class CloudHttpException(val status: Int) : FsException(when (status) {
    403 -> "云盘拒绝访问，请检查文件权限、账号策略或剩余空间"
    404 -> "云盘文件不存在或已被移走"
    409, 412 -> "云端文件已改变或存在同名文件，请刷新后重试"
    429, 503 -> "云盘暂时繁忙，请稍后重试"
    else -> "云盘请求失败（HTTP $status）"
})

/** Tokens are acquired per request; redirects and upload URLs never inherit credentials. */
class CloudHttp(
    val base: HttpUrl,
    private val token: (forceRefresh: Boolean) -> String,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false)
        .followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build(),
    private val allowed: () -> Boolean = { true },
) {
    fun trusted(url: HttpUrl): Boolean = url.scheme == base.scheme && url.host == base.host && url.port == base.port
    fun api(path: String): HttpUrl = base.newBuilder().addEncodedPathSegments(path).build()
    fun file(path: String, id: String, tail: String = ""): HttpUrl =
        base.newBuilder().addPathSegments(path).addPathSegment(id).apply { if (tail.isNotEmpty()) addPathSegments(tail) }.build()

    fun request(url: HttpUrl, method: String = "GET", body: RequestBody? = null,
                headers: Map<String, String> = emptyMap(), authenticated: Boolean = true,
                accepted: Set<Int> = emptySet()): Response {
        if (authenticated && !trusted(url)) throw FsException("拒绝向其他服务器发送云盘授权")
        if (!authenticated && (url.scheme != "https" && !trusted(url) || url.username.isNotEmpty() || url.password.isNotEmpty()))
            throw FsException("云盘返回了不安全的传输地址")
        var refresh = false
        var retries = 0
        while (true) {
            if (!allowed()) throw CloudAuthRequired()
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
            val builder = Request.Builder().url(url).method(method, body)
            headers.forEach { (key, value) -> builder.header(key, value) }
            if (authenticated) builder.header("Authorization", "Bearer ${token(refresh)}")
            val response = try { client.newCall(builder.build()).execute() }
            catch (_: java.io.IOException) { throw FsException("无法连接云盘，请检查网络后重试") }
            if (authenticated && response.code == 401) {
                response.close()
                if (refresh) throw CloudAuthRequired()
                refresh = true
                continue
            }
            if (response.code in setOf(429, 503) && method in setOf("GET", "PUT") && retries++ < 2) {
                val delay = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 5) ?: retries.toLong()
                response.close()
                Thread.sleep(delay * 1000)
                continue
            }
            if (!response.isSuccessful && response.code !in accepted) {
                val status = response.code
                response.close()
                throw CloudHttpException(status)
            }
            return response
        }
    }

    fun json(url: HttpUrl, method: String = "GET", body: RequestBody? = null): JSONObject =
        request(url, method, body).use { response ->
            val text = response.body?.string().orEmpty()
            try { if (text.isBlank()) JSONObject() else JSONObject(text) }
            catch (_: org.json.JSONException) { throw FsException("云盘返回的数据格式无效，请稍后重试") }
        }

    fun stream(url: HttpUrl): InputStream {
        var current = url
        var auth = true
        repeat(5) {
            val response = request(current, authenticated = auth, accepted = setOf(301, 302, 303, 307, 308))
            if (response.code in 300..399) {
                val next = response.header("Location")?.let { current.resolve(it) }
                response.close()
                current = next ?: throw FsException("云盘下载地址无效")
                // Graph's download URL is pre-authorized, even if it happens to share a host.
                auth = false
            } else {
                val input = response.body?.byteStream() ?: run { response.close(); throw FsException("云盘下载没有返回内容") }
                return object : FilterInputStream(input) {
                    override fun close() { try { super.close() } finally { response.close() } }
                }
            }
        }
        throw FsException("云盘下载重定向过多")
    }
}
