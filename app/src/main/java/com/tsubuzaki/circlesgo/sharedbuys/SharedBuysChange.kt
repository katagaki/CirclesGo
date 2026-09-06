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
        for (change in changes.sortedWith(compareBy({ it.seq }, { it.device }))) {
            val payload = change.payload
            when (payload.kind) {
                SharedBuyKind.ADD_ITEM -> {
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
                }
                SharedBuyKind.SET_STATUS -> byId[payload.itemId]?.let {
                    byId[payload.itemId] = it.copy(
                        status = payload.value ?: SharedBuyStatus.PENDING,
                        lastTouchedBy = payload.actor
                    )
                }
                SharedBuyKind.SET_ASSIGNEE -> byId[payload.itemId]?.let {
                    byId[payload.itemId] = it.copy(assignee = payload.value, lastTouchedBy = payload.actor)
                }
                SharedBuyKind.SET_COST -> byId[payload.itemId]?.let {
                    byId[payload.itemId] = it.copy(cost = payload.value ?: 0, lastTouchedBy = payload.actor)
                }
                SharedBuyKind.REMOVE_ITEM -> byId[payload.itemId]?.let {
                    byId[payload.itemId] = it.copy(isRemoved = true)
                }
            }
        }
        return order.mapNotNull { byId[it] }.filter { !it.isRemoved }
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
