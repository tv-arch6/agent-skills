#!/usr/bin/env bash
# Type-checks the glue classes (ExoPlayer + app dependent) against hand-written stubs. See stubs/README.md.
#   LIBS=/path/with/android-all.jar  verify/compile-glue.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="${LIBS:?set LIBS}"
OUT="${OUT:-$(mktemp -d)}"
ANDROID="$(ls "$LIBS"/android-all*.jar | head -1)"
SRC="$HERE/src/main/java/com/Cigram/vid"
STUBS="$HERE/verify/stubs"
ALL="$(find "$SRC" -name '*.java') $(find "$STUBS" -name '*.java')"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -cp "$ANDROID" -d "$OUT" $ALL
echo "glue compiles against the stubs: $(find "$OUT" -name '*.class' | wc -l) classes"
