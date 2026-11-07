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
