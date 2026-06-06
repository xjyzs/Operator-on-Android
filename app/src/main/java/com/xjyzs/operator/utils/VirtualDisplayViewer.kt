package com.xjyzs.operator.utils

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.min

@Composable
fun VirtualDisplayViewer(
    modifier: Modifier = Modifier,
    width: Int = 1080,
    height: Int = 1920,
    densityDpi: Int = DisplayMetrics.DENSITY_HIGH,
    enableTouch: Boolean = false,
    onDisplayCreated: (displayId: Int) -> Unit = {},
    onDisplayDestroyed: () -> Unit = {}
) {
    val context = LocalContext.current
    val bufferWidth = remember { width }
    val bufferHeight = remember { height }

    val touchModifier = if (enableTouch) {
        Modifier.pointerInput(bufferWidth, bufferHeight) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue

                    val displayId = VirtualDisplayManager.getDisplayId()
                    if (displayId == null) {
                        change.consume()
                        continue
                    }

                    val viewW = size.width.toFloat()
                    val viewH = size.height.toFloat()
                    val scale = min(viewW / bufferWidth, viewH / bufferHeight)
                    val offsetX = (viewW - bufferWidth * scale) / 2f
                    val offsetY = (viewH - bufferHeight * scale) / 2f

                    val vx = ((change.position.x - offsetX) / scale).toInt()
                    val vy = ((change.position.y - offsetY) / scale).toInt()

                    if (vx < 0 || vx >= bufferWidth || vy < 0 || vy >= bufferHeight) {
                        change.consume()
                        continue
                    }

                    when (event.type) {
                        PointerEventType.Press -> InputControlUtils.downSync(vx, vy, displayId)
                        PointerEventType.Move -> if (change.pressed) InputControlUtils.moveSync(vx, vy, displayId)
                        PointerEventType.Release -> InputControlUtils.upSync(vx, vy, displayId)
                    }
                    change.consume()
                }
            }
        }
    } else {
        Modifier
    }

    AndroidView(
        modifier = modifier.then(touchModifier),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
                        surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
                        val sf = Surface(surfaceTexture)

                        val existingDisplay = VirtualDisplayManager.getVirtualDisplay()
                        if (existingDisplay != null) {
                            existingDisplay.surface = sf
                            onDisplayCreated(existingDisplay.display.displayId)
                        } else {
                            val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                            val vd = displayManager.createVirtualDisplay(
                                "OperatorVirtualDisplay", bufferWidth, bufferHeight, densityDpi, sf,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or 256 or 512 or 1024
                            )
                            VirtualDisplayManager.setVirtualDisplay(vd)
                            onDisplayCreated(vd.display.displayId)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        onDisplayDestroyed()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        }
    )
}
