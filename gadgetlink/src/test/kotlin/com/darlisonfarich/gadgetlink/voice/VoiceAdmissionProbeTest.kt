package com.darlisonfarich.gadgetlink.voice

import com.darlisonfarich.gadgetlink.MuseApi
import com.darlisonfarich.gadgetlink.RefreshResult
import com.darlisonfarich.gadgetlink.Vm
import com.darlisonfarich.gadgetlink.VmList
import com.darlisonfarich.gadgetlink.VmListWithFields
import com.darlisonfarich.gadgetlink.noise.DeviceDescription
import com.darlisonfarich.gadgetlink.noise.LinkScheduler
import com.darlisonfarich.gadgetlink.noise.LinkSession
import com.darlisonfarich.gadgetlink.noise.MuseApiPort
import com.darlisonfarich.gadgetlink.noise.NoiseXxInitiator
import com.darlisonfarich.gadgetlink.noise.PairingLoad
import com.darlisonfarich.gadgetlink.noise.PairingRecord
import com.darlisonfarich.gadgetlink.noise.PairingStore
import com.darlisonfarich.gadgetlink.noise.StreamMuse
import com.darlisonfarich.gadgetlink.noise.fixedKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * O 5.0 inteiro contra o Muse falso que fala o protocolo de verdade (decifra tudo o que a sessão manda,
 * na ordem): a sequência de 11 quadros da receita, nenhum áudio enviado, o end-call em todo caminho
 * depois da admissão, e o registro só com estágio/status/tamanho/ms. Relógio e agendador são falsos:
 * nenhum teste dorme.
 */
class VoiceAdmissionProbeTest {
    private val callId = "0b9d0a50-6c3c-4c53-9a5e-3f2f3b2f9f11"
    private val device = DeviceDescription("homelink-abc123", "Muse voice", "0.1.7")
    private val record = PairingRecord("access-SECRET", "refresh-SECRET", "", "noise.example", 0, null)
    private val vm = Vm("wss://vm", "vm-bearer-SECRET", "vm", "vm-1", isDefault = true)

    /** Relógio e agendador de mentira: o tempo só anda quando o teste manda. */
    private class Time : VoiceClock, LinkScheduler {
        var now = 1_000L
        var wall = 1_794_769_445_007L
        private var seq = 0L
        private val tasks = ArrayList<Triple<Long, Long, () -> Unit>>()
        override fun monoMs() = now
        override fun wallMs() = wall
        override fun schedule(delayMs: Long, task: () -> Unit) {
            tasks += Triple(now + delayMs, seq++, task)
        }

        /** Roda uma tarefa vencida (a mais antiga); false se não há nenhuma. */
        fun runOne(): Boolean {
            val next = tasks.filter { it.first <= now }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: return false
            tasks.remove(next)
            next.third()
            return true
        }

        fun nextDue(): Long? = tasks.minOfOrNull { it.first }
    }

    private inner class Rig(
        loads: List<PairingLoad> = listOf(PairingLoad.Paired(record)),
        private val lists: MutableList<VmList> = mutableListOf(VmList(listOf(vm), 200)),
        private val upgrade: Int? = 101,
        /** Nomes dos campos que a porta devolve junto da lista (null = a porta não sabe, como o padrão). */
        private val fieldNames: List<List<String>>? = null,
        /** Valores de metadado que a porta devolve junto da lista (null = a porta não sabe, como o padrão). */
        private val meta: List<Map<String, String>>? = null,
    ) {
        val time = Time()
        val muse = StreamMuse(fixedKeys(0x33, 0x44))
        val stages = ArrayList<VoiceStageEntry>()
        var result: VoiceAdmissionResult? = null
        val logs = ArrayList<String>()
        val tokensSeen = ArrayList<String>()
        var session: LinkSession? = null
        private var nextChunk = 100L
        private val loadQueue = ArrayDeque(loads)

        val store = object : PairingStore {
            override fun load(): PairingLoad = if (loadQueue.size > 1) loadQueue.removeFirst() else loadQueue.first()
            override fun save(record: PairingRecord) = error("o 5.0 nunca grava o pareamento")
            override fun delete() = error("o 5.0 nunca apaga o pareamento")
        }
        val api = object : MuseApiPort {
            override fun fetchVmList(accessToken: String, apiRoot: String): VmList {
                tokensSeen += accessToken
                return if (lists.size > 1) lists.removeAt(0) else lists.first()
            }
            override fun fetchVmListWithFields(accessToken: String, apiRoot: String) =
                VmListWithFields(fetchVmList(accessToken, apiRoot), fieldNames, meta)
            override fun refreshDeviceToken(refreshToken: String, deviceId: String, apiRoot: String, sdkToken: String?): RefreshResult =
                error("o 5.0 nunca renova o token")
        }
        val probe = VoiceAdmissionProbe(
            deviceId = device.nodeId,
            store = store,
            api = api,
            connector = { _, _, listener, onUpgrade ->
                val s = LinkSession(
                    device, muse.socket, listener,
                    newInitiator = { NoiseXxInitiator(fixedKeys(0x11, 0x22)) },
                    chunkIds = { nextChunk++ },
                    registerDevice = false,
                )
                session = s
                onUpgrade(upgrade)
                if (upgrade == 101) s.onOpen() else if (upgrade != null) s.onUpgradeRejected(upgrade) else s.onClosed("IOException")
                s
            },
            scheduler = time,
            clock = time,
            listener = object : VoiceProbeListener {
                override fun onStage(entry: VoiceStageEntry) { stages += entry }
                override fun onFinished(result: VoiceAdmissionResult) { this@Rig.result = result }
                override fun log(line: String) { logs += line }
            },
            newCallId = { callId },
        )

        /** Roda tudo o que já venceu, entregando à sessão o que o Muse respondeu, até parar. */
        fun settle() {
            while (true) {
                val s = session
                if (s != null && muse.outbox.isNotEmpty()) {
                    s.onBinary(muse.outbox.removeFirst())
                    continue
                }
                if (!time.runOne()) return
            }
        }

        fun advance(ms: Long) {
            val target = time.now + ms
            settle()
            while (true) {
                val due = time.nextDue()
                if (due == null || due > target) break
                time.now = maxOf(time.now, due)
                settle()
            }
            time.now = target
            settle()
        }

        fun start() {
            probe.start()
            settle()
        }

        val mediaId: Long get() = muse.opened().first { it.request.path.startsWith("/voice/realtime") }.streamId
        fun endCalls() = muse.opened().filter { it.request.path == "/voice/end-call" }

        fun send(stream: Long, vararg lines: String, end: Boolean = false) {
            muse.body(stream, lines.joinToString("") { it + "\n" }.toByteArray(), end)
            settle()
        }

        fun ackEndCall(index: Int, body: String = """{"ok":true,"result":{"ok":true}}""", status: Int = 200) {
            muse.response(endCalls()[index].streamId, status, body.toByteArray(), end = true)
            settle()
        }

        fun stageNames() = stages.map { "${it.stage}${if (it.ok) "" else "!"}" }
    }

    private val readiness = """{"type":"hatch.voice.session","call_id":"0b9d0a50-6c3c-4c53-9a5e-3f2f3b2f9f11","session_id":"sess-PRIVATE","thread_id":"thr-PRIVATE"}"""
    private val zeroKeepalive = """{"type":"response.output_audio.delta","delta":"${Base64.getEncoder().encodeToString(ByteArray(1920))}"}"""

    /** O caminho feliz até o end-call, com a resposta da mídia e a prontidão chegando. */
    private fun Rig.admitAndBecomeReady() {
        start()
        muse.response(mediaId, 200)
        send(mediaId, """{"type":"session.created","note":"server text PRIVATE"}""", zeroKeepalive, readiness)
    }

    // ---- a receita inteira ------------------------------------------------------------------------
    @Test
    fun theElevenFrameRecipeAdmitsWaitsWithoutAudioAndEndsWithANestedAck() {
        val r = Rig()
        r.admitAndBecomeReady()
        assertEquals(listOf("PAIRING", "FETCH_VMS", "UPGRADE", "HANDSHAKE", "ADMISSION", "READINESS"), r.stageNames())
        assertTrue("ainda não mandou o end-call: está na espera de ~2 s", r.endCalls().isEmpty())

        r.advance(1_999)
        assertTrue("antes dos 2 s, nada", r.endCalls().isEmpty())
        r.time.wall += 2_093
        r.advance(1)
        val endCall = r.endCalls().single()
        assertTrue("end-call de uma vez só (end_body no pedido)", endCall.request.endBody)
        assertEquals("application/json", endCall.request.headers.single { it.key == "Content-Type" }.value)
        assertEquals(
            """{"call_id":"$callId","call_started_at":"2026-11-15T19:04:05.007+00:00","call_ended_at":"2026-11-15T19:04:07.100+00:00","thread_id":"thr-PRIVATE"}""",
            String(endCall.request.body),
        )
        r.ackEndCall(0)
        assertEquals("o end-call é repetido uma vez (idempotência)", 2, r.endCalls().size)
        r.ackEndCall(1, status = 200)
        // Half-close da mídia: corpo vazio com end_body; o Muse termina o dele e a sonda fecha.
        r.send(r.mediaId, end = true)

        // A ordem dos quadros (depois do handshake): abre a mídia, call.start, oferta, end-call, end-call
        // repetido, half-close. Nenhum quadro de áudio em lugar nenhum.
        val seen = r.muse.seen.map {
            when (it) {
                is StreamMuse.Opened -> "open ${it.request.path.substringBefore('?')} end=${it.request.endBody}"
                is StreamMuse.Body -> "body ${it.streamId} ${String(it.data).substringBefore(",")} end=${it.end}"
                is StreamMuse.Cancelled -> "reset ${it.streamId}"
            }
        }
        val m = r.mediaId
        assertEquals(
            listOf(
                "open /voice/realtime end=false",
                """body $m {"type":"hatch.voice.call.start" end=false""",
                """body $m {"type":"hatch.voice.capability.offer" end=false""",
                "open /voice/end-call end=true",
                "body ${r.endCalls()[0].streamId} {\"call_id\":\"$callId\" end=true",
                "open /voice/end-call end=true",
                "body ${r.endCalls()[1].streamId} {\"call_id\":\"$callId\" end=true",
                "body $m  end=true",
            ),
            seen,
        )
        val media = r.muse.opened().first()
        assertEquals("/voice/realtime?call_id=$callId", media.request.path)
        assertEquals("application/x-ndjson; charset=utf-8", media.request.headers.single().value)
        assertArrayEquals(
            (String(VoiceWire.ndjsonLine(VoiceWire.callStart(callId, "homelink-abc123"))) +
                String(VoiceWire.ndjsonLine(VoiceWire.END_CALL_STOP_OFFER))).toByteArray(),
            r.muse.bodyOf(m),
        )

        assertEquals(
            listOf("PAIRING", "FETCH_VMS", "UPGRADE", "HANDSHAKE", "ADMISSION", "READINESS", "HOLD", "END_CALL", "END_CALL_REPEAT", "CLOSE"),
            r.stageNames(),
        )
        val res = r.result!!
        assertTrue(res.admissionSent)
        assertEquals(200, res.admissionStatus)
        assertTrue(res.ready)
        assertTrue(res.endCallAcknowledged)
        assertEquals(200, res.repeatStatus)
        assertTrue(res.repeatAcknowledged)
        assertNull(res.failedStage)
        assertEquals("HOLD dura os 2 s, sem áudio", 2_000L, r.stages.single { it.stage == VoiceStage.HOLD }.ms)
        assertEquals("o ACK é medido em bytes", 32L, r.stages.single { it.stage == VoiceStage.END_CALL }.bytes)
        assertEquals("eof", r.stages.last().note)
        assertTrue("a sessão fechou no fim", r.muse.closed)
        assertEquals(listOf("access-SECRET"), r.tokensSeen)
        assertNoSecrets(r)
    }

    /** O registro nunca leva token, id de chamada/sessão/thread nem texto do servidor. */
    private fun assertNoSecrets(r: Rig) {
        val text = (r.stages.map { it.line() } + r.logs).joinToString("\n") +
            VoiceAdmissionDiagnosis.explain(r.result!!, r.stages)
        for (secret in listOf("SECRET", callId, "PRIVATE", "sess-", "thr-", "server text")) {
            assertFalse("vazou '$secret' em:\n$text", text.contains(secret))
        }
    }

    // ---- end-call em todo caminho depois da admissão ------------------------------------------------
    @Test
    fun aRefusedAdmissionStillSendsTheEndCall() {
        val r = Rig()
        r.start()
        r.muse.response(r.mediaId, 403, "denied PRIVATE".toByteArray(), end = true)
        r.settle()
        val admission = r.stages.single { it.stage == VoiceStage.ADMISSION }
        assertFalse(admission.ok)
        assertEquals(403, admission.status)
        assertEquals("o tamanho do erro, nunca o texto", 14L, admission.bytes)
        assertEquals("rejected", admission.note)
        assertEquals("o end-call sai mesmo com a admissão recusada", 1, r.endCalls().size)
        r.ackEndCall(0)
        r.ackEndCall(1, status = 404, body = """{"error":"PRIVATE"}""")
        assertEquals(listOf("PAIRING", "FETCH_VMS", "UPGRADE", "HANDSHAKE", "ADMISSION!", "END_CALL", "END_CALL_REPEAT!", "CLOSE"), r.stageNames())
        val res = r.result!!
        assertEquals(VoiceStage.ADMISSION, res.failedStage)
        assertTrue(res.endCallAcknowledged)
        assertEquals(404, res.repeatStatus)
        assertTrue(VoiceAdmissionDiagnosis.explain(res, r.stages).startsWith("B4 negativo"))
        assertNoSecrets(r)
    }

    @Test
    fun noReadinessIn35SecondsTimesOutAndStillEndsTheCall() {
        val r = Rig()
        r.start()
        r.muse.response(r.mediaId, 200)
        r.send(r.mediaId, zeroKeepalive)
        r.advance(34_999)
        assertTrue(r.endCalls().isEmpty())
        r.advance(1)
        assertEquals("timeout", r.stages.single { it.stage == VoiceStage.READINESS }.note)
        assertEquals(1, r.endCalls().size)
        r.ackEndCall(0)
        r.ackEndCall(1)
        r.send(r.mediaId, end = true)
        assertEquals(VoiceStage.READINESS, r.result!!.failedStage)
        assertTrue(r.result!!.endCallAcknowledged)
    }

    @Test
    fun theStreamEndingBeforeReadinessStillEndsTheCall() {
        val r = Rig()
        r.start()
        r.muse.response(r.mediaId, 200)
        r.send(r.mediaId, zeroKeepalive, end = true)
        assertEquals("ended_before_ready", r.stages.single { it.stage == VoiceStage.READINESS }.note)
        r.ackEndCall(0)
        r.ackEndCall(1)
        assertEquals("o servidor já terminou a mídia: half-close e fecha sem esperar", "eof", r.stages.last().note)
        assertEquals(listOf("READINESS!", "END_CALL", "END_CALL_REPEAT", "CLOSE"), r.stageNames().drop(5))
    }

    @Test
    fun audibleAudioBeforeReadinessIsAProtocolViolationAndStillEndsTheCall() {
        val r = Rig()
        r.start()
        r.muse.response(r.mediaId, 200)
        val loud = Base64.getEncoder().encodeToString(ByteArray(1920) { 7 })
        r.send(r.mediaId, """{"type":"response.output_audio.delta","delta":"$loud"}""")
        assertEquals("audio_before_ready", r.stages.single { it.stage == VoiceStage.READINESS }.note)
        assertEquals(1, r.endCalls().size)
    }

    @Test
    fun aMalformedAckIsNotAcknowledgedAndASilentServerHitsTheTenSecondCleanupCap() {
        val r = Rig()
        r.admitAndBecomeReady()
        r.advance(2_000)
        r.ackEndCall(0, body = """{"ok":true,"result":{"ok":"true"}}""")
        val first = r.stages.single { it.stage == VoiceStage.END_CALL }
        assertFalse(first.ok)
        assertEquals("not_acknowledged", first.note)
        // O repetido nunca responde: o teto de 10 s da limpeza corta, e o fechamento ainda roda (≤1 s).
        assertEquals(2, r.endCalls().size)
        r.advance(10_000)
        val repeat = r.stages.single { it.stage == VoiceStage.END_CALL_REPEAT }
        assertEquals("cleanup_timeout", repeat.note)
        assertTrue("o pedido abandonado é resetado", r.muse.seen.any { it is StreamMuse.Cancelled && it.streamId == r.endCalls()[1].streamId })
        r.advance(1_000)
        assertEquals("reset_after_drain_timeout", r.stages.last().note)
        assertNotNull(r.result)
        assertFalse(r.result!!.endCallAcknowledged)
        assertEquals(VoiceStage.END_CALL, r.result!!.failedStage)
    }

    // ---- sem admissão, sem end-call -----------------------------------------------------------------
    @Test
    fun aRefusedUpgradeNeverSendsAnything() {
        val r = Rig(upgrade = 403)
        r.start()
        assertEquals(listOf("PAIRING", "FETCH_VMS", "UPGRADE!"), r.stageNames())
        assertEquals(403, r.stages.last().status)
        assertTrue(r.muse.sentByClient.isEmpty())
        assertFalse(r.result!!.admissionSent)
        assertTrue(VoiceAdmissionDiagnosis.explain(r.result!!, r.stages).startsWith("403 ambíguo"))
    }

    @Test
    fun aHandshakeThatNeverFinishesTimesOutAt20Seconds() {
        val r = Rig()
        r.probe.start()
        // O Muse não responde a mensagem 1 do handshake.
        r.time.runOne()
        r.muse.outbox.clear()
        r.advance(20_000)
        assertEquals(listOf("PAIRING", "FETCH_VMS", "UPGRADE", "HANDSHAKE!"), r.stageNames())
        assertEquals("timeout", r.stages.last().note)
        assertFalse(r.result!!.admissionSent)
        assertTrue(r.endCalls().isEmpty())
    }

    @Test
    fun notPairedStopsAtTheFirstStage() {
        val r = Rig(loads = listOf(PairingLoad.NotPaired))
        r.start()
        assertEquals(listOf("PAIRING!"), r.stageNames())
        assertTrue(r.tokensSeen.isEmpty())
    }

    @Test
    fun aRejectedTokenRereadsTheStoreOnceAndNeverRefreshes() {
        val rotated = record.copy(accessToken = "rotated-SECRET")
        val r = Rig(
            loads = listOf(PairingLoad.Paired(record), PairingLoad.Paired(rotated)),
            lists = mutableListOf(VmList(emptyList(), 401), VmList(listOf(vm), 200)),
        )
        r.start()
        assertEquals(listOf("access-SECRET", "rotated-SECRET"), r.tokensSeen)
        assertEquals("reread_store", r.stages[1].note.substringAfter(" "))
        assertEquals(VoiceStage.HANDSHAKE, r.stages.last().stage)
        assertEquals("seguiu até abrir a mídia", 1, r.muse.opened().size)

        // Mesmo token depois de reler: para (não fica girando).
        val same = Rig(lists = mutableListOf(VmList(emptyList(), 401)))
        same.start()
        assertEquals(listOf("PAIRING", "FETCH_VMS!"), same.stageNames())
        assertEquals(1, same.tokensSeen.size)
    }

    @Test
    fun theStageLineCarriesOnlyStageStatusSizeAndTime() {
        assertEquals(
            "ADMISSION FAIL http=403 bytes=14 ms=87 rejected",
            VoiceStageEntry(VoiceStage.ADMISSION, false, 403, 14, 87, "rejected").line(),
        )
        assertEquals("HANDSHAKE ok http=- bytes=- ms=5", VoiceStageEntry(VoiceStage.HANDSHAKE, true, null, null, 5, "").line())
    }

    // ---- 5.0.1: nomes dos campos do fetch_vms na nota do FETCH_VMS -------------------------------
    @Test
    fun theFieldsNoteHasAFixedFormat() {
        assertEquals("fields=[a,b_c]", fieldsNote(listOf("a", "b_c")))
        assertEquals("fields=[]", fieldsNote(emptyList()))
        assertEquals("fields=unknown", fieldsNote(null))
        assertEquals("a porta não é de confiança: o nome ruim sai redigido, o +N do teto passa", "fields=[?,+3]", fieldsNote(listOf("bad key", "+3")))
    }

    @Test
    fun theFetchStageCarriesTheChosenVmFieldNames() {
        val other = vm.copy(vmName = "other", isDefault = false)
        val r = Rig(
            lists = mutableListOf(VmList(listOf(other, vm), 200)),
            fieldNames = listOf(listOf("vm_auth_token", "vm_url"), listOf("capabilities", "default", "vm_auth_token", "vm_ws_url")),
        )
        r.start()
        val fetch = r.stages.single { it.stage == VoiceStage.FETCH_VMS }
        assertEquals("a VM escolhida é a default (a 2ª)", "vms=2 not_exactly_one fields=[capabilities,default,vm_auth_token,vm_ws_url] meta=unknown", fetch.note)
        assertEquals("FETCH_VMS ok http=200 bytes=- ms=0 vms=2 not_exactly_one fields=[capabilities,default,vm_auth_token,vm_ws_url] meta=unknown", fetch.line())

        val single = Rig(fieldNames = listOf(listOf("default", "vm_auth_token", "vm_id", "vm_name", "vm_ws_url")))
        single.start()
        assertEquals("vms=1 fields=[default,vm_auth_token,vm_id,vm_name,vm_ws_url] meta=unknown", single.stages[1].note)
    }

    @Test
    fun unknownOrMisalignedFieldNamesSayUnknown() {
        val r = Rig()
        r.start()
        assertEquals("vms=1 fields=unknown meta=unknown", r.stages[1].note)
        val misaligned = Rig(fieldNames = listOf(listOf("a"), listOf("b")))
        misaligned.start()
        assertEquals("vms=1 fields=unknown meta=unknown", misaligned.stages[1].note)
        // O padrão da interface: a mesma lista, nomes desconhecidos.
        val plain = object : MuseApiPort {
            override fun fetchVmList(accessToken: String, apiRoot: String) = VmList(listOf(vm), 200)
            override fun refreshDeviceToken(refreshToken: String, deviceId: String, apiRoot: String, sdkToken: String?) = RefreshResult(null, null)
        }
        assertEquals(VmListWithFields(VmList(listOf(vm), 200), null), plain.fetchVmListWithFields("t", "r"))
    }

    // ---- 5.0.2: valores dos campos de metadado na nota do FETCH_VMS ------------------------------
    @Test
    fun theMetaNoteHasAFixedFormat() {
        assertEquals("meta=[vm_ingress_kind=noise,vm_state=running,vm_type=gadget]", metaNote(mapOf("vm_type" to "gadget", "vm_state" to "running", "vm_ingress_kind" to "noise")))
        assertEquals("meta=[]", metaNote(emptyMap()))
        assertEquals("meta=unknown", metaNote(null))
        assertEquals("a porta não é de confiança: o valor ruim sai redigido", "meta=[vm_type=?]", metaNote(mapOf("vm_type" to "bad value")))
        assertEquals("a porta não é de confiança: chave fora da whitelist some", "meta=[vm_type=gadget]", metaNote(mapOf("vm_name" to "LEAK", "vm_type" to "gadget")))
    }

    @Test
    fun theFetchStageCarriesTheChosenVmMeta() {
        val other = vm.copy(vmName = "other", isDefault = false)
        val r = Rig(
            lists = mutableListOf(VmList(listOf(other, vm), 200)),
            fieldNames = listOf(listOf("vm_auth_token", "vm_url"), listOf("capabilities", "default", "vm_auth_token", "vm_ws_url")),
            meta = listOf(mapOf("vm_type" to "other"), mapOf("vm_ingress_kind" to "noise", "vm_state" to "running", "vm_type" to "gadget")),
        )
        r.start()
        val fetch = r.stages.single { it.stage == VoiceStage.FETCH_VMS }
        assertEquals("a VM escolhida é a default (a 2ª)", "vms=2 not_exactly_one fields=[capabilities,default,vm_auth_token,vm_ws_url] meta=[vm_ingress_kind=noise,vm_state=running,vm_type=gadget]", fetch.note)
    }

    @Test
    fun unknownOrMisalignedMetaSaysUnknown() {
        val misaligned = Rig(meta = listOf(mapOf("vm_type" to "a"), mapOf("vm_type" to "b")))
        misaligned.start()
        assertEquals("vms=1 fields=unknown meta=unknown", misaligned.stages[1].note)
        // O padrão da interface: a mesma lista, nomes e meta desconhecidos.
        val plain = object : MuseApiPort {
            override fun fetchVmList(accessToken: String, apiRoot: String) = VmList(listOf(vm), 200)
            override fun refreshDeviceToken(refreshToken: String, deviceId: String, apiRoot: String, sdkToken: String?) = RefreshResult(null, null)
        }
        assertEquals(VmListWithFields(VmList(listOf(vm), 200), null, null), plain.fetchVmListWithFields("t", "r"))
    }

    @Test
    fun noFetchVmsValueLeaksIntoEntriesResultOrLogs() {
        val body = """
            {"vm_list":[{"vm_ws_url":"wss://LEAKVAL-url","vm_auth_token":"LEAKVAL-token","vm_name":"LEAKVAL-name",
              "vm_id":"LEAKVAL-id","default":true,"capabilities":{"voice":"LEAKVAL-cap"},"region":"LEAKVAL-region","ttl":987654321,
              "vm_type":"gadget","vm_state":"running","vm_ingress_kind":"noise","vm_notary_tokens":["LEAKVAL-notary"]}]}
        """.trimIndent()
        fun leakRig() = Rig(lists = mutableListOf(VmList(MuseApi.parseVmList(body), 200)), fieldNames = MuseApi.parseVmEntryFieldNames(body), meta = MuseApi.parseVmEntryMeta(body))

        val happy = leakRig()
        happy.admitAndBecomeReady()
        happy.advance(2_000)
        happy.ackEndCall(0)
        happy.ackEndCall(1)
        happy.send(happy.mediaId, end = true)

        val refused = leakRig()
        refused.start()
        refused.muse.response(refused.mediaId, 404, ByteArray(0), end = true)
        refused.settle()
        refused.ackEndCall(0)
        refused.ackEndCall(1)

        for (r in listOf(happy, refused)) {
            val res = r.result!!
            assertEquals("vms=1 fields=[capabilities,default,region,ttl,vm_auth_token,vm_id,vm_ingress_kind,vm_name,vm_notary_tokens,vm_state,vm_type,vm_ws_url] meta=[vm_ingress_kind=noise,vm_state=running,vm_type=gadget]", r.stages[1].note)
            val text = (r.stages.map { it.line() } + r.probe.entries().map { it.toString() } + r.logs +
                res.toString() + VoiceAdmissionDiagnosis.explain(res, r.stages)).joinToString("\n")
            for (value in listOf("LEAKVAL", "987654321", "SECRET")) {
                assertFalse("vazou '$value' em:\n$text", text.contains(value))
            }
        }
        assertNull(happy.result!!.failedStage)
        assertEquals(VoiceStage.ADMISSION, refused.result!!.failedStage)
    }
}
