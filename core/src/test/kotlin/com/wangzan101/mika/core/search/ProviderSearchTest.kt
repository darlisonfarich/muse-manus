package com.wangzan101.mika.core.search

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SearXNG-only contract: JSON response, failures and safe URLs. */
class ProviderSearchTest {
    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun withServer(response: MockResponse, block: suspend (MockWebServer) -> Unit) = runTest {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try { block(server) } finally { server.shutdown() }
    }

    private val client = OkHttpClient()

    // ---------- SearXNG ----------

    @Test fun searxngIsAJsonGetOnTheInstance() = withServer(ok("""{"results":[
        {"title":"T1","url":"https://a.example","content":"C1","engine":"bing"},
        {"title":"T2","url":"https://b.example","content":"C2"},
        {"title":"T3","url":"https://c.example","content":"C3"}]}""")) { server ->
        val result = SearxngSearch(server.url("/searx/"), client).search("安卓", 2)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/searx/search?q=%E5%AE%89%E5%8D%93&format=json", request.path)
        assertEquals(listOf(WebSearchHit("T1", "https://a.example", "C1"), WebSearchHit("T2", "https://b.example", "C2")), (result as WebSearchResult.Success).hits)
    }

    @Test fun searxngAcceptsAnAddressThatAlreadyEndsInSearch() = withServer(ok("""{"results":[]}""")) { server ->
        SearxngSearch(server.url("/search"), client).search("q", 5)
        assertEquals("/search?q=q&format=json", server.takeRequest().path)
    }

    @Test fun searxng403MeansJsonIsOffNotABadKey() = withServer(MockResponse().setResponseCode(403).setBody("Forbidden")) { server ->
        val failure = SearxngSearch(server.url("/"), client).search("q", 5) as WebSearchResult.Failure
        assertEquals("json_disabled", failure.code)
        assertTrue(failure.message.contains("search.formats"))
    }

    // ---------- 共用 ----------

    @Test fun providersNeedTheRightFields() {
        assertEquals(WebSearchProvider.Problem.Endpoint, WebSearchProvider.SearXNG.problem("not a url", ""))
        assertEquals(null, WebSearchProvider.SearXNG.problem("https://searx.example.org", ""))
    }

    @Test fun onlyPlainHttpAddressesAreAccepted() {
        assertEquals(null, WebSearch.parseEndpoint(""))
        assertEquals(null, WebSearch.parseEndpoint("ftp://example.com/search"))
        assertEquals(null, WebSearch.parseEndpoint("https://user:pass@example.com/search"))
        assertEquals(null, WebSearch.parseEndpoint("https://:pass@example.com/search"))
        assertEquals(null, WebSearch.parseEndpoint("https://example.com/search?key=value"))
        assertTrue(WebSearch.parseEndpoint("http://127.0.0.1:1234/") != null)
    }
}
