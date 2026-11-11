#!/usr/bin/env bash
# Regenerates the Plebcity icons with RuneLite's own RuneScape fonts.
#
#   tools/generate-icons.sh preview                 # 8x previews of all variants -> tools/preview/
#   tools/generate-icons.sh install <a|b> <none|brown|gold> <one|two>
#       writes the 16x16 sidebar icon (a/b) and the Plugin Hub icon.png (one- or two-line layout)
#
# The generator is compiled into build/icon-tool/, never into the plugin jar.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CP="$(./gradlew -q -I tools/classpath.gradle printIconToolClasspath | sed -n 's/^CP=//p')"
OUT="$ROOT/build/icon-tool"
mkdir -p "$OUT"
javac -d "$OUT" -cp "$CP" tools/IconGenerator.java
java -Djava.awt.headless=true -cp "$OUT:$CP" IconGenerator "$1" "$ROOT" "${@:2}"
