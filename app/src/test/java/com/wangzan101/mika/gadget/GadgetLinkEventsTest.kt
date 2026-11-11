package com.wangzan101.mika.gadget

import com.darlisonfarich.gadgetlink.SetupEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** O estado do gadget-link anda com os eventos do protocolo; erro de uma tentativa velha não gruda num pareamento que deu certo. */
class GadgetLinkEventsTest {
    @After
    fun reset() {
        GadgetLink.setPhase(GadgetLink.Phase.STARTING) // zera lastError
        GadgetLink.setPhase(GadgetLink.Phase.OFF)
    }

    @Test
    fun aFailedAttemptIsReportedAsLastError() {
        GadgetLink.setPhase(GadgetLink.Phase.STARTING)
        GadgetLink.onSetupEvent(SetupEvent.DECRYPT_FAILED, "bad tag")
        assertEquals("DECRYPT_FAILED bad tag", GadgetLink.lastError)
    }

    @Test
    fun aSuccessfulProvisioningClearsTheOldError() {
        GadgetLink.setPhase(GadgetLink.Phase.STARTING)
        GadgetLink.onSetupEvent(SetupEvent.DECRYPT_FAILED, "bad tag")
        GadgetLink.onSetupEvent(SetupEvent.PROVISIONED, "")
        assertEquals(GadgetLink.Phase.PAIRED, GadgetLink.phase.value)
        assertNull(GadgetLink.lastError)
    }
}
