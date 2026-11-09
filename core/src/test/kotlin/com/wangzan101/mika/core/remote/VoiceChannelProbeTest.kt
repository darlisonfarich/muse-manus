package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.Base64
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VoiceChannelProbeTest {
    private val token = "voice-probe-test-token"
    private val host = RemoteHost(FakeRemoteTasks(), { token }, 0, false, "0.1.38-test")
    @Before fun start() = host.start()
    @After fun stop() = host.stop()

    private fun open(auth: Boolean = true, origin: Boolean = false): Pair<Socket, BufferedInputStream> {
        val socket = Socket("127.0.0.1", host.boundPort).apply { soTimeout = 10_000 }
        val input = BufferedInputStream(socket.getInputStream())
        val headers = buildString {
            append("GET /api/v1/voice/channel HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            if (auth) append("Authorization: Bearer $token\r\n")
            if (origin) append("Origin: https://example.com\r\n")
            append("\r\n")
        }
        socket.getOutputStream().write(headers.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        return socket to input
    }

    private fun head(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var n = 0
        while (n < 4) {
            val b = input.read()
            if (b < 0) error("HTTP header ended early")
            out.write(b)
            n = if (b == "\r\n\r\n"[n].code) n + 1 else if (b == '\r'.code) 1 else 0
        }
        return out.toString("ISO-8859-1")
    }

    private fun writeClientFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size < 65536 -> {
                out.write(0x80 or 126); out.write(payload.size ushr 8); out.write(payload.size and 255)
            }
            else -> {
                out.write(0x80 or 127)
                repeat(4) { out.write(0) }
                out.write(payload.size ushr 24 and 255)
                out.write(payload.size ushr 16 and 255)
                out.write(payload.size ushr 8 and 255)
                out.write(payload.size and 255)
            }
        }
        val key = byteArrayOf(7, 23, 47, 89)
        out.write(key)
        val masked = payload.copyOf()
        for (i in masked.indices) masked[i] = (masked[i].toInt() xor key[i % 4].toInt()).toByte()
        out.write(masked)
        out.flush()
    }

    private fun octet(input: InputStream) = input.read().also { if (it < 0) error("truncated frame") }
    private fun readFrame(input: InputStream): Pair<Int, ByteArray> {
        val first = octet(input)
        val second = octet(input)
        assertEquals(0, second and 0x80)
        var length = (second and 0x7f).toLong()
        if (length == 126L) length = ((octet(input) shl 8) or octet(input)).toLong()
        else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or octet(input).toLong() }
        }
        assertTrue(length < 2_000_000)
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n <= 0) error("truncated payload")
            read += n
        }
        return (first and 15) to bytes
    }

    @Test fun authenticationPrecedesUpgrade() {
        val (socket, input) = open(auth = false)
        socket.use { assertTrue(head(input).startsWith("HTTP/1.1 401")) }
    }

    @Test fun browserOriginIsRejected() {
        val (socket, input) = open(origin = true)
        socket.use { assertTrue(head(input).startsWith("HTTP/1.1 403")) }
    }

    @Test fun upgradeProbeBinaryEchoPingAndLargeResponse() {
        val (socket, input) = open()
        socket.use {
            val header = head(input)
            assertTrue(header, header.startsWith("HTTP/1.1 101 Switching Protocols"))
            assertTrue(header, header.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
            val out = socket.getOutputStream()
            writeClientFrame(out, 1, "probe".toByteArray())
            val (opcode, reply) = readFrame(input)
            assertEquals(1, opcode)
            assertTrue(String(reply).contains("\"type\":\"probe_ok\""))
            assertTrue(String(reply).contains("\"server_version\":\"0.1.38-test\""))
            writeClientFrame(out, 9, byteArrayOf(1, 2, 3))
            val (pongOpcode, pong) = readFrame(input)
            assertEquals(10, pongOpcode)
            assertArrayEquals(byteArrayOf(1, 2, 3), pong)
            for (size in listOf(36 * 1024, 1_000_000)) {
                val payload = ByteArray(size) { i -> (i * 31).toByte() }
                writeClientFrame(out, 2, payload)
                val (echoType, echo) = readFrame(input)
                assertEquals(2, echoType)
                assertArrayEquals(payload, echo)
            }
            writeClientFrame(out, 8, byteArrayOf(3, -24))
            assertEquals(8, readFrame(input).first)
        }
    }

    @Test fun invalidWebsocketHandshakeRejected() {
        val socket = Socket("127.0.0.1", host.boundPort).apply { soTimeout = 5_000 }
        socket.use {
            val req = "GET /api/v1/voice/channel HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Key: invalid\r\nSec-WebSocket-Version: 13\r\n\r\n"
            socket.getOutputStream().write(req.toByteArray())
            assertTrue(head(socket.getInputStream()).startsWith("HTTP/1.1 400"))
        }
    }
}
