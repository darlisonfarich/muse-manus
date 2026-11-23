package com.wangzan101.mika.voice.wake

import android.content.Context
import org.json.JSONArray

class SamWakeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sam_wake", Context.MODE_PRIVATE)
    fun phrases(): List<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_PHRASES, null) ?: DEFAULTS)
        (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotEmpty).distinct()
    }.getOrDefault(DEFAULT_PHRASES)
    fun setPhrases(values: List<String>) {
        val clean = values.map(String::trim).filter(String::isNotEmpty).distinct()
        prefs.edit().putString(KEY_PHRASES, JSONArray(clean).toString()).apply()
    }
    fun enabled() = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    companion object {
        const val KEY_PHRASES = "phrases"
        private const val KEY_ENABLED = "enabled"
        val DEFAULT_PHRASES = listOf("Samantha", "Oi Sam", "Ei Sam", "Acorda Sam", "Sam vem cá")
        private val DEFAULTS = JSONArray(DEFAULT_PHRASES).toString()
    }
}
