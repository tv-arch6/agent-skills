#!/usr/bin/env bash
# Compiles the engine core and runs its JUnit tests on the JVM.
#
#   LIBS=/path/with/jars  verify/run-tests.sh [TestClass ...]
#
# LIBS must contain:  android-all*.jar (org.robolectric:android-all, gives org.json and the android.* classes
# the core touches), junit-4.13.2.jar, hamcrest-core-1.3.jar. All three are on Maven Central.
# Only the classes that do not need ExoPlayer or the rest of the app are built here (see CORE below);
# the glue classes are checked by verify/compile-glue.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="${LIBS:?set LIBS to the directory that holds the jars}"
OUT="${OUT:-$(mktemp -d)}"
ANDROID="$(ls "$LIBS"/android-all*.jar | head -1)"
CP="$ANDROID:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar"
SRC="$HERE/src/main/java/com/Cigram/vid"

CORE="CigramEngineException CigramCancel CigramLog CigramHttp CigramMediaRef CigramEpisode CigramSeason
CigramMediaRequest CigramPlaybackSource CigramSourceProvider CigramBaseProvider CigramEngineConfig
CigramLibraryData CigramLibraryItems CigramLibraryProvider CigramStreamLink CigramJsonProvider CigramProviders
CigramSourceValidator CigramText CigramPoster CigramMediaCatalog CigramTmdbSource CigramLibrarySource
CigramSubtitles CigramPlaybackCoordinator"

FILES=""
for c in $CORE; do FILES="$FILES $SRC/$c.java"; done
mkdir -p "$OUT/main" "$OUT/test"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -cp "$CP" -d "$OUT/main" $FILES
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -cp "$OUT/main:$CP" -d "$OUT/test" $(ls "$HERE"/src/test/java/com/Cigram/vid/*.java | grep -v OverlayStyleTest)

if [ "$#" -gt 0 ]; then TESTS="$*"; else
  TESTS="$(cd "$HERE/src/test/java" && ls com/Cigram/vid/*Test.java | grep -v OverlayStyleTest | sed 's/\.java$//; s#/#.#g' | tr '\n' ' ')"
fi
exec java -cp "$OUT/test:$OUT/main:$CP" org.junit.runner.JUnitCore $TESTS
