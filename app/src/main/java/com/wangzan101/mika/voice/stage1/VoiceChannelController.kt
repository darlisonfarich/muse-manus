package com.wangzan101.mika.voice.stage1

import com.wangzan101.mika.core.remote.VoiceA2Hub
import com.wangzan101.mika.core.remote.VoiceA2State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Transport interface permits a future HTTP-pull implementation without changing the screen. */
interface VoiceTransport {
    val state: StateFlow<VoiceA2State>
    suspend fun submit(conversationId: String, turnId: String, generationId: String, wav: ByteArray): Boolean
    fun cancel()
}

/** VM initiates the socket. Android sends back over the authenticated accepted connection. */
class ReverseWebSocketVoiceTransport(private val hub: VoiceA2Hub) : VoiceTransport {
    override val state: StateFlow<VoiceA2State> get() = hub.state

    override suspend fun submit(conversationId: String, turnId: String, generationId: String, wav: ByteArray): Boolean =
        withContext(Dispatchers.IO) { hub.submit(conversationId, turnId, generationId, wav) }

    override fun cancel() = hub.cancel()
}

class VoiceChannelController(private val transport: VoiceTransport) {
    val state: StateFlow<VoiceA2State> = transport.state
    suspend fun send(conversationId: String, turnId: String, generationId: String, wav: ByteArray) =
        transport.submit(conversationId, turnId, generationId, wav)
    fun cancel() = transport.cancel()
}
