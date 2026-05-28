#!/usr/bin/env bash
# aravis-replay-cycle.sh — drive the Aravis replay test end-to-end.
#
# Composes:
#   1. Phone-side: AravisReplayTest (runs the AravisReplayRunner on a real
#      paired board for ~60 wall-clock seconds at 64x speed; records screen).
#   2. Host-side (in parallel): poll the board's OLED via USB serial every
#      5 s into a PNG stack.
#   3. Pull the phone screen recording.
#   4. Hand both to firmware-repo `compose-replay-video.py` to produce a
#      side-by-side phone+OLED video with the virtual-time overlay.
#
# Output (under tern-android/build/aravis-replay/<timestamp>/):
#   - phone-screen.mp4
#   - oled/oled_<unix_ms>.png × N
#   - replay-meta.json
#   - composite.mp4 (if compose-replay-video.py is available)
#
# Prerequisites:
#   - Real phone on adb (USB or WiFi).
#   - Tern + test APKs installed.
#   - Board powered on, connected to host via USB serial (/dev/ttyACM0).
#   - Board ALREADY PAIRED to the phone (run pairing-test-cycle.sh first
#     if not — that one is destructive and re-flashes the firmware, so we
#     only re-pair on demand, not every replay run).
#   - Tern set as the system mock-location app in Developer Options.
#
# Usage:
#   ./scripts/aravis-replay-cycle.sh [device-serial] [speed-multiplier]
#
# Example:
#   ./scripts/aravis-replay-cycle.sh 10.10.10.82:5555 64

set -euo pipefail

DEVICE="${1:-10.10.10.82:5555}"
SPEED_MULTIPLIER="${2:-64}"
SERIAL_PORT="${SERIAL_PORT:-/dev/ttyACM0}"
OLED_INTERVAL_SECONDS="${OLED_INTERVAL_SECONDS:-5}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FIRMWARE_DIR="${FIRMWARE_DIR:-$HOME/src/meshtastic-firmware}"
SCREENDUMP="${SCREENDUMP:-$FIRMWARE_DIR/scripts/screendump.sh}"
COMPOSE_VIDEO="${COMPOSE_VIDEO:-$FIRMWARE_DIR/scripts/compose-replay-video.py}"

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR="$ANDROID_DIR/build/aravis-replay/$TIMESTAMP"
OLED_DIR="$OUT_DIR/oled"
mkdir -p "$OLED_DIR"

echo "=== ARAVIS REPLAY CYCLE ==="
echo "Phone:          $DEVICE"
echo "Speed:          ${SPEED_MULTIPLIER}x"
echo "Serial port:    $SERIAL_PORT"
echo "OLED interval:  ${OLED_INTERVAL_SECONDS}s"
echo "Output:         $OUT_DIR"
echo ""

# -----------------------------------------------------------------------------
# Sanity checks
# -----------------------------------------------------------------------------

if ! adb -s "$DEVICE" get-state >/dev/null 2>&1; then
    echo "FAIL: phone $DEVICE not reachable via adb" >&2
    exit 1
fi

if [ ! -e "$SERIAL_PORT" ]; then
    echo "WARN: $SERIAL_PORT does not exist — OLED capture will be skipped" >&2
    SKIP_OLED=1
else
    SKIP_OLED=0
fi

if [ "$SKIP_OLED" -eq 0 ] && [ ! -x "$SCREENDUMP" ]; then
    echo "WARN: $SCREENDUMP not executable — OLED capture will be skipped" >&2
    SKIP_OLED=1
fi

# -----------------------------------------------------------------------------
# Wake the phone & make sure no stale Tern is running
# -----------------------------------------------------------------------------

adb -s "$DEVICE" shell "settings put system screen_off_timeout 600000" 2>/dev/null || true
adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
sleep 1
adb -s "$DEVICE" shell input keyevent 82 2>/dev/null || true
sleep 1

adb -s "$DEVICE" shell "am force-stop com.ternparagliding" 2>/dev/null || true
adb -s "$DEVICE" logcat -c 2>/dev/null || true
sleep 2

# -----------------------------------------------------------------------------
# Background: OLED dump loop
# -----------------------------------------------------------------------------

OLED_PID=""
if [ "$SKIP_OLED" -eq 0 ]; then
    (
        while true; do
            TS_MS=$(date +%s%3N)
            OUT="$OLED_DIR/oled_${TS_MS}.png"
            # screendump.sh args: PORT SAVE_PATH
            bash "$SCREENDUMP" "$SERIAL_PORT" "$OUT" >/dev/null 2>&1 || true
            sleep "$OLED_INTERVAL_SECONDS"
        done
    ) &
    OLED_PID=$!
    echo "[1/3] OLED capture loop started (pid=$OLED_PID)"
else
    echo "[1/3] OLED capture SKIPPED"
fi

cleanup() {
    if [ -n "$OLED_PID" ]; then
        kill "$OLED_PID" 2>/dev/null || true
        wait "$OLED_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

# -----------------------------------------------------------------------------
# Foreground: run the instrumented test
# -----------------------------------------------------------------------------

echo "[2/3] Running AravisReplayTest (this drives the runner + records screen)..."
set +e
adb -s "$DEVICE" shell "am instrument -w \
    -e class com.ternparagliding.test.AravisReplayTest \
    -e speedMultiplier '$SPEED_MULTIPLIER' \
    com.ternparagliding.test/androidx.test.runner.AndroidJUnitRunner"
TEST_EXIT=$?
set -e

# Stop OLED loop before pulling
cleanup
trap - EXIT INT TERM

# -----------------------------------------------------------------------------
# Pull screen recording from /sdcard/tern-tests/
# (See VideoHelper.kt — recordings land in /sdcard/tern-tests/<testName>.mp4)
# -----------------------------------------------------------------------------

echo "[3/3] Pulling screen recording from phone..."
PHONE_VIDEO_NAME="aravis_team_xc_replay_golden_path_15km_range.mp4"
adb -s "$DEVICE" pull "/sdcard/tern-tests/${PHONE_VIDEO_NAME}" \
    "$OUT_DIR/phone-screen.mp4" 2>/dev/null \
    || echo "WARN: no screen recording found at /sdcard/tern-tests/${PHONE_VIDEO_NAME}" >&2

# -----------------------------------------------------------------------------
# Drop a meta file so downstream tools (compose-replay-video.py) know what
# virtual time corresponds to wall-clock zero.
# -----------------------------------------------------------------------------

# tonio24's first IGC fix per AravisTeam2026 + MezullaBuddyFlyingVisualTest doc.
VIRTUAL_START="2026-04-25T07:21:36Z"

cat > "$OUT_DIR/replay-meta.json" <<EOF
{
  "scenario": "aravis-team-2026-04-25",
  "dutPilot": "tonio24",
  "virtualStart": "$VIRTUAL_START",
  "speedMultiplier": $SPEED_MULTIPLIER,
  "loraRangeMeters": 15000,
  "phoneVideo": "phone-screen.mp4",
  "oledDir": "oled/",
  "oledIntervalSeconds": $OLED_INTERVAL_SECONDS,
  "phoneSerial": "$DEVICE",
  "testExitCode": $TEST_EXIT
}
EOF

# -----------------------------------------------------------------------------
# Optional: compose the side-by-side video
# -----------------------------------------------------------------------------

if [ -f "$COMPOSE_VIDEO" ] && [ -f "$OUT_DIR/phone-screen.mp4" ]; then
    OLED_COUNT=$(find "$OLED_DIR" -name 'oled_*.png' 2>/dev/null | wc -l)
    if [ "$OLED_COUNT" -gt 0 ]; then
        echo "[+] Composing side-by-side video ($OLED_COUNT OLED frames)..."
        python3 "$COMPOSE_VIDEO" \
            --phone-video "$OUT_DIR/phone-screen.mp4" \
            --oled-dir "$OLED_DIR" \
            --virtual-start "$VIRTUAL_START" \
            --speed-multiplier "$SPEED_MULTIPLIER" \
            --output "$OUT_DIR/composite.mp4" \
            2>&1 | sed 's/^/    /' \
            || echo "WARN: compose-replay-video.py failed" >&2
    else
        echo "[+] No OLED frames captured — skipping composite video"
    fi
else
    echo "[+] Skipping composite (compose-replay-video.py or phone video missing)"
fi

# -----------------------------------------------------------------------------
# Summary
# -----------------------------------------------------------------------------

echo ""
echo "=== SUMMARY ==="
echo "Output:        $OUT_DIR"
echo "Screen video:  $([ -f "$OUT_DIR/phone-screen.mp4" ] && echo OK || echo MISSING)"
echo "OLED frames:   $(find "$OLED_DIR" -name 'oled_*.png' 2>/dev/null | wc -l)"
echo "Composite:     $([ -f "$OUT_DIR/composite.mp4" ] && echo OK || echo not-built)"
echo "Meta:          $OUT_DIR/replay-meta.json"
echo ""
if [ "$TEST_EXIT" -eq 0 ]; then
    echo "RESULT: PASS"
    exit 0
else
    echo "RESULT: FAIL (instrumentation exit $TEST_EXIT)"
    exit "$TEST_EXIT"
fi
