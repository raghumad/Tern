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

    fun generateReport(testName: String) {
        val reportFilename = "report_${testName}.html"
        
        testStorage.openOutputFile(reportFilename).use { out ->
            val writer = BufferedWriter(OutputStreamWriter(out))
            writer.write("<html><head><style>")
            writer.write("body { font-family: sans-serif; padding: 20px; }")
            writer.write(".step { margin-bottom: 10px; padding: 10px; border-left: 4px solid #ccc; }")
            writer.write(".PASS { border-left-color: green; }")
            writer.write(".FAIL { border-left-color: red; }")
            writer.write("img { max-width: 100%; margin-top: 10px; border: 1px solid #ddd; }")
            writer.write("</style></head><body>")
            
            writer.write("<h1>Test Report: $testName</h1>")
            
            steps.forEach { step ->
                writer.write("<div class='step ${step.status}'>")
                writer.write("<strong>${step.type}</strong>: ${step.description}")
                if (step.screenshotPath != null) {
                    writer.write("<br/><img src='${step.screenshotPath}' />")
                }
                writer.write("</div>")
            }
            
            writer.write("</body></html>")
            writer.flush()
        }
        
        // Clear steps for next test
        steps.clear()
    }
}
