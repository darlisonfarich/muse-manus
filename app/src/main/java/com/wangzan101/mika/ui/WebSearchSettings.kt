package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.search.WebSearchProvider
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.WebSearchConfig
import com.wangzan101.mika.settings.WebSearchEntry
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** Search providers have been reduced to an explicitly configured SearXNG instance. */
internal object WebSearchCopy {
    fun name(p: WebSearchProvider): String = "SearXNG"
    fun note(p: WebSearchProvider): String = tr(R.string.web_search_note_searxng)
    fun endpointLabel(): String = tr(R.string.web_search_searxng_url)
    const val ENDPOINT_PLACEHOLDER = "https://searx.example.org"
    fun problem(p: WebSearchProvider, why: WebSearchProvider.Problem): String =
        tr(R.string.web_search_invalid_endpoint)
    fun value(config: WebSearchConfig): String = if (config.enabled) name(config.provider) else tr(R.string.state_off)
}

/** No You/Exa/Jina API keys or provider choices are offered here. */
@Composable
fun WebSearchDialog(initial: WebSearchConfig, onDismiss: () -> Unit, onSave: (WebSearchConfig) -> Unit) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var endpoint by remember { mutableStateOf(initial.current.endpoint) }
    var tried by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val config = WebSearchConfig(enabled, WebSearchProvider.SearXNG,
        mapOf(WebSearchProvider.SearXNG to WebSearchEntry(endpoint)))
    val invalid = config.problem == WebSearchProvider.Problem.Endpoint
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.web_search_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.web_search_enabled), Modifier.weight(1f))
                    MikaSwitch(enabled, { enabled = it }, Modifier.testTag("web-search-enabled"))
                }
                GroupLabel(stringResource(R.string.web_search_provider), Modifier.padding(top = 4.dp))
                Text("SearXNG", Modifier.testTag("web-search-provider-searxng"), style = MikaType.Body)
                Text(WebSearchCopy.note(WebSearchProvider.SearXNG), Modifier.padding(top = 8.dp).testTag("web-search-note"),
                    style = MikaType.Hint, color = MikaTheme.colors.ink3)
                OutlinedTextField(
                    endpoint, { endpoint = it },
                    Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus).testTag("web-search-endpoint"),
                    singleLine = true, isError = invalid && tried,
                    label = { Text(WebSearchCopy.endpointLabel()) },
                    placeholder = { Text(WebSearchCopy.ENDPOINT_PLACEHOLDER) },
                    supportingText = if (invalid && tried) ({ Text(WebSearchCopy.problem(WebSearchProvider.SearXNG,
                        WebSearchProvider.Problem.Endpoint)) }) else null,
                )
            }
        },
        confirmButton = {
            TextButton({
                if (config.problem == null) onSave(config)
                else { tried = true; runCatching { focus.requestFocus() } }
            }, Modifier.testTag("web-search-save")) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
