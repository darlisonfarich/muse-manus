package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.RefreshResult
import com.darlisonfarich.gadgetlink.TokenPair
import com.darlisonfarich.gadgetlink.Vm
import com.darlisonfarich.gadgetlink.VmList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeWorld {
    var now = 0L
    var wall = 1_000_000L
    private var seq = 0
    private class Task(val at: Long, val seq: Int, val run: () -> Unit)
    private val queue = ArrayList<Task>()
    val delays = ArrayList<Long>()

    val scheduler = object : LinkScheduler {
        override fun schedule(delayMs: Long, task: () -> Unit) {
            delays += delayMs
            queue += Task(now + delayMs, seq++, task)
        }
    }
    val clock = object : LinkClock {
        override fun nowMs() = now
        override fun wallSeconds() = wall + now / 1000
    }

    fun pending() = queue.size

    /** Roda o que vence até [ms] à frente, na ordem, inclusive o que for agendado no caminho. */
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = queue.filter { it.at <= target }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            queue.remove(next)
            now = maxOf(now, next.at)
            next.run()
        }
        now = target
    }

    fun runDue() = advance(0)
}

private class FakeStore(var state: PairingLoad) : PairingStore {
    var deletes = 0
    val saved = ArrayList<PairingRecord>()
    override fun load() = state
    override fun save(record: PairingRecord) { saved += record; state = PairingLoad.Paired(record) }
    override fun delete() { deletes++; state = PairingLoad.NotPaired }
}

private class FakeApi : MuseApiPort {
    val fetches = ArrayList<Pair<String, String>>()
    val refreshes = ArrayList<List<String?>>()
    val fetchResults = ArrayDeque<VmList>()
    val refreshResults = ArrayDeque<RefreshResult>()
    var defaultFetch = VmList(emptyList(), 200)
    var defaultRefresh = RefreshResult(TokenPair("new-access", "new-refresh"), 200)

    override fun fetchVmList(accessToken: String, apiRoot: String): VmList {
        fetches += accessToken to apiRoot
        return fetchResults.removeFirstOrNull() ?: defaultFetch
    }

    override fun refreshDeviceToken(refreshToken: String, deviceId: String, apiRoot: String, sdkToken: String?): RefreshResult {
        refreshes += listOf(refreshToken, deviceId, apiRoot, sdkToken)
        return refreshResults.removeFirstOrNull() ?: defaultRefresh
    }
}

private class FakeLauncher : SessionLauncher {
    class Launch(val vm: Vm, val pairing: PairingRecord, val events: SessionLauncher.Events) : SessionLauncher.Handle {
        var stopped = false
        override fun stop() { stopped = true }
    }

    val launches = ArrayList<Launch>()
    var onLaunch: () -> Unit = {}
    override fun launch(vm: Vm, pairing: PairingRecord, events: SessionLauncher.Events): SessionLauncher.Handle {
        val l = Launch(vm, pairing, events)
        launches += l
        onLaunch()
        return l
    }
}

private class Spy : SupervisorListener {
    val states = ArrayList<Pair<SupervisorState, String>>()
    val logs = ArrayList<String>()
    override fun onState(state: SupervisorState, detail: String) { states += state to detail }
    override fun log(line: String) { logs += line }
    fun last() = states.last().first
    fun count(state: SupervisorState) = states.count { it.first == state }
}

class LinkSupervisorTest {
    private val vmA = Vm("wss://a", "tok-a", "Alpha", "id-a", false)
    private val vmB = Vm("wss://b", "tok-b", "Beta", "id-b", true)

    private class Rig(paired: PairingLoad, sdkHint: String? = null) {
        val world = FakeWorld()
        val store = FakeStore(paired)
        val api = FakeApi()
        val launcher = FakeLauncher()
        val spy = Spy()
        val supervisor = LinkSupervisor("homelink-abc123", "mgst_sdk", sdkHint, store, api, launcher, world.scheduler, world.clock, spy)
        fun start() { supervisor.start(); world.runDue() }
        fun last() = launcher.launches.last()
    }

    private fun record(savedAt: Long = 1_000_000L - 100, sdkHint: String? = null, apiUrl: String = "") =
        PairingRecord("old-access", "old-refresh", apiUrl, "noise.example.org", savedAt, sdkHint)

    private fun rig(r: PairingRecord = record(), sdkHint: String? = null) = Rig(PairingLoad.Paired(r), sdkHint).also {
        it.api.defaultFetch = VmList(listOf(vmA, vmB), 200)
    }

    // ---- sem pareamento ----------------------------------------------------------------------------
    @Test
    fun withoutAPairingItPollsEveryThirtySecondsUntilOneAppears() {
        val r = Rig(PairingLoad.NotPaired)
        r.api.defaultFetch = VmList(listOf(vmB), 200)
        r.start()
        assertEquals(SupervisorState.NOT_PAIRED, r.spy.last())
        assertTrue(r.launcher.launches.isEmpty())
        r.world.advance(29_999)
        assertEquals(1, r.spy.count(SupervisorState.NOT_PAIRED))
        r.world.advance(1)
        assertEquals(2, r.spy.count(SupervisorState.NOT_PAIRED))
        r.store.state = PairingLoad.Paired(record(savedAt = r.world.clock.wallSeconds()))
        r.world.advance(30_000)
        assertEquals(1, r.launcher.launches.size)
    }

    @Test
    fun anUnusablePairingIsReportedWithItsProblemAndPolled() {
        val r = Rig(PairingLoad.Invalid("Saved pairing lacks device tokens."))
        r.start()
        assertEquals(SupervisorState.INVALID_PAIRING to "Saved pairing lacks device tokens.", r.spy.states.last())
        assertTrue(r.launcher.launches.isEmpty())
        assertEquals(30_000L, r.world.delays.last())
    }

    // ---- caminho feliz -----------------------------------------------------------------------------
    @Test
    fun itLaunchesTheDefaultVmWithTheCurrentPairingAndAnnouncesTheRegistration() {
        val r = rig(record(apiUrl = "https://api.example.org/"))
        r.start()
        assertEquals("tok-b", r.last().vm.vmAuthToken)
        assertEquals("noise.example.org", r.last().pairing.noiseHost)
        assertEquals("old-access" to "https://api.example.org", r.api.fetches.single())
        assertEquals(SupervisorState.CONNECTING to "Beta", r.spy.states.last())
        r.last().events.onRegistered()
        r.world.runDue()
        assertEquals(SupervisorState.REGISTERED, r.spy.last())
    }

    @Test
    fun withoutADefaultTheFirstVmIsUsedAndTheApiRootFallsBackToMuse() {
        val r = rig()
        r.api.defaultFetch = VmList(listOf(vmA.copy(isDefault = false), vmB.copy(isDefault = false)), 200)
        r.start()
        assertEquals("tok-a", r.last().vm.vmAuthToken)
        assertEquals("https://api.muse.ai", r.api.fetches.single().second)
    }

    // ---- reconexão ---------------------------------------------------------------------------------
    @Test
    fun aHealthySessionReconnectsInOneSecondAndResetsTheBackoff() {
        val r = rig()
        r.start()
        // duas sessões que caem logo: 2 s, depois 4 s
        r.last().events.onEnded(LinkOutcome.CLOSED, 0); r.world.runDue()
        assertEquals(2_000L, r.world.delays.last())
        r.world.advance(2_000)
        r.last().events.onEnded(LinkOutcome.CLOSED, 29_999); r.world.runDue()
        assertEquals(4_000L, r.world.delays.last())
        r.world.advance(4_000)
        // uma sessão saudável (>= 30 s desde o registro): reconecta em 1 s e zera a espera
        r.last().events.onEnded(LinkOutcome.CLOSED, 30_000); r.world.runDue()
        assertEquals(1_000L, r.world.delays.last())
        r.world.advance(1_000)
        r.last().events.onEnded(LinkOutcome.CLOSED, 0); r.world.runDue()
        assertEquals("zerou: volta a 2 s", 2_000L, r.world.delays.last())
        assertEquals(4, r.launcher.launches.size)
    }

    @Test
    fun theBackoffDoublesAndSaturatesAtSixtySeconds() {
        val r = rig()
        r.api.defaultFetch = VmList(emptyList(), 200)
        r.start()
        val seen = ArrayList<Long>()
        repeat(9) {
            seen += r.world.delays.last()
            r.world.advance(r.world.delays.last())
        }
        assertEquals(listOf(2_000L, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000, 60_000), seen)
        assertEquals(SupervisorState.NO_VM, r.spy.last())
    }

    @Test
    fun authRefusalsRaiseTheBackoffFloorToFifteenSeconds() {
        for (outcome in listOf(LinkOutcome.AUTH_REJECTED, LinkOutcome.FORBIDDEN)) {
            val r = rig()
            r.start()
            val delays = ArrayList<Long>()
            repeat(5) {
                r.last().events.onEnded(outcome, 0); r.world.runDue()
                delays += r.world.delays.last()
                r.world.advance(r.world.delays.last())
            }
            assertEquals(outcome.name, listOf(15_000L, 15_000, 15_000, 16_000, 32_000), delays)
            // uma sessão saudável solta o piso
            r.last().events.onEnded(LinkOutcome.CLOSED, 40_000); r.world.runDue()
            r.world.advance(1_000)
            r.last().events.onEnded(LinkOutcome.CLOSED, 0); r.world.runDue()
            assertEquals(outcome.name, 2_000L, r.world.delays.last())
        }
    }

    @Test
    fun aHealthySessionThatWasRefusedStillWaitsTheFloor() {
        val r = rig()
        r.start()
        r.last().events.onEnded(LinkOutcome.AUTH_REJECTED, 60_000); r.world.runDue()
        assertEquals("só CLOSED saudável reconecta em 1 s", 15_000L, r.world.delays.last())
    }

    @Test
    fun theMuseRemovingTheDeviceDeletesThePairingAndBacksToPolling() {
        val r = rig()
        r.start()
        r.last().events.onEnded(LinkOutcome.UNPAIRED, 5_000); r.world.runDue()
        assertEquals(1, r.store.deletes)
        assertEquals(SupervisorState.NOT_PAIRED, r.spy.last())
        assertEquals(30_000L, r.world.delays.last())
        assertEquals(1, r.launcher.launches.size)
    }

    // ---- parada ------------------------------------------------------------------------------------
    @Test
    fun aStoppedOutcomeEndsTheLoopForGood() {
        val r = rig()
        r.start()
        r.last().events.onEnded(LinkOutcome.STOPPED, 1_000); r.world.runDue()
        assertEquals(SupervisorState.STOPPED, r.spy.last())
        assertEquals(0, r.world.pending())
        r.world.advance(600_000)
        assertEquals(1, r.launcher.launches.size)
    }

    @Test
    fun stoppingWhileASessionRunsStopsTheSessionAndNeverReconnects() {
        val r = rig()
        r.start()
        r.supervisor.stop()
        assertTrue(r.last().stopped)
        r.last().events.onEnded(LinkOutcome.STOPPED, 0); r.world.runDue()
        assertEquals(SupervisorState.STOPPED, r.spy.last())
        r.world.advance(600_000)
        assertEquals(1, r.launcher.launches.size)
    }

    @Test
    fun aSessionThatEndsByItselfAfterAStopRequestDoesNotReconnect() {
        val r = rig()
        r.start()
        r.supervisor.stop()
        r.last().events.onEnded(LinkOutcome.CLOSED, 100_000); r.world.runDue()
        assertEquals(SupervisorState.STOPPED, r.spy.last())
        assertEquals(0, r.world.pending())
    }

    @Test
    fun stoppingWhileWaitingAnnouncesStoppedOnceAndLaunchesNothingMore() {
        val r = rig()
        r.start()
        r.last().events.onEnded(LinkOutcome.CLOSED, 0); r.world.runDue()
        r.supervisor.stop()
        r.world.advance(600_000)
        assertEquals(1, r.launcher.launches.size)
        assertEquals(1, r.spy.count(SupervisorState.STOPPED))
    }

    @Test
    fun stoppingBeforeTheLoopStartedLaunchesNothing() {
        val r = rig()
        r.supervisor.start()
        r.supervisor.stop()
        r.world.advance(60_000)
        assertTrue(r.launcher.launches.isEmpty())
        assertEquals(1, r.spy.count(SupervisorState.STOPPED))
    }

    @Test
    fun aStopThatRacesTheLaunchStopsTheSessionItJustCreated() {
        val r = rig()
        r.launcher.onLaunch = { r.supervisor.stop() } // o stop chega com o launch ainda em andamento
        r.start()
        assertTrue("o handle novo também é parado", r.last().stopped)
    }

    @Test
    fun stopNeverBlocksEvenWhileTheNetworkCallIsStillRunning() {
        val r = rig()
        val finished = java.util.concurrent.CountDownLatch(1)
        val inFetch = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocking = object : MuseApiPort {
            override fun fetchVmList(accessToken: String, apiRoot: String): VmList {
                inFetch.countDown()
                release.await()
                return VmList(listOf(vmB), 200)
            }

            override fun refreshDeviceToken(refreshToken: String, deviceId: String, apiRoot: String, sdkToken: String?) =
                RefreshResult(null, 500)
        }
        val s = LinkSupervisor("d", null, null, r.store, blocking, r.launcher, r.world.scheduler, r.world.clock, r.spy)
        s.start()
        val worker = Thread { r.world.runDue(); finished.countDown() }.also { it.start() }
        assertTrue(inFetch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val stopper = Thread { s.stop() }.also { it.start() }
        stopper.join(2_000)
        assertFalse("stop() não pode esperar a chamada de rede", stopper.isAlive)
        release.countDown()
        worker.join(5_000)
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("a sessão criada depois do stop é parada", r.launcher.launches.single().stopped)
    }

    // ---- renovação do token ------------------------------------------------------------------------
    @Test
    fun aTokenOlderThanThreeHoursIsRotatedBeforeTheVmsAreFetched() {
        val r = rig(record(savedAt = 1_000_000L - 3 * 3600))
        r.start()
        assertEquals(listOf<String?>("old-refresh", "homelink-abc123", "https://api.muse.ai", "mgst_sdk"), r.api.refreshes.single())
        assertEquals("new-access", r.api.fetches.single().first)
        val saved = r.store.saved.single()
        assertEquals(PairingRecord("new-access", "new-refresh", "", "noise.example.org", 1_000_000L, null), saved)
        assertEquals("new-access", r.last().pairing.accessToken)
    }

    @Test
    fun aTokenJustInsideTheThreeHourWindowIsNotRotated() {
        val r = rig(record(savedAt = 1_000_000L - 3 * 3600 + 1))
        r.start()
        assertTrue(r.api.refreshes.isEmpty())
        assertEquals("old-access", r.api.fetches.single().first)
    }

    @Test
    fun aPassingRefreshFailureKeepsTheOldTokenAndWaitsFiveMinutesBeforeTryingAgain() {
        val r = rig(record(savedAt = 1_000_000L - 4 * 3600))
        r.api.defaultRefresh = RefreshResult(null, 500)
        r.api.defaultFetch = VmList(emptyList(), 200) // sem VM: o laço volta a rodar com espera crescente
        r.start()
        assertEquals(1, r.api.refreshes.size)
        assertEquals("segue com o token velho", "old-access", r.api.fetches.single().first)
        r.world.advance(299_000)
        assertEquals("dentro dos 5 min não tenta de novo", 1, r.api.refreshes.size)
        // os passos caem em 2, 6, 14, 30, 62, 122, 242 e 302 s: o de 302 s já passa dos 5 min
        r.world.advance(4_000)
        assertEquals(2, r.api.refreshes.size)
    }

    @Test
    fun aRevokedPairingOnRefreshDeletesItAndWaitsFiveMinutes() {
        val r = rig(record(savedAt = 1_000_000L - 4 * 3600))
        r.api.defaultRefresh = RefreshResult(null, 401)
        r.start()
        assertEquals(1, r.store.deletes)
        assertTrue(r.launcher.launches.isEmpty())
        assertTrue(r.api.fetches.isEmpty())
        assertEquals(300_000L, r.world.delays.last())
        r.world.advance(300_000)
        assertEquals(SupervisorState.NOT_PAIRED, r.spy.last())
    }

    // ---- 401 na lista de VMs -----------------------------------------------------------------------
    @Test
    fun aRejectedTokenIsRefreshedAndRetriedAtOnceWithTheNewOne() {
        val r = rig()
        r.api.fetchResults += VmList(emptyList(), 401)
        r.start()
        assertEquals(1, r.api.refreshes.size)
        assertEquals(listOf("old-access", "new-access"), r.api.fetches.map { it.first })
        assertEquals("tok-b", r.last().vm.vmAuthToken)
        assertEquals("a nova tentativa não esperou", 0L, r.world.now)
        assertEquals(1, r.launcher.launches.size)
    }

    @Test
    fun aSecondRejectionRightAfterARefreshWaitsAtLeastFifteenSeconds() {
        val r = rig()
        r.api.fetchResults += VmList(emptyList(), 401)
        r.api.fetchResults += VmList(emptyList(), 401)
        r.start()
        assertEquals(2, r.api.refreshes.size)
        assertEquals(15_000L, r.world.delays.last())
        assertTrue(r.launcher.launches.isEmpty())
        r.world.advance(14_999)
        assertEquals(2, r.api.fetches.size)
        r.world.advance(1)
        assertEquals(1, r.launcher.launches.size)
    }

    @Test
    fun theRejectionCounterResetsAfterASuccessfulFetch() {
        val r = rig()
        r.api.fetchResults += VmList(emptyList(), 401)
        r.start()
        r.last().events.onEnded(LinkOutcome.CLOSED, 40_000); r.world.runDue()
        r.api.fetchResults += VmList(emptyList(), 401)
        r.world.advance(1_000)
        assertEquals("o 401 novo é tratado como o primeiro: volta na hora", 2, r.launcher.launches.size)
    }

    @Test
    fun aRejectedTokenWhoseRefreshAlsoFailsWaitsFiveMinutes() {
        val r = rig()
        r.api.fetchResults += VmList(emptyList(), 401)
        r.api.refreshResults += RefreshResult(null, 500)
        r.start()
        assertTrue(r.launcher.launches.isEmpty())
        assertEquals(300_000L, r.world.delays.last())
    }

    @Test
    fun aNetworkFailureOnTheVmListIsTreatedAsNoVmAndRetriedWithBackoff() {
        val r = rig()
        r.api.defaultFetch = VmList(emptyList(), null)
        r.start()
        assertEquals(SupervisorState.NO_VM, r.spy.last())
        assertEquals(2_000L, r.world.delays.last())
        assertTrue(r.api.refreshes.isEmpty())
    }

    // ---- diversos ----------------------------------------------------------------------------------
    @Test
    fun aPairingMadeWithAnotherSdkTokenWarnsOnlyOnce() {
        val r = rig(record(sdkHint = "mgst_old…"), sdkHint = "mgst_new…")
        r.start()
        r.last().events.onEnded(LinkOutcome.CLOSED, 0); r.world.runDue()
        r.world.advance(2_000)
        assertEquals(1, r.spy.logs.count { it.contains("paired with SDK token") })
        assertTrue(r.spy.logs.none { it.contains("mgst_sdk") })

        val same = rig(record(sdkHint = "mgst_new…"), sdkHint = "mgst_new…")
        same.start()
        assertTrue(same.spy.logs.none { it.contains("SDK token") })
        val none = rig(record(sdkHint = null), sdkHint = "mgst_new…")
        none.start()
        assertTrue(none.spy.logs.none { it.contains("SDK token") })
    }

    @Test
    fun registeringAfterAStopIsNotAnnounced() {
        val r = rig()
        r.start()
        r.supervisor.stop()
        r.last().events.onRegistered(); r.world.runDue()
        assertNull(r.spy.states.firstOrNull { it.first == SupervisorState.REGISTERED })
    }

    @Test
    fun startingTwiceStartsOneLoop() {
        val r = rig()
        r.supervisor.start()
        r.supervisor.start()
        r.world.runDue()
        assertEquals(1, r.launcher.launches.size)
    }
}
