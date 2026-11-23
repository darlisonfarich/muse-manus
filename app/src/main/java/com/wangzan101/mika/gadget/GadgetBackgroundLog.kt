package com.wangzan101.mika.gadget

import android.content.Context
import com.wangzan101.mika.core.shell.BackgroundSurvival
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lifecycle of the link service that must survive the process: boot resume, sticky restarts, the task being swiped
 * away, a foreground start the system refused. The in-memory [GadgetLink] log dies with the process, which is exactly
 * when these facts matter, so they are kept in preferences (newest 30 lines, fixed vocabulary, see
 * [BackgroundSurvival.eventLine]). Shown in the copyable link log.
 */
object GadgetBackgroundLog {
    private const val PREFS = "gadget_link_lifecycle"
    private const val KEY = "events"
    private val SEPARATOR = "\n"

    @Synchronized
    fun record(context: Context, event: String, detail: String? = null) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val old = prefs.getString(KEY, "").orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }
            val next = BackgroundSurvival.appendEvent(old, BackgroundSurvival.eventLine(stamp, event, detail))
            prefs.edit().putString(KEY, next.joinToString(SEPARATOR)).apply()
        }
    }

    @Synchronized
    fun lines(context: Context): List<String> = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}
