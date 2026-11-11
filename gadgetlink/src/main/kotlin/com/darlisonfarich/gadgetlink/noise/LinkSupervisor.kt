// Supervisor do link (passo 4): o laço que mantém o celular registrado no Muse. Port do laço de
// Service.run e de _maybe_refresh em musegadget/service.py do facebookincubator/muse-gadget-sdk
// (Copyright (c) Meta Platforms, Inc. and affiliates; Apache License 2.0). Mudanças: Kotlin; em vez
// de asyncio, passos agendados num único executor (agendador, relógio, armazenamento, API e
// lançador de sessão são injetados, então cada decisão é testável sem rede nem relógio real); a
// sessão roda fora daqui (o lançador abre o WebSocket) e avisa o desfecho por callback.
package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.MuseApi
import com.darlisonfarich.gadgetlink.RefreshResult
import com.darlisonfarich.gadgetlink.Vm
import com.darlisonfarich.gadgetlink.VmList

/** O que o supervisor lê do pairing.json. Tempos em segundos de relógio de parede, como o SDK. */
data class PairingRecord(
    val accessToken: String,
    val refreshToken: String,
    val apiUrlV2: String,
    val noiseHost: String,
    val accessTokenSavedAt: Long,
    val sdkTokenHint: String?,
)

sealed interface PairingLoad {
    data object NotPaired : PairingLoad
    class Invalid(val problem: String) : PairingLoad
    class Paired(val record: PairingRecord) : PairingLoad
}

interface PairingStore {
    fun load(): PairingLoad
    fun save(record: PairingRecord)
    fun delete()
}

/** A API do Muse; a implementação real chama [MuseApi]. */
interface MuseApiPort {
    fun fetchVmList(accessToken: String, apiRoot: String): VmList
    fun refreshDeviceToken(refreshToken: String, deviceId: String, apiRoot: String, sdkToken: String?): RefreshResult
}

class RealMuseApi : MuseApiPort {
    override fun fetchVmList(accessToken: String, apiRoot: String) = MuseApi.fetchVmList(accessToken, apiRoot)
    override fun refreshDeviceToken(refreshToken: String, deviceId: String, apiRoot: String, sdkToken: String?) =
        MuseApi.refreshDeviceToken(refreshToken, deviceId, apiRoot, sdkToken)
}

/** Passos agendados sempre na MESMA thread, um por vez. */
interface LinkScheduler {
    fun schedule(delayMs: Long, task: () -> Unit)
}

interface LinkClock {
    /** Relógio monotônico, em ms. */
    fun nowMs(): Long

    /** Relógio de parede, em segundos (idade do token). */
    fun wallSeconds(): Long
}

/** Quem abre a conexão de verdade (OkHttp) e roda a [LinkSession]. */
interface SessionLauncher {
    interface Handle {
        fun stop()
    }

    interface Events {
        fun onRegistered()

        /** @param lastedMs quanto durou DESDE o registro (0 se nunca registrou). */
        fun onEnded(outcome: LinkOutcome, lastedMs: Long)
    }

    fun launch(vm: Vm, pairing: PairingRecord, events: Events): Handle
}

enum class SupervisorState { IDLE, NOT_PAIRED, INVALID_PAIRING, REFRESHING_TOKEN, FETCHING_VMS, NO_VM, CONNECTING, REGISTERED, WAITING_TO_RECONNECT, STOPPED }

interface SupervisorListener {
    fun onState(state: SupervisorState, detail: String = "") {}
    fun log(line: String) {}
}

class LinkSupervisor(
    private val deviceId: String,
    private val sdkToken: String?,
    /** Dica do token do SDK atual (config.sdk_token_hint), para avisar se o pareamento usou outro. */
    private val sdkTokenHint: String?,
    private val store: PairingStore,
    private val api: MuseApiPort,
    private val launcher: SessionLauncher,
    private val scheduler: LinkScheduler,
    private val clock: LinkClock,
    private val listener: SupervisorListener = object : SupervisorListener {},
) {
    companion object {
        const val BACKOFF_BASE_MS = 2_000L
        const val BACKOFF_MAX_MS = 60_000L
        const val AUTH_BACKOFF_MIN_MS = 15_000L
        const val HEALTHY_SESSION_MS = 30_000L
        const val HEALTHY_RECONNECT_MS = 1_000L
        const val UNPAIRED_POLL_MS = 30_000L

        /** O token do device vive ~4 h; gira com 3 h. */
        const val TOKEN_REFRESH_AGE_S = 3 * 3600L
        const val TOKEN_RETRY_MS = 300_000L
    }

    /** Espera crescente: 2 s, 4 s, ... até 60 s; o piso sobe depois de recusa de auth. */
    private class Backoff {
        var failures = 0
        var floorMs = 0L

        fun nextDelay(): Long {
            // Expoente limitado a 16: o resultado já satura em 60 s bem antes.
            val delay = minOf(BACKOFF_BASE_MS * (1L shl minOf(failures, 16)), BACKOFF_MAX_MS)
            failures++
            return maxOf(delay, floorMs)
        }

        fun reset() {
            failures = 0
            floorMs = 0
        }
    }

    // Tudo abaixo, menos [stopped] e [current], só é tocado pela thread do agendador: os passos, as
    // chamadas de rede e os callbacks da sessão (que voltam por scheduler.schedule) rodam nela. Por isso
    // nenhum método daqui é sincronizado: um stop() vindo da thread principal NÃO pode esperar uma
    // chamada de rede de 20 s.
    private val backoff = Backoff()
    private var started = false
    private var stoppedAnnounced = false
    private var tokenRejected = false
    private var lastRefreshAttemptMs: Long? = null
    private var sdkTokenChangeWarned = false

    @Volatile private var stopped = false
    @Volatile private var current: SessionLauncher.Handle? = null

    /** Começa o laço. Os passos seguintes se agendam sozinhos. */
    fun start() {
        scheduler.schedule(0) {
            if (started || stopped) return@schedule
            started = true
            listener.onState(SupervisorState.IDLE)
            step()
        }
    }

    /**
     * Para o laço e a sessão em andamento (o desfecho STOPPED chega pelo callback da sessão). Pode ser
     * chamado de qualquer thread e nunca bloqueia.
     */
    fun stop() {
        stopped = true
        val handle = current
        if (handle != null) handle.stop() else scheduler.schedule(0) { announceStopped() }
    }

    private fun announceStopped() {
        if (stoppedAnnounced) return
        stoppedAnnounced = true
        listener.onState(SupervisorState.STOPPED)
    }

    private fun later(delayMs: Long, state: SupervisorState = SupervisorState.WAITING_TO_RECONNECT, detail: String = "") {
        listener.onState(state, detail.ifEmpty { "${delayMs / 1000} s" })
        scheduler.schedule(delayMs) { step() }
    }

    private fun step() {
        if (stopped) {
            announceStopped()
            return
        }
        val pairing = when (val loaded = store.load()) {
            PairingLoad.NotPaired -> {
                listener.log("not paired")
                later(UNPAIRED_POLL_MS, SupervisorState.NOT_PAIRED)
                return
            }
            is PairingLoad.Invalid -> {
                listener.log("saved pairing is unusable: ${loaded.problem}")
                later(UNPAIRED_POLL_MS, SupervisorState.INVALID_PAIRING, loaded.problem)
                return
            }
            is PairingLoad.Paired -> loaded.record
        }
        warnIfSdkTokenChanged(pairing)
        val fresh = maybeRefresh(pairing, force = false)
        if (fresh == null) {
            later(TOKEN_RETRY_MS)
            return
        }

        listener.onState(SupervisorState.FETCHING_VMS)
        val root = MuseApi.apiRoot(fresh.apiUrlV2)
        val list = api.fetchVmList(fresh.accessToken, root)
        if (list.status == 401) {
            listener.log("device token rejected by the API; refreshing")
            if (maybeRefresh(fresh, force = true) == null) {
                later(TOKEN_RETRY_MS)
            } else if (tokenRejected) {
                // Uma nova tentativa imediata com o primeiro token renovado; recusa repetida não pode
                // ficar girando renovações bem-sucedidas.
                backoff.floorMs = AUTH_BACKOFF_MIN_MS
                later(backoff.nextDelay())
            } else {
                scheduler.schedule(0) { step() }
            }
            tokenRejected = true
            return
        }
        tokenRejected = false
        val vm = list.vms.firstOrNull { it.isDefault } ?: list.vms.firstOrNull()
        if (vm == null) {
            listener.log("the account has no usable VM")
            later(backoff.nextDelay(), SupervisorState.NO_VM)
            return
        }

        listener.onState(SupervisorState.CONNECTING, vm.vmName.ifEmpty { vm.vmId })
        val handle = launcher.launch(vm, fresh, object : SessionLauncher.Events {
            override fun onRegistered() {
                scheduler.schedule(0) { registered() }
            }

            override fun onEnded(outcome: LinkOutcome, lastedMs: Long) {
                scheduler.schedule(0) { ended(outcome, lastedMs) }
            }
        })
        current = handle
        // Um stop() que chegou entre o launch e esta linha não viu o handle: quem para a sessão é este teste.
        if (stopped) handle.stop()
    }

    private fun registered() {
        if (!stopped) listener.onState(SupervisorState.REGISTERED)
    }

    private fun ended(outcome: LinkOutcome, lastedMs: Long) {
        current = null
        listener.log("session ended: ${outcome.name.lowercase()} after ${lastedMs / 1000} s")
        if (outcome == LinkOutcome.STOPPED || stopped) {
            announceStopped()
            return
        }
        if (outcome == LinkOutcome.UNPAIRED) {
            store.delete()
            listener.log("pairing removed; pair again to set up")
            scheduler.schedule(0) { step() }
            return
        }
        val healthy = lastedMs >= HEALTHY_SESSION_MS
        if (healthy) backoff.reset()
        if (outcome == LinkOutcome.AUTH_REJECTED || outcome == LinkOutcome.FORBIDDEN) backoff.floorMs = AUTH_BACKOFF_MIN_MS
        val delay = if (healthy && outcome == LinkOutcome.CLOSED) HEALTHY_RECONNECT_MS else backoff.nextDelay()
        later(delay)
    }

    private fun warnIfSdkTokenChanged(pairing: PairingRecord) {
        val paired = pairing.sdkTokenHint
        if (sdkTokenChangeWarned || paired == null) return
        if (paired != sdkTokenHint) {
            sdkTokenChangeWarned = true
            listener.log("paired with SDK token $paired, not the current one; pair again to use it")
        }
    }

    /**
     * O pareamento atual, girando os tokens antes se estiver na hora. Devolve null só quando uma
     * renovação devida falhou e o token velho não deve ser usado agora; o pareamento é apagado quando
     * ele mesmo foi revogado (401).
     */
    private fun maybeRefresh(pairing: PairingRecord, force: Boolean): PairingRecord? {
        if (!force && pairing.accessTokenSavedAt > clock.wallSeconds() - TOKEN_REFRESH_AGE_S) return pairing
        val last = lastRefreshAttemptMs
        if (!force && last != null && clock.nowMs() - last < TOKEN_RETRY_MS) return pairing
        lastRefreshAttemptMs = clock.nowMs()
        listener.onState(SupervisorState.REFRESHING_TOKEN)
        val result = api.refreshDeviceToken(pairing.refreshToken, deviceId, MuseApi.apiRoot(pairing.apiUrlV2), sdkToken)
        val tokens = result.tokens
        if (tokens != null) {
            val updated = pairing.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accessTokenSavedAt = clock.wallSeconds(),
            )
            store.save(updated)
            listener.log("device token rotated")
            return updated
        }
        if (result.status == 401) {
            store.delete()
            listener.log("pairing revoked; pair again to set up")
            return null
        }
        // Falha passageira: segue com o token atual enquanto ele ainda funcionar.
        return if (force) null else pairing
    }
}
