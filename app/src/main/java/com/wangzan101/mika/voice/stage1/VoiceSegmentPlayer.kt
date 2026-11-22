package com.wangzan101.mika.voice.stage1

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays MP3 segments in arrival order. The first one starts as soon as it is prepared, later ones are
 * prepared while the current one plays, so the hand-over is as short as MediaPlayer allows. Every
 * segment is a complete MP3 of its own. Playback uses USAGE_VOICE_COMMUNICATION so the platform AEC
 * sees the output it must cancel. All calls and callbacks run on the main thread.
 *
 * [onPlaying] fires with true when audio starts and false when the queue has run dry or was stopped.
 * A false between two segments is possible if the next one has not arrived yet.
 */
class VoiceSegmentPlayer(private val context: Context, private val onPlaying: (Boolean) -> Unit) {
    private class Item(val file: File) {
        var player: MediaPlayer? = null
        var prepared = false
        var failed = false
    }

    private val queue = ArrayDeque<Item>()
    private var current: Item? = null
    private var playing = false

    val isPlaying: Boolean get() = playing

    fun enqueue(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "MP3 fora do limite" }
        val item = Item(File.createTempFile("voice-seg-", ".mp3", context.cacheDir))
        try {
            item.file.writeBytes(bytes)
            val p = MediaPlayer()
            item.player = p
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(item.file.absolutePath)
            p.setOnPreparedListener { item.prepared = true; pump() }
            p.setOnCompletionListener { ended(item) }
            p.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "segment playback error what=$what extra=$extra")
                item.failed = true
                ended(item)
                true
            }
            queue.addLast(item)
            p.prepareAsync()
        } catch (e: Exception) {
            queue.remove(item)
            release(item)
            throw e
        }
    }

    /** Immediate: stops the current segment and drops everything queued. */
    fun stop() {
        val items = ArrayList<Item>()
        current?.let { items.add(it) }
        items.addAll(queue)
        current = null
        queue.clear()
        items.forEach { release(it) }
        setPlaying(false)
    }

    private fun pump() {
        if (current != null) return
        while (true) {
            val head = queue.firstOrNull() ?: break
            if (head.failed) { queue.removeFirst(); release(head); continue }
            if (!head.prepared) break
            queue.removeFirst()
            current = head
            try { head.player!!.start(); setPlaying(true) }
            catch (e: Exception) { Log.w(TAG, "segment start failed", e); current = null; release(head); continue }
            return
        }
        if (current == null && queue.isEmpty()) setPlaying(false)
    }

    private fun ended(item: Item) {
        if (current === item) {
            current = null
            release(item)
            pump()
        } else if (queue.remove(item)) {
            release(item)
            pump()
        }
    }

    private fun release(item: Item) {
        val p = item.player
        item.player = null
        if (p != null) {
            p.setOnPreparedListener(null); p.setOnCompletionListener(null); p.setOnErrorListener(null)
            try { p.stop() } catch (_: IllegalStateException) {}
            p.release()
        }
        item.file.delete()
    }

    private fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        onPlaying(value)
    }

    private companion object {
        const val TAG = "VoiceSegmentPlayer"
        const val MAX_BYTES = 12 * 1024 * 1024
    }
}
