package com.tsubuzaki.circlesgo.sharedbuys

import android.content.Context
import android.net.Uri
import com.tsubuzaki.circlesgo.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID

class SharedBuysSession(private val context: Context, private val scope: CoroutineScope) {

    private val store = SharedBuysStore(context)
    private val relay = SharedBuysRelay(scope)
    private val bluetooth = SharedBuysBluetooth(context)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    var status by mutableStateOf("idle")
        private set
    var relayBaseUrl by mutableStateOf("ws://10.0.2.2:8787")
    var actorPid by mutableStateOf(0)
    var nickname by mutableStateOf("")
    var isDebugVisible by mutableStateOf(false)
    var bluetoothPeers by mutableStateOf(0)
        private set
    var isBluetoothEnabled by mutableStateOf(true)

    val log = mutableStateListOf<String>()
    val changes = mutableStateListOf<SharedBuyChange>()

    var sessionKey: ByteArray? = null
        private set
    var deviceId by mutableStateOf("")
        private set
    private var eventNumber = 0
    private var lastSeq = 0L
    private var reconnectAttempt = 0
    private var reconnectJob: kotlinx.coroutines.Job? = null

    val isActive: Boolean get() = sessionKey != null

    val roomId: String? get() = sessionKey?.let { SharedBuysCrypto.roomId(it) }

    val items: List<SharedBuyItem> get() = SharedBuyFold.items(changes)

    val yourShare: Int
        get() = items.filter { it.assignee == actorPid && it.status != SharedBuyStatus.CANCELLED }
            .sumOf { it.cost }

    val groupTotal: Int
        get() = items.filter { it.status != SharedBuyStatus.CANCELLED }.sumOf { it.cost }

    val hasUnsentChanges: Boolean
        get() = status != "connected" && bluetoothPeers == 0 && changes.isNotEmpty()

    val members: Map<Int, String> get() = SharedBuyFold.members(changes)

    val joinUrl: String?
        get() = sessionKey?.let {
            "circles-app://buys-join?v=1&e=$eventNumber&k=${it.toBase64Url()}"
        }

    private val versionVector: Map<String, Long>
        get() = changes.groupBy { it.device }.mapValues { entry -> entry.value.maxOf { it.seq } }

    fun adoptIdentity() {
        val preferences = context.getSharedPreferences("circles", Context.MODE_PRIVATE)
        val storedPid = preferences.getInt("My.LastKnownPID", 0)
        val storedNickname = preferences.getString("My.LastKnownNickname", null)
        if (storedPid != 0) actorPid = storedPid
        if (!storedNickname.isNullOrEmpty()) nickname = storedNickname
        if (nickname.isEmpty()) nickname = context.getString(R.string.buys_shared_you)
    }

    fun restore() {
        adoptIdentity()
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
        startBluetooth()
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
        startBluetooth()
    }

    fun leave() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        bluetooth.stop()
        bluetoothPeers = 0
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
        reconnectJob?.cancel()
        reconnectJob = null
        status = "connecting"
        relay.connect(
            SharedBuysRelay.Endpoint(relayBaseUrl, room, deviceId, key, versionVector)
        ) { event -> handle(event) }
    }

    fun startBluetooth() {
        if (!isBluetoothEnabled) return
        val key = sessionKey ?: return
        bluetooth.start(key, SharedBuysDigest.bytes(versionVector)) { event ->
            when (event) {
                is BluetoothEvent.PeerCount -> {
                    bluetoothPeers = event.count
                    note("bluetooth peers ${event.count}")
                    if (event.count > 0) sendWant()
                }
                is BluetoothEvent.Payload -> handleBluetooth(event.bytes)
                is BluetoothEvent.Unavailable -> note("bluetooth: ${event.reason}")
            }
        }
    }

    fun stopBluetooth() {
        bluetooth.stop()
        bluetoothPeers = 0
    }

    fun missingBluetoothPermissions(): List<String> = bluetooth.missingPermissions()

    private fun sendWant() {
        val frame = buildJsonObject {
            put("t", "want")
            put("v", buildJsonObject { versionVector.forEach { (device, seq) -> put(device, seq) } })
        }
        bluetooth.send(frame.toString().toByteArray())
    }

    private fun handleBluetooth(payload: ByteArray) {
        val frame = runCatching {
            Json.parseToJsonElement(String(payload)).jsonObject
        }.getOrNull() ?: return
        when (frame["t"]?.jsonPrimitive?.content) {
            "want" -> {
                val theirs = frame["v"]?.jsonObject.orEmpty()
                val missing = changes.filter { change ->
                    change.seq > (theirs[change.device]?.jsonPrimitive?.long ?: 0L)
                }
                sendOverBluetooth(missing)
            }
            "ops" -> {
                val records = frame["o"]?.jsonArray?.mapNotNull { element ->
                    val entry = element.jsonObject
                    RelayRecord(
                        entry["d"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        entry["n"]?.jsonPrimitive?.long ?: return@mapNotNull null,
                        entry["b"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        entry["a"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    )
                }.orEmpty()
                ingest(records)
            }
        }
    }

    private fun sendOverBluetooth(outgoing: List<SharedBuyChange>) {
        if (outgoing.isEmpty() || bluetoothPeers == 0) return
        val key = sessionKey ?: return
        val room = roomId ?: return
        val records = outgoing.mapNotNull { seal(it, key, room) }
        if (records.isEmpty()) return
        val frame = buildJsonObject {
            put("t", "ops")
            put("o", buildJsonArray {
                records.forEach { record ->
                    add(buildJsonObject {
                        put("d", record.device)
                        put("n", record.seq)
                        put("b", record.blob)
                        put("a", record.tag)
                    })
                }
            })
        }
        bluetooth.send(frame.toString().toByteArray())
    }

    fun runSelfTest() {
        val vector = mapOf("aaaaaaaa" to 3L, "bbbbbbbb" to 1L, "cafebabe" to 260L)
        note("digest ${SharedBuysDigest.bytes(vector).toHex()}")

        val payload = ByteArray(500) { (it % 251).toByte() }
        val frames = SharedBuysFraming.chunks(payload, 7)
        val reassembler = SharedBuysFraming.Reassembler()
        var rebuilt: ByteArray? = null
        for (frame in frames.shuffled()) {
            reassembler.accept(frame)?.let { rebuilt = it }
        }
        val ok = rebuilt?.contentEquals(payload) == true
        note("framing ${frames.size} chunks, round trip ${if (ok) "ok" else "FAILED"}")

        sessionKey?.let {
            note("ble tag ${SharedBuysProfile.sessionTag(it).toHex()} window ${SharedBuysProfile.window()}")
        }
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
        sendOverBluetooth(listOf(change))
        bluetooth.update(SharedBuysDigest.bytes(versionVector))
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
                reconnectAttempt = 0
                note("connected")
                resend()
            }
            is RelayEvent.Records -> ingest(event.records)
            is RelayEvent.Failed -> {
                status = "offline (${event.reason})"
                note("failed: ${event.reason}")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (!isActive || reconnectJob != null) return
        if (bluetoothPeers > 0) {
            note("holding off, bluetooth is carrying")
            return
        }
        reconnectAttempt = minOf(reconnectAttempt + 1, 6)
        val delayMs = (minOf(Math.pow(2.0, reconnectAttempt.toDouble()), 30.0) * 1000).toLong() +
            (0..1000).random()
        note("reconnect in ${delayMs / 1000.0}s")
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            reconnectJob = null
            if (isActive && bluetoothPeers == 0) connect()
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
            bluetooth.update(SharedBuysDigest.bytes(versionVector))
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
