package app.twinfile.pc.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Same inputs as `interop_vectors` in desktop/core/src/crypto.rs. */
class ProtocolVectorsTest {
    private fun fill(b: Int, n: Int) = ByteArray(n) { b.toByte() }

    @Test
    fun matchesRustVectors() {
        val (c2s, s2c) = Crypto.sessionKeys(fill(1, 32), fill(2, 32), fill(3, 32))
        assertEquals("52683392e90801f0d4a680c992a94a5c1350d05601342c6730eb815434b1bdbb", c2s.toHex())
        assertEquals("87b9d32852ed479408d25ffd259c341dabaa6e9cfc6b9328adf43e48c7b4a9b8", s2c.toHex())
        assertEquals(
            "3f4764e5876b49a0c3bc7b6bcf05898bab64f67eeea99e9805",
            RecordCipher(c2s).seal("syncboard".toByteArray()).toHex(),
        )
        val th = Crypto.transcript(fill(4, 65), fill(5, 65), fill(6, 32), fill(7, 32))
        assertEquals("dea0e5c624c2396ded5c681ebfbfbce0f2d4e5c4995e85b3f6f49eb3107da9f9", th.toHex())
        assertEquals("582880", Crypto.pairingCode(th))
        assertEquals(
            "a27e85b4a21238126aaf336d028857061bac9b6ebd9f58f220cf7fa3246b8c7c",
            Crypto.derivePsk(fill(8, 32), fill(9, 32), th).toHex(),
        )
        assertEquals(
            "5f054113ae2411c9b05b377fae6bcf5fd073e2b82ed6bb8a051647238be562fb",
            Crypto.hmac(fill(9, 32), "client".toByteArray(), th).toHex(),
        )
    }

    @Test
    fun ecdhAgrees() {
        val a = Ecdh()
        val b = Ecdh()
        assertArrayEquals(a.agree(b.public), b.agree(a.public))
    }

    @Test
    fun messageRoundTrip() {
        val m = Message.of("t" to "clip.text", "id" to "x", body = "hi".toByteArray())
        val d = Message.decode(m.encode())
        assertEquals("clip.text", d.kind)
        assertArrayEquals("hi".toByteArray(), d.body)
    }
}
