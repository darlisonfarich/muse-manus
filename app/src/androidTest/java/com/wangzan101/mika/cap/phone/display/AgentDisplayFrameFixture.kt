package com.wangzan101.mika.cap.phone.display

/**
 * Test-only fingerprint of an internal virtual-display bitmap.
 * Never serializes or publishes raw frames; always recycles the bitmap.
 */
internal fun AgentDisplay.frameFingerprint(): Long? {
    val frame = latestFrameBitmap() ?: return null
    return try {
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        (frame.width.toLong() shl 48) xor
            (frame.height.toLong() shl 32) xor
            (pixels.contentHashCode().toLong() and 0xffff_ffffL)
    } finally {
        frame.recycle()
    }
}
