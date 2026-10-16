package com.tsubuzaki.circlesgo.sharedbuys

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SharedBuysCrypto {

    const val TOPIC_INFO = "circles-buys/v1/topic"
    const val OPS_INFO = "circles-buys/v1/ops"
    const val RELAY_AUTH_INFO = "circles-buys/v1/relay-auth"
    const val HANDSHAKE_INFO = "circles-buys/v1/ble-handshake"
    const val REKEY_INFO = "circles-buys/v1/rekey"
    const val PUBLIC_KEY_LENGTH = 65
    private const val REKEY_PLAINTEXT_LENGTH = 36
    const val TAG_LENGTH = 16
    const val NONCE_LENGTH = 12
    val RANDOM_NONCE_MARKER = byteArrayOf(0x53, 0x42, 0x32, 0x00)

    private val random = SecureRandom()

    fun newSessionKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun newDeviceId(): String = ByteArray(4).also { random.nextBytes(it) }.toHex()

    fun newDeviceAuthKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun randomNonce(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

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

    fun helloInput(deviceId: String, timestamp: Long): ByteArray {
        val input = ByteBuffer.allocate(5 + 1 + deviceId.length + 1 + 8)
        input.put("hello".toByteArray())
        input.put(0)
        input.put(deviceId.toByteArray())
        input.put(0)
        input.putLong(timestamp)
        return input.array()
    }

    fun helloTag(deviceId: String, timestamp: Long, relayAuthKey: ByteArray): ByteArray =
        hmac(relayAuthKey, helloInput(deviceId, timestamp)).copyOf(TAG_LENGTH)

    /**
     * The client data an attestation is bound to, matching the relay byte for byte.
     *
     * hello is the exact UTF-8 JSON sent in X-Circles-Hello, so the attestation covers
     * every field of it — push token and device key included.
     */
    fun attestInput(roomId: String, hello: ByteArray): ByteArray {
        val input = ByteBuffer.allocate(6 + 1 + roomId.length + 1 + hello.size)
        input.put("attest".toByteArray())
        input.put(0)
        input.put(roomId.toByteArray())
        input.put(0)
        input.put(hello)
        return input.array()
    }

    fun requestHash(roomId: String, hello: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(attestInput(roomId, hello))
            .toBase64Url()

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

    private val p256: ECParameterSpec by lazy { (newAgreementKey().public as ECPublicKey).params }

    fun newAgreementKey(): KeyPair =
        KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()

    fun publicKeyBytes(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        return byteArrayOf(4) + point.affineX.toFixed(32) + point.affineY.toFixed(32)
    }

    fun publicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == PUBLIC_KEY_LENGTH && bytes[0] == 4.toByte())
        val point = ECPoint(
            BigInteger(1, bytes.copyOfRange(1, 33)),
            BigInteger(1, bytes.copyOfRange(33, PUBLIC_KEY_LENGTH))
        )
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, p256))
    }

    fun privateKeyBytes(key: PrivateKey): ByteArray = (key as ECPrivateKey).s.toFixed(32)

    fun privateKey(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, bytes), p256))

    fun sealRekey(
        sessionKey: ByteArray,
        ownerDevice: String,
        publicKey: ByteArray,
        roomId: String,
        deviceId: String,
        ephemeral: KeyPair = newAgreementKey(),
        suppliedNonce: ByteArray? = null
    ): ByteArray {
        require(sessionKey.size == 32 && ownerDevice.length == 8)
        val nonce = suppliedNonce ?: randomNonce(NONCE_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(rekeyWrapKey(ephemeral.private, publicKey(publicKey)), "AES"),
            GCMParameterSpec(128, nonce)
        )
        cipher.updateAAD(roomId.fromHex() + deviceId.fromHex())
        return publicKeyBytes(ephemeral.public) + nonce + cipher.doFinal(sessionKey + ownerDevice.fromHex())
    }

    fun openRekey(box: ByteArray, privateKey: PrivateKey, roomId: String, deviceId: String): Pair<ByteArray, String> {
        require(box.size == PUBLIC_KEY_LENGTH + NONCE_LENGTH + REKEY_PLAINTEXT_LENGTH + TAG_LENGTH)
        val nonceEnd = PUBLIC_KEY_LENGTH + NONCE_LENGTH
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(rekeyWrapKey(privateKey, publicKey(box.copyOfRange(0, PUBLIC_KEY_LENGTH))), "AES"),
            GCMParameterSpec(128, box.copyOfRange(PUBLIC_KEY_LENGTH, nonceEnd))
        )
        cipher.updateAAD(roomId.fromHex() + deviceId.fromHex())
        val plaintext = cipher.doFinal(box.copyOfRange(nonceEnd, box.size))
        return plaintext.copyOfRange(0, 32) to plaintext.copyOfRange(32, REKEY_PLAINTEXT_LENGTH).toHex()
    }

    private fun rekeyWrapKey(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return derive(REKEY_INFO, agreement.generateSecret())
    }

    private fun BigInteger.toFixed(size: Int): ByteArray {
        val raw = toByteArray()
        return if (raw.size >= size) raw.copyOfRange(raw.size - size, raw.size) else ByteArray(size - raw.size) + raw
    }

    private fun legacyNonce(deviceId: String, seq: Long): ByteArray =
        ByteBuffer.allocate(12).put(deviceId.fromHex()).putLong(seq).array()

    private fun associatedData(roomId: String, deviceId: String, seq: Long): ByteArray =
        ByteBuffer.allocate(16 + 4 + 8)
            .put(roomId.fromHex())
            .put(deviceId.fromHex())
            .putLong(seq)
            .array()

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }
}

/** The two keys every record needs, derived once per room rather than per record. */
class SharedBuysKeys(sessionKey: ByteArray) {
    val content: ByteArray = SharedBuysCrypto.derive(SharedBuysCrypto.OPS_INFO, sessionKey)
    val relayAuth: ByteArray = SharedBuysCrypto.derive(SharedBuysCrypto.RELAY_AUTH_INFO, sessionKey)
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
