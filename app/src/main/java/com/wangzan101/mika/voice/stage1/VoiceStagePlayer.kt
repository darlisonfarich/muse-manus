package com.wangzan101.mika.voice.stage1

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import java.io.File

/** Local stop never waits for the gateway. */
class VoiceStagePlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private var file: File? = null

    fun play(encoded: String, onDone: () -> Unit) {
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) }
            catch (e: IllegalArgumentException) { throw IllegalArgumentException("MP3 inválido", e) }
        playBytes(bytes, onDone)
    }

    fun playBytes(bytes: ByteArray, onDone: () -> Unit) {
        stop()
        require(bytes.isNotEmpty() && bytes.size <= 12 * 1024 * 1024) { "MP3 fora do limite" }
        val temp = File.createTempFile("voice-stage1-", ".mp3", context.cacheDir)
        file = temp
        try {
            temp.writeBytes(bytes)
            val p = MediaPlayer()
            player = p
            p.setDataSource(temp.absolutePath)
            p.setOnCompletionListener { stop(); onDone() }
            p.setOnErrorListener { _, _, _ -> stop(); onDone(); true }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        val current = player
        player = null
        try { current?.stop() } catch (_: IllegalStateException) {}
        current?.release()
        file?.delete()
        file = null
    }
}
