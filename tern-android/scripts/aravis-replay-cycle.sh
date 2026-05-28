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
# Wipe stale frame dirs from previous runs so wall-clock-start is honest.
adb -s "$DEVICE" shell "rm -rf /sdcard/Android/data/com.ternparagliding/files/tern-tests/*-frames" 2>/dev/null || true
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
TEST_LOG="$OUT_DIR/instrumentation.log"
set +e
adb -s "$DEVICE" shell "am instrument -w \
    -e class com.ternparagliding.test.AravisReplayTest \
    -e speedMultiplier '$SPEED_MULTIPLIER' \
    com.ternparagliding.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$TEST_LOG"
INSTRUMENT_EXIT=$?
set -e

# am instrument exits 0 even when tests fail. Parse the output for the real verdict.
if grep -qE 'FAILURES!!!|Tests run: [0-9]+,  Failures: [1-9]' "$TEST_LOG"; then
    TEST_EXIT=1
elif grep -qE 'OK \([0-9]+ tests?\)' "$TEST_LOG"; then
    TEST_EXIT=0
else
    TEST_EXIT=$INSTRUMENT_EXIT
fi

# Stop OLED loop before pulling
cleanup
trap - EXIT INT TERM

# -----------------------------------------------------------------------------
# Pull screen recording from /sdcard/tern-tests/
# (See VideoHelper.kt — recordings land in /sdcard/tern-tests/<testName>.mp4)
# -----------------------------------------------------------------------------

echo "[3/3] Pulling screen recording / frames from phone..."
TEST_NAME="aravis_team_xc_replay_golden_path_15km_range"

# Try the screenrecord .mp4 first; if missing, pull the FrameCaptureHelper
# fallback PNGs and ffmpeg them into a phone-screen.mp4. AOSP-based phones
# often refuse screenrecord and silently fall back.
if adb -s "$DEVICE" pull "/sdcard/tern-tests/${TEST_NAME}.mp4" \
        "$OUT_DIR/phone-screen.mp4" 2>/dev/null; then
    echo "    Pulled screenrecord .mp4"
elif adb -s "$DEVICE" shell "ls /sdcard/Android/data/com.ternparagliding/files/tern-tests/${TEST_NAME}-frames/" >/dev/null 2>&1; then
    FRAMES_DIR="$OUT_DIR/phone-frames"
    mkdir -p "$FRAMES_DIR"
    # Use '/.' to copy contents only (not the source dir itself)
    adb -s "$DEVICE" pull "/sdcard/Android/data/com.ternparagliding/files/tern-tests/${TEST_NAME}-frames/." "$FRAMES_DIR/" 2>&1 | tail -1
    FRAME_COUNT=$(find "$FRAMES_DIR" -name '*.png' 2>/dev/null | wc -l)
    echo "    Pulled $FRAME_COUNT frames"
    if [ "$FRAME_COUNT" -gt 0 ]; then
        echo "    Stitching frames into phone-screen.mp4 (2 fps)..."
        # Glob recursively for frames anywhere under FRAMES_DIR.
        # Use sort to ensure chronological order (filenames have unix_ms suffix).
        FRAME_LIST="$OUT_DIR/phone-frames.list"
        find "$FRAMES_DIR" -name '*.png' | sort > "$FRAME_LIST"
        # Build an ffconcat file: each frame held for 0.5s (2fps)
        CONCAT_FILE="$OUT_DIR/phone-frames.ffconcat"
        echo "ffconcat version 1.0" > "$CONCAT_FILE"
        while IFS= read -r f; do
            echo "file '$f'" >> "$CONCAT_FILE"
            echo "duration 0.5" >> "$CONCAT_FILE"
        done < "$FRAME_LIST"
        # Repeat the last file (ffconcat quirk — last duration is ignored)
        tail -1 "$FRAME_LIST" | sed "s/^/file '/" | sed "s/$/'/" >> "$CONCAT_FILE"
        ffmpeg -y -f concat -safe 0 -i "$CONCAT_FILE" \
               -c:v libx264 -pix_fmt yuv420p -movflags +faststart -vf "fps=2" \
               "$OUT_DIR/phone-screen.mp4" >"$OUT_DIR/ffmpeg.log" 2>&1 \
            && echo "    Phone video: OK ($(du -h "$OUT_DIR/phone-screen.mp4" | cut -f1))" \
            || { echo "    WARN: ffmpeg failed — see $OUT_DIR/ffmpeg.log" >&2; tail -5 "$OUT_DIR/ffmpeg.log" >&2; }
    fi
else
    echo "WARN: no screen recording OR fallback frames found on phone" >&2
fi

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
        # The stitched phone-screen.mp4 has no creation_time metadata
        # because we built it from PNGs. Compute the wall-clock-start
        # from the earliest pulled phone frame so OLED frames align.
        FRAMES_DIR="${FRAMES_DIR:-$OUT_DIR/phone-frames}"
        WALL_CLOCK_START=$(find "$FRAMES_DIR" -name '*.png' 2>/dev/null \
            | xargs -I{} basename {} \
            | grep -oE '[0-9]{13}' \
            | sort -n | head -1)
        echo "[+] Composing side-by-side video ($OLED_COUNT OLED frames, wall-clock-start=${WALL_CLOCK_START})..."
        python3 "$COMPOSE_VIDEO" \
            --phone-video "$OUT_DIR/phone-screen.mp4" \
            --oled-dir "$OLED_DIR" \
            --virtual-start "$VIRTUAL_START" \
            --speed-multiplier "$SPEED_MULTIPLIER" \
            --wall-clock-start "$WALL_CLOCK_START" \
            --output "$OUT_DIR/composite.mp4" \
            --scenario "Aravis team XC — golden path (50 km LoRa)" \
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
