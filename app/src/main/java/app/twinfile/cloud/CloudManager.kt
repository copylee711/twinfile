package app.twinfile.cloud

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import app.twinfile.TwinApp
import app.twinfile.net.Secrets
import app.twinfile.util.JsonStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import app.twinfile.fs.FileSource

data class CloudAccount(val provider: CloudProvider, val accountId: String, val handle: String, val display: String) {
    val sourceId get() = "cloud:${provider.name}:" + Base64.getUrlEncoder().withoutPadding().encodeToString(accountId.toByteArray(Charsets.UTF_8))
}

fun FileSource.belongsToCloud(account: CloudAccount, cache: File): Boolean = id == account.sourceId ||
    container?.let { File(it).canonicalPath.startsWith(cache.canonicalPath + File.separator) } == true

/** Owns persisted account metadata and source lifetime; SDKs own credential caches. */
class CloudManager(private val app: TwinApp) {
    val accounts = mutableStateListOf<CloudAccount>()
    val auth = CloudAuth(app)
    private val store = JsonStore(File(app.filesDir, "cloud-accounts.json"))
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val connected = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        io.execute {
            val value = store.load().optString("accounts")
            val loaded = runCatching {
                val array = JSONArray(if (value.isEmpty()) "[]" else Secrets.decrypt(value))
                (0 until array.length()).map { index ->
                    val j = array.getJSONObject(index)
                    CloudAccount(CloudProvider.valueOf(j.getString("provider")), j.getString("id"), j.getString("handle"), j.getString("display"))
                }
            }.getOrDefault(emptyList())
            main.post { loaded.forEach(::register); accounts.addAll(loaded) }
        }
    }

    private fun register(account: CloudAccount) {
        connected.add(account.sourceId)
        app.sources.remove(account.sourceId)?.close()
        val base = if (account.provider == CloudProvider.GOOGLE) "https://www.googleapis.com/drive/v3/" else "https://graph.microsoft.com/v1.0/"
        val http = CloudHttp(base.toHttpUrl(), { refresh ->
            if (account.sourceId !in connected) throw CloudAuthRequired()
            auth.token(account, refresh)
        }, allowed = { account.sourceId in connected })
        val api = if (account.provider == CloudProvider.GOOGLE) GoogleDriveApi(http) else OneDriveApi(http)
        app.sources[account.sourceId] = CloudSource(account.sourceId, "${account.provider.label} · ${account.display}", api, cacheDirectory(account))
    }

    fun validate(account: CloudAccount) {
        val base = if (account.provider == CloudProvider.GOOGLE) "https://www.googleapis.com/drive/v3/" else "https://graph.microsoft.com/v1.0/"
        val http = CloudHttp(base.toHttpUrl(), { auth.token(account, it) })
        val api = if (account.provider == CloudProvider.GOOGLE) GoogleDriveApi(http) else OneDriveApi(http)
        api.root()
    }

    fun upsert(account: CloudAccount) {
        val index = accounts.indexOfFirst { it.sourceId == account.sourceId }
        if (index < 0) accounts += account else accounts[index] = account
        register(account)
        persist()
    }

    fun remove(account: CloudAccount) {
        connected.remove(account.sourceId)
        val cache = cacheDirectory(account)
        app.sources.values.filter { it.belongsToCloud(account, cache) }.forEach {
            runCatching { app.sources.remove(it.id)?.close() }
        }
        accounts.removeAll { it.sourceId == account.sourceId }
        persist()
        // This is an app-owned directory keyed by a digest, never a remote file name.
        io.execute {
            val directory = cacheDirectory(account)
            val parent = File(app.cacheDir, "cloud").canonicalFile
            if (directory.canonicalFile.parentFile == parent) directory.deleteRecursively()
        }
    }

    fun cacheDirectory(account: CloudAccount): File {
        val key = MessageDigest.getInstance("SHA-256").digest(account.sourceId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(app.cacheDir, "cloud/$key")
    }

    private fun persist() {
        val snapshot = accounts.toList()
        io.execute {
            val array = JSONArray()
            snapshot.forEach { array.put(JSONObject().put("provider", it.provider.name).put("id", it.accountId).put("handle", it.handle).put("display", it.display)) }
            store.save(JSONObject().put("accounts", Secrets.encrypt(array.toString())))
        }
    }
}
