#!/bin/bash

echo "🚀 Starting Safe Test Execution..."
echo "=================================="

# 1. Run Stable Tests (Exclude @Unstable)
echo "✅ Running Stable Tests (Skipping @Unstable)..."
./gradlew pixel9proapi35DebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.madanala.tern.utils.Unstable \
    --continue

STABLE_EXIT_CODE=$?

if [ $STABLE_EXIT_CODE -ne 0 ]; then
    echo "⚠️ Stable tests failed! Proceeding to unstable tests anyway..."
fi

# 2. Run Unstable Tests (Only @Unstable)
echo "⚠️ Running Unstable/Flaky Tests (@Unstable)..."
./gradlew pixel9proapi35DebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.annotation=com.madanala.tern.utils.Unstable \
    --continue

UNSTABLE_EXIT_CODE=$?

# 3. Generate Combined Report
echo "📊 Generating Combined Test Report..."
./gradlew generateTestReport

echo "=================================="
echo "🏁 Test Execution Completed."
echo "Stable Tests Exit Code: $STABLE_EXIT_CODE"
echo "Unstable Tests Exit Code: $UNSTABLE_EXIT_CODE"

if [ $STABLE_EXIT_CODE -eq 0 ] && [ $UNSTABLE_EXIT_CODE -eq 0 ]; then
    echo "✅ ALL TESTS PASSED"
    exit 0
else
    echo "❌ SOME TESTS FAILED"
    exit 1
fi
