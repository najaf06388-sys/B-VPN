package com.bubble.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** The big round power button. state: 0 = off (grey), 1 = starting (amber), 2 = connected (green). */
class PowerView(context: Context) : View(context) {

    var state: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val accent = Color.parseColor(
            when (state) {
                2 -> "#22C55E"
                1 -> "#F59E0B"
                else -> "#64748B"
            }
        )

        // soft glow
        fillPaint.color = accent
        fillPaint.alpha = 40
        canvas.drawCircle(cx, cy, r * 0.98f, fillPaint)

        // dark disc
        fillPaint.color = Color.parseColor("#111B2E")
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, r * 0.80f, fillPaint)

        // ring
        strokePaint.color = accent
        strokePaint.strokeWidth = r * 0.05f
        canvas.drawCircle(cx, cy, r * 0.80f, strokePaint)

        // power symbol
        strokePaint.strokeWidth = r * 0.09f
        val ir = r * 0.30f
        rect.set(cx - ir, cy - ir, cx + ir, cy + ir)
        canvas.drawArc(rect, -55f, 290f, false, strokePaint)
        canvas.drawLine(cx, cy - ir * 1.15f, cx, cy - ir * 0.10f, strokePaint)
    }
}
