#!/usr/bin/env bash
#
# make-test-video.sh — stitch captured frame PNGs into an MP4 video.
#
# Usage:
#   ./scripts/make-test-video.sh <test-name> [frame-dir]
#
# Frames are named: ${test_name}_frame_NNNN_${timestamp}.png
# Output: ${frame_dir}/${test_name}.mp4
#
# Prerequisites: ffmpeg installed

set -euo pipefail

TEST_NAME="${1:?Usage: make-test-video.sh <test-name> [frame-dir]}"
FRAME_DIR="${2:-app/build/reports/bdd-report}"
OUTPUT="${FRAME_DIR}/${TEST_NAME}.mp4"

if [ -f "$OUTPUT" ]; then
    echo "Video already exists: $OUTPUT"
    exit 0
fi

# Find frames for this test (handles timestamp-suffixed filenames)
FRAMES=$(find "$FRAME_DIR" -name "${TEST_NAME}_frame_*\.png" 2>/dev/null | sort)
COUNT=$(echo "$FRAMES" | grep -c . || true)

if [ "$COUNT" -eq 0 ]; then
    echo "No frames found for $TEST_NAME in $FRAME_DIR"
    exit 0
fi

# Create a temp dir with sequential symlinks (ffmpeg needs sequential numbering)
STAGING=$(mktemp -d)
trap "rm -rf $STAGING" EXIT

i=1
echo "$FRAMES" | while read -r frame; do
    ln -s "$(realpath "$frame")" "$STAGING/$(printf 'frame_%04d.png' $i)"
    i=$((i + 1))
done

ffmpeg -y -framerate 2 -i "$STAGING/frame_%04d.png" \
    -c:v libx264 -pix_fmt yuv420p -movflags +faststart \
    "$OUTPUT" 2>/dev/null

echo "Created: $OUTPUT ($COUNT frames)"
