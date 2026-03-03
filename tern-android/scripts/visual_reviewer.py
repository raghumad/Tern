#!/usr/bin/env python3
import http.server
import socketserver
import os
import json
import shutil
from pathlib import Path

# Paths relative to the project root
PROJECT_ROOT = Path(__file__).parent.parent
GOLDENS_DIR = PROJECT_ROOT / "app/src/instrumentedTests/assets/goldens"
REPORT_DIR = PROJECT_ROOT / "app/build/reports/androidTests/managedDevice/debug/allDevices"
BLACKLIST_FILE = GOLDENS_DIR / "blacklist.json"

PORT = 8080

import http.server
import socketserver
import os
import json
import shutil
import glob
from pathlib import Path

# Paths relative to the project root
PROJECT_ROOT = Path(__file__).parent.parent
GOLDENS_DIR = PROJECT_ROOT / "app/src/instrumentedTests/assets/goldens"
BLACKLIST_FILE = GOLDENS_DIR / "blacklist.json"

# Search paths for test outputs
REPORTS_BASE = PROJECT_ROOT / "app/build/reports/androidTests/managedDevice/debug/allDevices"
ADDITIONAL_OUTPUTS = PROJECT_ROOT / "app/build/outputs/managed_device_android_test_additional_output"

PORT = 8080

class VisualReviewHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        # We'll handle file serving ourselves for flexibility
        super().__init__(*args, **kwargs)

    def do_GET(self):
        if self.path == "/" or self.path == "/index.html":
            self.serve_dashboard()
        elif self.path == "/api/tests":
            self.serve_tests_json()
        elif "/report_" in self.path or "/step_" in self.path or "/failure_" in self.path or "/success_" in self.path:
            # Try to find the file in either directory
            filename = os.path.basename(self.path)
            found_path = self.find_file(filename)
            if found_path:
                self.serve_static_file(found_path)
            else:
                self.send_error(404, f"File not found: {filename}")
        else:
            # Fallback to standard serving if needed (css/js)
            super().do_GET()

    def find_file(self, filename):
        # 1. Check reports base
        p1 = REPORTS_BASE / filename
        if p1.exists(): return p1
        
        # 2. Search in additional outputs
        for p in ADDITIONAL_OUTPUTS.rglob(filename):
            return p
            
        return None

    def get_all_summaries(self):
        summaries = []
        # Search for summary_*.json files
        for p in ADDITIONAL_OUTPUTS.rglob("summary_*.json"):
            try:
                with open(p, 'r') as f:
                    data = json.load(f)
                    data['_path'] = str(p.name)
                    summaries.append(data)
            except Exception as e:
                print(f"Error loading summary {p}: {e}")
        
        # If no JSON summaries, try to infer from HTML reports
        if not summaries:
            for p in ADDITIONAL_OUTPUTS.rglob("report_*.html"):
                filename = p.name
                # report_ClassName_MethodName.html
                parts = filename.replace("report_", "").replace(".html", "").split("_")
                summaries.append({
                    "className": parts[0],
                    "testName": parts[1] if len(parts) > 1 else parts[0],
                    "status": "PASS", # Default
                    "reportFile": filename
                })
        
        return sorted(summaries, key=lambda x: (x.get('className', ''), x.get('testName', '')))

    def serve_dashboard(self):
        self.send_response(200)
        self.send_header('Content-type', 'text/html')
        self.end_headers()
        
        # Embedded HTML/CSS/JS for the premium dashboard
        html = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Tern Visual Reviewer</title>
            <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&family=JetBrains+Mono&display=swap" rel="stylesheet">
            <style>
                :root {
                    --bg: #0f172a;
                    --panel: #1e293b;
                    --accent: #38bdf8;
                    --text: #f8fafc;
                    --text-muted: #94a3b8;
                    --success: #22c55e;
                    --fail: #ef4444;
                    --warning: #f59e0b;
                }
                body {
                    margin: 0;
                    padding: 0;
                    font-family: 'Inter', sans-serif;
                    background: var(--bg);
                    color: var(--text);
                    height: 100vh;
                    display: flex;
                    overflow: hidden;
                }
                #sidebar {
                    width: 350px;
                    background: var(--panel);
                    border-right: 1px solid #334155;
                    display: flex;
                    flex-direction: column;
                    z-index: 10;
                }
                .sidebar-header {
                    padding: 24px;
                    border-bottom: 1px solid #334155;
                }
                .sidebar-header h1 {
                    font-size: 1.25rem;
                    margin: 0;
                    color: var(--accent);
                }
                #search-box {
                    width: calc(100% - 32px);
                    margin: 16px;
                    padding: 10px 14px;
                    background: #0f172a;
                    border: 1px solid #334155;
                    border-radius: 8px;
                    color: var(--text);
                    font-size: 0.9rem;
                }
                #test-list {
                    flex: 1;
                    overflow-y: auto;
                    padding: 16px 0;
                }
                .group-header {
                    padding: 8px 24px;
                    font-size: 0.75rem;
                    text-transform: uppercase;
                    letter-spacing: 0.05em;
                    color: var(--text-muted);
                    font-weight: 700;
                    margin-top: 16px;
                }
                .test-item {
                    padding: 12px 24px;
                    cursor: pointer;
                    transition: background 0.2s;
                    border-left: 4px solid transparent;
                    display: flex;
                    align-items: center;
                    gap: 12px;
                }
                .test-item:hover {
                    background: #334155;
                }
                .test-item.active {
                    background: #0ea5e920;
                    border-left-color: var(--accent);
                }
                .status-dot {
                    width: 8px;
                    height: 8px;
                    border-radius: 50%;
                }
                .status-dot.PASS { background: var(--success); }
                .status-dot.FAIL { background: var(--fail); }
                .test-info { flex: 1; min-width: 0; }
                .test-name { font-size: 0.9rem; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .scenario-name { font-size: 0.75rem; color: var(--text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

                #main {
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    background: var(--bg);
                }
                iframe {
                    flex: 1;
                    border: none;
                    background: white;
                }
                .empty-state {
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    height: 100%;
                    color: var(--text-muted);
                }
                #toolbar {
                    padding: 12px 24px;
                    background: var(--panel);
                    border-bottom: 1px solid #334155;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                }
                .btn {
                    padding: 8px 16px;
                    border-radius: 6px;
                    border: none;
                    cursor: pointer;
                    font-weight: 600;
                    font-size: 0.85rem;
                    transition: opacity 0.2s;
                }
                .btn:hover { opacity: 0.9; }
                .btn-refresh { background: #334155; color: white; }
                
                ::-webkit-scrollbar { width: 6px; }
                ::-webkit-scrollbar-track { background: transparent; }
                ::-webkit-scrollbar-thumb { background: #334155; border-radius: 3px; }
            </style>
        </head>
        <body>
            <div id="sidebar">
                <div class="sidebar-header">
                    <h1>Tern Reviewer</h1>
                    <div style="font-size: 0.75rem; color: var(--text-muted); margin-top: 4px;">Premium Visual Integration Test Report</div>
                </div>
                <input type="text" id="search-box" placeholder="Search scenarios, stories, classes...">
                <div id="test-list">
                    <div class="empty-state">Loading tests...</div>
                </div>
            </div>
            <div id="main">
                <div id="toolbar" style="display: none;">
                    <div id="current-test-title" style="font-weight: 600;"></div>
                    <button class="btn btn-refresh" onclick="window.location.reload()">Refresh Data</button>
                </div>
                <div id="report-container" style="flex: 1; position: relative;">
                    <div class="empty-state" id="initial-empty">
                        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                            <rect width="18" height="18" x="3" y="3" rx="2" ry="2"/>
                            <circle cx="9" cy="9" r="2"/>
                            <path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21"/>
                        </svg>
                        <p>Select a test from the sidebar to review steps and screenshots</p>
                    </div>
                    <iframe id="report-frame" style="display: none;"></iframe>
                </div>
            </div>

            <script>
                let allTests = [];
                async function loadTests() {
                    try {
                        const res = await fetch('/api/tests');
                        allTests = await res.json();
                        renderList(allTests);
                    } catch (e) {
                        document.getElementById('test-list').innerHTML = '<div class="empty-state" style="color:var(--fail)">Failed to load tests. Ensure server is running.</div>';
                    }
                }

                function renderList(tests) {
                    const listContainer = document.getElementById('test-list');
                    if (tests.length === 0) {
                        listContainer.innerHTML = '<div class="empty-state">No BDD reports found in build/outputs.</div>';
                        return;
                    }

                    const grouped = {};
                    tests.forEach(t => {
                        const group = t.className || 'Misc';
                        if (!grouped[group]) grouped[group] = [];
                        grouped[group].push(t);
                    });

                    let html = '';
                    Object.keys(grouped).sort().forEach(group => {
                        html += `<div class="group-header">${group}</div>`;
                        grouped[group].forEach(test => {
                            html += `
                                <div class="test-item" onclick="selectTest('${test.reportFile}', '${test.testName}', this)">
                                    <div class="status-dot ${test.status}"></div>
                                    <div class="test-info">
                                        <div class="test-name">${test.testName}</div>
                                        <div class="scenario-name">${test.scenarioName || ''}</div>
                                    </div>
                                </div>
                            `;
                        });
                    });
                    listContainer.innerHTML = html;
                }

                function selectTest(reportFile, testName, element) {
                    document.querySelectorAll('.test-item').forEach(i => i.classList.remove('active'));
                    element.classList.add('active');
                    
                    document.getElementById('toolbar').style.display = 'flex';
                    document.getElementById('current-test-title').innerText = testName;
                    document.getElementById('initial-empty').style.display = 'none';
                    
                    const frame = document.getElementById('report-frame');
                    frame.style.display = 'block';
                    frame.src = reportFile;
                }

                document.getElementById('search-box').addEventListener('input', (e) => {
                    const q = e.target.value.toLowerCase();
                    const filtered = allTests.filter(t => 
                        (t.testName || '').toLowerCase().includes(q) || 
                        (t.className || '').toLowerCase().includes(q) || 
                        (t.scenarioName || '').toLowerCase().includes(q) ||
                        (t.story || '').toLowerCase().includes(q)
                    );
                    renderList(filtered);
                });

                loadTests();
            </script>
        </body>
        </html>
        """
        self.wfile.write(html.encode())

    def serve_tests_json(self):
        tests = self.get_all_summaries()
        self.send_response(200)
        self.send_header('Content-type', 'application/json')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(json.dumps(tests).encode())

    def serve_static_file(self, full_path):
        content_type = self.guess_type(str(full_path))
        self.send_response(200)
        self.send_header('Content-type', content_type)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        with open(full_path, 'rb') as f:
            self.wfile.write(f.read())

    def do_POST(self):
        if self.path == "/approve":
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            params = json.loads(post_data)
            
            filename = params.get('filename')
            test_name = params.get('test_name')
            
            if not filename or not test_name:
                self.send_error(400, "Missing filename or test_name")
                return

            source_file = self.find_file(filename)
            if not source_file:
                self.send_error(404, f"File not found: {filename}")
                return

            GOLDENS_DIR.mkdir(parents=True, exist_ok=True)
            target_file = GOLDENS_DIR / f"{test_name}.png"
            shutil.copy(source_file, target_file)
            print(f"Approved {filename} as golden: {target_file}")

            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({"status": "success"}).encode())

        elif self.path == "/reject":
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            params = json.loads(post_data)
            
            filename = params.get('filename')
            test_name = params.get('test_name')
            hash_val = params.get('hash')
            
            if not filename or not test_name:
                self.send_error(400, "Missing filename or test_name")
                return

            source_file = self.find_file(filename)
            
            blacklist = {}
            if BLACKLIST_FILE.exists():
                try:
                    with open(BLACKLIST_FILE, 'r') as f:
                        blacklist = json.load(f)
                except Exception as e: print(f"Error loading blacklist: {e}")

            if test_name not in blacklist: blacklist[test_name] = []
            if hash_val and hash_val not in blacklist[test_name]:
                blacklist[test_name].append(hash_val)
                with open(BLACKLIST_FILE, 'w') as f:
                    json.dump(blacklist, f, indent=2)

            if source_file:
                rejected_dir = GOLDENS_DIR / "rejected"
                rejected_dir.mkdir(parents=True, exist_ok=True)
                target_file = rejected_dir / f"{test_name}.png"
                shutil.copy(source_file, target_file)

            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({"status": "success"}).encode())

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

if __name__ == "__main__":
    if not ADDITIONAL_OUTPUTS.exists() and not REPORTS_BASE.exists():
        print(f"Error: Build outputs not found. Please run ./gradlew testAll first.")
        exit(1)

    with socketserver.TCPServer(("", PORT), VisualReviewHandler) as httpd:
        print(f"Premium Visual Reviewer started at http://localhost:{PORT}")
        print(f"Aggregation logic active. Scanning for BDD reports...")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down.")
            httpd.server_close()
