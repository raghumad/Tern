#!/bin/bash
# Full pairing test cycle — no human in the loop.
#
# Prerequisites:
#   - Board connected via USB serial (/dev/ttyACM0)
#   - Ulefone connected via WiFi adb
#   - Tern + test APKs installed on Ulefone
#   - Board flashed with Mezulla firmware
#
# Usage:
#   ./scripts/pairing-test-cycle.sh [device-serial]
#
# The cycle:
#   1. Reboot board (clears BLE state)
#   2. Capture fresh token from boot log
#   3. Run BLE pairing test on phone
#   4. Report result

set -euo pipefail

DEVICE="${1:-10.10.10.82:5555}"
SERIAL_PORT="/dev/ttyACM0"
DEEPLINK_FILE="$HOME/src/Tern/docs/handoffs/mezulla-deeplink.txt"
NODE_ID="4a312aaa"

echo "=== PAIRING TEST CYCLE ==="
echo "Board: $SERIAL_PORT"
echo "Phone: $DEVICE"
echo ""

# Step 1: Reboot board
echo "[1/4] Rebooting board..."
meshtastic --port "$SERIAL_PORT" --reboot 2>&1 | grep -v '^$'

# Step 2: Capture token from boot log
echo "[2/4] Capturing token from boot log..."
sleep 11  # wait for meshtastic CLI to release port + board to reboot

TOKEN=""
TIMEOUT=15
START=$(date +%s)
while [ -z "$TOKEN" ] && [ $(( $(date +%s) - START )) -lt $TIMEOUT ]; do
    LINE=$(timeout 1 head -c 500 "$SERIAL_PORT" 2>/dev/null | tr -d '\0' | grep -o 'token=[a-f0-9]*' | head -1 || true)
    if [ -n "$LINE" ]; then
        TOKEN="${LINE#token=}"
    fi
done

if [ -z "$TOKEN" ]; then
    # Fallback: try Python serial reader
    TOKEN=$(python3 -c "
import serial, time, re
ser = serial.Serial('$SERIAL_PORT', 115200, timeout=1)
start = time.time()
while time.time() - start < 10:
    line = ser.readline().decode('utf-8', errors='replace')
    m = re.search(r'token=([a-f0-9]+)', line)
    if m:
        print(m.group(1))
        break
" 2>/dev/null || true)
fi

if [ -z "$TOKEN" ]; then
    echo "ERROR: Could not capture token. Board may have booted too fast."
    echo "Try: press RST on the board and run this script again."
    exit 1
fi

PAIR_URI="tern://p?n=${NODE_ID}&t=${TOKEN}"
echo "$PAIR_URI" > "$DEEPLINK_FILE"
echo "Token: $TOKEN"
echo "Deep link: $PAIR_URI"
echo ""

# Step 3: Wait for BLE advertising to stabilize
echo "[3/4] Waiting for BLE advertising..."
sleep 5

# Step 4: Run the BLE test
echo "[4/4] Running BLE pairing test..."

# Wake screen
adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
sleep 1

# Force stop old instance
adb -s "$DEVICE" shell "am force-stop com.ternparagliding" 2>/dev/null || true
sleep 2

# Clear logcat
adb -s "$DEVICE" logcat -c 2>/dev/null || true

# Launch with deep link
adb -s "$DEVICE" shell "am start -a android.intent.action.VIEW -d '$PAIR_URI'" 2>&1

# Wait for pairing
sleep 15

# Collect results
echo ""
echo "=== RESULTS ==="
adb -s "$DEVICE" logcat -d 2>&1 | grep 'BlePairingService\|PairingOrchestrator' | grep -v 'Scan hit.*rssi' | tail -15

echo ""
echo "=== BOARD SERIAL ==="
# Check if board accepted the claim
timeout 3 python3 -c "
import serial
ser = serial.Serial('$SERIAL_PORT', 115200, timeout=1)
for _ in range(5):
    line = ser.readline().decode('utf-8', errors='replace').strip()
    if 'MEZULLA' in line or 'claim' in line.lower():
        print(line)
" 2>/dev/null || true

echo ""
RESULT=$(adb -s "$DEVICE" logcat -d 2>&1 | grep 'PairingOrchestrator.*successful' | tail -1)
if [ -n "$RESULT" ]; then
    echo "RESULT: PASS ✓"
    echo "$RESULT"
else
    FAIL=$(adb -s "$DEVICE" logcat -d 2>&1 | grep 'PairingOrchestrator.*failed\|PairingOrchestrator.*not found\|PairingOrchestrator.*Board not' | tail -1)
    if [ -n "$FAIL" ]; then
        echo "RESULT: FAIL ✗"
        echo "$FAIL"
    else
        echo "RESULT: UNKNOWN (check logcat manually)"
    fi
fi
