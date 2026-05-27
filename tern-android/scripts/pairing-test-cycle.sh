#!/bin/bash
# Full pairing test cycle — no human in the loop.
#
# 1. Reset board (erase flash → flash firmware → set identity → capture token)
# 2. Run BlePairingTest on phone with fresh token
#
# Prerequisites:
#   - Board connected via USB serial (/dev/ttyACM0)
#   - Phone connected via adb (USB or WiFi)
#   - Tern + test APKs installed on phone
#
# Usage:
#   ./scripts/pairing-test-cycle.sh [device-serial]

set -euo pipefail

DEVICE="${1:-10.10.10.82:5555}"
DEEPLINK_FILE="$HOME/src/Tern/docs/handoffs/mezulla-deeplink.txt"
RESET_SCRIPT="$HOME/src/meshtastic-firmware/scripts/reset-mezulla.sh"

echo "=== PAIRING TEST CYCLE ==="
echo "Phone: $DEVICE"
echo ""

# Step 1: Reset board and capture fresh token
echo "[1/3] Resetting board (erase → flash → identity → token)..."
bash "$RESET_SCRIPT" 2>&1

PAIR_URI=$(cat "$DEEPLINK_FILE" | tr -d '[:space:]')
echo ""
echo "Deep link: $PAIR_URI"
echo ""

# Step 2: Wait for BLE advertising
echo "[2/3] Waiting for BLE advertising..."
sleep 5

# Step 3: Run instrumented test
echo "[3/3] Running BlePairingTest..."
# Ensure screen is ON (Android 12 blocks BLE scan with screen off)
adb -s "$DEVICE" shell "settings put system screen_off_timeout 600000" 2>/dev/null || true
adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
sleep 1
adb -s "$DEVICE" shell input keyevent 82 2>/dev/null || true
sleep 1
SCREEN=$(adb -s "$DEVICE" shell "dumpsys deviceidle | grep mScreenOn" 2>/dev/null)
echo "    Screen: $SCREEN"

adb -s "$DEVICE" shell "am force-stop com.ternparagliding" 2>/dev/null || true
sleep 2
adb -s "$DEVICE" logcat -c 2>/dev/null || true

adb -s "$DEVICE" shell "am instrument -w \
    -e class com.ternparagliding.test.BlePairingTest \
    -e pairUri '$PAIR_URI' \
    com.ternparagliding.test/androidx.test.runner.AndroidJUnitRunner"

TEST_EXIT=$?

echo ""
echo "=== LOGCAT ==="
adb -s "$DEVICE" logcat -d 2>&1 | grep 'BlePairingService\|PairingOrchestrator\|BlePairingTest' | grep -v 'Scan hit.*rssi' | tail -10

echo ""
if [ $TEST_EXIT -eq 0 ]; then
    echo "RESULT: PASS ✓"
else
    echo "RESULT: FAIL ✗"
fi
