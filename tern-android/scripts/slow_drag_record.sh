#!/usr/bin/env bash
#
# slow_drag_record.sh — drive a slow, CONTINUOUS map pan on a connected device
# while screen-recording, then pull the video to the host.
#
# Why continuous: a chained `adb shell input swipe` lifts the finger between
# segments, which ends the gesture and lets the overlay query catch up — that
# HIDES the trigger-starvation bug. We instead inject one DOWN, a long series of
# MOVE steps, then one UP, so `state.center` updates continuously (every step)
# exactly like a real slow drag. That is what starves AirspaceOverlay's
# LaunchedEffect(center) on the buggy build (and what the fix must survive).
#
# Usage:
#   scripts/slow_drag_record.sh [label] [direction] [seconds] [serial]
#     label      tag in the output filename            (default: drag)
#     direction  east | west | north | south           (default: east)
#     seconds    approx wall-clock of the drag          (default: 12)
#     serial     adb device serial                      (default: first device)
#
# Output: /tmp/tern_drag_<label>_<direction>_<HHMMSS>.mp4 on the host.
#
# Dragging "east" pans the map content eastward => finger moves right-to-left.
set -euo pipefail

LABEL="${1:-drag}"
DIRECTION="${2:-east}"
SECONDS_TOTAL="${3:-12}"
SERIAL="${4:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"

if [[ -z "${SERIAL}" ]]; then
  echo "No adb device found." >&2
  exit 1
fi
ADB=(adb -s "${SERIAL}")

# Screen geometry (parsed so the script works on any device).
read -r W H < <("${ADB[@]}" shell wm size | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p')
W="${W:-720}"; H="${H:-1600}"
MIDY=$(( H / 2 ))

# Endpoints: keep well inside the screen and below any top app bar.
LEFT=$(( W * 18 / 100 ))
RIGHT=$(( W * 82 / 100 ))
TOP=$(( H * 28 / 100 ))
BOT=$(( H * 72 / 100 ))

case "${DIRECTION}" in
  east)  X0=${RIGHT}; Y0=${MIDY}; X1=${LEFT};  Y1=${MIDY} ;;   # map moves east
  west)  X0=${LEFT};  Y0=${MIDY}; X1=${RIGHT}; Y1=${MIDY} ;;
  north) X0=${MIDY};  Y0=${BOT};  X1=${MIDY};  Y1=${TOP}  ;;   # mistype-safe below
  south) X0=${MIDY};  Y0=${TOP};  X1=${MIDY};  Y1=${BOT}  ;;
  *) echo "Unknown direction: ${DIRECTION}" >&2; exit 1 ;;
esac
# For vertical drags the X must be a screen-x, not MIDY; fix that:
if [[ "${DIRECTION}" == "north" || "${DIRECTION}" == "south" ]]; then
  X0=$(( W / 2 )); X1=$(( W / 2 ))
fi

STEPS=$(( SECONDS_TOTAL * 8 ))          # ~8 move events/sec → smooth + slow
[[ ${STEPS} -lt 8 ]] && STEPS=8
SLEEP=$(awk -v s="${SECONDS_TOTAL}" -v n="${STEPS}" 'BEGIN{printf "%.3f", s/n}')

STAMP=$("${ADB[@]}" shell date +%H%M%S | tr -d '\r')
DEVVID="/sdcard/tern_drag_${STAMP}.mp4"
OUT="/tmp/tern_drag_${LABEL}_${DIRECTION}_${STAMP}.mp4"

echo "Device ${SERIAL}  screen ${W}x${H}"
echo "Drag ${DIRECTION}: (${X0},${Y0}) -> (${X1},${Y1})  steps=${STEPS} step_sleep=${SLEEP}s"
echo "Recording to ${DEVVID} ..."

# 1) Start screen recording (background on device).
"${ADB[@]}" shell screenrecord --time-limit 180 --bit-rate 8000000 "${DEVVID}" &
REC_PID=$!
sleep 1.5   # let the encoder start before we touch the screen

# 2) Inject the continuous gesture in a SINGLE on-device shell session so the
#    DOWN..MOVE..UP stays one gesture with tight, even timing.
"${ADB[@]}" shell "
  x0=${X0}; y0=${Y0}; x1=${X1}; y1=${Y1}; n=${STEPS}
  input motionevent DOWN \$x0 \$y0
  i=1
  while [ \$i -le \$n ]; do
    x=\$(( x0 + (x1 - x0) * i / n ))
    y=\$(( y0 + (y1 - y0) * i / n ))
    input motionevent MOVE \$x \$y
    sleep ${SLEEP}
    i=\$(( i + 1 ))
  done
  input motionevent UP \$x1 \$y1
"

# 3) Let the post-gesture settle + inertia render, then stop recording.
sleep 3
"${ADB[@]}" shell pkill -INT screenrecord >/dev/null 2>&1 || true
wait "${REC_PID}" 2>/dev/null || true
sleep 1

# 4) Pull and clean up.
"${ADB[@]}" pull "${DEVVID}" "${OUT}" >/dev/null
"${ADB[@]}" shell rm -f "${DEVVID}" >/dev/null 2>&1 || true
echo "Saved: ${OUT}"
