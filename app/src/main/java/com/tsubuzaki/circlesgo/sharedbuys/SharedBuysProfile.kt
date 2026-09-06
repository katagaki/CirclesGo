package com.tsubuzaki.circlesgo.sharedbuys

import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SharedBuysProfile {

    val SERVICE_UUID: UUID = UUID.fromString("A7C1F2E0-5B3D-4E8A-9F16-3C2D8E4B7A90")
    val INBOX_UUID: UUID = UUID.fromString("A7C1F2E1-5B3D-4E8A-9F16-3C2D8E4B7A90")
    val OUTBOX_UUID: UUID = UUID.fromString("A7C1F2E2-5B3D-4E8A-9F16-3C2D8E4B7A90")

    const val ADVERTISEMENT_WINDOW_SECONDS = 900L
    const val MAX_PAYLOAD_PER_CHUNK = 160
    const val FRAME_MAGIC: Byte = 0x01

    fun window(epochSeconds: Long = System.currentTimeMillis() / 1000): Long =
        epochSeconds / ADVERTISEMENT_WINDOW_SECONDS

    fun sessionTag(sessionKey: ByteArray, window: Long = window()): ByteArray {
        val input = ByteBuffer.allocate(3 + 8).put("ble".toByteArray()).putLong(window).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        return mac.doFinal(input).copyOf(2)
    }

    fun acceptedTags(sessionKey: ByteArray, window: Long = window()): List<ByteArray> =
        listOf(window - 1, window, window + 1).map { sessionTag(sessionKey, it) }
}

object SharedBuysDigest {

    fun value(vector: Map<String, Long>): UInt {
        var hash = 2166136261u
        for (key in vector.keys.sorted()) {
            for (byte in key.toByteArray()) {
                hash = (hash xor (byte.toUByte().toUInt())) * 16777619u
            }
            val seq = (vector[key] ?: 0L).toUInt()
            for (shift in 0 until 32 step 8) {
                hash = (hash xor ((seq shr shift) and 0xFFu)) * 16777619u
            }
        }
        return hash
    }

    fun bytes(vector: Map<String, Long>): ByteArray {
        val hash = value(vector)
        return byteArrayOf(
            ((hash shr 24) and 0xFFu).toByte(),
            ((hash shr 16) and 0xFFu).toByte(),
            ((hash shr 8) and 0xFFu).toByte(),
            (hash and 0xFFu).toByte()
        )
    }
}

object SharedBuysFraming {

    fun chunks(payload: ByteArray, messageId: Byte): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val slices = payload.toList().chunked(MAX_PAYLOAD_PER_CHUNK_SAFE)
        val count = minOf(slices.size, 255).toByte()
        return slices.mapIndexed { index, slice ->
            ByteBuffer.allocate(4 + slice.size)
                .put(SharedBuysProfile.FRAME_MAGIC)
                .put(messageId)
                .put(index.toByte())
                .put(count)
                .put(slice.toByteArray())
                .array()
        }
    }

    private const val MAX_PAYLOAD_PER_CHUNK_SAFE = SharedBuysProfile.MAX_PAYLOAD_PER_CHUNK

    class Reassembler {
        private val buffers = mutableMapOf<Byte, MutableMap<Int, ByteArray>>()

        fun accept(frame: ByteArray): ByteArray? {
            if (frame.size <= 4 || frame[0] != SharedBuysProfile.FRAME_MAGIC) return null
            val messageId = frame[1]
            val index = frame[2].toUByte().toInt()
            val count = frame[3].toUByte().toInt()
            if (count == 0 || index >= count) return null
            val parts = buffers.getOrPut(messageId) { mutableMapOf() }
            parts[index] = frame.copyOfRange(4, frame.size)
            if (parts.size != count) return null
            buffers.remove(messageId)
            val output = ByteArray(parts.values.sumOf { it.size })
            var at = 0
            for (position in 0 until count) {
                val part = parts[position] ?: return null
                part.copyInto(output, at)
                at += part.size
            }
            return output
        }
    }
}
