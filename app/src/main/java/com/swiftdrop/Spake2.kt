package com.swiftdrop

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECFieldFp
import java.security.spec.ECPoint
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SPAKE2 over NIST P-256 — zero-knowledge PIN-based pairing.
 *
 * The PIN never crosses the wire.  Both sides prove knowledge of it
 * through blinded elliptic-curve Diffie-Hellman.
 *
 * Protocol (single HTTP round trip):
 *   Client → Server:  pake_msg = encode(x·G + w·M)
 *   Server → Client:  pake_msg = encode(y·G + w·N), confirm, encrypted_key
 *   Both derive:      K = xy·G  →  sharedKey = SHA-256(K ‖ msgA ‖ msgB)
 *
 * Must produce identical results to the Go implementation in crypto.go.
 */
object Spake2 {

    // P-256 curve parameters
    private val p  = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val a  = p - BigInteger.valueOf(3) // P-256 has a = -3
    private val b  = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val n  = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val gx = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
    private val gy = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)
    private val G  = ECPoint(gx, gy)

    // Nothing-up-my-sleeve blinding points (same seeds as Go).
    private val M: ECPoint by lazy { hashToPoint("SwiftDrop-SPAKE2-point-M") }
    private val N: ECPoint by lazy { hashToPoint("SwiftDrop-SPAKE2-point-N") }

    // ── Public API ──────────────────────────────────────────────────────────

    class ClientState(
        private val x: BigInteger,
        private val w: BigInteger,
        val msgA: ByteArray
    ) {
        /** Process server response; returns 32-byte shared key or throws. */
        fun finish(msgB: ByteArray, serverConfirm: ByteArray): ByteArray {
            val Y = decodePoint(msgB)
            // K = x · (Y − w·N)
            val wN = scalarMult(N, w)
            val yMinusWN = pointAdd(Y, negate(wN))
            val K = scalarMult(yMinusWN, x)
            val key = deriveKey(K, msgA, msgB)
            val expected = confirmMac(key, "server")
            if (!MessageDigest.isEqual(serverConfirm, expected)) {
                throw IllegalStateException("SPAKE2 confirmation failed — wrong PIN?")
            }
            return key
        }
    }

    /** Begin client side (PIN submitter). Returns (msgA, state). */
    fun clientStart(pin: String): ClientState {
        val w = pinScalar(pin)
        val x = randomScalar()
        // X = x·G + w·M
        val xG = scalarMult(G, x)
        val wM = scalarMult(M, w)
        val X = pointAdd(xG, wM)
        val msgA = encodePoint(X)
        return ClientState(x, w, msgA)
    }

    data class ServerResult(
        val msgB: ByteArray,
        val confirm: ByteArray,
        val sharedKey: ByteArray
    )

    /** Process client message (server side). Returns (msgB, confirm, sharedKey). */
    fun serverFinish(pin: String, msgA: ByteArray): ServerResult {
        val w = pinScalar(pin)
        val A = decodePoint(msgA)
        val y = randomScalar()
        // Y = y·G + w·N
        val yG = scalarMult(G, y)
        val wN = scalarMult(N, w)
        val Y = pointAdd(yG, wN)
        val msgB = encodePoint(Y)
        // K = y · (X − w·M)
        val wM = scalarMult(M, w)
        val aMinusWM = pointAdd(A, negate(wM))
        val K = scalarMult(aMinusWM, y)
        val key = deriveKey(K, msgA, msgB)
        val confirm = confirmMac(key, "server")
        return ServerResult(msgB, confirm, key)
    }

    /** Wrap plaintext with AES-256-GCM using the given key. Returns nonce‖ciphertext. */
    fun aesGcmWrap(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    /** Unwrap data produced by aesGcmWrap. */
    fun aesGcmUnwrap(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size > 12) { "ciphertext too short" }
        val nonce = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ct)
    }

    // ── EC point arithmetic over P-256 (affine) ────────────────────────────

    private fun negate(pt: ECPoint): ECPoint =
        if (pt == ECPoint.POINT_INFINITY) pt else ECPoint(pt.affineX, p - pt.affineY)

    private fun pointAdd(p1: ECPoint, p2: ECPoint): ECPoint {
        if (p1 == ECPoint.POINT_INFINITY) return p2
        if (p2 == ECPoint.POINT_INFINITY) return p1
        val x1 = p1.affineX; val y1 = p1.affineY
        val x2 = p2.affineX; val y2 = p2.affineY
        if (x1 == x2) {
            if (y1 == y2) return pointDouble(p1)
            return ECPoint.POINT_INFINITY
        }
        val s = (y2 - y1).multiply((x2 - x1).modInverse(p)).mod(p)
        val x3 = (s * s - x1 - x2).mod(p)
        val y3 = (s * (x1 - x3) - y1).mod(p)
        return ECPoint(x3, y3)
    }

    private fun pointDouble(pt: ECPoint): ECPoint {
        if (pt == ECPoint.POINT_INFINITY) return pt
        val x = pt.affineX; val y = pt.affineY
        if (y.signum() == 0) return ECPoint.POINT_INFINITY
        val s = (BigInteger.valueOf(3) * x * x + a).multiply((BigInteger.TWO * y).modInverse(p)).mod(p)
        val x3 = (s * s - BigInteger.TWO * x).mod(p)
        val y3 = (s * (x - x3) - y).mod(p)
        return ECPoint(x3, y3)
    }

    /** Double-and-add scalar multiplication. */
    private fun scalarMult(pt: ECPoint, k: BigInteger): ECPoint {
        var result = ECPoint.POINT_INFINITY
        var addend = pt
        var scalar = k.mod(n)
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) result = pointAdd(result, addend)
            addend = pointDouble(addend)
            scalar = scalar.shiftRight(1)
        }
        return result
    }

    // ── Encoding (uncompressed, matching Go's elliptic.Marshal) ─────────────

    private fun encodePoint(pt: ECPoint): ByteArray {
        val xb = pt.affineX.toByteArray().let { padOrTrim(it, 32) }
        val yb = pt.affineY.toByteArray().let { padOrTrim(it, 32) }
        return byteArrayOf(0x04) + xb + yb
    }

    private fun decodePoint(data: ByteArray): ECPoint {
        require(data.size == 65 && data[0] == 0x04.toByte()) { "invalid uncompressed point" }
        val x = BigInteger(1, data.copyOfRange(1, 33))
        val y = BigInteger(1, data.copyOfRange(33, 65))
        return ECPoint(x, y)
    }

    private fun padOrTrim(b: ByteArray, len: Int): ByteArray {
        return when {
            b.size == len -> b
            b.size > len -> b.copyOfRange(b.size - len, b.size) // trim leading zeros
            else -> ByteArray(len - b.size) + b // pad
        }
    }

    // ── Hash-to-point, key derivation, confirmation ─────────────────────────

    /** Try-and-increment hash to P-256 point (matches Go implementation). */
    private fun hashToPoint(seed: String): ECPoint {
        for (i in 0u..UInt.MAX_VALUE) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(seed.toByteArray())
            val ctr = java.nio.ByteBuffer.allocate(4).putInt(i.toInt()).array()
            md.update(ctr)
            val hash = md.digest()
            val x = BigInteger(1, hash).mod(p)
            // y² = x³ − 3x + b
            val x3 = x.modPow(BigInteger.valueOf(3), p)
            val threeX = BigInteger.valueOf(3).multiply(x).mod(p)
            val rhs = (x3 - threeX + b).mod(p)
            val y = modSqrt(rhs, p) ?: continue
            // Verify point (paranoia)
            if (isOnCurve(x, y)) return ECPoint(x, y)
        }
        throw IllegalStateException("hash-to-point failed")
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val lhs = y.modPow(BigInteger.TWO, p)
        val rhs = (x.modPow(BigInteger.valueOf(3), p) - BigInteger.valueOf(3) * x + b).mod(p)
        return lhs == rhs
    }

    /** Tonelli-Shanks modular square root. Returns null if not a QR. */
    private fun modSqrt(a: BigInteger, p: BigInteger): BigInteger? {
        if (a.signum() == 0) return BigInteger.ZERO
        if (p == BigInteger.TWO) return a.mod(p)
        // For p ≡ 3 (mod 4), shortcut: sqrt = a^((p+1)/4) mod p
        // P-256's p ≡ 3 mod 4.
        val exp = (p + BigInteger.ONE).shiftRight(2)
        val y = a.modPow(exp, p)
        return if (y.modPow(BigInteger.TWO, p) == a.mod(p)) y else null
    }

    private fun pinScalar(pin: String): BigInteger {
        val h = MessageDigest.getInstance("SHA-256").digest("SwiftDrop-SPAKE2-password:$pin".toByteArray())
        val w = BigInteger(1, h).mod(n - BigInteger.ONE) + BigInteger.ONE
        return w
    }

    private fun randomScalar(): BigInteger {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        var x = BigInteger(1, bytes).mod(n)
        if (x.signum() == 0) x = BigInteger.ONE
        return x
    }

    private fun deriveKey(K: ECPoint, msgA: ByteArray, msgB: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(encodePoint(K))
        md.update(msgA)
        md.update(msgB)
        return md.digest()
    }

    fun confirmMac(key: ByteArray, role: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal("SwiftDrop-SPAKE2-confirm-$role".toByteArray())
    }
}
