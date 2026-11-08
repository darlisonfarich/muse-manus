# Muse Manus — registro de fork modificado

Este repositório é um **fork modificado** do projeto [Mika](https://github.com/wangzan101/Mika)
(de wangzan101), distribuído sob a **Apache License 2.0**. Os arquivos `LICENSE` e `NOTICE`
originais estão mantidos sem alteração, assim como os avisos de copyright existentes nos
arquivos-fonte.

Este arquivo é o registro visível de modificação exigido pela Apache License 2.0, §4(b):
os arquivos alterados em relação ao projeto original estão listados abaixo.

## Nova identidade

- **Nome do app:** Muse Manus (variante de desenvolvimento: Muse Manus Dev)
- **applicationId Android:** `com.darlisonfarich.musemanus` (original: `com.wangzan101.mika`).
- **namespace Android e pacotes Kotlin:** preservados como `com.wangzan101.mika` para manter
  as referências existentes a `R` e `BuildConfig`, sem renomear classes do Mika.

## Modificações da branch `feat/muse-manus-identity`

1. **`app/build.gradle.kts`** — apenas `applicationId` alterado de
   `com.wangzan101.mika` para `com.darlisonfarich.musemanus`; o `namespace` permanece
   `com.wangzan101.mika`. O nome de `devInstall` mudou para "Muse Manus Dev",
   preservando `applicationIdSuffix` `.dev`.
2. **`app/src/main/res/values/strings.xml`** — `app_name` trocado de "Mika" para
   "Muse Manus".
3. **`app/src/main/java/com/wangzan101/mika/about/MikaLinks.kt`** — a checagem de
   atualização passou a apontar para este fork: `LATEST_RELEASE_API` agora consulta
   `https://api.github.com/repos/darlisonfarich/muse-manus/releases/latest` e `RELEASES`
   aponta para `https://github.com/darlisonfarich/muse-manus/releases`. Os links do
   repositório original (código, README e abertura de issues) foram mantidos.
4. **`.github/workflows/ci.yml`** — o job `app (Android)` ganhou um passo final que
   publica o APK debug (`app/build/outputs/apk/debug/*.apk`) como artefato da CI com o
   nome `muse-manus-debug-apk`, também em caso de sucesso (o upload existente só roda
   em falha e só sobe relatórios de teste). Nenhum outro job ou gatilho foi alterado.
5. **`app/src/test/java/com/wangzan101/mika/about/ApkIntegrityTest.kt`** — fixture
   oficial adaptada à URL do fork; a URL do repositório original agora é testada como rejeitada.
6. **`FORK.md`** — este arquivo, novo e atualizado com as correções do PR nº 1.

## Observação de instalação

Como o `applicationId` mudou, o Muse Manus é um **app novo** para o Android: ele não
atualiza nem herda os dados de uma instalação do Mika original — os dois podem coexistir
instalados no mesmo aparelho.


## Segurança P0 — mascaramento de capturas e OCR (PR separado, em revisão)

Esta alteração ocorre **após** a integração da identidade; preserva `LICENSE`, `NOTICE`,
todos os créditos e os pacotes Kotlin originais.

- `core/src/main/kotlin/com/wangzan101/mika/core/uistate/SecretRegions.kt` (novo):
  recolhe áreas `secret=true` tanto dos elementos quanto dos nós aninhados da árvore;
  converte coordenadas com margem conservadora; descarta linhas OCR que toquem áreas
  secretas, mesmo com interseção mínima; falha fechado em geometria inválida.
- `app/src/main/java/com/wangzan101/mika/cap/phone/a11y/SecretBitmap.kt` (novo):
  desenha máscaras pretas opacas em uma cópia da imagem **antes** da conversão em
  JPEG/PNG ou do reconhecimento OCR.
- `AndroidScreen.kt`: screenshots e OCR da tela virtual usam árvores estáveis antes/
  depois da captura, verificam a geração de janelas e devolvem `null` quando a leitura
  da árvore é inconfiável; a imagem crua não sai do método.
- `MainScreenCapture.kt`: exige árvore de acessibilidade estável e legível, inclui
  diálogos do sistema na coleta e aplica máscara antes do JPEG/OCR da tela principal;
  se a análise for impossível, marca a captura como bloqueada.
- `PhoneOcr.kt` e `VisibleText.kt`: OCR recebe somente imagens já mascaradas e
  aplica um segundo filtro de interseção em linhas reconhecidas.
- `ScreenReader.kt`: não concatena texto de campo de senha ao rótulo de contêiner pai.
- `SecretRegionsTest.kt` (novo) e `SecretBitmapAndroidTest.kt` (novo):
  testes de geometria, interseção, falha fechada e pixel opaco. O teste de bitmap
  requer Android real/emulador e não é executado pelos quatro jobs JVM da CI atual.

**Limite conhecido:** quando um aplicativo desenha um segredo em pixels sem identificá-lo
como campo sensível na árvore de acessibilidade (por exemplo, um código de autenticador
exibido em um `TextView` normal), não é possível garantir sua identificação apenas por
`secret=true`. O revisor deve avaliar esse risco antes de autorizar capturas reais.
Este PR não acrescenta lista de aplicativos proibidos e não altera as regras de aprovação
de ações, que pertencem ao PR seguinte.


## Portões de aprovação — Strict / Bridge (PR independente, aguardando revisão)

Mudanças sobre a `main` pós-P0. Preservados `LICENSE`, `NOTICE` e pacotes-fonte Kotlin.

- `core/action/Approval.kt`, `RiskJudge.kt`, `ConfirmDecision.kt`, `ActionExecutor.kt`,
  `ActionOutcome.kt`, `FollowUp.kt`: três modos de aprovação; retenção de
  `IrrevAction` no `Risk`; `Strict` exige confirmação para ações externas,
  irreversíveis ou desconhecidas, mantendo leituras e modificações internas sem
  nova confirmação. O pagamento em modo não autônomo sempre requer intervenção
  manual; tela de senha em tarefa Bridge interrompe a ação e entrega ao usuário.
  MCP/canais/temporizadores genéricos não adquirem permissão Bridge.
- `core/run/RunProvenance.kt`, `AttendanceWindow.kt` (novo): origem `Bridge`,
  presença por tela interativa/desbloqueada e interação/desbloqueio local recente
  (Presente até 5 minutos, Alcançável até 30 minutos).
- `app/settings/ApprovalStore.kt`, `ApprovalControl.kt`, `MainActivity.kt`,
  `SettingsScreen.kt`, `SettingsSubPages.kt`: persistência dos modos
  `Ask / Strict / Autonomous` com migração do valor legado, exibição da opção na
  página de execução e piso `Strict` para tarefas Bridge, independente do modo global.
- `core/remote/RemoteTasks.kt`, `RemoteTaskService.kt`, `RemoteTaskApi.kt`,
  `app/task/RemoteTaskHub.kt`, `TaskService.kt`, `TaskRun.kt`: início Bridge
  em rota HTTP dedicada `POST /api/v1/bridge/tasks`, somente com contexto
  autenticado `accessOwner`, propagado pelo serviço Android privado. Início
  MCP comum permanece origem `Mcp` e não recebe exceção.
- `core/agent/UserExchange.kt`, `AgentLoop.kt`, `TaskState.kt`,
  `core/gate/ConfirmationGate.kt`, `core/remote/RemoteTasks.kt`,
  `app/task/DeviceStateWatcher.kt`: confirmação local por notificação existente,
  pausa da ponte por até 30 minutos, exposição de `paused_waiting_user` no
  estado da tarefa, expiração diferenciada (`confirmation_expired`) da
  recusa expressa e reconsulta de presença em cada decisão.
- Testes de política e risco, persistência, presença e isolamento do endpoint
  em `ApprovalPolicyTest.kt`, `ApprovalStoreTest.kt`, `AttendanceWindowTest.kt`
  e `RemoteTaskApiTest.kt`.

**Atenção:** este PR depende da revisão e do CI; não cobre a mitigação P0 dos
segredos que não sejam nós `secret=true`, nem teste real no POCO. As ressalvas
do PR anterior permanecem em aberto. Os portões NÃO adotam lista de Apps proibidos.


## Remoção 1 — envio OTLP de traces (PR independente)

O Muse Manus mantém a gravação, visualização e exportação **local** de traces,
mas não oferece mais upload de traces para servidores OTLP/HTTP.

- Removidos `app/record/OtlpSender.kt` (cliente de rede e tipos de resposta)
  e `app/ui/TraceSendRow.kt` (formulário, credenciais e comando de envio).
- Removido `TraceSendRow` de `app/ui/RunTraceSection.kt`; preservados
  `TraceExportControls`, `TraceDetails`, `TraceSettingsRows` e a exportação
  voluntária para arquivo local.
- Removidos somente os testes vinculados ao envio de rede:
  `app/src/test/java/com/wangzan101/mika/record/OtlpSenderTest.kt` e
  `app/src/androidTest/java/com/wangzan101/mika/ui/OtlpSendDeviceTest.kt`.
  Os testes de registro/visualização local permanecem.
- Removidas as onze strings exclusivas do envio OTLP de cada uma das dez
  traduções `strings_trace.xml`; mantidas as strings da gravação local.
- `docs/dev/architecture.md`: removidas as instruções e referências à transmissão
  OTLP, mantendo a descrição da exportação voluntária local.
- **Preservados:** `core/trace/*`, `app/record/DetailedTraces.kt`, a
  dependência `traceSchemas(libs.otel.proto)` em `core/build.gradle.kts`,
  e as demais capacidades do aplicativo. `LICENSE`, `NOTICE` e créditos
  do Mika continuam intactos.

Esta remoção não altera a ponte do Muse nem o canal genérico de tarefas.

## Remoção 2 — canal de mensagens WeChat (PR independente)

- Excluídos a implementação do bot/canal WeChat em `core/wechat` e
  `app/wechat`, sua interface `app/ui/WechatSettings.kt`, os testes
  exclusivos do canal, o serviço do `AndroidManifest.xml` e as strings de
  configuração do bot em dez idiomas.
- `MikaApp.kt`: removida a inicialização/reconexão automática do canal; em
  recuperação de tarefas persistidas, somente o canal local registrado é
  resolvido e uma origem antiga/desconhecida não é enviada a outro canal.
- `app/quit/MikaQuit.kt`: removidas referências residuais a `app.wechat` e
  `WechatService`, preservando o encerramento dos demais serviços.
- `TaskService.kt`: origem de canal desconhecida retorna erro explícito
  `unsupported_channel`, em vez de acionar WeChat por fallback. Preservados
  `LocalChannel`, `Channel`, `ChannelGateway`, `Outbox` e toda a
  abstração de integração futura.
- `SettingsScreen.kt` e `SettingsScreenTest.kt`: removida a página do bot
  e atualizado o teste da navegação, que agora verifica sua ausência.
- `docs/dev/architecture.md` atualizado para refletir a remoção.
- **Preservados** `core/src/main/resources/knowledge/apps/wechat/APP.md`,
  os exemplos de uso do App WeChat, seus assets de demonstração e os testes
  genéricos de `ChannelGateway`. Nenhuma lista de aplicativos proibidos.

Licenças e créditos originais intocados. Este PR não altera o transporte de
ponte do Muse e é independente da remoção OTLP.

## Remoção 3 — buscas externas You/Exa/Jina (PR independente)

- Excluídos `YouSearch.kt`, `ExaSearch.kt`, `JinaSearch.kt` e o teste
  exclusivo do You. `ProviderSearchTest.kt` mantém testes SearXNG,
  removendo apenas os testes dos provedores eliminados.
- `WebSearchProvider.kt`: só `SearXNG` continua disponível por opção
  explícita; identificadores persistidos `you`, `exa`, `jina` não
  viram automaticamente um serviço alternativo.
- `app/settings/WebSearchSettings.kt`: preferência legada de provedor
  removido é desativada (fail-closed), e as chaves de API antigas são
  apagadas do armazenamento; `service()` só cria SearXNG explicitamente
  habilitado com endpoint válido.
- `app/ui/WebSearchSettings.kt`: retirados seleção e formulários de
  chaves dos serviços externos. A tela permite apenas uma instância
  SearXNG do próprio usuário, sem ativação automática.
- Atualizados testes de migração e as traduções `strings_web_search.xml`
  nos dez idiomas; mantidos `SearchHttp.kt`, `WebSearch.kt`, o
  navegador embutido (`browser/SearchEngine.kt`) e o contrato
  `web_search` para instância SearXNG explicitamente configurada.
- Nenhuma alteração no `ChatModel.kt`, `OpenAiCompatModel`, gateway ou
  permissões de aprovação. Licença Apache-2.0 e créditos preservados.

## Segurança — remover a API de PNG bruto do display

- `AgentDisplay.latestFramePng()` foi removido, junto com o uso de
  `ByteArrayOutputStream` que serializava o quadro completo sem aplicar a
  máscara de regiões sensíveis.
- `latestFrameBitmap()` permanece para o caminho interno de OCR/captura,
  que faz validações e máscara em `AndroidScreen.screenshotPng()` antes de
  qualquer codificação que possa ser exposta a ferramentas.
- Teste `AgentDisplayApiSafetyTest` verifica a ausência do acesso PNG bruto e
  a preservação do método interno de bitmap. Nenhuma alteração em
  `ChatModel.kt` ou `OpenAiCompatModel.kt`.

## Remoção final — somente Custom OpenAI-compatible (PR de rascunho)

- Removidos OAuth de modelos ChatGPT/xAI/OpenRouter, clientes de assinatura e
  painéis de login, além das implementações de modelo Anthropic/Gemini/Responses.
- Mantidos **intactos** `core/model/ChatModel.kt` e
  `core/model/OpenAiCompatModel.kt`, usados pelo transporte Custom/MUSE.
- Lista de presets reduzida a Custom. O runtime de `ProviderStore` rejeita
  métodos fora de Chat Completions, fixa a Base URL do usuário e migra JSON
  antigo em modo fail-closed, excluindo serviços OAuth, configurações e Key
  órfãos sem reenquadrar contas antigas como Custom.
- `core/cap/mcp/McpOAuth.kt` continua suportado: `core/oauth/Pkce.kt`
  e `core/oauth/LoopbackCallback.kt` são **preservados** porque
  oferecem autenticação ao MCP, não login de modelo.
- Testes da conexão Custom, migração e isolamento de origem reforçados; testes
  exclusivos dos adaptadores removidos foram retirados. README e documentação
  de modelos atualizados. Sem alterações em MCP, `LICENSE` e `NOTICE`.

**PR em rascunho: revisão Muse obrigatória, CI 4/4 verde, sem merge.**
