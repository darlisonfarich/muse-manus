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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.darlisonfarich.gadgetlink.GadgetState
import com.wangzan101.mika.core.shell.BackgroundSurvival
import com.wangzan101.mika.gadget.GadgetBackgroundState
import com.wangzan101.mika.gadget.GadgetLink
import com.wangzan101.mika.gadget.GadgetLinkCommands
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
    val linkState by GadgetLink.link.collectAsState()
    val linking = linkState != null

    // A conexão com o Muse é internet pura (serviço specialUse): não pede "Dispositivos próximos"; só o pareamento BLE pede.

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
    LaunchedEffect(linkState) { reload() }
    LaunchedEffect(running, linking) {
        while (running || linking) {
            delay(3_000)
            reload()
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Gadget link (spike) — ${phase.label}", style = MikaType.Caption, color = c.ink3)
        Text("Muse — ${GadgetLink.linkLabel(linkState, GadgetLink.linkDetail)}", style = MikaType.Caption, color = c.ink3)
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
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MikaButton(
                if (linking) "Desconectar do Muse" else "Conectar ao Muse",
                {
                    if (linking) {
                        GadgetLink.disconnect(context)
                        reload()
                    } else {
                        connectNow(context, reload)
                    }
                },
                Modifier.weight(1f).testTag("gadget-link-connect"),
                kind = ButtonKind.Tonal,
            )
            MikaButton(
                "Copiar registro do link",
                { copyLinkLog(context) },
                Modifier.weight(1f).testTag("gadget-link-copy-log"),
                kind = ButtonKind.Tonal,
            )
        }
        // Medição da Etapa 1 do Gadget Link: anuncia também os 29 stubs phone.* (sem efeito nenhum, todos
        // devolvem not_implemented). Desligado por padrão; vale a partir da próxima conexão.
        // A linha mostra o que o link.register da conexão atual REALMENTE anunciou (contado do catálogo
        // registrado); a preferência só aparece como pendente quando ainda não vale.
        var stubs by remember { mutableStateOf(GadgetLinkCommands.measureStubs(context)) }
        val announced by GadgetLink.announced.collectAsState()
        val shown = announced
        val pendingNote = when {
            shown == null -> if (stubs) " · medição ligada (vale na próxima conexão)" else ""
            stubs != (shown.stubs > 0) -> " · medição ${if (stubs) "ligada" else "desligada"} (vale na próxima conexão)"
            else -> ""
        }
        Text(
            (if (shown == null) "Comandos: nada anunciado" else "Comandos anunciados: ${shown.commands}" + (if (shown.stubs > 0) " (${shown.stubs} stubs phone.*)" else " (linktest.*)") + ", registro ${shown.registerBytes} B") + pendingNote,
            style = MikaType.Caption, color = c.ink3, modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MikaButton(
                if (stubs) "Medição: desligar stubs" else "Medição: anunciar stubs",
                {
                    stubs = !stubs
                    GadgetLinkCommands.setMeasureStubs(context, stubs)
                    Toast.makeText(context, "Vale na próxima conexão com o Muse.", Toast.LENGTH_SHORT).show()
                },
                Modifier.weight(1f).testTag("gadget-link-stubs"),
                kind = ButtonKind.Tonal,
            )
        }
        // Segundo plano: o que o sistema precisa deixar ligado para o link seguir vivo com o Manus fechado. O usuário faz
        // isto UMA vez; os botões só abrem a tela certa (HyperOS primeiro, Android puro depois).
        Text(
            "Segundo plano — bateria sem otimização: ${GadgetBackgroundState.batteryUnrestricted(context)} · notificações: ${GadgetBackgroundState.notificationsAllowed(context)}",
            style = MikaType.Caption, color = c.ink3, modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MikaButton(
                "Autostart",
                { openBackgroundSetting(context, BackgroundSurvival.SurvivalSetting.Autostart) },
                Modifier.weight(1f).testTag("gadget-bg-autostart"),
                kind = ButtonKind.Tonal,
            )
            MikaButton(
                "Bateria",
                { openBackgroundSetting(context, BackgroundSurvival.SurvivalSetting.BatteryUnrestricted) },
                Modifier.weight(1f).testTag("gadget-bg-battery"),
                kind = ButtonKind.Tonal,
            )
            MikaButton(
                "Detalhes do app",
                { openBackgroundSetting(context, BackgroundSurvival.SurvivalSetting.AppDetails) },
                Modifier.weight(1f).testTag("gadget-bg-details"),
                kind = ButtonKind.Tonal,
            )
        }
        VoiceAdmissionControls()
    }
}

private fun openBackgroundSetting(context: Context, setting: BackgroundSurvival.SurvivalSetting) {
    if (GadgetBackgroundState.open(context, setting) == null) {
        Toast.makeText(context, "Não consegui abrir essa tela; use Detalhes do app.", Toast.LENGTH_LONG).show()
    }
}

private fun connectNow(context: Context, reload: () -> Unit) {
    runCatching { GadgetLink.connect(context) }.onFailure {
        Toast.makeText(context, "Não deu para conectar: ${it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
    }
    reload()
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

/** Copia o registro do link (versão, estado do ajudante e eventos) para colar numa conversa. */
private fun copyLinkLog(context: Context) {
    val text = runCatching { GadgetLink.copyableLog(context) }.getOrElse {
        Toast.makeText(context, "Não deu para montar o registro: ${it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        return
    }
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(android.content.ClipData.newPlainText("Registro do Gadget Link", text))
    Toast.makeText(context, "Registro do link copiado.", Toast.LENGTH_SHORT).show()
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
