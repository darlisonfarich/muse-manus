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
   **CORREÇÃO NO CÓDIGO (2026-11-10), falta o ensaio físico:** `DisconnectWatchdog` (gadgetlink, testado na JVM) — loga "disconnect scheduled/requested", manda `connect(…, false)` + `cancelConnection` (o cancel do servidor só age numa conexão que ele "conhece") e loga o envio ou o erro; se o MESMO cliente, na MESMA conexão, seguir de pé 600 ms depois, loga "still connected … forcing it off", fecha o servidor GATT, limpa a sessão como no onDisconnect ("client disconnected … (forced: GATT server closed)") e reabre serviço + anúncio se a janela ainda estiver aberta (se não reabrir, a fase vai para erro). Pior caso: ~0,9 s depois do DECRYPT_FAILED. Cliente que caiu e voltou, ou outro que chegou no meio, não é derrubado. Ferramenta do ensaio: `gadgetlink/tools/pc_central.py` (central Python independente, bleak + cryptography; `--selftest` sem Bluetooth, `--replay` para o teste negativo). Critério: no próximo `--replay`, "client disconnected" até ~1 s depois do DECRYPT_FAILED e a etapa de replay `ok`.
   **CÓDIGO PRONTO (2026-11-10), falta o ensaio físico:** ble_setup.py portado para o módulo gadgetlink (todo o test_ble_setup.py portado), junto com identidade, estado e checagem do token na API do Muse; no app, servidor GATT + anúncio com os UUIDs do ble_server.py, serviço em primeiro plano (connectedDevice) e controles na Diagnostics. O estado fica no MESMO formato do SDK (identity.json, pairing.json, sdk_token em filesDir/gadgetlink/state), para o SDK oficial poder usá-lo direto.
   Diferenças do Android tratadas: o anúncio só leva o nome do adaptador, então o nome Bluetooth vira MuseGadgetXXXXXX durante o setup e volta depois (o original fica gravado antes e é devolvido até se o app morrer no meio); MTU real de onMtuChanged (23 até negociar); um notify por vez; writes longos remontados; um cliente por vez; janela de 10 min como o SDK.
   Ensaio: Bluetooth ligado → Sobre → Feedback → Copiar diagnóstico → "Colar token SDK" (opcional) → "Abrir pareamento" (pede "Dispositivos próximos" só aí) → no outro celular, app Muse → adicionar gadget → MuseGadgetXXXXXX. A seção "Gadget link (spike)" mostra a fase (waiting / hello / paired) e os últimos eventos; o pareamento completo termina com "paired and provisioned" e "link saved pairing: yes".
4. **Cliente Noise + `link.register`** como `homehub` → chegar a `registered with the Muse`.
   **EM ANDAMENTO (2026-11-11):** camada Noise pronta no módulo gadgetlink, só JVM, sem Android: handshake Noise XX (Noise_XX_25519_AESGCM_SHA256) no papel de iniciador e cifras de transporte, com conformidade byte a byte contra a implementação de referência do SDK (vetores gerados por ela com chaves fixas, em gadgetlink/tools/gen_noise_vectors.py), mais X25519 conferido contra o RFC 7748. Cifra envenenada após qualquer falha, nonce nunca reutilizado, ponto de ordem baixa recusado, chaves privadas zeradas. Limite conhecido: o X25519 do provedor só existe no Android 13+ (API 33); o app tem minSdk 30, então o recurso precisa checar isso antes de abrir o túnel. Faltam: framing e envelopes de serviço, o cliente de link (register) sobre WebSocket e a integração no app; "registered with the Muse" só se confirma no aparelho, com a conta e a VM reais.
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
