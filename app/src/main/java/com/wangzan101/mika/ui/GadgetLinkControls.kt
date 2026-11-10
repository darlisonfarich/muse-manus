package com.wangzan101.mika.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.darlisonfarich.gadgetlink.GadgetState
import com.wangzan101.mika.gadget.GadgetLink
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.delay

/**
 * Controles do gadget-link (spike) dentro da Diagnostics: abrir/fechar a janela de
 * pareamento BLE e colar o token do SDK. A permissão "Dispositivos próximos" só é
 * pedida no toque em "Abrir" — nunca fora de hora. [reload] recoleta o texto da
 * Diagnostics para o estado do pareamento aparecer nele.
 */
@Composable
fun GadgetLinkControls(reload: () -> Unit) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val phase by GadgetLink.phase.collectAsState()
    val running = phase != GadgetLink.Phase.OFF && phase != GadgetLink.Phase.ERROR && phase != GadgetLink.Phase.PAIRED

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.isNotEmpty() && result.values.all { it }) {
            openIfReady(context, reload)
            return@rememberLauncherForActivityResult
        }
        val activity = context.findActivity()
        val askedForGood = activity != null &&
            GadgetLink.missingPermissions(context).none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (askedForGood) {
            // O sistema não pergunta mais: leva às configurações do app.
            Toast.makeText(context, "Permita \"Dispositivos próximos\" para o Muse Manus e volte.", Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        } else {
            Toast.makeText(context, "Sem \"Dispositivos próximos\" o pareamento não abre.", Toast.LENGTH_LONG).show()
        }
    }

    // O texto da Diagnostics acompanha o pareamento: recoleta a cada mudança de fase e,
    // com a janela aberta, de tempos em tempos (eventos de MTU, conexão etc.).
    LaunchedEffect(phase) { reload() }
    LaunchedEffect(running) {
        while (running) {
            delay(3_000)
            reload()
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Gadget link (spike) — ${phase.label}", style = MikaType.Caption, color = c.ink3)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MikaButton(
                if (running) "Fechar pareamento" else "Abrir pareamento",
                {
                    if (running) {
                        GadgetLink.close(context)
                    } else {
                        val missing = GadgetLink.missingPermissions(context)
                        if (missing.isEmpty()) openIfReady(context, reload) else permissions.launch(missing.toTypedArray())
                    }
                },
                Modifier.weight(1f).testTag("gadget-link-toggle"),
            )
            MikaButton(
                "Colar token SDK",
                {
                    pasteSdkToken(context)
                    reload()
                },
                Modifier.weight(1f).testTag("gadget-link-token"),
                kind = ButtonKind.Tonal,
            )
        }
    }
}

private fun openIfReady(context: Context, reload: () -> Unit) {
    if (!GadgetLink.bluetoothOn(context)) {
        Toast.makeText(context, "Ligue o Bluetooth e toque de novo.", Toast.LENGTH_LONG).show()
        return
    }
    runCatching { GadgetLink.open(context) }.onFailure {
        Toast.makeText(context, "Não deu para abrir: ${it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
    }
    reload()
}

/** Lê o token do SDK da área de transferência (só no toque) e grava no estado do gadget. */
private fun pasteSdkToken(context: Context) {
    val text = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
    val message = if (GadgetState.isValidSdkToken(text)) {
        runCatching { GadgetLink.state(context).saveSdkToken(text) }.fold(
            onSuccess = { "Token do SDK salvo (${GadgetState.sdkTokenHint(text)}…). Vale no próximo pareamento." },
            onFailure = { "Não deu para salvar o token: ${it.javaClass.simpleName}" },
        )
    } else {
        "Copie o token do gadgets.muse.ai (começa com mgst_) e toque de novo."
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
