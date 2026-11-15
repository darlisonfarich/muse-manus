package com.darlisonfarich.gadgetlink.noise

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teste 1 da refatoração da LinkSession (despacho por streamId + registerDevice): a instância
 * REGISTRADA, a que o celular usa hoje para o link de gadget, não pode mudar nem um byte.
 *
 * Este teste foi escrito e rodou VERDE contra o código anterior à refatoração (838f00e): é um teste
 * de caracterização, não um teste que falha primeiro. O que ele fixa:
 *  - o SHA-256 de tudo o que a instância manda pelo socket até o link.register (handshake, abertura
 *    de /link-control e o corpo do registro), com chaves, chunk ids e id do pedido fixos;
 *  - que, depois do ack, quadros de um stream desconhecido continuam sendo descartados em silêncio:
 *    nenhum callback, nenhum byte enviado, nenhuma linha de log.
 *
 * Falha se: a ordem ou o conteúdo dos quadros de saída da instância registrada mudar (por exemplo,
 * se ela passasse a consumir um chunk id ou um nonce a mais, ou a mandar algo antes do registro), ou se um
 * stream desconhecido passasse a ter efeito nela. Usa só a API anterior à refatoração, de propósito:
 * a instância registrada é a construída com os argumentos que já existiam.
 */
class LinkSessionRegisteredInstanceTest {
    private val registerId = "11111111-2222-3333-4444-555555555555"
    private val device = DeviceDescription("homelink-abc123", "Celular", "0.1.7")

    private class Events : LinkListener {
        val outcomes = ArrayList<LinkOutcome>()
        var registered = 0
        val logs = ArrayList<String>()
        override fun onRegistered() { registered++ }
        override fun onOutcome(outcome: LinkOutcome) { outcomes += outcome }
        override fun log(line: String) { logs += line }
    }

    @Test
    fun theRegisteredInstanceSendsTheSameBytesAsBeforeAndStillDropsUnknownStreams() {
        val muse = StreamMuse(fixedKeys(0x33, 0x44))
        val events = Events()
        var nextChunk = 100L
        val session = LinkSession(
            device, muse.socket, events,
            newInitiator = { NoiseXxInitiator(fixedKeys(0x11, 0x22)) },
            newRequestId = { registerId },
            chunkIds = { nextChunk++ },
        )

        session.onOpen()
        while (muse.outbox.isNotEmpty()) session.onBinary(muse.outbox.removeFirst())

        // A abertura do stream de controle e o link.register, como sempre.
        val opened = muse.opened().single()
        assertEquals(1L, opened.streamId)
        assertEquals(ServiceType.DAEMON, opened.service)
        assertEquals("POST", opened.request.verb)
        assertEquals("/link-control", opened.request.path)
        assertFalse(opened.request.endBody)
        val register = MessageDecoder().feed(muse.bodyOf(1L)).single()
        assertEquals("link.register", register["method"]!!.jsonPrimitive.content)
        assertEquals(registerId, register["id"]!!.jsonPrimitive.content)

        // Os bytes: handshake (2 quadros) + abertura (1) + registro (1), idênticos aos de antes da refatoração.
        assertEquals(4, muse.sentByClient.size)
        assertEquals(GOLDEN_BYTES_UNTIL_REGISTER, digestOf(muse.sentByClient))
        assertEquals(0, events.registered)
        assertTrue(events.outcomes.isEmpty())

        // O ack do Muse registra a instância, uma vez.
        muse.response(1L, 200)
        muse.body(1L, LinkWire.encodeMessage(JsonObject(mapOf("id" to JsonPrimitive(registerId), "ok" to JsonPrimitive(true)))))
        while (muse.outbox.isNotEmpty()) session.onBinary(muse.outbox.removeFirst())
        assertEquals(1, events.registered)
        assertTrue(session.isRegistered)

        // Um stream que a instância não abriu continua descartado, seja qual for o quadro.
        val sentBefore = muse.sentByClient.size
        val logsBefore = events.logs.size
        muse.response(7L, 200, "x".toByteArray(), end = true)
        muse.body(7L, "voice".toByteArray())
        muse.reset(7L)
        while (muse.outbox.isNotEmpty()) session.onBinary(muse.outbox.removeFirst())
        assertTrue("nada de desfecho por causa de stream desconhecido", events.outcomes.isEmpty())
        assertEquals("nenhum byte novo no socket", sentBefore, muse.sentByClient.size)
        assertEquals("nenhuma linha de log nova", logsBefore, events.logs.size)
        assertEquals(1, events.registered)
        assertFalse(muse.closed)
    }

    private companion object {
        /** SHA-256 dos 4 quadros enviados até o link.register, gerado pelo código anterior à refatoração (838f00e). */
        const val GOLDEN_BYTES_UNTIL_REGISTER = "2f75821022fb745b258db8d522b206abf2f40c66ed94a38105da1829b8161e86"
    }
}
