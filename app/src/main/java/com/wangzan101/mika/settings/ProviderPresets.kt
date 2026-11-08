package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.PromptCaching
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 添加模型时的常用服务商模板:只预填接口类型、Base URL 和能力的初始勾选,
 * 模型名和 Key 由用户填,能力也可以改。
 * [sessionHeader] 是服务商要求放对话会话号的请求头名(见 [com.wangzan101.mika.core.model.ModelSession]);不要的为 null。
 * [selfHosted] 是用户自己机器上跑的服务:[baseUrl] 只是本机的默认端口,表单里让用户改成那台机器的地址,Key 可以不填。
 * [familyTokens] 是这家自己的模型名里都有的词(小写):Key 下列出的还有别家的模型时,默认先试自家的(见 [ModelOptions.candidates]);
 * 托管多家模型的、自己跑的不给。
 */
data class ProviderPreset(
    val label: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val capabilities: Capabilities,
    val sessionHeader: String? = null,
    val selfHosted: Boolean = false,
    val familyTokens: List<String> = emptyList(),
    /**
     * 这家官方文档写明的思考档位:列表接口不回档位时(OpenAI 兼容协议大多如此),界面上按它给可选档。
     * 只填官方文档确认过的值,不猜;照服务商写明的顺序。
     */
    val reasoningLevels: List<String> = emptyList(),
)

object ProviderPresets {
    private fun caps(vision: Boolean = false, context: Int = 64_000, caching: PromptCaching = PromptCaching.Implicit) =
        Capabilities(toolCalls = true, vision = vision, contextTokens = context, reasoning = false, promptCaching = caching)

    /** Base URL 对得上的模板(添加时选了别的模板、或测试里直接给地址时,按地址补上能力的初始值);没有为 null。 */
    fun forBaseUrl(url: String): ProviderPreset? = all.firstOrNull { it.baseUrl.isNotEmpty() && url.trimEnd('/') == it.baseUrl.trimEnd('/') }

    /**
     * 主机名和哪个服务商模板的地址一样(路径可以不同):自定义填了某家的官方地址时,建议改用那个模板。
     * 自己跑的服务(本机地址)不算。认不出地址或没有对得上的为 null。
     */
    fun forHost(url: String): ProviderPreset? {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return null
        // 智谱的标准 API 和 Coding Plan 同主机不同路径；共同路径更多的是更具体的预设。
        return all.filter { !it.selfHosted && it.baseUrl.isNotEmpty() && it.baseUrl.toHttpUrlOrNull()?.host == host }
            .maxByOrNull { pathOverlap(it.baseUrl, url) }
    }

    val all: List<ProviderPreset> = listOf(
        ProviderPreset("自定义", ProviderConfig.Kind.OpenAiCompat, "", caps()),
    )

    val custom: ProviderPreset get() = all.last()

    /** 同主机有多家预设时，按逐段路径相同的长度挑更贴近的一个；根地址并列时保持预设顺序。 */
    private fun pathOverlap(presetUrl: String, inputUrl: String): Int {
        val preset = presetUrl.trimEnd('/').toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() } ?: return 0
        val input = inputUrl.trim().toHttpUrlOrNull()?.pathSegments?.filter { it.isNotBlank() } ?: return 0
        return preset.indices.count { preset[it] == input.getOrNull(it) }
    }
}
