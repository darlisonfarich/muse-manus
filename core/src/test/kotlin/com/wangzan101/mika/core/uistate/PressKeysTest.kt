package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressKeysTest {
    @Test fun scopedVolumeReadbackAcceptsAKeyAtItsLimit() {
        assertTrue(PressKeys.volumeVerified(24, PressKeys.VolumeState(15, 15, false), PressKeys.VolumeState(15, 15, false)))
        assertTrue(PressKeys.volumeVerified(25, PressKeys.VolumeState(0, 15, false), PressKeys.VolumeState(0, 15, false)))
        assertTrue(PressKeys.volumeVerified(164, PressKeys.VolumeState(0, 15, true), PressKeys.VolumeState(0, 15, true)))
    }

    @Test fun scopedVolumeAcceptsWhenReadbackIsUnavailable() {
        assertTrue(PressKeys.volumeVerified(24, null, null))
    }

    @Test fun availableVolumeReadbackMustShowExpectedDirection() {
        assertFalse(PressKeys.volumeVerified(24, PressKeys.VolumeState(4, 15, false), PressKeys.VolumeState(3, 15, false)))
        assertFalse(PressKeys.volumeVerified(25, PressKeys.VolumeState(4, 15, false), PressKeys.VolumeState(5, 15, false)))
    }

    @Test fun anyDisplayKeysRemainSeparatelyScoped() {
        assertTrue(PressKeys.resolve("KEYCODE_ENTER", PressKeys.Scope.VirtualDisplay) == 66)
        assertTrue(PressKeys.resolve("KEYCODE_HOME", PressKeys.Scope.VirtualDisplay) == 3)
        assertTrue(PressKeys.resolve("KEYCODE_POWER", PressKeys.Scope.VirtualDisplay) == null)
    }

    @Test fun injectionFailureHasItsOwnStableErrorAndUnchangedEffectKeepsActionFailed() {
        assertTrue(PressKeys.keyFailure(false, false) == "key_injection_failed")
        assertTrue(PressKeys.keyFailure(true, false) == "action_failed")
        assertTrue(PressKeys.keyFailure(true, true) == null)
    }

    @Test fun scopedKeyWithoutDisplayReadbackSucceedsAfterInjection() {
        assertTrue(PressKeys.scopedKeyFailure(true, null) == null)
    }
}
