#!/usr/bin/env python3
"""Generate the Tern Claims Report — HELD / BROKEN / GAP per claim.

The honest successor to the old screenshot dashboard. Reads the JUnit XML for
claim-driven tests (the `com.ternparagliding.claims` package), groups them by
subsystem (the "known") and axis, and renders a one-glance board. Also lists
the `[GAP]` claims from docs/claims.md — promises that aren't implemented yet, so
the board never hides them behind a passing suite.

Run from anywhere; paths are derived from this file's location. Always exits 0 —
report generation must never fail the build.

Output: tern-android/app/build/reports/claims-report.html
"""
from __future__ import annotations
import glob, re, sys
from pathlib import Path
from xml.etree import ElementTree as ET

SCRIPT = Path(__file__).resolve()
GRADLE_ROOT = SCRIPT.parent.parent              # tern-android/
REPO_ROOT = GRADLE_ROOT.parent                  # repo root (Tern/)
TEST_RESULTS = GRADLE_ROOT / "app/build/test-results/testDebugUnitTest"
CLAIMS_MD = REPO_ROOT / "docs/claims.md"
OUTPUT = GRADLE_ROOT / "app/build/reports/claims-report.html"

CLAIMS_PKG = "com.ternparagliding.claims."

# Map a subsystem (claim-test class, minus "ClaimsTest") to its known in claims.md.
KNOWN = {
    "Position": "K1 · Position & terrain",
    "Airspace": "K2 · Airspace",
    "PgSpot": "K3 · Sites", "Site": "K3 · Sites", "Sites": "K3 · Sites", "Spot": "K3 · Sites",
    "Weather": "K4 · Weather",
    "Mezulla": "K5 · Team", "Peer": "K5 · Team", "Team": "K5 · Team",
    "Route": "K6 · Route/task", "Task": "K6 · Route/task",
    "FlightState": "K7 · Flight state", "Flight": "K7 · Flight state",
}


def _esc(t: str) -> str:
    return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def parse_claim_tests():
    """-> list of dicts: {known, subsystem, axis, desc, status, time, message}"""
    rows = []
    for xp in sorted(glob.glob(str(TEST_RESULTS / "*.xml"))):
        try:
            root = ET.parse(xp).getroot()
        except ET.ParseError:
            continue
        for suite in (root.iter("testsuite") if root.tag != "testsuite" else [root]):
            cls = suite.get("name", "")
            if not cls.startswith(CLAIMS_PKG):
                continue
            subsystem = cls.rsplit(".", 1)[-1].replace("ClaimsTest", "").replace("Test", "")
            known = KNOWN.get(subsystem, subsystem)
            for tc in suite.findall("testcase"):
                name = tc.get("name", "")
                axis, _, desc = name.partition(" - ")
                fail = tc.find("failure")
                if fail is None:
                    fail = tc.find("error")
                if fail is not None:
                    status, msg = "BROKEN", (fail.get("message") or "").strip()
                elif tc.find("skipped") is not None:
                    status, msg = "SKIPPED", ""
                else:
                    status, msg = "HELD", ""
                rows.append({
                    "known": known, "subsystem": subsystem,
                    "axis": (axis or name).strip().title(), "desc": desc.strip(),
                    "status": status, "time": float(tc.get("time", 0) or 0), "message": msg,
                })
    return rows


def parse_gaps():
    """Claims marked [GAP] in claims.md -> list of {known, axis, note}."""
    gaps = []
    if not CLAIMS_MD.exists():
        return gaps
    known = "?"
    for line in CLAIMS_MD.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"###\s+(K\d+)\s*[—-]\s*(.+)", line)
        if m:
            known = f"{m.group(1)} · {m.group(2).split('*')[0].strip()}"
            continue
        if "[GAP]" in line:
            am = re.search(r"\*\*([A-Za-z][A-Za-z ]*?):\*\*", line)
            axis = am.group(1).strip() if am else "?"
            note = re.sub(r"\s+", " ", line.split("[GAP]", 1)[1]).strip(" .`*") or "not implemented"
            gaps.append({"known": known, "axis": axis, "note": note})
    return gaps


def render(rows, gaps) -> str:
    held = sum(1 for r in rows if r["status"] == "HELD")
    broken = sum(1 for r in rows if r["status"] == "BROKEN")
    skipped = sum(1 for r in rows if r["status"] == "SKIPPED")
    colors = {"HELD": "#22c55e", "BROKEN": "#ef4444", "SKIPPED": "#f59e0b", "GAP": "#64748b"}

    # group rows by known (subsystem), preserving first-seen order
    order, groups = [], {}
    for r in rows:
        groups.setdefault(r["known"], []).append(r)
        if r["known"] not in order:
            order.append(r["known"])

    body = []
    for known in order:
        body.append(f'<h2>{_esc(known)}</h2><table>')
        body.append("<tr><th>Axis</th><th>Status</th><th>Claim</th><th>Time</th></tr>")
        for r in sorted(groups[known], key=lambda x: x["axis"]):
            c = colors.get(r["status"], "#94a3b8")
            msg = f'<div class="msg">{_esc(r["message"])}</div>' if r["message"] else ""
            body.append(
                f'<tr><td class="axis">{_esc(r["axis"])}</td>'
                f'<td><span class="pill" style="background:{c}">{r["status"]}</span></td>'
                f'<td>{_esc(r["desc"])}{msg}</td>'
                f'<td class="t">{r["time"]:.2f}s</td></tr>'
            )
        body.append("</table>")

    if gaps:
        body.append("<h2>Gaps — promised but not implemented</h2><table>")
        body.append("<tr><th>Known</th><th>Axis</th><th>Note</th></tr>")
        for g in gaps:
            body.append(
                f'<tr><td>{_esc(g["known"])}</td>'
                f'<td class="axis">{_esc(g["axis"])}</td>'
                f'<td><span class="pill" style="background:{colors["GAP"]}">GAP</span> {_esc(g["note"])}</td></tr>'
            )
        body.append("</table>")

    if not rows and not gaps:
        body.append('<p class="empty">No claim tests found yet. Add tests under '
                     '<code>app/src/test/.../claims/</code> named as claims.</p>')

    return f"""<!doctype html><html><head><meta charset="utf-8">
<title>Tern — Claims Report</title><style>
 body{{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:2rem auto;max-width:62rem;padding:0 1rem;color:#0f172a}}
 h1{{margin:.2rem 0}} .sub{{color:#64748b;margin:0 0 1.2rem}}
 .badges{{display:flex;gap:.6rem;margin:1rem 0 1.6rem;flex-wrap:wrap}}
 .badge{{padding:.5rem .9rem;border-radius:.6rem;color:#fff;font-weight:700}}
 h2{{margin:1.6rem 0 .4rem;font-size:1.05rem}}
 table{{border-collapse:collapse;width:100%;margin-bottom:.6rem;font-size:.92rem}}
 th,td{{text-align:left;padding:.45rem .6rem;border-bottom:1px solid #e2e8f0;vertical-align:top}}
 th{{color:#64748b;font-weight:600;font-size:.8rem;text-transform:uppercase;letter-spacing:.03em}}
 .axis{{font-weight:600;white-space:nowrap}} .t{{color:#94a3b8;white-space:nowrap}}
 .pill{{color:#fff;border-radius:.4rem;padding:.1rem .5rem;font-size:.78rem;font-weight:700}}
 .msg{{color:#ef4444;font-size:.82rem;margin-top:.25rem;font-family:ui-monospace,monospace}}
 .empty{{color:#64748b}} code{{background:#f1f5f9;padding:.1rem .3rem;border-radius:.3rem}}
</style></head><body>
<h1>Tern — Claims Report</h1>
<p class="sub">Promises the app makes to a pilot, demonstrated by claim-driven tests. The honest successor to the screenshot dashboard.</p>
<div class="badges">
 <span class="badge" style="background:{colors['HELD']}">{held} HELD</span>
 <span class="badge" style="background:{colors['BROKEN']}">{broken} BROKEN</span>
 <span class="badge" style="background:{colors['SKIPPED']}">{skipped} SKIPPED</span>
 <span class="badge" style="background:{colors['GAP']}">{len(gaps)} GAP</span>
</div>
{''.join(body)}
</body></html>"""


def main():
    try:
        rows = parse_claim_tests()
        gaps = parse_gaps()
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(render(rows, gaps), encoding="utf-8")
        held = sum(1 for r in rows if r["status"] == "HELD")
        broken = sum(1 for r in rows if r["status"] == "BROKEN")
        print(f"📋 Claims Report: {held} HELD, {broken} BROKEN, {len(gaps)} GAP "
              f"→ file://{OUTPUT}")
    except Exception as e:  # never fail the build over a report
        print(f"claims_report: skipped ({e})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
