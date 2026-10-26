#!/usr/bin/env bash
# Runs the tests that need the ExoPlayer stubs (see verify/stubs/README.md): LIBS=/path/with/jars verify/run-glue-tests.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="${LIBS:?set LIBS}"
OUT="${OUT:-$(mktemp -d)}"
ANDROID="$(ls "$LIBS"/android-all*.jar | head -1)"
CP="$ANDROID:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar"
mkdir -p "$OUT/main" "$OUT/test"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -cp "$CP" -d "$OUT/main" \
  $(find "$HERE/src/main/java" "$HERE/verify/stubs" -name '*.java')
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -cp "$OUT/main:$CP" -d "$OUT/test" \
  "$HERE/src/test/java/com/Cigram/vid/CigramSubtitleOverlayStyleTest.java"
exec java -cp "$OUT/test:$OUT/main:$CP" org.junit.runner.JUnitCore com.Cigram.vid.CigramSubtitleOverlayStyleTest
