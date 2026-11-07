package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.graphics.Color
import com.wangzan101.mika.core.uistate.SecretRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Run with connected Android device/emulator; not part of JVM CI. */
class SecretBitmapAndroidTest {
    @Test fun opaqueMaskIsAppliedBeforePixelsAreExportedWithoutMutatingSource() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        try {
            val masked = SecretBitmap.redact(source, listOf(SecretRegions.Box(300, 300, 600, 600)))!!
            try {
                assertNotSame(source, masked)
                assertEquals(Color.BLACK, masked.getPixel(40, 40))
                assertEquals(Color.WHITE, masked.getPixel(95, 95))
                assertEquals(Color.WHITE, source.getPixel(40, 40))
            } finally {
                masked.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test fun noSecretRegionsPreserveExistingFrameWithoutExtraCopy() {
        val source = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
        try { assertSame(source, SecretBitmap.redact(source, emptyList())) }
        finally { source.recycle() }
    }
}
