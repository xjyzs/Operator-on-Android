package com.xjyzs.operator.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.xjyzs.operator.remote.InputControlClient
import com.xjyzs.operator.remote.RemoteServiceLauncher
import java.util.concurrent.CountDownLatch
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

object InputControlUtils {
    @Volatile
    private var client: InputControlClient? = null

    @Volatile
    var displayId: Int = -1
        private set
    var vdWidth = 1080
    var vdHeight = 1920
    private var backgroundReader: ImageReader? = null

    // 截屏相关
    @Volatile
    private var screenshotRequest = false
    private var screenshotBitmap: Bitmap? = null
    private var screenshotLatch: CountDownLatch? = null

    fun init(context: Context) {
        if (client?.isAlive == true) return
        if (!RemoteServiceLauncher.isServiceAlive()) {
            val ok = RemoteServiceLauncher.launch(context)
            if (!ok) return
        }
        client = InputControlClient.connect(retries = 10, delayMs = 500)
    }

    fun release() {
        client?.destroy(); client = null
    }
    @Synchronized
    fun downSync(x: Int, y: Int, displayId: Int) {
        client?.downSync(x, y, displayId)
    }

    @Synchronized
    fun moveSync(x: Int, y: Int, displayId: Int) {
        client?.moveSync(x, y, displayId)
    }

    @Synchronized
    fun upSync(x: Int, y: Int, displayId: Int) {
        client?.upSync(x, y, displayId)
    }
    fun releaseVirtualDisplay(displayId: Int) {
        client?.releaseVirtualDisplay(displayId)
    }


    fun createVirtualDisplay(
        width: Int, height: Int, densityDpi: Int
    ): Int {
        if (displayId != -1 && backgroundReader != null) {
            client?.setVirtualDisplaySurface(displayId, backgroundReader!!.surface)
            return displayId
        }
        backgroundReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        if (screenshotRequest) {
                            screenshotBitmap = imageToBitmap(image)
                            screenshotRequest = false
                            screenshotLatch?.countDown()
                        }
                        image.close()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, Handler(Looper.getMainLooper()))
        }

        displayId = client?.createVirtualDisplay(
            backgroundReader!!.surface, width, height, densityDpi
        ) ?: -1
        if (displayId != -1) {
            vdWidth = width
            vdHeight = height
        }
        return displayId
    }

    /** 切换虚拟屏到前台 Compose 渲染 */
    fun attachSurfaceToVirtualDisplay(surface: Surface) {
        if (displayId != -1) client?.setVirtualDisplaySurface(
            displayId,
            surface
        )
    }

    /** Compose 退出时，把虚拟屏重新挂回后台，防止应用被杀 */
    fun detachSurfaceFromVirtualDisplay() {
        if (displayId != -1 && backgroundReader != null) {
            client?.setVirtualDisplaySurface(displayId, backgroundReader!!.surface)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        // 处理跨度可能包含的 Padding (内存对齐)
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = createBitmap(image.width + rowPadding / pixelStride, image.height)
        bitmap.copyPixelsFromBuffer(buffer)
        // 裁切掉 padding，返回纯净图像
        return if (rowPadding == 0) bitmap else Bitmap.createBitmap(
            bitmap,
            0,
            0,
            image.width,
            image.height
        )
    }

    /**
     * 3. 自然滑动注入 (非阻塞，后台协程自动分发点位)
     */
    fun swipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        displayId: Int,
        durationMs: Long = 450L
    ) {
        val random = Random.Default

        // 生成贝塞尔曲线控制点（模拟手指自然弧度）
        val midX = (startX + endX) / 2.0
        val midY = (startY + endY) / 2.0
        val dx = (endX - startX).toDouble()
        val dy = (endY - startY).toDouble()
        // 控制点偏移（垂直于滑动方向，模拟手指弧形运动）
        val perpX = -dy * (random.nextDouble(0.05, 0.15) * if (random.nextBoolean()) 1 else -1)
        val perpY = dx * (random.nextDouble(0.05, 0.15) * if (random.nextBoolean()) 1 else -1)
        val ctrl1X = midX * 0.4 + startX * 0.6 + perpX
        val ctrl1Y = midY * 0.4 + startY * 0.6 + perpY
        val ctrl2X = midX * 0.4 + endX * 0.6 + perpX * 0.5
        val ctrl2Y = midY * 0.4 + endY * 0.6 + perpY * 0.5
        // 确定采样步数（约 60fps 等效，步数随时长调整）
        val steps = (durationMs / 16).toInt().coerceIn(20, 200)
        // 按步执行滑动
        var lastX = startX
        var lastY = startY
        val startTime = System.currentTimeMillis()
        for (i in 0..steps) {
            val rawT = i.toDouble() / steps
            // 非线性时间映射（慢-快-慢，模拟人手加减速）
            val t = easeInOutHuman(rawT, random)
            // 三次贝塞尔曲线插值
            val bx = cubicBezier(t, startX.toDouble(), ctrl1X, ctrl2X, endX.toDouble())
            val by = cubicBezier(t, startY.toDouble(), ctrl1Y, ctrl2Y, endY.toDouble())
            // 叠加微小随机抖动（模拟手指颤动，越靠近中间越大）
            val jitterScale = sin(rawT * PI) * 1.5  // 两端抖动小，中间略大
            val jitterX = if (jitterScale > 1e-5) random.nextDouble(-jitterScale, jitterScale) else 0.0
            val jitterY = if (jitterScale > 1e-5) random.nextDouble(-jitterScale, jitterScale) else 0.0
            val jx = (bx + jitterX).roundToInt()
            val jy = (by + jitterY).roundToInt()
            if (i == 0) {
                downSync(jx, jy, displayId)
            } else {
                if (jx != lastX || jy != lastY) {
                    moveSync(jx, jy, displayId)
                }
            }
            lastX = jx
            lastY = jy

            // 8. 精确计时等待
            if (i < steps) {
                val elapsed = System.currentTimeMillis() - startTime
                val targetTime = (durationMs * (i + 1).toDouble() / steps).toLong()
                val sleepMs = targetTime - elapsed
                if (sleepMs > 0) Thread.sleep(sleepMs)
            }
        }

        // 确保最后落点精确
        upSync(endX, endY, displayId)
    }

    /**
     * 人类化单击
     * @param x 点击X坐标
     * @param y 点击Y坐标
     * @param displayId 显示ID
     */
    fun tap(x: Int, y: Int, displayId: Int) {
        val random = Random.Default
        val offsetX = x + random.nextInt(-2, 3)
        val offsetY = y + random.nextInt(-2, 3)
        downSync(offsetX, offsetY, displayId)
        val pressDuration = random.nextLong(50, 100)
        Thread.sleep(pressDuration)
        val releaseX = offsetX + random.nextInt(-1, 2)
        val releaseY = offsetY + random.nextInt(-1, 2)

        upSync(releaseX, releaseY, displayId)
    }

    /**
     * 人类化长按
     * @param x 长按X坐标
     * @param y 长按Y坐标
     * @param displayId 显示ID
     * @param holdDurationMs 长按持续时长（毫秒），默认 800ms
     */
    fun longPress(x: Int, y: Int, displayId: Int, holdDurationMs: Long = 800L) {
        val random = Random.Default

        val offsetX = x + random.nextInt(-2, 3)
        val offsetY = y + random.nextInt(-2, 3)

        downSync(offsetX, offsetY, displayId)

        // 长按期间模拟手指微颤（每 80~120ms 发送一次微移 moveSync）
        val startTime = System.currentTimeMillis()
        var curX = offsetX
        var curY = offsetY

        while (System.currentTimeMillis() - startTime < holdDurationMs) {
            val interval = random.nextLong(80, 121)
            Thread.sleep(interval)

            // 微颤偏移：±1 像素，且有 40% 概率不动
            if (random.nextFloat() > 0.4f) {
                curX += random.nextInt(-1, 2)
                curY += random.nextInt(-1, 2)
                // 漂移不能偏离太远，夹回原点附近 4px 范围
                curX = curX.coerceIn(x - 4, x + 4)
                curY = curY.coerceIn(y - 4, y + 4)
                moveSync(curX, curY, displayId)
            }
        }

        // 抬起
        upSync(curX, curY, displayId)
    }

    /**
     * 人类化双击
     * @param x 双击X坐标
     * @param y 双击Y坐标
     * @param displayId 显示ID
     */
    fun doubleTap(x: Int, y: Int, displayId: Int) {
        val random = Random.Default
        val tap1X = x + random.nextInt(-2, 3)
        val tap1Y = y + random.nextInt(-2, 3)
        downSync(tap1X, tap1Y, displayId)
        Thread.sleep(random.nextLong(45, 95))
        upSync(tap1X, tap1Y, displayId)
        // 点击间隔
        Thread.sleep(random.nextLong(80, 150))
        val tap2X = tap1X + random.nextInt(-3, 4)
        val tap2Y = tap1Y + random.nextInt(-3, 4)
        downSync(tap2X, tap2Y, displayId)
        Thread.sleep(random.nextLong(45, 95))
        upSync(tap2X, tap2Y, displayId)
    }

    /**
     * 三次贝塞尔曲线
     */
    fun cubicBezier(t: Double, p0: Double, p1: Double, p2: Double, p3: Double): Double {
        val mt = 1.0 - t
        return mt * mt * mt * p0 +
                3 * mt * mt * t * p1 +
                3 * mt * t * t * p2 +
                t * t * t * p3
    }

    /**
     * 模拟人类手速曲线：启动慢 → 中间快 → 结束慢，并加入轻微随机波动
     */
    fun easeInOutHuman(t: Double, random: Random): Double {
        // 基础 ease-in-out（smoothstep）
        val base = t * t * (3.0 - 2.0 * t)

        // 叠加轻微随机扰动（模拟真实手速不均匀）
        val noise = random.nextDouble(-0.012, 0.012) * sin(t * PI)
        return (base + noise).coerceIn(0.0, 1.0)
    }

    fun moveAppToDisplay(packageName: String, displayId: Int) {
        client?.moveAppToDisplay(packageName, displayId)
    }

    fun setSize(width: Int, height: Int) {
        client?.setSize(width, height)
    }

    fun captureScreen(displayId: Int, x1: Float?, y1: Float?, x2: Float?, y2: Float?): ParcelFileDescriptor? {
        return client?.captureScreen(displayId, x1 ?: -1f, y1 ?: -1f, x2 ?: -1f, y2 ?: -1f)
    }
}