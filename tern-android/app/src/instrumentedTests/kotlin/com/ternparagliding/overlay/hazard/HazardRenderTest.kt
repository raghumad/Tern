package com.ternparagliding.overlay.hazard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the actual hazard-halo bitmaps ([renderHazardBitmap]) for a
 * spread of states and zoom scales, and writes a single labelled montage
 * PNG to the app's external files dir for human review.
 *
 * This is a *render-review* artifact, not a pass/fail correctness test:
 * it calls the real production renderer (no reimplementation) so what you
 * see is exactly what the map draws. Assertions only check that real
 * pixels were produced and that the exact hazard colours are present.
 */
@RunWith(AndroidJUnit4::class)
class HazardRenderTest {

    @Test
    fun render_hazard_states_to_montage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val amber = renderHazardBitmap(HazardLevel.CONVECTIVE)
        val red = renderHazardBitmap(HazardLevel.THUNDERSTORM)

        // The map applies iconSize per zoom; preview the same three stages.
        data class Cell(val label: String, val bmp: Bitmap, val scale: Float)
        val cells = listOf(
            Cell("CONVECTIVE · amber halo (z>10, 100%)", amber, 1.0f),
            Cell("THUNDERSTORM · red halo + bolt (z>10)", red, 1.0f),
            Cell("convective · z6–10 (70%)", amber, 0.7f),
            Cell("thunderstorm · z6–10 (70%)", red, 0.7f),
            Cell("convective · z<6 pin-prick (35%)", amber, 0.35f),
            Cell("thunderstorm · z<6 pin-prick (35%)", red, 0.35f),
        )

        val cols = 2
        val cellW = 420
        val cellH = 360
        val rows = (cells.size + cols - 1) / cols
        val montage = Bitmap.createBitmap(cols * cellW, rows * cellH + 60, Bitmap.Config.ARGB_8888)
        val c = Canvas(montage)
        c.drawColor(Color.rgb(60, 90, 55)) // mossy map-ish background
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText("Tern weather-hazard halos — live render", 24f, 44f, title)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f }

        cells.forEachIndexed { i, cell ->
            val scaled = Bitmap.createScaledBitmap(
                cell.bmp,
                (cell.bmp.width * cell.scale).toInt().coerceAtLeast(1),
                (cell.bmp.height * cell.scale).toInt().coerceAtLeast(1),
                true,
            )
            val cx = (i % cols) * cellW + cellW / 2f
            val cyTop = 60 + (i / cols) * cellH
            c.drawBitmap(scaled, cx - scaled.width / 2f, cyTop + (cellH - 70 - scaled.height) / 2f + 10f, null)
            c.drawText(cell.label, (i % cols) * cellW + 16f, cyTop + cellH - 24f, label)
        }

        val dir = ctx.getExternalFilesDir("tern-tests-report")
        val out = File(dir, "hazard-states.png")
        FileOutputStream(out).use { montage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("HazardRenderTest", "wrote montage: ${out.absolutePath} (${montage.width}x${montage.height})")

        // Sanity 1: the montage has real (non-background) pixels.
        var nonBg = 0
        for (y in 0 until montage.height step 7) for (x in 0 until montage.width step 7) {
            if (montage.getPixel(x, y) != Color.rgb(60, 90, 55)) nonBg++
        }
        assertTrue("montage appears empty — renderer drew nothing", nonBg > 200)

        // Sanity 2: the exact hazard colours are actually present.
        assertTrue("amber halo colour missing", hasColor(amber, AMBER_HAZARD))
        assertTrue("red halo colour missing", hasColor(red, RED_HAZARD))
    }

    private fun hasColor(bmp: Bitmap, argb: Int): Boolean {
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            if (bmp.getPixel(x, y) == argb) return true
        }
        return false
    }
}
