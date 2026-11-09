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
