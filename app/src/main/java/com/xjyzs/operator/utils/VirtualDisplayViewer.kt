package com.xjyzs.operator.utils

import android.annotation.SuppressLint
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
@SuppressLint("ClickableViewAccessibility")
@Composable
fun VirtualDisplayViewer(
    modifier: Modifier = Modifier,
    width: Int = 1080,
    height: Int = 1920,
    densityDpi: Int = DisplayMetrics.DENSITY_DEFAULT,
    onDisplayReady: (displayId: Int) -> Unit = {}
) {
    val displayIdState = remember { mutableIntStateOf(-1) }
    AndroidView(
        modifier = modifier
            .aspectRatio(width.toFloat() / height.toFloat())
            .background(Color.Black),
        factory = { context ->
            SurfaceView(context).apply {
                holder.setFixedSize(width, height)

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val id = InputControlUtils.createVirtualDisplay(width, height, densityDpi)
                        displayIdState.intValue = id
                        InputControlUtils.attachSurfaceToVirtualDisplay(holder.surface)
                        if (id != -1) onDisplayReady(id)
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        InputControlUtils.detachSurfaceFromVirtualDisplay()
                    }
                })

                setOnTouchListener { v, event ->
                    val displayId = displayIdState.intValue
                    if (displayId == -1) return@setOnTouchListener false
                    val scaleX = width.toFloat() / v.width.coerceAtLeast(1)
                    val scaleY = height.toFloat() / v.height.coerceAtLeast(1)

                    fun mapX(raw: Float) = (raw * scaleX).toInt().coerceIn(0, width - 1)
                    fun mapY(raw: Float) = (raw * scaleY).toInt().coerceIn(0, height - 1)

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> InputControlUtils.downSync(mapX(event.x), mapY(event.y), displayId)
                        MotionEvent.ACTION_MOVE -> InputControlUtils.moveSync(mapX(event.getX(0)), mapY(event.getY(0)), displayId)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> InputControlUtils.upSync(
                            mapX(event.getX(event.actionIndex)), mapY(event.getY(event.actionIndex)), displayId
                        )
                    }
                    true
                }
            }
        })
}