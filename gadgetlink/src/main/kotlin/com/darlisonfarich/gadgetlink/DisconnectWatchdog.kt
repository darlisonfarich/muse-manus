package com.darlisonfarich.gadgetlink

/**
 * Faz valer o `transport.disconnect(delay)` do setup. O SDK (BlueZ) derruba o
 * cliente na hora; no Android o `cancelConnection` do servidor GATT pode não derrubar
 * um central externo (visto no POCO X8 Pro, Android 16/HyperOS: o cliente seguiu
 * conectado depois de um DECRYPT_FAILED até ir embora sozinho).
 *
 * Então: depois do atraso, pede a queda educada ([Link.cancel]) e registra; se o
 * MESMO cliente, na MESMA conexão, ainda estiver lá [graceMs] depois, força
 * ([Link.forceOff]: no Android, fechar o servidor GATT e reabrir). Um cliente que
 * caiu e voltou, ou outro que chegou nesse meio-tempo, não é derrubado.
 *
 * Nada aqui lança: falhas viram linha de log, porque isto roda em thread de agenda.
 */
class DisconnectWatchdog<C : Any>(
    private val link: Link<C>,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val log: (String) -> Unit,
    private val graceMs: Long = DEFAULT_GRACE_MS,
) {
    companion object {
        /** Com o atraso de 0,3 s do setup, a queda forçada sai em ~0,9 s do erro. */
        const val DEFAULT_GRACE_MS = 600L
    }

    interface Link<C> {
        /** O cliente conectado agora, ou null. */
        fun current(): C?
        fun describe(client: C): String
        /** Queda educada (pode não ter efeito). */
        fun cancel(client: C)
        /** Queda garantida; deve também limpar a sessão como numa desconexão. */
        fun forceOff(client: C)
    }

    private val lock = Any()
    private var epoch = 0L

    /** Chamar a cada conexão ou desconexão observada no transporte. */
    fun connectionChanged() {
        synchronized(lock) { epoch++ }
    }
    /** Pede a queda do cliente atual daqui a [delayMs]. */
    fun request(delayMs: Long) {
        val (requestedEpoch, requestedClient) = synchronized(lock) { epoch to link.current() }
        log("disconnect scheduled in $delayMs ms")
        schedule(delayMs) { fire(requestedEpoch, requestedClient) }
    }

    private fun fire(requestedEpoch: Long, requestedClient: C?) {
        val client = synchronized(lock) {
            val now = link.current()
            when {
                now == null -> null.also { log("disconnect requested but no client is connected") }
                epoch != requestedEpoch || now != requestedClient ->
                    null.also { log("disconnect skipped: ${link.describe(now)} connected after the request") }
                else -> now
            }
        } ?: return
        val label = link.describe(client)
        log("disconnect requested $label")
        runCatching { link.cancel(client) }.fold(
            onSuccess = { log("cancelConnection sent to $label") },
            onFailure = { log("cancelConnection failed for $label: ${it.javaClass.simpleName}") },
        )
        val cancelEpoch = synchronized(lock) { epoch }
        schedule(graceMs) { check(cancelEpoch, client, label) }
    }

    private fun check(cancelEpoch: Long, client: C, label: String) {
        val stillThere = synchronized(lock) { epoch == cancelEpoch && link.current() == client }
        if (!stillThere) {
            log("disconnect confirmed $label")
            return
        }
        log("$label still connected $graceMs ms after cancelConnection; forcing it off")
        runCatching { link.forceOff(client) }.fold(
            onSuccess = {
                // A conexão conhecida acabou: qualquer outra checagem agendada para
                // esta época (ex.: dois request() seguidos) não deve forçar de novo —
                // no transporte real o forceOff fecha o servidor e o callback de
                // desconexão faria este papel, mas o watchdog não depende disso.
                connectionChanged()
            },
            onFailure = { log("forced disconnect failed for $label: ${it.javaClass.simpleName}") },
        )
    }
}
