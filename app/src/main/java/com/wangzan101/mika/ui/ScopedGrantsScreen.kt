package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.gate.ScopedActionClass
import com.wangzan101.mika.core.gate.ScopedGrantRegistry
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import kotlinx.coroutines.delay

/**
 * LOCAL-only management. These scopes do NOT replace any one-use approval
 * or enable remote writes. Account is an opaque label; the executor may use it
 * only after a separate, trusted account-identity binding is implemented.
 */
@Composable
fun ScopedGrantsScreen(registry: ScopedGrantRegistry, onBack: () -> Unit) {
    var app by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(ScopedActionClass.Navigate) }
    var duration by remember { mutableIntStateOf(5) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            revision++
        }
    }
    val current = remember(revision) { registry.active() }
    PageScaffold(stringResource(R.string.mcp_scoped_title), onBack) {
        GroupLabel(stringResource(R.string.mcp_scoped_new))
        GroupCard {
            InputRow(stringResource(R.string.mcp_scoped_package), app,
                { app = it.take(160) }, "com.example.app", first = true)
            InputRow(stringResource(R.string.mcp_scoped_account), account,
                { account = it.take(128) }, stringResource(R.string.mcp_scoped_account_hint))
        }
        GroupCard {
            RadioRow(stringResource(R.string.mcp_scoped_navigate),
                selected == ScopedActionClass.Navigate,
                { selected = ScopedActionClass.Navigate }, first = true)
            RadioRow(stringResource(R.string.mcp_scoped_edit),
                selected == ScopedActionClass.Edit,
                { selected = ScopedActionClass.Edit })
        }
        GroupCard {
            RadioRow(stringResource(R.string.mcp_scoped_5min), duration == 5, { duration = 5 }, first = true)
            RadioRow(stringResource(R.string.mcp_scoped_15min), duration == 15, { duration = 15 })
        }
        TextButton(onClick = {
            val result = runCatching {
                registry.issueFromLocalUi(app, account, selected, duration * 60_000L)
            }
            message = when {
                result.isFailure -> "audit_failed"
                result.getOrNull() == null -> "invalid"
                else -> "created"
            }
            revision++
        }) {
            Text(stringResource(R.string.mcp_scoped_issue))
        }
        if (message.isNotEmpty()) {
            Text(stringResource(when (message) {
                "created" -> R.string.mcp_scoped_created
                "audit_failed" -> R.string.mcp_scoped_audit_failed
                else -> R.string.mcp_scoped_invalid
            }), Modifier.padding(12.dp))
        }
        FooterNote(stringResource(R.string.mcp_scoped_warning))
        GroupLabel(stringResource(R.string.mcp_scoped_active))
        GroupCard {
            if (current.isEmpty()) {
                ListRow(null, stringResource(R.string.mcp_scoped_empty), first = true)
            }
            current.forEachIndexed { index, grant ->
                ListRow(null, grant.app + " • " + grant.account,
                    subtitle = grant.actionClass.name + " • " +
                        ((grant.expiresAtMs - grant.issuedAtMs) / 60_000L).toString() + " min",
                    first = index == 0) {
                    TextButton(onClick = {
                        registry.revoke(grant.id)
                        revision++
                    }) { Text(stringResource(R.string.mcp_scoped_revoke)) }
                }
            }
        }
        if (current.isNotEmpty()) {
            TextButton(onClick = {
                registry.revokeAll()
                revision++
            }) { Text(stringResource(R.string.mcp_scoped_revoke_all)) }
        }
    }
}
