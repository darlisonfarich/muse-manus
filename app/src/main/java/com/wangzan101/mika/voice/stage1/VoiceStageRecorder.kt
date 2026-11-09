package com.wangzan101.mika.voice.stage1

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.wangzan101.mika.voice.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Manual screen-only capture, capped at 30 seconds. No service or wake word. */
class VoiceStageRecorder {
    private val stopRequested = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    fun stop() { stopRequested.set(true) }

    @SuppressLint("MissingPermission")
    suspend fun record(context: Context): ByteArray = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microfone sem permissão"
        }
        check(active.compareAndSet(false, true)) { "Gravação em andamento" }
        stopRequested.set(false)
        var recorder: AudioRecord? = null
        try {
            val min = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) throw IOException("Microfone indisponível")
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, Wav.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096),
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microfone indisponível")
            val data = ShortArray(2048)
            val output = ByteArrayOutputStream()
            val limit = Wav.RATE * 30
            var countTotal = 0
            recorder.startRecording()
            while (!stopRequested.get() && countTotal < limit) {
                val n = recorder.read(data, 0, minOf(data.size, limit - countTotal), AudioRecord.READ_NON_BLOCKING)
                if (n < 0) throw IOException("Falha ao gravar áudio")
                if (n == 0) { delay(12); continue }
                val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bytes.putShort(data[i])
                output.write(bytes.array())
                countTotal += n
            }
            if (countTotal == 0) throw IOException("Áudio vazio")
            val pcm = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            Wav.encode(ShortArray(countTotal) { pcm.short })
        } finally {
            try { recorder?.stop() } catch (_: IllegalStateException) {}
            recorder?.release()
            active.set(false)
        }
    }
}
