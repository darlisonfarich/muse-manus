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
3. **Transporte BLE periférico** (advertiser + GATT server) ligando a lógica de `ble_setup`. Parear com o app Muse num **segundo dispositivo** (ver B2). Confirmar MuseGadgetXXXXXX, Wi-Fi, `pairing.json`.
4. **Cliente Noise + `link.register`** como `homehub` → chegar a `registered with the Muse`.
5. **Voz realtime** (`AudioRecord`/`AudioTrack`, half-duplex, barge-in, end_call_stop). Paridade por WAV primeiro, depois ao vivo.
6. **Integração da wake:** o turno do VoiceWakeService deixa de abrir o A2 e passa a abrir o `voice-call` contra a VM arrendada; mantém gate half-duplex, notificação e a garantia de não-exfiltração pré-wake. "tchau" → `session_control/stop` → encerra a chamada.

## Bloqueios reais (camada → contorno)

- **B1 — periférico BLE no POCO** (controlador/HyperOS): BluetoothLeAdvertiser pode ser nulo em alguns aparelhos. *Gate deste spike.* Atenção: `isMultipleAdvertisementSupported()` falso NÃO reprova — o Android só a liga com 5 ou mais instâncias de anúncio, e o pareamento usa uma. O veredito vem de um teste real na sonda (anúncio conectável de ~1 s + GATT server), que só roda com a permissão "Dispositivos próximos" concedida. Se o teste der FEATURE_UNSUPPORTED: sem pareamento on-device → usar o padrão headless (gerar identidade no celular, parear numa caixa Linux com BLE, copiar `identity.json`+`pairing.json`+`sdk_token`). O `voice-call` é só rede, então isso **não** mata a meta de comunicador.
- **B2 — pareamento no MESMO aparelho** (stack BLE do Android): o app Muse e o app-gadget no mesmo celular não fazem loopback central↔peripheral confiável. Contorno: parear com o app Muse num **segundo dispositivo**, ou headless (B1).
- **B3 — identidade de plataforma** (admissão do servidor): registrar um gadget Android como `homehub`; verificar que o servidor admite o device e a voz. Verificar empiricamente.
- **B4 — admissão de voz realtime** (conta/servidor): a VM precisa admitir devices pareados em `/voice/realtime` e `/voice/end-call`; exige elegibilidade de voz na conta/VM. De-riscar com o `voice-call` WAV no gadget de referência ANTES do Android.
- **B5 — Doze/energia OEM** (gestão de energia): FGS + WSS de vida longa sob HyperOS. Mitigar: FGS, pedido de isenção de otimização de bateria, keepalive. (Mesma preocupação já anotada para a wake.)
- **B6 — eco acústico:** half-duplex por padrão (reaproveitar o gate) ou AEC para full-duplex.

## Conta vs código

- **Conta:** token SDK (`gadgets.muse.ai`); Developer mode (feito); ≥1 VM arrendada via `fetch_vms` com voz realtime admitida; elegibilidade de voz na conta/VM. `--vm-id` é uma VM exata e nunca muda a VM padrão.
- **Código:** todo o resto — daemon, porte do pairing, cliente Noise, cliente de voz, integração com a wake.

## Onde nasce

Módulo autocontido no muse-manus (Apache-2.0, compatível com o fork Mika e com o
SDK), eventual `:gadgetlink` (pacote `com.darlisonfarich.gadgetlink`), sem
dependências específicas do Mika para poder ser extraído depois. Este spike começa
a sonda dentro do módulo `app` (compila já) e só vira módulo próprio quando o
portão do passo 1 passar.
