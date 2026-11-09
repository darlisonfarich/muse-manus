package com.wangzan101.mika.core.remote

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Transport-only RFC 6455 probe. Authentication is performed by RemoteAccess before
 * [handshake]. No microphone, agent, task, or device capabilities are exposed.
 */
object VoiceChannelProbe {
    const val PATH = "/api/v1/voice/channel"
    const val MAX_BINARY_BYTES = 1_200_000
    private const val MAX_TEXT_BYTES = 4_096
    private const val MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun handshake(req: HttpRequest, version: String): HttpResponse {
        if (req.method != "GET") return HttpResponse.empty(405, mapOf("Allow" to "GET"))
        val connection = req.header("connection").orEmpty().split(',').any { it.trim().equals("upgrade", true) }
        val upgrade = req.header("upgrade").equals("websocket", true)
        val wsVersion = req.header("sec-websocket-version")
        val key = req.header("sec-websocket-key").orEmpty()
        val decoded = try { Base64.getDecoder().decode(key) } catch (_: IllegalArgumentException) { null }
        if (!connection || !upgrade || wsVersion != "13" || decoded?.size != 16 ||
            req.header("sec-websocket-protocol") != null || req.body.isNotEmpty()
        ) return HttpResponse.empty(400)
        val digest = MessageDigest.getInstance("SHA-1").digest((key + MAGIC).toByteArray(Charsets.US_ASCII))
        val accept = Base64.getEncoder().encodeToString(digest)
        return HttpResponse.empty(101, mapOf(
            "Upgrade" to "websocket",
            "Connection" to "Upgrade",
            "Sec-WebSocket-Accept" to accept,
        ))
    }

    /** One connection, one worker. Reads masked client frames, echoes binary and answers ping. */
    fun serve(input: InputStream, output: OutputStream, version: String,
        event: (String) -> Unit = {}, hub: VoiceA2Hub = VoiceA2Hub(), close: () -> Unit = {}) {
        val writer = object : VoiceA2Sender {
            override fun send(opcode: Int, payload: ByteArray) {
                synchronized(output) { writeFrame(output, opcode, payload) }
            }
            override fun close() { close.invoke() }
        }
        if (!hub.attach(writer)) return
        event("voice WebSocket probe opened")
        try {
            while (true) {
                val first = input.read()
                if (first == -1) return
                val second = input.read()
                if (second == -1) throw EOFException("truncated frame")
                val opcode = first and 15
                val fin = first and 0x80 != 0
                val masked = second and 0x80 != 0
                if (!fin || !masked || first and 0x70 != 0) throw IOException("invalid probe frame")
                var length = (second and 0x7f).toLong()
                if (length == 126L) length = ((octet(input).toLong() shl 8) or octet(input).toLong())
                else if (length == 127L) {
                    length = 0
                    repeat(8) {
                        val byte = octet(input).toLong()
                        if (length > (MAX_BINARY_BYTES.toLong() ushr 8)) throw IOException("oversized frame")
                        length = (length shl 8) or byte
                    }
                }
                val max = if (opcode == 1) MAX_TEXT_BYTES else if (opcode >= 8) 125 else MAX_BINARY_BYTES
                if (length > max || length < 0) throw IOException("oversized frame")
                val mask = readBytes(input, 4)
                val payload = readBytes(input, length.toInt())
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    1 -> {
                        val value = String(payload, Charsets.UTF_8)
                        val answer = when (value) {
                            "ping" -> "pong"
                            "probe" -> buildJsonObject {
                                put("type", "probe_ok")
                                put("server_version", version)
                            }.toString()
                            else -> null
                        }
                        if (answer != null) writer.send(1, answer.toByteArray(Charsets.UTF_8))
                        else hub.onTextFrom(writer, value)
                    }
                    2 -> if (hub.state.value.ready) hub.onBinaryFrom(writer, payload) else writer.send(2, payload)
                    8 -> { writer.send(8, payload); return }
                    9 -> writer.send(10, payload)
                    10 -> Unit
                    else -> throw IOException("unsupported opcode")
                }
            }
        } finally {
            hub.detach(writer)
            event("voice WebSocket probe closed")
        }
    }

    private fun octet(input: InputStream): Int = input.read().takeIf { it >= 0 } ?: throw EOFException("truncated frame")

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(data, offset, count - offset)
            if (read <= 0) throw EOFException("truncated frame")
            offset += read
        }
        return data
    }

    private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 65535 -> {
                output.write(126)
                output.write(payload.size ushr 8)
                output.write(payload.size and 255)
            }
            else -> {
                output.write(127)
                repeat(4) { output.write(0) }
                output.write(payload.size ushr 24 and 255)
                output.write(payload.size ushr 16 and 255)
                output.write(payload.size ushr 8 and 255)
                output.write(payload.size and 255)
            }
        }
        output.write(payload)
        output.flush()
    }
}
