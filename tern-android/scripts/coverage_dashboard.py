#!/usr/bin/env python3
"""
Coverage Dashboard Generator for Tern Android App

This script generates comprehensive coverage dashboards with visualizations
and analytics for development teams and stakeholders.
"""

import os
import json
import base64
from pathlib import Path
from datetime import datetime, timedelta
import argparse
from typing import Dict, List, Optional

class CoverageDashboardGenerator:
    def __init__(self, project_root=None):
        self.project_root = Path(project_root) if project_root else Path(__file__).parent.parent
        self.dashboard_dir = self.project_root / "coverage-dashboard"
        self.history_file = self.project_root / ".coverage_history.json"
        self.thresholds_file = self.project_root / "coverage-thresholds.json"

    def load_data(self):
        """Load all necessary data for dashboard generation."""
        data = {
            'current_coverage': None,
            'history': [],
            'thresholds': {},
            'package_breakdown': {},
            'trends': {}
        }

        # Load current coverage
        badge_file = self.project_root / "coverage-badge.json"
        if badge_file.exists():
            try:
                with open(badge_file, 'r') as f:
                    data['current_coverage'] = json.load(f)
            except Exception as e:
                print(f"Error loading current coverage: {e}")

        # Load history
        if self.history_file.exists():
            try:
                with open(self.history_file, 'r') as f:
                    data['history'] = json.load(f)
            except Exception as e:
                print(f"Error loading history: {e}")

        # Load thresholds
        if self.thresholds_file.exists():
            try:
                with open(self.thresholds_file, 'r') as f:
                    data['thresholds'] = json.load(f)
            except Exception as e:
                print(f"Error loading thresholds: {e}")

        # Load package breakdown from XML report
        xml_file = self.project_root / "app" / "build" / "reports" / "jacoco" / "detailed" / "jacocoDetailedReport.xml"
        if xml_file.exists():
            data['package_breakdown'] = self.parse_package_coverage(xml_file)

        return data

    def parse_package_coverage(self, xml_file):
        """Parse package-level coverage from JaCoCo XML."""
        try:
            import xml.etree.ElementTree as ET

            tree = ET.parse(xml_file)
            root = tree.getroot()

            packages = {}
            for package in root.findall(".//package"):
                name = package.get('name', '')
                if name:
                    counters = {}
                    for counter in package.findall(".//counter"):
                        counter_type = counter.get('type')
                        missed = int(counter.get('missed', 0))
                        covered = int(counter.get('covered', 0))
                        total = missed + covered
                        percentage = (covered / total * 100) if total > 0 else 0.0
                        counters[counter_type.lower()] = round(percentage, 1)

                    packages[name] = counters

            return packages
        except Exception as e:
            print(f"Error parsing package coverage: {e}")
            return {}

    def generate_html_dashboard(self, data):
        """Generate HTML dashboard."""
        current = data['current_coverage'] or {}
        history = data['history']
        thresholds = data['thresholds']
        package_breakdown = data['package_breakdown']

        # Prepare data for JavaScript
        current_metrics_json = json.dumps(current.get('detailed_metrics', {}))

        # Calculate trend data
        trend_data = []
        if history:
            for i, entry in enumerate(history[-30:]):  # Last 30 entries
                trend_data.append({
                    'x': i,
                    'y': entry['percentage'],
                    'date': entry['timestamp'][:10]  # YYYY-MM-DD
                })

        html = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Tern Android - Code Coverage Dashboard</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            margin: 0;
            padding: 20px;
            background-color: #f5f5f5;
        }}

        .dashboard {{
            max-width: 1200px;
            margin: 0 auto;
        }}

        .header {{
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            padding: 30px;
            border-radius: 10px;
            margin-bottom: 30px;
            text-align: center;
        }}

        .metric-cards {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
            gap: 20px;
            margin-bottom: 30px;
        }}

        .metric-card {{
            background: white;
            padding: 20px;
            border-radius: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
            text-align: center;
        }}

        .metric-value {{
            font-size: 2.5em;
            font-weight: bold;
            color: #333;
        }}

        .metric-label {{
            color: #666;
            margin-top: 5px;
        }}

        .coverage-excellent {{ color: #28a745; }}
        .coverage-good {{ color: #ffc107; }}
        .coverage-poor {{ color: #dc3545; }}

        .charts {{
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 30px;
            margin-bottom: 30px;
        }}

        .chart-container {{
            background: white;
            padding: 20px;
            border-radius: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
        }}

        .package-table {{
            background: white;
            border-radius: 10px;
            box-shadow: 0 2px 10px rgba(0,0,0,0.1);
            overflow: hidden;
        }}

        .package-table table {{
            width: 100%;
            border-collapse: collapse;
        }}

        .package-table th,
        .package-table td {{
            padding: 12px;
            text-align: left;
            border-bottom: 1px solid #eee;
        }}

        .package-table th {{
            background-color: #f8f9fa;
            font-weight: 600;
        }}

        .package-coverage {{
            font-weight: bold;
        }}

        .footer {{
            text-align: center;
            color: #666;
            margin-top: 30px;
        }}

        @media (max-width: 768px) {{
            .charts {{
                grid-template-columns: 1fr;
            }}
        }}
    </style>
</head>
<body>
    <div class="dashboard">
        <div class="header">
            <h1>📊 Tern Android - Code Coverage Dashboard</h1>
            <p>Generated on {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>
        </div>

        <div class="metric-cards">
            {self.generate_metric_cards(current)}
        </div>

        <div class="charts">
            <div class="chart-container">
                <h3>📈 Coverage Trend (Last 30 Days)</h3>
                <canvas id="trendChart" width="400" height="200"></canvas>
            </div>

            <div class="chart-container">
                <h3>📊 Coverage Distribution</h3>
                <canvas id="distributionChart" width="400" height="200"></canvas>
            </div>
        </div>

        <div class="package-table">
            <h3 style="padding: 20px; margin: 0; background: #f8f9fa;">📦 Package-Level Coverage</h3>
            {self.generate_package_table(package_breakdown, thresholds)}
        </div>

        <div class="footer">
            <p>Auto-generated by coverage dashboard generator</p>
        </div>
    </div>

    <script>
        // Trend Chart
        const trendCtx = document.getElementById('trendChart').getContext('2d');
        new Chart(trendCtx, {{
            type: 'line',
            data: {{
                labels: {json.dumps([d['date'] for d in trend_data])},
                datasets: [{{
                    label: 'Coverage %',
                    data: {json.dumps([d['y'] for d in trend_data])},
                    borderColor: 'rgb(75, 192, 192)',
                    tension: 0.1,
                    fill: false
                }}]
            }},
            options: {{
                responsive: true,
                scales: {{
                    y: {{
                        beginAtZero: false,
                        min: 70,
                        max: 100
                    }}
                }}
            }}
        }});

        // Distribution Chart
        const distributionCtx = document.getElementById('distributionChart').getContext('2d');
        const currentMetrics = JSON.parse('{current_metrics_json}');
        new Chart(distributionCtx, {{
            type: 'radar',
            data: {{
                labels: ['Instruction', 'Branch', 'Method', 'Class', 'Line'],
                datasets: [{{
                    label: 'Coverage %',
                    data: [
                        currentMetrics.instruction?.percentage || 0,
                        currentMetrics.branch?.percentage || 0,
                        currentMetrics.method?.percentage || 0,
                        currentMetrics.class?.percentage || 0,
                        currentMetrics.line?.percentage || 0
                    ],
                    borderColor: 'rgb(255, 99, 132)',
                    backgroundColor: 'rgba(255, 99, 132, 0.2)',
                }}]
            }},
            options: {{
                responsive: true,
                scales: {{
                    r: {{
                        beginAtZero: true,
                        max: 100
                    }}
                }}
            }}
        }});
    </script>
</body>
</html>"""

        return html

    def generate_metric_cards(self, current):
        """Generate HTML for metric cards."""
        if not current:
            return '<div class="metric-card"><div class="metric-value">N/A</div><div class="metric-label">Coverage Data Not Available</div></div>'

        percentage = float(current.get('percentage', '0').rstrip('%'))
        color_class = 'coverage-excellent' if percentage >= 80 else 'coverage-good' if percentage >= 70 else 'coverage-poor'

        cards = f'''
        <div class="metric-card">
            <div class="metric-value {color_class}">{current.get('percentage', 'N/A')}</div>
            <div class="metric-label">Overall Coverage</div>
        </div>
        '''

        # Add detailed metrics
        metrics = current.get('detailed_metrics', {})
        for metric_type, data in metrics.items():
            if isinstance(data, dict) and 'percentage' in data:
                pct = data['percentage']
                color_class = 'coverage-excellent' if pct >= 80 else 'coverage-good' if pct >= 70 else 'coverage-poor'
                cards += f'''
        <div class="metric-card">
            <div class="metric-value {color_class}">{pct:.1f}%</div>
            <div class="metric-label">{metric_type.title()} Coverage</div>
        </div>
                '''

        return cards

    def generate_package_table(self, package_breakdown, thresholds):
        """Generate HTML table for package breakdown."""
        if not package_breakdown:
            return '<p style="padding: 20px;">Package coverage data not available. Run detailed JaCoCo report first.</p>'

        # Get safety-critical packages from thresholds
        safety_critical = set()
        for category, config in thresholds.get('package_specific_thresholds', {}).items():
            packages = config.get('packages', [])
            if category == 'safety_critical':
                safety_critical.update(packages)

        table_rows = ''
        for package, metrics in sorted(package_breakdown.items()):
            instruction_cov = metrics.get('instruction', 0)
            is_safety_critical = any(package.startswith(p.replace('*', '')) for p in safety_critical)

            # Determine status
            status_icon = '✅' if instruction_cov >= 80 else '⚠️' if instruction_cov >= 70 else '❌'
            if is_safety_critical:
                status_icon = '🛡️' if instruction_cov >= 90 else '⚠️' if instruction_cov >= 80 else '🚨'

            table_rows += f'''
            <tr>
                <td><code>{package}</code></td>
                <td><span class="package-coverage">{instruction_cov:.1f}%</span></td>
                <td>{metrics.get('branch', 0):.1f}%</td>
                <td>{metrics.get('method', 0):.1f}%</td>
                <td>{metrics.get('class', 0):.1f}%</td>
                <td>{status_icon} {'Safety Critical' if is_safety_critical else 'Standard'}</td>
            </tr>
            '''

        return f'''
        <table>
            <thead>
                <tr>
                    <th>Package</th>
                    <th>Instruction</th>
                    <th>Branch</th>
                    <th>Method</th>
                    <th>Class</th>
                    <th>Status</th>
                </tr>
            </thead>
            <tbody>
                {table_rows}
            </tbody>
        </table>
        '''

    def save_dashboard(self):
        """Generate and save dashboard."""
        self.dashboard_dir.mkdir(exist_ok=True)

        data = self.load_data()
        html_content = self.generate_html_dashboard(data)

        dashboard_file = self.dashboard_dir / "index.html"
        try:
            with open(dashboard_file, 'w', encoding='utf-8') as f:
                f.write(html_content)
            print(f"✅ Dashboard generated: {dashboard_file}")
            return True
        except Exception as e:
            print(f"Error saving dashboard: {e}")
            return False

    def run(self):
        """Main execution method."""
        print("📊 Generating coverage dashboard...")
        success = self.save_dashboard()
        if success:
            print(f"🌐 Open dashboard at: file://{self.dashboard_dir}/index.html")
        return success

def main():
    parser = argparse.ArgumentParser(description="Generate coverage dashboard for Tern Android app")
    parser.add_argument("--project-root", help="Project root directory")
    parser.add_argument("--output-dir", help="Output directory for dashboard")

    args = parser.parse_args()

    generator = CoverageDashboardGenerator(args.project_root)

    if args.output_dir:
        generator.dashboard_dir = Path(args.output_dir)

    success = generator.run()
    exit(0 if success else 1)

if __name__ == "__main__":
    main()