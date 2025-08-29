package com.madanala.tern

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.osmdroid.views.overlay.Overlay

class CustomCompassOverlay(
    private val screenWidth: Float,
    private val screenHeight: Float
) : Overlay() {

    companion object {
        private const val TAG = "CustomCompassOverlay"
    }

    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        textSize = 40f
        isAntiAlias = true
    }

    private val compassPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas, mapView: org.osmdroid.views.MapView, shadow: Boolean) {
        if (shadow) return

        // Draw a simple compass rose at the specified position
        val centerX = screenWidth - 100f
        val centerY = 100f
        val radius = 30f

        // Draw compass circle
        canvas.drawCircle(centerX, centerY, radius, compassPaint)

        // Draw North indicator (simple triangle)
        val path = android.graphics.Path()
        path.moveTo(centerX, centerY - radius)
        path.lineTo(centerX - 8f, centerY - radius + 15f)
        path.lineTo(centerX + 8f, centerY - radius + 15f)
        path.close()
        canvas.drawPath(path, paint)

        // Draw "N" label
        canvas.drawText("N", centerX - 8f, centerY - radius - 10f, paint.apply {
            color = Color.BLACK
            textSize = 24f
        })

        Log.d(TAG, "Custom compass drawn at: $centerX, $centerY")
    }
}
