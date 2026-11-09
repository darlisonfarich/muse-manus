# Muse Manus — wake word local (voz-manus-008)

## Como ativar
Abra **Configurações → Modo voz → Ativar 'oi Muse' offline** e conceda acesso ao microfone.
A escuta roda somente depois dessa ação (Android foreground service do tipo `microphone`).
A notificação persistente permite desligar a escuta. Na primeira vez há um download do modelo.
Sem permissão ou sem o modelo, a interface expõe o estado; não há reconhecimento remoto.
**A ativação não persiste automaticamente após encerramento do processo ou reinício.**
O usuário pode reativar ao abrir o Modo voz.

## Caminho do áudio
1. Um `AudioRecord` local lê PCM16 mono a 16 kHz e alimenta apenas o Vosk.
2. A gramática restrita é `["oi muse", "[unk]"]`. O gatilho exige resultado
   parcial exato seguido de resultado final exato (janela até 2,5 s).
   Estes resultados são **correlacionados**, não dois classificadores independentes.
3. Um buffer circular em memória de 16.000 amostras (~1 s) mantém o áudio recente
   e é descartado ao disparar; pre-roll ao pedido é assunto da etapa 4.
4. O `AudioRecord` do detector fecha **antes** de começar `VoiceStageRecorder`.
   Um bipe curto avisa que o usuário pode falar o pedido. O mesmo gravador grava WAV
   por até 30 s, terminando 1,3 s após a última fala detectada (limiar simples de energia).
5. Somente **depois do wake** o WAV segue pelo controlador A2 já existente.
   A resposta em MP3 é reproduzida no aparelho. O serviço não lança Activities do fundo.

O detector e o TTS são **half-duplex por construção**: o AudioRecord/Vosk é fechado
ao reconhecer a wake word; só depois da resposta A2 iniciar, o player toca o TTS
com o detector explicitamente em `PausedForPlayback`. O detector só volta a `Listening`
depois do callback de conclusão/erro do MediaPlayer, ou depois de timeout e
parada explícita do player. Uma guarda impede abrir o microfone fora da fase
`Listening`. Assim, a voz sintetizada do Muse não pode ser capturada pelo
detector da wake word. O buffer prévio é descartado; a gravação do pedido começa
após o bipe. O botão de gravação manual fica desativado enquanto a escuta está
ativa para impedir dois gravadores concorrendo pelo microfone.

**AEC (AcousticEchoCanceler) não é a defesa primária e não é requisito desta
implementação.** Poderá ser adicionado como reforço opcional em dispositivos
compatíveis, mas não substitui a pausa half-duplex: a referência acústica do AEC
do Android pode não representar o TTS reproduzido localmente.

## Distribuição do Vosk
- Biblioteca Android: `com.alphacephei:vosk-android:0.3.75` (via Maven Central).
- Modelo: `vosk-model-small-pt-0.3` (pt-BR, Apache-2.0).
- Fonte: https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip
- Tamanho publicado pelo catálogo Vosk: **31M**; cópia pública do ZIP:
  aproximadamente **32,5 MB** compactados (o valor exato do download não foi
  medido por este projeto).
- SHA-256 pin: `6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7`.
- Estratégia: **baixar uma vez na primeira ativação**, verificar hash e extrair
  sob `filesDir`, com bloqueio de zip-slip e limites de 64 MiB de download /
  180 MiB de expansão; **não embutir o modelo no APK**.
- O APK ainda contém as bibliotecas nativas do Vosk; o CI registra o tamanho
  real do `app-debug.apk` e a contagem de testes nos logs da job `app`.

## Verificação e critérios de aceite
A CI mantém as quatro jobs (`core`, `cli`, `app`, `relay`).
Os testes JVM do `:core:test` cobrem estado/erros/permissão, confirmações
parcial+final e limites do buffer circular.

Pendente de ensaio **no POCO X8 Pro (HyperOS)**: reconhecer "oi Muse" com tela
ligada/apagada, primeiro download/corrupção/offline, verificar ausência de
envio de áudio pré-wake (captura de rede), não perder início do pedido,
entrega A2/MP3, ruído/falso positivo, interrupções do microfone,
permissões revogadas, desligamento por notificação, memória e consumo de bateria.
O comportamento de serviço foreground com tela apagada depende também da
política de energia do fabricante. Não alegar bateria ou reconhecimento real
aprovados somente pelos testes JVM.
