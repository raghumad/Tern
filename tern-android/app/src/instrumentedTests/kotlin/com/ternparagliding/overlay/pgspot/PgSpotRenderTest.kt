package com.ternparagliding.overlay.pgspot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the actual PG-spot marker bitmaps ([renderPgSpotBitmap]) for a
 * spread of names and zoom scales, and writes a labelled montage PNG to the
 * app's external files dir for human review.
 *
 * Render-review artifact (not pass/fail correctness): it calls the real
 * production renderer so the montage is exactly what the map draws. The only
 * assertions are that real pixels exist and the launch-green is present.
 */
@RunWith(AndroidJUnit4::class)
class PgSpotRenderTest {

    @Test
    fun render_pg_spot_states_to_montage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val bird = BitmapFactory.decodeResource(ctx.resources, R.drawable.tern_pgspot)

        data class Cell(val label: String, val name: String, val scale: Float)
        val cells = listOf(
            Cell("z>12 full (100%)", "Boulder Launch", 1.0f),
            Cell("z>12 full (100%)", "Chamonix S", 1.0f),
            Cell("z8–12 (80%)", "Boulder Launch", 0.8f),
            Cell("z8–12 (80%)", "Bir Billing", 0.8f),
            Cell("z<8 (55%)", "Boulder Launch", 0.55f),
            Cell("z<8 (55%)", "Edith's Gap", 0.55f),
        )

        val cols = 2
        val cellW = 420
        val cellH = 240
        val rows = (cells.size + cols - 1) / cols
        val montage = Bitmap.createBitmap(cols * cellW, rows * cellH + 60, Bitmap.Config.ARGB_8888)
        val c = Canvas(montage)
        c.drawColor(Color.rgb(60, 90, 55)) // mossy map-ish background
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText("Tern PG-spot markers — live render", 24f, 44f, title)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f }

        var tealSeen = false
        cells.forEachIndexed { i, cell ->
            val bmp = renderPgSpotBitmap(cell.name, bird)
            if (hasColor(bmp, PG_SPOT_TEAL)) tealSeen = true
            val scaled = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * cell.scale).toInt().coerceAtLeast(1),
                (bmp.height * cell.scale).toInt().coerceAtLeast(1),
                true,
            )
            val cx = (i % cols) * cellW + cellW / 2f
            val cyTop = 60 + (i / cols) * cellH
            c.drawBitmap(scaled, cx - scaled.width / 2f, cyTop + (cellH - 60 - scaled.height) / 2f + 10f, null)
            c.drawText(cell.label, (i % cols) * cellW + 16f, cyTop + cellH - 18f, label)
        }

        val dir = ctx.getExternalFilesDir("tern-tests-report")
        val out = File(dir, "pgspot-states.png")
        FileOutputStream(out).use { montage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("PgSpotRenderTest", "wrote montage: ${out.absolutePath} (${montage.width}x${montage.height})")

        var nonBg = 0
        for (y in 0 until montage.height step 7) for (x in 0 until montage.width step 7) {
            if (montage.getPixel(x, y) != Color.rgb(60, 90, 55)) nonBg++
        }
        assertTrue("montage appears empty — renderer drew nothing", nonBg > 200)
        assertTrue("tern-icon teal missing", tealSeen)
    }

    private fun hasColor(bmp: Bitmap, argb: Int): Boolean {
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            if (bmp.getPixel(x, y) == argb) return true
        }
        return false
    }
}
