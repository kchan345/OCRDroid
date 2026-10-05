#!/usr/bin/env bash
set -euo pipefail
mkdir -p results
cp runtime.lock.json results/runtime.lock.json
sha256sum build-android/ocr-probe models/*.gguf > results/input-sha256.txt
remote=/data/local/tmp/ocrdroid
adb shell mkdir -p "$remote"
adb push build-android/ocr-probe "$remote/ocr-probe"
adb push models/model-q4_k_m.gguf models/mmproj-f16.gguf "$remote/"
adb push fixtures/receipt.png fixtures/note.png "$remote/"
adb shell chmod 755 "$remote/ocr-probe"
adb shell getprop > results/device-properties.txt
adb shell cat /proc/meminfo > results/meminfo.txt
if [[ "${PHYSICAL_DEVICE:-0}" != 1 ]]; then
  adb shell svc wifi disable
  adb shell svc data disable
fi
for name in receipt note; do
  timeout 900 adb shell "$remote/ocr-probe $remote/model-q4_k_m.gguf $remote/mmproj-f16.gguf $remote/$name.png $remote/$name.txt $remote/$name.json" > "results/$name.log" 2>&1
  adb pull "$remote/$name.txt" "results/$name.txt"
  adb pull "$remote/$name.json" "results/$name.json"
done
python scripts/verify_probe.py results
