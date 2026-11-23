package com.wangzan101.mika.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.voice.wake.SamWakeService
import com.wangzan101.mika.voice.wake.SamWakeSettings

@Composable
fun SamWakeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config = remember(context) { SamWakeSettings(context) }
    var phrases by remember { mutableStateOf(config.phrases()) }
    var enabled by remember { mutableStateOf(config.enabled()) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { config.setEnabled(true); enabled = true; SamWakeService.start(context) }
    }
    PageScaffold("Chamar a Sam", onBack) {
        Text("Frases reconhecidas localmente no aparelho. Ao detectar uma delas, o app abre o assistente padrão e tenta tocar no botão de ligação.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(if (enabled) "Ouvinte ligado" else "Ouvinte desligado", style = MaterialTheme.typography.titleMedium)
                Text(if (enabled) "Microfone em uso pela escuta local" else "Microfone liberado")
            }
            Switch(checked = enabled, onCheckedChange = { turnOn ->
                if (turnOn && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    enabled = turnOn; config.setEnabled(turnOn)
                    if (turnOn) SamWakeService.start(context) else SamWakeService.stop(context)
                }
            })
        }
        Text("Frases de ativação", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(phrases, key = { it }) { phrase ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(phrase, Modifier.weight(1f).padding(vertical = 12.dp))
                    TextButton(onClick = {
                        phrases = phrases - phrase
                        config.setPhrases(phrases)
                    }, enabled = phrases.size > 1) { Text("Remover") }
                }
            }
        }
        OutlinedButton(onClick = { draft = ""; adding = true }, Modifier.fillMaxWidth()) { Text("Adicionar frase") }
        Button(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            } else SamWakeService.test(context)
        }, Modifier.fillMaxWidth()) { Text("Testar agora") }
        Text("O teste abre o assistente padrão e procura o botão de ligação por até 3 segundos. O Modo Voz do Manus é independente.", style = MaterialTheme.typography.bodySmall)
    }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("Adicionar frase") },
        text = { OutlinedTextField(draft, { draft = it }, label = { Text("Frase") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val value = draft.trim()
            if (value.isNotEmpty() && value !in phrases) { phrases = phrases + value; config.setPhrases(phrases) }
            adding = false
        }) { Text("Adicionar") } },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancelar") } },
    )
}
