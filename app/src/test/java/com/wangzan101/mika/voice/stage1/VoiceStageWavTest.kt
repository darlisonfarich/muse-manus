package com.wangzan101.mika.voice.stage1

import com.wangzan101.mika.voice.Wav
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VoiceStageWavTest {
    @Test fun stageOneWaveHasPcm16MonoHeaderAndLittleEndianSamples() {
        val wav = Wav.encode(shortArrayOf(0x0102, -2))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(16000, header.getInt(24))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(4, header.getInt(40))
        assertArrayEquals(byteArrayOf(2, 1, -2, -1), wav.copyOfRange(44, 48))
    }

    @Test fun thirtySecondLimitFitsValidRiffWav() {
        val wav = Wav.encode(ShortArray(16000 * 30))
        assertEquals(44 + 960000, wav.size)
        assertEquals(960000, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }
}
