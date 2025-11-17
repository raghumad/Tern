#!/usr/bin/env python3
"""
Coverage Trend Analysis for Tern Android App

This script analyzes coverage trends over time, provides insights,
and generates trend reports for continuous improvement.
"""

import os
import json
import csv
from pathlib import Path
from datetime import datetime, timedelta
import argparse
import statistics

class CoverageTrendAnalyzer:
    def __init__(self, project_root=None):
        self.project_root = Path(project_root) if project_root else Path(__file__).parent.parent
        self.history_file = self.project_root / ".coverage_history.json"
        self.trend_report_file = self.project_root / "coverage-trend-report.md"
        self.analytics_file = self.project_root / "coverage-analytics.json"

    def load_history(self):
        """Load coverage history from file."""
        if self.history_file.exists():
            try:
                with open(self.history_file, 'r') as f:
                    return json.load(f)
            except Exception as e:
                print(f"Error loading history: {e}")
        return []

    def save_history(self, history):
        """Save coverage history to file."""
        try:
            with open(self.history_file, 'w') as f:
                json.dump(history, f, indent=2, default=str)
        except Exception as e:
            print(f"Error saving history: {e}")

    def get_current_coverage(self):
        """Get current coverage data from badge file."""
        badge_file = self.project_root / "coverage-badge.json"
        if badge_file.exists():
            try:
                with open(badge_file, 'r') as f:
                    data = json.load(f)
                    percentage = float(data['percentage'].rstrip('%'))
                    return {
                        'timestamp': datetime.now().isoformat(),
                        'percentage': percentage,
                        'detailed_metrics': data.get('detailed_metrics', {})
                    }
            except Exception as e:
                print(f"Error reading current coverage: {e}")
        return None

    def update_history(self):
        """Update coverage history with current data."""
        history = self.load_history()
        current = self.get_current_coverage()

        if current:
            # Remove entries older than 90 days
            cutoff_date = datetime.now() - timedelta(days=90)
            history = [entry for entry in history
                      if datetime.fromisoformat(entry['timestamp']) > cutoff_date]

            # Add current entry
            history.append(current)

            # Keep only last 100 entries to prevent file bloat
            if len(history) > 100:
                history = history[-100:]

            self.save_history(history)
            print(f"✅ Coverage history updated ({len(history)} entries)")
            return True
        else:
            print("❌ Could not get current coverage data")
            return False

    def analyze_trends(self, days=30):
        """Analyze coverage trends over specified period."""
        history = self.load_history()
        if not history:
            return None

        cutoff_date = datetime.now() - timedelta(days=days)
        recent_history = [entry for entry in history
                         if datetime.fromisoformat(entry['timestamp']) > cutoff_date]

        if len(recent_history) < 2:
            return None

        percentages = [entry['percentage'] for entry in recent_history]

        analysis = {
            'period_days': days,
            'data_points': len(recent_history),
            'current_coverage': percentages[-1],
            'start_coverage': percentages[0],
            'change': percentages[-1] - percentages[0],
            'trend': 'stable',
            'volatility': statistics.stdev(percentages) if len(percentages) > 1 else 0,
            'min_coverage': min(percentages),
            'max_coverage': max(percentages),
            'average_coverage': statistics.mean(percentages),
            'median_coverage': statistics.median(percentages)
        }

        # Determine trend
        if analysis['change'] > 1.0:
            analysis['trend'] = 'improving'
        elif analysis['change'] < -1.0:
            analysis['trend'] = 'declining'
        else:
            analysis['trend'] = 'stable'

        # Add recommendations
        analysis['recommendations'] = self.generate_recommendations(analysis)

        return analysis

    def generate_recommendations(self, analysis):
        """Generate recommendations based on trend analysis."""
        recommendations = []

        if analysis['trend'] == 'declining':
            recommendations.append("🚨 Coverage is declining - investigate recent changes")
            recommendations.append("📊 Review recent commits for test coverage gaps")

        if analysis['volatility'] > 2.0:
            recommendations.append("⚠️ High coverage volatility detected")
            recommendations.append("📈 Consider stabilizing test execution")

        if analysis['current_coverage'] < 75.0:
            recommendations.append("🎯 Coverage below target (75%) - prioritize test coverage")
        elif analysis['current_coverage'] >= 80.0:
            recommendations.append("✅ Excellent coverage maintained")

        if analysis['data_points'] < 7:
            recommendations.append("📊 Need more data points for reliable trend analysis")

        return recommendations

    def generate_trend_report(self):
        """Generate markdown trend report."""
        analysis_30d = self.analyze_trends(30)
        analysis_7d = self.analyze_trends(7)

        report = f"""# Coverage Trend Analysis Report

**Generated**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

## 📊 Current Status

"""

        if analysis_30d:
            trend_30d = analysis_30d['trend'].capitalize()
            change_30d = f"{analysis_30d['change']:+.1f}%"

            report += f"""- **Current Coverage**: {analysis_30d['current_coverage']:.1f}%
- **30-Day Trend**: {trend_30d} ({change_30d})
- **Coverage Range**: {analysis_30d['min_coverage']:.1f}% - {analysis_30d['max_coverage']:.1f}%
- **Average Coverage**: {analysis_30d['average_coverage']:.1f}%
- **Volatility**: {analysis_30d['volatility']:.2f}

"""

        report += "## 📈 Trend Analysis\n\n"

        if analysis_7d:
            report += f"""### Last 7 Days
- **Data Points**: {analysis_7d['data_points']}
- **Change**: {analysis_7d['change']:+.1f}%
- **Trend**: {analysis_7d['trend'].capitalize()}

"""

        if analysis_30d:
            report += f"""### Last 30 Days
- **Data Points**: {analysis_30d['data_points']}
- **Change**: {analysis_30d['change']:+.1f}%
- **Trend**: {analysis_30d['trend'].capitalize()}
- **Volatility**: {analysis_30d['volatility']:.2f}

"""

        # Recommendations
        recommendations = analysis_30d.get('recommendations', []) if analysis_30d else []
        if recommendations:
            report += "## 💡 Recommendations\n\n"
            for rec in recommendations:
                report += f"- {rec}\n"
            report += "\n"

        report += "---\n*Auto-generated by coverage trend analyzer*"

        return report

    def save_trend_report(self):
        """Save trend report to file."""
        try:
            report = self.generate_trend_report()
            with open(self.trend_report_file, 'w') as f:
                f.write(report)
            print(f"✅ Trend report saved to: {self.trend_report_file}")
            return True
        except Exception as e:
            print(f"Error saving trend report: {e}")
            return False

    def export_csv(self, output_file=None):
        """Export coverage history to CSV."""
        if not output_file:
            output_file = self.project_root / "coverage-history.csv"

        history = self.load_history()
        if not history:
            print("No history data to export")
            return False

        try:
            with open(output_file, 'w', newline='') as csvfile:
                fieldnames = ['timestamp', 'percentage', 'instruction', 'branch', 'method', 'class', 'line']
                writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
                writer.writeheader()

                for entry in history:
                    row = {
                        'timestamp': entry['timestamp'],
                        'percentage': entry['percentage']
                    }

                    # Add detailed metrics if available
                    metrics = entry.get('detailed_metrics', {})
                    for metric_type in ['INSTRUCTION', 'BRANCH', 'METHOD', 'CLASS', 'LINE']:
                        metric_data = metrics.get(metric_type.lower(), {})
                        row[metric_type.lower()] = metric_data.get('percentage', '')

                    writer.writerow(row)

            print(f"✅ Coverage history exported to: {output_file}")
            return True
        except Exception as e:
            print(f"Error exporting CSV: {e}")
            return False

    def run(self, update=True, report=True, csv_export=False):
        """Main execution method."""
        print("📊 Running coverage trend analysis...")

        if update:
            self.update_history()

        analysis = self.analyze_trends()
        if analysis:
            print(f"📈 Current coverage: {analysis['current_coverage']:.1f}%")
            print(f"📊 30-day trend: {analysis['change']:+.1f}% ({analysis['trend']})")

        if report:
            self.save_trend_report()

        if csv_export:
            self.export_csv()

        return True

def main():
    parser = argparse.ArgumentParser(description="Coverage trend analysis for Tern Android app")
    parser.add_argument("--project-root", help="Project root directory")
    parser.add_argument("--update", action="store_true", help="Update history with current data")
    parser.add_argument("--no-report", action="store_true", help="Skip generating trend report")
    parser.add_argument("--csv-export", action="store_true", help="Export history to CSV")
    parser.add_argument("--days", type=int, default=30, help="Analysis period in days")

    args = parser.parse_args()

    analyzer = CoverageTrendAnalyzer(args.project_root)

    update = args.update or not args.no_report  # Default to update unless explicitly skipped
    report = not args.no_report

    success = analyzer.run(update=update, report=report, csv_export=args.csv_export)
    exit(0 if success else 1)

if __name__ == "__main__":
    main()