package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelProtocol

/**
 * Reasoning effort for a Custom Chat Completions connection.
 * Do not assume levels for a model or resurrect old vendor-specific defaults:
 * only server-declared levels are offered, and absent metadata means no override.
 */
data class ReasoningOptions(val levels: List<String>, val fromProvider: Boolean) {
    companion object {
        fun of(config: ProviderConfig, listed: ListedModel?): ReasoningOptions? {
            if (config.kind != ProviderConfig.Kind.OpenAiCompat) return null
            if (listed?.endpoint != null && listed.endpoint.protocol != ModelProtocol.ChatCompletions) return null
            if (listed?.protocols != null && ModelProtocol.ChatCompletions !in listed.protocols) return null
            listed?.reasoningEfforts?.let { return ReasoningOptions(it, fromProvider = true) }
            if (listed?.reasoning == false) return ReasoningOptions(emptyList(), fromProvider = true)
            return null
        }
    }
}
