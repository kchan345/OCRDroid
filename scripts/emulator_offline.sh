#!/usr/bin/env bash
set -euo pipefail
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb root
adb wait-for-device
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
# The emulator also has Ethernet, independent of its Wi-Fi/mobile radio toggles.
if adb shell ip link show eth0 >/dev/null 2>&1; then
  adb shell ip link set eth0 down
fi
for attempt in $(seq 1 20); do
  links=$(adb shell ip -o link show up | tr -d '\r')
  if ! printf '%s\n' "$links" | grep -Eq '(eth[0-9]+|wlan[0-9]+|rmnet[^: ]*)[:@]'; then
    printf '%s\n' "$links" > results/offline-interfaces.txt
    exit 0
  fi
  sleep 1
done
echo "Emulator network interfaces are still active" >&2
exit 1
