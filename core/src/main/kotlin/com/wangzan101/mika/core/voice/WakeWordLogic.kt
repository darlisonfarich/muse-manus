package com.wangzan101.mika.core.voice

import java.text.Normalizer
import java.util.Locale

/** Pure state machine. Unexpected events never silently move the service forward. */
enum class WakePhase { Off, Preparing, Listening, Capturing, Waiting, Playing, Paused, PermissionDenied, Error }

class WakeStateMachine {
    var phase: WakePhase = WakePhase.Off
        private set

    fun enable(hasPermission: Boolean): WakePhase {
        phase = if (hasPermission) WakePhase.Preparing else WakePhase.PermissionDenied
        return phase
    }

    fun prepared(): Boolean = move(WakePhase.Preparing, WakePhase.Listening)
    fun detected(): Boolean = move(WakePhase.Listening, WakePhase.Capturing)
    fun sent(): Boolean = move(WakePhase.Capturing, WakePhase.Waiting)
    fun answer(): Boolean = move(WakePhase.Waiting, WakePhase.Playing)
    fun finished(): Boolean {
        if (phase !in setOf(WakePhase.Capturing, WakePhase.Waiting, WakePhase.Playing)) return false
        phase = WakePhase.Listening
        return true
    }

    fun pause(): Boolean {
        if (phase == WakePhase.Off || phase == WakePhase.PermissionDenied) return false
        phase = WakePhase.Paused
        return true
    }

    fun resume(): Boolean = move(WakePhase.Paused, WakePhase.Preparing)
    fun permissionLost() { phase = WakePhase.PermissionDenied }
    fun error() { phase = WakePhase.Error }
    fun disable() { phase = WakePhase.Off }

    private fun move(from: WakePhase, to: WakePhase): Boolean {
        if (phase != from) return false
        phase = to
        return true
    }
}

/**
 * Requires a matching Vosk partial hypothesis AND a matching final hypothesis for one
 * utterance within a bounded interval. Repeated partial callbacks count only once.
 * This is correlated two-stage confirmation, not two independent acoustic recognizers.
 */
class WakePhraseGate(private val windowMs: Long = 2_500L) {
    init { require(windowMs > 0) }
    private var partialAt: Long? = null

    fun accept(text: String, final: Boolean, nowMs: Long): Boolean {
        val match = normalize(text) == "oi muse"
        if (!final) {
            if (match && (partialAt == null || nowMs - partialAt!! !in 0..windowMs)) {
                partialAt = nowMs
            }
            return false
        }
        val first = partialAt
        partialAt = null
        return match && first != null && nowMs - first in 0..windowMs
    }

    fun reset() { partialAt = null }

    companion object {
        fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}

/** Last 1s of 16kHz mono PCM16, bounded storage, oldest sample first on snapshot. */
class ShortPcmRing(private val capacity: Int = 16_000) {
    init { require(capacity > 0) }
    private val samples = ShortArray(capacity)
    private var next = 0
    private var count = 0

    fun add(chunk: ShortArray, length: Int = chunk.size) {
        require(length in 0..chunk.size)
        for (index in 0 until length) {
            samples[next] = chunk[index]
            next = (next + 1) % capacity
            if (count < capacity) count++
        }
    }

    fun snapshot(): ShortArray = ShortArray(count) { index ->
        samples[(next - count + index + capacity) % capacity]
    }

    fun clear() {
        next = 0
        count = 0
        samples.fill(0)
    }

    val size: Int get() = count
}
