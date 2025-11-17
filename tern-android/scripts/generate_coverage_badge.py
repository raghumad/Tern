#!/usr/bin/env python3
"""
Coverage Badge Generator for Tern Android App

This script generates coverage badges for README and CI/CD integration.
It parses JaCoCo XML reports and creates Shields.io compatible badges.
"""

import os
import json
import xml.etree.ElementTree as ET
from pathlib import Path
import argparse

class CoverageBadgeGenerator:
    def __init__(self, project_root=None):
        self.project_root = Path(project_root) if project_root else Path(__file__).parent.parent
        self.reports_dir = self.project_root / "app" / "build" / "reports" / "jacoco" / "combined"
        self.badge_file = self.project_root / "coverage-badge.json"

    def parse_jacoco_xml(self, xml_file):
        """Parse JaCoCo XML report and extract coverage metrics."""
        try:
            tree = ET.parse(xml_file)
            root = tree.getroot()

            # Find overall coverage counters
            counters = {}
            for counter in root.findall(".//counter"):
                counter_type = counter.get('type')
                missed = int(counter.get('missed', 0))
                covered = int(counter.get('covered', 0))
                total = missed + covered
                percentage = (covered / total * 100) if total > 0 else 0.0
                counters[counter_type] = {
                    'missed': missed,
                    'covered': covered,
                    'percentage': round(percentage, 1)
                }

            return counters
        except Exception as e:
            print(f"Error parsing JaCoCo XML: {e}")
            return None

    def get_coverage_color(self, percentage):
        """Get color for coverage badge based on percentage."""
        if percentage >= 90:
            return "brightgreen"
        elif percentage >= 80:
            return "green"
        elif percentage >= 70:
            return "yellowgreen"
        elif percentage >= 60:
            return "yellow"
        elif percentage >= 50:
            return "orange"
        else:
            return "red"

    def generate_badge_data(self):
        """Generate badge data from JaCoCo reports."""
        xml_file = self.reports_dir / "jacocoCombinedReport.xml"

        if not xml_file.exists():
            print(f"JaCoCo XML report not found: {xml_file}")
            return None

        counters = self.parse_jacoco_xml(xml_file)
        if not counters:
            return None

        # Use instruction coverage as primary metric
        instruction_coverage = counters.get('INSTRUCTION', {})
        percentage = instruction_coverage.get('percentage', 0.0)
        color = self.get_coverage_color(percentage)

        badge_data = {
            "percentage": f"{percentage}%",
            "color": color,
            "label": "coverage",
            "message": f"{percentage}%",
            "schemaVersion": 1,
            "namedLogo": "java",
            "logoColor": "white",
            "style": "flat",
            "shield_url": f"https://img.shields.io/badge/coverage-{percentage}%25-{color}.svg",
            "detailed_metrics": {
                "instruction": counters.get('INSTRUCTION', {}),
                "branch": counters.get('BRANCH', {}),
                "method": counters.get('METHOD', {}),
                "class": counters.get('CLASS', {}),
                "line": counters.get('LINE', {})
            }
        }

        return badge_data

    def save_badge(self, badge_data):
        """Save badge data to JSON file."""
        try:
            with open(self.badge_file, 'w') as f:
                json.dump(badge_data, f, indent=2)
            print(f"Coverage badge saved to: {self.badge_file}")
            return True
        except Exception as e:
            print(f"Error saving badge: {e}")
            return False

    def generate_markdown_badge(self, badge_data):
        """Generate markdown for README."""
        shield_url = badge_data['shield_url']
        return f"[![Coverage]({shield_url})](app/build/reports/jacoco/combined/html/index.html)"

    def run(self):
        """Main execution method."""
        print("🔍 Generating coverage badge...")

        badge_data = self.generate_badge_data()
        if not badge_data:
            print("❌ Failed to generate coverage badge")
            return False

        success = self.save_badge(badge_data)
        if success:
            print(f"✅ Coverage badge generated: {badge_data['percentage']}")
            print(f"📊 Detailed metrics available in {self.badge_file}")

            # Print markdown for README
            markdown = self.generate_markdown_badge(badge_data)
            print(f"\n📋 Add this to your README.md:\n{markdown}")

        return success

def main():
    parser = argparse.ArgumentParser(description="Generate coverage badge for Tern Android app")
    parser.add_argument("--project-root", help="Project root directory")
    parser.add_argument("--output", help="Output badge file path")

    args = parser.parse_args()

    generator = CoverageBadgeGenerator(args.project_root)

    if args.output:
        generator.badge_file = Path(args.output)

    success = generator.run()
    exit(0 if success else 1)

if __name__ == "__main__":
    main()