#!/usr/bin/env python3
"""Generate a consolidated HTML test dashboard from JUnit XML results.

Run from the tern-android directory:  python3 scripts/test_report.py

Reads JUnit XML from unit and instrumented test outputs, scans for
screenshots / videos / per-test HTML reports, detects @Untruthful
annotations, and writes a single self-contained HTML file to
  app/build/reports/tern-test-dashboard.html
"""
from __future__ import annotations
import base64, re, sys
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree as ET

# -- Paths (relative to PROJECT_ROOT = tern-android/) -----------------------
PROJECT_ROOT = Path(__file__).resolve().parent.parent
UNIT_RESULTS       = PROJECT_ROOT / "app/build/test-results/testDebugUnitTest"
INSTR_RESULTS      = PROJECT_ROOT / "app/build/outputs/androidTest-results"
MANAGED_OUTPUT     = PROJECT_ROOT / "app/build/outputs/managed_device_android_test_additional_output"
ANDROID_REPORTS    = PROJECT_ROOT / "app/build/reports/androidTests"
RECORDINGS_DIR     = PROJECT_ROOT / "tern-tests"
UNIT_SRC           = PROJECT_ROOT / "app/src/test"
INSTR_SRC          = PROJECT_ROOT / "app/src/instrumentedTests"
OUTPUT_FILE        = PROJECT_ROOT / "app/build/reports/tern-test-dashboard.html"

# -- Data model --------------------------------------------------------------
@dataclass
class TC:
    name: str; classname: str; time_s: float = 0.0
    status: str = "pass"; untruthful: bool = False; untruthful_reason: str = ""
    failure_msg: str = ""; sys_out: str = ""; sys_err: str = ""
    category: str = "Unit Tests"
    screenshots: list[Path] = field(default_factory=list)
    videos: list[Path] = field(default_factory=list)

# -- JUnit XML parsing -------------------------------------------------------
def parse_junit_dir(directory: Path, category: str) -> list[TC]:
    cases: list[TC] = []
    if not directory.is_dir():
        return cases
    for xp in sorted(directory.rglob("*.xml")):
        try: tree = ET.parse(xp)
        except ET.ParseError: continue
        root = tree.getroot()
        suites = root.iter("testsuite") if root.tag == "testsuites" else [root] if root.tag == "testsuite" else root.iter("testsuite")
        for suite in suites:
            sout = (suite.findtext("system-out") or "").strip()
            serr = (suite.findtext("system-err") or "").strip()
            for el in suite.findall("testcase"):
                tc = TC(name=el.get("name","?"), classname=el.get("classname",""),
                        time_s=float(el.get("time","0") or "0"), category=category,
                        sys_out=sout, sys_err=serr)
                fail_el = el.find("failure") or el.find("error")
                if fail_el is not None:
                    tc.status = "fail" if el.find("failure") is not None else "error"
                    tc.failure_msg = fail_el.get("message", "")
                elif el.find("skipped") is not None:
                    tc.status = "skip"
                cases.append(tc)
    return cases

# -- @Untruthful detection from Kotlin sources --------------------------------
_UNTRUTH_RE = re.compile(r'@(?:com\.madanala\.tern\.utils\.)?Untruthful\s*\(\s*"([^"]*)"', re.M)

def _scan_untruthful() -> dict[str, str]:
    result: dict[str, str] = {}
    for src in (UNIT_SRC, INSTR_SRC):
        if not src.is_dir(): continue
        for kt in src.rglob("*.kt"):
            m = _UNTRUTH_RE.search(kt.read_text(errors="replace"))
            if not m: continue
            parts = kt.relative_to(src).with_suffix("").parts
            if parts and parts[0] in ("kotlin", "java"): parts = parts[1:]
            result[".".join(parts)] = m.group(1)
    return result

def tag_untruthful(cases: list[TC]) -> None:
    lk = _scan_untruthful()
    for tc in cases:
        if tc.classname in lk:
            tc.untruthful, tc.untruthful_reason = True, lk[tc.classname]

# -- Artifact attachment -------------------------------------------------------
def _attach_artifacts(cases: list[TC]) -> None:
    keys: dict[str, list[TC]] = {}
    for tc in cases:
        simple = tc.classname.rsplit(".", 1)[-1]
        for k in (tc.name, simple, f"{simple}_{tc.name}", f"{simple}.{tc.name}"):
            keys.setdefault(k.lower(), []).append(tc)

    def match(fname: str) -> list[TC]:
        stem = Path(fname).stem.lower()
        for k, tcs in keys.items():
            if k in stem or stem in k: return tcs
        return []

    for scan in (MANAGED_OUTPUT, ANDROID_REPORTS):
        if not scan.is_dir(): continue
        for p in scan.rglob("*.png"):
            for tc in match(p.name):
                if p not in tc.screenshots: tc.screenshots.append(p)
    if RECORDINGS_DIR.is_dir():
        for p in RECORDINGS_DIR.rglob("*.mp4"):
            for tc in match(p.name):
                if p not in tc.videos: tc.videos.append(p)

# -- HTML helpers --------------------------------------------------------------
S = {"pass": "#22c55e", "fail": "#ef4444", "error": "#ef4444", "skip": "#f59e0b"}

def _e(t: str) -> str:
    return t.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace('"',"&quot;")

def _b64img(p: Path) -> str:
    try: return f"data:image/png;base64,{base64.b64encode(p.read_bytes()).decode()}"
    except Exception: return ""

def _dur(s: float) -> str:
    if s < 0.001: return "<1ms"
    return f"{s*1000:.0f}ms" if s < 1.0 else f"{s:.2f}s"

def _row(tc: TC) -> str:
    color = "#a855f7" if tc.untruthful else S.get(tc.status, "#94a3b8")
    badge = "UNTRUTHFUL" if tc.untruthful else tc.status.upper()
    rid = f"r{hash(tc.classname+tc.name)&0xFFFFFFFF:08x}"
    h = (f'<tr class="test-row" onclick="tog(\'{rid}\')" style="cursor:pointer">'
         f'<td style="padding:10px 16px;border-bottom:1px solid #1e293b">'
         f'<span style="font-weight:600;color:#f8fafc">{_e(tc.name)}</span>'
         f'<br><span style="font-size:.75rem;color:#64748b">{_e(tc.classname)}</span></td>'
         f'<td style="padding:10px 16px;border-bottom:1px solid #1e293b;text-align:center">'
         f'<span style="background:{color};color:#fff;padding:2px 10px;border-radius:9999px;font-size:.75rem;font-weight:600">{badge}</span></td>'
         f'<td style="padding:10px 16px;border-bottom:1px solid #1e293b;text-align:right;color:#94a3b8;font-family:monospace;font-size:.85rem">{_dur(tc.time_s)}</td></tr>')
    # Detail row
    d: list[str] = []
    if tc.untruthful and tc.untruthful_reason:
        d.append(f'<div style="margin-bottom:12px;padding:10px;background:#581c87;border-radius:6px;border-left:4px solid #a855f7;font-size:.85rem">'
                 f'<strong style="color:#d8b4fe">Untruthful:</strong> {_e(tc.untruthful_reason)}</div>')
    if tc.failure_msg:
        d.append(f'<div style="margin-bottom:12px;padding:10px;background:#450a0a;border-radius:6px;border-left:4px solid #ef4444;font-size:.85rem">'
                 f'<strong style="color:#fca5a5">Failure:</strong><pre style="margin:4px 0 0;white-space:pre-wrap;color:#fca5a5;background:transparent;border:none;padding:0">{_e(tc.failure_msg)}</pre></div>')
    if tc.screenshots:
        imgs = "".join(f'<img src="{_b64img(p)}" style="max-width:320px;border:1px solid #334155;border-radius:6px"/>' for p in tc.screenshots if _b64img(p))
        d.append(f'<details style="margin-bottom:12px"><summary style="cursor:pointer;font-weight:600;color:#38bdf8;font-size:.85rem">Screenshots ({len(tc.screenshots)})</summary>'
                 f'<div style="display:flex;flex-wrap:wrap;gap:8px;margin-top:8px">{imgs}</div></details>')
    if tc.videos:
        vids: list[str] = []
        for mp4 in tc.videos:
            try:
                vb = mp4.read_bytes()
                if len(vb) < 10*1024*1024:
                    vids.append(f'<video controls style="max-width:480px;margin-top:8px;border-radius:6px;border:1px solid #334155" src="data:video/mp4;base64,{base64.b64encode(vb).decode()}"></video>')
                else:
                    vids.append(f'<p style="color:#94a3b8;font-size:.85rem">Too large to embed: {_e(mp4.name)} ({len(vb)//1048576}MB)</p>')
            except Exception:
                vids.append(f'<p style="color:#94a3b8;font-size:.85rem">Cannot read: {_e(mp4.name)}</p>')
        d.append(f'<details style="margin-bottom:12px"><summary style="cursor:pointer;font-weight:600;color:#38bdf8;font-size:.85rem">Recordings ({len(tc.videos)})</summary>{"".join(vids)}</details>')
    log = (tc.sys_out + ("\n" if tc.sys_out and tc.sys_err else "") + tc.sys_err).strip()
    if log:
        d.append(f'<details style="margin-bottom:12px"><summary style="cursor:pointer;font-weight:600;color:#38bdf8;font-size:.85rem">Logcat / Output</summary>'
                 f'<pre style="margin-top:8px;max-height:300px;overflow-y:auto;font-size:.75rem;padding:12px;background:#020617;border:1px solid #1e293b;border-radius:6px;color:#cbd5e1;white-space:pre-wrap;word-wrap:break-word">{_e(log)}</pre></details>')
    detail = "".join(d) if d else '<span style="color:#64748b;font-size:.85rem">No additional details.</span>'
    h += f'<tr id="{rid}" class="detail-row" style="display:none"><td colspan="3" style="padding:16px 24px;background:#0f172a;border-bottom:1px solid #1e293b">{detail}</td></tr>'
    return h

# -- Full page -----------------------------------------------------------------
def generate_html(cases: list[TC]) -> str:
    total = len(cases)
    passed  = sum(1 for c in cases if c.status == "pass" and not c.untruthful)
    failed  = sum(1 for c in cases if c.status in ("fail","error"))
    skipped = sum(1 for c in cases if c.status == "skip")
    untrue  = sum(1 for c in cases if c.untruthful)

    groups: dict[str, list[TC]] = {}
    for tc in cases: groups.setdefault(tc.category, []).append(tc)

    cards = "".join(
        f'<div style="text-align:center"><div style="font-size:2rem;font-weight:700;color:{c}">{n}</div>'
        f'<div style="font-size:.75rem;color:#94a3b8;text-transform:uppercase;letter-spacing:.05em">{l}</div></div>'
        for l, n, c in [("Total",total,"#38bdf8"),("Passed",passed,"#22c55e"),("Failed",failed,"#ef4444"),("Skipped",skipped,"#f59e0b"),("Untruthful",untrue,"#a855f7")])

    rows: list[str] = []
    for cat in ("Unit Tests", "Instrumented Tests"):
        cc = groups.get(cat, [])
        if not cc: continue
        cp = sum(1 for c in cc if c.status == "pass")
        rows.append(f'<tr><td colspan="3" style="padding:16px;background:#1e293b;font-weight:700;color:#38bdf8;font-size:1rem;border-bottom:1px solid #334155">'
                    f'{_e(cat)} <span style="font-weight:400;color:#64748b;font-size:.85rem">({cp}/{len(cc)} passed)</span></td></tr>')
        for tc in sorted(cc, key=lambda t: (t.status != "fail", t.classname, t.name)):
            rows.append(_row(tc))

    oc = "#22c55e" if failed == 0 else "#ef4444"
    ol = "ALL PASS" if failed == 0 else f"{failed} FAILURE{'S' if failed != 1 else ''}"

    return f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/>
<title>Tern Test Dashboard</title>
<style>
*{{box-sizing:border-box;margin:0;padding:0}}
body{{font-family:-apple-system,'Inter',system-ui,sans-serif;background:#0f172a;color:#f8fafc;padding:32px;line-height:1.6}}
table{{width:100%;border-collapse:collapse;background:#1e293b;border-radius:12px;overflow:hidden}}
.test-row:hover{{background:#334155}}
details>summary{{list-style:none}}details>summary::-webkit-details-marker{{display:none}}
pre{{font-family:'JetBrains Mono','Fira Code',monospace}}
::-webkit-scrollbar{{width:6px}}::-webkit-scrollbar-track{{background:transparent}}::-webkit-scrollbar-thumb{{background:#334155;border-radius:3px}}
</style></head><body>
<div style="max-width:1100px;margin:0 auto">
<div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:32px;flex-wrap:wrap;gap:16px">
<div><h1 style="font-size:1.5rem;color:#38bdf8">Tern Test Dashboard</h1>
<div style="font-size:.8rem;color:#64748b;margin-top:4px">Generated from JUnit XML results</div></div>
<div style="background:{oc};color:#fff;padding:6px 18px;border-radius:9999px;font-weight:700;font-size:.85rem">{ol}</div></div>
<div style="display:grid;grid-template-columns:repeat(5,1fr);gap:16px;margin-bottom:32px">{cards}</div>
<table><thead><tr style="border-bottom:2px solid #334155">
<th style="text-align:left;padding:12px 16px;color:#94a3b8;font-size:.75rem;text-transform:uppercase;letter-spacing:.05em">Test</th>
<th style="padding:12px 16px;color:#94a3b8;font-size:.75rem;text-transform:uppercase;letter-spacing:.05em;text-align:center">Status</th>
<th style="padding:12px 16px;color:#94a3b8;font-size:.75rem;text-transform:uppercase;letter-spacing:.05em;text-align:right">Duration</th>
</tr></thead><tbody>{"".join(rows)}</tbody></table></div>
<script>function tog(id){{var e=document.getElementById(id);if(e)e.style.display=e.style.display==='none'?'table-row':'none'}}</script>
</body></html>"""

# -- Main ----------------------------------------------------------------------
def main() -> None:
    cases: list[TC] = []
    cases.extend(parse_junit_dir(UNIT_RESULTS, "Unit Tests"))
    cases.extend(parse_junit_dir(INSTR_RESULTS, "Instrumented Tests"))
    if MANAGED_OUTPUT.is_dir():
        for sub in MANAGED_OUTPUT.iterdir():
            cases.extend(parse_junit_dir(sub, "Instrumented Tests"))
    if not cases:
        print(f"No JUnit XML found. Checked:\n  {UNIT_RESULTS}\n  {INSTR_RESULTS}")
        sys.exit(0)
    tag_untruthful(cases)
    _attach_artifacts(cases)
    # Deduplicate by classname::name, keeping richer / worse-status entry
    seen: dict[str, TC] = {}
    for tc in cases:
        key = f"{tc.classname}::{tc.name}"
        if key in seen:
            prev = seen[key]
            if (len(tc.screenshots)+len(tc.videos)) > (len(prev.screenshots)+len(prev.videos)):
                seen[key] = tc
            elif tc.status in ("fail","error") and prev.status not in ("fail","error"):
                seen[key] = tc
        else:
            seen[key] = tc
    cases = list(seen.values())
    html = generate_html(cases)
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_FILE.write_text(html, encoding="utf-8")
    total, failed = len(cases), sum(1 for c in cases if c.status in ("fail","error"))
    untrue = sum(1 for c in cases if c.untruthful)
    print(f"Dashboard: {OUTPUT_FILE}")
    print(f"  {total} tests | {total-failed} passed | {failed} failed | {untrue} untruthful")

if __name__ == "__main__":
    main()
