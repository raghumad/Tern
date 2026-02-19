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

class VisualReviewHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(REPORT_DIR), **kwargs)

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

            source_file = REPORT_DIR / filename
            if not source_file.exists():
                # Try to find it in the test_output directory if not in common report dir
                self.send_error(404, f"File not found: {filename}")
                return

            # Ensure goldens dir exists
            GOLDENS_DIR.mkdir(parents=True, exist_ok=True)
            
            # Save as golden: use test_name + index or just filename?
            # Better to use a stable name: testName_stepIndex.png
            target_file = GOLDENS_DIR / f"{test_name}.png"
            
            shutil.copy(source_file, target_file)
            print(f"Approved {filename} as golden: {target_file}")

            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({"status": "success", "golden": str(target_file.name)}).encode())

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

            # Update Blacklist
            blacklist = {}
            if BLACKLIST_FILE.exists():
                try:
                    with open(BLACKLIST_FILE, 'r') as f:
                        blacklist = json.load(f)
                except Exception as e:
                    print(f"Error loading blacklist: {e}")

            if test_name not in blacklist:
                blacklist[test_name] = []
            
            if hash_val and hash_val not in blacklist[test_name]:
                blacklist[test_name].append(hash_val)
                with open(BLACKLIST_FILE, 'w') as f:
                    json.dump(blacklist, f, indent=2)
                print(f"Blacklisted hash {hash_val} for test {test_name}")

            source_file = REPORT_DIR / filename
            if not source_file.exists():
                # We still want to acknowledge the blacklist even if we can't copy the file
                self.send_response(200)
                self.send_header('Content-type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(json.dumps({"status": "success", "info": "Blacklisted hash, but image file not found for copying"}).encode())
                return

            rejected_dir = GOLDENS_DIR / "rejected"
            rejected_dir.mkdir(parents=True, exist_ok=True)
            
            target_file = rejected_dir / f"{test_name}.png"
            shutil.copy(source_file, target_file)
            print(f"Rejected {filename}. Blacklisted as bad state: {target_file}")

            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({"status": "success", "rejected": str(target_file.name)}).encode())

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

if __name__ == "__main__":
    if not REPORT_DIR.exists():
        print(f"Error: Report directory not found at {REPORT_DIR}")
        print("Please run ./gradlew testAll first.")
        exit(1)

    os.chdir(REPORT_DIR)
    with socketserver.TCPServer(("", PORT), VisualReviewHandler) as httpd:
        print(f"Visual Reviewer started at http://localhost:{PORT}")
        print(f"Serving reports from: {REPORT_DIR}")
        print(f"Goldens will be saved to: {GOLDENS_DIR}")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down.")
            httpd.server_close()
