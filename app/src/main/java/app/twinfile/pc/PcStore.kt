package app.twinfile.pc

import android.os.Build
import app.twinfile.net.Secrets
import app.twinfile.pc.proto.Crypto
import app.twinfile.util.JsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** A PC running SyncBoard that this phone paired with. */
data class PcInfo(
    val id: String,
    val name: String,
    val psk: ByteArray,
    val hosts: List<String>,
    val port: Int = PcStore.PORT,
    val alias: String = "",
) {
    val display get() = alias.ifBlank { name }
    val sourceId get() = "pc:$id"

    /** Moves [host] to the front: it answered last. */
    fun withHost(host: String) = copy(hosts = (listOf(host) + hosts).distinct().take(6))
}

/** pc.json: this app's own device identity and the paired PCs (psk encrypted with Keystore). */
class PcStore(dir: File) {
    private val store = JsonStore(File(dir, "pc.json"))
    val deviceId: String
    val deviceName: String = "TwinFile · ${Build.MODEL}"
    private val pcs = LinkedHashMap<String, PcInfo>()

    init {
        val j = store.load()
        deviceId = j.optString("deviceId").ifEmpty { Crypto.randomId() }
        j.optJSONArray("pcs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val psk = runCatching { Base64.getDecoder().decode(Secrets.decrypt(o.optString("psk"))) }.getOrNull() ?: continue
                if (psk.size != 32) continue
                val hosts = o.optJSONArray("hosts")?.let { h -> (0 until h.length()).map { h.getString(it) } } ?: emptyList()
                pcs[o.getString("id")] = PcInfo(o.getString("id"), o.optString("name", "PC"), psk, hosts, o.optInt("port", PORT), o.optString("alias"))
            }
        }
        if (!j.has("deviceId")) save()
    }

    @Synchronized fun all(): List<PcInfo> = pcs.values.toList()
    @Synchronized fun get(id: String): PcInfo? = pcs[id]

    @Synchronized fun upsert(pc: PcInfo) {
        pcs[pc.id] = pc
        save()
    }

    @Synchronized fun remove(id: String) {
        pcs.remove(id)
        save()
    }

    @Synchronized private fun save() {
        val arr = JSONArray()
        for (p in pcs.values) arr.put(JSONObject().put("id", p.id).put("name", p.name).put("alias", p.alias)
            .put("psk", Secrets.encrypt(Base64.getEncoder().encodeToString(p.psk)))
            .put("hosts", JSONArray(p.hosts)).put("port", p.port))
        store.save(JSONObject().put("deviceId", deviceId).put("pcs", arr))
    }

    companion object {
        const val PORT = 53317
    }
}
