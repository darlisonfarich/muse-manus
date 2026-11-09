package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalKeyDispatchTest {
    private val ok = GlobalKeyDispatch.Outcome.Dispatched
    private val no = GlobalKeyDispatch.Outcome.NotApplied
    private val unknown = GlobalKeyDispatch.Outcome.Unavailable
    private val volume = PressKeys.VolumeState(7, 15, false)

    @Test fun globalKeysAreDispatched() {
        listOf(3, 24, 25, 164, 85, 86, 87, 88).forEach {
            assertEquals(GlobalKeyDispatch.Route.Global, GlobalKeyDispatch.route(it))
        }
    }

    @Test fun ordinaryKeysRemainDisplayInjected() {
        listOf(4, 19, 66, 187, 82).forEach {
            assertEquals(GlobalKeyDispatch.Route.VirtualKey, GlobalKeyDispatch.route(it))
        }
    }

    @Test fun volumeUpReadback() {
        assertEquals(ok, GlobalKeyDispatch.volume(24, volume, volume.copy(current = 8)))
        assertEquals(no, GlobalKeyDispatch.volume(24, volume, volume))
        assertEquals(no, GlobalKeyDispatch.volume(24, volume.copy(current = 15), volume.copy(current = 15)))
        assertEquals(unknown, GlobalKeyDispatch.volume(24, volume, null))
    }

    @Test fun volumeDownReadback() {
        assertEquals(ok, GlobalKeyDispatch.volume(25, volume, volume.copy(current = 6)))
        assertEquals(no, GlobalKeyDispatch.volume(25, volume, volume))
        assertEquals(unknown, GlobalKeyDispatch.volume(25, null, volume))
    }

    @Test fun muteNeedsAnActualToggle() {
        assertEquals(ok, GlobalKeyDispatch.volume(164, volume, volume.copy(muted = true)))
        assertEquals(ok, GlobalKeyDispatch.volume(164, volume.copy(muted = true), volume))
        assertEquals(no, GlobalKeyDispatch.volume(164, volume, volume))
    }

    @Test fun homeRequiresReadbackOfTheHomeActivity() {
        val before = GlobalKeyDispatch.Home(9, false)
        assertEquals(ok, GlobalKeyDispatch.home(before, GlobalKeyDispatch.Home(10, true)))
        assertEquals(no, GlobalKeyDispatch.home(before, GlobalKeyDispatch.Home(9, false)))
        assertEquals(no, GlobalKeyDispatch.home(GlobalKeyDispatch.Home(10, true), GlobalKeyDispatch.Home(10, true)))
        assertEquals(unknown, GlobalKeyDispatch.home(null, GlobalKeyDispatch.Home(10, true)))
    }

    @Test fun mediaPauseAndResumeReadback() {
        val playing = GlobalKeyDispatch.Media("s1", 3, "track1")
        val paused = playing.copy(playback = 2)
        assertEquals(ok, GlobalKeyDispatch.media(85, playing, paused))
        assertEquals(ok, GlobalKeyDispatch.media(85, paused, playing))
        assertEquals(no, GlobalKeyDispatch.media(85, playing, playing))
        assertEquals(no, GlobalKeyDispatch.media(85, playing, paused.copy(session = "s2")))
        assertEquals(unknown, GlobalKeyDispatch.media(85, null, playing))
    }

    @Test fun mediaStopReadback() {
        val playing = GlobalKeyDispatch.Media("s1", 3, "track1")
        assertEquals(ok, GlobalKeyDispatch.media(86, playing, playing.copy(playback = 1)))
        assertEquals(no, GlobalKeyDispatch.media(86, playing, playing))
    }

    @Test fun nextAndPreviousRequireTrackChange() {
        val before = GlobalKeyDispatch.Media("s1", 3, "track1")
        assertEquals(ok, GlobalKeyDispatch.media(87, before, before.copy(track = "track2")))
        assertEquals(ok, GlobalKeyDispatch.media(88, before, before.copy(track = "track2")))
        assertEquals(no, GlobalKeyDispatch.media(87, before, before))
        assertEquals(unknown, GlobalKeyDispatch.media(87, before, before.copy(track = null)))
    }
}
