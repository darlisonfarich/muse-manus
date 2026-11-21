package com.wangzan101.mika.cap.phone.shell

import com.google.protobuf.CodedInputStream
import java.io.InputStream

/**
 * Reads only the selected display's window flags from WindowManagerServiceDumpProto.
 * Black virtual frames cannot identify FLAG_SECURE, especially for a secure dialog
 * over a visible window. The helper sends only this verdict back over Binder.
 */
internal enum class VirtualWindowProtection(val code: Int) {
    Unknown(0), Empty(1), Clear(2), Secure(3);

    companion object {
        private const val FLAG_SECURE = 0x200
        private const val MAX_DUMP_BYTES = 4 * 1024 * 1024
        private const val MAX_DEPTH = 64

        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: Unknown

        fun inspect(source: InputStream, displayId: Int): VirtualWindowProtection {
            if (displayId <= 0) return Unknown
            return try {
                val input = CodedInputStream.newInstance(source).apply { setSizeLimit(MAX_DUMP_BYTES) }
                var result = Unknown
                input.fields { tag ->
                    if (tag == message(2)) input.nested { // WindowManagerServiceDumpProto.root_window_container
                        input.fields { rootTag ->
                            if (rootTag == message(1)) input.nested { // RootWindowContainerProto.window_container
                                input.children {
                                    if (it == message(3)) {
                                        val display = CodedInputStream.newInstance(input.readByteArray())
                                        var id = 0
                                        var area: ByteArray? = null
                                        display.fields { field ->
                                            when (field) {
                                                varint(2) -> id = display.readInt32()
                                                message(21) -> area = display.readByteArray()
                                                else -> display.skip(field)
                                            }
                                        }
                                        if (id == displayId) {
                                            val root = CodedInputStream.newInstance(requireNotNull(area))
                                            val windows = WindowState()
                                            root.container(1, windows, 0)
                                            result = windows.verdict()
                                        }
                                    } else input.skip(it)
                                }
                            } else input.skip(rootTag)
                        }
                    } else input.skip(tag)
                }
                result
            } catch (_: Exception) {
                Unknown
            }
        }

        private class WindowState(var visible: Boolean = false, var secure: Boolean = false) {
            fun verdict() = when {
                secure -> Secure
                visible -> Clear
                else -> Empty
            }
        }

        private fun CodedInputStream.walk(tag: Int, windows: WindowState, depth: Int) {
            require(depth < MAX_DEPTH)
            when (tag) {
                message(2) -> nested { children { walk(it, windows, depth + 1) } }
                message(4), message(7), message(9) -> nested { container(1, windows, depth) }
                message(5) -> nested { // TaskProto.task_fragment -> WindowContainerProto
                    fields { if (it == message(31)) nested { container(1, windows, depth) } else skip(it) }
                }
                message(6) -> nested { // ActivityRecordProto.window_token -> WindowContainerProto
                    fields { if (it == message(2)) nested { container(1, windows, depth) } else skip(it) }
                }
                message(8) -> nested {
                    var visible = false
                    var flags: Int? = null
                    fields {
                        when (it) {
                            message(1) -> nested { children { walk(it, windows, depth + 1) } }
                            message(5) -> nested {
                                flags = 0 // Proto omits a zero LayoutParams.flags.
                                fields { field -> if (field == varint(24)) flags = readUInt32() else skip(field) }
                            }
                            varint(37), varint(38) -> if (readBool()) visible = true // On-screen or visible.
                            else -> skip(it)
                        }
                    }
                    if (visible) {
                        windows.visible = true
                        if (requireNotNull(flags) and FLAG_SECURE != 0) windows.secure = true
                    }
                }
                // A display container must not contain a second display. Unknown hierarchy
                // shapes fail closed until we can establish where all windows live.
                else -> error("unsupported window container")
            }
        }

        private fun CodedInputStream.container(field: Int, windows: WindowState, depth: Int) =
            fields { if (it == message(field)) nested { children { walk(it, windows, depth + 1) } } else skip(it) }

        private fun CodedInputStream.children(child: (Int) -> Unit) =
            fields { if (it == message(5)) nested { fields(child) } else skip(it) }

        private fun CodedInputStream.fields(field: (Int) -> Unit) {
            while (true) {
                val tag = readTag()
                if (tag == 0) return
                field(tag)
            }
        }

        private fun CodedInputStream.nested(body: () -> Unit) {
            val previous = pushLimit(readRawVarint32())
            body()
            require(bytesUntilLimit == 0)
            popLimit(previous)
        }

        private fun CodedInputStream.skip(tag: Int) { require(skipField(tag)) }
        private fun message(field: Int) = (field shl 3) or 2
        private fun varint(field: Int) = field shl 3
    }
}
