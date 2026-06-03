package com.ternparagliding.overlay.route

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ternparagliding.model.LocationType
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the actual route waypoint marker bitmaps ([renderWaypointBitmap])
 * for every waypoint type (+ a selected variant) into a labelled montage PNG
 * for human review — the real production renderer, so the montage is exactly
 * what the map will draw.
 */
@RunWith(AndroidJUnit4::class)
class RouteWaypointRenderTest {

    @Test
    fun render_waypoint_markers_to_montage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        data class Cell(val label: String, val bmp: Bitmap)
        val cells = listOf(
            Cell("LAUNCH · Takeoff", renderWaypointBitmap(LocationType.LAUNCH, 0, "Fiesch Launch")),
            Cell("SSS · Start", renderWaypointBitmap(LocationType.SSS, 0, "Start Cylinder")),
            Cell("TURNPOINT 1", renderWaypointBitmap(LocationType.TURNPOINT, 1, "Eggishorn")),
            Cell("TURNPOINT 2", renderWaypointBitmap(LocationType.TURNPOINT, 2, "Pizzo Rotondo")),
            Cell("ESS · End speed", renderWaypointBitmap(LocationType.ESS, 0, "End Speed")),
            Cell("GOAL", renderWaypointBitmap(LocationType.GOAL, 0, "Goal Field")),
            Cell("LANDING", renderWaypointBitmap(LocationType.LANDING, 0, "Bedretto LZ")),
            Cell("TURNPOINT 2 · SELECTED", renderWaypointBitmap(LocationType.TURNPOINT, 2, "Pizzo Rotondo", selected = true)),
        )

        val cols = 2
        val cellW = 420
        val cellH = 230
        val rows = (cells.size + cols - 1) / cols
        val montage = Bitmap.createBitmap(cols * cellW, rows * cellH + 60, Bitmap.Config.ARGB_8888)
        val c = Canvas(montage)
        c.drawColor(Color.rgb(60, 90, 55))
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText("Tern route waypoint markers — live render", 24f, 44f, title)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f }

        cells.forEachIndexed { i, cell ->
            val cx = (i % cols) * cellW + cellW / 2f
            val cyTop = 60 + (i / cols) * cellH
            c.drawBitmap(cell.bmp, cx - cell.bmp.width / 2f, cyTop + (cellH - 56 - cell.bmp.height) / 2f + 10f, null)
            c.drawText(cell.label, (i % cols) * cellW + 16f, cyTop + cellH - 16f, label)
        }

        val dir = ctx.getExternalFilesDir("tern-tests-report")
        val out = File(dir, "route-waypoint-markers.png")
        FileOutputStream(out).use { montage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("RouteWaypointRenderTest", "wrote montage: ${out.absolutePath}")

        var nonBg = 0
        for (y in 0 until montage.height step 7) for (x in 0 until montage.width step 7) {
            if (montage.getPixel(x, y) != Color.rgb(60, 90, 55)) nonBg++
        }
        assertTrue("montage appears empty", nonBg > 200)
    }
}
