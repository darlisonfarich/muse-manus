# Muse Manus — celular como gadget Muse (gadget-link)

> Estado: **spike**. Meta: rodar o papel de *gadget* do Muse NO celular, como um
> comunicador — um daemon que ouve a palavra-chave "oi Muse" e abre a sessão de
> voz **realtime oficial** do SDK contra a VM arrendada da conta, para conversar
> com a Muse oficial sem tocar em nada e desligar pela voz ("tchau").
>
> Construído a partir do SDK aberto `facebookincubator/muse-gadget-sdk`
> (Apache-2.0), com token real de `gadgets.muse.ai` e pareamento confirmado no
> app. Nada aqui contorna controle: a palavra-chave falada é o gatilho/consentimento,
> e a voz realtime é capacidade que o próprio SDK entrega. **Fonte autoritativa: o
> SDK aberto, não o app revertido.**

## Mapa de portabilidade (módulo do SDK Linux → responsabilidade no Android)

| SDK (`linux/src/musegadget/`) | No celular (Kotlin) | Observação |
|---|---|---|
| identity.py | store de identidade (`homelink-xxxxxx`, nome BLE MuseGadgetXXXXXX) | persistir em `filesDir`; reaproveitar padrão DataStore do app |
| `config.py` | config + token (`mgst_…`) | token em EncryptedSharedPreferences; nunca em argv |
| pairing.py (v5: P-256 ECDH, HKDF-SHA256, AES-256-GCM, `confirm_app`) | cripto via `java.security`/`javax.crypto`/Conscrypt | **portar primeiro**; validar contra `tests/vectors/link_pairing_v5.json` em teste JVM |
| ble_framing.py (framing `0xFE`, index/total/payload) | framing sobre writes do GATT | respeitar MTU (`negotiatedMtu-3`; write ≤512; `ExchangeMTU=256`) |
| ble_setup.py (**sem dependência de BlueZ**) | camada de lógica de setup, dirigida pelos callbacks do GATT server | porta limpa |
| ble_server.py (GATT peripheral via BlueZ/D-Bus) | **ÚNICO módulo não portado**: reimplementar com BluetoothLeAdvertiser + BluetoothGattServer | advertir MuseGadgetXXXXXX (sem hífen); modelo `hatch_link` em `get_device_info` |
| provisionamento (`provision_v2`) | responder SSID (aparelho online → "rede atual"; ignorar campos Wi-Fi); usar `api_url_v2` ou `https://api.muse.ai` | — |
| tls.py | OkHttp com trust store do sistema | app já usa OkHttp |
| link_client.py (`/v1/noise`, Noise XX, `/link-control`, chat, TTS) | cliente Noise XX sobre OkHttp WebSocket → `wss://hatch.metaaivm.com/v1/noise?vm_id=…` | registrar `device_family: "homehub"`; **nunca** family `link` nem `device.ota` |
| muse_api.py (`fetch_vms`, refresh de token) | chamadas REST | bearer por-VM no upgrade |
| voice_call.py / voice_audio.py (lifecycle realtime; PCM16 24 kHz; half-duplex; barge-in; end_call_stop) | `AudioRecord` (24 kHz mono PCM16) + `AudioTrack`; portar máquina de turnos | reaproveitar disciplina half-duplex já existente |
| service.py (loop, reconexão, rotação de token, socket) | **foreground service** | reaproveitar esqueleto do VoiceWakeService |
| executor.py/fileops.py (comandos) | opcional | para comunicador puro, registrar DeviceDescription com poucos/nenhum comando |

## Reaproveitar do VoiceWakeService (PR #31) já pronto

- Esqueleto de FGS tipo `microphone`, notificação persistente e dispensável, fluxo de permissão.
- WakeStateMachine (gate half-duplex: detector pausado durante playback) → mapeia direto no requisito half-duplex do `voice-call`.
- VoiceStageRecorder (subir 16k→24k PCM16) e o padrão de playback (trocar MediaPlayer/MP3 por `AudioTrack` PCM contínuo do realtime).
- Detecção Vosk (WakeModelInstaller, gramática `["oi muse","[unk]"]`) inalterada.
- Garantia de **não enviar áudio antes do wake** (captura de rede) carrega para cá.

## Ordem de construção

1. **Portão do spike (este commit + voz de referência):**
   - (código) `BlePeripheralProbe` — o controlador do POCO X8 Pro consegue advertir como periférico? Se não, pareamento on-device sai de cena (ver bloqueios B1/B2).
   - (conta) rodar `musegadget voice-call --in-wav/--out-wav` contra um **gadget de referência Linux** (a VM da conta serve) para confirmar que a VM admite voz realtime ANTES de qualquer porte.
2. **Pairing v5 + framing** em Kotlin, validado contra os vetores publicados (teste JVM, sem Android). Trust-critical → primeiro.
   **FEITO (2026-11-10):** módulo JVM puro em `gadgetlink/` (port de pairing.py e ble_framing.py do SDK, commit 55cbf48). Bate byte a byte com os 3 vetores oficiais (transcript, HKDF, session id, registro client-finished) e porta toda a suíte test_pairing.py/test_ble_framing.py. Endurecido além do SDK: ponto P-256 validado à mão (não confia no provedor), UTF-8 estrito nos dois sentidos, contador sem repetição sob concorrência, chaves zeradas ao limpar, MTU 4..6 vira erro em vez de perder mensagem, handshakes com chaves aleatórias (coordenadas com byte zero). Os testes rodam no job core (JVM) do CI. Falta: provar no Android de verdade (Conscrypt) — vem junto do passo 3. Quando o app passar a depender do módulo (passo 3), registrar o muse-gadget-sdk (Apache-2.0) em licenses/THIRD_PARTY_NOTICES.md e na tela de licenças — o teste de licenças do app exige os dois; até lá o crédito está no cabeçalho de cada arquivo portado.
3. **Transporte BLE periférico** (advertiser + GATT server) ligando a lógica de `ble_setup`. Parear com o app Muse num **segundo dispositivo** (ver B2). Confirmar MuseGadgetXXXXXX, Wi-Fi, `pairing.json`. RESULTADO (2026-11-10, POCO real, replay do PC): a cadeia inteira funcionou no aparelho — advertise MuseGadget364AC1, conexão, MTU 517, subscribe, DEVICE_INFO, **HELLO_OK** (cripto v5 no Android/Conscrypt), **client-finished CONFIRMED** e WIFI_SCAN decifrado e respondido. FALTA UM DEFEITO: o registro cifrado seguinte ao wifi_scan chegou com contador inesperado (`DECRYPT_FAILED unexpected counter`) e a sessão caiu antes do provisionamento — nenhum `pairing.json` salvo. DIAGNÓSTICO FECHADO (2026-11-10, Claude + revisão do Muse no código): o contador inesperado foi um TESTE NEGATIVO deliberado do pc_central — reenviou um registro já usado (ataque de replay) e o gadget rejeitou certo. O defeito real é outro: após o erro de decifragem o gadget NÃO derrubou a conexão em ~0,3 s como o SDK faz; a queda só veio 7 s depois, quando o script encerrou. O SetupController chama transport.disconnect(0.3) corretamente; o problema está em GattTransport.disconnect(): um único cancelConnection dentro de runCatching (exceção engolida em silêncio), sem conferir se a conexão caiu nem escalar. Correção: registrar o resultado/erro do cancelConnection e, se a conexão seguir de pé após ~1 s, forçar a queda.
   **CORREÇÃO NO CÓDIGO (2026-11-10), falta o ensaio físico:** `DisconnectWatchdog` (gadgetlink, testado na JVM) — loga "disconnect scheduled/requested", manda `connect(…, false)` + `cancelConnection` (o cancel do servidor só age numa conexão que ele "conhece") e loga o envio ou o erro; se o MESMO cliente, na MESMA conexão, seguir de pé 600 ms depois, loga "still connected … forcing it off", fecha o servidor GATT, limpa a sessão como no onDisconnect ("client disconnected … (forced: GATT server closed)") e reabre serviço + anúncio se a janela ainda estiver aberta (se não reabrir, a fase vai para erro). Pior caso: ~0,9 s depois do DECRYPT_FAILED. **MEDIDO NO POCO (2026-11-11, build ca0bef2, reteste-ble.bat):** pareamento v5 inteiro ok no aparelho (GATT, MTU 517, handshake, criptografia dos dois lados, wifi_scan cifrado) e o registro repetido derrubou a conexão, mas em 3,04 s (limite 1,5 s). Achado no código: depois de confirmado, o decrypt zera as chaves antes de falhar, então nenhum status sai e a queda não precisava esperar os 0,3 s; ela agora é pedida na hora (só o status em texto puro, antes do hello, mantém a espera) e, quando há status a enviar, o pedido vem ANTES do envio (o notify espera até 2 s pela confirmação do Android). O Android ainda soma o tempo de ociosidade do GATT para derrubar o enlace, fora do nosso controle: o app passa a logar, em ms, o pedido da queda e o evento de desconexão do ACL (linha ACL link ... dropped N ms after the drop request na Diagnostics), para o próximo reteste dizer onde vai cada milissegundo em vez de supor. Cliente que caiu e voltou, ou outro que chegou no meio, não é derrubado. Ferramenta do ensaio: `gadgetlink/tools/pc_central.py` (central Python independente, bleak + cryptography; `--selftest` sem Bluetooth, `--replay` para o teste negativo). Critério: no próximo `--replay`, "client disconnected" até ~1 s depois do DECRYPT_FAILED e a etapa de replay `ok`.
   **CÓDIGO PRONTO (2026-11-10), falta o ensaio físico:** ble_setup.py portado para o módulo gadgetlink (todo o test_ble_setup.py portado), junto com identidade, estado e checagem do token na API do Muse; no app, servidor GATT + anúncio com os UUIDs do ble_server.py, serviço em primeiro plano (connectedDevice) e controles na Diagnostics. O estado fica no MESMO formato do SDK (identity.json, pairing.json, sdk_token em filesDir/gadgetlink/state), para o SDK oficial poder usá-lo direto.
   Diferenças do Android tratadas: o anúncio só leva o nome do adaptador, então o nome Bluetooth vira MuseGadgetXXXXXX durante o setup e volta depois (o original fica gravado antes e é devolvido até se o app morrer no meio); MTU real de onMtuChanged (23 até negociar); um notify por vez; writes longos remontados; um cliente por vez; janela de 10 min como o SDK.
   Ensaio: Bluetooth ligado → Sobre → Feedback → Copiar diagnóstico → "Colar token SDK" (opcional) → "Abrir pareamento" (pede "Dispositivos próximos" só aí) → no outro celular, app Muse → adicionar gadget → MuseGadgetXXXXXX. A seção "Gadget link (spike)" mostra a fase (waiting / hello / paired) e os últimos eventos; o pareamento completo termina com "paired and provisioned" e "link saved pairing: yes".
4. **Cliente Noise + `link.register`** como `homehub` → chegar a `registered with the Muse`.
   **EM ANDAMENTO (2026-11-11):** camada Noise pronta no módulo gadgetlink, só JVM, sem Android: handshake Noise XX (Noise_XX_25519_AESGCM_SHA256) no papel de iniciador e cifras de transporte, com conformidade byte a byte contra a implementação de referência do SDK (vetores gerados por ela com chaves fixas, em gadgetlink/tools/gen_noise_vectors.py), mais X25519 conferido contra o RFC 7748. Cifra envenenada após qualquer falha, nonce nunca reutilizado, ponto de ordem baixa recusado, chaves privadas zeradas. Limite conhecido: o X25519 do provedor só existe no Android 13+ (API 33); o app tem minSdk 30, então o recurso precisa checar isso antes de abrir o túnel. Framing também pronto e provado contra a referência: protobuf escrito à mão, fragmentação em frames de até 65489 bytes e remontagem com limite de 16 montagens pendentes, 256 fragmentos, descarte após 60 s e decoder envenenado após qualquer erro. Envelopes de serviço também prontos e provados contra a referência (pedido de serviço, quadro com stream, requisição e resposta, reset): último campo repetido vence, int32 com faixa, UTF-8 estrito, enum desconhecido recusado. Transporte que junta cifra, framing e envelopes também pronto e provado contra a referência, com chaves e ids de fragmento fixos (o que o cliente envia confere em bytes; o que ele recebe confere cenário a cenário: resposta, pedaços, reset, quadro sem tipo ignorado, pedido vindo do servidor e payload vazio recusados, transporte morto após qualquer erro). Cliente de link também pronto na JVM (máquina de estados dirigida por eventos do WebSocket, testada contra um Muse falso que fala o protocolo de verdade): handshake, stream de controle, registro como homehub SEM comandos (todo pedido de execução recebe unsupported command e nada roda), desfechos CLOSED, AUTH_REJECTED, FORBIDDEN, UNPAIRED e STOPPED, URL do Noise com o host validado como host:porta puro, mensagem de controle idêntica em bytes à da referência. Atenção: o SDK anuncia platform e model_id linux; ficam configuráveis, e é o primeiro ajuste se o Muse estranhar o celular. PASSO 4 INTEGRADO NO APP (2026-11-11): supervisor de reconexão (renova o token a cada 3 h, busca a VM, espera crescente de 2 a 60 s, piso de 15 s depois de recusa de auth), WebSocket real com OkHttp (bearer da VM no cabeçalho, ping de 20 s) testado contra um servidor WebSocket de verdade que fala o protocolo, armazenamento do pairing.json, serviço de primeiro plano GadgetConnectService e, na Diagnostics, o botão Conectar ao Muse com a linha link to Muse (estados: looking up your Muse, connecting, registered with the Muse, reconnecting). Limites: precisa de Android 13 ou mais novo (X25519 do provedor; senão a Diagnostics diz por quê), registra SEM comandos e só como comunicador. Falta o ensaio real: com o celular pareado, tocar em Conectar ao Muse e ver registered with the Muse (conta e VM reais, nada disso eu consigo exercitar fora do aparelho); "registered with the Muse" só se confirma no aparelho, com a conta e a VM reais.
5. **Voz realtime** (`AudioRecord`/`AudioTrack`, half-duplex, barge-in, end_call_stop). Paridade por WAV primeiro, depois ao vivo.
6. **Integração da wake:** o turno do VoiceWakeService deixa de abrir o A2 e passa a abrir o `voice-call` contra a VM arrendada; mantém gate half-duplex, notificação e a garantia de não-exfiltração pré-wake. "tchau" → `session_control/stop` → encerra a chamada.

## Bloqueios reais (camada → contorno)

- **B1 — periférico BLE no POCO** (controlador/HyperOS): BluetoothLeAdvertiser pode ser nulo em alguns aparelhos. *Gate deste spike.* Atenção: flags lidas com o Bluetooth DESLIGADO voltam false e não valem nada — foi o que gerou a primeira leitura "cannot advertise" (2026-11-10 de manhã). O veredito vem de um teste real na sonda (anúncio conectável de ~1 s + GATT server), que só roda com a permissão "Dispositivos próximos" concedida e o Bluetooth ligado. **RESULTADO (2026-11-10, POCO X8 Pro, build 1562fde): can advertise (tested); gatt server=opened — B1 FECHADO a favor; o pareamento on-device é a rota (o headless vira só reserva).** Se o teste der FEATURE_UNSUPPORTED: sem pareamento on-device → usar o padrão headless (gerar identidade no celular, parear numa caixa Linux com BLE, copiar `identity.json`+`pairing.json`+`sdk_token`). O `voice-call` é só rede, então isso **não** mata a meta de comunicador.
- **B2 — pareamento no MESMO aparelho** (stack BLE do Android): o app Muse e o app-gadget no mesmo celular não fazem loopback central↔peripheral confiável. Contorno: parear com o app Muse num **segundo dispositivo**, ou headless (B1).
- **B3 — identidade de plataforma** (admissão do servidor): registrar um gadget Android como `homehub`; verificar que o servidor admite o device e a voz. Verificar empiricamente.
- **B4 — admissão de voz realtime** (conta/servidor): a VM precisa admitir devices pareados em `/voice/realtime` e `/voice/end-call`; exige elegibilidade de voz na conta/VM. DECISÃO (2026-11-10, Darlison): rota B — o pareamento é construído no Manus e o B4 é testado a partir do build do próprio celular; nada de PC no caminho. Referência já preparada: o SDK oficial roda na VM do Muse com a identidade homelink-96821a criada e o token do SDK válido, mas o doctor do SDK confirma que o `voice-call` usa o paired store — sem pareamento salvo ele não testa a voz, então o gadget de referência fica como apoio de protocolo, não como atalho do B4.
- **B5 — Doze/energia OEM** (gestão de energia): FGS + WSS de vida longa sob HyperOS. Mitigar: FGS, pedido de isenção de otimização de bateria, keepalive. (Mesma preocupação já anotada para a wake.)
- **B6 — eco acústico:** half-duplex por padrão (reaproveitar o gate) ou AEC para full-duplex.

## Conta vs código

- **Conta:** token SDK (`gadgets.muse.ai`); Developer mode (feito); ≥1 VM arrendada via `fetch_vms` com voz realtime admitida; elegibilidade de voz na conta/VM. `--vm-id` é uma VM exata e nunca muda a VM padrão.
- **Código:** todo o resto — daemon, porte do pairing, cliente Noise, cliente de voz, integração com a wake.

## Onde nasce

Módulo autocontido no muse-manus (Apache-2.0, compatível com o fork Mika e com o
SDK), `:gadgetlink` (pacote `com.darlisonfarich.gadgetlink`; criado em 2026-11-10 com o pairing v5), sem
dependências específicas do Mika para poder ser extraído depois. Este spike começa
a sonda dentro do módulo `app` (compila já) e só vira módulo próprio quando o
portão do passo 1 passar.

## Rodada de revisão de código — 2026-11-10 (Muse, 5 frentes)

Revisão somente-leitura de todo o spike (cripto/sessão, SetupController+framing,
GattTransport, serviço/estado/API, testes) contra o SDK oficial. Cripto e sessão:
nenhum defeito alto/médio — contadores, nonces, HKDF, validação EC e ciclo de vida
batem com a referência. Corrigido nesta rodada (com testes):

- `BleFraming.feed`: pacote cru agora respeita `MAX_MESSAGE_BYTES` (long write
  remontado pelo transporte furava o teto) e descarta montagem parcial em vez de
  misturá-la com os chunks seguintes (desvio consciente do SDK, fixado em teste).
- `Pairing.parseCounter`: removida a rejeição de contadores ≥ 2⁶³ (o SDK aceita;
  o wrap nunca iguala o contador esperado e cai no erro de decifragem).
- `PairingSession.handleHello`: segredo ECDH zerado em `finally` (antes, uma
  falha na derivação deixava o segredo na heap).
- `MuseApi.isUsableApiUrl`: URL de API com userinfo (`https://api…@host/`) não
  é mais aceita — o bearer iria para o host errado; cai para a API oficial.
- `GadgetState.writeAtomic`: tmp único por gravação (o `@Synchronized` é por
  instância e cada `state(context)` cria uma nova sobre o mesmo diretório).
- `SetupController`: `start()` com guarda contra chamada dupla; `runProvision`
  só anuncia auth_ok/`onComplete` se a geração ainda é a atual; eventos de
  erro levam só o nome da classe (contrato "sem texto livre" da Diagnostics).
- `DisconnectWatchdog`: após uma força bem-sucedida a época avança — dois
  `request()` seguidos cancelam duas vezes (inofensivo) mas forçam uma só.
- `GattTransport`: status do notify agora é por pacote (nasce FALHA — acordar a
  trava por fechamento/desconexão não é entrega); timeout limpa o pendente;
  `notifyCharacteristicChanged` dentro de `runCatching` (revogação de permissão
  no meio da sessão não emudece os envios); `adopt()` só pelos canais do setup
  (RX ou CCCD do TX); chunk de prepared write fora de ordem é descartado, não
  remontado no lugar errado; `onExecuteWrite` só drena para o cliente; reconexão
  do mesmo cliente sem DISCONNECTED reseta MTU e parcial; trava única do buffer
  de prepared writes.
- `GadgetLinkService`: flag `destroyed` confere-se após criar o transporte
  (corrida `onDestroy` × `openSetup` podia deixar o rádio anunciando sem
  serviço) e o `catch` da abertura para o transporte criado.
- Testes: asserções dentro de threads de pool não morrem mais nelas
  (`concurrentSendersNeverReuseACounter`); total esperado fixo de 200 em
  `encryptedStatusesStayInCounterOrderUnderConcurrentSenders`; redirect 302 da
  API não é seguido (teste com dois servidores); chunk duplicado com semântica
  fixada; pacote cru acima do teto descartado.

Registrado, NÃO corrigido (decisão ou refatoração maior — fila do Claude):

- **Costura testável do GattTransport**: a fiação callback↔watchdog não tem
  nenhum teste (o teste do watchdog usa um FakeLink que chama
  `connectionChanged()` na mão). Refatoração para uma costura fakeável.
- **Harness por relógio real** em parte do `SetupControllerTest.kt`
  (`waitForStatus` com deadlines de 2–3 s): trocar por latch/executor injetável.
- **Flag `provisioning` através do disconnect** (`SetupController`): cliente
  que cai no meio do provisionamento segura a flag até a thread terminar; o
  impacto é limitado pelos timeouts de 15 s da MuseApi. Correção sugerida:
  limpar a flag por geração no `finally`.
- **`identity.json` corrompido**: o cabeçalho da classe promete "erro, não
  identidade nova", mas o teste `corruptIdentityIsReplaced` fixa regenerar.
  As duas semânticas não convivem — escolher uma (decisão de produto).
- **`pairing.json` velho** sobrevive a um reprovisionamento falho e a
  Diagnostics afirma pareamento. Apagar ao confirmar um novo pareamento tem o
  custo oposto: uma falha transitória apagaria um pareamento ainda válido.
  Escolher a semântica (decisão de produto).
- **auth_failed genérico**: rede fora/HTTP 500 viram auth_failed — mas é o
  comportamento do próprio SDK (o CLI dele distingue só no log). Sem mudança
  no fio; no máximo detalhar na Diagnostics um dia.
- **`forceOff` bloqueia a thread do watchdog** por até ~10 s na reabertura
  (addService + advertising): mover a reabertura para um executor próprio.
- **Endurecimento menor de testes**: transcript do `RandomKeysTest.kt` montado
  com as funções de produção (independência repousa no `PairingVectorsTest.kt`);
  escopo de sessão dos vetores community/official menor que o doc da suíte;
  `sendResponse` dos callbacks binder sem `runCatching` (mesma classe do
  notify, menor); `Mobile.open` não confronta a sequência de contadores —
  considerado e não adotado: `open()` também serve a releituras no harness, e
  a sequência exata já está fixada no nível do envelope por três testes.

## Etapa 2 — leitura do display virtual

O serviço conectado mantém uma sessão direta própria ao receber a confirmação de registro da VM. Usa a identidade do gadget, da conta e da VM no diário de ações. A sessão tem seu próprio slot, display virtual, referências e autorização por conexão. O serviço prepara a sessão mesmo que a ponte nunca tenha recebido um comando. O display é criado sob demanda ao abrir o primeiro app. Antes disso a árvore tem `hasWindow: false` e a captura retorna `unavailable`.

- `phone.get_tree` aceita `detail: "elements"` (padrão) ou `"full"`. Os dois modos aplicam a redação OTP. O serviço guarda a impressão digital da última árvore. Um comando de escrita com `ref` e `snapshotId` precisa reencontrar a mesma árvore e geração antes de usar o executor.
- `phone.get_screenshot` entrega `mimeType`, `data` em base64 e `sizeBytes`. O alvo é 170 KiB de imagem binária e o limite é 240 KiB para o resultado completo. Leituras e ações consultam as proteções de janela, senha e uso pelo dono.
- O encerramento do registro revoga a sessão. O próximo registro cria outra sessão e exige uma nova leitura da tela. Um resultado de escrita incerto é consultado com `phone.reconcile` e o mesmo identificador de pedido. Usar outro ID pode duplicar a ação.

## Controle direto e recuperação

O catálogo anuncia abrir app, tocar, digitar, rolar, deslizar, gestos, teclas, volume, mídia, voltar e lote. Também oferece estado, lista de apps, consulta ao diário e cancelamento. Os nomes e parâmetros seguem o catálogo `commands_v2`. Um lote recebe os mesmos nomes internos da ponte, por exemplo `phone_launch_app`. O acesso remoto e a escrita direta continuam sujeitos aos interruptores locais. Enquanto uma tarefa local roda, a sessão do gadget devolve o slot, o display e os apps reservados. Os comandos dele esperam na fila e a sessão é recriada quando a tarefa termina, o que exige uma nova leitura antes de ações por `ref`. Uma recusa por operação sensível exige intervenção local. Os resultados das escritas trazem apenas metadados. Uma escrita já executada nunca volta como indisponível: se o registro terminar durante ela, ou se parte de um lote já tiver rodado, o erro é de executor com a indicação de reconciliar pelo mesmo identificador de pedido. A ponte continua acessível pelos caminhos existentes e mantém seu próprio proprietário.

O APK contém o ajudante da sua própria versão, e o app mantém esse ajudante sozinho. A ordem das tentativas é esta:

1. **Autoatualização do ajudante, sem ADB, root nem depuração.** Depois de uma atualização, o app recusa o ajudante antigo (`HelperHandoff.Reject.StaleApk`). Um ajudante desta versão em diante ainda roda com UID shell e inicia o sucessor a partir do APK que o PackageManager informa. Ele usa os mesmos argumentos e o identificador de entrega que o app ainda não consumiu, porque a recusa por APK antigo acontece antes da conferência desse identificador. O sucessor passa pelas mesmas verificações de qualquer ajudante (UID shell, APK atual, identificador e impressão do certificado) e encerra o antigo. O app espera até 20 s por essa entrega antes de tentar outro caminho.
2. **Root já autorizado pelo dono**, se esse caminho estiver ligado. Nenhum pedido novo de root é feito.
3. **Conexão ADB própria e já pareada do app.** Com a permissão de configurações seguras já concedida, o app liga a depuração Wi-Fi só para iniciar o ajudante. Com o gadget pareado e ligado, ele a desliga logo após a entrega, preservando a configuração do dono. Se desligar a depuração derrubar o ajudante neste aparelho, o app aprende isso e passa a deixar a depuração ligada depois do relançamento. O diagnóstico registra `AutoOffOutcome.HelperStopped` ou `AutoOffOutcome.KeptOnStopsHelper`. Esse caminho exige Opções do desenvolvedor ligadas e Wi-Fi em rede já autorizada para depuração. Com as Opções do desenvolvedor desligadas, o app não tenta conexões em loop: mostra `helper_needs_developer_options`, devolve a depuração ao estado anterior e tenta de novo assim que elas forem ligadas. Se o relançamento falhar de vez, a depuração que o app ligou também é desligada.

O diagnóstico do link registra `ready`, `helper_upgrading`, `helper_starting`, `stale_helper`, `helper_needs_wifi`, `helper_wifi_not_allowed`, `helper_needs_wireless_debugging`, `helper_needs_developer_options`, `helper_needs_pairing`, `helper_not_set_up` ou `helper_unavailable`. Ele também registra o desfecho da depuração após cada relançamento: um dos valores de `AutoOffOutcome` (`Restored`, `Refused`, entre outros).

**Por que o ajudante morre com a depuração toda desligada.** O ajudante é um processo com UID shell criado pelo `adbd`. Com a depuração USB e a depuração Wi-Fi desligadas ao mesmo tempo, o Android para o `adbd`, e o `init` mata todos os processos do grupo dele, inclusive o ajudante (o `setsid` não muda isso). Nenhum componente do app (serviço em primeiro plano, receptor de boot, acessibilidade) consegue um UID shell, então sem root não há como sobreviver a isso. Para manter o controle sem Wi-Fi, sem Tailscale e sem depuração por Wi-Fi, deixe a **depuração USB ligada**, sem cabo: o `adbd` continua de pé e o ajudante sobrevive, inclusive às atualizações. Por isso, depois de relançar, o app só desliga a depuração Wi-Fi quando a USB está ligada; senão registra `AutoOffOutcome.KeptOnAdbWouldStop`. O registro copiado mostra a hora do último boot, o estado atual das depurações e, quando o ajudante morre com o app vivo, a hora e o estado das depurações naquele momento.

**Limites que continuam valendo.** O ajudante que roda hoje no POCO veio do PR #39 e não sabe se autoatualizar. Por isso, a **primeira** instalação deste PR depende do caminho 2 ou 3. As atualizações seguintes usam o caminho 1. Depois de reiniciar o aparelho não existe ajudante vivo, então só os caminhos 2 e 3 servem. Sem root, sem Wi-Fi autorizado ou sem o pareamento ADB do app, um APK comum não consegue um processo com UID shell, e o diagnóstico mostra o motivo.

### Validação no POCO com o Ship

1. Baixar da CI o artefato universal sem assinatura, conferir o commit e assinar com o certificado do APK instalado. Conferir o applicationId e um versionCode superior ao instalado. Instalar por atualização e verificar dados, pareamento, permissões e configurações.
2. Com Tailscale, USB e depuração Wi-Fi desligados, conectar o gadget já pareado sem fazer chamada pela ponte. Registrar se há root previamente autorizado, se o ajudante anterior sobreviveu e se o diagnóstico chegou a `ready`. Verificar a árvore vazia antes da primeira abertura.
3. Abrir app permitido no display virtual. Testar toque por ref e por coordenadas, texto, gestos, rolagem, teclas e lote. Conferir efeito único após queda de rede, consulta pelo mesmo ID, recusa de referência antiga e proteção do app na tela física. Repetir com escrita desabilitada, senha, OTP e janela segura. Medir bytes, MIME, latência e legibilidade das duas leituras.
4. Na primeira instalação deste PR, registrar qual caminho relançou o ajudante (root ou ADB próprio) e se a depuração Wi-Fi voltou a ficar desligada.
5. Com um ajudante **vivo** desta versão (`phone session ready` antes da troca), gerar outra build deste PR com versionCode maior e instalar por cima com Wi-Fi, Tailscale e depuração Wi-Fi desligados e a **depuração USB ligada sem cabo** (com ela desligada também, o ajudante morre junto com o `adbd` antes da troca). Sem ajudante vivo não há o que se autoatualizar, e o app cai nos caminhos 2 ou 3. O log deve mostrar a recusa por APK antigo seguida de `ready`, sem a depuração ligar. Esse é o teste da autoatualização.
6. Reiniciar o aparelho com depurações desligadas e registrar o desfecho (caminho 2 ou 3 e o estado da depuração). Exercitar a ponte como reserva, inclusive após desconectar o gadget.
7. Para recuperação por rollback-forward, o Ship recompila o código conhecido como bom com versionCode maior que o instalado, assina com o mesmo certificado e aplica por cima. Não desinstalar nem limpar dados.
