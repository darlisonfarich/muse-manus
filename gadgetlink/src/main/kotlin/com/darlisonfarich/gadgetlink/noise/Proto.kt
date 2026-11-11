// Protobuf escrito à mão do túnel Hatch. Port de musegadget/noise/_proto.py do
// facebookincubator/muse-gadget-sdk (Copyright (c) Meta Platforms, Inc. and affiliates;
// Apache License 2.0). Mudanças: Kotlin; varint em Long (os 64 bits são os do uint64, a
// comparação sem sinal é feita com ushr); leitor e escritor em classes pequenas.
package com.darlisonfarich.gadgetlink.noise

import com.darlisonfarich.gadgetlink.Utf8
import java.io.ByteArrayOutputStream

/** Mensagem Hatch malformada ou valor fora da faixa. */
class ProtoError(message: String) : IllegalArgumentException(message)

internal object Proto {
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_DELIMITED = 2
    const val WIRE_FIXED32 = 5

    private const val MAX_FIELD_NUMBER = (1L shl 29) - 1
    private const val FIRST_RESERVED = 19000L
    private const val LAST_RESERVED = 19999L

    fun validFieldNumber(n: Long): Boolean =
        n != 0L && n in 1..MAX_FIELD_NUMBER && n !in FIRST_RESERVED..LAST_RESERVED

    fun validWireType(w: Int): Boolean = w == WIRE_VARINT || w == WIRE_FIXED64 || w == WIRE_DELIMITED || w == WIRE_FIXED32

    /** [value] carrega os 64 bits do uint64 (negativo = bit 63 ligado). */
    fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream(10)
        var rest = value
        while (true) {
            val byte = (rest and 0x7F).toInt()
            rest = rest ushr 7
            if (rest != 0L) {
                out.write(byte or 0x80)
            } else {
                out.write(byte)
                return out.toByteArray()
            }
        }
    }

    fun encodeKey(field: Int, wire: Int): ByteArray {
        if (!validFieldNumber(field.toLong())) throw ProtoError("invalid field number")
        if (!validWireType(wire)) throw ProtoError("invalid wire type")
        return encodeVarint((field.toLong() shl 3) or wire.toLong())
    }

    fun decodeUint32(raw: Long): Long {
        if ((raw ushr 32) != 0L) throw ProtoError("uint32 value out of range")
        return raw
    }

    fun decodeInt32(raw: Long): Int {
        if (raw < Int.MIN_VALUE || raw > Int.MAX_VALUE) throw ProtoError("int32 value out of range")
        return raw.toInt()
    }
}

class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        out.write(Proto.encodeKey(field, Proto.WIRE_VARINT))
        out.write(Proto.encodeVarint(value))
        return this
    }

    fun int64(field: Int, value: Long): ProtoWriter = varint(field, value)

    /** int32 negativo vai com extensão de sinal para 64 bits, como no protobuf. */
    fun int32(field: Int, value: Int): ProtoWriter = varint(field, value.toLong())

    fun uint32(field: Int, value: Long): ProtoWriter {
        if (value < 0 || value > 0xFFFFFFFFL) throw ProtoError("uint32 value out of range")
        return varint(field, value)
    }

    fun bool(field: Int, value: Boolean): ProtoWriter = varint(field, if (value) 1 else 0)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        out.write(Proto.encodeKey(field, Proto.WIRE_DELIMITED))
        out.write(Proto.encodeVarint(value.size.toLong()))
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, Utf8.encodeStrict(value))

    fun toByteArray(): ByteArray = out.toByteArray()
}

class ProtoReader(private val data: ByteArray, private var offset: Int = 0) {
    class Key(val field: Int, val wire: Int)

    val atEnd: Boolean get() = offset >= data.size

    fun readVarint(): Long {
        var value = 0L
        var shift = 0
        for (i in 0 until 10) {
            if (offset >= data.size) throw ProtoError("truncated varint")
            val byte = data[offset++].toInt() and 0xFF
            if (i == 9 && (byte and 0xFE) != 0) throw ProtoError("malformed varint")
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
        throw ProtoError("malformed varint")
    }

    fun readKey(): Key {
        val key = readVarint()
        val field = key ushr 3
        val wire = (key and 7).toInt()
        if (!Proto.validFieldNumber(field)) throw ProtoError("invalid field number")
        if (!Proto.validWireType(wire)) throw ProtoError("invalid wire type")
        return Key(field.toInt(), wire)
    }

    fun readDelimited(): ByteArray {
        val length = readVarint()
        if (length < 0 || length > data.size - offset) throw ProtoError("truncated delimited field")
        val out = data.copyOfRange(offset, offset + length.toInt())
        offset += length.toInt()
        return out
    }

    fun skip(wire: Int) {
        when (wire) {
            Proto.WIRE_VARINT -> readVarint()
            Proto.WIRE_FIXED64 -> skipFixed(8, "fixed64")
            Proto.WIRE_DELIMITED -> readDelimited()
            Proto.WIRE_FIXED32 -> skipFixed(4, "fixed32")
            else -> throw ProtoError("invalid wire type")
        }
    }

    private fun skipFixed(n: Int, name: String) {
        if (offset + n > data.size) throw ProtoError("truncated $name field")
        offset += n
    }
}
