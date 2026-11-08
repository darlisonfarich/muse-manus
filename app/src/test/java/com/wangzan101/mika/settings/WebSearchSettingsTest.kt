package com.wangzan101.mika.settings

import com.wangzan101.mika.core.search.SearxngSearch
import com.wangzan101.mika.core.search.WebSearchProvider
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchSettingsTest {
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }
    private fun settings(prefs: MemoryPrefs = MemoryPrefs()) = WebSearchSettings(prefs, cipher)
    private fun searxng(url: String = "https://searx.example.org") =
        WebSearchConfig(true, WebSearchProvider.SearXNG,
            mapOf(WebSearchProvider.SearXNG to WebSearchEntry(url)))

    @Test fun freshInstallNeverStartsAnExternalSearch() {
        val config = settings().get()
        assertFalse(config.enabled)
        assertEquals(WebSearchProvider.SearXNG, config.provider)
        assertNull(settings().service())
    }

    @Test fun searxngRequiresExplicitValidAddress() {
        val prefs = MemoryPrefs()
        settings(prefs).set(searxng())
        assertTrue(settings(prefs).service() is SearxngSearch)
        assertEquals("https://searx.example.org", settings(prefs).get().current.endpoint)
        assertEquals("searxng", prefs.getString("provider", null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun enabledSearxngNeedsAnAddress() {
        settings().set(searxng(""))
    }

    @Test fun removedProvidersAreNotMappedOntoAnotherEndpoint() {
        for (legacy in listOf("you", "jina", "exa", "gone")) {
            val prefs = MemoryPrefs()
            prefs.edit().putBoolean("enabled", true).putString("provider", legacy)
                .putString("endpoint_searxng", "https://searx.example.org").commit()
            val config = settings(prefs).get()
            assertFalse(legacy, config.enabled)
            assertEquals(WebSearchProvider.SearXNG, config.provider)
            assertNull(legacy, settings(prefs).service())
        }
    }

    @Test fun migratedCredentialsArePurgedWithoutAccessingLegacyNetwork() {
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("enabled", true).putString("provider", "jina")
            .putString("api_key_encrypted_jina", "encrypted-secret")
            .putString("api_key_encrypted_exa", "another-secret")
            .putString("api_key_encrypted_you", "old-secret")
            .putString("api_key_encrypted", "old-legacy-secret")
            .putString("endpoint", "https://s.jina.ai")
            .putString("endpoint_exa", "https://api.exa.ai").commit()
        val config = settings(prefs).get()
        assertFalse(config.enabled)
        assertNull(settings(prefs).service())
        for (key in listOf("api_key_encrypted_jina", "api_key_encrypted_exa",
            "api_key_encrypted_you", "api_key_encrypted", "endpoint", "endpoint_exa"))
            assertFalse("Stale credential: $key", prefs.contains(key))
        settings(prefs).set(searxng())
        assertTrue(settings(prefs).service() is SearxngSearch)
    }

    @Test fun disabledSavedSearxngNeverSendsQueries() {
        val prefs = MemoryPrefs()
        settings(prefs).set(searxng().copy(enabled = false))
        assertNull(settings(prefs).service())
    }

    @Test fun removedProviderNamesAreUnknown() {
        for (legacy in listOf("you", "jina", "exa"))
            assertNull(WebSearchProvider.of(legacy))
    }
}
