package com.madanala.tern.test

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.madanala.tern.R
import com.madanala.tern.overlay.mezulla.MezullaIcons
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Renders peer marker bitmaps standalone (no map) so we can visually
 * verify the layout, sizing, colors, and text for different data sets.
 *
 * Outputs PNGs to the test device's external storage where AGP copies
 * them to build/outputs/ for review.
 */
class PeerMarkerBitmapTest {

    private lateinit var nerdFont: Typeface

    @Before
    fun loadFont() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        nerdFont = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_nerd_regular)!!
    }

    @Test
    fun render_all_staleness_states() {
        val markers = listOf(
            spec("COR", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "0", "s", "1970", "m"),
            spec("CBE", MezullaIcons.PEER, 0xFFFFD600.toInt(), "45", "s", "2130", "m"),
            spec("LMA", MezullaIcons.PEER, 0xFFFF9100.toInt(), "3", "m", "1450", "m"),
            spec("TXK", MezullaIcons.PEER_LOST, 0xFF9E9E9E.toInt(), "lost", "", "", ""),
            spec("SOS", MezullaIcons.SOS, 0xFFF44336.toInt(), "SOS", "", "890", "m"),
        )
        renderGrid("staleness_states", markers)
    }

    @Test
    fun render_safety_view() {
        val markers = listOf(
            spec("COR", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "5", "s", "2450", "m"),
            spec("CBE", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "12", "s", "980", "m"),
            spec("LMA", MezullaIcons.PEER, 0xFFFFD600.toInt(), "58", "s", "3100", "m"),
            spec("TONIO24", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "2", "s", "15230", "m"),
        )
        renderGrid("safety_view", markers)
    }

    @Test
    fun render_climb_view() {
        val markers = listOf(
            spec("COR", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "+2.3", "m/s", "2450", "m"),
            spec("CBE", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "-1.8", "m/s", "980", "m"),
            spec("LMA", MezullaIcons.PEER, 0xFFFFD600.toInt(), "+0.0", "m/s", "3100", "m"),
            spec("MAX", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "+5.2", "m/s", "4200", "m"),
        )
        renderGrid("climb_view", markers)
    }

    @Test
    fun render_tactical_view() {
        val markers = listOf(
            spec("COR", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "42", "km/h", "2450", "m"),
            spec("CBE", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "0", "km/h", "980", "m"),
            spec("LMA", MezullaIcons.PEER, 0xFFFFD600.toInt(), "38", "km/h", "3100", "m"),
        )
        renderGrid("tactical_view", markers)
    }

    @Test
    fun render_anchor_proof() {
        val markers = listOf(
            spec("COR", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "5", "s", "2450", "m"),
            spec("TONIO24", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "2", "s", "15230", "m"),
            spec("TXK", MezullaIcons.PEER_LOST, 0xFF9E9E9E.toInt(), "lost", "", "", ""),
        )
        val bitmaps = markers.map { s ->
            val bmp = renderMarker(s)
            // Draw red crosshair at bitmap center to prove circle center alignment
            val c = Canvas(bmp)
            val crossPaint = Paint().apply {
                color = Color.RED
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
            val cx = bmp.width / 2f
            val cy = bmp.height / 2f
            c.drawLine(cx - 20, cy, cx + 20, cy, crossPaint)
            c.drawLine(cx, cy - 20, cx, cy + 20, crossPaint)
            bmp
        }

        val cellH = bitmaps.maxOf { it.height } + 40
        val cellW = bitmaps.maxOf { it.width } + 40
        val grid = Bitmap.createBitmap(cellW, cellH * bitmaps.size, Bitmap.Config.ARGB_8888)
        val gc = Canvas(grid)
        gc.drawColor(Color.parseColor("#2D2D2D"))
        bitmaps.forEachIndexed { i, bmp ->
            gc.drawBitmap(bmp, (cellW - bmp.width) / 2f, (i * cellH + (cellH - bmp.height) / 2).toFloat(), null)
        }
        savePng("anchor_proof", grid)
    }

    @Test
    fun render_edge_cases() {
        val markers = listOf(
            spec("X", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "0", "s", "0", "m"),
            spec("ABCDEFGH", MezullaIcons.PEER, 0xFF4CAF50.toInt(), "999", "s", "99999", "m"),
            spec("CB", MezullaIcons.PEER_LOST, 0xFF9E9E9E.toInt(), "lost", "", "", ""),
            spec("---", MezullaIcons.PEER, 0xFFFF9100.toInt(), "---", "", "---", ""),
        )
        renderGrid("edge_cases", markers)
    }

    // -- Rendering (mirrors NativeOverlayLayers.renderMarkerBitmap) --

    private data class Spec(
        val callsign: String,
        val glyph: String,
        val glyphColor: Int,
        val leftValue: String,
        val leftUnit: String,
        val rightValue: String,
        val rightUnit: String,
    )

    private fun spec(cs: String, g: String, gc: Int, lv: String, lu: String, rv: String, ru: String) =
        Spec(cs, g, gc, lv, lu, rv, ru)

    private val S = 2.5f

    private fun renderMarker(s: Spec): Bitmap {
        val callsignPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * S
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * S
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255)
            textSize = 8f * S
        }
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f * S
            typeface = nerdFont
        }
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.glyphColor
            style = Paint.Style.FILL
        }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f * S
        }
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 20, 20, 20)
            style = Paint.Style.FILL
        }

        val circleR = 20f * S
        val gap = 4f * S
        val pH = 6f * S
        val pV = 3f * S
        val pR = 5f * S

        val csH = callsignPaint.descent() - callsignPaint.ascent()
        val csPillH = csH + pV * 2
        val csW = callsignPaint.measureText(s.callsign)
        val csPillW = csW + pH * 2

        val leftVW = valuePaint.measureText(s.leftValue)
        val leftUW = unitPaint.measureText(s.leftUnit)
        val leftPW = if (s.leftValue.isNotEmpty()) leftVW + leftUW + pH * 2 else 0f
        val rightVW = valuePaint.measureText(s.rightValue)
        val rightUW = unitPaint.measureText(s.rightUnit)
        val rightPW = if (s.rightValue.isNotEmpty()) rightVW + rightUW + pH * 2 else 0f
        val valH = valuePaint.descent() - valuePaint.ascent()
        val pillH = valH + pV * 2

        val leftExtent = circleR + (if (leftPW > 0) gap + leftPW else 0f)
        val rightExtent = circleR + (if (rightPW > 0) gap + rightPW else 0f)
        val halfW = maxOf(leftExtent, rightExtent, csPillW / 2f) + gap
        val w = halfW * 2
        val h = csPillH + gap + circleR * 2 + gap + csPillH

        val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val cy = csPillH + gap + circleR

        // Callsign pill
        c.drawRoundRect(cx - csPillW / 2f, 0f, cx + csPillW / 2f, csPillH, pR, pR, pillPaint)
        c.drawText(s.callsign, cx, pV - callsignPaint.ascent(), callsignPaint)

        // Circle + ring
        c.drawCircle(cx, cy, circleR, circlePaint)
        c.drawCircle(cx, cy, circleR, ringPaint)

        // Glyph — getTextBounds for precise visual centering
        val bounds = android.graphics.Rect()
        glyphPaint.getTextBounds(s.glyph, 0, s.glyph.length, bounds)
        val glyphX = cx - (bounds.left + bounds.right) / 2f
        val glyphY = cy - (bounds.top + bounds.bottom) / 2f
        c.drawText(s.glyph, glyphX, glyphY, glyphPaint)

        if (s.leftValue.isNotEmpty()) {
            val pr = cx - circleR - gap
            val pl = pr - leftPW
            val pt = cy - pillH / 2f
            val pb = cy + pillH / 2f
            c.drawRoundRect(pl, pt, pr, pb, pR, pR, pillPaint)
            val ty = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f
            c.drawText(s.leftValue, pl + pH, ty, valuePaint)
            c.drawText(s.leftUnit, pl + pH + leftVW, ty, unitPaint)
        }

        if (s.rightValue.isNotEmpty()) {
            val pl = cx + circleR + gap
            val pr = pl + rightPW
            val pt = cy - pillH / 2f
            val pb = cy + pillH / 2f
            c.drawRoundRect(pl, pt, pr, pb, pR, pR, pillPaint)
            val ty = cy - (valuePaint.descent() + valuePaint.ascent()) / 2f
            c.drawText(s.rightValue, pl + pH, ty, valuePaint)
            c.drawText(s.rightUnit, pl + pH + rightVW, ty, unitPaint)
        }

        return bmp
    }

    /** Arrange markers in a grid on a dark background and save as PNG. */
    private fun renderGrid(name: String, markers: List<Spec>) {
        val bitmaps = markers.map { renderMarker(it) }
        val cols = 2
        val rows = (bitmaps.size + cols - 1) / cols
        val cellW = bitmaps.maxOf { it.width } + 40
        val cellH = bitmaps.maxOf { it.height } + 40
        val gridW = cols * cellW
        val gridH = rows * cellH

        val grid = Bitmap.createBitmap(gridW, gridH, Bitmap.Config.ARGB_8888)
        val c = Canvas(grid)

        // Dark background to simulate dark map tiles
        c.drawColor(Color.parseColor("#2D2D2D"))

        // Light background variant on right column to test readability on light tiles
        val lightPaint = Paint().apply { color = Color.parseColor("#E8E0D8") }
        for (row in 0 until rows) {
            c.drawRect(
                cellW.toFloat(), (row * cellH).toFloat(),
                gridW.toFloat(), ((row + 1) * cellH).toFloat(),
                lightPaint,
            )
        }

        bitmaps.forEachIndexed { i, bmp ->
            val col = i % cols
            val row = i / cols
            val x = col * cellW + (cellW - bmp.width) / 2
            val y = row * cellH + (cellH - bmp.height) / 2
            c.drawBitmap(bmp, x.toFloat(), y.toFloat(), null)

            // If only one column worth of markers, duplicate on light bg
            if (cols == 2 && i < markers.size) {
                val x2 = 1 * cellW + (cellW - bmp.width) / 2
                val y2 = (i) * cellH + (cellH - bmp.height) / 2
                if (i % cols == 0 && i + 1 >= markers.size) {
                    // Last odd marker — draw on light side too
                    c.drawBitmap(bmp, x2.toFloat(), y2.toFloat(), null)
                }
            }
        }

        // Actually, simpler: render each marker twice, once on dark, once on light
        val finalW = bitmaps.maxOf { it.width } + 60
        val finalH = (bitmaps.maxOf { it.height } + 30) * bitmaps.size
        val final2W = finalW * 2
        val finalBmp = Bitmap.createBitmap(final2W, finalH, Bitmap.Config.ARGB_8888)
        val fc = Canvas(finalBmp)

        // Left half: dark
        fc.drawRect(0f, 0f, finalW.toFloat(), finalH.toFloat(),
            Paint().apply { color = Color.parseColor("#2D2D2D") })
        // Right half: light
        fc.drawRect(finalW.toFloat(), 0f, final2W.toFloat(), finalH.toFloat(),
            Paint().apply { color = Color.parseColor("#E8E0D8") })

        val rowH = finalH / bitmaps.size
        bitmaps.forEachIndexed { i, bmp ->
            val y = i * rowH + (rowH - bmp.height) / 2
            // Dark side
            fc.drawBitmap(bmp, (finalW - bmp.width) / 2f, y.toFloat(), null)
            // Light side
            fc.drawBitmap(bmp, finalW + (finalW - bmp.width) / 2f, y.toFloat(), null)
        }

        savePng(name, finalBmp)
    }

    private fun savePng(name: String, bitmap: Bitmap) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "marker_bitmaps")
        dir.mkdirs()
        val file = File(dir, "$name.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        // Copy to managed device output directory so AGP picks it up
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outDir = androidx.test.platform.app.InstrumentationRegistry
            .getArguments().getString("additionalTestOutputDir")
        if (outDir != null) {
            val outFile = File(outDir, "marker_$name.png")
            file.copyTo(outFile, overwrite = true)
        }
    }
}
