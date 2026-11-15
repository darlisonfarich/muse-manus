package com.wangzan101.mika.gadget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** O retorno do link depois do boot: só religa se o usuário deixou ligado e há pareamento salvo. */
class GadgetLinkResumeTest {

    @Test
    fun resumesOnlyWhenTheUserLeftItOnAndPairingExists() {
        assertTrue(GadgetLink.shouldResumeLink(linkEnabled = true, hasPairing = true))
        assertFalse(GadgetLink.shouldResumeLink(linkEnabled = false, hasPairing = true))
        assertFalse(GadgetLink.shouldResumeLink(linkEnabled = true, hasPairing = false))
        assertFalse(GadgetLink.shouldResumeLink(linkEnabled = false, hasPairing = false))
    }
}
