package com.ternparagliding.overlay.mezulla

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the actual peer-HUD bitmaps ([renderMarkerBitmap] /
 * [renderCompactBitmap]) for a spread of states and writes a single
 * labelled montage PNG to the app's external files dir for human review.
 *
 * This is a *render-review* artifact, not a pass/fail correctness test:
 * it calls the real production renderer (no reimplementation) so what you
 * see is exactly what the map draws. The only assertion is that the
 * montage is non-trivial (real pixels were produced).
 */
@RunWith(AndroidJUnit4::class)
class PeerHudRenderTest {

    private fun spec(
        callsign: String,
        puckColor: Long,
        track: Float?,
        dalt: String,
        daltColor: Long,
        dist: String,
        bottom: String,
        bottomColor: Long,
        staleness: MezullaPeerTextFormatter.StalenessLevel,
        glyph: String = MezullaIcons.PEER,
    ) = MarkerSpec(
        imageName = "peer-$callsign",
        callsign = callsign,
        shortTag = callsign.take(3),
        glyph = glyph,
        puckColor = puckColor.toInt(),
        trackDegrees = track,
        deltaAltText = dalt,
        deltaAltColor = daltColor.toInt(),
        distanceText = dist,
        bottomText = bottom,
        bottomColor = bottomColor.toInt(),
        staleness = staleness,
    )

    @Test
    fun render_peer_hud_states_to_montage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val nerd = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_nerd_regular)

        val fresh = spec("STEPHEN", 0xFF4CAF50, 40f, "+340m", 0xFFB4FFB4, "2.4km",
            "+1.2 m/s", 0xFFFFFFFF, MezullaPeerTextFormatter.StalenessLevel.FRESH)
        val stale = spec("JOSH", 0xFFFF9100, 200f, "-180m", 0xFFFFB4B4, "6.1km",
            "⚠ STALE", 0xFFFF9100, MezullaPeerTextFormatter.StalenessLevel.STALE)
        val lost = spec("LUC", 0xFF9E9E9E, null, "", 0xFFB4FFB4, "",
            "⚠ LOST", 0xFF9E9E9E, MezullaPeerTextFormatter.StalenessLevel.LOST,
            glyph = MezullaIcons.PEER_LOST)

        data class Cell(val label: String, val bmp: Bitmap)
        val cells = listOf(
            Cell("FRESH · above · climbing", renderMarkerBitmap(fresh, nerd)),
            Cell("STALE · below me", renderMarkerBitmap(stale, nerd)),
            Cell("LOST", renderMarkerBitmap(lost, nerd)),
            Cell("compact: fresh", renderCompactBitmap(fresh, nerd)),
            Cell("compact: stale", renderCompactBitmap(stale, nerd)),
            Cell("compact: lost", renderCompactBitmap(lost, nerd)),
        )

        val cols = 3
        val cellW = 380
        val cellH = 360
        val rows = (cells.size + cols - 1) / cols
        val montage = Bitmap.createBitmap(cols * cellW, rows * cellH + 60, Bitmap.Config.ARGB_8888)
        val c = Canvas(montage)
        c.drawColor(Color.rgb(60, 90, 55)) // mossy map-ish background
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText("Tern peer HUD — live render", 24f, 44f, title)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f }

        cells.forEachIndexed { i, cell ->
            val cx = (i % cols) * cellW + cellW / 2f
            val cyTop = 60 + (i / cols) * cellH
            c.drawBitmap(cell.bmp, cx - cell.bmp.width / 2f, cyTop + (cellH - 70 - cell.bmp.height) / 2f + 10f, null)
            c.drawText(cell.label, (i % cols) * cellW + 16f, cyTop + cellH - 24f, label)
        }

        val dir = ctx.getExternalFilesDir("tern-tests-report")
        val out = File(dir, "peer-hud-states.png")
        FileOutputStream(out).use { montage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("PeerHudRenderTest", "wrote montage: ${out.absolutePath} (${montage.width}x${montage.height})")

        // Sanity: the montage actually has non-background pixels (real HUDs drawn).
        var nonBg = 0
        for (y in 0 until montage.height step 7) for (x in 0 until montage.width step 7) {
            if (montage.getPixel(x, y) != Color.rgb(60, 90, 55)) nonBg++
        }
        assertTrue("montage appears empty — renderer drew nothing", nonBg > 200)
    }
}
