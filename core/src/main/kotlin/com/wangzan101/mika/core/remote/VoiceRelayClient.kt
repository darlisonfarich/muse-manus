package com.wangzan101.mika.core.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.random.Random
import java.util.concurrent.TimeUnit

/** Persistent relay voice leg. Access tokens are evaluated on offers and are never retained. */
class VoiceRelayClient(
    private val scope: CoroutineScope,
    private val hub: VoiceA2Hub,
    private val acceptsToken: (String) -> Boolean,
    private val http: OkHttpClient = RelayGateway.http().newBuilder().pingInterval(10, TimeUnit.SECONDS).build(),
) {
    private var job: Job? = null
    private var socket: WebSocket? = null
    private var identity: String? = null

    @Synchronized fun start(pairing: RelayPairing) {
        val key = pairing.gateway + "\n" + pairing.deviceToken
        if (job?.isActive == true && identity == key) return
        stop()
        identity = key
        job = scope.launch {
            var attempt = 0
            while (true) {
                val ended = kotlinx.coroutines.CompletableDeferred<Unit>()
                var opened = false
                val url = RelayGateway.parse(pairing.gateway).newBuilder().encodedPath("/voice/device").build()
                val request = Request.Builder().url(url).header("Authorization", "Bearer ${pairing.deviceToken}")
                    .header("Sec-WebSocket-Protocol", RelayClient.PROTOCOL).build()
                val listener = object : WebSocketListener() {
                    private var writer: VoiceA2Sender? = null
                    override fun onOpen(webSocket: WebSocket, response: Response) { opened = true }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val control = runCatching { json.decodeFromString<VoiceControl>(text) }.getOrNull()
                            ?: run { webSocket.cancel(); return }
                        if (control.type != "voice_offer") { hub.onText(text); return }
                        if (control.token.isEmpty()) { webSocket.cancel(); return }
                        if (!acceptsToken(control.token)) {
                            webSocket.send(json.encodeToString(VoiceControl("voice_reject")))
                            return
                        }
                        val next = VoiceA2Sender { opcode, payload ->
                            val sent = when (opcode) {
                                1 -> webSocket.send(payload.toString(Charsets.UTF_8))
                                2 -> webSocket.send(ByteString.of(*payload))
                                else -> false
                            }
                            if (!sent) throw java.io.IOException("voice relay write failed")
                        }
                        if (!hub.attach(next)) {
                            webSocket.send(json.encodeToString(VoiceControl("voice_reject")))
                            return
                        }
                        writer = next
                        if (!webSocket.send(json.encodeToString(VoiceControl("voice_accept")))) {
                            hub.detach(next)
                            writer = null
                            webSocket.cancel()
                        }
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { hub.onBinary(bytes.toByteArray()) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        writer?.let(hub::detach)
                        ended.complete(Unit)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        writer?.let(hub::detach)
                        ended.complete(Unit)
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        writer?.let(hub::detach)
                        ended.complete(Unit)
                    }
                }
                synchronized(this@VoiceRelayClient) {
                    if (identity != key) return@launch
                    socket = http.newWebSocket(request, listener)
                }
                ended.await()
                synchronized(this@VoiceRelayClient) {
                    if (identity != key) return@launch
                    socket = null
                }
                attempt = if (opened) 0 else (attempt + 1).coerceAtMost(6)
                delay((1_000L shl attempt).coerceAtMost(30_000) + Random.nextLong(500))
            }
        }
    }

    @Synchronized fun stop() {
        identity = null
        job?.cancel()
        job = null
        socket?.cancel()
        socket = null
    }

    @Serializable private data class VoiceControl(val type: String, val token: String = "")
    private companion object { val json = Json { ignoreUnknownKeys = true } }
}
