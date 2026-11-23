package com.wangzan101.mika.gadget

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import com.wangzan101.mika.core.shell.BackgroundSurvival
import com.wangzan101.mika.core.shell.BackgroundSurvival.SurvivalSetting

/**
 * The user-side switches that decide whether the system lets the link outlive Mika's screen, and the shortcuts that
 * open them. Nothing here changes a setting: the user does that once, on the phone.
 */
object GadgetBackgroundState {
    /**
     * Stock Android's battery-optimization list. HyperOS has its own "no restrictions" switch on top of it that an app
     * cannot read, so "yes" here is necessary, not proof that HyperOS lets the app run.
     */
    fun batteryUnrestricted(context: Context): String = runCatching {
        if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) "yes" else "no"
    }.getOrDefault("unknown")

    fun notificationsAllowed(context: Context): String = runCatching {
        if (context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "yes" else "no"
    }.getOrDefault("unknown")

    /**
     * Opens the best settings screen the phone has for [setting] (HyperOS screen first, stock Android after, the app
     * details page last). Returns the candidate that opened, or null when nothing could be opened. Each attempt is a
     * real `startActivity`: another package's screens are invisible to `resolveActivity` without extra manifest
     * queries, and a screen that is not exported answers with an exception, which simply moves on to the next one.
     */
    fun open(context: Context, setting: SurvivalSetting): BackgroundSurvival.SettingsCandidate? {
        val label = runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("Muse Manus")
        return BackgroundSurvival.firstResolvable(BackgroundSurvival.settingsCandidates(setting, context.packageName, label)) { c ->
            runCatching { context.startActivity(intentFor(context, c)) }.isSuccess
        }
    }

    private fun intentFor(context: Context, c: BackgroundSurvival.SettingsCandidate): Intent {
        val intent = Intent()
        c.action?.let { intent.action = it }
        if (c.packageName != null && c.className != null) intent.component = ComponentName(c.packageName!!, c.className!!)
        else if (c.packageName != null) intent.setPackage(c.packageName)
        if (c.withPackageUri) intent.data = Uri.fromParts("package", context.packageName, null)
        c.extras.forEach { (k, v) -> intent.putExtra(k, v) }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
