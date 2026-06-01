package com.ternparagliding.utils

import android.util.Log
import androidx.test.services.storage.TestStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.io.FileInputStream

/**
 * Records the emulator/device screen to MP4 for BDD test evidence.
 *
 * Primary path: `screenrecord` via shell. If the device doesn't
 * support it (AOSP ATD images), falls back to [FrameCaptureHelper]
 * which takes screenshots at intervals.
 *
 * All output goes through [TestStorage] so Gradle's managed device
 * infrastructure collects it automatically.
 */
object VideoHelper {

    private const val TAG = "VideoHelper"
    private const val TEMP_DIR = "/sdcard/tern-tests"
    private const val MAX_DURATION_SECONDS = 180
    private const val SCREENRECORD_PROBE_MS = 1500L

    private const val SCREENRECORD_FINALIZE_MS = 1500L

    // screenrecord must run as the shell uid (screen-capture privilege),
    // so it's launched via UiAutomation.executeShellCommand — NOT
    // Runtime.exec from the app uid (which exits immediately, denied).
    private var recordingActive: Boolean = false
    private var recordingPfd: android.os.ParcelFileDescriptor? = null
    private var currentTestName: String? = null
    private var currentTempPath: String? = null
    private var segmentIndex: Int = 0
    private val testStorage = try { TestStorage() } catch (_: Exception) { null }

    private val uiAutomation
        get() = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().uiAutomation

    /** Run a shell command (as shell uid) and return its stdout. */
    private fun shellOutput(cmd: String): String = try {
        // AutoCloseInputStream takes ownership of the fd and closes it; do
        // NOT also wrap the pfd in .use (that double-closes → exception).
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            uiAutomation.executeShellCommand(cmd)
        ).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.w(TAG, "shell '$cmd' failed: ${e.message}"); ""
    }

    /** SIGINT screenrecord so it finalizes the mp4 (a hard kill corrupts it). */
    private fun stopScreenrecordShell() {
        runCatching { uiAutomation.executeShellCommand("pkill -INT screenrecord").close() }
        runCatching { recordingPfd?.close() }
        recordingPfd = null
    }

    // Fallback
    private var frameCaptureHelper: FrameCaptureHelper? = null
    private var fallbackScope: CoroutineScope? = null
    val isUsingFallback: Boolean get() = frameCaptureHelper != null

    fun startRecording(testName: String) {
        if (recordingActive || frameCaptureHelper != null) {
            Log.w(TAG, "Already recording -- ignoring startRecording($testName)")
            return
        }

        currentTestName = testName
        segmentIndex = 0

        ensureOutputDir()
        startSegment()

        // Probe: is screenrecord actually supported on this device?
        //
        // We must NOT check the file size here: screenrecord buffers and
        // doesn't flush the mp4 (moov atom etc.) until it stops, so the
        // file is legitimately 0 bytes for the first seconds even on a
        // perfectly working device. The old size-check therefore ALWAYS
        // fell back, even though screenrecord works fine — leaving reports
        // with a <video> tag pointing at an mp4 that was never produced.
        //
        // Instead, probe by process liveness: on devices that lack
        // screenrecord (some AOSP ATD images) the process exits
        // immediately; on supported devices it stays alive recording.
        if (recordingActive) {
            try { Thread.sleep(SCREENRECORD_PROBE_MS) } catch (_: InterruptedException) {}
            // Probe via shell (the app uid can't stat shell-written files
            // under scoped storage). screenrecord opens its output file
            // immediately; if it never appears, it's unsupported here
            // (e.g. AOSP ATD) → fall back to interval screenshots.
            val present = currentTempPath?.let {
                shellOutput("ls $it 2>/dev/null").trim().isNotEmpty()
            } ?: false
            if (!present) {
                Log.w(TAG, "screenrecord produced no output file — unsupported; falling back to FrameCaptureHelper")
                stopScreenrecordShell()
                recordingActive = false
                activateFallback(testName)
            } else {
                Log.i(TAG, "screenrecord probe OK (output file present)")
            }
        } else {
            activateFallback(testName)
        }
    }

    fun stopRecording() {
        // Fallback path
        val helper = frameCaptureHelper
        if (helper != null) {
            val frames = helper.stop()
            fallbackScope?.cancel()
            fallbackScope = null
            frameCaptureHelper = null
            Log.i(TAG, "Frame capture stopped: $frames frames")
            currentTestName = null
            currentTempPath = null
            return
        }

        // screenrecord path
        if (!recordingActive) return
        val tempPath = currentTempPath
        val testName = currentTestName
        stopScreenrecordShell()   // SIGINT → screenrecord finalizes the mp4
        recordingActive = false
        // Let screenrecord flush + close the mp4 container before reads.
        try { Thread.sleep(SCREENRECORD_FINALIZE_MS) } catch (_: InterruptedException) {}

        // copyToTestStorage mirrors to TestStorage for the AGP managed-device
        // path. Under `am instrument` the app uid can't read the shell-written
        // mp4 (scoped storage), so that copy no-ops and the Gradle task
        // adb-pulls the mp4 (as shell) from $TEMP_DIR next to the report.
        if (tempPath != null && testName != null) {
            copyToTestStorage(tempPath, testName)
        }

        currentTestName = null
        currentTempPath = null
        Log.i(TAG, "Recording stopped")
    }

    fun splitRecording() {
        if (frameCaptureHelper != null) return // no-op for fallback
        val testName = currentTestName ?: return
        stopRecording()
        currentTestName = testName
        segmentIndex++
        startSegment()
    }

    val isRecording: Boolean
        get() = recordingActive || frameCaptureHelper != null

    private fun activateFallback(testName: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        fallbackScope = scope
        val helper = FrameCaptureHelper(testName, intervalMs = 500L)
        frameCaptureHelper = helper
        helper.start(scope)
        Log.i(TAG, "Fallback: FrameCaptureHelper started for $testName @ 500ms")
    }

    private fun startSegment() {
        val testName = currentTestName ?: return
        val suffix = if (segmentIndex == 0) "" else "_seg$segmentIndex"
        val tempPath = "$TEMP_DIR/${testName}${suffix}.mp4"
        currentTempPath = tempPath

        try {
            // Launch via UiAutomation so screenrecord runs as the shell uid
            // (screen-capture privilege). Runtime.exec from the app uid is
            // denied and exits immediately.
            recordingPfd = uiAutomation.executeShellCommand(
                "screenrecord --time-limit $MAX_DURATION_SECONDS $tempPath"
            )
            recordingActive = true
            Log.i(TAG, "Recording started (shell): $tempPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screenrecord", e)
            recordingActive = false
        }
    }

    private fun copyToTestStorage(tempPath: String, testName: String) {
        val file = File(tempPath)
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Recording file missing or empty: $tempPath")
            return
        }
        val suffix = if (segmentIndex == 0) "" else "_seg$segmentIndex"
        val outputName = "${testName}${suffix}.mp4"

        // Mirror to TestStorage (AGP connectedAndroidTest path).
        testStorage?.let { storage ->
            try {
                storage.openOutputFile(outputName).use { out ->
                    FileInputStream(file).use { inp -> inp.copyTo(out) }
                }
                Log.i(TAG, "Video copied to TestStorage: $outputName (${file.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy video to TestStorage: ${e.message}")
            }
        }

        // Mirror to external-files dir so our `am instrument`–based
        // gradle tasks (which bypass AGP / TestStorage) can adb-pull
        // the video alongside the BDD report HTML. Matches the
        // ReportGenerator.openOutputBothPaths pattern.
        try {
            val ctx = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext
            val baseDir = ctx.getExternalFilesDir(null)
            if (baseDir != null) {
                val dir = File(baseDir, "tern-tests-report")
                if (dir.exists() || dir.mkdirs()) {
                    val outFile = File(dir, outputName)
                    FileInputStream(file).use { inp ->
                        java.io.FileOutputStream(outFile).use { out -> inp.copyTo(out) }
                    }
                    Log.i(TAG, "Video mirrored to external files: ${outFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mirror video to external files: ${e.message}")
        }
    }

    private fun ensureOutputDir() {
        // Must create via shell: the app uid can't mkdir under /sdcard scoped
        // storage on API 30+ (the old Runtime.exec mkdir silently failed, so
        // shell screenrecord then had no directory to write into and we always
        // fell back). screenrecord — also shell — writes here.
        shellOutput("mkdir -p $TEMP_DIR")
    }
}
