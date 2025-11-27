package com.madanala.tern.utils

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentLinkedQueue

object ReportGenerator {

    private val testStorage = TestStorage()
    private val steps = ConcurrentLinkedQueue<Step>()
    private val screenshots = ConcurrentLinkedQueue<String>()

    data class Step(val type: String, val description: String, val status: String = "PASS", val screenshotPath: String? = null)

    fun logStep(type: String, description: String, status: String = "PASS", screenshotPath: String? = null) {
        steps.add(Step(type, description, status, screenshotPath))
    }

    fun captureScreenshot(name: String): String? {
        val filename = "${name}_${System.currentTimeMillis()}.png"
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
            val process = Runtime.getRuntime().exec("logcat -d")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val log = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                log.append(line).append("\n")
            }
            return log.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return "Failed to capture logcat: ${e.message}"
        }
    }

    fun generateReport(testName: String, logCatOutput: String? = null) {
        val reportFilename = "report_${testName}.html"
        
        testStorage.openOutputFile(reportFilename).use { out ->
            val writer = BufferedWriter(OutputStreamWriter(out))
            writer.write("<html><head><style>")
            writer.write("body { font-family: sans-serif; padding: 20px; }")
            writer.write(".step { margin-bottom: 10px; padding: 10px; border-left: 4px solid #ccc; }")
            writer.write(".PASS { border-left-color: green; }")
            writer.write(".FAIL { border-left-color: red; }")
            writer.write("img { max-width: 100%; margin-top: 10px; border: 1px solid #ddd; }")
            writer.write("details { margin-top: 20px; border: 1px solid #ccc; padding: 10px; border-radius: 5px; background-color: #f9f9f9; }")
            writer.write("summary { cursor: pointer; font-weight: bold; margin-bottom: 10px; }")
            writer.write("pre { white-space: pre-wrap; word-wrap: break-word; font-family: monospace; font-size: 12px; max-height: 500px; overflow-y: auto; }")
            writer.write("</style></head><body>")
            
            writer.write("<h1>Test Report: $testName</h1>")
            
            steps.forEach { step ->
                writer.write("<div class='step ${step.status}'>")
                writer.write("<strong>${step.type}</strong>: ${step.description}")
                if (step.screenshotPath != null) {
                    writer.write("<br/><details><summary>Screenshot</summary><img src='${step.screenshotPath}' /></details>")
                }
                writer.write("</div>")
            }

            if (logCatOutput != null) {
                writer.write("<details>")
                writer.write("<summary>LogCat Output</summary>")
                writer.write("<pre>${logCatOutput.replace("<", "&lt;").replace(">", "&gt;")}</pre>")
                writer.write("</details>")
            }
            
            writer.write("</body></html>")
            writer.flush()
        }
        
        // Clear steps for next test
        steps.clear()
    }
}
