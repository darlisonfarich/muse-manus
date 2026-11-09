package com.wangzan101.mika.voice.wake

import com.wangzan101.mika.core.voice.WakePhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakeUiState(
    val phase: WakePhase = WakePhase.Off,
    val detail: String = "Desligado",
    val serviceTurnId: String? = null,
    val consumedResultId: String? = null,
) {
    val active: Boolean get() = phase in setOf(
        WakePhase.Preparing, WakePhase.Listening, WakePhase.Capturing,
        WakePhase.Waiting, WakePhase.Playing, WakePhase.PausedForPlayback, WakePhase.Paused,
    )
}

/** Process-local view of the foreground service; no assertion of persistence after process death. */
object VoiceWakeStatus {
    private val mutable = MutableStateFlow(WakeUiState())
    val state = mutable.asStateFlow()

    fun publish(phase: WakePhase, detail: String, serviceTurnId: String? = null) {
        val prev = mutable.value
        mutable.value = WakeUiState(
            phase, detail, serviceTurnId,
            consumedResultId = serviceTurnId ?: prev.consumedResultId,
        )
    }

    fun denied() = publish(WakePhase.PermissionDenied, "Microfone sem permissão")
}
