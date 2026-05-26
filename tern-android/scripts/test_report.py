#!/usr/bin/env python3
"""Generate a consolidated HTML test dashboard.

Run from the tern-android directory:  python3 scripts/test_report.py

For instrumented tests, reads the per-test BDD HTML reports (which
contain Gherkin steps, screenshots, logcat, perf scorecards) and
embeds them inline. For unit tests, reads JUnit XML.

Output: app/build/reports/tern-test-dashboard.html
"""
from __future__ import annotations
import json, re, sys
from dataclasses import dataclass, field
from pathlib import Path
from xml.etree import ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parent.parent
UNIT_RESULTS   = PROJECT_ROOT / "app/build/test-results/testDebugUnitTest"
INSTR_RESULTS  = PROJECT_ROOT / "app/build/outputs/androidTest-results"
MANAGED_OUTPUT = PROJECT_ROOT / "app/build/outputs/managed_device_android_test_additional_output"
BDD_REPORTS    = PROJECT_ROOT / "app/build/reports/bdd-report"
UNIT_SRC       = PROJECT_ROOT / "app/src/test"
INSTR_SRC      = PROJECT_ROOT / "app/src/instrumentedTests"
OUTPUT_FILE    = PROJECT_ROOT / "app/build/reports/tern-test-dashboard.html"

@dataclass
class TC:
    name: str; classname: str; time_s: float = 0.0
    status: str = "pass"; liar: bool = False; liar_reason: str = ""
    failure_msg: str = ""; category: str = "Unit Tests"
    bdd_html: str = ""  # inline BDD report content
    scenario_name: str = ""
    story: str = ""

def _e(t: str) -> str:
    return t.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")

# -- Unit test JUnit XML parsing -----------------------------------------------
def parse_unit_tests() -> list[TC]:
    cases: list[TC] = []
    if not UNIT_RESULTS.is_dir(): return cases
    for xp in sorted(UNIT_RESULTS.rglob("*.xml")):
        try: tree = ET.parse(xp)
        except ET.ParseError: continue
        root = tree.getroot()
        suites = root.iter("testsuite") if root.tag == "testsuites" else [root]
        for suite in suites:
            for el in suite.findall("testcase"):
                tc = TC(name=el.get("name","?"), classname=el.get("classname",""),
                        time_s=float(el.get("time","0") or "0"), category="Unit Tests")
                fail_el = el.find("failure")
                if fail_el is None: fail_el = el.find("error")
                if fail_el is not None:
                    tc.status = "fail"
                    tc.failure_msg = fail_el.get("message") or (fail_el.text or "").split("\n")[0]
                elif el.find("skipped") is not None:
                    tc.status = "skip"
                cases.append(tc)
    return cases

# -- Instrumented test parsing from BDD summaries + JUnit XML -------------------
def parse_instrumented_tests() -> list[TC]:
    cases: list[TC] = []
    seen_keys: set[str] = set()

    # Primary source: BDD JSON summaries (have Gherkin, screenshots, logcat)
    if BDD_REPORTS.is_dir():
        for jp in sorted(BDD_REPORTS.glob("summary_*.json")):
            try: d = json.loads(jp.read_text())
            except Exception: continue
            cn = d.get("className", "")
            tn = d.get("testName", "")
            key = f"{cn}::{tn}"
            seen_keys.add(key)
            tc = TC(name=tn, classname=f"com.madanala.tern.{cn}" if "." not in cn else cn,
                    status="pass" if d.get("status") == "PASS" else "fail",
                    category="Instrumented Tests",
                    scenario_name=d.get("scenarioName", ""),
                    story=d.get("story", ""))
            # Load the per-test BDD HTML report inline
            report_file = d.get("reportFile", "")
            if report_file:
                rp = BDD_REPORTS / report_file
                if rp.is_file():
                    html = rp.read_text(errors="replace")
                    # Extract just the <body> content
                    body_match = re.search(r"<body>(.*)</body>", html, re.DOTALL)
                    if body_match:
                        tc.bdd_html = body_match.group(1)
            cases.append(tc)

    # Secondary: JUnit XML for tests not in BDD summaries (smoke tests, @Ignored)
    for results_dir in [INSTR_RESULTS]:
        if not results_dir.is_dir(): continue
        for xp in sorted(results_dir.rglob("*.xml")):
            try: tree = ET.parse(xp)
            except ET.ParseError: continue
            for suite in tree.getroot().iter("testsuite") if tree.getroot().tag != "testsuite" else [tree.getroot()]:
                for el in suite.findall("testcase"):
                    cn = el.get("classname", "")
                    nm = el.get("name", "?")
                    short_cn = cn.rsplit(".", 1)[-1] if "." in cn else cn
                    key = f"{short_cn}::{nm}"
                    if key in seen_keys: continue
                    seen_keys.add(key)
                    tc = TC(name=nm, classname=cn, category="Instrumented Tests",
                            time_s=float(el.get("time", "0") or "0"))
                    fail_el = el.find("failure")
                    if fail_el is None: fail_el = el.find("error")
                    if fail_el is not None:
                        tc.status = "fail"
                        tc.failure_msg = fail_el.get("message") or (fail_el.text or "").split("\n")[0]
                    elif el.find("skipped") is not None:
                        tc.status = "skip"
                    cases.append(tc)
    return cases

# -- @Liar detection from Kotlin sources ----------------------------------------
_LIAR_RE = re.compile(r'@(?:com\.madanala\.tern\.utils\.)?Liar\s*\(\s*"([^"]*)"', re.M)

def tag_liars(cases: list[TC]) -> None:
    lk_full: dict[str, str] = {}
    lk_short: dict[str, str] = {}
    for src in (UNIT_SRC, INSTR_SRC):
        if not src.is_dir(): continue
        for kt in src.rglob("*.kt"):
            m = _LIAR_RE.search(kt.read_text(errors="replace"))
            if not m: continue
            parts = kt.relative_to(src).with_suffix("").parts
            if parts and parts[0] in ("kotlin", "java"): parts = parts[1:]
            fqcn = ".".join(parts)
            lk_full[fqcn] = m.group(1)
            lk_short[parts[-1]] = m.group(1)
    for tc in cases:
        short = tc.classname.rsplit(".", 1)[-1]
        reason = lk_full.get(tc.classname) or lk_short.get(short)
        if reason:
            tc.liar, tc.liar_reason = True, reason

# -- HTML rendering ------------------------------------------------------------
S = {"pass": "#22c55e", "fail": "#ef4444", "error": "#ef4444", "skip": "#f59e0b"}

def _instr_row(tc: TC) -> str:
    color = "#a855f7" if tc.liar else S.get(tc.status, "#94a3b8")
    badge = "LIAR" if tc.liar else tc.status.upper()
    rid = f"r{hash(tc.classname+tc.name)&0xFFFFFFFF:08x}"
    short_class = tc.classname.rsplit(".", 1)[-1]
    title = tc.scenario_name or tc.name
    subtitle = tc.story if tc.story and tc.story != "null" else short_class

    h = (f'<tr class="test-row" onclick="tog(\'{rid}\')" style="cursor:pointer">'
         f'<td style="padding:12px 16px;border-bottom:1px solid #1e293b">'
         f'<span style="font-weight:600;color:#f8fafc">{_e(title)}</span>'
         f'<br><span style="font-size:.75rem;color:#64748b">{_e(subtitle)}</span></td>'
         f'<td style="padding:12px 16px;border-bottom:1px solid #1e293b;text-align:center">'
         f'<span style="background:{color};color:#fff;padding:2px 10px;border-radius:9999px;font-size:.75rem;font-weight:600">{badge}</span></td></tr>')

    # Detail row with embedded BDD report
    d: list[str] = []
    if tc.liar and tc.liar_reason:
        d.append(f'<div style="margin-bottom:12px;padding:10px;background:#581c87;border-radius:6px;border-left:4px solid #a855f7;font-size:.85rem">'
                 f'<strong style="color:#d8b4fe">Liar:</strong> {_e(tc.liar_reason)}</div>')
    if tc.failure_msg:
        d.append(f'<div style="margin-bottom:12px;padding:10px;background:#450a0a;border-radius:6px;border-left:4px solid #ef4444;font-size:.85rem">'
                 f'<strong style="color:#fca5a5">Failure:</strong> {_e(tc.failure_msg[:200])}</div>')
    if tc.bdd_html:
        d.append(f'<div style="margin-top:8px">{tc.bdd_html}</div>')
    elif tc.status == "skip":
        d.append('<span style="color:#64748b;font-size:.85rem">Skipped (likely @Ignore)</span>')
    else:
        d.append('<span style="color:#64748b;font-size:.85rem">No BDD report generated for this test.</span>')

    detail = "".join(d)
    h += f'<tr id="{rid}" class="detail-row" style="display:none"><td colspan="2" style="padding:0;background:#0f172a;border-bottom:1px solid #1e293b"><div style="padding:16px 24px;max-height:800px;overflow-y:auto">{detail}</div></td></tr>'
    return h

def _unit_summary(cases: list[TC]) -> str:
    if not cases: return ""
    passed = sum(1 for c in cases if c.status == "pass")
    failed = [c for c in cases if c.status in ("fail", "error")]
    total = len(cases)
    color = "#22c55e" if not failed else "#ef4444"

    h = (f'<div style="background:#1e293b;border-radius:12px;padding:24px;margin-bottom:24px;border:1px solid #334155">'
         f'<h2 style="color:#38bdf8;font-size:1.1rem;margin-bottom:16px">Unit Tests '
         f'<span style="color:{color};font-weight:400;font-size:.9rem">{passed}/{total} passed</span></h2>')

    if failed:
        h += '<div style="margin-bottom:12px">'
        for tc in failed:
            h += (f'<div style="padding:8px 12px;background:#450a0a;border-radius:6px;border-left:3px solid #ef4444;margin-bottom:6px;font-size:.85rem">'
                  f'<strong style="color:#fca5a5">{_e(tc.classname.rsplit(".",1)[-1])}.{_e(tc.name)}</strong>'
                  f'<span style="color:#94a3b8;margin-left:8px">{_e(tc.failure_msg[:120])}</span></div>')
        h += '</div>'
    else:
        h += f'<div style="color:#64748b;font-size:.85rem">All {total} unit tests passing.</div>'
    h += '</div>'
    return h

def generate_html(unit: list[TC], instr: list[TC]) -> str:
    all_cases = unit + instr
    total = len(all_cases)
    passed = sum(1 for c in all_cases if c.status == "pass" and not c.liar)
    failed = sum(1 for c in all_cases if c.status in ("fail", "error"))
    skipped = sum(1 for c in all_cases if c.status == "skip")
    liars = sum(1 for c in all_cases if c.liar)

    cards = "".join(
        f'<div style="text-align:center"><div style="font-size:2rem;font-weight:700;color:{c}">{n}</div>'
        f'<div style="font-size:.75rem;color:#94a3b8;text-transform:uppercase;letter-spacing:.05em">{l}</div></div>'
        for l, n, c in [("Total",total,"#38bdf8"),("Passed",passed,"#22c55e"),
                        ("Failed",failed,"#ef4444"),("Skipped",skipped,"#f59e0b"),("Liar",liars,"#a855f7")])

    # Instrumented test rows — failures first
    instr_rows = []
    ip = sum(1 for c in instr if c.status == "pass")
    if instr:
        for tc in sorted(instr, key=lambda t: (t.status != "fail", t.classname, t.name)):
            instr_rows.append(_instr_row(tc))

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
.scenario{{margin-bottom:20px;background:#1e293b;border-radius:12px;padding:20px;border:1px solid #334155}}
.step{{margin-bottom:12px;padding:12px;border-radius:8px;background:#0f172a;border-left:4px solid #475569}}
.PASS{{border-left-color:#22c55e}}.FAIL{{border-left-color:#ef4444}}
img{{max-width:100%;margin:12px 0;border:1px solid #334155;border-radius:8px;display:block}}
details{{margin-top:16px;border:1px solid #334155;padding:12px;border-radius:8px;background:#0f172a}}
summary{{cursor:pointer;font-weight:600;color:#38bdf8;font-size:.9rem}}
pre{{white-space:pre-wrap;word-wrap:break-word;font-family:'JetBrains Mono',monospace;font-size:12px;max-height:400px;overflow-y:auto;color:#cbd5e1;padding:12px;background:#020617;border-radius:6px;border:1px solid #1e293b}}
h1{{color:#38bdf8}}.approve-btn,.reject-btn{{display:none}}
::-webkit-scrollbar{{width:6px}}::-webkit-scrollbar-track{{background:transparent}}::-webkit-scrollbar-thumb{{background:#334155;border-radius:3px}}
</style></head><body>
<div style="max-width:1100px;margin:0 auto">
<div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:32px;flex-wrap:wrap;gap:16px">
<div><h1 style="font-size:1.5rem">Tern Test Dashboard</h1>
<div style="font-size:.8rem;color:#64748b;margin-top:4px">Consolidated unit + instrumented results</div></div>
<div style="background:{oc};color:#fff;padding:6px 18px;border-radius:9999px;font-weight:700;font-size:.85rem">{ol}</div></div>
<div style="display:grid;grid-template-columns:repeat(5,1fr);gap:16px;margin-bottom:32px">{cards}</div>

{_unit_summary(unit)}

<div style="background:#1e293b;border-radius:12px;overflow:hidden;border:1px solid #334155">
<div style="padding:16px;border-bottom:2px solid #334155">
<h2 style="color:#38bdf8;font-size:1.1rem">Instrumented Tests
<span style="font-weight:400;color:#64748b;font-size:.9rem">{ip}/{len(instr)} passed — click to expand Gherkin + screenshots</span></h2></div>
<table><tbody>{"".join(instr_rows)}</tbody></table>
</div></div>
<script>function tog(id){{var e=document.getElementById(id);if(e)e.style.display=e.style.display==='none'?'table-row':'none'}}</script>
</body></html>"""

def main() -> None:
    unit = parse_unit_tests()
    instr = parse_instrumented_tests()
    if not unit and not instr:
        print(f"No test results found. Checked:\n  {UNIT_RESULTS}\n  {BDD_REPORTS}")
        sys.exit(0)
    tag_liars(unit + instr)
    html = generate_html(unit, instr)
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_FILE.write_text(html, encoding="utf-8")
    total = len(unit) + len(instr)
    failed = sum(1 for c in unit + instr if c.status in ("fail", "error"))
    liars = sum(1 for c in unit + instr if c.liar)
    print(f"Dashboard: {OUTPUT_FILE}")
    print(f"  {total} tests | {total-failed} passed | {failed} failed | {liars} liar")

if __name__ == "__main__":
    main()
