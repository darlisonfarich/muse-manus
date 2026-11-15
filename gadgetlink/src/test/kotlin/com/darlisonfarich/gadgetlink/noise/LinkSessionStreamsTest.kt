package com.darlisonfarich.gadgetlink.noise

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes 2, 3 e 4 da refatoração da LinkSession (o teste 1, o da instância registrada, está em
 * [LinkSessionRegisteredInstanceTest]), mais o 5 (status não-2xx num stream de dados), pedido na
 * revisão do PR #35 e escrito depois da implementação. Foram escritos ANTES da implementação: a API que usam
 * (`registerDevice`, `LinkListener.onTransportReady`, `StreamSink`, `openStream`, `sendStreamBody`,
 * `StreamSend`) ainda não existia, então a primeira execução é uma falha de compilação listando
 * exatamente esses nomes. É para a voz do SDK: uma segunda sessão Noise, sem /link-control e sem
 * link.register, só com /voice/realtime (e o end-call), e com vários streams no mesmo socket.
 */
class LinkSessionStreamsTest {
    private val device = DeviceDescription("homelink-abc123", "Muse voice", "0.1.7")
    private val callPath = "/voice/realtime?call_id=0b9d0a50-6c3c-4c53-9a5e-3f2f3b2f9f11"
    private val ndjson = listOf(Header("Content-Type", "application/x-ndjson; charset=utf-8"))

    private class Events : LinkListener {
        val outcomes = ArrayList<LinkOutcome>()
        var registered = 0
        var ready = 0
        override fun onRegistered() { registered++ }
        override fun onTransportReady() { ready++ }
        override fun onOutcome(outcome: LinkOutcome) { outcomes += outcome }
    }

    /** Um consumidor de stream que só anota o que recebeu, em texto, na ordem. */
    private class Sink : StreamSink {
        val events = ArrayList<String>()
        override fun onResponse(status: Int, body: ByteArray, end: Boolean) { events += "response $status [${String(body)}] end=$end" }
        override fun onBody(data: ByteArray, end: Boolean) { events += "body ${String(data)} end=$end" }
        override fun onReset(code: ResetCode, reason: String) { events += "reset $code $reason" }
    }

    private class Rig(device: DeviceDescription, registerDevice: Boolean) {
        val muse = StreamMuse(fixedKeys(0x33, 0x44))
        val events = Events()
        var nextChunk = 100L
        val session = LinkSession(
            device, muse.socket, events,
            newInitiator = { NoiseXxInitiator(fixedKeys(0x11, 0x22)) },
            newRequestId = { "11111111-2222-3333-4444-555555555555" },
            chunkIds = { nextChunk++ },
            registerDevice = registerDevice,
        )

        /** Entrega à sessão tudo o que o Muse tem na caixa de saída. */
        fun pump() {
            while (muse.outbox.isNotEmpty()) session.onBinary(muse.outbox.removeFirst())
        }

        fun connect() {
            session.onOpen()
            pump()
        }
    }

    // ---- teste 2 -------------------------------------------------------------------------------
    /**
     * Falha se: a sessão sem registro mandar qualquer coisa depois do handshake (abrir /link-control,
     * mandar link.register, um reset...), se não avisar que o transporte está pronto, ou se o
     * openStream não abrir só o stream pedido.
     */
    @Test
    fun withoutRegistrationNoFrameGoesOutAfterTheHandshakeUntilAStreamIsOpened() {
        val r = Rig(device, registerDevice = false)
        r.connect()
        assertEquals("só as mensagens 1 e 3 do handshake", 2, r.muse.sentByClient.size)
        assertTrue("nenhum pedido, corpo nem reset", r.muse.seen.isEmpty())
        assertEquals("o transporte está pronto, uma vez", 1, r.events.ready)
        assertFalse(r.session.isRegistered)
        assertEquals(0, r.events.registered)
        assertTrue(r.events.outcomes.isEmpty())

        // Quadro de texto e tempo parado não fazem a sessão mandar nada por conta própria.
        r.session.onText()
        r.pump()
        assertEquals(2, r.muse.sentByClient.size)

        val id = r.session.openStream(callPath, ndjson, Sink())
        assertNotNull("a sessão ativa abre o stream", id)
        val opened = r.muse.opened().single()
        assertEquals(id, opened.streamId)
        assertEquals(ServiceType.DAEMON, opened.service)
        assertEquals("POST", opened.request.verb)
        assertEquals(callPath, opened.request.path)
        assertEquals("Content-Type", opened.request.headers.single().key)
        assertEquals("application/x-ndjson; charset=utf-8", opened.request.headers.single().value)
        assertFalse("o stream da chamada fica aberto", opened.request.endBody)
        assertEquals("só o pedido de abertura saiu", 1, r.muse.seen.size)
        assertFalse(r.session.isRegistered)
        assertTrue(r.events.outcomes.isEmpty())

        // Por contraste, a instância registrada continua abrindo /link-control sozinha e não anuncia "pronto".
        val registered = Rig(device, registerDevice = true)
        registered.connect()
        assertEquals("/link-control", registered.muse.opened().single().request.path)
        assertEquals(0, registered.events.ready)
    }

    // ---- teste 3 -------------------------------------------------------------------------------
    /**
     * Falha se: um quadro for entregue ao consumidor do outro stream, se um stream encerrado (fim ou
     * reset) continuar recebendo, se um reset de um stream derrubar a sessão ou o outro stream, ou se
     * um stream que ninguém abriu tiver algum efeito. Inclui o stream 0: sem registro não existe
     * stream de controle, então um link.invoke chegando ali não pode ser tratado nem respondido.
     */
    @Test
    fun twoInterleavedStreamsAreRoutedEachToItsOwnSink() {
        val r = Rig(device, registerDevice = false)
        r.connect()
        val a = Sink()
        val b = Sink()
        val idA = r.session.openStream("$callPath&n=a", ndjson, a)!!
        val idB = r.session.openStream("$callPath&n=b", ndjson, b)!!
        assertNotEquals(idA, idB)

        r.muse.response(idA, 200)
        r.muse.response(idB, 200)
        r.muse.body(idA, "a1".toByteArray())
        r.muse.body(idB, "b1".toByteArray())
        r.muse.body(idA, "a2".toByteArray())
        r.muse.body(idB, "b2".toByteArray(), end = true)
        r.muse.body(idB, "b-after-end".toByteArray()) // B já terminou: ninguém mais a recebe
        r.muse.reset(idA)
        r.muse.body(idA, "a-after-reset".toByteArray()) // A foi resetado: idem
        r.muse.body(99L, "ghost".toByteArray()) // stream que ninguém abriu
        val invoke = JsonObject(mapOf("method" to JsonPrimitive("link.invoke"), "id" to JsonPrimitive("x"), "command" to JsonPrimitive("system.run")))
        r.muse.body(0L, LinkWire.encodeMessage(invoke)) // stream 0: não é de controle nesta instância
        r.pump()

        assertEquals(
            listOf("response 200 [] end=false", "body a1 end=false", "body a2 end=false", "reset CANCELLED bye"),
            a.events,
        )
        assertEquals(
            listOf("response 200 [] end=false", "body b1 end=false", "body b2 end=true"),
            b.events,
        )
        assertTrue("o reset de um stream não encerra a sessão", r.events.outcomes.isEmpty())
        assertFalse(r.muse.closed)
        assertEquals("a sessão não mandou nada além das duas aberturas", 2, r.muse.seen.size)
    }

    // ---- teste 5 (pedido na revisão do PR #35) -------------------------------------------------
    /**
     * Falha se: um status não-2xx num stream de dados for tratado como o do stream de controle
     * (encerrar a sessão, fechar o socket, virar FORBIDDEN), se o sink não receber o status, ou se
     * depois dele a sessão parar de abrir e rotear streams. É o caso do 5.0: a admissão da voz
     * recusada (4xx/5xx em /voice/realtime) é um resultado da chamada, não uma queda do link.
     * Contraste no fim: o mesmo 403 no stream de controle da instância registrada encerra FORBIDDEN.
     */
    @Test
    fun aNon2xxStatusOnAnOpenStreamGoesToItsSinkAndDoesNotEndTheSession() {
        val r = Rig(device, registerDevice = false)
        r.connect()
        val refused = Sink()
        val failing = Sink()
        val idRefused = r.session.openStream("$callPath&n=403", ndjson, refused)!!
        val idFailing = r.session.openStream("$callPath&n=500", ndjson, failing)!!

        r.muse.response(idRefused, 403, "denied".toByteArray(), end = true)
        r.muse.response(idFailing, 500, "oops".toByteArray()) // sem fim: o stream continua aberto
        r.muse.body(idFailing, "tail".toByteArray(), end = true)
        r.muse.body(idRefused, "after-end".toByteArray()) // 403 veio com fim: ninguém mais recebe
        r.pump()

        assertEquals(listOf("response 403 [denied] end=true"), refused.events)
        assertEquals(listOf("response 500 [oops] end=false", "body tail end=true"), failing.events)
        assertTrue("status de stream de dados não encerra a sessão", r.events.outcomes.isEmpty())
        assertFalse("nem fecha o socket", r.muse.closed)

        // A sessão segue viva: abre outro stream e roteia normalmente.
        val next = Sink()
        val idNext = r.session.openStream("$callPath&n=next", ndjson, next)
        assertNotNull("depois do 403/500 a sessão ainda abre streams", idNext)
        r.muse.response(idNext!!, 200)
        r.muse.body(idNext, "ok".toByteArray())
        r.pump()
        assertEquals(listOf("response 200 [] end=false", "body ok end=false"), next.events)
        assertEquals(StreamSend.SENT, r.session.sendStreamBody(idNext, "pcm".toByteArray()))
        assertArrayEquals("pcm".toByteArray(), r.muse.bodyOf(idNext))
        assertTrue(r.events.outcomes.isEmpty())

        // Contraste: o mesmo 403 no stream de controle da instância registrada encerra a sessão.
        val registered = Rig(device, registerDevice = true)
        registered.connect()
        val control = registered.muse.opened().single().streamId
        registered.muse.response(control, 403, "denied".toByteArray(), end = true)
        registered.pump()
        assertEquals(listOf(LinkOutcome.FORBIDDEN), registered.events.outcomes)
        assertTrue(registered.muse.closed)
    }

    // ---- teste 4 -------------------------------------------------------------------------------
    private class Run(val frames: List<ByteArray>, val body: ByteArray, val lockHeld: List<Boolean>)

    private fun run(withBarredAttempts: Boolean): Run {
        val r = Rig(device, registerDevice = false)
        r.connect()
        val id = r.session.openStream(callPath, ndjson, Sink())!!
        assertEquals(StreamSend.SENT, r.session.sendStreamBody(id, "pcm-1".toByteArray()))
        val lockHeld = ArrayList<Boolean>()
        if (withBarredAttempts) {
            // Um quadro de áudio comum e um que, se fosse cifrado, viraria dois registros (> 65489 bytes).
            for (size in listOf(1920, 70_000)) {
                val result = r.session.sendStreamBody(id, ByteArray(size) { 0x42 }, admissible = {
                    lockHeld += Thread.holdsLock(r.session)
                    false
                })
                assertEquals(StreamSend.GATED, result)
            }
        }
        assertEquals(StreamSend.SENT, r.session.sendStreamBody(id, "pcm-2".toByteArray()))
        return Run(r.muse.sentByClient.map { it.copyOf() }, r.muse.bodyOf(id), lockHeld)
    }

    /**
     * Falha se: o quadro barrado for cifrado antes da checagem (gastaria nonce e chunk id, e todos os
     * quadros seguintes sairiam diferentes e o Muse não os decifraria), se a checagem rodar fora do
     * monitor da sessão (a porta poderia abrir entre a checagem e a cifra), se ela for consultada mais de
     * uma vez por tentativa, ou se uma sessão morta ainda a consultar.
     */
    @Test
    fun aBarredFrameIsNeverEncryptedSoItConsumesNeitherNonceNorChunkIdByteForByte() {
        val clean = run(withBarredAttempts = false)
        val gated = run(withBarredAttempts = true)

        assertEquals("a porta é consultada uma vez por tentativa", 2, gated.lockHeld.size)
        assertTrue("sempre sob o monitor da sessão, antes de cifrar", gated.lockHeld.all { it })

        // Byte a byte: com as tentativas barradas no meio, a sessão manda exatamente os mesmos quadros.
        assertEquals(clean.frames.size, gated.frames.size)
        for (i in clean.frames.indices) {
            assertArrayEquals("quadro $i do socket", clean.frames[i], gated.frames[i])
        }
        // E o Muse, que decifra na ordem e com nonce implícito, leu os dois quadros admitidos e nada do barrado.
        assertArrayEquals("pcm-1pcm-2".toByteArray(), gated.body)

        // Sessão morta: nem consulta a porta.
        val r = Rig(device, registerDevice = false)
        r.connect()
        val id = r.session.openStream(callPath, ndjson, Sink())!!
        r.session.stop()
        var consulted = false
        val result = r.session.sendStreamBody(id, "late".toByteArray(), admissible = { consulted = true; true })
        assertEquals(StreamSend.FAILED, result)
        assertFalse("sessão encerrada não consulta a porta", consulted)
    }
}
