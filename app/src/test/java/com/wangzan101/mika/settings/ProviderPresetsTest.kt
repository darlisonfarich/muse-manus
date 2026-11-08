package com.wangzan101.mika.settings

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.ui.AddForm
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Only the explicit Custom OpenAI-compatible setup is offered. */
class ProviderPresetsTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun onlyCustomOpenAiCompatiblePresetRemains() {
        val presets = ProviderPresets.all
        assertEquals(1, presets.size)
        assertEquals(ProviderPresets.custom, presets.single())
        assertEquals(ProviderConfig.Kind.OpenAiCompat, presets.single().kind)
        assertTrue(presets.single().baseUrl.isEmpty())
        assertTrue(presets.single().familyTokens.isEmpty())
    }

    @Test fun noExternalPresetHostIsOffered() {
        assertNull(ProviderPresets.forHost("https://api.openai.com/v1"))
        assertNull(ProviderPresets.forHost("https://api.anthropic.com"))
        assertNull(ProviderPresets.forBaseUrl("https://api.deepseek.com"))
    }

    @Test fun customUrlIsEditableAndDoesNotRequireAnApiKey() {
        val form = AddForm.of(ProviderPresets.custom).copy(baseUrl = "http://192.168.1.20:11434/v1")
        assertTrue(form.urlEditable)
        assertFalse(form.keyRequired)
        assertEquals(ProviderConfig.Kind.OpenAiCompat, form.kind)
    }
}
