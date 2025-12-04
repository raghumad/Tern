package com.madanala.tern.utils

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentLinkedQueue

object ReportGenerator {

    private val testStorage = TestStorage()
    private val currentSteps = ConcurrentLinkedQueue<Step>()
    private val recordedScenarios = ConcurrentLinkedQueue<ScenarioData>()
    private val screenshots = ConcurrentLinkedQueue<String>()

    data class Step(val type: String, val description: String, val status: String = "PASS", val screenshotPath: String? = null)
    data class ScenarioData(val name: String, val steps: List<Step>, val logcat: String?)

    fun logStep(type: String, description: String, status: String = "PASS", screenshotPath: String? = null) {
        currentSteps.add(Step(type, description, status, screenshotPath))
    }

    fun captureScreenshot(name: String): String? {
        // Sanitize filename: replace non-alphanumeric characters (except underscores) with underscores
        val sanitizedName = name.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val filename = "${sanitizedName}_${System.currentTimeMillis()}.png"
        try {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bitmap != null) {
                testStorage.openOutputFile(filename).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                screenshots.add(filename)
                return filename
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun captureLogCat(): String {
        try {
            val process = Runtime.getRuntime().exec("logcat -d -v threadtime")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val log = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                log.append(line).append("\n")
            }
            val result = log.toString()
            if (result.isEmpty()) {
                return "WARNING: Logcat was empty."
            }
            return result
        } catch (e: Exception) {
            e.printStackTrace()
            return "Failed to capture logcat: ${e.message}"
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
            .firstOrNull()

        if (match == null) {
            val tail = log.lines()
                .filter { it.contains("PGSpot") || it.contains("MockServer") || it.contains("System.out") || it.contains("MapViewModel") }
                .takeLast(100)
                .joinToString("\n")
            println("DEBUG: Assertion Failed. Tail:\n$tail")
            throw AssertionError("XXX FAILURE XXX: Logcat did not contain message matching regex. Tag: $tag, Pattern: $regexPattern. Log Length: ${log.length}\n\nFiltered Logcat Tail:\n$tail")
        }

        if (!validator(match)) {
            val tail = log.lines().takeLast(200).joinToString("\n")
            println("DEBUG: Validation Failed. Tail:\n$tail")
            throw AssertionError("XXX FAILURE XXX: Log message matched pattern but failed validation. Match: ${match.value}")
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

    fun finishScenario(name: String, logCatOutput: String?) {
        recordedScenarios.add(ScenarioData(name, currentSteps.toList(), logCatOutput))
        currentSteps.clear()
    }

    fun generateFinalReport(testName: String) {
        val reportFilename = "report_${testName}.html"
        
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
            writer.write("</style></head><body>")
            
            writer.write("<h1>Test Report: $testName</h1>")
            
            if (recordedScenarios.isEmpty() && currentSteps.isNotEmpty()) {
                // Fallback if finishScenario wasn't called (e.g. non-scenario test)
                finishScenario("Default Scenario", captureLogCat())
            }

            recordedScenarios.forEach { scenario ->
                writer.write("<div class='scenario'>")
                writer.write("<h2>Scenario: ${scenario.name}</h2>")
                
                scenario.steps.forEach { step ->
                    writer.write("<div class='step ${step.status}'>")
                    writer.write("<strong>${step.type}</strong>: ${step.description}")
                    if (step.screenshotPath != null) {
                        writer.write("<br/><details><summary>Screenshot</summary><img src='${step.screenshotPath}' /></details>")
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
        
        // Clear for next test
        recordedScenarios.clear()
        currentSteps.clear()
    }
}
