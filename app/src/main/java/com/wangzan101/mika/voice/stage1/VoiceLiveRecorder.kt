package com.wangzan101.mika.voice.stage1

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.wangzan101.mika.core.voice.UtteranceSegmenter
import com.wangzan101.mika.voice.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the device gave us for echo control; logged and shown so a missing AEC is never silent. */
data class LiveAudioInfo(
    val aecAvailable: Boolean,
    val aecEnabled: Boolean,
    val noiseSuppressorEnabled: Boolean,
    val gainControlEnabled: Boolean,
) {
    val summary: String get() = if (aecEnabled) "cancelamento de eco ativo"
        else "SEM cancelamento de eco neste aparelho (microfone fecha durante a fala do Ship)"
}

/**
 * Continuous capture for live voice. The microphone stays open (source VOICE_COMMUNICATION, which turns
 * on the device AEC/NS/AGC path); [UtteranceSegmenter] cuts the stream into utterances and each one is
 * handed over as a 16 kHz mono PCM16 WAV, the exact format the A2 TURN_START declares.
 *
 * [gate] says whether new speech may be taken now (false while the Ship is thinking or talking). While it is
 * false the frames are read and dropped, so the microphone stays warm but nothing is queued. Without a
 * working AEC the caller should also keep the gate closed a little after playback ends.
 */
class VoiceLiveRecorder(private val segmenter: UtteranceSegmenter = UtteranceSegmenter()) {
    @SuppressLint("MissingPermission")
    suspend fun run(
        context: Context,
        gate: () -> Boolean,
        onInfo: (LiveAudioInfo) -> Unit,
        onSpeech: (speaking: Boolean) -> Unit,
        onUtterance: suspend (wav: ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microfone sem permissão"
        }
        val min = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IOException("Microfone indisponível")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, Wav.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Wav.RATE / 2 * 2),
        )
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microfone indisponível")
            val session = record.audioSessionId
            val aecAvailable = AcousticEchoCanceler.isAvailable()
            if (aecAvailable) aec = AcousticEchoCanceler.create(session)?.also { it.enabled = true }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(session)?.also { it.enabled = true }
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(session)?.also { it.enabled = true }
            val info = LiveAudioInfo(aecAvailable, aec?.enabled == true, ns?.enabled == true, agc?.enabled == true)
            Log.i(TAG, "live mic: aecAvailable=${info.aecAvailable} aec=${info.aecEnabled} ns=${info.noiseSuppressorEnabled} agc=${info.gainControlEnabled}")
            if (!info.aecEnabled) Log.w(TAG, "no acoustic echo canceller on this device: half-duplex fallback")
            onInfo(info)

            val buffer = ShortArray(Wav.RATE / 50 * 4) // 80 ms per read
            var wasOpen = true
            var lastSpeaking = false
            record.startRecording()
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = record.read(buffer, 0, buffer.size) // blocking: returns within ~80 ms
                if (n < 0) throw IOException("Falha ao gravar áudio ($n)")
                if (n == 0) continue
                val open = gate()
                if (!open) {
                    if (wasOpen) segmenter.reset()
                    wasOpen = false
                    if (lastSpeaking) { lastSpeaking = false; onSpeech(false) }
                    continue
                }
                wasOpen = true
                for (pcm in segmenter.feed(buffer, n)) onUtterance(Wav.encode(pcm))
                val speaking = segmenter.speaking
                if (speaking != lastSpeaking) { lastSpeaking = speaking; onSpeech(speaking) }
            }
        } finally {
            try { record.stop() } catch (_: IllegalStateException) {}
            aec?.release(); ns?.release(); agc?.release()
            record.release()
            segmenter.reset()
        }
    }

    private companion object { const val TAG = "VoiceLiveRecorder" }
}
