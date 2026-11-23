package com.wangzan101.mika.device

import android.content.Context
import android.os.PowerManager

/** Wakes the display briefly without changing keyguard state or any radio setting. */
internal object ScreenWakeup {
    @Suppress("DEPRECATION")
    fun wake(context: Context): Boolean = runCatching {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Mika:remote-wake",
        )
        lock.setReferenceCounted(false)
        lock.acquire(3_000L)
        true
    }.getOrDefault(false)
}
