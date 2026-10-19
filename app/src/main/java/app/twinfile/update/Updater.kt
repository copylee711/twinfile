package app.twinfile.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.twinfile.BuildConfig
import app.twinfile.ui.Opener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Checks GitHub Releases for a newer build and installs it. */
object Updater {
    const val REPO = "copylee711/twinfile"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val sumsUrl: String?,
        val page: String,
    )

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    val current: String get() = BuildConfig.VERSION_NAME

    /** The latest release when it is newer than this build, else null. Throws on network errors. */
    fun check(): Release? {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        val body = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("GitHub 返回 ${r.code}")
            r.body!!.string()
        }
        val j = JSONObject(body)
        val version = j.optString("tag_name").removePrefix("v")
        if (compare(version, current) <= 0) return null
        val assets = j.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            when {
                name.endsWith(".apk") -> apk = a
                name.startsWith("SHA256SUMS") -> sums = a.optString("browser_download_url")
            }
        }
        apk ?: return null
        return Release(version, j.optString("body"), apk.optString("browser_download_url"), apk.optString("name"),
            apk.optLong("size"), sums, j.optString("html_url", RELEASES_PAGE))
    }

    /** Numeric compare of "1.2.3"-style versions; a pre-release suffix sorts before the release. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return (if ('-' in a) 0 else 1).compareTo(if ('-' in b) 0 else 1)
    }

    /**
     * Downloads the APK into the cache, checks it against SHA256SUMS.txt when the release has
     * one, and returns the file. [progress] gets (done, total) bytes; return false to cancel.
     */
    fun download(context: Context, r: Release, progress: (Long, Long) -> Boolean): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != r.apkName) it.delete() }
        val out = File(dir, r.apkName)
        val tmp = File(dir, r.apkName + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("下载失败：HTTP ${resp.code}")
            val body = resp.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: r.apkSize
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (!progress(done, total)) throw java.util.concurrent.CancellationException()
                    }
                }
            }
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        r.sumsUrl?.let { url ->
            val sums = http.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
            // Lines look like "<hex>  <name>" (sha256sum format, optional '*').
            val expected = sums.lineSequence().map { it.trim().split(Regex("\\s+"), 2) }
                .firstOrNull { it.size == 2 && it[1].removePrefix("*") == r.apkName }?.get(0)?.lowercase()
            if (expected != null && expected != hash) {
                tmp.delete()
                throw java.io.IOException("校验失败：安装包的 SHA-256 与发布页不一致")
            }
        }
        out.delete()
        tmp.renameTo(out)
        return out
    }

    /** Opens the system installer, or the "install unknown apps" setting if not allowed yet. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        context.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(Opener.uri(context, apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun openPage(context: Context, url: String = RELEASES_PAGE) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
