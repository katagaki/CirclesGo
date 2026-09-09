package com.tsubuzaki.circlesgo.sharedbuys

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object SharedBuyKind {
    const val ADD_ITEM = 0
    const val SET_STATUS = 1
    const val SET_ASSIGNEE = 2
    const val SET_COST = 3
    const val REMOVE_ITEM = 4
    const val MEMBER_JOINED = 5
    const val RENAME_ITEM = 6

    /**
     * Whether this kind is carried over the peer-to-peer Bluetooth path.
     *
     * Bluetooth frames are chunked at 160 bytes and reassembled by hand, so the
     * transport is only dependable for small, self-contained payloads. Item names,
     * costs and images travel over the relay exclusively; on-site we only exchange
     * status flips, which is what makes the list useful while walking a venue.
     */
    fun travelsOverBluetooth(kind: Int): Boolean = kind == SET_STATUS

    /**
     * Whether this build can interpret the kind at all.
     *
     * An unknown kind is folded as a no-op on both platforms. Without this it was parked
     * in the deferred map instead, waiting for an addItem that would never make it
     * applicable, and the map grew for the life of the session.
     */
    fun isKnown(kind: Int): Boolean = kind in ADD_ITEM..RENAME_ITEM
}

object SharedBuyStatus {
    const val PENDING = 0
    const val BOUGHT = 1
    const val CANCELLED = 2

    fun next(status: Int): Int = when (status) {
        PENDING -> BOUGHT
        BOUGHT -> CANCELLED
        else -> PENDING
    }
}

@Serializable
data class SharedBuyPayload(
    @SerialName("a") val actor: Int,
    @SerialName("k") val kind: Int,
    @SerialName("i") val itemId: String,
    @SerialName("c") val circleId: Int,
    @SerialName("t") val text: String? = null,
    @SerialName("v") val value: Int? = null
)

@Serializable
data class SharedBuyChange(
    val device: String,
    val seq: Long,
    val payload: SharedBuyPayload
) {
    val id: String get() = "$device#$seq"
}

data class SharedBuyItem(
    val id: String,
    val circleId: Int,
    val name: String,
    val cost: Int,
    val status: Int,
    val assignee: Int?,
    val isRemoved: Boolean,
    val lastTouchedBy: Int
)

object SharedBuyFold {

    fun items(changes: List<SharedBuyChange>): List<SharedBuyItem> {
        val byId = mutableMapOf<String, SharedBuyItem>()
        val order = mutableListOf<String>()
        val deferred = mutableMapOf<String, MutableList<SharedBuyChange>>()

        for (change in changes.sortedWith(compareBy({ it.seq }, { it.device }))) {
            val payload = change.payload
            when {
                payload.kind == SharedBuyKind.ADD_ITEM -> {
                    if (!byId.containsKey(payload.itemId)) order.add(payload.itemId)
                    byId[payload.itemId] = SharedBuyItem(
                        id = payload.itemId,
                        circleId = payload.circleId,
                        name = payload.text.orEmpty(),
                        cost = payload.value ?: 0,
                        status = SharedBuyStatus.PENDING,
                        assignee = null,
                        isRemoved = false,
                        lastTouchedBy = payload.actor
                    )
                    // A mutation can land before the addItem it targets: Bluetooth carries
                    // status flips but never the item itself, so a peer can learn that
                    // something was bought before the relay delivers what it is. Replay
                    // whatever was parked on this item, in the same order it was folded.
                    deferred.remove(payload.itemId)?.forEach { apply(it, byId) }
                }
                payload.kind == SharedBuyKind.MEMBER_JOINED -> Unit
                !SharedBuyKind.isKnown(payload.kind) -> Unit
                !byId.containsKey(payload.itemId) ->
                    deferred.getOrPut(payload.itemId) { mutableListOf() }.add(change)
                else -> apply(change, byId)
            }
        }
        return order.mapNotNull { byId[it] }.filter { !it.isRemoved }
    }

    private fun apply(change: SharedBuyChange, byId: MutableMap<String, SharedBuyItem>) {
        val payload = change.payload
        val current = byId[payload.itemId] ?: return
        byId[payload.itemId] = when (payload.kind) {
            SharedBuyKind.SET_STATUS -> current.copy(
                status = payload.value ?: SharedBuyStatus.PENDING,
                lastTouchedBy = payload.actor
            )
            SharedBuyKind.SET_ASSIGNEE ->
                current.copy(assignee = payload.value, lastTouchedBy = payload.actor)
            SharedBuyKind.SET_COST ->
                current.copy(cost = payload.value ?: 0, lastTouchedBy = payload.actor)
            SharedBuyKind.RENAME_ITEM ->
                current.copy(name = payload.text.orEmpty(), lastTouchedBy = payload.actor)
            SharedBuyKind.REMOVE_ITEM -> current.copy(isRemoved = true)
            else -> return
        }
    }

    fun members(changes: List<SharedBuyChange>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (change in changes.sortedWith(compareBy({ it.seq }, { it.device }))) {
            if (change.payload.kind == SharedBuyKind.MEMBER_JOINED) {
                result[change.payload.actor] = change.payload.text.orEmpty()
            }
        }
        return result
    }
}
