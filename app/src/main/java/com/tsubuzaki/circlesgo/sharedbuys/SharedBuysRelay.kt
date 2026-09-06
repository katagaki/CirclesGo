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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RelayRecord(val device: String, val seq: Long, val blob: String, val tag: String)

sealed interface RelayEvent {
    data object Connected : RelayEvent
    data class Records(val records: List<RelayRecord>) : RelayEvent
    data class Failed(val reason: String) : RelayEvent
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
            put("k", relayAuthKey.toBase64Url())
            put("ts", timestamp)
            put("a", tag.toBase64Url())
        }.toString()
    }

    private fun handle(text: String, onEvent: (RelayEvent) -> Unit) {
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when ((frame["t"] as? JsonPrimitive)?.content) {
            "ops" -> {
                val records = frame["o"]?.jsonArray.orEmpty().mapNotNull { element ->
                    val entry = element.jsonObject
                    val device = entry["d"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val seq = entry["n"]?.jsonPrimitive?.int?.toLong() ?: return@mapNotNull null
                    val blob = entry["b"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val tag = entry["a"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    RelayRecord(device, seq, blob, tag)
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
