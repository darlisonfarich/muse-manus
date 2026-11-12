package com.wangzan101.mika.gadget

import com.darlisonfarich.gadgetlink.noise.SupervisorState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A conexão com o Muse (passo 4) como a Diagnostics a mostra: estado, rótulo e se está de pé. */
class GadgetLinkMuseStateTest {
    @After
    fun reset() = GadgetLink.onLinkState(SupervisorState.STOPPED, "")

    @Test
    fun theLabelsSayWhatTheLinkIsDoing() {
        assertEquals("off", GadgetLink.linkLabel(null, ""))
        assertEquals("starting", GadgetLink.linkLabel(SupervisorState.IDLE, ""))
        assertEquals("not paired yet (pair first)", GadgetLink.linkLabel(SupervisorState.NOT_PAIRED, "30 s"))
        assertEquals("saved pairing is unusable: Saved pairing lacks device tokens.", GadgetLink.linkLabel(SupervisorState.INVALID_PAIRING, "Saved pairing lacks device tokens."))
        assertEquals("refreshing the device token", GadgetLink.linkLabel(SupervisorState.REFRESHING_TOKEN, ""))
        assertEquals("looking up your Muse", GadgetLink.linkLabel(SupervisorState.FETCHING_VMS, ""))
        assertEquals("no usable Muse on the account, retrying in 4 s", GadgetLink.linkLabel(SupervisorState.NO_VM, "4 s"))
        assertEquals("connecting to Alpha", GadgetLink.linkLabel(SupervisorState.CONNECTING, "Alpha"))
        assertEquals("registered with the Muse", GadgetLink.linkLabel(SupervisorState.REGISTERED, ""))
        assertEquals("reconnecting in 8 s", GadgetLink.linkLabel(SupervisorState.WAITING_TO_RECONNECT, "8 s"))
        assertEquals("off", GadgetLink.linkLabel(SupervisorState.STOPPED, ""))
    }

    @Test
    fun theLinkIsRunningFromTheFirstStateUntilItStops() {
        assertFalse(GadgetLink.linkRunning)
        GadgetLink.onLinkState(SupervisorState.IDLE, "")
        assertTrue(GadgetLink.linkRunning)
        GadgetLink.onLinkState(SupervisorState.REGISTERED, "")
        assertTrue(GadgetLink.linkRunning)
        assertEquals(SupervisorState.REGISTERED, GadgetLink.link.value)
        GadgetLink.onLinkState(SupervisorState.CONNECTING, "Alpha")
        GadgetLink.onLinkState(SupervisorState.STOPPED, "Alpha") // mesmo com um detalhe velho, parar zera
        assertFalse(GadgetLink.linkRunning)
        assertNull("parado = desligado, sem estado", GadgetLink.link.value)
        assertEquals("parado não guarda o detalhe da conexão anterior", "", GadgetLink.linkDetail)
    }

    @Test
    fun theDetailFollowsTheStateAndTheEventLogRecordsEachChange() {
        GadgetLink.onLinkState(SupervisorState.CONNECTING, "Alpha")
        assertEquals("connecting to Alpha", GadgetLink.linkLabel(GadgetLink.link.value, GadgetLink.linkDetail))
        GadgetLink.onLinkState(SupervisorState.REGISTERED, "")
        assertEquals("registered with the Muse", GadgetLink.linkLabel(GadgetLink.link.value, GadgetLink.linkDetail))
    }
}
