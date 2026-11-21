package com.wangzan101.mika.cap.phone.a11y

internal sealed interface VirtualScreenshot {
    class Png(val bytes: ByteArray) : VirtualScreenshot
    data object Stale : VirtualScreenshot
    data object Failed : VirtualScreenshot
}
