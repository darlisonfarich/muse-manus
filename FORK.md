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
