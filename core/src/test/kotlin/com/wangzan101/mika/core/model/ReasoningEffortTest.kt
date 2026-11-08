package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ReasoningEffortTest {
    private val caps = Capabilities(toolCalls = true, vision = true, contextTokens = 100_000, reasoning = true)
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
    private fun done() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n")

    @Test fun catalogRetainsLevelsAndDistinguishesAbsentFromEmptyMetadata() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"models":[
                {"slug":"a","supported_reasoning_levels":[{"effort":"high"},"low",{"effort":"high"},{"effort":"future"},{"effort":3},{},3," "]},
                {"slug":"b","supported_reasoning_levels":[]},
                {"slug":"c"},
                {"slug":"d","capabilities":{"reasoning":{"supported":true,"effort":["low","high"]}}}
            ]}"""))
            val models = (ModelCatalog(OkHttpClient()).openAiCompat(server.url("/").toString(), null) as ModelListResult.Ok).models
            assertEquals(listOf("high", "low", "future"), models[0].reasoningEfforts)
            assertEquals(emptyList<String>(), models[1].reasoningEfforts)
            assertNull(models[2].reasoningEfforts)
            assertEquals(listOf("low", "high"), models[3].reasoningEfforts)
        }
    }
}
