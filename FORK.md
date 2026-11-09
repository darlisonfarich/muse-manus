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


## GER / PR-7 — teclas no display virtual e controles de áudio

- A allowlist de teclas agora registra o escopo junto da definição. HOME,
  Recentes, volume e controles de mídia só são aceitos pela família de tela
  do agente virtual; a injeção do shell também exige display pertencente ao
  agente. Power continua bloqueado. A validação padrão/main continua recusando
  as teclas com escopo virtual.
- O MCP direto oferece `phone_volume` (um passo para cima/baixo ou mute) e
  `phone_media` (play/pause, stop, next, previous). Ambos entram na fila por
  proprietário do GER e usam os mesmos `ready()`/`sensitive()` gates e journal
  PENDING fsync antes da execução, através da ferramenta de tecla já guardada.
- Limite: o Android pode tratar volume e transporte de mídia como controles
  globais do sistema, mesmo quando o evento é endereçado a um display virtual.
  O código impede injeção destinada ao display principal; o efeito isolado no
  hardware/ROM precisa ser confirmado no aparelho. Power não está incluído.

## Fase 2 / PR-A — proteção de OTP em observações ao vivo

- Quando uma tela contiver qualquer campo de senha (também em nós aninhados da árvore), sequências **isoladas de 4 a 8 dígitos** são substituídas por `[OTP REDACTED]` nos nomes dos elementos e nos textos, descrições, dicas e títulos das janelas.
- O mascaramento ocorre **antes** da estabilização de referências, diffs, observações, histórico e serialização MCP. Linhas do OCR passam pelo mesmo filtro após a proteção de regiões secretas.
- A geometria, as referências, o indicador `secret` e os identificadores de captura são preservados. Texto normal sem campo de senha **não** é alterado. Números embutidos em identificadores alfanuméricos ou com mais de oito dígitos não são classificados como OTP isolado.
- Este PR **não habilita** captura de screenshots sensíveis, digitação em senha, execução remota nem qualquer nova permissão. O PR-B deve recusar leitura de imagem de telas com senha e aplicar os gates existentes antes de agir.
- Testes JVM: mascaramento em árvore (inclusive aninhada), elemento, OCR, código em texto corrido, ausência de senha e observação `phone_observe`.


## Fase 2 / PR-B — controle direto do telefone pelo Muse (MCP)

- A API `/mcp` mantém o funil **inalterado** de `RemoteAccess` (Bearer, anti-Origin, rate-limit e IPs privados) e só registra ferramentas `phone_*` quando o pedido já está vinculado a um proprietário autenticado. Não há provider de modelo nesta rota; `McpPhoneTools` envia chamadas para `AndroidDirectPhoneHost`.
- `phone_status`, `phone_observe`, `phone_screenshot`, `phone_list_apps` são leitura; `phone_tap`, `phone_type`, `phone_scroll`, `phone_swipe`, `phone_gesture`, `phone_key`, `phone_back`, `phone_launch_app`, `phone_run` são ações; `phone_cancel` encerra a sessão do dono. `phone_run` limita a 30 ações conhecidas, verificadas individualmente.
- A tela virtual e o dispatcher ficam associados ao proprietário da conexão, com TTL de 15 minutos de inatividade e apenas uma sessão de controle por vez; conflito com tarefa em execução retorna `session_conflict`. Ref/snapshot do dispatcher são preservados entre chamadas.
- **Não é um caminho para executar comandos de sistema**: cada ação passa por `ActionExecutor.run(..., Origin.McpTool)`, `OperationGuards`, `ApprovalSettings.STRICT` e `AppAccessScreenPort`. Ações de risco externo/irreversíveis não herdam aprovação ou modo autônomo do app. No momento, essas ações retornam recusa e precisam ser concluídas/confirmadas pelo usuário no próprio aparelho; não existe uma ferramenta remota de aprovação.
- **Tela de senha:** leitura, screenshot e escrita por `phone_*` retornam `needs_user` ao detectar elemento/árvore com campo secreto, tela protegida ou falha de captura. Enquanto o PR-A estiver separado, não há sequer leitura remota desse contexto.
- **Kill switch:** `Configurações › Avançado › Acesso pelo computador (MCP) › Permitir controle direto pelo Muse`. Padrão **desligado** inclusive para instalações antigas. Desligar revoga as próximas ações; o MCP ainda responde às consultas permitidas.
- **Idempotência:** cada ação exige `request_id` estável. O host registra em arquivo privado de auditoria a marca PENDING com `fsync` **antes** da tentativa; uma ID já vista não é executada novamente, mesmo após reinício (`delivery_unknown`: observar o estado antes de prosseguir). `phone_run` deriva uma ID única por passo. Auditoria não grava textos digitados, capturas ou conteúdo de telas.
- **Limite importante:** as teclas HOME, Recentes, Volume e Power continuam bloqueadas pela allowlist existente do executor e do shell, pois afetam a tela principal/aparelho inteiro; ampliar isso sem um gate específico seria afrouxar uma proteção existente. O controle nesta fase é de aplicativos executados no display virtual.
- **Sem alteração** do relay/gateway, dos providers, de `RemoteAccess` ou da API de tarefas existente. PR em **rascunho**, sem merge. Necessário CI 4/4 verde, revisão Muse e teste ponta a ponta no POCO antes da integração.

## GER / PR 1 — ledger global e arbitragem de aplicativos

- O `GlobalExecutionRegistry` (GER) é único por processo, criado no `ResourceScheduler` de `MikaApp`. O `TaskCenter` registra exclusivamente as tarefas admitidas em execução; itens em fila não ocupam a vaga. A troca de tarefa na saída atualiza o GER no mesmo ponto do ciclo de vida.
- O `AndroidDirectPhoneHost` precisa pedir ao GER a vaga exclusiva `mcp:<hash do proprietário>` antes de criar/continuar uma sessão virtual. Enquanto houver tarefa admitida no `TaskCenter`, devolve `session_conflict`; se uma tarefa começar depois, a sessão fica suspensa para novas ações sem tomar a tela de volta.
- O `AndroidScreen` do host direto agora usa o mesmo `ResourceScheduler.reserveApp/appFree` usado pelo `TaskRun` (incluindo instalações em outros usuários Android). O GER mantém a titularidade do app da sessão direta até o cancelamento, expiração de 15 min ou fechamento do host; reservações do Muse não podem ser roubadas pela heurística de titular inativo. A sessão libera seus pacotes e a vaga do GER ao fechar.
- **Compatibilidade deliberada:** o `TaskCenter` continua decidindo admissão, paralelismo 1–3 e a fila persistente exatamente como antes. Tarefas existentes têm prioridade: o PR não introduz fila de intenções para o host direto, nem cria vagas paralelas para o MCP. A implementação dessas transições e do rodízio de telas fica para os PRs 2 e 8; não prometer execução automática de tarefas que receberam `session_conflict` nesta fase.
- **Limitações mantidas:** tela apagada, credenciais, aprovações, login e operações irreversíveis seguem os gates preexistentes; não há novo acesso a senhas, nenhuma IA local e nenhuma alteração à ponte de rede. A comunicação direta Muse ⇄ POCO e o Android em hardware real ainda exigem teste ponta a ponta antes de publicação.
- Testes JVM: concorrência do claim direto, escopo e revogação por proprietário, prioridade de tarefas já admitidas, sequência tarefa→fila→tarefa e arbitragem de pacote diante do host direto ativo.

## GER / PR 2 — fila de intenções no host MCP direto (sem merge até E2E)

- `GerIntentQueue` pertence ao `GlobalExecutionRegistry` da instância de `ResourceScheduler` do aplicativo. **É diferente da fila persistente do `TaskCenter`**: só mantém, enquanto o processo está vivo, chamadas `phone_*` que ainda não conseguiram executar. Não há segundo motor de tarefas; não há modelo de IA no Android.
- **Sessão direta suspensa ≠ intenção em espera.** A sessão suspensa mantém display, app reservado, proprietário e TTL de inatividade (15 minutos) enquanto tarefas `TaskCenter` têm prioridade. A intenção em espera é uma chamada MCP individual ainda não executada. Uma sessão pode estar suspensa com várias intenções do mesmo dono aguardando.
- FIFO por proprietário, até 8 chamadas por proprietário contando a chamada da frente; identificações repetidas enquanto pendentes/ativas devolvem `delivery_unknown`. `phone_status` e `phone_get_state` continuam imediatos, expondo `pendingIntents`; `phone_cancel` cancela todas as intenções pendentes do dono e, se houver, sua sessão. Não cancela uma ação que já começou a ser efetivada no sistema Android.
- A espera é feita **fora** do mutex do host. Cada tentativa reabre o mutex só para verificar condições, criar/reusar a sessão, observar a tela e executar. Ao liberar tarefa/vaga/pacote, o GER sinaliza os aguardantes; eventos da tela principal sem sinalização do sistema são revisitados por polling de até 500 ms. Configuração local persistente `McpSettings.directWaitTimeoutMs`: 120 s padrão, intervalo válido 5–300 s; nomes e parâmetros do protocolo MCP **inalterados**.
- A espera só é aceita **antes de gravar PENDING e antes de qualquer efeito**. Causas transitórias: `host_busy` (outro comando já ocupa o mutex), `task_running`, `direct_session_busy`, `app_busy`, `app_in_use_by_user` (observação prévia de pacote no display físico). No fim do prazo: `{ok:false,error:"wait_timeout",cause:"...",executed:false}` para chamadas que ainda não executaram; um `phone_run` parcialmente executado informa `executed:true`, `completed` e `results` anteriores, sem repetir os passos; lotação: `intent_queue_full`; cancelamento: `cancelled`. Os erros permanentes/segurança, como `needs_user`, `write_disabled`, `remote_access_disabled`, `phone_unavailable` e qualquer reprovação STRICT, continuam terminando imediatamente.
- Para `phone_run` há índice e resultados **em memória por chamada**; um bloqueio antes do próximo passo deixa o lote parado e, quando liberar, continua do próximo índice sem refazer passos já executados. Cada passo mantém seu `request_id` determinístico; o journal `direct-phone-audit.jsonl` continua gravando PENDING com `fsync` antes de cada ação. Uma reconciliação após morte do processo **não foi implementada** (etapa 3).
- Um bloqueio que só for descoberto **depois** do marco PENDING de uma ação não pode ser repetido automaticamente sem provar ausência de efeito: prevalece o journal e a recusa `delivery_unknown`. O host pré-checa os conflitos de aplicativo antes de gravar o journal, incluindo aplicativo aberto no display físico. Nunca reexecutar uma ação incerta para disfarçar o erro.
- Tarefas admitidas continuam prioritárias. O `TaskRun` usa a arbitragem de apps do `ResourceScheduler` também quando o paralelismo padrão está desligado mas há uma sessão MCP viva; o `awaitApp` já existente permite esperar a liberação do pacote sem roubar a sessão. Não há nova permissão Android, escrita em senha, aprovação remota, controle de wake/unlock nem multiplas telas MCP.
- **Validação obrigatória:** JVM para fila/FIFO/limite, cancelamento, timeout com causa, duplicidade, kill switch, retomada após tarefa e sessão suspensa; CI 4/4; E2E do Muse no POCO verificando execução após liberação da tarefa, timeout quando o usuário mantém app em uso e cancelamento durante espera. Revisão Muse e evidência E2E **antes de merge**.

## GER / PR-8 — slots MCP e governador de admissão

- O GER aloca slots numerados para sessões diretas MCP (padrão: três); cada proprietário tem sua sessão/display, e encerramento, falha de criação e expiração liberam app e slot. A sessão retém o slot enquanto suspensa por tarefa até o TTL de inatividade (15 min por padrão). Lotação entra na fila de intenções e termina pelo timeout já existente com causa `slot_budget_exhausted`.
- O display principal do usuário não integra o conjunto de slots. Sessões MCP não iniciam nem movem conteúdo nele; tarefas admitidas suspendem ações nos slots até poderem prosseguir.
- `GlobalExecutionRegistry.Budgets` define máximo de tarefas (3), tarefas por conversa (1), slots (3), TTL de sessão (15 min) e TTL nominal de tarefa (1 h). A capacidade do TaskCenter permanece o limite efetivo mais baixo. Erros de orçamento de tarefa são códigos estáveis (`task_budget_exhausted`, `owner_budget_exhausted`); a fila de intenções preserva chamadas em espera, sem descarte silencioso.
- Limites desta entrega: o TTL de sessão é aplicado pelo host; TTL de tarefa é orçamento configurável para integração ao ciclo de execução e não força cancelamento de uma tarefa em andamento. O governador não estima memória/bateria em tempo real. São necessários testes em dispositivo para validar estabilidade/custo de três displays e coexistência real de apps.
- Testes JVM cobrem alocação/reuso, esgotamento sem roubo de slot, orçamento de tarefas e prioridade de tarefa sobre execução MCP.

## Limites de telas: computador do Muse vs navegação pessoal no POCO (decisão do usuário)

**Regra de produto confirmada (2026-11-08):** qualquer limite configurável de **até três janelas/sessões** que o Muse possa escolher é um **orçamento de janelas de trabalho no computador virtual do Muse**, não uma restrição sobre a navegação física do usuário no POCO. O usuário pode abrir quantos aplicativos e janelas o Android/HyperOS suportar. A fila e o GER não podem restringir a abertura, a alternância, o uso em tela dividida ou o primeiro plano da tela física do usuário.

**O código atual não é a implementação dessa política de janelas cloud.** `ParallelCapability.detect` e `FeatureFlags.parallelMax` ainda limitam **tarefas do agente que criam VirtualDisplays no próprio Android** (1–3), por motivos de recursos/compatibilidade do POCO. Isso **não é** um limite de janelas do usuário, nem equivale a 3 janelas no computador do Muse. Não trocar nomes ou remover esse limite técnico do Android sem testar compatibilidade: tais displays continuam consumindo recursos do telefone. A política de três janelas no computador deve ser implementada e controlada **no ambiente do Muse**, fora deste repositório, e não deve contar nem controlar janelas físicas do aparelho.

**Prioridade e não interferência:** quando o usuário estiver usando um aplicativo na tela principal, o executor Android deve esperar, usar outra rota autorizada (web/API no computador, quando realmente disponível), ou informar `app_in_use_by_user` / timeout ao Muse; nunca roubar o aplicativo, bloquear a interação do usuário, fechar sua janela, forçar troca de tela ou destravar o aparelho. A ausência de resposta do agente não deve bloquear a navegação local. A fila de intenções do PR #15 limita **pedidos do executor**, não apps/janelas do usuário. Nenhum novo limite de tela física é introduzido neste PR.

**Aceitação no E2E:** durante tarefa e espera do MCP, usuário abre e alterna livremente entre diversos apps, inclusive o app que o Muse queria abrir; tela dividida e navegação padrão continuam funcionando sem interrupção. A chamada remota aguarda ou termina com causa clara, e só retoma após reconferir que o alvo deixou de estar em uso. O Muse deve validar separadamente a limitação de janelas no próprio computador antes de afirmar que a regra de três cloud existe.

## PR #15 — correção da revisão Muse: leitura de display virtual com tela física apagada

A fila de intenções não pode introduzir um requisito novo de tela principal acesa para operações de **leitura** do display virtual. Em `AndroidDirectPhoneHost`, `phone_observe`, `phone_list_apps` e `phone_screenshot` **não** chamam `ready(s)`, portanto não são recusadas somente porque o POCO está bloqueado ou com a tela principal apagada. Essa exceção é exclusiva da pré-condição da tela física; continuam exigidos: acesso MCP autenticado/ativado, GER (`canUseDirect`), exclusividade de sessão, política de conteúdo sensível, `AppAccessScreenPort` e nova verificação contra retorno de captura protegida. Se a infraestrutura de tela virtual estiver indisponível, a leitura ainda poderá falhar — não há promessa de desbloqueio do Android.

As operações que **alteram** o aparelho, incluindo cada passo de `phone_run`, continuam obrigatoriamente sujeitas a `ready(s)`, `ActionExecutor`, `ApprovalSettings.STRICT`, kill switch e todos os demais gates anteriores. A mudança não altera wake/unlock nem aprovações (etapas futuras).

Testes JVM: `DirectPhoneScreenPolicyTest` verifica classificação de todas as ferramentas de leitura e escrita existentes. **E2E adicional:** com a tela física do POCO apagada, `phone_observe`/`phone_screenshot` devem continuar acessando somente a tela virtual não sensível; `phone_tap` deve continuar recusando escrita sem desbloqueio. Esta alteração requer revisão Muse e E2E antes de merge.

## PR #15 — correção E2E: `app_in_use_by_user` precisa aguardar na fila

**Causa comprovada no código:** `ShellService.displayOfPackage(pkg)` retorna apenas displays virtuais pertencentes ao Muse Manus; nunca retorna o display físico 0. O `AndroidDirectPhoneHost.launchBlocker` original supunha que `-1` significava app livre e, como consequência, gravava `PENDING` antes de `ShellService.startApp` rejeitar corretamente o Spotify visível na tela principal com `START_IN_USE_ON_MAIN`. Nessa altura, a mesma `request_id` não poderia voltar à fila sem violar idempotência.

**Correção:** sem alterar AIDL, permissões, credenciais ou protocolo MCP, a pré-checagem do host agora usa **`IMikaShell.visibleOnMainScreen(packageName, userId)`** já existente, que usa o mesmo predicado `inUseOnMainScreen` de `startApp/restartApp`. O usuário Android corresponde ao UID do processo do Muse Manus (UID / 100.000). `true` → `GerIntentAttempt.Blocked("app_in_use_by_user")`; falha Binder / estado desconhecido → `Blocked("app_presence_unknown")` (fail closed); reserva de outro executor ou outro display virtual → `Blocked("app_busy")`; app livre → pode prosseguir. A verificação é feita antes da admissão da ação e **repetida imediatamente antes do `markOnce(PENDING)`**, inclusive dentro de `phone_run`. Nenhum aplicativo é fechado, movido ou tomado do usuário durante a espera.

A fila continua limitada a 120 segundos por padrão (configurável), é cancelável, FIFO por proprietário e reaplica todos os gates existentes. Uma corrida residual entre a checagem e o lançamento real ainda pode acontecer porque o usuário pode abrir o app a qualquer instante; **após PENDING, a tentativa não é repetida automaticamente**, por segurança. A checagem correta elimina o falso negativo sistemático anterior, sem afirmar atomicidade impossível entre uma leitura e o Android launching.

**Teste JVM de regressão:** `DirectPhoneLaunchPreflightTest` cobre `displayOfPackage == -1` enquanto Spotify está na tela física, classificação da causa, app ocupado em outro display, estado desconhecido, fila acordando após liberação sem executar duas vezes e sem gravar PENDING antes do desbloqueio, timeout com `app_in_use_by_user` e cancelamento. **E2E obrigatório no POCO:** deixar Spotify visível na tela principal, iniciar `phone_launch_app` do mesmo pacote pelo Muse, observar `pendingIntents > 0`, mudar de app, confirmar que a chamada conclui **sem que o Spotify saia da sua tela por ação do robô**; repetir mantendo Spotify visível até `wait_timeout/cause=app_in_use_by_user` e cancelando com `phone_cancel`. Ainda requer revisão Muse e aprovação antes de merge.


## GER — correção isolada após PR #15: lançamento retomado com áudio ativo (POCO / HyperOS)

**Evidência de 2026-11-08 (não alterar o que passou):** com Spotify visível no
display físico, o pedido ficou em fila (`pendingIntents=1`) por cerca de 48 s
sem roubar a tela do usuário. Após sair da tela do app, uma tentativa consumiu
`PENDING` e retornou `delivery_unknown` sem abrir o display virtual; outra
ficou mais de 45 s dentro de `startApp` enquanto pedidos pendentes subiam até
3. `phone_cancel` removeu as intenções. Spotify ocioso em segundo plano abriu
duas vezes no display virtual. A causa exata no HyperOS **não está provada**:
a atividade pode reutilizar uma task do display 0 mesmo com
`ActivityOptions.setLaunchDisplayId`; um foreground service de áudio é
distinto da atividade e não prova por si só que a interface está em uso.

**Correção no host, não na fila:** mantém `visibleOnMainScreen`, preflight
duplicado, `GerIntentQueue`, timeout de espera, gates STRICT e o journal
`fsync(PENDING)` intactos. Somente o caminho `phone_launch_app` que JÁ passou
por `PENDING` recebe limite de 10 s para **resposta MCP**, não para o Binder
Android. O trabalho do Android continua isolado uma única vez, sem ser
cancelado/reexecutado cegamente. Prazo esgotado devolve
`{ok:false,error:"launch_timeout_unknown",cause:"start_app_timeout",executed:false,executionUnknown:true,retryable:false}`;
`executed:false` aqui indica **ausência de sucesso confirmado**, não prova
que o Android nunca executará a tentativa. Exceção do executor informa
`cause:"launch_executor_exception"`. O journal continua proibindo usar a
mesma `request_id` em qualquer replay. A conclusão real, quando houver,
fica registrada pelo worker no journal; não se grava falsamente "concluído"
quando apenas o prazo MCP acabou.

**Contenção:** enquanto um Binder `startApp` antigo ainda puder terminar,
novas ferramentas `phone_*` (exceto `phone_status` e `phone_cancel`) falham
rápido com `launch_in_flight` **antes de PENDING**, em vez de empilhar
execuções. `phone_cancel` limpa a fila; se existir um start já emitido,
a sessão/display só serão liberados após o worker finalizar
(`closePending:true`, `closed:false`). Não há maneira segura de matar uma
transação Binder que já entrou no Android sem a possibilidade de efeito tardio.
Caso nunca termine, mantenha a sessão em quarentena e NÃO force replay:
a pessoa pode fechar/reiniciar manualmente o serviço após investigar o estado.

**Lote `phone_run`:** erro incerto em `phone_launch_app` não incrementa
`completed`; informa o prefixo confirmado e não tenta o passo seguinte.
Os checkpoints duráveis do PR #16 continuarão a registrar apenas passos com
sucesso. `phone_observe` deve ser feito após a pendência terminar, antes de
qualquer novo ID de escrita.

**Testes JVM:** `DirectPhoneLaunchBoundaryTest` simula `startApp` bloqueado
por reprodução, deadline sem sobreposição, conclusão tardia sem replay,
desconexão do chamador e lançamento normal em segundo plano. Executar também
`DirectPhoneLaunchPreflightTest`, `GerIntentQueueTest` e a CI 4/4.

**Roteiro E2E para o Muse (o usuário controla a tela física):**
1. Spotify em primeiro plano, música tocando: Muse chama
   `phone_launch_app(package="com.spotify.music",request_id=<ID-A>)`,
   consulta `phone_status` e verifica `pendingIntents=1`, nenhum app
   removido da tela principal e nenhum PENDING antes de liberar.
2. Ainda com música tocando, ir à tela inicial ou a outro app sem pausar
   o áudio: a intenção sai da fila, e o resultado deve ser **ou** abertura
   confirmada na tela virtual **ou** erro com causa precisa; após no máximo
   10 s de execução do host, `launch_timeout_unknown/start_app_timeout`
   se o launch ficar preso. Nenhuma segunda execução com a mesma ID.
3. Se `launch_timeout_unknown`, tentar outro `phone_launch_app` com ID-B
   antes da conclusão do Binder: receber `launch_in_flight` imediatamente,
   com `pendingIntents` não crescendo por causa dessa tentativa. Repetir
   ID-A nunca deve abrir outra instância; o journal retorna
   `delivery_unknown` após a operação terminar. Não usar outro ID para
   tentar vencer uma operação de resultado ainda desconhecido.
4. `phone_cancel` durante a pendência: intenção cancelada e
   `pendingIntents=0`; se um lançamento já tiver sido enviado ao Android,
   `closePending=true` até finalizar, sem tomar a tela física.
5. Com Spotify pausado/ocioso em segundo plano, após resolução da
   tentativa anterior, novo ID-C deve abrir na tela virtual uma única vez
   e preservar o primeiro plano do POCO.
6. `phone_run` com `phone_launch_app` lento seguido de `phone_tap`:
   timeout incerto NÃO executa o toque, não conta o lançamento como
   `completed` e exige observação fresca; repetir o lote não duplica
   o primeiro passo. Verificar também `phone_observe` com tela física
   apagada e recusa de `phone_tap` com tela bloqueada.
7. Se reproduzir a falha, coletar apenas logs técnicos filtrados de
   `ActivityTaskManager`, `dumpsys activity activities` (task/display) e
   `dumpsys media_session` (estado da reprodução), sem coletar tokens,
   textos, credenciais ou conteúdo da tela.

**Gate:** PR em rascunho empilhado na branch #15; revisão independente Muse,
CI 4/4 e E2E no POCO antes de integrar. Não fazer merge automático.

## GER / PR 3 — checkpoints duráveis do `phone_run` (etapa 3, primeira entrega)

- Branch dependente do PR #15, sem merge. O journal pré-existente `direct-phone-audit.jsonl` permanece a **autoridade contra reexecução**: marca `PENDING` com `fsync` antes de executar qualquer ação.
- `DirectPhoneBatchCheckpoints` registra em `direct-phone-batches.log`, também com `fsync`, o manifesto de cada lote (`owner + request_id` transformados em hash SHA-256, hash dos comandos e quantidade de passos) e um evento `OK` somente após o executor retornar sucesso. **Nenhum argumento, texto de tela, senha, captura, token ou resultado de ação é salvo.**
- Enquanto uma chamada MCP continua viva, `BatchProgress` retoma os passos seguintes após esperas da fila exatamente como na etapa 2. Após desconexão/reinício, o mesmo `request_id` e comandos idênticos recuperam o **prefixo de sucessos duráveis**. Um lote com passos já confirmados devolve `reconciliation_required`, `completed`, `nextIndex` e `requiresFreshObservation=true`, sem disparar novas ações.
- Isso é deliberadamente **fail-closed**: a tela pode ter mudado durante uma queda. O Muse precisa usar `phone_observe`, comparar o estado atual ao objetivo e decidir a continuação com **nova** `request_id` para os passos ainda necessários. O Android não inventa a reconciliação nem executa toques cegamente. Se houve uma ação depois do `PENDING`, mas sem sucesso durável, o journal existente a impede de repetir; a resposta também exige reconciliação.
- Um `request_id` repetido com comandos diferentes retorna `checkpoint_mismatch`. Corrupção do journal retorna `checkpoint_corrupt` sem execução. Falha de gravação depois de um passo retorna `checkpoint_unavailable` com indicação de ação incerta. Os registros são isolados por proprietário.
- **Ainda fora do escopo:** transporte que reconecta automaticamente pelo lado Muse, resolução semântica das alterações da tela, recuperação da fila de intenções ainda não iniciadas após morte do processo, reinício automático do serviço Android e aprovação de ações sensíveis. Esta entrega permite a recuperação segura do estado e sua reconciliação, **não** promete autonomia integral após reboot.
- **Testes requeridos antes de merge:** JVM para prefixo persistido/reabertura, mudança de comandos, sequência fora de ordem, isolamento, arquivo parcial e ausência de credenciais; CI 4/4. E2E no POCO: executar passos 0–1, interromper transporte ou processo, repetir com a mesma ID, conferir ausência de replay e retorno `reconciliation_required`; observar tela fresca, concluir somente ações realmente faltantes sob nova ID. Também testar perda entre `PENDING` e `OK`, tela física em uso, cancelamento, tela protegida, kill switch e regressões da etapa 2. Revisão independente Muse e autorização expressa continuam obrigatórias.


### Integração com a retomada limitada do PR #17

Após integrar os dois caminhos em `AndroidDirectPhoneHost.batch`, `phone_run` reconhece **ambos** `delivery_unknown` e `launch_timeout_unknown` como efeitos incertos. Não soma o passo em `completed`, não grava um evento `OK` em `DirectPhoneBatchCheckpoints`, não avança para o próximo comando e devolve `reconciliation_required`, `executionUnknown=true`, `uncertainStepIndex` e `requiresFreshObservation=true`. O `startApp` pendente permanece em execução única sob o journal do PR #17; nenhum caminho faz replay para contornar o timeout. Confirmar essas invariantes novamente na CI do diff rebaseado e no E2E integrado.

## GER etapa 3 / PR 2 — reconexão do cliente por estado (PR em rascunho)

**Dependências:** PR #15 (fila), PR #17 (limite/estado do lançamento), PR #16
(checkpoints). Este PR está empilhado na branch #16, ainda **sem** o código do
#17, que será integrado pela ordem aprovada pelos responsáveis. Não rebasear
antes dos merges; jamais fazer merge automático.

### Interface de reconciliação, sem novo privilégio

- Novo `phone_reconcile` no MCP autenticado, marcado **read-only** e sem
  criar sessão, display ou intenção na fila. A consulta exige `request_id`
  **original** e `kind: "action" | "batch"`; lote exige a lista `commands`
  **idêntica** à chamada original, somente para confirmar o fingerprint.
  Não existe caminho de escrita, retry, mudança de `request_id` ou gravação
  de `OPEN/PENDING` nesse endpoint.
- Para ações isoladas, o servidor deriva novamente o hash do proprietário
  autenticado + `request_id` e lê apenas seu próprio journal
  `direct-phone-audit.jsonl`: `not_seen`, `pending_unknown`,
  `completed_ok`, `completed_unconfirmed` ou `journal_corrupt`.
  Nem `not_seen` prova que nada aconteceu, nem `completed_ok` prova que
  a tela **continua** no mesmo estado. Todos retornam
  `retryable:false` e `requiresFreshObservation:true`.
  O journal bruto, argumentos, capturas, textos e hash **não** saem pelo MCP.
- Para `phone_run`, `DirectPhoneBatchCheckpoints.inspect()` não grava
  novos checkpoints: `checkpointState=not_seen|found`, `completed`,
  `nextIndex` e `nextJournalState` do primeiro passo ainda não
  confirmado (derivado do ID determinístico do passo). Fingerprint/total
  alterados resultam em `checkpoint_mismatch`; truncamento/corrupção
  resulta em `checkpoint_corrupt`, ambos falha segura. Prefixo confirmado
  **não** autoriza reexecutar o sufixo automaticamente: Muse observa,
  reconcilia e decide nova tarefa/ID com base no estado atual.
- O campo canônico para incerteza passa a ser `executionUnknown` no
  PR #16 e neste PR, igual ao #17; não confundir com `executed:false`,
  que é ausência de confirmação, não prova de ausência de efeito.
- `phone_status` e `phone_reconcile` são metadados e podem ser
  consultados mesmo durante `launchInFlight`; `phone_observe`/
  `phone_screenshot` permanecem protegidos durante o Binder não resolvido
  na implementação do #17. **Não** isentar leituras que possam retornar
  tela inconsistente/tardia antes da conclusão do lançamento.
  `phone_list_apps` também mantém a guarda atual, para não abrir um
  caminho paralelo sem teste de sensibilidade/estado.

### Procedimento do cliente (timeout MCP ≈ 45 s)

O cliente MCP externo do Muse **não está versionado neste fork Android**.
`gpt-muse-bridge` é uma caixa postal GitHub Issues, e não o processo MCP
que controla a tela. Por isso este PR não pode alterar o hook real de
reconexão da sessão do Muse sem o código daquele cliente. Entrega uma
interface Android e o helper executável de leitura
`tools/mika/scripts/muse_phone_recover.py`, que o cliente Muse pode invocar
depois de reconectar. O helper usa a API MCP JSON-RPC existente sobre HTTP,
somente `phone_status`, `phone_reconcile` e, quando
`launchInFlight=false`, `phone_observe`. Nunca envia `phone_run`,
`phone_launch_app` ou outro comando de escrita e não imprime conteúdo da
tela, senha ou token.

Exemplo seguro, com o token no ambiente do processo (não nos argumentos,
documentos ou histórico do terminal):

```sh
export MUSE_MCP_TOKEN='(definido no cofre; não colar no chat)'
python3 tools/mika/scripts/muse_phone_recover.py \
  --url http://100.64.1.2:8765/mcp \
  --kind action --request-id ger_spotify_A_20261108
```

O endereço é **exemplo**. Para lote, usar `--kind batch` e
`--commands-file` com o array JSON original. A saída sempre declara
`safeToReplay:false` e `decision:manual_reconciliation_required`.
O helper é invocado **sob demanda**: não altera o timeout de 45 segundos
do cliente existente, não reconecta o SDK automaticamente e não tenta
reexecutar a solicitação. Seu uso no hook do Muse exige adaptação no
ambiente real do cliente.

### Roteiro E2E no POCO (sob revisão do Muse)

1. Com Spotify em primeiro plano e tocando, chamar
   `phone_launch_app(package="com.spotify.music", request_id="ger_reconnect_A_20261108")`.
   Confirmar `pendingIntents=1` e nenhum toque na tela do usuário.
2. Deixar a ponte MCP do Muse expirar em ~45 s, sem gerar nova ID;
   manter o Spotify no primeiro plano por mais tempo e depois liberar.
   Reconectar, chamar `phone_status` e
   `phone_reconcile(kind="action",request_id="ger_reconnect_A_20261108")`.
   Esperado: sem lançamento adicional; `journalState` informa apenas
   a marca que realmente existe. Se `not_seen`, **não** concluir que o
   app jamais executou; se `pending_unknown`, nenhuma repetição.
3. Se `launchInFlight=true`, consultar `phone_status` e
   `phone_reconcile` novamente, sem `phone_observe` (a tela poderia
   mudar depois). Após `launchInFlight=false`, chamar `phone_observe`.
   Esperado: observação atual, proteção de tela sensível intacta.
4. Após um retorno de `launch_timeout_unknown`, reconectar e
   reconciliar a **mesma** ID; confirmar `retryable:false`,
   `requiresFreshObservation:true`, nenhum segundo `startApp`.
5. Em um `phone_run` seguro de dois passos, forçar perda da conexão
   depois de um passo `OK`, antes do seguinte. Reabrir MCP, chamar
   `phone_status`, `phone_reconcile(kind="batch",request_id=<ID>,
   commands=<array ORIGINAL>)`. Esperado: `completed=1`,
   `nextIndex=1`; o próximo passo não é executado sozinho.
   Trocar os comandos com a mesma ID: `checkpoint_mismatch` e nenhum
   efeito. Verificar marca `reconciliation_required` no fluxo antigo.
6. Repetir para ação após PENDING, mas antes de OK: consulta retorna
   `pending_unknown`/estado incerto, sem retry, mesmo que a tela
   pareça não ter mudado.
7. Executar com outra credencial/proprietário MCP válido: a mesma
   `request_id` não pode revelar o journal de outro proprietário
   (`not_seen`/checkpoint ausente). Verificar também journal corrompido,
   kill switch, bloqueio de senha/OTP, tela física apagada e
   `phone_cancel` sem afetar a tela física.

**Critérios de aprovação:** CI JVM/Python/Android/Go **4/4 verde**, testes
de isolamento e falha segura, revisão independente Muse, E2E com a build
**integrada**, autorização explícita do Darlison antes de qualquer merge.


## MUSE MANUS NEXT — GER PR-5 (concessões de rotina com escopo, locais)

**Defesa em profundidade:** o novo `ScopedGrantRegistry` mantém concessões
**somente em memória de processo**, com app pelo package name exato,
identificador de conta explícito, classe de ação restrita a
`Navigate`/`Edit`, duração entre 1 ms e **15 min** e revogação imediata.
Usa relógio monotônico; relógio revertido invalida concessões. Em
reinício do app, **todas** as concessões expiram (não são recarregadas).
Emissão exige ação do usuário na tela local de gerenciamento, sob
**Conectar computador → Gerenciar concessões**. Não há endpoint MCP
para criar/alterar grants. Tentativa de emissão sem fsync do log privado
resulta em **nenhuma concessão**; revogar/expirar remove permissões mesmo
com erro de disco. O log não guarda pacote, conta, senha, captura ou
argumentos, apenas ID aleatória, classe, prazo e tipo de evento.

**Limite deliberado desta etapa:** uma etiqueta de conta digitada pelo
usuário **não é prova** da identidade logada num aplicativo Android.
O registry ainda **não autoriza** ações do executor; o PR-6 terá de
adicionar um canal de consentimento autenticado e uma vinculação
verificável de conta/sessão antes que as concessões possam ser
consultadas por decisões de risco. Esta separação é a forma segura de
evitar que texto no screenshot, arguments MCP ou o próprio agente
imitem a identidade de conta. Regras de `ConfirmationGate` de uso único,
`ApprovalPolicy` (pagamentos remotos sempre `Deny`), proteção de
senha/OTP, `directWritesEnabled` e permissões STRICT permanecem
**inalteradas**. Não interpretar a existência de uma concessão como
aprovação efetiva para enviar, postar, excluir, comprar ou pagar.

**Testes:** JVM `ScopedGrantsTest` verifica limite 15 min, expiração no
instante exato, rollback do relógio, pacote/conta/classe diferentes,
revogação, ausência de persistência entre processos e
`ApprovalPolicy.pay` negado remotamente. UI deve permitir criar,
listar, expirar e revogar, sem conceder privilégios remotos; conferir
FORK.md e CI 4/4 antes do merge. Sem instalação automática no POCO.

## MUSE MANUS NEXT — GER PR-6 (base de tokens de aprovação)

O core agora contém `ChatApprovalTokens`: tokens HMAC-SHA256 versionados
vinculam proprietário, ferramenta, digest canônico dos argumentos, escopo,
expiração curta e ID aleatório. A API de consumo registra o ID por meio de
`ConsumedApprovalIds` antes de aprovar; o adaptador precisa persistir esse
registro de forma durável. Token inválido, expirado, repetido ou incompatível
tem resultado distinto e nunca é aprovado.

**Fiação (commit seguinte, Muse):** a chave vive no Android Keystore
(`ChatApprovalTokenStore`, alias HMAC próprio) e os IDs consumidos vão para
um ledger append-only com `fsync` que falha fechado se corromper
(`chat-approval-consumed.log`). A emissão é a ferramenta MCP
`phone_approval_issue` (fora da fila de intenções; exige acesso remoto e
`directWritesEnabled`; registra a emissão no journal de auditoria só com
digest). A ação-alvo apresenta `approval_token` nos argumentos; o host o
retira dos argumentos executados e o `ConfirmDecision` o resgata **somente**
quando a etapa exigiria aprovação e não há gate local de concessões —
amarrado ao nome interno da ferramenta e aos argumentos **como submetidos**
(digest canônico), com códigos estáveis `approval_token_invalid`,
`approval_token_expired`, `approval_token_replay` e
`approval_token_mismatch` (via `confirmation_mismatch`). Passos de
`phone_run` apresentam o próprio token nos argumentos de cada passo.

**Limites mantidos:** pagamento **nunca** é destravado por token (a negação
de `ApprovalPolicy` para `IrrevAction.pay` precede o resgate, e o resgate
também o exclui explicitamente); STRICT, grants PR-5 e a regra de o remote
não consumir aprovações locais permanecem inalterados — o token é um canal
novo e separado, emitido pelo próprio aparelho.

**Testes JVM:** caminho feliz e replay, expiração, mismatch de argumentos e
adulteração da assinatura em `ChatApprovalTokensTest`; mapeamento de
resultados e códigos estáveis em `ChatApprovalGateTest`.
