package com.wangzan101.mika.core.uistate

/**
 * Global controls are dispatched through Android services instead of being injected into
 * a virtual display. All of them still require an owned display and the existing phone
 * action/approval gate. This class is pure so effect decisions are JVM-testable.
 */
object GlobalKeyDispatch {
    enum class Route { Global, VirtualKey }
    enum class Outcome(val wire: String) {
        Dispatched("dispatched"), NotApplied("not_applied"), Unavailable("effect_unavailable");
    }

    data class Home(val taskId: Int, val isHome: Boolean)
    data class Media(val session: String, val playback: Int, val track: String?)

    fun route(keyCode: Int): Route = when (keyCode) {
        3, 24, 25, 164, 85, 86, 87, 88 -> Route.Global
        else -> Route.VirtualKey
    }

    fun volume(keyCode: Int, before: PressKeys.VolumeState?, after: PressKeys.VolumeState?): Outcome {
        if (before == null || after == null) return Outcome.Unavailable
        val changed = when (keyCode) {
            24 -> after.current > before.current
            25 -> after.current < before.current
            164 -> after.muted != before.muted
            else -> false
        }
        return if (changed) Outcome.Dispatched else Outcome.NotApplied
    }

    fun home(before: Home?, after: Home?): Outcome {
        if (before == null || after == null) return Outcome.Unavailable
        return if (!before.isHome && after.isHome) Outcome.Dispatched else Outcome.NotApplied
    }

    /** PlaybackState: STOPPED=1, PAUSED=2, PLAYING=3. */
    fun media(keyCode: Int, before: Media?, after: Media?): Outcome {
        if (before == null || after == null) return Outcome.Unavailable
        if (before.session != after.session) return Outcome.NotApplied
        val changed = when (keyCode) {
            85 -> (before.playback == 3 && after.playback == 2) ||
                (before.playback != 3 && after.playback == 3)
            86 -> before.playback != 1 && after.playback == 1
            87, 88 -> {
                if (before.track == null || after.track == null) return Outcome.Unavailable
                before.track != after.track
            }
            else -> false
        }
        return if (changed) Outcome.Dispatched else Outcome.NotApplied
    }
}

/** Redacted snapshots for the durable phone_* audit (never store track titles or media IDs). */
data class KeyDispatchEvidence(val status: String, val before: String, val after: String)
