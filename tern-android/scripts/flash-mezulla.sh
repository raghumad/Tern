#!/usr/bin/env bash
#
# flash-mezulla.sh -- automated Meshtastic flash for a LilyGo TTGO LoRa32 T3
# board ("mezulla" in this project).
#
# What it does, in order:
#   1. Check that esptool and meshtastic CLI are available.
#   2. Check the target USB port exists.
#   3. Download the pinned Meshtastic firmware zip (cached after first run).
#   4. Extract it to a temp dir.
#   5. Run Meshtastic's bundled device-install.sh against your board.
#   6. Wait for the board to reboot.
#   7. Set the LoRa region.
#   8. Print `meshtastic --info` so you can see it came up.
#
# Defaults are tuned for: T3 V1.6 / V1.6.1, US 915 MHz, board on /dev/ttyACM0.
# Override via flags or env vars.
#
# Usage:
#   ./flash-mezulla.sh                          # full flash with defaults
#   ./flash-mezulla.sh --check                  # don't flash; just verify env + download
#   ./flash-mezulla.sh -p /dev/ttyUSB0          # different port
#   ./flash-mezulla.sh -r EU_868                # different LoRa region
#   ./flash-mezulla.sh --no-region              # don't change region after flash
#

set -euo pipefail

# ----- pinned config -----
MESHTASTIC_VERSION="${MESHTASTIC_VERSION:-2.7.15.567b8ea}"
BOARD_VARIANT="${BOARD_VARIANT:-tlora-v2-1-1_6}"
PORT="${PORT:-/dev/ttyACM0}"
REGION="${REGION:-US}"
CACHE_DIR="${CACHE_DIR:-$HOME/.cache/tern/meshtastic}"

# ----- runtime flags -----
CHECK_ONLY=false
SET_REGION=true

usage() {
  sed -n '2,30p' "$0"
  exit 0
}

# ----- arg parse -----
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)        usage ;;
    --check)          CHECK_ONLY=true; shift ;;
    --no-region)      SET_REGION=false; shift ;;
    -p|--port)        PORT="$2"; shift 2 ;;
    -r|--region)      REGION="$2"; shift 2 ;;
    -v|--version)     MESHTASTIC_VERSION="$2"; shift 2 ;;
    -V|--variant)     BOARD_VARIANT="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

# ----- pretty output -----
say()  { printf '\n--- %s ---\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ----- step 1: tooling -----
say "Checking esptool and meshtastic CLI"
ESPTOOL_FOUND=true
MESHTASTIC_FOUND=true
if command -v esptool >/dev/null 2>&1; then
  echo "esptool: $(command -v esptool)"
elif command -v esptool.py >/dev/null 2>&1; then
  echo "esptool: $(command -v esptool.py)"
elif python3 -m esptool version >/dev/null 2>&1; then
  echo "esptool: python3 -m esptool ($(python3 -m esptool version 2>&1 | head -1))"
else
  ESPTOOL_FOUND=false
  warn "esptool not found"
fi

if command -v meshtastic >/dev/null 2>&1; then
  echo "meshtastic: $(command -v meshtastic)"
else
  MESHTASTIC_FOUND=false
  warn "meshtastic CLI not found"
fi

if ! $ESPTOOL_FOUND || ! $MESHTASTIC_FOUND; then
  cat >&2 <<EOF

Install missing tools with one of:

  pip3 install --user esptool meshtastic

  # or, if you prefer an isolated venv:
  python3 -m venv ~/.venvs/meshtastic
  source ~/.venvs/meshtastic/bin/activate
  pip install esptool meshtastic

Then re-run this script.
EOF
  exit 1
fi

# ----- step 2: port -----
say "Checking USB port"
if [ ! -e "$PORT" ]; then
  die "$PORT does not exist. Plug the board in or pass -p /dev/ttyUSBn"
fi
if [ ! -w "$PORT" ]; then
  die "$PORT is not writable by current user. Either fix udev permissions or add user to the right group (uucp on Arch / dialout on Debian)."
fi
ls -l "$PORT"

# ----- step 3: download (cached) -----
say "Locating firmware bundle"
mkdir -p "$CACHE_DIR"
ZIP_NAME="firmware-esp32-${MESHTASTIC_VERSION}.zip"
ZIP_PATH="$CACHE_DIR/$ZIP_NAME"
ZIP_URL="https://github.com/meshtastic/firmware/releases/download/v${MESHTASTIC_VERSION}/${ZIP_NAME}"

if [ -f "$ZIP_PATH" ]; then
  echo "Cached: $ZIP_PATH"
else
  echo "Downloading: $ZIP_URL"
  curl -fL --progress-bar -o "$ZIP_PATH.partial" "$ZIP_URL"
  mv "$ZIP_PATH.partial" "$ZIP_PATH"
fi

# ----- step 4: extract -----
say "Extracting firmware for ${BOARD_VARIANT}"
WORK_DIR="$(mktemp -d -t mezulla-flash-XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT
unzip -q "$ZIP_PATH" -d "$WORK_DIR"

FW_BIN="$WORK_DIR/firmware-${BOARD_VARIANT}-${MESHTASTIC_VERSION}.bin"
INSTALLER="$WORK_DIR/device-install.sh"
if [ ! -f "$FW_BIN" ]; then
  die "Expected firmware not in zip: $(basename "$FW_BIN"). Did the variant name change upstream?"
fi
if [ ! -f "$INSTALLER" ]; then
  die "Meshtastic device-install.sh not found in zip."
fi
chmod +x "$INSTALLER"
echo "Firmware:  $FW_BIN"
echo "Installer: $INSTALLER"

# ----- check-only stops here -----
if $CHECK_ONLY; then
  say "--check: environment and download OK; not flashing."
  exit 0
fi

# ----- step 5: flash -----
say "Flashing $BOARD_VARIANT on $PORT (this rewrites the board's flash)"
( cd "$WORK_DIR" && ./device-install.sh -p "$PORT" -f "$(basename "$FW_BIN")" )

# ----- step 6: wait for reboot -----
say "Waiting 8s for the board to come back online"
sleep 8

# ----- step 7: set region -----
if $SET_REGION; then
  say "Setting LoRa region to $REGION"
  meshtastic --port "$PORT" --set lora.region "$REGION"
fi

# ----- step 8: verify -----
say "Reading board info via meshtastic"
meshtastic --port "$PORT" --info

say "Done. Board is flashed with Meshtastic ${MESHTASTIC_VERSION} (${BOARD_VARIANT})."
