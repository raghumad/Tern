package com.ternparagliding.overlay.route

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import com.ternparagliding.model.LocationType

/**
 * Rasterises route waypoint markers + leg-distance pills as icon bitmaps.
 * Drawn with Canvas (no Nerd Font / sprite) so they render on every map style
 * — the `textField`/`marker-15` approach drew nothing on Tern's raster styles.
 *
 * Cylinder-centric design: the FAI cylinder ring (drawn by RouteLayer) is the
 * waypoint's identity; the marker here is a small role-coloured centre + short
 * code, with the name/radius shown only at the detailed (zoomed-in) tier.
 */

/** Bitmap supersampling factor — crisp when scaled by zoom. */
private const val S = 2.5f

internal data class WaypointStyle(val discColor: Int, val code: String)

internal val LAUNCH_COLOR = 0xFF2E7D32.toInt()      // green
internal val SSS_COLOR = 0xFF00C853.toInt()         // bright green (start)
internal val TURNPOINT_COLOR = 0xFF2962FF.toInt()   // blue
internal val ESS_COLOR = 0xFFFFA000.toInt()         // amber (end speed)
internal val GOAL_COLOR = 0xFFD50000.toInt()        // red
internal val LANDING_COLOR = 0xFFFF6D00.toInt()     // orange

/** Turnpoints show their sequence number; special types show a letter code. */
internal fun waypointStyle(type: LocationType, seq: Int): WaypointStyle = when (type) {
    LocationType.LAUNCH -> WaypointStyle(LAUNCH_COLOR, "T")
    LocationType.SSS -> WaypointStyle(SSS_COLOR, "S")
    LocationType.ESS -> WaypointStyle(ESS_COLOR, "E")
    LocationType.GOAL -> WaypointStyle(GOAL_COLOR, "G")
    LocationType.LANDING -> WaypointStyle(LANDING_COLOR, "LZ")
    else -> WaypointStyle(TURNPOINT_COLOR, seq.toString())
}

/** Role colour for a waypoint type — also used for its cylinder ring/fill. */
internal fun cylinderColor(type: LocationType): Int = waypointStyle(type, 0).discColor

/**
 * Renders a waypoint marker. Small role-coloured centre + short code; at the
 * [detailed] tier a name pill (and radius, if [radiusM] given) sits below.
 * [selected] enlarges the centre and adds a white halo. Symmetric so the
 * default CENTER anchor lands the centre on the geo point.
 */
internal fun renderWaypointBitmap(
    type: LocationType,
    seq: Int,
    name: String,
    selected: Boolean = false,
    detailed: Boolean = true,
    radiusM: Double? = null,
): Bitmap {
    val wp = waypointStyle(type, seq)

    val discR = (if (selected) 11f else 8.5f) * S
    val ringW = 2.5f * S
    val haloW = 3f * S

    val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = wp.discColor; style = Paint.Style.FILL }
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = ringW
    }
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(220, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = haloW
    }
    val codeSize = (if (wp.code.length >= 2) 9f else 11f) * S
    val codeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = codeSize
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    val codeHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(160, 0, 0, 0); textSize = codeSize
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    // Detailed pill = name [ radius-glyph  radius-value ]. The glyph (a small
    // circle with a radius line) conveys "radius" instead of an "r" prefix.
    val showRadius = detailed && radiusM != null && radiusM > 0
    val radiusText = if (showRadius) RouteGeoJson.formatKm((radiusM ?: 0.0) / 1000.0) else ""
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 11f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.LEFT
    }
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(190, 20, 20, 20); style = Paint.Style.FILL
    }

    val gap = 3f * S; val pH = 6f * S; val pV = 3f * S; val pR = 5f * S
    val hasPill = detailed
    val nameW = if (hasPill) namePaint.measureText(name) else 0f
    val glyphSize = (namePaint.descent() - namePaint.ascent()) * 0.82f
    val innerGap = 4f * S
    val radiusW = if (showRadius) innerGap + glyphSize + innerGap + namePaint.measureText(radiusText) else 0f
    val contentW = nameW + radiusW
    val pillW = if (hasPill) contentW + pH * 2 else 0f
    val pillH = if (hasPill) (namePaint.descent() - namePaint.ascent()) + pV * 2 else 0f

    val outerR = discR + (if (selected) haloW else 0f)
    val halfW = maxOf(outerR, pillW / 2f) + gap
    val w = halfW * 2f
    // Symmetric vertical layout → centre on the geo point.
    val band = if (hasPill) gap + pillH else outerR
    val h = band + outerR * 2f + band

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f; val cy = h / 2f

    if (selected) c.drawCircle(cx, cy, discR + haloW * 0.6f, haloPaint)
    c.drawCircle(cx, cy, discR, discPaint)
    c.drawCircle(cx, cy, discR, ringPaint)
    val cyText = cy - (codeFill.descent() + codeFill.ascent()) / 2f
    c.drawText(wp.code, cx, cyText, codeHalo)
    c.drawText(wp.code, cx, cyText, codeFill)

    if (hasPill) {
        val pillTop = cy + outerR + gap
        c.drawRoundRect(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH, pR, pR, pillPaint)
        var x = cx - contentW / 2f
        val textY = pillTop + pV - namePaint.ascent()
        c.drawText(name, x, textY, namePaint)
        x += nameW
        if (showRadius) {
            x += innerGap
            drawRadiusGlyph(c, x, pillTop + pillH / 2f, glyphSize)
            x += glyphSize + innerGap
            c.drawText(radiusText, x, textY, namePaint)
        }
    }
    return bmp
}

/** A small "radius" pictogram: a circle with a radius line to its right edge. */
private fun drawRadiusGlyph(c: Canvas, left: Float, cy: Float, size: Float) {
    val r = size / 2f
    val gcx = left + r
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.6f * S
    }
    c.drawCircle(gcx, cy, r, stroke)
    c.drawLine(gcx, cy, gcx + r, cy, stroke)
    c.drawCircle(gcx, cy, 1.4f * S, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.FILL
    })
}

/** A leg-distance pill ("25 km") that sits on the route line at the leg midpoint. */
internal fun renderLegPillBitmap(text: String): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 10f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(205, 10, 20, 23); style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(180, 0, 229, 255); style = Paint.Style.STROKE; strokeWidth = 1.2f * S
    }
    val pH = 6f * S; val pV = 3f * S; val pR = 5f * S
    val tw = textPaint.measureText(text)
    val th = textPaint.descent() - textPaint.ascent()
    val w = tw + pH * 2; val h = th + pV * 2
    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRoundRect(0f, 0f, w, h, pR, pR, bgPaint)
    c.drawRoundRect(0.6f * S, 0.6f * S, w - 0.6f * S, h - 0.6f * S, pR, pR, borderPaint)
    c.drawText(text, w / 2f, pV - textPaint.ascent(), textPaint)
    return bmp
}
