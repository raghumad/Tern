package com.ternparagliding.overlay.task

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import com.ternparagliding.model.LocationType

/**
 * Rasterises task waypoint markers + leg-distance pills as icon bitmaps.
 * Drawn with Canvas (no Nerd Font / sprite) so they render on every map style
 * — the `textField`/`marker-15` approach drew nothing on Tern's raster styles.
 *
 * Cylinder-centric design: the FAI cylinder ring (drawn by TaskLayer) is the
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
 * Renders a task waypoint marker. The role-coloured disc carries the short code
 * (turnpoint number, T/S/E, checkered flag for a GOAL); at the [detailed] tier the
 * comp-relevant facts sit around it — the **name** pill below the disc, and a right-hand
 * pill stacking the **cylinder radius**, **elevation**, and **time gate** when present —
 * the same rich treatment the library marker gets, so "1" stops being cryptic. [selected]
 * enlarges + haloes the disc. Symmetric so the default CENTER anchor lands the disc on the
 * geo point.
 */
internal fun renderWaypointBitmap(
    type: LocationType,
    seq: Int,
    name: String,
    selected: Boolean = false,
    detailed: Boolean = true,
    radiusM: Double? = null,
    altM: Double? = null,
    openTime: String? = null,
    closeTime: String? = null,
    nerdFont: Typeface? = null,
): Bitmap {
    val wp = waypointStyle(type, seq)

    // A GOAL renders the checkered-flag glyph (Nerd Font, nf-fa-flag-checkered) instead of "G".
    val goalFlag = type == LocationType.GOAL && nerdFont != null
    val codeStr = if (goalFlag) String(Character.toChars(0xF11E)) else wp.code
    val codeFace = if (goalFlag) nerdFont!! else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    val discR = (if (selected) 11f else 8.5f) * S
    val ringW = 2.5f * S
    val haloW = 3f * S
    val outerR = discR + (if (selected) haloW else 0f)

    val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = wp.discColor; style = Paint.Style.FILL }
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = ringW
    }
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(220, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = haloW
    }
    val codeSize = (if (goalFlag) 13f else if (wp.code.length >= 2) 9f else 11f) * S
    val codeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = codeSize
        typeface = codeFace; textAlign = Paint.Align.CENTER
    }
    val codeHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(160, 0, 0, 0); textSize = codeSize
        typeface = codeFace; textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE; strokeWidth = 3f
    }

    val gap = 3f * S; val pH = 6f * S; val pV = 3f * S; val pR = 5f * S; val innerGap = 4f * S

    fun drawDisc(c: Canvas, cx: Float, cy: Float) {
        if (selected) c.drawCircle(cx, cy, discR + haloW * 0.6f, haloPaint)
        c.drawCircle(cx, cy, discR, discPaint)
        c.drawCircle(cx, cy, discR, ringPaint)
        val cyText = cy - (codeFill.descent() + codeFill.ascent()) / 2f
        if (!goalFlag) c.drawText(codeStr, cx, cyText, codeHalo) // halo muddies the glyph
        c.drawText(codeStr, cx, cyText, codeFill)
    }

    // ── Compact tier (zoomed out): just the disc ─────────────────────────
    if (!detailed) {
        val side = (outerR + gap) * 2f
        val bmp = Bitmap.createBitmap(side.toInt().coerceAtLeast(1), side.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        drawDisc(Canvas(bmp), side / 2f, side / 2f)
        return bmp
    }

    // ── Detailed tier: name pill below; info split left/right of the disc to balance it ──
    val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 11f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.LEFT
    }
    val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(190, 20, 20, 20); style = Paint.Style.FILL
    }
    val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 10f * S
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.LEFT
    }
    val infoGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCFD8DC.toInt(); textSize = 10f * S
        typeface = nerdFont ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.LEFT
    }

    val nameW = namePaint.measureText(name)
    val nameTextH = namePaint.descent() - namePaint.ascent()
    val namePillW = nameW + pH * 2
    val namePillH = nameTextH + pV * 2

    // Info lines — Triple(kind, glyph, value); kind 0 = drawn radius glyph, 1 = font glyph.
    val elevGlyph = if (nerdFont != null) String(Character.toChars(0xF0509)) else "" // md-terrain
    val gateGlyph = if (nerdFont != null) String(Character.toChars(0xF017)) else ""   // fa-clock-o
    val gateText = when {
        !openTime.isNullOrBlank() && !closeTime.isNullOrBlank() -> "$openTime–$closeTime"
        !openTime.isNullOrBlank() -> openTime
        !closeTime.isNullOrBlank() -> closeTime
        else -> null
    }
    // Cylinder + gate sit LEFT of the disc; elevation sits RIGHT (as on the library marker),
    // so the marker reads balanced instead of stacking everything on one side.
    val leftLines = buildList {
        if (radiusM != null && radiusM > 0) add(Triple(0, "", TaskGeoJson.formatKm(radiusM / 1000.0)))
        if (gateText != null) add(Triple(1, gateGlyph, gateText))
    }
    val rightLines = buildList {
        if (altM != null) add(Triple(1, elevGlyph, "${altM.toInt()} m"))
    }

    val infoTextH = infoPaint.descent() - infoPaint.ascent()
    val glyphSize = infoTextH * 0.9f
    val lineGap = 2f * S
    fun glyphW(l: Triple<Int, String, String>): Float = when (l.first) {
        0 -> glyphSize
        else -> if (l.second.isEmpty()) 0f else infoGlyphPaint.measureText(l.second)
    }
    fun lineW(l: Triple<Int, String, String>): Float {
        val gw = glyphW(l)
        return gw + (if (gw > 0f) innerGap else 0f) + infoPaint.measureText(l.third)
    }
    fun pillWidth(ls: List<Triple<Int, String, String>>): Float =
        if (ls.isEmpty()) 0f else ls.maxOf { lineW(it) } + pH * 2
    fun pillHeight(ls: List<Triple<Int, String, String>>): Float =
        if (ls.isEmpty()) 0f else ls.size * infoTextH + (ls.size - 1) * lineGap + pV * 2

    val leftW = pillWidth(leftLines); val leftH = pillHeight(leftLines)
    val rightW = pillWidth(rightLines); val rightH = pillHeight(rightLines)

    // Symmetric so the disc lands on the geo point: pad each side to the wider content.
    val leftExtent = if (leftLines.isNotEmpty()) outerR + gap + leftW else outerR
    val rightExtent = if (rightLines.isNotEmpty()) outerR + gap + rightW else outerR
    val halfW = maxOf(leftExtent, rightExtent, namePillW / 2f, outerR) + gap
    val w = halfW * 2f
    val topExtent = maxOf(outerR, leftH / 2f, rightH / 2f)
    val bottomExtent = maxOf(outerR + gap + namePillH, leftH / 2f, rightH / 2f)
    val band = maxOf(topExtent, bottomExtent) + gap
    val h = band * 2f

    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = halfW; val cy = band
    drawDisc(c, cx, cy)

    // Name pill — centred below the disc.
    val namePillLeft = cx - namePillW / 2f
    val namePillTop = cy + outerR + gap
    c.drawRoundRect(namePillLeft, namePillTop, namePillLeft + namePillW, namePillTop + namePillH, pR, pR, pillPaint)
    c.drawText(name, namePillLeft + pH, namePillTop + pV - namePaint.ascent(), namePaint)

    // Draw one info pill (multi-line) with its left edge at [left], vertically centred on the disc.
    fun drawPill(ls: List<Triple<Int, String, String>>, left: Float) {
        if (ls.isEmpty()) return
        val pw = pillWidth(ls); val ph = pillHeight(ls)
        val top = cy - ph / 2f
        c.drawRoundRect(left, top, left + pw, top + ph, pR, pR, pillPaint)
        ls.forEachIndexed { i, l ->
            val lineTop = top + pV + i * (infoTextH + lineGap)
            val midY = lineTop + infoTextH / 2f
            val baseline = lineTop - infoPaint.ascent()
            var x = left + pH
            val gw = glyphW(l)
            if (l.first == 0) {
                drawRadiusGlyph(c, x, midY, glyphSize)
            } else if (l.second.isNotEmpty()) {
                c.drawText(l.second, x, midY - (infoGlyphPaint.descent() + infoGlyphPaint.ascent()) / 2f, infoGlyphPaint)
            }
            if (gw > 0f) x += gw + innerGap
            c.drawText(l.third, x, baseline, infoPaint)
        }
    }

    drawPill(leftLines, cx - outerR - gap - leftW)
    drawPill(rightLines, cx + outerR + gap)
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

/** A leg-distance pill ("25 km") that sits on the task line at the leg midpoint. */
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
