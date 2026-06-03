package com.swiftdrop

import android.content.Context
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM streaming encryption + PIN-based pairing, mirroring the Mac Go
 * implementation.
 *
 * Wire format:
 *   [12-byte base nonce]
 *   [4-byte big-endian ciphertext-chunk length][ciphertext] …
 *   [4-byte 0x00000000] ← end marker
 *
 * Each chunk: up to 256 KiB plaintext, encrypted with nonce = baseNonce XOR chunkIndex.
 */
object Crypto {
    private const val CHUNK_PLAIN = 256 * 1024
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128

    fun encryptStream(out: OutputStream, inp: InputStream, key: ByteArray) {
        val baseNonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        out.write(baseNonce)

        val buf = ByteArray(CHUNK_PLAIN)
        val lenBuf = ByteBuffer.allocate(4)
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = baseNonce.copyOf()
        var idx = 0L
        while (true) {
            val n = readFull(inp, buf)
            if (n <= 0) break
            chunkNonceInPlace(nonce, baseNonce, idx)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
            val ct = cipher.doFinal(buf, 0, n)
            lenBuf.clear(); lenBuf.putInt(ct.size)
            out.write(lenBuf.array())
            out.write(ct)
            idx++
        }
        // End marker
        lenBuf.clear(); lenBuf.putInt(0)
        out.write(lenBuf.array())
        out.flush()
    }

    fun decryptStream(out: OutputStream, inp: InputStream, key: ByteArray) {
        val baseNonce = ByteArray(NONCE_SIZE)
        readExact(inp, baseNonce)

        val lenBuf = ByteArray(4)
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = baseNonce.copyOf()
        val ctBuf = ByteArray(CHUNK_PLAIN + 16 + 256)
        var idx = 0L
        while (true) {
            readExact(inp, lenBuf)
            val cLen = ByteBuffer.wrap(lenBuf).int
            if (cLen == 0) break // end marker
            if (cLen > ctBuf.size) throw IllegalStateException("chunk too large: $cLen")

            readExact(inp, ctBuf, cLen)
            chunkNonceInPlace(nonce, baseNonce, idx)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
            val pt = cipher.doFinal(ctBuf, 0, cLen)
            out.write(pt)
            idx++
        }
        out.flush()
    }

    /** Mutate nonce in-place: copy base then XOR counter into low 8 bytes. */
    private fun chunkNonceInPlace(dst: ByteArray, base: ByteArray, idx: Long) {
        base.copyInto(dst)
        for (i in 0 until 8) {
            dst[dst.size - 1 - i] = (base[base.size - 1 - i].toInt() xor ((idx shr (i * 8)) and 0xFF).toInt()).toByte()
        }
    }

    private fun readFull(inp: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun readExact(inp: InputStream, buf: ByteArray, len: Int = buf.size) {
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) throw IllegalStateException("unexpected EOF")
            off += n
        }
    }
}

/**
 * Manages shared AES-256 keys for paired devices, persisted in EncryptedSharedPreferences
 * (backed by Android Keystore).
 */
object PairStore {
    private const val PREFS = "swiftdrop_pairs_enc"
    private const val LEGACY_PREFS = "swiftdrop_pairs"
    private val keys = mutableMapOf<String, ByteArray>()

    // Pending PIN pairing offer
    @Volatile var pendingPIN: String? = null
        private set
    @Volatile var pendingKey: ByteArray? = null
        private set
    @Volatile var pendingExpiry: Long = 0
        private set
    @Volatile private var pendingFails: Int = 0

    // Pending QR pairing offer
    @Volatile private var qrToken: String? = null
    @Volatile private var qrKey: ByteArray? = null
    @Volatile private var qrExpiry: Long = 0

    private fun encryptedPrefs(ctx: Context): android.content.SharedPreferences {
        return androidx.security.crypto.EncryptedSharedPreferences.create(
            PREFS,
            androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC),
            ctx,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun init(ctx: Context) {
        // Migrate from legacy plain SharedPreferences if present.
        val legacy = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacyAll = legacy.all
        if (legacyAll.isNotEmpty()) {
            for ((k, v) in legacyAll) {
                if (v is String) runCatching { keys[k] = hexToBytes(v) }
            }
            legacy.edit().clear().apply()
            save() // re-save into encrypted prefs
            return
        }
        val prefs = encryptedPrefs(ctx)
        for ((k, v) in prefs.all) {
            if (v is String) {
                try {
                    keys[k] = hexToBytes(v)
                } catch (_: Exception) {}
            }
        }
    }

    fun isPaired(id: String): ByteArray? = synchronized(keys) { keys[id] }

    fun pairedIDs(): List<String> = synchronized(keys) { keys.keys.toList() }

    fun generatePIN(): String {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pin = String.format("%06d", BigInteger(1, ByteArray(4).also { SecureRandom().nextBytes(it) }).mod(BigInteger.valueOf(1_000_000)))
        synchronized(keys) {
            pendingPIN = pin
            pendingKey = key
            pendingExpiry = System.currentTimeMillis() + 60_000
            pendingFails = 0
        }
        return pin
    }

    fun claimPIN(pin: String, peerId: String): ByteArray? {
        synchronized(keys) {
            if (pendingPIN == null || System.currentTimeMillis() > pendingExpiry) return null
            if (pin != pendingPIN) {
                pendingFails++
                if (pendingFails >= 3) {
                    // Invalidate PIN after 3 failed attempts to prevent brute-force.
                    pendingPIN = null
                    pendingKey = null
                }
                return null
            }
            val key = pendingKey!!
            keys[peerId] = key
            pendingPIN = null
            pendingKey = null
            pendingFails = 0
            save()
            return key
        }
    }

    fun generateQRToken(): String {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tok = ByteArray(32).also { SecureRandom().nextBytes(it) }
        synchronized(keys) {
            qrToken = bytesToHex(tok)
            qrKey = key
            qrExpiry = System.currentTimeMillis() + 120_000
        }
        return qrToken!!
    }

    fun claimQRToken(token: String, peerId: String): ByteArray? {
        synchronized(keys) {
            if (qrToken == null || token != qrToken || System.currentTimeMillis() > qrExpiry) return null
            val key = qrKey!!
            keys[peerId] = key
            qrToken = null
            qrKey = null
            save()
            return key
        }
    }

    fun unpair(peerId: String) {
        synchronized(keys) {
            keys.remove(peerId)
            save()
        }
    }

    fun storeKey(peerId: String, key: ByteArray) {
        synchronized(keys) {
            keys[peerId] = key
            save()
        }
    }

    private fun save() {
        val prefs = encryptedPrefs(State.appContext).edit()
        prefs.clear()
        for ((id, k) in keys) prefs.putString(id, bytesToHex(k))
        prefs.apply()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        return data
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
