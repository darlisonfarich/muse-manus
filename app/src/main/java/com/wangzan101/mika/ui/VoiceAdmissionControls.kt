package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.gadget.VoiceAdmissionTest
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * O botão do passo 5.0 na Diagnostics e o registro por estágio logo abaixo dele (estágio, HTTP,
 * tamanho do corpo, ms — nunca texto do servidor). Uma execução por toque; não abre microfone.
 */
@Composable
fun VoiceAdmissionControls() {
    val c = MikaTheme.colors
    val context = LocalContext.current
    val ui by VoiceAdmissionTest.ui.collectAsState()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        MikaButton(
            if (ui.running) "Teste 5.0 rodando…" else "Teste 5.0 — admissão de voz",
            { VoiceAdmissionTest.start(context) },
            Modifier.fillMaxWidth().testTag("voice-50-run"),
            kind = ButtonKind.Tonal,
            enabled = !ui.running,
        )
        if (ui.lines.isNotEmpty() || ui.summary.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).testTag("voice-50-log")) {
                for (line in ui.lines) {
                    Text(line, style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace), color = c.ink2)
                }
                if (ui.summary.isNotEmpty()) {
                    Text(ui.summary, style = MikaType.Caption, color = c.ink, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
