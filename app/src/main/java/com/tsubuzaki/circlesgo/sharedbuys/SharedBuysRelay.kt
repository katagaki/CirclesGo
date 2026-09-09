package com.tsubuzaki.circlesgo.sharedbuys

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RelayRecord(val device: String, val seq: Long, val blob: String, val tag: String)

sealed interface RelayEvent {
    data object Connected : RelayEvent
    data class Records(val records: List<RelayRecord>) : RelayEvent
    data class Failed(val reason: String) : RelayEvent

    /**
     * The relay closed the socket cleanly.
     *
     * consumeEach returns normally on a close frame and runCatching succeeds, so a clean
     * close used to fire no event at all: when the room's 48 hour alarm closed sockets
     * with 1001, iOS reconnected and Android sat at "connected", dark, until the app was
     * restarted.
     */
    data class Closed(val code: Int) : RelayEvent
}

class SharedBuysRelay(private val scope: CoroutineScope) {

    data class Endpoint(
        val baseUrl: String,
        val roomId: String,
        val deviceId: String,
        val sessionKey: ByteArray,
        val vector: Map<String, Long>
    )

    private val client = HttpClient(OkHttp) { install(WebSockets) }
    private val json = Json { ignoreUnknownKeys = true }
    private var session: io.ktor.websocket.WebSocketSession? = null
    private var job: Job? = null

    fun connect(endpoint: Endpoint, onEvent: (RelayEvent) -> Unit) {
        disconnect()
        job = scope.launch {
            runCatching {
                val socket = client.webSocketSession("${endpoint.baseUrl}/r/${endpoint.roomId}")
                session = socket
                socket.send(Frame.Text(helloFrame(endpoint)))
                onEvent(RelayEvent.Connected)
                socket.incoming.consumeEach { frame ->
                    if (frame is Frame.Text) handle(frame.readText(), onEvent)
                }
                onEvent(RelayEvent.Closed(socket.closeReason.await()?.code?.toInt() ?: 1006))
            }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException) {
                    onEvent(RelayEvent.Failed(it.message ?: "socket error"))
                }
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        val socket = session
        session = null
        if (socket != null) scope.launch { runCatching { socket.close() } }
    }

    fun send(records: List<RelayRecord>, onEvent: (RelayEvent) -> Unit = {}) {
        val socket = session
        if (socket == null) {
            onEvent(RelayEvent.Failed("no socket"))
            return
        }
        if (records.isEmpty()) return
        val frame = buildJsonObject {
            put("t", "ops")
            put("o", kotlinx.serialization.json.buildJsonArray {
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
        scope.launch {
            runCatching { socket.send(Frame.Text(frame.toString())) }
                .onFailure { onEvent(RelayEvent.Failed("send: ${it.message}")) }
        }
    }

    /**
     * Whether the room key may be handed to this relay.
     *
     * The hello frame registers a room by uploading relayAuthKey, the same key that
     * backs every recordTag. Over ws:// anyone on the path recovers it and can forge
     * records for any device, so the key only travels over TLS — or to a development
     * server on this machine, which includes the emulator's alias for its host. A relay
     * that has not seen the key answers a hello with CLOSE_UNKNOWN_ROOM, which is a
     * diagnosable failure rather than a silent leak.
     */
    private fun allowsKeyUpload(baseUrl: String): Boolean {
        val url = runCatching { java.net.URI(baseUrl) }.getOrNull() ?: return false
        val scheme = url.scheme?.lowercase() ?: return false
        if (scheme == "wss" || scheme == "https") return true
        val host = url.host?.lowercase() ?: return false
        return host in listOf("localhost", "127.0.0.1", "::1", "10.0.2.2")
    }

    private fun helloFrame(endpoint: Endpoint): String {
        val relayAuthKey = SharedBuysCrypto.derive(
            SharedBuysCrypto.RELAY_AUTH_INFO,
            endpoint.sessionKey
        )
        val timestamp = System.currentTimeMillis() / 1000
        val tag = SharedBuysCrypto.helloTag(endpoint.deviceId, timestamp, relayAuthKey)
        return buildJsonObject {
            put("t", "hello")
            put("d", endpoint.deviceId)
            put("v", buildJsonObject {
                endpoint.vector.forEach { (device, seq) -> put(device, seq) }
            })
            if (allowsKeyUpload(endpoint.baseUrl)) put("k", relayAuthKey.toBase64Url())
            put("ts", timestamp)
            put("a", tag.toBase64Url())
        }.toString()
    }

    private fun handle(text: String, onEvent: (RelayEvent) -> Unit) {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when ((frame["t"] as? JsonPrimitive)?.content) {
            "ops" -> {
                // Parsed per record: a seq wider than Int used to throw out of the whole
                // mapNotNull, unwind consumeEach and tear the socket down, rather than
                // costing the one record that carried it.
                val records = frame["o"]?.jsonArray.orEmpty().mapNotNull { element ->
                    runCatching {
                        val entry = element.jsonObject
                        RelayRecord(
                            entry["d"]?.jsonPrimitive?.content ?: return@runCatching null,
                            entry["n"]?.jsonPrimitive?.long ?: return@runCatching null,
                            entry["b"]?.jsonPrimitive?.content ?: return@runCatching null,
                            entry["a"]?.jsonPrimitive?.content ?: return@runCatching null
                        )
                    }.getOrNull()
                }
                onEvent(RelayEvent.Records(records))
            }
            "err" -> onEvent(RelayEvent.Failed(frame["c"]?.jsonPrimitive?.content ?: "error"))
        }
    }
}

private fun List<kotlinx.serialization.json.JsonElement>?.orEmpty() =
    this ?: emptyList()

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content
