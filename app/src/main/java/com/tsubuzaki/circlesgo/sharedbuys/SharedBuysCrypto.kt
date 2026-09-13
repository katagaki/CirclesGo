package com.tsubuzaki.circlesgo.sharedbuys

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SharedBuysCrypto {

    const val TOPIC_INFO = "circles-buys/v1/topic"
    const val OPS_INFO = "circles-buys/v1/ops"
    const val RELAY_AUTH_INFO = "circles-buys/v1/relay-auth"
    const val TAG_LENGTH = 16
    const val NONCE_LENGTH = 12
    val RANDOM_NONCE_MARKER = byteArrayOf(0x53, 0x42, 0x32, 0x00)

    private val random = SecureRandom()

    fun newSessionKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun newDeviceId(): String = ByteArray(4).also { random.nextBytes(it) }.toHex()

    fun roomId(sessionKey: ByteArray): String =
        hmac(sessionKey, TOPIC_INFO.toByteArray()).copyOf(16).toHex()

    fun derive(info: String, sessionKey: ByteArray): ByteArray {
        val prk = hmac(ByteArray(32), sessionKey)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray())
        mac.update(1.toByte())
        return mac.doFinal().copyOf(32)
    }

    fun helloTag(deviceId: String, timestamp: Long, relayAuthKey: ByteArray): ByteArray {
        val input = ByteBuffer.allocate(5 + 1 + deviceId.length + 1 + 8)
        input.put("hello".toByteArray())
        input.put(0)
        input.put(deviceId.toByteArray())
        input.put(0)
        input.putLong(timestamp)
        return hmac(relayAuthKey, input.array()).copyOf(TAG_LENGTH)
    }

    fun recordTag(deviceId: String, seq: Long, blob: ByteArray, relayAuthKey: ByteArray): ByteArray {
        val input = ByteBuffer.allocate(deviceId.length + 1 + 8 + blob.size)
        input.put(deviceId.toByteArray())
        input.put(0)
        input.putLong(seq)
        input.put(blob)
        return hmac(relayAuthKey, input.array()).copyOf(TAG_LENGTH)
    }

    fun seal(
        plaintext: ByteArray,
        contentKey: ByteArray,
        roomId: String,
        deviceId: String,
        seq: Long,
        suppliedNonce: ByteArray? = null
    ): ByteArray {
        val nonce = suppliedNonce ?: ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }
        require(nonce.size == NONCE_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(contentKey, "AES"),
            GCMParameterSpec(128, nonce)
        )
        cipher.updateAAD(associatedData(roomId, deviceId, seq))
        return RANDOM_NONCE_MARKER + nonce + cipher.doFinal(plaintext)
    }

    fun open(
        blob: ByteArray,
        contentKey: ByteArray,
        roomId: String,
        deviceId: String,
        seq: Long
    ): ByteArray {
        val marked = blob.size > RANDOM_NONCE_MARKER.size + NONCE_LENGTH + TAG_LENGTH &&
            blob.copyOfRange(0, RANDOM_NONCE_MARKER.size).contentEquals(RANDOM_NONCE_MARKER)
        val nonce = if (marked) {
            blob.copyOfRange(RANDOM_NONCE_MARKER.size, RANDOM_NONCE_MARKER.size + NONCE_LENGTH)
        } else {
            legacyNonce(deviceId, seq)
        }
        val sealed = if (marked) blob.copyOfRange(RANDOM_NONCE_MARKER.size + NONCE_LENGTH, blob.size) else blob
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(contentKey, "AES"),
            GCMParameterSpec(128, nonce)
        )
        cipher.updateAAD(associatedData(roomId, deviceId, seq))
        return cipher.doFinal(sealed)
    }

    private fun legacyNonce(deviceId: String, seq: Long): ByteArray =
        ByteBuffer.allocate(12).put(deviceId.fromHex()).putLong(seq).array()

    private fun associatedData(roomId: String, deviceId: String, seq: Long): ByteArray =
        ByteBuffer.allocate(16 + 4 + 8)
            .put(roomId.fromHex())
            .put(deviceId.fromHex())
            .putLong(seq)
            .array()

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.fromHex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun ByteArray.toBase64Url(): String =
    android.util.Base64.encodeToString(
        this,
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    )

fun String.fromBase64Url(): ByteArray =
    android.util.Base64.decode(
        this,
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
    )
