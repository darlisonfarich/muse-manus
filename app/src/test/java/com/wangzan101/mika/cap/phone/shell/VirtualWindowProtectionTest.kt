package com.wangzan101.mika.cap.phone.shell

import com.darlisonfarich.gadgetlink.noise.ProtoWriter
import com.wangzan101.mika.cap.phone.shell.VirtualWindowProtection.*
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualWindowProtectionTest {
    private val clearWindow = window(flags = 0x100, visible = true)
    private val secureWindow = window(flags = 0x300, visible = true)

    @Test fun secureOverlayOnBoundDisplayTakesPrecedence() {
        val data = dump(display(0, clearWindow), display(7, clearWindow, secureWindow))
        assertEquals(Secure, inspect(data, 7))
    }

    @Test fun ignoresThePhysicalDisplayOtherSessionsAndHiddenWindows() {
        val data = dump(display(0, secureWindow), display(7, secureWindow),
            display(8, clearWindow, window(flags = 0x200, visible = false)))
        assertEquals(Clear, inspect(data, 8))
        assertEquals(Unknown, inspect(data, 0))
    }

    @Test fun supportsEmbeddedWindowTokensAndWindowsStillOnScreenInTransition() {
        val secure = ProtoWriter().bytes(5, ProtoWriter().uint32(24, 0x200).toByteArray()).bool(37, true).toByteArray()
        val childDialog = ProtoWriter().bytes(1, container(child(8, secure)))
            .bytes(5, ProtoWriter().uint32(24, 0x100).toByteArray()).bool(38, true).toByteArray()
        assertEquals(Secure, inspect(dump(display(8, childDialog)), 8))
    }

    @Test fun absentMalformedAndUnrecognizedDumpsFailClosed() {
        val data = dump(display(7, clearWindow))
        assertEquals(Unknown, inspect(data, 8))
        assertEquals(Unknown, inspect(data.copyOf(data.size - 1), 7))
        assertEquals(Unknown, inspect("Permission Denial".toByteArray(), 7))
        assertEquals(Unknown, inspect(dump(display(7, ProtoWriter().bool(38, true).toByteArray())), 7))
        assertEquals(Unknown, inspect(dump(rawDisplay(7, child(99, byteArrayOf(1)))), 7))
        assertEquals(Empty, inspect(dump(rawDisplay(7)), 7))
    }

    private fun inspect(data: ByteArray, id: Int) = VirtualWindowProtection.inspect(data.inputStream(), id)

    private fun window(flags: Long, visible: Boolean) =
        ProtoWriter().bytes(5, ProtoWriter().uint32(24, flags).toByteArray()).bool(38, visible).toByteArray()

    private fun display(id: Int, vararg windows: ByteArray): ByteArray {
        val token = wrap(1, container(*windows.map { child(8, it) }.toTypedArray()))
        val activity = child(6, wrap(2, token))
        val task = child(5, wrap(31, wrap(1, container(activity))))
        return rawDisplay(id, task)
    }

    private fun rawDisplay(id: Int, vararg children: ByteArray) = child(3,
        ProtoWriter().int32(2, id).bytes(21, wrap(1, container(*children))).toByteArray())

    private fun dump(vararg displays: ByteArray) = wrap(2, wrap(1, container(*displays)))
    private fun child(type: Int, value: ByteArray) = wrap(type, value)
    private fun wrap(field: Int, value: ByteArray) = ProtoWriter().bytes(field, value).toByteArray()
    private fun container(vararg children: ByteArray) =
        ProtoWriter().apply { children.forEach { bytes(5, it) } }.toByteArray()
}
