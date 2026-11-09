package com.wangzan101.mika.voice.stage1

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MP3 files are written off the main thread; MediaPlayer.prepareAsync never blocks Compose.
 * A generation token rejects a finished disk write after stop() or leaving the screen.
 */
class VoiceStagePlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var generation = 0L

    /** Legacy stage-one entry point, not used by the private A2 screen. */
    suspend fun play(encoded: String, onDone: () -> Unit) {
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException("MP3 inválido", e) }
        playBytesAsync(bytes, onDone)
    }

    suspend fun playBytesAsync(bytes: ByteArray, onDone: () -> Unit) {
        require(bytes.isNotEmpty() && bytes.size <= 12 * 1024 * 1024) { "MP3 fora do limite" }
        stop()
        val mine = generation
        val temp = withContext(Dispatchers.IO) {
            val newFile = File.createTempFile("voice-stage1-", ".mp3", context.cacheDir)
            try {
                newFile.writeBytes(bytes)
                newFile
            } catch (e: Exception) {
                newFile.delete()
                throw e
            }
        }
        if (mine != generation) {
            temp.delete()
            return
        }
        file = temp
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(temp.absolutePath)
            mp.setOnPreparedListener {
                if (mine == generation && player === mp) {
                    try { mp.start() } catch (_: Exception) {
                        if (mine == generation) { stop(); onDone() }
                    }
                }
            }
            mp.setOnCompletionListener {
                if (mine == generation) { stop(); onDone() }
            }
            mp.setOnErrorListener { _, _, _ ->
                if (mine == generation) { stop(); onDone() }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /** Local interruption is independent of network state. */
    fun stop() {
        generation++
        val current = player
        player = null
        // release() is valid even if prepareAsync() has not completed.
        runCatching { current?.release() }
        file?.delete()
        file = null
    }
}
