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

    var sessionKey: ByteArray? by mutableStateOf(null)
        private set
    var deviceId by mutableStateOf("")
        private set
    private var eventNumber = 0
    private var lastSeq = 0L
    private var reconnectAttempt = 0
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private val outbox = mutableListOf<RelayRecord>()
    private var flushJob: kotlinx.coroutines.Job? = null

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

    /**
     * What we hold with no gap in it, per device.
     *
     * A vector of max(seq) claims everything below the highest sequence number we have
     * seen, so a change learned out of order — over Bluetooth, or from a peer's backlog
     * — buries whatever is still missing underneath it, and the relay withholds the gap
     * forever. The contiguous prefix claims only what is actually complete; anything
     * above a hole is sent again and deduped on ingest.
     */
    private val versionVector: Map<String, Long>
        get() = contiguousPrefix(changes)

    /**
     * The same prefix, over the subset Bluetooth carries.
     *
     * The full vector counts relay-only changes, so advertising it to a peer would
     * claim we hold status flips whose sequence numbers sit below a name or cost we
     * happened to receive over the relay. The peer would then filter those flips out
     * of its reply and they would never arrive. The peer-to-peer path has to reason
     * about its own subset of the log.
     */
    private val bluetoothVersionVector: Map<String, Long>
        get() = contiguousPrefix(
            changes.filter { SharedBuyKind.travelsOverBluetooth(it.payload.kind) }
        )

    /**
     * What the advertised digest summarises: everything held over Bluetooth, gaps and
     * all.
     *
     * The digest answers "is there anything to exchange at all", so it has to count a
     * change sitting above a hole. The vector we send answers "what may you skip", and
     * must not.
     */
    private val bluetoothDigestVector: Map<String, Long>
        get() = changes.filter { SharedBuyKind.travelsOverBluetooth(it.payload.kind) }
            .groupBy { it.device }
            .mapValues { entry -> entry.value.maxOf { it.seq } }

    /**
     * The highest n for which every sequence number from 1 to n is present. A device we
     * hold nothing contiguous for is left out rather than claimed at 0.
     */
    private fun contiguousPrefix(changes: List<SharedBuyChange>): Map<String, Long> =
        changes.groupBy { it.device }.mapNotNull { entry ->
            val held = entry.value.mapTo(mutableSetOf()) { it.seq }
            var next = 1L
            while (held.contains(next)) next += 1
            if (next > 1L) entry.key to next - 1 else null
        }.toMap()

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
        flushJob?.cancel()
        flushJob = null
        outbox.clear()
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
        bluetooth.start(key, SharedBuysDigest.bytes(bluetoothDigestVector)) { event ->
            when (event) {
                is BluetoothEvent.PeerCount -> {
                    bluetoothPeers = event.count
                    note("bluetooth peers ${event.count}")
                }
                is BluetoothEvent.PeerVerified -> handshakeCompleted(event.digest)
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

    /**
     * A peer that advertised our own digest holds the same Bluetooth-eligible log, so
     * there is nothing for a version vector exchange to turn up.
     */
    private fun handshakeCompleted(peerDigest: ByteArray?) {
        if (peerDigest != null &&
            peerDigest.contentEquals(SharedBuysDigest.bytes(bluetoothDigestVector))
        ) {
            note("peer is level, skipping want")
            return
        }
        sendWant()
    }

    private fun sendWant() {
        SharedBuysWire.wantFrames(bluetoothVersionVector).forEach { bluetooth.send(it) }
    }

    private fun handleBluetooth(payload: ByteArray) {
        when (val frame = SharedBuysWire.decode(payload)) {
            is SharedBuysFrame.Want -> {
                val missing = changes.filter { change ->
                    SharedBuyKind.travelsOverBluetooth(change.payload.kind) &&
                        change.seq > (frame.vector[change.device] ?: 0L)
                }
                sendOverBluetooth(missing)
            }
            is SharedBuysFrame.Changes -> ingest(frame.records)
            null -> Unit
        }
    }

    private fun sendOverBluetooth(outgoing: List<SharedBuyChange>) {
        val eligible = outgoing.filter { SharedBuyKind.travelsOverBluetooth(it.payload.kind) }
        if (eligible.isEmpty() || bluetoothPeers == 0) return
        val key = sessionKey ?: return
        val room = roomId ?: return
        val records = eligible.mapNotNull { seal(it, key, room) }
        if (records.isEmpty()) return
        SharedBuysWire.changeFrames(records).forEach { bluetooth.send(it) }
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

            val handshake = SharedBuysProfile.handshake(it)
            val accepted = SharedBuysProfile.accepts(handshake, it)
            val stranger = ByteArray(it.size) { 0x5a }
            val rejected = !SharedBuysProfile.accepts(handshake, stranger)
            val notConfused = !SharedBuysProfile.isHandshake(frames.first())
            note(
                "handshake ${handshake.size}B " +
                    "accept ${if (accepted) "ok" else "FAILED"} " +
                    "reject ${if (rejected) "ok" else "FAILED"} " +
                    "framing ${if (notConfused) "ok" else "FAILED"}"
            )
        }

        checkWire()
    }

    /**
     * Encodes the wire format note's test vector from scratch and parses it back.
     *
     * This walks the whole construction — HKDF, AES-256-GCM, the record tag and the
     * frame layout — so a change on either platform that moves a byte shows up here
     * rather than as a peer that silently cannot open anything.
     */
    private fun checkWire() {
        val key = ByteArray(32) { it.toByte() }
        val room = SharedBuysCrypto.roomId(key)
        val contentKey = SharedBuysCrypto.derive(SharedBuysCrypto.OPS_INFO, key)
        val relayAuthKey = SharedBuysCrypto.derive(SharedBuysCrypto.RELAY_AUTH_INFO, key)
        val plaintext = "{\"a\":12345,\"k\":1,\"i\":\"a1b2c3d4\",\"c\":98765,\"v\":1}".toByteArray()
        val blob = SharedBuysCrypto.seal(plaintext, contentKey, room, "a1b2c3d4", 42)
        val recordTag = SharedBuysCrypto.recordTag("a1b2c3d4", 42, blob, relayAuthKey)
        val record = RelayRecord("a1b2c3d4", 42, blob.toBase64Url(), recordTag.toBase64Url())
        val frame = SharedBuysWire.changeFrames(listOf(record)).firstOrNull()
        if (frame == null) {
            note("wire vector FAILED to encode")
            return
        }
        note("wire ${frame.size}B vector ${if (frame.toHex() == TEST_VECTOR) "ok" else "FAILED"}")

        val decoded = SharedBuysWire.decode(frame) as? SharedBuysFrame.Changes
        val first = decoded?.records?.firstOrNull()
        val changesOk = first != null && first.device == record.device &&
            first.seq == record.seq && first.blob == record.blob && first.tag == record.tag
        val vector = mapOf("a1b2c3d4" to 42L, "cafebabe" to 260L)
        val wantFrame = SharedBuysWire.wantFrames(vector).firstOrNull()
        val wantOk = wantFrame != null &&
            (SharedBuysWire.decode(wantFrame) as? SharedBuysFrame.Want)?.vector == vector
        note(
            "wire round trip changes ${if (changesOk) "ok" else "FAILED"} " +
                "want ${if (wantOk) "ok" else "FAILED"}"
        )

        // A blobLen larger than what is left must be refused, not sliced.
        val overrun = SharedBuysWire.decode(frame.copyOf(frame.size - 1)) == null
        val wrongVersion = frame.copyOf().also { it[0] = 0x7F }
        val skipped = SharedBuysWire.decode(wrongVersion) == null
        note(
            "wire bounds ${if (overrun) "ok" else "FAILED"} " +
                "version ${if (skipped) "ok" else "FAILED"}"
        )
    }

    fun addItem(name: String, cost: Int, circleId: Int): String {
        val itemId = UUID.randomUUID().toString().take(8)
        append(SharedBuyKind.ADD_ITEM, itemId, circleId, name, cost)
        append(SharedBuyKind.SET_ASSIGNEE, itemId, circleId, null, actorPid)
        return itemId
    }

    fun rename(itemId: String, circleId: Int, name: String) {
        append(SharedBuyKind.RENAME_ITEM, itemId, circleId, name, null)
    }

    fun setCost(itemId: String, circleId: Int, cost: Int) {
        append(SharedBuyKind.SET_COST, itemId, circleId, null, cost)
    }

    fun remove(itemId: String, circleId: Int) {
        append(SharedBuyKind.REMOVE_ITEM, itemId, circleId, null, null)
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
        seal(change, key, room)?.let { enqueue(it) }
        sendOverBluetooth(listOf(change))
        bluetooth.update(SharedBuysDigest.bytes(bluetoothDigestVector))
    }

    /** Holds a record briefly so a burst of edits leaves as one frame. */
    private fun enqueue(record: RelayRecord) {
        outbox.add(record)
        if (outbox.size >= RECORDS_PER_FRAME) {
            flushOutbox()
            return
        }
        if (flushJob != null) return
        flushJob = scope.launch {
            kotlinx.coroutines.delay(COALESCE_WINDOW_MS)
            flushJob = null
            flushOutbox()
        }
    }

    private fun flushOutbox() {
        flushJob?.cancel()
        flushJob = null
        if (outbox.isEmpty()) return
        val records = outbox.toList()
        outbox.clear()
        relay.send(records) { event -> handle(event) }
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
            is RelayEvent.Closed -> {
                status = "offline (closed ${event.code})"
                note("closed ${event.code}")
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

    /**
     * Uploads every change this device authored, in frames the relay will accept.
     *
     * Taking the first 32 and discarding the rest left later changes with no path to the
     * server at all: resend() only runs on connect, and always re-took the same 32. Each
     * page is sealed as it is sent, so a long backlog no longer pays the whole seal cost
     * — encode, two derivations and AES-GCM per change — before transmitting any of it.
     */
    private fun resend() {
        val key = sessionKey ?: return
        val room = roomId ?: return
        changes.filter { it.device == deviceId }.chunked(RECORDS_PER_FRAME).forEach { page ->
            val records = page.mapNotNull { seal(it, key, room) }
            if (records.isNotEmpty()) relay.send(records) { event -> handle(event) }
        }
    }

    private fun ingest(records: List<RelayRecord>) {
        val key = sessionKey ?: return
        val room = roomId ?: return
        val contentKey = SharedBuysCrypto.derive(SharedBuysCrypto.OPS_INFO, key)
        val relayAuthKey = SharedBuysCrypto.derive(SharedBuysCrypto.RELAY_AUTH_INFO, key)
        val known = changes.mapTo(mutableSetOf()) { it.id }
        var added = 0
        for (record in records) {
            val identifier = "${record.device}#${record.seq}"
            if (known.contains(identifier)) continue
            // Whoever handed us this record — the relay, or a peer over Bluetooth — is
            // not trusted to have authored it. Check the tag before it enters the log.
            val authentic = runCatching {
                SharedBuysCrypto.recordTag(
                    record.device,
                    record.seq,
                    record.blob.fromBase64Url(),
                    relayAuthKey
                ).contentEquals(record.tag.fromBase64Url())
            }.getOrDefault(false)
            if (!authentic) {
                note("bad tag on $identifier")
                continue
            }
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
            // A batch can carry the same record twice — a relay echo, or a want reply
            // overlapping the live stream — so the set has to grow as we append.
            known.add(identifier)
            // A Lamport clock. Without it a fresh device's seq 1 sorts under an
            // established peer's seq 30, and the older edit wins on every screen.
            lastSeq = maxOf(lastSeq, record.seq)
            added += 1
        }
        if (added > 0) {
            persist()
            bluetooth.update(SharedBuysDigest.bytes(bluetoothDigestVector))
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

    companion object {
        /** The relay refuses a frame carrying more than this (MAX_RECORDS_PER_FRAME). */
        private const val RECORDS_PER_FRAME = 32

        /**
         * How long an outbound record waits for company before it is sent.
         *
         * The relay's bucket is 20 messages per 10 seconds and addItem alone emits two
         * changes, so a frame per change put ten quick adds over the limit and earned a
         * rate-limit close. A quarter second is under the threshold of feeling laggy and
         * collapses a burst of typing into one frame.
         */
        private const val COALESCE_WINDOW_MS = 250L

        /** The frame from the wire format note, byte for byte. */
        private const val TEST_VECTOR = "010101a1b2c3d4000000000000002a0040" +
            "2ab0b4c6e69b2900dc6274a75e783cb336899016f440b24fcb8da6dfb640f662" +
            "4e39050b00d0727ff8af49ad0b512d5b55fd21a0f1ce3cdbc148f01437111d57" +
            "f5d63b17d693abf3217419adf1b582d2"
    }
}
