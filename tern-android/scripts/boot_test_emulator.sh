#!/usr/bin/env bash
#
# Boot (or reuse) the GPU-backed emulator that testAll runs the instrumented
# suite against. Idempotent: if a booted emulator is already attached it exits
# immediately; otherwise it creates the AVD if missing and boots it headless
# with REAL GPU acceleration (-gpu host).
#
# Why GPU host + this AVD instead of the gradle managed device: the managed
# device runs headless software-GL and, more importantly, is irrelevant to the
# real blocker — the instrumented map tests need the app's welcome timers to
# fire, which the harness now advances via the Compose clock. This emulator is
# simply a reliable, frame-producing device for connectedDebugAndroidTest.
#
# Override the AVD name with TERN_TEST_AVD; the system image with TERN_TEST_IMG.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
AVDMAN="$SDK/cmdline-tools/latest/bin/avdmanager"
AVD="${TERN_TEST_AVD:-tern_gpu}"
IMG="${TERN_TEST_IMG:-system-images;android-35;google_apis;x86_64}"
BOOT_TIMEOUT="${TERN_BOOT_TIMEOUT:-180}"

echo "[boot_test_emulator] SDK=$SDK AVD=$AVD"

# Clear the on-device report dir so the dashboard only ever shows THIS run's
# screenshots/scenario reports — they accumulate across runs otherwise, and a
# stale success/welcome image would resurface as a lie. Runs whether we reuse a
# booted device or boot a fresh one. Safe no-op if the device isn't up yet.
clear_device_report_dir() {
  "$ADB" shell "rm -rf /sdcard/Android/data/com.ternparagliding/files/tern-tests-report /sdcard/tern-tests" >/dev/null 2>&1 || true
}

# 1. Already have a booted device? Reuse it.
if "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
  echo "[boot_test_emulator] a booted device is already attached — reusing it."
  clear_device_report_dir
  echo "[boot_test_emulator] cleared stale on-device report dir."
  exit 0
fi

# 2. Create the AVD if it does not exist.
if ! "$EMU" -list-avds 2>/dev/null | grep -qx "$AVD"; then
  echo "[boot_test_emulator] creating AVD '$AVD' from $IMG ..."
  echo "no" | "$AVDMAN" create avd -n "$AVD" -k "$IMG" -d "pixel_6" --force >/dev/null
fi

# 3. Boot headless with real GPU.
echo "[boot_test_emulator] launching '$AVD' (-gpu host, headless) ..."
nohup "$EMU" -avd "$AVD" -gpu host -no-snapshot -no-boot-anim -no-window \
  > /tmp/tern_test_emulator.log 2>&1 &

# 4. Wait for boot.
"$ADB" wait-for-device
elapsed=0
while [ "$elapsed" -lt "$BOOT_TIMEOUT" ]; do
  if "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
    echo "[boot_test_emulator] boot complete after ${elapsed}s."
    "$ADB" shell input keyevent 82 >/dev/null 2>&1 || true   # dismiss keyguard
    clear_device_report_dir
    echo "[boot_test_emulator] cleared stale on-device report dir."
    exit 0
  fi
  sleep 3
  elapsed=$((elapsed + 3))
done

echo "[boot_test_emulator] ERROR: emulator did not boot within ${BOOT_TIMEOUT}s" >&2
exit 1
