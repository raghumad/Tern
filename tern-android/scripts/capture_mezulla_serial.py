#!/usr/bin/env python3
"""Capture Mezulla board serial logs, timestamped in the PHONE's clock.

Used by the `configureHardwareCycleTest` Gradle tasks so the board's
firmware logs can be merged into the per-test BDD reports alongside the
phone's logcat. Two-sided logs are what make BLE drop/reconnect RCA
possible — the phone log alone can't tell you whether the *board*
re-advertised or refused the connection.

Key behaviours:
  - Opens the port with DTR/RTS deasserted (best-effort no-reset). On
    most CH340/CP210x ESP32 boards the open still pulses a reset; that's
    fine — we open ONCE at the start of the run and hold the handle open
    for the whole suite, so the single reset just gives a clean boot and
    every subsequent drop/reconnect is captured.
  - Each line is prefixed with `[HH:MM:SS.mmm]` in the PHONE's clock
    (host time minus the supplied host-minus-device offset), so the
    timestamps line up 1:1 with the BDD report's step timestamps.
  - Runs until SIGTERM/SIGINT, then flushes and closes cleanly.

Usage:
  capture_mezulla_serial.py --port /dev/ttyACM0 --baud 115200 \
      --offset-ms <hostMinusDeviceMillis> --out <file>
"""
import argparse
import datetime
import signal
import sys
import time

try:
    import serial  # pyserial (ships with the platformio penv)
except ImportError:
    sys.stderr.write("pyserial not available; skipping board serial capture\n")
    sys.exit(0)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default="/dev/ttyACM0")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--offset-ms", type=int, default=0,
                    help="host_epoch_ms - device_epoch_ms; subtracted so lines are stamped in the phone's clock")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    offset = datetime.timedelta(milliseconds=args.offset_ms)

    def device_now() -> str:
        return (datetime.datetime.now() - offset).strftime("%H:%M:%S.%f")[:-3]

    running = {"go": True}

    def stop(_signum, _frame):
        running["go"] = False
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    try:
        s = serial.Serial()
        s.port = args.port
        s.baudrate = args.baud
        s.dtr = False   # best-effort: avoid the ESP32 auto-reset on open
        s.rts = False
        s.timeout = 0.3
        s.open()
    except Exception as e:  # noqa: BLE001 — log and exit 0 so the test run still proceeds
        sys.stderr.write(f"could not open {args.port}: {e}\n")
        return 0

    out = open(args.out, "w", buffering=1, encoding="utf-8")
    out.write(f"[{device_now()}] === mezulla serial capture started ({args.port}@{args.baud}) ===\n")
    buf = b""
    try:
        while running["go"]:
            chunk = s.read(4096)
            if not chunk:
                continue
            buf += chunk
            while b"\n" in buf:
                raw, buf = buf.split(b"\n", 1)
                line = raw.decode("utf-8", "replace").rstrip("\r")
                # Strip ANSI colour codes the firmware emits, for clean reports.
                line = _strip_ansi(line)
                if line:
                    out.write(f"[{device_now()}] {line}\n")
    finally:
        out.write(f"[{device_now()}] === mezulla serial capture stopped ===\n")
        out.close()
        s.close()
    return 0


_ANSI = None


def _strip_ansi(s: str) -> str:
    global _ANSI
    if _ANSI is None:
        import re
        _ANSI = re.compile(r"\x1b\[[0-9;]*m")
    return _ANSI.sub("", s)


if __name__ == "__main__":
    raise SystemExit(main())
