#!/usr/bin/env python3
"""
Coverage Threshold Checker for Tern Android App

This script checks code coverage against configured thresholds and provides
pass/fail feedback for quality gates. Designed for local development and
pre-commit hooks.

Usage:
    python scripts/check_coverage_threshold.py [--threshold 80.0]

Exit codes:
    0: All thresholds met
    1: Coverage below threshold
    2: Error parsing reports
"""

import os
import sys
import json
import argparse
from pathlib import Path
from typing import Dict, Optional

class CoverageChecker:
    """Checks code coverage against quality thresholds."""

    def __init__(self, config_path: str = "coverage-thresholds.json"):
        self.config = self.load_config(config_path)
        self.project_root = Path(__file__).parent.parent
        self.thresholds = self.config.get("thresholds", {
            "line": 80.0,
            "instruction": 75.0,
            "branch": 70.0,
            "method": 75.0,
            "class": 85.0
        })

    def load_config(self, config_path: str) -> Dict:
        """Load coverage threshold configuration."""
        config_file = Path(config_path)
        if config_file.exists():
            try:
                with open(config_file, 'r') as f:
                    return json.load(f)
            except Exception as e:
                print(f"Warning: Could not load config {config_path}: {e}")

        # Default configuration
        return {
            "thresholds": {
                "line": 80.0,
                "instruction": 75.0,
                "branch": 70.0,
                "method": 75.0,
                "class": 85.0
            },
            "required_files": [
                "app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml"
            ]
        }

    def parse_coverage_report(self, xml_path: Path) -> Optional[Dict[str, float]]:
        """Parse JaCoCo XML report to extract coverage metrics."""
        if not xml_path.exists():
            print(f"❌ Coverage report not found: {xml_path}")
            return None

        try:
            import xml.etree.ElementTree as ET
            tree = ET.parse(xml_path)
            root = tree.getroot()

            coverage = {}
            for counter in root.findall(".//counter"):
                counter_type = counter.get('type')
                missed = int(counter.get('missed', 0))
                covered = int(counter.get('covered', 0))
                total = missed + covered

                if total > 0:
                    percentage = (covered / total) * 100
                    coverage[counter_type.lower()] = percentage

            return coverage

        except Exception as e:
            print(f"❌ Error parsing coverage report: {e}")
            return None

    def check_thresholds(self, coverage: Dict[str, float]) -> tuple[bool, Dict[str, Dict]]:
        """Check coverage against thresholds."""
        results = {}

        all_passed = True
        for metric, threshold in self.thresholds.items():
            actual = coverage.get(metric, 0.0)
            passed = actual >= threshold
            results[metric] = {
                "threshold": threshold,
                "actual": actual,
                "passed": passed
            }
            if not passed:
                all_passed = False

        return all_passed, results

    def run_checks(self) -> bool:
        """Run all coverage threshold checks."""
        print("🔍 Checking code coverage thresholds...")
        print("=" * 50)

        # Find the coverage report
        report_paths = [
            self.project_root / "app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml",
            self.project_root / "app/build/reports/jacoco/combined/jacocoCombinedReport.xml"
        ]

        coverage = None
        for report_path in report_paths:
            coverage = self.parse_coverage_report(report_path)
            if coverage:
                print(f"✅ Found coverage report: {report_path}")
                break

        if not coverage:
            print("❌ No valid coverage report found")
            print("💡 Run './gradlew jacocoTestReport' first")
            return False

        # Check thresholds
        all_passed, results = self.check_thresholds(coverage)

        print("\n📊 Coverage Results:")
        print("-" * 30)

        for metric, data in results.items():
            status = "✅" if data['passed'] else "❌"
            print(".1f")

        print("
🎯 Overall Status:"        if all_passed:
            print("✅ ALL THRESHOLDS MET - Quality gate passed!")
            return True
        else:
            print("❌ COVERAGE BELOW THRESHOLD - Quality gate failed!")
            print("\n💡 Recommendations:")
            print("- Run './gradlew jacocoTestReport' to regenerate coverage")
            print("- Add more unit tests to increase coverage")
            print("- Focus on high-priority uncovered code")
            return False

def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(description="Check code coverage thresholds")
    parser.add_argument("--threshold", type=float, help="Override default line coverage threshold")
    parser.add_argument("--config", type=str, help="Path to coverage config file")

    args = parser.parse_args()

    checker = CoverageChecker(args.config if args.config else "coverage-thresholds.json")

    if args.threshold:
        checker.thresholds["line"] = args.threshold

    success = checker.run_checks()
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()