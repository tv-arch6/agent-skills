#!/usr/bin/env bash
# Runs every check there is, in order. Stops at the first failure.
#   LIBS=/dir/with/android-all.jar,junit,hamcrest  SWB=/path/to/original-cigram.swb  verify/verify-all.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="${LIBS:?set LIBS}"
SWB="${SWB:?set SWB to the original filmsandseries .swb}"
OUT="${OUT:-$HERE/dist}"
WORK="$(mktemp -d)"
echo "== 1/6 engine core: unit + integration tests (JVM)";            "$HERE/verify/run-tests.sh" 2>&1 | grep -v "^Picked up\|^Note:\|duplicate request ignored" | tail -4
echo "== 2/6 glue tests (need the ExoPlayer stubs)";                   "$HERE/verify/run-glue-tests.sh" 2>&1 | grep -v "^Picked up\|^Note:" | tail -3
echo "== 3/6 glue classes compile against the stubs";                  "$HERE/verify/compile-glue.sh" 2>&1 | tail -1
echo "== 4/6 build the patched project";                               python3 "$HERE/patches/apply_patches.py" --swb "$SWB" --out "$OUT/filmsandseries_v10.65_com.Cigram.vid_65_player-engine.swb"
echo "== 5/6 patched blocks / files vs. original (javac)"
GLUE="$WORK/glue"; mkdir -p "$GLUE"
ANDROID="$(ls "$LIBS"/android-all*.jar | head -1)"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -cp "$ANDROID" -d "$GLUE" $(find "$HERE/src/main/java" "$HERE/verify/stubs" -name '*.java') 2>&1 | grep -v "^Picked up\|^Note:" || true
python3 "$HERE/verify/check_blocks.py" "$SWB" "$OUT/filmsandseries_v10.65_com.Cigram.vid_65_player-engine.swb" "$GLUE:$ANDROID" "$WORK/blocks" 2>&1 | grep -v "^Picked up"
python3 "$HERE/verify/check_swb_integrity.py" "$SWB" "$OUT/filmsandseries_v10.65_com.Cigram.vid_65_player-engine.swb"
echo "== 6/6 catalog proxy worker (node)";                             node --test "$HERE/worker/cigram-catalog-proxy.test.mjs" 2>&1 | grep -E "^# (tests|pass|fail)"
echo "ALL CHECKS PASSED"
