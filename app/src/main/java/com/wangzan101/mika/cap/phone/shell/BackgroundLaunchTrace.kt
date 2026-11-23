package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.os.SystemClock
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.shell.BackgroundSurvival

/**
 * Remembers whether the last app opening was started while Mika's screen was closed. Android blocks such a start
 * silently (no exception), so the only symptom is that the app never reaches the front; the failure hint uses this to
 * name the cause ([BackgroundSurvival.BACKGROUND_LAUNCH_TOKEN]) instead of leaving a bare "not in front".
 */
object BackgroundLaunchTrace {
    @Volatile private var at: Long? = null

    fun note(context: Context) {
        val visible = (context.applicationContext as? MikaApp)?.foreground?.visible?.value == true
        at = if (visible) null else SystemClock.elapsedRealtime()
    }

    fun suffix(): String = BackgroundSurvival.backgroundLaunchSuffix(at, SystemClock.elapsedRealtime())
}
