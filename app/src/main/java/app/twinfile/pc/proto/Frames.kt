package app.twinfile.pc.proto

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

const val MAX_PLAIN_FRAME = 64 * 1024
const val MAX_RECORD = 1024 * 1024 + 16 + 4096
const val CHUNK_SIZE = 256 * 1024

fun readPlain(input: DataInputStream): JSONObject {
    val len = input.readInt()
    if (len < 0 || len > MAX_PLAIN_FRAME) throw IOException("plain frame too large: $len")
    val buf = ByteArray(len)
    input.readFully(buf)
    return JSONObject(String(buf, Charsets.UTF_8))
}

fun writePlain(out: DataOutputStream, v: JSONObject) {
    val buf = v.toString().toByteArray(Charsets.UTF_8)
    synchronized(out) {
        out.writeInt(buf.size)
        out.write(buf)
        out.flush()
    }
}

class Message(val header: JSONObject, val body: ByteArray = EMPTY) {
    val kind: String get() = header.optString("t")
    fun str(key: String): String = header.optString(key)

    fun encode(): ByteArray {
        val h = header.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArray(4 + h.size + body.size)
        out[0] = (h.size ushr 24).toByte(); out[1] = (h.size ushr 16).toByte()
        out[2] = (h.size ushr 8).toByte(); out[3] = h.size.toByte()
        System.arraycopy(h, 0, out, 4, h.size)
        System.arraycopy(body, 0, out, 4 + h.size, body.size)
        return out
    }

    companion object {
        val EMPTY = ByteArray(0)

        fun decode(p: ByteArray): Message {
            if (p.size < 4) throw IOException("short message")
            val hlen = ((p[0].toInt() and 0xff) shl 24) or ((p[1].toInt() and 0xff) shl 16) or
                ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff)
            if (hlen < 0 || 4 + hlen > p.size) throw IOException("bad header length")
            val header = JSONObject(String(p, 4, hlen, Charsets.UTF_8))
            return Message(header, p.copyOfRange(4 + hlen, p.size))
        }

        fun of(vararg kv: Pair<String, Any?>, body: ByteArray = EMPTY): Message {
            val o = JSONObject()
            kv.forEach { (k, v) -> o.put(k, v) }
            return Message(o, body)
        }
    }
}

class SecureReader(private val input: DataInputStream, private val cipher: RecordCipher) {
    fun read(): Message {
        val len = input.readInt()
        if (len < 0 || len > MAX_RECORD) throw IOException("record too large: $len")
        val buf = ByteArray(len)
        input.readFully(buf)
        return Message.decode(cipher.open(buf))
    }
}

/** Thread-safe: messages from several threads are serialized. */
class SecureWriter(private val out: DataOutputStream, private val cipher: RecordCipher) {
    @Synchronized
    fun write(m: Message) {
        val ct = cipher.seal(m.encode())
        out.writeInt(ct.size)
        out.write(ct)
        out.flush()
    }
}
