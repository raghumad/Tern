package com.madanala.tern.utils

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentLinkedQueue
import android.util.Log

object ReportGenerator {

    private val testStorage = TestStorage()
    private val currentSteps = ConcurrentLinkedQueue<Step>()
    private val recordedScenarios = ConcurrentLinkedQueue<ScenarioData>()
    private val screenshots = ConcurrentLinkedQueue<String>()
    
    var currentTestName: String? = null
    var currentTestClass: String? = null

    data class Step(val type: String, val description: String, val status: String = "PASS", val screenshotPath: String? = null, val screenshotHash: String? = null)
    data class ScenarioData(val name: String, val steps: List<Step>, val logcat: String?, val perfMetrics: PerformanceMetrics? = null)
    
    data class PerformanceMetrics(
        val totalGcPauseMs: Long,
        val peakHeapUsedMb: Double,
        val baselineHeapUsedMb: Double,
        val finalHeapUsedMb: Double,
        val gcEventCount: Int
    )
    
    // Metadata for the dashboard
    data class TestSummary(
        val className: String,
        val testName: String,
        val scenarioName: String?,
        val story: String?,
        val status: String,
        val reportFile: String,
        val thumbnail: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun logStep(type: String, description: String, status: String = "PASS", screenshotPath: String? = null, screenshotHash: String? = null) {
        currentSteps.add(Step(type, description, status, screenshotPath, screenshotHash))
    }

    data class ScreenshotResult(val path: String, val hash: String)

    fun captureScreenshot(name: String): ScreenshotResult? {
        // Sanitize filename: replace non-alphanumeric characters (except underscores) with underscores
        val sanitizedName = name.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val filename = "${sanitizedName}_${System.currentTimeMillis()}.png"
        try {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bitmap != null) {
                val currentHash = VisualValidator.calculateHash(bitmap)
                
                if (VisualValidator.isBlank(bitmap)) {
                    Log.w("ReportGenerator", "Screenshot '$name' (hash: $currentHash) is BLANK! Validation failing.")
                    throw AssertionError("Visual Validation Failed: Screenshot '$name' is blank/unrendered.")
                }

                val testNameForBlacklist = currentTestName
                if (testNameForBlacklist != null && VisualValidator.isBlacklisted(testNameForBlacklist, bitmap)) {
                    Log.w("ReportGenerator", "Screenshot '$name' (hash: $currentHash) matches a BLACKLISTED bad state! Validation failing.")
                    throw AssertionError("Visual Validation Failed: Screenshot '$name' matches a known-bad state for test '$testNameForBlacklist'.")
                }
                
                testStorage.openOutputFile(filename).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                screenshots.add(filename)
                return ScreenshotResult(filename, currentHash)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun captureLogCat(): String {
        return try {
            val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val result = device.executeShellCommand("logcat -d -v threadtime")
            
            // Filter by current test name if set
            var filteredResult = result
            currentTestName?.let { name ->
                val startTag = "=== START $name ==="
                val startIndex = filteredResult.lastIndexOf(startTag)
                if (startIndex != -1) {
                    filteredResult = filteredResult.substring(startIndex)
                }
            }
            
            if (filteredResult.isEmpty()) {
                "WARNING: Logcat was empty."
            } else {
                filteredResult
            }
        } catch (e: Exception) {
            "Failed to capture logcat: ${e.message}"
        }
    }

    fun assertLogContains(tag: String, messageFragment: String) {
        val log = captureLogCat()
        val exists = log.lineSequence().any { it.contains(tag) && it.contains(messageFragment) }
        if (!exists) {
            throw AssertionError("Logcat did not contain expected message. Tag: $tag, Fragment: $messageFragment")
        }
    }

    fun assertLogDoesNotContain(tag: String, messageFragment: String) {
        val log = captureLogCat()
        val exists = log.lineSequence().any { it.contains(tag) && it.contains(messageFragment) }
        if (exists) {
            throw AssertionError("Logcat contained unexpected message. Tag: $tag, Fragment: $messageFragment")
        }
    }



    fun assertLogMatchesRegex(tag: String, regexPattern: String, validator: (MatchResult) -> Boolean) {
        val log = captureLogCat()
        println("DEBUG: ReportGenerator assertLogMatchesRegex called. Log length: ${log.length}")
        
        val regex = Regex(regexPattern)
        val match = log.lineSequence()
            .filter { it.contains(tag) }
            .mapNotNull { regex.find(it) }
            .lastOrNull()

        if (match == null) {
            val filteredLog = log.lines().filter { 
                it.contains("PGSpot") || 
                it.contains("MockServer") || 
                it.contains("System.out") || 
                it.contains("MapViewModel") ||
                it.contains("OverlayManager") ||
                it.contains("UniversalCountryCache") ||
                it.contains("AirspaceCache") ||
                it.contains("GeoJsonUtils") ||
                it.contains("MapOverlayCacheUtils") ||
                it.contains("BddTest")
            }.takeLast(100).joinToString("\n")
            println("DEBUG: Assertion Failed. Tail:\n$filteredLog")
            throw AssertionError("XXX FAILURE XXX: Logcat did not contain message matching regex. Tag: $tag, Pattern: $regexPattern. Log Length: ${log.length}\n\nFiltered Logcat Tail:\n$filteredLog")
        }

        if (!validator(match)) {
            val filteredLog = log.lines().filter { 
                it.contains("PGSpot") || 
                it.contains("MockServer") || 
                it.contains("System.out") || 
                it.contains("MapViewModel") ||
                it.contains("OverlayManager") ||
                it.contains("UniversalCountryCache") ||
                it.contains("AirspaceCache") ||
                it.contains("GeoJsonUtils") ||
                it.contains("MapOverlayCacheUtils") ||
                it.contains("BddTest")
            }.takeLast(100).joinToString("\n")
            println("DEBUG: Assertion Failed (Validation). Tail:\n$filteredLog")
            throw AssertionError("XXX FAILURE XXX: Log message matched pattern but failed validation. Last Match: ${match.value}\n\nFiltered Logcat Tail:\n$filteredLog")
        }
    }

    fun waitForLog(tag: String, messageFragment: String, timeoutMillis: Long = 10000) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            val log = captureLogCat()
            if (log.lineSequence().any { it.contains(tag) && it.contains(messageFragment) }) {
                return
            }
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        val finalLog = captureLogCat()
        val tail = finalLog.lines().takeLast(20).joinToString("\n")
        throw AssertionError("Timed out waiting for log message. Tag: $tag, Fragment: $messageFragment. \nLast 20 lines of Logcat:\n$tail")
    }

    /**
     * Waits for a log message that matches the given regex AND satisfies the validator.
     */
    fun waitForLogMatching(tag: String, regexPattern: String, timeoutMillis: Long = 20000, validator: (MatchResult) -> Boolean) {
        val startTime = System.currentTimeMillis()
        val regex = Regex(regexPattern)
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            val log = captureLogCat()
            val match = log.lineSequence()
                .filter { it.contains(tag) }
                .mapNotNull { regex.find(it) }
                .lastOrNull()

            if (match != null && validator(match)) {
                return
            }
            
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        
        val log = captureLogCat()
        val filteredLog = log.lines().filter { 
            it.contains("OverlayManager") || it.contains("Airspace")
        }.takeLast(20).joinToString("\n")
        
        throw AssertionError("Timed out waiting for log matching regex: $regexPattern in tag: $tag. \nLast matching logs:\n$filteredLog")
    }

    /**
     * Analyze logcat for performance metrics
     */
    private fun analyzePerformanceFromLog(logcat: String): PerformanceMetrics {
        var totalGcPauseMs = 0L
        var gcEventCount = 0
        var peakHeapUsed = 0.0
        var baselineHeapUsed = 0.0
        var finalHeapUsed = 0.0
        
        // Parse ART GC events: "Explicit concurrent mark sweep GC freed 240KB ... paused 1.052ms"
        val gcRegex = Regex("paused ([\\d.]+)ms")
        logcat.lineSequence().forEach { line ->
            if (line.contains("GC freed")) {
                gcRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { pause ->
                    totalGcPauseMs += pause.toLong()
                    gcEventCount++
                }
            }
            
            // Parse [PERF_HEAP] SNAPSHOT used=12345 total=23456 max=34567
            if (line.contains("[PERF_HEAP]")) {
                val usedMatch = Regex("used=(\\d+)").find(line)
                usedMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let { usedBytes ->
                    val usedMb = usedBytes / (1024.0 * 1024.0)
                    if (baselineHeapUsed == 0.0) baselineHeapUsed = usedMb
                    if (usedMb > peakHeapUsed) peakHeapUsed = usedMb
                    finalHeapUsed = usedMb
                }
            }
        }
        
        return PerformanceMetrics(
            totalGcPauseMs = totalGcPauseMs,
            peakHeapUsedMb = peakHeapUsed,
            baselineHeapUsedMb = baselineHeapUsed,
            finalHeapUsedMb = finalHeapUsed,
            gcEventCount = gcEventCount
        )
    }

    fun finishScenario(name: String, logCatOutput: String?) {
        val perfMetrics = logCatOutput?.let { analyzePerformanceFromLog(it) }
        recordedScenarios.add(ScenarioData(name, currentSteps.toList(), logCatOutput, perfMetrics))
        currentSteps.clear()
    }

    fun generateFinalReport(className: String, testName: String) {
        val reportFilename = "report_${className}_${testName}.html"
        val summaryFilename = "summary_${className}_${testName}.json"
        
        var finalStatus = "PASS"
        var scenarioName: String? = null
        var storyHighlight: String? = null
        var firstScreenshot: String? = null

        testStorage.openOutputFile(reportFilename).use { out ->
            val writer = BufferedWriter(OutputStreamWriter(out))
            writer.write("<html><head><style>")
            writer.write("body { font-family: 'Inter', -apple-system, sans-serif; padding: 40px; background-color: #0f172a; color: #f8fafc; line-height: 1.6; }")
            writer.write(".scenario { margin-bottom: 40px; background: #1e293b; border-radius: 12px; padding: 24px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1); border: 1px solid #334155; }")
            writer.write("h1 { color: #38bdf8; font-weight: 700; margin-bottom: 32px; border-bottom: 1px solid #334155; padding-bottom: 16px; font-size: 1.8rem; }")
            writer.write("h2 { font-size: 1.25rem; font-weight: 600; margin-bottom: 20px; display: flex; align-items: center; gap: 8px; }")
            writer.write(".step { margin-bottom: 16px; padding: 16px; border-radius: 8px; background: #0f172a; border-left: 4px solid #475569; position: relative; }")
            writer.write(".PASS { border-left-color: #22c55e; }")
            writer.write(".FAIL { border-left-color: #ef4444; }")
            writer.write("strong { color: #94a3b8; text-transform: uppercase; font-size: 0.75rem; letter-spacing: 0.05em; margin-right: 8px; }")
            writer.write("img { max-width: 100%; margin: 16px 0; border: 1px solid #334155; border-radius: 8px; display: block; }")
            writer.write("details { margin-top: 24px; border: 1px solid #334155; padding: 16px; border-radius: 8px; background-color: #0f172a; }")
            writer.write("summary { cursor: pointer; font-weight: 600; margin-bottom: 12px; color: #38bdf8; font-size: 0.9rem; }")
            writer.write("pre { white-space: pre-wrap; word-wrap: break-word; font-family: 'JetBrains Mono', monospace; font-size: 12px; max-height: 400px; overflow-y: auto; color: #cbd5e1; padding: 12px; background: #020617; border-radius: 6px; border: 1px solid #1e293b; }")
            writer.write(".approve-btn { background-color: #22c55e; color: white; border: none; padding: 8px 16px; border-radius: 6px; cursor: pointer; font-weight: 600; font-size: 0.85rem; margin-right: 12px; transition: background 0.2s; }")
            writer.write(".approve-btn:hover { background-color: #16a34a; }")
            writer.write(".reject-btn { background-color: #ef4444; color: white; border: none; padding: 8px 16px; border-radius: 6px; cursor: pointer; font-weight: 600; font-size: 0.85rem; transition: background 0.2s; }")
            writer.write(".reject-btn:hover { background-color: #dc2626; }")
            writer.write("</style>")
            writer.write("<script>")
            writer.write("async function approve(filename, testName, btn) {")
            writer.write("  const originalText = btn.innerText;")
            writer.write("  btn.innerText = '⌛ Processing...';")
            writer.write("  btn.disabled = true;")
            writer.write("  try {")
            writer.write("    const response = await fetch('http://localhost:8080/approve', {")
            writer.write("      method: 'POST',")
            writer.write("      headers: { 'Content-Type': 'application/json' },")
            writer.write("      body: JSON.stringify({ filename, test_name: testName })")
            writer.write("    });")
            writer.write("    if (response.ok) {")
            writer.write("      btn.innerText = '✅ Approved';")
            writer.write("      btn.style.backgroundColor = '#2E7D32';")
            writer.write("      const rejectBtn = btn.parentElement.querySelector('.reject-btn');")
            writer.write("      if (rejectBtn) rejectBtn.style.display = 'none';") // Hide reject button safely
            writer.write("    } else {")
            writer.write("      alert('Failed to approve. Is visual_reviewer.py running?');")
            writer.write("      btn.innerText = originalText;")
            writer.write("      btn.disabled = false;")
            writer.write("    }")
            writer.write("  } catch (e) {")
            writer.write("    alert('Error: ' + e.message + '. Ensure visual_reviewer.py is running on port 8080');")
            writer.write("    btn.innerText = originalText;")
            writer.write("    btn.disabled = false;")
            writer.write("  }")
            writer.write("}")
            writer.write("async function reject(filename, testName, hash, btn) {")
            writer.write("  if (!confirm('Mark this image as WRONG? It will be blacklisted.')) return;")
            writer.write("  const originalText = btn.innerText;")
            writer.write("  btn.innerText = '⌛ Processing...';")
            writer.write("  btn.disabled = true;")
            writer.write("  try {")
            writer.write("    const response = await fetch('http://localhost:8080/reject', {")
            writer.write("      method: 'POST',")
            writer.write("      headers: { 'Content-Type': 'application/json' },")
            writer.write("      body: JSON.stringify({ filename, test_name: testName, hash: hash })")
            writer.write("    });")
            writer.write("    if (response.ok) {")
            writer.write("      btn.innerText = '❌ Rejected & Blacklisted';")
            writer.write("      btn.style.backgroundColor = '#C62828';")
            writer.write("      const approveBtn = btn.parentElement.querySelector('.approve-btn');")
            writer.write("      if (approveBtn) approveBtn.style.display = 'none';") // Hide approve button safely
            writer.write("    } else {")
            writer.write("      alert('Failed to reject. Is visual_reviewer.py running?');")
            writer.write("      btn.innerText = originalText;")
            writer.write("      btn.disabled = false;")
            writer.write("    }")
            writer.write("  } catch (e) {")
            writer.write("    alert('Error: ' + e.message + '. Ensure visual_reviewer.py is running on port 8080');")
            writer.write("    btn.innerText = originalText;")
            writer.write("    btn.disabled = false;")
            writer.write("  }")
            writer.write("}")
            writer.write("</script>")
            writer.write("</head><body>")
            
            writer.write("<h1>Test Report: $testName</h1>")
            
            if (recordedScenarios.isEmpty() && currentSteps.isNotEmpty()) {
                // Fallback if finishScenario wasn't called (e.g. non-scenario test)
                finishScenario("Default Scenario", captureLogCat())
            }

            var firstScenarioMetrics: PerformanceMetrics? = null

            recordedScenarios.forEachIndexed { index, scenario ->
                if (index == 0) firstScenarioMetrics = scenario.perfMetrics
                
                writer.write("<div class='scenario'>")
                writer.write("<h2>Scenario: ${scenario.name} ${if (index == 0) "(Baseline)" else ""}</h2>")
                
                // Extract and print the story first if it exists
                val storyStep = scenario.steps.find { it.type == "STORY" }
                if (storyStep != null) {
                    writer.write("<div style='margin-bottom: 20px; padding: 15px; background-color: #e3f2fd; border-radius: 5px; border-left: 5px solid #1976d2;'>")
                    writer.write("<strong>Story:</strong> ${storyStep.description}")
                    writer.write("</div>")
                }
                
                // Print the rest of the steps
                scenario.steps.filter { it.type != "STORY" }.forEach { step ->
                    writer.write("<div class='step ${step.status}'>")
                    writer.write("<strong>${step.type}</strong>: ${step.description}")
                    if (step.screenshotPath != null) {
                        writer.write("<br/><details open><summary>Screenshot</summary>")
                        writer.write("<img src='${step.screenshotPath}' /><br/>")
                        writer.write("<button class='approve-btn' onclick=\"approve('${step.screenshotPath}', '${testName}', this)\">✅ Approve as Golden</button>")
                        writer.write("<button class='reject-btn' onclick=\"reject('${step.screenshotPath}', '${testName}', '${step.screenshotHash}', this)\">❌ Wrong / Reject</button>")
                        writer.write("</details>")
                    }
                    writer.write("</div>")
                }

                if (scenario.perfMetrics != null) {
                    val budget = PerformanceDebugger.DEFAULT_BUDGET
                    val leak = scenario.perfMetrics.finalHeapUsedMb - scenario.perfMetrics.baselineHeapUsedMb
                    
                    // Helper to get color based on value vs budget
                    fun getColor(value: Double, budget: Double): String = when {
                        value > budget -> "#ef4444" // Over budget (Red)
                        value > budget * 0.8 -> "#f59e0b" // Near budget (Yellow)
                        else -> "#22c55e" // Within budget (Green)
                    }

                    writer.write("<div style='margin-bottom: 20px; padding: 15px; background: #0f172a; border-radius: 8px; border: 1px solid #334155;'>")
                    writer.write("<h3 style='color: #22c55e; margin-top: 0; font-size: 1rem;'>⚡ Performance Scorecard</h3>")
                    writer.write("<div style='display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 16px;'>")
                    
                    val gcColor = getColor(scenario.perfMetrics.totalGcPauseMs.toDouble(), budget.maxGcPauseMs.toDouble())
                    writer.write("<div><strong style='display: block;'>Total GC Pause</strong><span style='color: $gcColor;'>${scenario.perfMetrics.totalGcPauseMs} ms</span> <small>(Budget: ${budget.maxGcPauseMs}ms)</small></div>")
                    
                    val eventColor = getColor(scenario.perfMetrics.gcEventCount.toDouble(), budget.maxGcEventCount.toDouble())
                    writer.write("<div><strong style='display: block;'>GC Events</strong><span style='color: $eventColor;'>${scenario.perfMetrics.gcEventCount}</span> <small>(Budget: ${budget.maxGcEventCount})</small></div>")
                    
                    val peakColor = getColor(scenario.perfMetrics.peakHeapUsedMb, budget.maxPeakHeapMb)
                    writer.write("<div><strong style='display: block;'>Peak Heap</strong><span style='color: $peakColor;'>${String.format("%.2f", scenario.perfMetrics.peakHeapUsedMb)} MB</span> <small>(SLA: ${budget.maxPeakHeapMb}MB)</small></div>")
                    
                    val leakColor = getColor(leak, budget.maxRetainedDeltaMb)
                    writer.write("<div><strong style='display: block;'>Retained Delta</strong><span style='color: $leakColor;'>${String.format("%+.2f", leak)} MB</span> <small>(Limit: ${budget.maxRetainedDeltaMb}MB)</small></div>")
                    
                    
                    writer.write("<div><strong style='display: block;'>Baseline/Final</strong>${String.format("%.1f", scenario.perfMetrics.baselineHeapUsedMb)} / ${String.format("%.1f", scenario.perfMetrics.finalHeapUsedMb)} MB</div>")
                    
                    // Comparison against the first scenario (baseline) if this is not the baseline
                    if (index > 0 && firstScenarioMetrics != null) {
                        val peakDelta = scenario.perfMetrics.peakHeapUsedMb - firstScenarioMetrics!!.peakHeapUsedMb
                        val pauseDelta = scenario.perfMetrics.totalGcPauseMs - firstScenarioMetrics!!.totalGcPauseMs
                        
                        writer.write("<div style='grid-column: 1 / -1; margin-top: 8px; border-top: 1px dashed #334155; padding-top: 8px; font-size: 0.85rem; color: #94a3b8;'>")
                        writer.write("<strong>Overhead vs Baseline:</strong> ")
                        writer.write("Peak Heap: <span style='color: ${if (peakDelta > 5.0) "#ef4444" else "#94a3b8"};'>${String.format("%+.1f", peakDelta)} MB</span> | ")
                        writer.write("GC Pause: <span style='color: ${if (pauseDelta > 50) "#ef4444" else "#94a3b8"};'>${if (pauseDelta >= 0) "+" else ""}$pauseDelta ms</span>")
                        writer.write("</div>")
                    }
                    
                    writer.write("</div></div>")
                }

                if (scenario.logcat != null) {
                    writer.write("<details>")
                    writer.write("<summary>LogCat Output</summary>")
                    writer.write("<pre>${scenario.logcat.replace("<", "&lt;").replace(">", "&gt;")}</pre>")
                    writer.write("</details>")
                }
                writer.write("</div>") // End scenario div
            }
            
            writer.write("</body></html>")
            writer.flush()
        }

        // Generate JSON Summary for the dashboard
        try {
            recordedScenarios.firstOrNull()?.let { scenario ->
                scenarioName = scenario.name
                storyHighlight = scenario.steps.find { it.type == "STORY" }?.description
                firstScreenshot = scenario.steps.find { it.screenshotPath != null }?.screenshotPath
                if (scenario.steps.any { it.status == "FAIL" }) {
                    finalStatus = "FAIL"
                }
            }

            testStorage.openOutputFile(summaryFilename).use { out ->
                val writer = BufferedWriter(OutputStreamWriter(out))
                writer.write("{")
                writer.write("\"className\": \"$className\",")
                writer.write("\"testName\": \"$testName\",")
                writer.write("\"scenarioName\": \"${scenarioName?.replace("\"", "\\\"")}\",")
                writer.write("\"story\": \"${storyHighlight?.replace("\"", "\\\"")}\",")
                writer.write("\"status\": \"$finalStatus\",")
                writer.write("\"reportFile\": \"$reportFilename\",")
                writer.write("\"thumbnail\": \"$firstScreenshot\",")
                writer.write("\"timestamp\": ${System.currentTimeMillis()}")
                writer.write("}")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e("ReportGenerator", "Failed to write JSON summary: ${e.message}")
        }
        
        // Clear for next test
        recordedScenarios.clear()
        currentSteps.clear()
    }
}
