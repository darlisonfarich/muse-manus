package com.wangzan101.mika.mcp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darlisonfarich.gadgetlink.commands.CommandDispatcher
import com.darlisonfarich.gadgetlink.noise.LinkWire
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class AndroidPhoneScreenshotTest {
    @Test
    fun realBitmapEncoderPreservesPngAndProducesDecodableJpegWithinWireBudget() {
        val bitmap = Bitmap.createBitmap(720, 1440, Bitmap.Config.ARGB_8888)
        try {
            val blankPng = png(bitmap)
            val blank = compressPhoneScreenshot(blankPng, DirectPhoneReads.GADGET_SCREENSHOT_BYTES) as EncodedPhoneScreenshot.Image
            assertArrayEquals(blankPng, blank.bytes)
            val random = Random(523)
            val pixels = IntArray(bitmap.width * bitmap.height) { random.nextInt() or 0xFF000000.toInt() }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val noisyPng = png(bitmap)
            assertTrue(noisyPng.size > DirectPhoneReads.GADGET_SCREENSHOT_BYTES)
            val jpeg = compressPhoneScreenshot(noisyPng, DirectPhoneReads.GADGET_SCREENSHOT_BYTES) as EncodedPhoneScreenshot.Image
            val payload = jpeg.json()
            assertEquals("image/jpeg", payload.getValue("mimeType").jsonPrimitive.content)
            val bytes = Base64.getDecoder().decode(payload.getValue("data").jsonPrimitive.content)
            assertEquals(bytes.size, payload.getValue("sizeBytes").jsonPrimitive.int)
            assertTrue(bytes.size <= DirectPhoneReads.GADGET_SCREENSHOT_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertTrue(bounds.outWidth in 1..bitmap.width)
            assertTrue(bounds.outHeight in 1..bitmap.height)
            val wire = buildJsonObject {
                put("method", "link.result")
                put("id", "á".repeat(126))
                put("ok", true)
                put("payload", payload)
            }
            assertTrue(LinkWire.encodeMessage(wire).size <= CommandDispatcher.MAX_RESULT_BYTES)
        } finally {
            bitmap.recycle()
        }
    }

    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().apply {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this))
    }.toByteArray()
}
