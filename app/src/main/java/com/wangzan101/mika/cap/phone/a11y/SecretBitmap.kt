package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.wangzan101.mika.core.uistate.SecretRegions

/**
 * Draws solid, opaque masks on a private COPY of the captured frame, before JPEG/PNG
 * encoding or OCR. The original bitmap must never be sent to a model or recognizer.
 *
 * The caller owns both bitmaps. When there are no secret areas this returns [source];
 * otherwise the caller must recycle both the original and returned copy.
 */
internal object SecretBitmap {
    fun redact(source: Bitmap, areas: List<SecretRegions.Box>): Bitmap? {
        if (areas.isEmpty()) return source
        val boxes = SecretRegions.pixels(areas, source.width, source.height) ?: return null
        val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        try {
            val paint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
                isAntiAlias = false
                alpha = 255
            }
            val canvas = Canvas(out)
            boxes.forEach { r -> canvas.drawRect(r.left.toFloat(), r.top.toFloat(),
                r.right.toFloat(), r.bottom.toFloat(), paint) }
            return out
        } catch (e: Exception) {
            out.recycle()
            throw e
        }
    }
}
