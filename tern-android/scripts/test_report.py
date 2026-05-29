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
    video_file: str = ""

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

# -- Parse instrumented tests from BDD summaries + JUnit XML + source --
_TEST_FUN_RE = re.compile(r'^\s*@Test\b[^\n]*\n\s*fun\s+(\w+)', re.M)
_CLASS_RE = re.compile(r'^\s*(?:open|abstract|sealed)?\s*class\s+(\w+)', re.M)
_PACKAGE_RE = re.compile(r'^\s*package\s+([\w.]+)', re.M)

def _discover_from_source(seen: set[str]) -> list[TC]:
    """Walk instrumented source for @Test methods that have not produced
    a result file yet. We surface them as 'not_run' so the dashboard
    reflects the real suite size — previously they were silently absent
    whenever the build dir was empty, which masked entire test classes
    (the hardware-only ones) from view."""
    cases: list[TC] = []
    if not INSTR_SRC.is_dir(): return cases
    for kt in sorted(INSTR_SRC.rglob("*.kt")):
        text = kt.read_text(errors="replace")
        class_m = _CLASS_RE.search(text)
        if not class_m: continue
        class_short = class_m.group(1)
        pkg_m = _PACKAGE_RE.search(text)
        class_fqn = f"{pkg_m.group(1)}.{class_short}" if pkg_m else class_short
        for m in _TEST_FUN_RE.finditer(text):
            method = m.group(1)
            # Match the key shape used by the result parsers below, which
            # store the short class name (rsplit on '.'). That way a result
            # for FullCycleTest::foo overrides our discovered 'not_run' row.
            key = f"{class_short}::{method}"
            if key in seen: continue
            seen.add(key)
            cases.append(TC(name=method, classname=class_fqn,
                            status="not_run", category="instrumented"))
    return cases

def parse_instrumented_tests() -> list[TC]:
    cases: list[TC] = []
    seen: set[str] = set()
    if BDD_REPORTS.is_dir():
        for jp in sorted(BDD_REPORTS.glob("summary_*.json")):
            try: d = json.loads(jp.read_text())
            except Exception: continue
            cn = d.get("className",""); tn = d.get("testName","")
            # Use short class name in `seen` so source-discovery (which only
            # has the short name to work with) can de-dupe correctly.
            seen.add(f"{cn.rsplit('.',1)[-1]}::{tn}")
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
    cases.extend(_discover_from_source(seen))
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

# -- Video attachment --
def attach_videos(cases: list[TC]) -> None:
    if not BDD_REPORTS.is_dir(): return
    vids = {p.stem.lower(): p.name for p in BDD_REPORTS.glob("*.mp4")}
    for tc in cases:
        if tc.category != "instrumented": continue
        # VideoHelper names files as method_name.mp4 (spaces replaced with _)
        key = tc.name.replace(" ", "_").lower()
        if key in vids:
            tc.video_file = vids[key]
            continue
        # Try class_method pattern
        short = tc.classname.rsplit(".", 1)[-1]
        for k, v in vids.items():
            if tc.name.lower() in k or short.lower() in k:
                tc.video_file = v
                break

# -- Wrapper page generation --
def generate_wrappers(cases: list[TC], rel: str) -> None:
    for tc in cases:
        if not tc.report_file and not tc.video_file: continue
        wrapper_name = f"view_{tc.classname.rsplit('.', 1)[-1]}_{tc.name}.html"
        wrapper_path = OUTPUT_FILE.parent / wrapper_name
        video_html = ""
        if tc.video_file:
            video_html = (f'<div style="padding:16px;background:#1e293b;border-bottom:1px solid #334155">'
                         f'<video controls style="width:100%;max-height:400px;border-radius:8px;border:1px solid #334155" '
                         f'src="{rel}/{_e(tc.video_file)}"></video></div>')
        report_html = ""
        if tc.report_file:
            report_html = f'<iframe src="{rel}/{tc.report_file}" style="flex:1;border:none;width:100%"></iframe>'
        else:
            report_html = '<div style="padding:32px;color:#64748b">No BDD report for this test.</div>'
        html = f"""<!DOCTYPE html>
<html><head><meta charset="UTF-8"/><style>
*{{margin:0;padding:0;box-sizing:border-box}}
body{{background:#0f172a;display:flex;flex-direction:column;height:100vh}}
</style></head><body>
{video_html}
{report_html}
</body></html>"""
        wrapper_path.write_text(html)
        tc._wrapper = wrapper_name

# -- HTML generation --
def generate_html(unit: list[TC], instr: list[TC]) -> str:
    all_c = unit + instr
    total = len(all_c)
    passed = sum(1 for c in all_c if c.status == "pass" and not c.liar)
    failed = sum(1 for c in all_c if c.status == "fail")
    skipped = sum(1 for c in all_c if c.status == "skip")
    not_run = sum(1 for c in all_c if c.status == "not_run")
    liars = sum(1 for c in all_c if c.liar)

    colors = {"pass":"#22c55e","fail":"#ef4444","skip":"#f59e0b","not_run":"#64748b"}
    rel = BDD_REPORTS.relative_to(OUTPUT_FILE.parent) if BDD_REPORTS.is_dir() else "bdd-report"

    # Build sidebar items
    def sidebar_item(tc: TC, idx: int) -> str:
        c = "#a855f7" if tc.liar else colors.get(tc.status, "#94a3b8")
        dot = f'<span style="color:{c};margin-right:6px">●</span>'
        vid = '<span style="color:#38bdf8;margin-left:auto;font-size:.7rem">▶</span>' if tc.video_file else ''
        label = _e(tc.scenario_name or tc.name)
        short = _e(tc.classname.rsplit(".",1)[-1])
        href = getattr(tc, '_wrapper', '') or (f"{rel}/{tc.report_file}" if tc.report_file else '')
        if href:
            return (f'<a class="nav-item" href="{href}" target="report" '
                    f'onclick="document.querySelectorAll(\'.nav-item\').forEach(e=>e.classList.remove(\'active\'));this.classList.add(\'active\')">'
                    f'{dot}<span class="nav-label">{label}</span>{vid}'
                    f'<span class="nav-class">{short}</span></a>')
        else:
            return (f'<div class="nav-item disabled">'
                    f'{dot}<span class="nav-label">{label}</span>'
                    f'<span class="nav-class">{short}</span></div>')

    # Instrumented tests: failures first, then liars, then not-run, then passing
    def _rank(t: TC) -> int:
        if t.status == "fail": return 0
        if t.liar: return 1
        if t.status == "not_run": return 2
        return 3
    instr_sorted = sorted(instr, key=lambda t: (_rank(t), t.classname, t.name))
    instr_items = "\n".join(sidebar_item(tc, i) for i, tc in enumerate(instr_sorted))

    # Unit test summary
    up = sum(1 for c in unit if c.status == "pass")
    uf = sum(1 for c in unit if c.status == "fail")

    if failed:
        oc, badge = "#ef4444", f"{failed} FAILURES"
    elif not_run:
        oc, badge = "#64748b", f"{not_run} NEVER RUN"
    else:
        oc, badge = "#22c55e", "ALL PASS"

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
      <div class="stat"><div class="stat-num" style="color:#64748b">{not_run}</div><div class="stat-label">Not Run</div></div>
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
    all_cases = unit + instr
    tag_liars(all_cases)
    attach_videos(all_cases)
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    rel = BDD_REPORTS.relative_to(OUTPUT_FILE.parent) if BDD_REPORTS.is_dir() else "bdd-report"
    generate_wrappers(instr, str(rel))
    html = generate_html(unit, instr)
    OUTPUT_FILE.write_text(html, encoding="utf-8")
    total = len(all_cases)
    passed = sum(1 for c in all_cases if c.status == "pass")
    failed = sum(1 for c in all_cases if c.status == "fail")
    skipped = sum(1 for c in all_cases if c.status == "skip")
    not_run = sum(1 for c in all_cases if c.status == "not_run")
    liars = sum(1 for c in all_cases if c.liar)
    vids = sum(1 for c in all_cases if c.video_file)
    print(f"Dashboard: {OUTPUT_FILE}")
    print(f"  {total} tests | {passed} pass | {failed} fail | {skipped} skip | {not_run} never-run | {liars} liar | {vids} with video")

if __name__ == "__main__":
    main()
