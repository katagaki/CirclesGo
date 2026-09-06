package com.tsubuzaki.circlesgo.sharedbuys

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import java.util.UUID

class SharedBuysSession(context: Context, scope: CoroutineScope) {

    private val store = SharedBuysStore(context)
    private val relay = SharedBuysRelay(scope)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    var status by mutableStateOf("idle")
        private set
    var relayBaseUrl by mutableStateOf("ws://10.0.2.2:8787")
    var actorPid by mutableStateOf(0)
    var isDebugVisible by mutableStateOf(false)

    val log = mutableStateListOf<String>()
    val changes = mutableStateListOf<SharedBuyChange>()

    var sessionKey: ByteArray? = null
        private set
    var deviceId by mutableStateOf("")
        private set
    private var eventNumber = 0
    private var lastSeq = 0L

    val isActive: Boolean get() = sessionKey != null

    val roomId: String? get() = sessionKey?.let { SharedBuysCrypto.roomId(it) }

    val items: List<SharedBuyItem> get() = SharedBuyFold.items(changes)

    val members: Map<Int, String> get() = SharedBuyFold.members(changes)

    val joinUrl: String?
        get() = sessionKey?.let {
            "circles-app://buys-join?v=1&e=$eventNumber&k=${it.toBase64Url()}"
        }

    private val versionVector: Map<String, Long>
        get() = changes.groupBy { it.device }.mapValues { entry -> entry.value.maxOf { it.seq } }

    fun restore() {
        val snapshot = store.load() ?: return
        sessionKey = snapshot.sessionKey.fromBase64Url()
        deviceId = snapshot.deviceId
        eventNumber = snapshot.eventNumber
        lastSeq = snapshot.lastSeq
        changes.clear()
        changes.addAll(snapshot.changes)
    }

    fun start(eventNumber: Int, nickname: String) {
        sessionKey = SharedBuysCrypto.newSessionKey()
        deviceId = SharedBuysCrypto.newDeviceId()
        this.eventNumber = eventNumber
        lastSeq = 0
        changes.clear()
        persist()
        append(SharedBuyKind.MEMBER_JOINED, "-", 0, nickname, actorPid)
        note("started room $roomId as $deviceId")
        connect()
    }

    fun join(uri: Uri, nickname: String) {
        val raw = uri.getQueryParameter("k") ?: return note("bad join link")
        val key = runCatching { raw.fromBase64Url() }.getOrNull()
        if (key == null || key.size != 32) return note("bad join link")
        sessionKey = key
        deviceId = SharedBuysCrypto.newDeviceId()
        eventNumber = uri.getQueryParameter("e")?.toIntOrNull() ?: 0
        lastSeq = 0
        changes.clear()
        persist()
        append(SharedBuyKind.MEMBER_JOINED, "-", 0, nickname, actorPid)
        note("joined room $roomId as $deviceId")
        connect()
    }

    fun leave() {
        relay.disconnect()
        sessionKey = null
        changes.clear()
        lastSeq = 0
        status = "idle"
        store.clear()
        note("left session")
    }

    fun connect() {
        val key = sessionKey ?: return
        val room = roomId ?: return
        status = "connecting"
        relay.connect(
            SharedBuysRelay.Endpoint(relayBaseUrl, room, deviceId, key, versionVector)
        ) { event -> handle(event) }
    }

    fun addItem(name: String, cost: Int, circleId: Int) {
        val itemId = UUID.randomUUID().toString().take(8)
        append(SharedBuyKind.ADD_ITEM, itemId, circleId, name, cost)
        append(SharedBuyKind.SET_ASSIGNEE, itemId, circleId, null, actorPid)
    }

    fun cycle(item: SharedBuyItem) {
        append(SharedBuyKind.SET_STATUS, item.id, item.circleId, null, SharedBuyStatus.next(item.status))
    }

    private fun append(kind: Int, itemId: String, circleId: Int, text: String?, value: Int?) {
        val key = sessionKey ?: return
        val room = roomId ?: return
        lastSeq += 1
        val change = SharedBuyChange(
            device = deviceId,
            seq = lastSeq,
            payload = SharedBuyPayload(actorPid, kind, itemId, circleId, text, value)
        )
        changes.add(change)
        persist()
        seal(change, key, room)?.let { relay.send(listOf(it)) { event -> handle(event) } }
    }

    private fun seal(change: SharedBuyChange, key: ByteArray, room: String): RelayRecord? =
        runCatching {
            val plaintext = json.encodeToString(change.payload).toByteArray()
            val contentKey = SharedBuysCrypto.derive(SharedBuysCrypto.OPS_INFO, key)
            val relayAuthKey = SharedBuysCrypto.derive(SharedBuysCrypto.RELAY_AUTH_INFO, key)
            val blob = SharedBuysCrypto.seal(plaintext, contentKey, room, change.device, change.seq)
            val tag = SharedBuysCrypto.recordTag(change.device, change.seq, blob, relayAuthKey)
            RelayRecord(change.device, change.seq, blob.toBase64Url(), tag.toBase64Url())
        }.getOrNull()

    private fun handle(event: RelayEvent) {
        when (event) {
            is RelayEvent.Connected -> {
                status = "connected"
                note("connected")
                resend()
            }
            is RelayEvent.Records -> ingest(event.records)
            is RelayEvent.Failed -> {
                status = "offline (${event.reason})"
                note("failed: ${event.reason}")
            }
        }
    }

    private fun resend() {
        val key = sessionKey ?: return
        val room = roomId ?: return
        val mine = changes.filter { it.device == deviceId }.mapNotNull { seal(it, key, room) }
        if (mine.isNotEmpty()) relay.send(mine.take(32)) { event -> handle(event) }
    }

    private fun ingest(records: List<RelayRecord>) {
        val key = sessionKey ?: return
        val room = roomId ?: return
        val contentKey = SharedBuysCrypto.derive(SharedBuysCrypto.OPS_INFO, key)
        val known = changes.map { it.id }.toSet()
        var added = 0
        for (record in records) {
            val identifier = "${record.device}#${record.seq}"
            if (known.contains(identifier)) continue
            val payload = runCatching {
                val blob = record.blob.fromBase64Url()
                val plaintext =
                    SharedBuysCrypto.open(blob, contentKey, room, record.device, record.seq)
                json.decodeFromString<SharedBuyPayload>(String(plaintext))
            }.getOrNull()
            if (payload == null) {
                note("could not open $identifier")
                continue
            }
            changes.add(SharedBuyChange(record.device, record.seq, payload))
            added += 1
        }
        if (added > 0) {
            persist()
            note("received $added")
        }
    }

    private fun persist() {
        val key = sessionKey ?: return
        store.save(
            SharedBuysSnapshot(
                sessionKey = key.toBase64Url(),
                deviceId = deviceId,
                eventNumber = eventNumber,
                lastSeq = lastSeq,
                changes = changes.toList()
            )
        )
    }

    private fun note(message: String) {
        log.add(0, message)
        if (log.size > 40) log.removeAt(log.size - 1)
    }
}
