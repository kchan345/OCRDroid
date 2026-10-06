#!/usr/bin/env bash
set -euo pipefail
package=io.github.ocrdroid
mkdir -p results
trap 'adb logcat -d -t 1500 > results/logcat.txt || echo "Unable to collect logcat" >&2' EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell run-as "$package" mkdir -p files/test-source
for name in model-q4_k_m.gguf mmproj-f16.gguf manifest.json; do
  adb shell "run-as $package sh -c 'cat > files/test-source/$name'" < "models/$name"
done
adb shell "run-as $package sh -c 'cat > files/test-source/receipt.png'" < fixtures/receipt.png
bash scripts/emulator_offline.sh
timeout 1200 adb shell am instrument -w -r "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee results/instrumentation.txt
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=' results/instrumentation.txt; then
  exit 1
fi
grep -q 'OK (11 tests)' results/instrumentation.txt
adb exec-out run-as "$package" cat files/app-evidence.json > results/app-evidence.json
for shot in input-preview adjust-preview region-preview selection-preview rendered-preview settings-preview; do
  adb exec-out run-as "$package" cat "files/$shot.png" > "results/$shot.png"
done
