package com.madanala.tern.utils

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

object LocalReportGenerator {

    private val steps = ConcurrentLinkedQueue<Step>()
    private val outputDir = File("build/outputs/unit_test_bdd_report")

    init {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
    }

    data class Step(val type: String, val description: String, val status: String = "PASS")

    fun logStep(type: String, description: String, status: String = "PASS") {
        steps.add(Step(type, description, status))
    }

    fun generateReport(testName: String) {
        val reportFilename = "report_${testName}.html"
        val reportFile = File(outputDir, reportFilename)
        
        reportFile.bufferedWriter().use { writer ->
            writer.write("<html><head><style>")
            writer.write("body { font-family: sans-serif; padding: 20px; }")
            writer.write(".step { margin-bottom: 10px; padding: 10px; border-left: 4px solid #ccc; }")
            writer.write(".PASS { border-left-color: green; }")
            writer.write(".FAIL { border-left-color: red; }")
            writer.write("</style></head><body>")
            
            writer.write("<h1>Test Report: $testName</h1>")
            
            steps.forEach { step ->
                writer.write("<div class='step ${step.status}'>")
                writer.write("<strong>${step.type}</strong>: ${step.description}")
                writer.write("</div>")
            }
            
            writer.write("</body></html>")
        }
        
        // Clear steps for next test
        steps.clear()
    }
}
