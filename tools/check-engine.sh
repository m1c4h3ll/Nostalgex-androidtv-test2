#!/usr/bin/env bash
# Compiles the engine and its checks with a bare kotlinc and runs them, no Gradle needed.
#   KOTLINC=/path/to/kotlinc/bin/kotlinc tools/check-engine.sh
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
OUT="${TMPDIR:-/tmp}/nostalgex-engine-check"
mkdir -p "$OUT"
"$KOTLINC" engine/src/main/kotlin $(find engine/src/test/kotlin -name '*.kt' ! -name 'GoldenTest.kt') -d "$OUT/engine.jar" 2>&1 | grep -v "^warning" || true
LIB="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib"
java -cp "$OUT/engine.jar:engine/src/test/resources:$LIB/kotlin-stdlib.jar" app.nostalgex.engine.GoldenMainKt
