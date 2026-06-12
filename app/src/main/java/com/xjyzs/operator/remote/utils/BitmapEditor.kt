package com.xjyzs.operator.remote.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object BitmapEditor {

    /**
     * 在 Bitmap 指定位置绘制圆点
     *
     * @param x 圆心 X 坐标
     * @param y 圆心 Y 坐标
     * @param radius 半径（像素）
     * @param color 颜色，例如 Color.RED
     */
    fun drawDot(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        val paint = Paint().apply {
            this.color = color
            this.style = Paint.Style.FILL
            this.isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius, paint)
    }

    /**
     * 在 Bitmap 上绘制带箭头的直线
     *
     * @param x1 起点 X
     * @param y1 起点 Y
     * @param x2 终点 X（箭头指向该位置）
     * @param y2 终点 Y（箭头指向该位置）
     * @param color 箭头与线条颜色
     * @param strokeWidth 线条宽度
     */
    fun drawArrowLine(
        canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, strokeWidth: Float
    ) {
        val linePaint = Paint().apply {
            this.color = color
            this.strokeWidth = strokeWidth
            this.style = Paint.Style.STROKE
            this.isAntiAlias = true
        }
        canvas.drawLine(x1, y1, x2, y2, linePaint)
        val arrowLength = strokeWidth * 4f
        val arrowAngle = Math.toRadians(30.0)
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val x3 = (x2 - arrowLength * cos(angle - arrowAngle)).toFloat()
        val y3 = (y2 - arrowLength * sin(angle - arrowAngle)).toFloat()

        val x4 = (x2 - arrowLength * cos(angle + arrowAngle)).toFloat()
        val y4 = (y2 - arrowLength * sin(angle + arrowAngle)).toFloat()
        val arrowPaint = Paint().apply {
            this.color = color
            this.style = Paint.Style.FILL
            this.isAntiAlias = true
        }
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(x3, y3)
            lineTo(x4, y4)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }
}