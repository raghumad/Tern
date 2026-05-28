#!/bin/bash
# Run the BLE pairing test on a connected device.
# Reads the current deep link from docs/handoffs/mezulla-deeplink.txt
#
# Usage:
#   ./scripts/run-ble-test.sh [device-serial]
#
# Example:
#   ./scripts/run-ble-test.sh 10.10.10.82:5555

set -euo pipefail

DEVICE="${1:-}"
DEEPLINK_FILE="$HOME/src/Tern/docs/handoffs/mezulla-deeplink.txt"
PAIR_URI=$(cat "$DEEPLINK_FILE" | tr -d '[:space:]')

echo "Deep link: $PAIR_URI"

DEVICE_FLAG=""
if [ -n "$DEVICE" ]; then
    DEVICE_FLAG="-s $DEVICE"
fi

echo "Force-stopping app..."
adb $DEVICE_FLAG shell "am force-stop com.ternparagliding"
sleep 2

echo "Clearing logcat..."
adb $DEVICE_FLAG logcat -c

echo "Running BlePairingTest..."
adb $DEVICE_FLAG shell "am instrument -w \
    -e class com.ternparagliding.test.BlePairingTest \
    -e pairUri '$PAIR_URI' \
    com.ternparagliding.test/androidx.test.runner.AndroidJUnitRunner"

echo ""
echo "=== Logcat ==="
adb $DEVICE_FLAG logcat -d | grep 'BlePairingService\|PairingOrchestrator' | grep -v 'Scan hit.*rssi'
