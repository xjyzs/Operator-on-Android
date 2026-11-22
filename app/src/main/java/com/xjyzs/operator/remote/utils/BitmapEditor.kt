package com.xjyzs.operator.remote.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object BitmapEditor {

    private const val INNER_COLOR = 0xE6FF007F.toInt()
    private const val OUTER_COLOR = 0xE6FFFFFF.toInt()

    /**
     * 绘制点击标记：按 dp 比例自适应大小，不随平板横屏放大
     * @param pxPerDp 目标截图中的 1dp 对应的像素数
     */
    fun drawTapReticle(canvas: Canvas, cx: Float, cy: Float, pxPerDp: Float) {
        // 半径约 7.5dp，限制在 12px ~ 16px 之间（直径 24~32px）
        val radius = (7.5f * pxPerDp).coerceIn(12f, 16f)
        val tickLen = radius * 0.45f
        // 线条变纤细，减少对小文字（如 192.168）的遮挡
        val innerStroke = (1.3f * pxPerDp).coerceIn(2.2f, 3.0f)
        val outerStroke = innerStroke + (1.3f * pxPerDp).coerceIn(2.2f, 2.8f)

        val path = Path().apply {
            addCircle(cx, cy, radius, Path.Direction.CW)
            moveTo(cx, cy - radius)
            lineTo(cx, cy - radius - tickLen)
            moveTo(cx, cy + radius)
            lineTo(cx, cy + radius + tickLen)
            moveTo(cx - radius, cy)
            lineTo(cx - radius - tickLen, cy)
            moveTo(cx + radius, cy)
            lineTo(cx + radius + tickLen, cy)
        }

        drawDualStrokePath(canvas, path, outerStroke, innerStroke)
    }

    /**
     * 绘制滑动标记
     */
    fun drawSwipeArrow(
        canvas: Canvas,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        pxPerDp: Float
    ) {
        val baseRadius = (7.5f * pxPerDp).coerceIn(12f, 16f)
        val startRadius = baseRadius * 0.55f
        val headLen = baseRadius * 1.0f
        val innerStroke = (1.3f * pxPerDp).coerceIn(2.2f, 3.0f)
        val outerStroke = innerStroke + (1.3f * pxPerDp).coerceIn(2.2f, 2.8f)

        val dx = x2 - x1
        val dy = y2 - y1
        val dist = hypot(dx, dy)

        val path = Path().apply {
            addCircle(x1, y1, startRadius, Path.Direction.CW)

            if (dist > startRadius * 1.2f) {
                val angle = atan2(dy.toDouble(), dx.toDouble())
                val lineStartX = x1 + (startRadius * cos(angle)).toFloat()
                val lineStartY = y1 + (startRadius * sin(angle)).toFloat()

                moveTo(lineStartX, lineStartY)
                lineTo(x2, y2)

                val wingAngle = PI / 6.0
                val leftX = x2 - (headLen * cos(angle - wingAngle)).toFloat()
                val leftY = y2 - (headLen * sin(angle - wingAngle)).toFloat()
                val rightX = x2 - (headLen * cos(angle + wingAngle)).toFloat()
                val rightY = y2 - (headLen * sin(angle + wingAngle)).toFloat()

                moveTo(leftX, leftY)
                lineTo(x2, y2)
                lineTo(rightX, rightY)
            }
        }

        drawDualStrokePath(canvas, path, outerStroke, innerStroke)
    }

    private fun drawDualStrokePath(
        canvas: Canvas,
        path: Path,
        outerStroke: Float,
        innerStroke: Float
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        paint.color = OUTER_COLOR
        paint.strokeWidth = outerStroke
        canvas.drawPath(path, paint)

        paint.color = INNER_COLOR
        paint.strokeWidth = innerStroke
        canvas.drawPath(path, paint)
    }
}