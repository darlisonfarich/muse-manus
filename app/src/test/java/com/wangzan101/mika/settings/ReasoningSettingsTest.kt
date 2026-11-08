package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/** Custom-only runtime: verify settings persistence, supported metadata and Chat Completions wire format. */
class ReasoningSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caps = Capabilities(toolCalls = true, vision = true, contextTokens = 100_000, reasoning = true)
    private val setups = mutableListOf<ClientSetup>()

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, setup ->
        setups += setup
        object : ChatModel {
            override val name = cfg.model
            override val capabilities = cfg.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                error("not called")
        }
    })

    private fun connected(s: ProviderStore) =
        s.connect("Custom", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "k",
            listOf(ListedModel("m", reasoningEfforts = listOf("low", "medium", "high")),
                ListedModel("other", reasoningEfforts = emptyList())))

    @Test fun sameModelCanHaveDifferentEffortPerRoleAndSurvivesReload() {
        val s = store()
        val p = connected(s)
        ModelRole.entries.forEach { assertTrue(s.use(it, p.id, "m", caps)) }
        assertEquals(1, s.list().size)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        assertTrue(s.setReasoningEffort(ModelRole.Vision, "medium"))
        assertTrue(s.setReasoningEffort(ModelRole.Light, "low"))
        val reloaded = store()
        ModelRole.entries.forEach { reloaded.modelFor(it) }
        assertEquals(listOf("high", "medium", "low"), setups.map { it.reasoningEffort })
        assertEquals(listOf("low", "medium", "high"), reloaded.provider(p.id)!!.models!!.first().reasoningEfforts)
        assertEquals(emptyList<String>(), reloaded.provider(p.id)!!.models!![1].reasoningEfforts)
        assertTrue(reloaded.setReasoningEffort(ModelRole.Light, null))
        assertNull(store().roles.value.reasoningEffort(ModelRole.Light))
        assertEquals("high", store().roles.value.reasoningEffort(ModelRole.Main))
    }

    @Test fun followingMainInheritsEffortAndChangingModelClearsOnlyItsOwnChoice() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Main, "high")
        assertFalse(s.setReasoningEffort(ModelRole.Light, "low"))
        s.modelFor(ModelRole.Light)
        assertEquals("high", setups.last().reasoningEffort)
        s.use(ModelRole.Light, p.id, "m", caps)
        s.setReasoningEffort(ModelRole.Light, "low")
        s.use(ModelRole.Main, p.id, "other", caps)
        assertNull(s.roles.value.reasoningEffort(ModelRole.Main))
        assertEquals("low", s.roles.value.reasoningEffort(ModelRole.Light))
        s.choose(ModelRole.Light, RoleChoice.SameAsMain)
        assertNull(s.roles.value.reasoningEffort(ModelRole.Light))
        assertFalse(s.roles.value.reasoningEfforts.containsKey(ModelRole.Light))
    }

    @Test fun unsupportedLevelsAreRejectedAndOldSettingsKeepServerDefault() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "max"))
        assertNull(store().roles.value.reasoningEffort(ModelRole.Main))
        val saved = Json.parseToJsonElement(tmp.root.resolve("providers.json").readText()).jsonObject
        assertFalse(saved["uses"]!!.jsonObject["Main"]!!.jsonObject.containsKey("reasoningEffort"))
        store().modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
        s.use(ModelRole.Main, p.id, "other", caps)
        assertFalse(s.setReasoningEffort(ModelRole.Main, "low"))
        assertTrue(s.setReasoningEffort(ModelRole.Main, null))
    }

    @Test fun providerReportedLevelsAreUsedOnlyForTheCustomProvider() {
        val cfg = ProviderConfig("c", ProviderConfig.Kind.OpenAiCompat, "https://custom.example/v1", "m", caps)
        assertNull(ReasoningOptions.of(cfg, null))
        val listed = ListedModel("m", endpoint = ModelEndpoint(ModelProtocol.ChatCompletions, "https://custom.example/v1"),
            reasoningEfforts = listOf("low", "high"))
        assertEquals(listOf("low", "high"), ReasoningOptions.of(cfg, listed)!!.levels)
        assertTrue(ReasoningOptions.of(cfg, listed)!!.fromProvider)
        assertEquals(emptyList<String>(), ReasoningOptions.of(cfg, listed.copy(reasoningEfforts = emptyList()))!!.levels)
        assertNull(ReasoningOptions.of(cfg, ListedModel("m", reasoning = true)))
        assertEquals(emptyList<String>(), ReasoningOptions.of(cfg, ListedModel("m", reasoning = false))!!.levels)
    }

    @Test fun customThinkingMetadataSurvivesReloadAndLegacyListsHaveNoOptions() {
        val s = store()
        val listed = ListedModel("m", reasoning = true, reasoningEfforts = listOf("none", "high"),
            thinkingTypes = listOf("adaptive", "disabled"))
        val p = s.connect("Custom", ProviderConfig.Kind.OpenAiCompat, "https://custom.example/v1", "k", listOf(listed))
        s.use(ModelRole.Main, p.id, "m", caps)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        val reloaded = store()
        assertEquals(listed, reloaded.provider(p.id)!!.models!!.single())
        reloaded.modelFor(ModelRole.Main)
        assertEquals(listOf("adaptive", "disabled"), setups.last().thinkingTypes)
        assertEquals("high", setups.last().reasoningEffort)
        val file = tmp.root.resolve("providers.json")
        file.writeText(file.readText().replace(Regex(""","reasoningEfforts":\[[^]]*]"""), "")
            .replace(Regex(""","thinkingTypes":\[[^]]*]"""), ""))
        val old = store()
        val oldListed = old.provider(p.id)!!.models!!.single()
        assertNull(oldListed.reasoningEfforts)
        assertNull(oldListed.thinkingTypes)
        assertNull(ReasoningOptions.of(old.configFor(ModelRole.Main)!!, oldListed))
    }

    @Test fun metadataRefreshDropsUnavailableEffortWithoutChangingTheMainModel() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
        s.connect("Custom", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "k",
            listOf(ListedModel("m", reasoning = false)))
        val reloaded = store()
        assertEquals("m", reloaded.configFor(ModelRole.Main)?.model)
        assertNull(reloaded.roles.value.reasoningEffort(ModelRole.Main))
        assertFalse(reloaded.setReasoningEffort(ModelRole.Main, "high"))
        assertTrue(reloaded.setReasoningEffort(ModelRole.Main, null))
        reloaded.modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
    }

    @Test fun unsupportedSavedEffortCannotBeReplayedAfterReload() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m", caps)
        assertTrue(s.setReasoningEffort(ModelRole.Main, "low"))
        val file = tmp.root.resolve("providers.json")
        file.writeText(file.readText().replace("\"reasoningEffort\":\"low\"", "\"reasoningEffort\":\"ultra\""))
        val reloaded = store()
        assertNull(reloaded.roles.value.reasoningEffort(ModelRole.Main))
        reloaded.modelFor(ModelRole.Main)
        assertNull(setups.last().reasoningEffort)
    }

    @Test fun savedRoleEffortReachesRealCustomChatCompletionsAdapter() = runTest {
        MockWebServer().use { server ->
            server.start()
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key })
            val p = s.connect("Custom", ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(), "k",
                listOf(ListedModel("m", reasoningEfforts = listOf("high"))))
            s.use(ModelRole.Main, p.id, "m", caps)
            assertTrue(s.setReasoningEffort(ModelRole.Main, "high"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
            s.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("output_config"))
            assertFalse(body.containsKey("generationConfig"))
        }
    }
}
