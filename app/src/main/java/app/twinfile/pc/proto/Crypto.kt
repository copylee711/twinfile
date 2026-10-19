package app.twinfile.pc.proto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitives from docs/protocol.md; must match desktop/core/src/crypto.rs. */
object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    fun randomId(): String = random(8).toHex()

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < len) {
            t = hmac(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun ctEq(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    fun transcript(pubC: ByteArray, pubS: ByteArray, nonceC: ByteArray, nonceS: ByteArray) =
        sha256(pubC, pubS, nonceC, nonceS)

    fun pairingCode(th: ByteArray): String {
        val d = sha256("syncboard/v1/code".toByteArray(), th)
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or
            ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    fun derivePsk(shared: ByteArray, token: ByteArray?, th: ByteArray): ByteArray =
        hkdf(if (token != null) shared + token else shared, th, "syncboard/v1/psk".toByteArray(), 32)

    /** Returns (client→server, server→client) keys. */
    fun sessionKeys(psk: ByteArray, nonceC: ByteArray, nonceS: ByteArray): Pair<ByteArray, ByteArray> {
        val k = hkdf(psk, nonceC + nonceS, "syncboard/v1/session".toByteArray(), 64)
        return k.copyOfRange(0, 32) to k.copyOfRange(32, 64)
    }
}

/** Ephemeral P-256 key pair; [public] is the 65-byte uncompressed point. */
class Ecdh {
    private val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    private val pair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    val public: ByteArray = (pair.public as ECPublicKey).w.let { byteArrayOf(4) + it.affineX.to32() + it.affineY.to32() }

    fun agree(peer: ByteArray): ByteArray {
        require(peer.size == 65 && peer[0] == 4.toByte()) { "bad public key" }
        val point = ECPoint(BigInteger(1, peer.copyOfRange(1, 33)), BigInteger(1, peer.copyOfRange(33, 65)))
        val pk = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(pair.private)
        ka.doPhase(pk, true)
        return ka.generateSecret()
    }

    private fun BigInteger.to32(): ByteArray {
        val b = toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }
}

/** One direction of the encrypted stream. Nonce = 4 zero bytes ‖ u64 counter. */
class RecordCipher(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var counter = 0L

    private fun nextNonce(): ByteArray {
        val n = ByteArray(12)
        var c = counter++
        for (i in 11 downTo 4) {
            n[i] = (c and 0xff).toByte()
            c = c ushr 8
        }
        return n
    }

    fun seal(plain: ByteArray): ByteArray {
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nextNonce()))
        return cipher.doFinal(plain)
    }

    fun open(ct: ByteArray): ByteArray {
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nextNonce()))
        return cipher.doFinal(ct)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
