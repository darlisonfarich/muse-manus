package com.wangzan101.mika.mcp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal fun compressPhoneScreenshot(png: ByteArray, maxBytes: Int): EncodedPhoneScreenshot? {
    if (png.size <= maxBytes) return EncodedPhoneScreenshot.Image(png, "image/png")
    val original = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
    var scaled = original
    try {
        return encodePhoneScreenshot(png, maxBytes) { quality, target ->
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            if (out.size() <= target) out.toByteArray() else {
                val next = Bitmap.createScaledBitmap(scaled,
                    (scaled.width * 0.75).toInt().coerceAtLeast(1),
                    (scaled.height * 0.75).toInt().coerceAtLeast(1), true)
                if (scaled !== original && scaled !== next) scaled.recycle()
                scaled = next
                null
            }
        }
    } finally {
        if (scaled !== original) scaled.recycle()
        original.recycle()
    }
}
