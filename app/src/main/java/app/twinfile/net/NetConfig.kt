package app.twinfile.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.twinfile.util.FileLog
import app.twinfile.util.JsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class NetType(val label: String, val defaultPort: Int) {
    FTP("FTP", 21), FTPS("FTPS", 21), SFTP("SFTP", 22), SMB("SMB", 445), WEBDAV("WebDAV", 80);
}

/**
 * A saved network location. [password] is plain text in memory only; on disk it is
 * encrypted with a key that never leaves Android Keystore.
 */
data class NetConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: NetType,
    val name: String,
    val host: String,
    val port: Int,
    val user: String = "",
    val password: String = "",
    /** Start folder; for SMB the first segment is the share name. */
    val path: String = "/",
    val encoding: String = "UTF-8",
    /** FTPS: implicit TLS (port 990) instead of AUTH TLS. WebDAV: use https. */
    val tls: Boolean = false,
    /** Accept self-signed TLS certificates (FTPS/WebDAV). */
    val trustAll: Boolean = false,
    /** SFTP: private key file on the phone; [password] is then its passphrase. */
    val keyFile: String = "",
    /** SFTP: pinned host key fingerprint, set on first successful connect. */
    val hostKey: String = "",
    /** SMB: optional domain. */
    val domain: String = "",
) {
    val sourceId get() = "net:$id"
    val display get() = name.ifBlank { "$host${if (port != type.defaultPort) ":$port" else ""}" }
}

/** Encrypts small secrets with an AES-GCM key held by Android Keystore. */
object Secrets {
    private const val ALIAS = "twinfile-credentials"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        return try {
            val b = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
            String(c.doFinal(b, 12, b.size - 12))
        } catch (e: Exception) {
            FileLog.w("cannot decrypt a saved password", e)
            ""
        }
    }
}

/** netlocs.json in the app's files dir. */
class NetStore(dir: File) {
    private val store = JsonStore(File(dir, "netlocs.json"))

    fun load(): List<NetConfig> {
        val arr = store.load().optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            try {
                NetConfig(
                    id = o.getString("id"), type = NetType.valueOf(o.getString("type")), name = o.optString("name"),
                    host = o.optString("host"), port = o.optInt("port"), user = o.optString("user"),
                    password = Secrets.decrypt(o.optString("password")), path = o.optString("path", "/"),
                    encoding = o.optString("encoding", "UTF-8"), tls = o.optBoolean("tls"), trustAll = o.optBoolean("trustAll"),
                    keyFile = o.optString("keyFile"), hostKey = o.optString("hostKey"), domain = o.optString("domain"),
                )
            } catch (e: Exception) {
                FileLog.w("bad network location", e)
                null
            }
        }
    }

    fun save(items: List<NetConfig>) {
        val arr = JSONArray()
        for (c in items) arr.put(JSONObject()
            .put("id", c.id).put("type", c.type.name).put("name", c.name).put("host", c.host).put("port", c.port)
            .put("user", c.user).put("password", Secrets.encrypt(c.password)).put("path", c.path)
            .put("encoding", c.encoding).put("tls", c.tls).put("trustAll", c.trustAll).put("keyFile", c.keyFile)
            .put("hostKey", c.hostKey).put("domain", c.domain))
        store.save(JSONObject().put("items", arr))
    }
}
