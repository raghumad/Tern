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
    data class ScenarioData(val name: String, val steps: List<Step>, val logcat: String?)
    
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

    fun finishScenario(name: String, logCatOutput: String?) {
        recordedScenarios.add(ScenarioData(name, currentSteps.toList(), logCatOutput))
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
            writer.write("body { font-family: sans-serif; padding: 20px; }")
            writer.write(".scenario { margin-bottom: 30px; border: 1px solid #ddd; padding: 15px; border-radius: 8px; }")
            writer.write(".step { margin-bottom: 10px; padding: 10px; border-left: 4px solid #ccc; }")
            writer.write(".PASS { border-left-color: green; }")
            writer.write(".FAIL { border-left-color: red; }")
            writer.write("img { max-width: 100%; margin-top: 10px; border: 1px solid #ddd; }")
            writer.write("details { margin-top: 20px; border: 1px solid #ccc; padding: 10px; border-radius: 5px; background-color: #f9f9f9; }")
            writer.write("summary { cursor: pointer; font-weight: bold; margin-bottom: 10px; }")
            writer.write("pre { white-space: pre-wrap; word-wrap: break-word; font-family: monospace; font-size: 12px; max-height: 500px; overflow-y: auto; }")
            writer.write(".approve-btn { background-color: #4CAF50; color: white; border: none; padding: 5px 10px; border-radius: 4px; cursor: pointer; margin: 5px; }")
            writer.write(".reject-btn { background-color: #f44336; color: white; border: none; padding: 5px 10px; border-radius: 4px; cursor: pointer; margin: 5px; }")
            writer.write("</style>")
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

            recordedScenarios.forEach { scenario ->
                writer.write("<div class='scenario'>")
                writer.write("<h2>Scenario: ${scenario.name}</h2>")
                
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
