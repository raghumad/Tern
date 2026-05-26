#!/usr/bin/env python3
"""Generate a test dashboard with sidebar index + iframe for BDD reports.

Run from tern-android/:  python3 scripts/test_report.py

Output: app/build/reports/tern-test-dashboard.html
"""
from __future__ import annotations
import json, re, sys
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parent.parent
UNIT_RESULTS = PROJECT_ROOT / "app/build/test-results/testDebugUnitTest"
INSTR_RESULTS = PROJECT_ROOT / "app/build/outputs/androidTest-results"
BDD_REPORTS = PROJECT_ROOT / "app/build/reports/bdd-report"
UNIT_SRC = PROJECT_ROOT / "app/src/test"
INSTR_SRC = PROJECT_ROOT / "app/src/instrumentedTests"
OUTPUT_FILE = PROJECT_ROOT / "app/build/reports/tern-test-dashboard.html"

@dataclass
class TC:
    name: str; classname: str; status: str = "pass"
    time_s: float = 0.0; category: str = "unit"
    liar: bool = False; liar_reason: str = ""
    report_file: str = ""; scenario_name: str = ""

def _e(t: str) -> str:
    return t.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("'","&#39;")

# -- Parse unit tests from JUnit XML --
def parse_unit_tests() -> list[TC]:
    cases: list[TC] = []
    if not UNIT_RESULTS.is_dir(): return cases
    for xp in sorted(UNIT_RESULTS.rglob("*.xml")):
        try: tree = ET.parse(xp)
        except ET.ParseError: continue
        for suite in (tree.getroot().iter("testsuite") if tree.getroot().tag != "testsuite" else [tree.getroot()]):
            for el in suite.findall("testcase"):
                tc = TC(name=el.get("name","?"), classname=el.get("classname",""),
                        time_s=float(el.get("time","0") or "0"), category="unit")
                fail_el = el.find("failure")
                if fail_el is None: fail_el = el.find("error")
                if fail_el is not None: tc.status = "fail"
                elif el.find("skipped") is not None: tc.status = "skip"
                cases.append(tc)
    return cases

# -- Parse instrumented tests from BDD summaries + JUnit XML --
def parse_instrumented_tests() -> list[TC]:
    cases: list[TC] = []
    seen: set[str] = set()
    if BDD_REPORTS.is_dir():
        for jp in sorted(BDD_REPORTS.glob("summary_*.json")):
            try: d = json.loads(jp.read_text())
            except Exception: continue
            cn = d.get("className",""); tn = d.get("testName","")
            seen.add(f"{cn}::{tn}")
            tc = TC(name=tn, classname=cn, category="instrumented",
                    status="pass" if d.get("status") == "PASS" else "fail",
                    scenario_name=d.get("scenarioName",""),
                    report_file=d.get("reportFile",""))
            cases.append(tc)
    for rdir in [INSTR_RESULTS]:
        if not rdir.is_dir(): continue
        for xp in sorted(rdir.rglob("*.xml")):
            try: tree = ET.parse(xp)
            except ET.ParseError: continue
            for suite in (tree.getroot().iter("testsuite") if tree.getroot().tag != "testsuite" else [tree.getroot()]):
                for el in suite.findall("testcase"):
                    cn = el.get("classname","").rsplit(".",1)[-1]
                    nm = el.get("name","?")
                    if not nm or nm == "null": continue
                    if f"{cn}::{nm}" in seen: continue
                    seen.add(f"{cn}::{nm}")
                    tc = TC(name=nm, classname=cn, category="instrumented")
                    fail_el = el.find("failure")
                    if fail_el is None: fail_el = el.find("error")
                    if fail_el is not None: tc.status = "fail"
                    elif el.find("skipped") is not None: tc.status = "skip"
                    cases.append(tc)
    return cases

# -- @Liar detection --
_LIAR_RE = re.compile(r'@(?:com\.madanala\.tern\.utils\.)?Liar\s*\(\s*"([^"]*)"', re.M)
def tag_liars(cases: list[TC]) -> None:
    lk: dict[str, str] = {}
    for src in (UNIT_SRC, INSTR_SRC):
        if not src.is_dir(): continue
        for kt in src.rglob("*.kt"):
            m = _LIAR_RE.search(kt.read_text(errors="replace"))
            if not m: continue
            parts = kt.relative_to(src).with_suffix("").parts
            if parts and parts[0] in ("kotlin","java"): parts = parts[1:]
            lk[parts[-1]] = m.group(1)
    for tc in cases:
        short = tc.classname.rsplit(".",1)[-1]
        r = lk.get(short)
        if r: tc.liar, tc.liar_reason = True, r

# -- HTML generation --
def generate_html(unit: list[TC], instr: list[TC]) -> str:
    all_c = unit + instr
    total = len(all_c)
    passed = sum(1 for c in all_c if c.status == "pass" and not c.liar)
    failed = sum(1 for c in all_c if c.status == "fail")
    skipped = sum(1 for c in all_c if c.status == "skip")
    liars = sum(1 for c in all_c if c.liar)

    colors = {"pass":"#22c55e","fail":"#ef4444","skip":"#f59e0b"}
    rel = BDD_REPORTS.relative_to(OUTPUT_FILE.parent) if BDD_REPORTS.is_dir() else "bdd-report"

    # Build sidebar items
    def sidebar_item(tc: TC, idx: int) -> str:
        c = "#a855f7" if tc.liar else colors.get(tc.status, "#94a3b8")
        dot = f'<span style="color:{c};margin-right:6px">●</span>'
        label = _e(tc.scenario_name or tc.name)
        short = _e(tc.classname.rsplit(".",1)[-1])
        if tc.report_file:
            href = f"{rel}/{tc.report_file}"
            return (f'<a class="nav-item" href="{href}" target="report" '
                    f'onclick="document.querySelectorAll(\'.nav-item\').forEach(e=>e.classList.remove(\'active\'));this.classList.add(\'active\')">'
                    f'{dot}<span class="nav-label">{label}</span>'
                    f'<span class="nav-class">{short}</span></a>')
        else:
            return (f'<div class="nav-item disabled">'
                    f'{dot}<span class="nav-label">{label}</span>'
                    f'<span class="nav-class">{short}</span></div>')

    # Instrumented tests: failures first, then liars, then passing
    instr_sorted = sorted(instr, key=lambda t: (0 if t.status=="fail" else 1 if t.liar else 2, t.classname, t.name))
    instr_items = "\n".join(sidebar_item(tc, i) for i, tc in enumerate(instr_sorted))

    # Unit test summary
    up = sum(1 for c in unit if c.status == "pass")
    uf = sum(1 for c in unit if c.status == "fail")

    oc = "#22c55e" if failed == 0 else "#ef4444"
    badge = "ALL PASS" if failed == 0 else f"{failed} FAILURES"

    # Default report to show
    default_report = ""
    for tc in instr_sorted:
        if tc.report_file:
            default_report = f"{rel}/{tc.report_file}"
            break

    return f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1.0"/>
<title>Tern Test Dashboard</title>
<style>
* {{ box-sizing: border-box; margin: 0; padding: 0 }}
body {{ font-family: -apple-system,'Inter',system-ui,sans-serif; background: #0f172a; color: #f8fafc; height: 100vh; overflow: hidden }}
.layout {{ display: flex; height: 100vh }}
.sidebar {{ width: 320px; min-width: 320px; background: #1e293b; border-right: 1px solid #334155; display: flex; flex-direction: column; overflow: hidden }}
.sidebar-header {{ padding: 16px; border-bottom: 1px solid #334155; flex-shrink: 0 }}
.sidebar-header h1 {{ font-size: 1.1rem; color: #38bdf8; margin-bottom: 8px }}
.stats {{ display: flex; gap: 12px; flex-wrap: wrap }}
.stat {{ text-align: center }}
.stat-num {{ font-size: 1.3rem; font-weight: 700 }}
.stat-label {{ font-size: .65rem; color: #64748b; text-transform: uppercase; letter-spacing: .05em }}
.section-header {{ padding: 8px 16px; background: #0f172a; color: #64748b; font-size: .7rem; text-transform: uppercase; letter-spacing: .05em; border-bottom: 1px solid #334155; flex-shrink: 0 }}
.nav-list {{ overflow-y: auto; flex: 1 }}
.nav-item {{ display: block; padding: 8px 16px; border-bottom: 1px solid #0f172a; cursor: pointer; text-decoration: none; color: inherit; transition: background .15s }}
.nav-item:hover {{ background: #334155 }}
.nav-item.active {{ background: #334155; border-left: 3px solid #38bdf8 }}
.nav-item.disabled {{ opacity: .5; cursor: default }}
.nav-label {{ display: block; font-size: .8rem; font-weight: 500; color: #f8fafc; white-space: nowrap; overflow: hidden; text-overflow: ellipsis }}
.nav-class {{ display: block; font-size: .65rem; color: #64748b; margin-top: 2px }}
.main {{ flex: 1; display: flex; flex-direction: column }}
.main iframe {{ flex: 1; border: none; background: #0f172a }}
.badge {{ display: inline-block; padding: 3px 12px; border-radius: 9999px; font-weight: 700; font-size: .75rem; color: #fff }}
::-webkit-scrollbar {{ width: 6px }}
::-webkit-scrollbar-track {{ background: transparent }}
::-webkit-scrollbar-thumb {{ background: #334155; border-radius: 3px }}
</style></head><body>
<div class="layout">
<div class="sidebar">
  <div class="sidebar-header">
    <h1>Tern Tests <span class="badge" style="background:{oc};margin-left:8px">{badge}</span></h1>
    <div class="stats">
      <div class="stat"><div class="stat-num" style="color:#38bdf8">{total}</div><div class="stat-label">Total</div></div>
      <div class="stat"><div class="stat-num" style="color:#22c55e">{passed}</div><div class="stat-label">Pass</div></div>
      <div class="stat"><div class="stat-num" style="color:#ef4444">{failed}</div><div class="stat-label">Fail</div></div>
      <div class="stat"><div class="stat-num" style="color:#f59e0b">{skipped}</div><div class="stat-label">Skip</div></div>
      <div class="stat"><div class="stat-num" style="color:#a855f7">{liars}</div><div class="stat-label">Liar</div></div>
    </div>
  </div>
  <div class="section-header">Instrumented Tests ({sum(1 for c in instr if c.status=='pass')}/{len(instr)} passed)</div>
  <div class="nav-list">
    {instr_items}
  </div>
  <div class="section-header">Unit Tests ({up}/{len(unit)} passed{f', {uf} failed' if uf else ''})</div>
</div>
<div class="main">
  <iframe name="report" src="{default_report}"></iframe>
</div>
</div>
</body></html>"""

def main() -> None:
    unit = parse_unit_tests()
    instr = parse_instrumented_tests()
    if not unit and not instr:
        print("No test results found.")
        sys.exit(0)
    tag_liars(unit + instr)
    html = generate_html(unit, instr)
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_FILE.write_text(html, encoding="utf-8")
    total = len(unit) + len(instr)
    failed = sum(1 for c in unit + instr if c.status == "fail")
    liars = sum(1 for c in unit + instr if c.liar)
    print(f"Dashboard: {OUTPUT_FILE}")
    print(f"  {total} tests | {total-failed} passed | {failed} failed | {liars} liar")

if __name__ == "__main__":
    main()
