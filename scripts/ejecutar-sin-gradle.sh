#!/usr/bin/env bash
# Compila y ejecuta la demo sin Gradle (Linux/macOS con ZeroC Ice 3.7 instalado).
# Uso: ICE_JAR=/ruta/a/ice-3.7.10.jar scripts/ejecutar-sin-gradle.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ICE_JAR="${ICE_JAR:-/usr/share/java/ice-3.7.10.jar}"
OUT="$(mktemp -d)"
mkdir -p "$OUT/gen" "$OUT/cls"
slice2java --output-dir "$OUT/gen" src/main/slice/ApexStore.ice
javac -encoding UTF-8 -cp "$ICE_JAR" -d "$OUT/cls" $(find "$OUT/gen" src/main/java -name '*.java')
java -Dstdout.encoding=UTF-8 -cp "$OUT/cls:$ICE_JAR" apexstore.Demo
