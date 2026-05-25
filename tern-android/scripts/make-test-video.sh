#!/usr/bin/env bash
#
# make-test-video.sh — pull captured frames from the managed device
# and stitch into an MP4 video.
#
# Usage:
#   ./scripts/make-test-video.sh <test-name>
#
# Example:
#   ./scripts/make-test-video.sh mezulla_convergence
#
# Prerequisites:
#   - adb in PATH
#   - ffmpeg installed
#   - The managed device still running (or frames already pulled)
#

set -euo pipefail

TEST_NAME="${1:?Usage: make-test-video.sh <test-name>}"
DEVICE_DIR="/sdcard/tern-tests/$TEST_NAME"
LOCAL_DIR="app/build/test-videos/$TEST_NAME"
OUTPUT="app/build/test-videos/${TEST_NAME}.mp4"

mkdir -p "$LOCAL_DIR"

echo "--- pulling frames from device ---"
adb pull "$DEVICE_DIR/" "$LOCAL_DIR/" 2>/dev/null || {
    echo "Warning: could not pull from device (may be shut down)."
    echo "Looking for frames in $LOCAL_DIR..."
}

FRAME_COUNT=$(ls "$LOCAL_DIR"/frame_*.png 2>/dev/null | wc -l)
if [ "$FRAME_COUNT" -eq 0 ]; then
    echo "No frames found. Run the test with FrameCaptureHelper first."
    exit 1
fi

echo "--- stitching $FRAME_COUNT frames into video ---"
ffmpeg -y -framerate 2 \
    -i "$LOCAL_DIR/frame_%04d.png" \
    -c:v libx264 -pix_fmt yuv420p \
    -preset fast \
    "$OUTPUT" 2>&1 | tail -5

echo "--- done ---"
echo "Video: $OUTPUT ($FRAME_COUNT frames)"
ls -lh "$OUTPUT"
