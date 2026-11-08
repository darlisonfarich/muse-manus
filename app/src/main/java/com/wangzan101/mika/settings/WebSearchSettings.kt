package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.search.SearxngSearch
import com.wangzan101.mika.core.search.WebSearch
import com.wangzan101.mika.core.search.WebSearchProvider

/** Legacy apiKey property kept only for source/data compatibility; never loaded or stored. */
data class WebSearchEntry(val endpoint: String = "", val apiKey: String = "")

/** No user-selected managed provider is silently replaced with a different network service. */
data class WebSearchConfig(
    val enabled: Boolean = false,
    val provider: WebSearchProvider = WebSearchProvider.SearXNG,
    val entries: Map<WebSearchProvider, WebSearchEntry> = emptyMap(),
) {
    val current: WebSearchEntry get() = entries[provider] ?: WebSearchEntry()
    val problem: WebSearchProvider.Problem? get() =
        if (enabled) provider.problem(current.endpoint, "") else null
}

/** Managed external search credentials are purged on read and never passed to a network client. */
class WebSearchSettings(
    private val prefs: SharedPreferences,
    @Suppress("UNUSED_PARAMETER") private val cipher: KeyCipher = KeystoreKeyCipher("mika-web-search-key"),
) {
    fun get(): WebSearchConfig {
        clearRemovedCredentials()
        val saved = WebSearchProvider.of(prefs.getString(PROVIDER, null))
        val known = saved == WebSearchProvider.SearXNG
        val endpoint = if (known) prefs.getString(SEARXNG_ENDPOINT, null).orEmpty() else ""
        return WebSearchConfig(
            enabled = known && prefs.getBoolean(ENABLED, false),
            provider = WebSearchProvider.SearXNG,
            entries = if (known) mapOf(WebSearchProvider.SearXNG to WebSearchEntry(endpoint)) else emptyMap(),
        )
    }

    @Synchronized fun set(config: WebSearchConfig) {
        require(config.problem == null) { "Invalid web search configuration: ${config.problem}" }
        val edit = prefs.edit()
            .putBoolean(ENABLED, config.enabled)
            .putString(PROVIDER, WebSearchProvider.SearXNG.wire)
            .putString(SEARXNG_ENDPOINT, config.current.endpoint.trim())
        for (key in DEPRECATED_KEYS) edit.remove(key)
        check(edit.commit()) { "Cannot save web search settings" }
    }

    /** Only an explicitly enabled and valid SearXNG instance can send a query. */
    fun service(): WebSearch? {
        val config = get()
        if (!config.enabled || config.problem != null) return null
        val url = WebSearch.parseEndpoint(config.current.endpoint) ?: return null
        return SearxngSearch(url)
    }

    @Synchronized private fun clearRemovedCredentials() {
        if (DEPRECATED_KEYS.none(prefs::contains)) return
        val editor = prefs.edit()
        for (key in DEPRECATED_KEYS) editor.remove(key)
        check(editor.commit()) { "Cannot clear removed web-search credentials" }
    }

    companion object {
        private const val PREFS = "web_search"
        private const val ENABLED = "enabled"
        private const val PROVIDER = "provider"
        private const val SEARXNG_ENDPOINT = "endpoint_searxng"
        /** Include both provider-specific secrets and pre-provider migration credentials. */
        private val DEPRECATED_KEYS = listOf(
            "endpoint", "api_key_encrypted",
            "endpoint_you", "endpoint_jina", "endpoint_exa",
            "api_key_encrypted_you", "api_key_encrypted_jina", "api_key_encrypted_exa",
        )

        fun get(context: Context) = WebSearchSettings(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
