package com.wangzan101.mika.core.search

/**
 * Optional, explicitly user-configured self-hosted web search. Managed You/Exa/Jina
 * integrations were removed: search cannot silently forward queries or API keys.
 * The embedded browser remains available independently.
 */
enum class WebSearchProvider(val wire: String) {
    SearXNG("searxng");

    val needsEndpoint: Boolean get() = true
    val takesKey: Boolean get() = false
    val keyRequired: Boolean get() = false

    fun problem(endpoint: String, apiKey: String): Problem? =
        if (WebSearch.parseEndpoint(endpoint) == null) Problem.Endpoint else null

    enum class Problem { Endpoint, Key }

    companion object {
        /** Unknown or previously removed provider names are NOT mapped to an active provider. */
        fun of(wire: String?): WebSearchProvider? = entries.firstOrNull { it.wire == wire }
    }
}
