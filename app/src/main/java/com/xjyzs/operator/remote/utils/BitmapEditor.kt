package com.xjyzs.operator.remote.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object BitmapEditor {

    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val arrowPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun drawDot(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        dotPaint.color = color
        canvas.drawCircle(x, y, radius, dotPaint)
    }

    fun drawArrowLine(
        canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, strokeWidth: Float
    ) {
        linePaint.color = color
        linePaint.strokeWidth = strokeWidth
        canvas.drawLine(x1, y1, x2, y2, linePaint)
        val arrowLength = strokeWidth * 4f
        val arrowAngle = Math.toRadians(30.0)
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val x3 = (x2 - arrowLength * cos(angle - arrowAngle)).toFloat()
        val y3 = (y2 - arrowLength * sin(angle - arrowAngle)).toFloat()

        val x4 = (x2 - arrowLength * cos(angle + arrowAngle)).toFloat()
        val y4 = (y2 - arrowLength * sin(angle + arrowAngle)).toFloat()
        arrowPaint.color = color
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(x3, y3)
            lineTo(x4, y4)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }
}