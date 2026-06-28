#!/usr/bin/env python3
"""Inject time-sliced Mezulla board serial logs into per-test BDD reports.

After a hardware test run, the Gradle task pulls each `report_<Class>_<test>.html`
to the host and has a single `mezulla-serial.log` covering the whole run
(timestamped in the phone's clock by capture_mezulla_serial.py). This
script slices that log to each test's time window and adds a
"📟 Mezulla Serial (board)" section next to the existing LogCat section,
so every report shows BOTH ends of the link for that scenario.

Usage:
  inject_mezulla_serial.py --bdd-dir <dir> --serial-log <file> [--margin-s 3]
"""
import argparse
import glob
import html
import os
import re

TS = re.compile(r"\[(\d\d):(\d\d):(\d\d)\.(\d{3})\]")


def to_ms(h, m, s, ms) -> int:
    return ((int(h) * 60 + int(m)) * 60 + int(s)) * 1000 + int(ms)


def report_window(text: str):
    """Min/max [HH:MM:SS.mmm] timestamp in a report, in ms since midnight."""
    pts = [to_ms(*mt.groups()) for mt in TS.finditer(text)]
    return (min(pts), max(pts)) if pts else None


def slice_serial(serial_lines, start_ms, end_ms):
    out = []
    for line in serial_lines:
        mt = TS.match(line)
        if not mt:
            # continuation / un-timestamped line — keep if we're inside the window
            if out:
                out.append(line.rstrip("\n"))
            continue
        t = to_ms(*mt.groups())
        if start_ms <= t <= end_ms:
            out.append(line.rstrip("\n"))
    return out


SECTION_TMPL = (
    '\n<details open style="margin-top:24px;border:1px solid #334155;padding:16px;'
    'border-radius:8px;background-color:#0f172a;">'
    '<summary style="cursor:pointer;font-weight:600;margin-bottom:12px;color:#f59e0b;'
    'font-size:0.9rem;">📟 Mezulla Serial (board) — this scenario\'s window</summary>'
    '<pre style="white-space:pre-wrap;word-wrap:break-word;font-family:\'JetBrains Mono\',monospace;'
    'font-size:12px;max-height:400px;overflow-y:auto;color:#fcd34d;padding:12px;background:#020617;'
    'border-radius:6px;border:1px solid #1e293b;">{body}</pre></details>\n'
)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bdd-dir", required=True)
    ap.add_argument("--serial-log", required=True)
    ap.add_argument("--margin-s", type=int, default=3)
    args = ap.parse_args()

    if not os.path.exists(args.serial_log) or os.path.getsize(args.serial_log) == 0:
        print(f"⚠️  no mezulla serial log at {args.serial_log}; skipping injection")
        return 0

    with open(args.serial_log, encoding="utf-8", errors="replace") as f:
        serial_lines = f.readlines()
    margin = args.margin_s * 1000

    reports = glob.glob(os.path.join(args.bdd_dir, "report_*.html"))
    injected = 0
    for path in reports:
        with open(path, encoding="utf-8", errors="replace") as f:
            doc = f.read()
        if "📟 Mezulla Serial" in doc:
            continue  # idempotent — don't double-inject on re-runs
        win = report_window(doc)
        if not win:
            continue
        sliced = slice_serial(serial_lines, win[0] - margin, win[1] + margin)
        body = html.escape("\n".join(sliced)) if sliced else "(no board serial captured in this window)"
        section = SECTION_TMPL.format(body=body)
        if "</body>" in doc:
            doc = doc.replace("</body>", section + "</body>", 1)
        else:
            doc += section
        with open(path, "w", encoding="utf-8") as f:
            f.write(doc)
        injected += 1
        print(f"📟 injected board serial ({len(sliced)} lines) → {os.path.basename(path)}")

    print(f"📟 mezulla serial injected into {injected} report(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
