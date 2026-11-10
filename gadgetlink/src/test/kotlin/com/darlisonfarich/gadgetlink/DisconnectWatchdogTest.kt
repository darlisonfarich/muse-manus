package com.darlisonfarich.gadgetlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A queda pedida pelo setup (erro de decifragem, falha de provisionamento) tem que
 * acontecer de verdade: no POCO (Android 16/HyperOS) o cancelConnection do servidor
 * GATT não derrubou um central externo, e o cliente ficou conectado até ir embora sozinho.
 */
class DisconnectWatchdogTest {
    private class FakeLink(var client: String?) : DisconnectWatchdog.Link<String> {
        val cancelled = mutableListOf<String>()
        val forced = mutableListOf<String>()
        var cancelThrows: Exception? = null
        var forceThrows: Exception? = null

        override fun current(): String? = client
        override fun describe(client: String) = client
        override fun cancel(client: String) {
            cancelled += client
            cancelThrows?.let { throw it }
        }
        override fun forceOff(client: String) {
            forced += client
            forceThrows?.let { throw it }
        }
    }

    /** Agenda manual: cada tarefa só roda quando o teste manda. */
    private class ManualScheduler {
        val pending = ArrayDeque<Pair<Long, () -> Unit>>()
        fun schedule(delayMs: Long, task: () -> Unit) { pending.addLast(delayMs to task) }
        fun runNext(): Long {
            val (delay, task) = pending.removeFirst()
            task()
            return delay
        }
    }

    private val logs = mutableListOf<String>()
    private val scheduler = ManualScheduler()

    private fun watchdog(link: FakeLink, graceMs: Long = 600) =
        DisconnectWatchdog(link, scheduler::schedule, { logs += it }, graceMs)

    @Test
    fun cancelsTheClientAfterTheRequestedDelayAndLogsIt() {
        val link = FakeLink("AA:BB")
        watchdog(link).request(300)

        assertEquals(listOf<String>(), link.cancelled) // nada antes do atraso
        assertTrue(logs.toString(), logs.any { it.contains("disconnect scheduled in 300 ms") })
        assertEquals(300L, scheduler.runNext())
        assertEquals(listOf("AA:BB"), link.cancelled)
        assertTrue(logs.toString(), logs.any { it.contains("disconnect requested") && it.contains("AA:BB") })
        assertTrue(logs.toString(), logs.any { it.contains("cancelConnection") })
    }

    @Test
    fun forcesTheClientOffWhenItIsStillConnectedAfterTheGrace() {
        val link = FakeLink("AA:BB")
        watchdog(link, graceMs = 600).request(300)
        scheduler.runNext()

        assertEquals(600L, scheduler.runNext())
        assertEquals(listOf("AA:BB"), link.forced)
        assertTrue(logs.toString(), logs.any { it.contains("still connected") && it.contains("600 ms") })
    }

    @Test
    fun doesNotForceWhenTheClientLeftWithinTheGrace() {
        val link = FakeLink("AA:BB")
        val dog = watchdog(link)
        dog.request(300)
        scheduler.runNext()
        link.client = null
        dog.connectionChanged()

        scheduler.runNext()
        assertEquals(listOf<String>(), link.forced)
        assertTrue(logs.toString(), logs.any { it.contains("disconnect confirmed") })
    }

    @Test
    fun doesNotForceTheSameClientWhenItReconnectedWithinTheGrace() {
        val link = FakeLink("AA:BB")
        val dog = watchdog(link)
        dog.request(300)
        scheduler.runNext()
        dog.connectionChanged() // caiu
        dog.connectionChanged() // e voltou: conexão nova, sessão nova

        scheduler.runNext()
        assertEquals(listOf<String>(), link.forced)
    }

    @Test
    fun skipsWhenThereIsNoClientWhenTheDelayEnds() {
        val link = FakeLink("AA:BB")
        val dog = watchdog(link)
        dog.request(300)
        link.client = null
        dog.connectionChanged()

        scheduler.runNext()
        assertEquals(listOf<String>(), link.cancelled)
        assertTrue("no grace check without a cancel", scheduler.pending.isEmpty())
    }

    @Test
    fun doesNotKickAClientThatConnectedAfterTheRequest() {
        val link = FakeLink("AA:BB")
        val dog = watchdog(link)
        dog.request(300)
        link.client = "CC:DD"
        dog.connectionChanged()
        dog.connectionChanged()

        scheduler.runNext()
        assertEquals(listOf<String>(), link.cancelled)
        assertEquals(listOf<String>(), link.forced)
        assertTrue(logs.toString(), logs.any { it.contains("skipped") })
    }

    @Test
    fun aFailingCancelIsLoggedAndStillEscalates() {
        val link = FakeLink("AA:BB").apply { cancelThrows = SecurityException("no connect permission") }
        watchdog(link).request(300)
        scheduler.runNext()
        assertTrue(logs.toString(), logs.any { it.contains("cancelConnection failed") && it.contains("SecurityException") })

        scheduler.runNext()
        assertEquals(listOf("AA:BB"), link.forced)
    }

    @Test
    fun aFailingForceIsLoggedNotThrown() {
        val link = FakeLink("AA:BB").apply { forceThrows = IllegalStateException("adapter off") }
        watchdog(link).request(300)
        scheduler.runNext()
        scheduler.runNext()
        assertTrue(logs.toString(), logs.any { it.contains("forced disconnect failed") && it.contains("IllegalStateException") })
    }

    @Test
    fun theWorstCaseFitsInAboutOneSecondForTheSetupDelay() {
        val link = FakeLink("AA:BB")
        watchdog(link, graceMs = DisconnectWatchdog.DEFAULT_GRACE_MS).request(300)
        val total = scheduler.runNext() + scheduler.runNext()
        assertTrue("forced off at $total ms", total <= 1_000)
        assertEquals(listOf("AA:BB"), link.forced)
    }

    @Test
    fun twoRequestsBeforeTheFirstFiresForceOnlyOnce() {
        // Plausível na integração: erro de decifragem agenda 0,3 s e uma falha de
        // provisionamento agenda 0,5 s sobre a mesma conexão. Os dois disparos
        // cancelam (inofensivo), mas a queda forçada é uma só — a segunda
        // checagem encontra a época trocada pela primeira força.
        val link = FakeLink("AA:BB")
        val dog = watchdog(link)
        dog.request(300)
        dog.request(500)

        assertEquals(300L, scheduler.runNext()) // dispara o 1º: cancela e agenda a checagem
        assertEquals(500L, scheduler.runNext()) // dispara o 2º: cancela de novo e agenda outra
        assertEquals(600L, scheduler.runNext()) // 1ª checagem: ainda lá → força (e vira a época)
        assertEquals(600L, scheduler.runNext()) // 2ª checagem: época trocada → não força de novo
        assertEquals(listOf("AA:BB", "AA:BB"), link.cancelled)
        assertEquals(listOf("AA:BB"), link.forced)
    }
}
