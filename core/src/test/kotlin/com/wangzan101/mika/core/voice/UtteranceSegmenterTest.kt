package com.wangzan101.mika.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class UtteranceSegmenterTest {
    private val rate = 16_000
    private fun tone(ms: Int, amp: Int = 4_000, hz: Double = 220.0) =
        ShortArray(rate * ms / 1000) { (amp * sin(2 * PI * hz * it / rate)).toInt().toShort() }
    private fun quiet(ms: Int, amp: Int = 60) =
        ShortArray(rate * ms / 1000) { (if (it % 2 == 0) amp else -amp).toShort() }
    private fun ms(samples: ShortArray) = samples.size * 1000 / rate

    private fun run(seg: UtteranceSegmenter, vararg parts: ShortArray, chunk: Int = 512): List<ShortArray> {
        val out = ArrayList<ShortArray>()
        for (p in parts) {
            var i = 0
            while (i < p.size) { val n = minOf(chunk, p.size - i); out += seg.feed(p.copyOfRange(i, i + n)); i += n }
        }
        return out
    }

    @Test fun silenceAloneProducesNothing() {
        assertTrue(run(UtteranceSegmenter(), quiet(5_000)).isEmpty())
    }

    @Test fun oneUtteranceIsCutAfterEndSilence() {
        val s = UtteranceSegmenter()
        val out = run(s, quiet(1_000), tone(1_500), quiet(1_500))
        assertEquals(1, out.size)
        val d = ms(out[0])
        // 1,5 s de fala + pré-roll (≤ 300 ms) + cauda curta (≤ ~100 ms)
        assertTrue("duração $d ms", d in 1_500..2_000)
        assertFalse(s.speaking)
    }

    @Test fun twoUtterancesSeparatedBySilence() {
        val out = run(UtteranceSegmenter(), tone(800), quiet(1_000), tone(1_200), quiet(1_000))
        assertEquals(2, out.size)
    }

    @Test fun shortPauseInsideSpeechDoesNotSplit() {
        val out = run(UtteranceSegmenter(), tone(800), quiet(400), tone(800), quiet(1_000))
        assertEquals(1, out.size)
        assertTrue(ms(out[0]) >= 2_000)
    }

    @Test fun clickShorterThanStartIsIgnored() {
        val out = run(UtteranceSegmenter(), quiet(500), tone(60), quiet(2_000))
        assertTrue(out.isEmpty())
    }

    @Test fun veryShortSpeechBelowMinVoicedIsDropped() {
        // 200 ms de voz passa o start (6 frames = 120 ms) mas fica abaixo de 300 ms de voz
        val out = run(UtteranceSegmenter(), quiet(500), tone(200), quiet(2_000))
        assertTrue(out.isEmpty())
    }

    @Test fun maxLengthForcesCutUnderProtocolLimit() {
        val s = UtteranceSegmenter()
        val out = run(s, tone(65_000))
        assertTrue("2 cortes em 65 s", out.size == 2)
        for (u in out) {
            val wavBytes = 44 + u.size * 2
            assertTrue("WAV $wavBytes B", wavBytes <= 1_200_000)
            assertEquals(30_000, ms(u))
        }
    }

    @Test fun preRollKeepsTheStartOfSpeech() {
        val out = run(UtteranceSegmenter(), quiet(1_000), tone(1_000), quiet(1_000))
        assertEquals(1, out.size)
        // 1000 ms de voz + 300 ms pré-roll + cauda 100 ms => o início não foi comido
        assertTrue(ms(out[0]) >= 1_300)
    }

    @Test fun noiseFloorAdaptsAndLoudNoiseBecomesBackground() {
        val s = UtteranceSegmenter()
        run(s, quiet(3_000, amp = 200))   // ruído constante de 200 (abaixo do minRms=350)
        assertTrue("piso ${s.noiseFloor}", s.noiseFloor in 150.0..250.0)
        val out = run(s, tone(1_000), quiet(1_000, amp = 200))
        assertEquals(1, out.size)
    }

    @Test fun flushClosesOpenUtterance() {
        val s = UtteranceSegmenter()
        run(s, tone(1_000))
        assertTrue(s.speaking)
        val f = s.flush()
        assertTrue(f != null && ms(f) >= 1_000)
        assertFalse(s.speaking)
    }

    @Test fun resetDropsPartialUtterance() {
        val s = UtteranceSegmenter()
        run(s, tone(1_000))
        s.reset()
        assertTrue(run(s, quiet(2_000)).isEmpty())
    }

    @Test fun oddChunkSizesGiveSameResult() {
        val parts = arrayOf(quiet(600), tone(1_000), quiet(1_000))
        val a = run(UtteranceSegmenter(), *parts, chunk = 512)
        val b = run(UtteranceSegmenter(), *parts, chunk = 77)
        assertEquals(1, a.size); assertEquals(1, b.size)
        assertEquals(a[0].size, b[0].size)
    }
}
