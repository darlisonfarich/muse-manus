#!/usr/bin/env bash
# CI workaround for GitHub's 504 on Maven Central's Kotlin compiler JAR.
# The downloaded artifact must match the JetBrains Kotlin SHA-256.
set -euo pipefail

readonly path=".ci-maven/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.20"
readonly jar="kotlin-compiler-embeddable-2.1.20.jar"
readonly expected_sha256="c54a00718c8c0e3ee858bf42771c7f401c5e3c1738861e18e9536371042fa1b3"
mkdir -p "$path"

curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
  --connect-timeout 20 --max-time 300 \
  "https://maven.aliyun.com/repository/central/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.20/kotlin-compiler-embeddable-2.1.20.jar" \
  -o "$path/$jar"

printf '%s  %s\n' "$expected_sha256" "$path/$jar" | sha256sum --check --

curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
  --connect-timeout 20 --max-time 60 \
  "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.20/kotlin-compiler-embeddable-2.1.20.pom" \
  -o "$path/kotlin-compiler-embeddable-2.1.20.pom"

# Only the verified compiler JAR uses this runner-local Maven repo.
python3 - <<'PY'
from pathlib import Path
p = Path("settings.gradle.kts")
src = p.read_text()
old = """dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}"""
new = """dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(file(".ci-maven"))
            content { includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable") }
        }
        google()
        mavenCentral()
    }
}"""
if src.count(old) != 1:
    raise SystemExit("Unexpected settings.gradle.kts: refuse repository injection")
p.write_text(src.replace(old, new))
PY
