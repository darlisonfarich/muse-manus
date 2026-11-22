#!/usr/bin/env bash
# Gera gadgetlink/src/test/resources/mika_tools.json a partir do código do servidor mika.
# Sem Gradle (o daemon trava nesta VM): compila os 3 arquivos de ferramentas MCP do core deste repo
# por cima de um core já compilado (CORE_CLASSES, só as dependências — remote/ e device/) e roda o
# DumpMikaTools. Uso: dump_mika_tools.sh [repo] [core-classes]
set -euo pipefail
REPO=${1:-$HOME/workspace/muse-manus-build}
CORE_CLASSES=${2:-$HOME/workspace/muse-manus-repo/core/build/classes/kotlin/main}
OUT=${OUT:-$HOME/workspace/tools/kotlinc-direct/out-mika-dump}
C=$HOME/.gradle/caches/modules-2/files-2.1
J=$HOME/workspace/tools/jdk17/bin
jar(){ find "$C/$1" -name '*.jar' ! -name '*sources*' | head -1; }
COMP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.20):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.1.20):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.1.20):$(jar org.jetbrains.intellij.deps/trove4j/1.0.20200330):$(jar org.jetbrains/annotations/13.0):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0)"
LIBS="$(jar org.jetbrains.kotlin/kotlin-stdlib/2.1.20):$(jar org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.7.3):$(jar org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.7.3):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2)"
M=$REPO/core/src/main/kotlin/com/wangzan101/mika/core/mcp
rm -rf "$OUT"; mkdir -p "$OUT"
"$J/java" -Xmx1200m -cp "$COMP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -module-name core -Xfriend-paths="$CORE_CLASSES" -cp "$LIBS:$CORE_CLASSES" -d "$OUT" \
  "$M/McpTaskTools.kt" "$M/McpPhoneTools.kt" "$M/McpDeviceTools.kt" "$(dirname "$0")/DumpMikaTools.kt" 1>&2
"$J/java" -cp "$OUT:$CORE_CLASSES:$LIBS" DumpMikaToolsKt
