#!/usr/bin/env python3
"""
Performance Benchmark Runner for Tern Android App
Executes aviation-grade performance benchmarks and validates safety compliance
"""

import os
import sys
import subprocess
import json
import time
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Any, Optional

class PerformanceBenchmarkRunner:
    """Runs comprehensive performance benchmarks for aviation safety compliance"""

    def __init__(self, project_root: str):
        self.project_root = Path(project_root)
        self.android_dir = self.project_root / "tern-android"
        self.benchmark_results_dir = self.android_dir / "app" / "build" / "reports" / "benchmarks"
        self.baseline_dir = self.benchmark_results_dir / "baselines"

        # Aviation safety thresholds
        self.thresholds = {
            "redux_dispatch_max_ms": 10.0,
            "memory_usage_max_percent": 75.0,
            "gps_processing_max_ms": 5.0,
            "ui_frame_time_max_ms": 16.0,
            "cache_operation_max_ms": 2.0
        }

    def run_benchmarks(self) -> bool:
        """Run all performance benchmarks and return success status"""
        print("🚀 Starting Aviation Performance Benchmarks")
        print("=" * 60)

        try:
            # Ensure we're in the right directory
            os.chdir(self.android_dir)

            # Step 1: Build benchmark APK
            if not self._build_benchmark_apk():
                return False

            # Step 2: Run benchmark tests on device
            if not self._run_device_benchmarks():
                return False

            # Step 3: Generate performance reports
            if not self._generate_reports():
                return False

            # Step 4: Validate safety compliance
            if not self._validate_safety_compliance():
                return False

            print("\n✅ All performance benchmarks completed successfully!")
            print("📊 Results saved to:", self.benchmark_results_dir)
            return True

        except Exception as e:
            print(f"❌ Benchmark execution failed: {e}")
            return False

    def _build_benchmark_apk(self) -> bool:
        """Build the benchmark APK"""
        print("\n🔨 Building benchmark APK...")

        try:
            result = subprocess.run([
                "./gradlew", "assembleBenchmark"
            ], capture_output=True, text=True, timeout=300)

            if result.returncode != 0:
                print("❌ Benchmark APK build failed:")
                print(result.stderr)
                return False

            print("✅ Benchmark APK built successfully")
            return True

        except subprocess.TimeoutExpired:
            print("❌ Benchmark APK build timed out")
            return False

    def _run_device_benchmarks(self) -> bool:
        """Run benchmarks on connected Android device"""
        print("\n📱 Running benchmarks on device...")

        # Check for connected device
        if not self._check_device_connected():
            print("❌ No Android device connected or emulator running")
            print("   Please connect a device or start an emulator")
            return False

        # Install benchmark APK
        if not self._install_benchmark_apk():
            return False

        # Run benchmarks
        if not self._execute_benchmarks():
            return False

        print("✅ Device benchmarks completed")
        return True

    def _check_device_connected(self) -> bool:
        """Check if Android device is connected"""
        try:
            result = subprocess.run([
                "adb", "devices"
            ], capture_output=True, text=True, timeout=10)

            # Look for devices (excluding header line)
            lines = result.stdout.strip().split('\n')[1:]
            devices = [line for line in lines if line.strip() and not line.startswith('*')]

            return len(devices) > 0

        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            return False

    def _install_benchmark_apk(self) -> bool:
        """Install benchmark APK on device"""
        apk_path = self.android_dir / "app" / "build" / "outputs" / "apk" / "benchmark" / "app-benchmark.apk"

        if not apk_path.exists():
            print(f"❌ Benchmark APK not found: {apk_path}")
            return False

        try:
            result = subprocess.run([
                "adb", "install", "-r", str(apk_path)
            ], capture_output=True, text=True, timeout=60)

            if result.returncode != 0:
                print("❌ APK installation failed:")
                print(result.stderr)
                return False

            print("✅ Benchmark APK installed successfully")
            return True

        except subprocess.TimeoutExpired:
            print("❌ APK installation timed out")
            return False

    def _execute_benchmarks(self) -> bool:
        """Execute benchmark tests on device"""
        try:
            print("🏃 Executing performance benchmarks...")

            # Run benchmarks with Android Benchmark runner
            result = subprocess.run([
                "adb", "shell", "am", "instrument", "-w",
                "com.madanala.tern.benchmark/androidx.benchmark.junit4.AndroidBenchmarkRunner"
            ], capture_output=True, text=True, timeout=600)  # 10 minute timeout

            if result.returncode != 0:
                print("❌ Benchmark execution failed:")
                print(result.stderr)
                return False

            # Save benchmark output
            self._save_benchmark_output(result.stdout)

            print("✅ Benchmarks executed successfully")
            return True

        except subprocess.TimeoutExpired:
            print("❌ Benchmark execution timed out")
            return False

    def _save_benchmark_output(self, output: str):
        """Save benchmark output to file"""
        output_file = self.benchmark_results_dir / "benchmark_output.txt"
        output_file.parent.mkdir(parents=True, exist_ok=True)

        with open(output_file, 'w') as f:
            f.write(f"Benchmark execution timestamp: {datetime.now()}\n\n")
            f.write(output)

        print(f"📄 Benchmark output saved to: {output_file}")

    def _generate_reports(self) -> bool:
        """Generate performance reports"""
        print("\n📊 Generating performance reports...")

        try:
            result = subprocess.run([
                "./gradlew", "generatePerformanceReports"
            ], capture_output=True, text=True, timeout=120)

            if result.returncode != 0:
                print("❌ Report generation failed:")
                print(result.stderr)
                return False

            print("✅ Performance reports generated")
            return True

        except subprocess.TimeoutExpired:
            print("❌ Report generation timed out")
            return False

    def _validate_safety_compliance(self) -> bool:
        """Validate that all performance metrics meet aviation safety standards"""
        print("\n🛡️ Validating aviation safety compliance...")

        compliance_report = self.benchmark_results_dir / "safety_compliance_report.json"

        if not compliance_report.exists():
            print("❌ Safety compliance report not found")
            return False

        try:
            with open(compliance_report, 'r') as f:
                data = json.load(f)

            violations = []

            # Check Redux dispatch performance
            redux_check = self._find_check_by_metric(data, "Redux Dispatch Rate")
            if redux_check and redux_check['value'] > self.thresholds['redux_dispatch_max_ms']:
                violations.append(f"Redux dispatch: {redux_check['value']:.2f}ms > {self.thresholds['redux_dispatch_max_ms']}ms")

            # Check memory usage
            memory_check = self._find_check_by_metric(data, "Memory Usage")
            if memory_check and memory_check['value'] > self.thresholds['memory_usage_max_percent']:
                violations.append(f"Memory usage: {memory_check['value']:.1f}% > {self.thresholds['memory_usage_max_percent']}%")

            # Check GPS processing
            gps_check = self._find_check_by_metric(data, "GPS Processing")
            if gps_check and gps_check['value'] > self.thresholds['gps_processing_max_ms']:
                violations.append(f"GPS processing: {gps_check['value']:.2f}ms > {self.thresholds['gps_processing_max_ms']}ms")

            # Check UI responsiveness
            ui_check = self._find_check_by_metric(data, "UI Responsiveness")
            if ui_check and ui_check['value'] > self.thresholds['ui_frame_time_max_ms']:
                violations.append(f"UI responsiveness: {ui_check['value']:.2f}ms > {self.thresholds['ui_frame_time_max_ms']}ms")

            if violations:
                print("🚨 SAFETY COMPLIANCE VIOLATIONS:")
                for violation in violations:
                    print(f"   - {violation}")
                print("\n❌ Aviation safety standards not met!")
                return False

            print("✅ All performance metrics meet aviation safety standards")
            return True

        except (json.JSONDecodeError, KeyError) as e:
            print(f"❌ Error parsing compliance report: {e}")
            return False

    def _find_check_by_metric(self, data: Dict[str, Any], metric_name: str) -> Optional[Dict[str, Any]]:
        """Find a specific metric check in the compliance report"""
        checks = data.get('checks', [])
        for check in checks:
            if check.get('metric') == metric_name:
                return check
        return None

    def generate_ci_summary(self) -> Dict[str, Any]:
        """Generate a summary suitable for CI/CD systems"""
        compliance_report = self.benchmark_results_dir / "safety_compliance_report.json"

        summary = {
            "timestamp": datetime.now().isoformat(),
            "status": "unknown",
            "metrics": {},
            "violations": []
        }

        if compliance_report.exists():
            try:
                with open(compliance_report, 'r') as f:
                    data = json.load(f)

                summary["status"] = "passed" if data.get("overall_compliance", False) else "failed"

                # Extract key metrics
                for check in data.get('checks', []):
                    summary["metrics"][check['metric']] = {
                        "value": check['value'],
                        "threshold": check['threshold'],
                        "passed": check['passed']
                    }

                    if not check['passed']:
                        summary["violations"].append({
                            "metric": check['metric'],
                            "value": check['value'],
                            "threshold": check['threshold']
                        })

            except Exception as e:
                summary["error"] = str(e)
                summary["status"] = "error"

        return summary


def main():
    """Main entry point"""
    script_dir = Path(__file__).parent
    project_root = script_dir.parent.parent

    runner = PerformanceBenchmarkRunner(project_root)

    if len(sys.argv) > 1 and sys.argv[1] == "--ci":
        # CI mode - run benchmarks and output JSON summary
        success = runner.run_benchmarks()
        summary = runner.generate_ci_summary()

        # Ensure status reflects actual benchmark execution
        if not success:
            summary["status"] = "failed"

        print(json.dumps(summary, indent=2))
        sys.exit(0 if success else 1)
    else:
        # Interactive mode
        success = runner.run_benchmarks()
        sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()