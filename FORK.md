# Muse Manus — registro de fork modificado

Este repositório é um **fork modificado** do projeto [Mika](https://github.com/wangzan101/Mika)
(de wangzan101), distribuído sob a **Apache License 2.0**. Os arquivos `LICENSE` e `NOTICE`
originais estão mantidos sem alteração, assim como os avisos de copyright existentes nos
arquivos-fonte.

Este arquivo é o registro visível de modificação exigido pela Apache License 2.0, §4(b):
os arquivos alterados em relação ao projeto original estão listados abaixo.

## Nova identidade

- **Nome do app:** Muse Manus (variante de desenvolvimento: Muse Manus Dev)
- **applicationId / namespace Android:** `com.darlisonfarich.musemanus`
  (o original é `com.wangzan101.mika`)
- Os pacotes dos arquivos-fonte Kotlin **não** foram renomeados nesta etapa: continuam
  `com.wangzan101.mika`, o que é independente do `namespace`/`applicationId`.

## Modificações da branch `feat/muse-manus-identity`

1. **`app/build.gradle.kts`** — `namespace` e `applicationId` trocados de
   `com.wangzan101.mika` para `com.darlisonfarich.musemanus`; o nome da variante de
   desenvolvimento (`devInstall`) mudou de "Mika Dev" para "Muse Manus Dev".
   O `applicationIdSuffix` `.dev` foi mantido.
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
   Nota: a escrita em `.github/workflows/` não é permitida à integração de API usada
   neste projeto, então este passo é aplicado pela interface web do GitHub, neste mesmo PR.
5. **`FORK.md`** — este arquivo, novo.

## Observação de instalação

Como o `applicationId` mudou, o Muse Manus é um **app novo** para o Android: ele não
atualiza nem herda os dados de uma instalação do Mika original — os dois podem coexistir
instalados no mesmo aparelho.
