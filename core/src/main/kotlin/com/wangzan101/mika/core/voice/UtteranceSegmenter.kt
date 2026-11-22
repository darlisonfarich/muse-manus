package com.wangzan101.mika.core.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cuts a continuous 16 kHz mono PCM16 stream into utterances, one per spoken request.
 *
 * Energy VAD, no external library: 20 ms frames, RMS compared with an adaptive noise floor
 * (slow moving average, updated only on frames not classed as speech). Speech starts after
 * [Config.startFrames] voiced frames in a row and ends after [Config.endSilenceMs] of silence;
 * a short pre-roll is kept so the first syllable is not clipped. Everything is deterministic and
 * Android-free, so the cutting rules are unit-tested on the JVM.
 */
class UtteranceSegmenter(private val config: Config = Config()) {
    data class Config(
        val sampleRate: Int = 16_000,
        val frameMs: Int = 20,
        /** Silence that closes an utterance. */
        val endSilenceMs: Int = 700,
        /** Audio kept from before the detected start. */
        val preRollMs: Int = 300,
        /** Consecutive voiced frames needed to open an utterance (filters clicks). */
        val startFrames: Int = 6,
        /** Utterances with less voiced audio than this are dropped (coughs, taps). */
        val minVoicedMs: Int = 300,
        /** Hard cut. 30 s of PCM16 at 16 kHz is 960,044 bytes as WAV, under the 1,200,000 byte protocol limit. */
        val maxUtteranceMs: Int = 30_000,
        /** Speech needs RMS above max(this, noiseFloor * [noiseRatio]). */
        val minRms: Double = 350.0,
        val noiseRatio: Double = 3.0,
        val initialNoiseRms: Double = 120.0,
    ) {
        init {
            require(sampleRate > 0 && frameMs > 0 && sampleRate * frameMs % 1000 == 0)
            require(startFrames >= 1 && endSilenceMs >= frameMs && maxUtteranceMs > endSilenceMs)
        }
    }

    private val frameSize = config.sampleRate * config.frameMs / 1000
    private val endFrames = config.endSilenceMs / config.frameMs
    private val preRollFrames = config.preRollMs / config.frameMs
    private val maxFrames = config.maxUtteranceMs / config.frameMs
    private val minVoicedFrames = config.minVoicedMs / config.frameMs

    private val pending = ShortArray(frameSize)
    private var pendingFill = 0
    private val preRoll = ArrayDeque<ShortArray>()
    private var utterance = ArrayList<ShortArray>()
    private var inSpeech = false
    private var voicedRun = 0
    private var voicedTotal = 0
    private var silenceRun = 0
    private var noiseRms = config.initialNoiseRms

    /** True while an utterance is open (speech was detected and has not ended). */
    val speaking: Boolean get() = inSpeech

    /** Current noise estimate, exposed for logs and tests. */
    val noiseFloor: Double get() = noiseRms

    /** Feeds [count] samples; returns the utterances (PCM16 samples) that were completed by this call. */
    fun feed(samples: ShortArray, count: Int = samples.size): List<ShortArray> {
        require(count in 0..samples.size)
        var done: MutableList<ShortArray>? = null
        var at = 0
        while (at < count) {
            val take = minOf(frameSize - pendingFill, count - at)
            System.arraycopy(samples, at, pending, pendingFill, take)
            pendingFill += take
            at += take
            if (pendingFill == frameSize) {
                pendingFill = 0
                val finished = onFrame(pending.copyOf())
                if (finished != null) (done ?: ArrayList<ShortArray>().also { done = it }).add(finished)
            }
        }
        return done ?: emptyList()
    }

    /** Closes the open utterance, if it has enough speech (call when the capture stops). */
    fun flush(): ShortArray? {
        val out = if (inSpeech) close() else null
        reset()
        return out
    }

    /** Drops any partial state (e.g. when the microphone gate closes), keeping the noise estimate. */
    fun reset() {
        pendingFill = 0
        preRoll.clear()
        utterance = ArrayList()
        inSpeech = false
        voicedRun = 0
        voicedTotal = 0
        silenceRun = 0
    }

    private fun onFrame(frame: ShortArray): ShortArray? {
        val rms = rms(frame)
        val voiced = rms >= max(config.minRms, noiseRms * config.noiseRatio)
        if (!voiced) noiseRms = (noiseRms * 0.95 + rms * 0.05).coerceIn(30.0, 2_000.0)

        if (!inSpeech) {
            preRoll.addLast(frame)
            while (preRoll.size > preRollFrames + config.startFrames) preRoll.removeFirst()
            voicedRun = if (voiced) voicedRun + 1 else 0
            if (voicedRun >= config.startFrames) {
                inSpeech = true
                voicedTotal = voicedRun
                silenceRun = 0
                utterance = ArrayList(preRoll)
                preRoll.clear()
            }
            return null
        }

        utterance.add(frame)
        if (voiced) { voicedTotal++; silenceRun = 0 } else silenceRun++
        if (silenceRun >= endFrames || utterance.size >= maxFrames) return close()
        return null
    }

    private fun close(): ShortArray? {
        // Drop the trailing silence beyond a short tail, so the WAV stays small.
        val tailKeep = 5
        var frames = utterance
        if (silenceRun > tailKeep) frames = ArrayList(frames.subList(0, frames.size - (silenceRun - tailKeep)))
        val keep = voicedTotal >= minVoicedFrames
        inSpeech = false
        voicedRun = 0
        voicedTotal = 0
        silenceRun = 0
        utterance = ArrayList()
        preRoll.clear()
        if (!keep) return null
        val out = ShortArray(frames.size * frameSize)
        frames.forEachIndexed { i, f -> f.copyInto(out, i * frameSize) }
        return out
    }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return sqrt(sum / frame.size)
    }
}
