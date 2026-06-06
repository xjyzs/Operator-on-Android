package com.xjyzs.operator.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.createBitmap
import com.xjyzs.operator.height
import com.xjyzs.operator.width
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

object Screenshot {
    init {
        System.loadLibrary("native-lib")
    }

    private external fun scaleImageJNI(
        srcArray: ByteArray, srcW: Int, srcH: Int, dstArray: ByteArray, dstW: Int, dstH: Int
    )

    private var suProcess: Process? = null
    private var os: OutputStream? = null
    private var bis: BufferedInputStream? = null

    private var rawBuffer = ByteArray(0)
    private var scaledBuffer = ByteArray(0)

    private fun initProcess() {
        if (suProcess == null) {
            suProcess = Runtime.getRuntime().exec("su")
            os = suProcess!!.outputStream
            bis = BufferedInputStream(suProcess!!.inputStream, 8 * 1024 * 1024)
        }
    }

    // 缓存 SurfaceFlinger Display ID，避免每次截图都执行 dumpsys
    private var cachedSfDisplayId: String? = null
    private var cachedForDisplayId: Int? = null

    fun clearSfDisplayIdCache() {
        cachedSfDisplayId = null
        cachedForDisplayId = null
    }

    private suspend fun getSurfaceFlingerDisplayId(displayId: Int): String? {
        // 优先使用缓存
        if (cachedForDisplayId == displayId && cachedSfDisplayId != null) {
            return cachedSfDisplayId
        }
        try {
            val executor = SuExecutor.getInstance()
            val result = executor.execute("dumpsys SurfaceFlinger --display-id")
            if (!result.isSuccess) {
                Log.w("Screenshot", "dumpsys SurfaceFlinger 失败: ${result.stderr}")
                return null
            }
            val lines = result.stdout.lines()
            for (line in lines) {
                if (line.contains("Virtual display") && line.contains("OperatorVirtualDisplay")) {
                    val match = Regex("""Display (\d+)""").find(line)
                    if (match != null) {
                        val sfId = match.groupValues[1]
                        // 缓存结果
                        cachedSfDisplayId = sfId
                        cachedForDisplayId = displayId
                        return sfId
                    }
                }
            }
            Log.w("Screenshot", "在 SurfaceFlinger 输出中未找到 OperatorVirtualDisplay")
        } catch (e: Exception) {
            Log.e("Screenshot", "获取 SurfaceFlinger Display ID 异常", e)
        }
        return null
    }

    suspend fun screenshot(mFloatingView: View, displayId: Int? = null): String {
        // 1. 将悬浮窗移出屏幕
        withContext(Dispatchers.Main) { mFloatingView.translationX += 114514f }

        var success = false
        var pixelCount = 0

        try {
            withContext(Dispatchers.IO) {
                try {
                    initProcess()

                    while ((bis?.available() ?: 0) > 0) {
                        bis?.read()
                    }

                    // 获取 SurfaceFlinger Display ID，带重试逻辑
                    var sfId: String? = null
                    if (displayId != null) {
                        for (attempt in 1..3) {
                            sfId = getSurfaceFlingerDisplayId(displayId)
                            if (sfId != null) break
                            Log.w("Screenshot", "获取 SF Display ID 失败, 重试 $attempt/3")
                            if (attempt < 3) {
                                clearSfDisplayIdCache()
                                delay(500)
                            }
                        }
                        if (sfId == null) {
                            Log.e("Screenshot", "虚拟屏截图失败: 无法获取 SurfaceFlinger Display ID, 拒绝回退到主屏")
                            // 移除此处手动减去 114514f 的逻辑，直接退出当前 withContext 即可
                            return@withContext
                        }
                    }

                    val command =
                        if (sfId != null) "screencap -d $sfId 2>/dev/null\n" else "screencap 2>/dev/null\n"
                    os?.write(command.toByteArray())
                    os?.flush()

                    val headerSize = if (Build.VERSION.SDK_INT >= 29) 16 else 12
                    val header = ByteArray(headerSize)
                    var readHeader = 0
                    while (readHeader < 12) {
                        val r = bis?.read(header, readHeader, 12 - readHeader) ?: -1
                        readHeader += r
                    }

                    val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                    width = headerBuffer.int
                    height = headerBuffer.int
                    headerBuffer.int

                    pixelCount = width * height * 4
                    if (rawBuffer.size < pixelCount) {
                        rawBuffer = ByteArray(pixelCount)
                    }

                    var readTotal = 0
                    while (readTotal < pixelCount) {
                        val read = bis?.read(rawBuffer, readTotal, pixelCount - readTotal) ?: -1
                        if (read == -1) break
                        readTotal += read
                    }

                    if (readTotal == pixelCount) success = true
                } catch (e: Exception) {
                    // 如果是协程取消异常，主动抛出以配合外层 try-finally
                    if (e is CancellationException) throw e
                    e.printStackTrace()
                    suProcess?.destroy()
                    suProcess = null
                    os = null
                    bis = null
                }
            }
        } finally {
            // 使用 NonCancellable 保证即使协程被取消，复位逻辑也一定会执行，且只执行一次
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    mFloatingView.translationX -= 114514f
                }
            }
        }

        if (!success) return ""

        return withContext(Dispatchers.Default) {
            try {
                val targetShortEdge = 720
                val shortEdge = min(width, height)
                val targetW: Int
                val targetH: Int

                if (shortEdge > targetShortEdge) {
                    val ratio = targetShortEdge.toFloat() / shortEdge
                    targetW = (width * ratio).toInt()
                    targetH = (height * ratio).toInt()
                } else {
                    targetW = width
                    targetH = height
                }

                val finalBitmap = createBitmap(targetW, targetH)

                if (targetW != width || targetH != height) {
                    val scaledPixelCount = targetW * targetH * 4
                    if (scaledBuffer.size < scaledPixelCount) {
                        scaledBuffer = ByteArray(scaledPixelCount)
                    }
                    scaleImageJNI(rawBuffer, width, height, scaledBuffer, targetW, targetH)
                    finalBitmap.copyPixelsFromBuffer(
                        ByteBuffer.wrap(
                            scaledBuffer, 0, scaledPixelCount
                        )
                    )
                } else {
                    finalBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rawBuffer, 0, pixelCount))
                }

                val baos = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                finalBitmap.recycle()

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "screenshot_$timeStamp.jpg"

                // 2. 在私有目录创建文件（context.filesDir 指向 /data/user/0/包名/files/）
                val file = File("/data/user/0/com.xjyzs.operator/", fileName)
                file.writeBytes(baos.toByteArray())

                return@withContext "data:image/jpeg;base64," + Base64.encodeToString(
                    baos.toByteArray(), Base64.NO_WRAP
                )
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext ""
            }
        }
    }
}

suspend fun getCurrentApp(displayId: Int? = null): String {
    val command =
        if (displayId != null) "dumpsys activity activities | awk -v id=\"$displayId\" '\$0 ~ \"Display #\" id { flag = 1; next } \$0 ~ \"Display #[0-9]+\" { flag = 0 } flag && /ActivityRecord/ { print; exit }' | grep -oE '[a-zA-Z0-9._]+\\/[a-zA-Z0-9._]+' | head -n 1 | cut -d'/' -f1"
        else "dumpsys window | grep mCurrentFocus | grep -oE '[a-zA-Z0-9._]+\\/[a-zA-Z0-9._]+' | head -n 1 | cut -d'/' -f1"
    try {
        val executor = SuExecutor.getInstance()
        val result = executor.execute(command)
        val packageName = result.stdout.trim().ifEmpty { null }
        return getAppName(packageName ?: "系统桌面")
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return getAppName("系统桌面")
}

suspend fun operation(
    action: String, args: String, context: Context, mFloatingView: View, displayId: Int? = null
) {
    try {
        when (action) {
            "Launch" -> launch(args, context, displayId)
            "Tap" -> tap(context, args, mFloatingView, displayId)
            "Type" -> type(args, displayId)
            "Swipe" -> swipe(context, args, mFloatingView, displayId)
            "Back" -> back(displayId)
            "Home" -> home(displayId)
            "Long Press" -> longPress(context, args, mFloatingView, displayId)
            "Double Tap" -> doubleTap(context, args, mFloatingView, displayId)
            "Wait" -> wait(args)
        }
    } catch (_: Exception) {
    }
}

suspend fun launch(args: String, context: Context, displayId: Int? = null) {
    val re = Regex("""app\s*=\s*"(?<appName>[^"\x5C]*(?:\\.[^"\x5C]*)*)"""")
    val appName = re.find(args)?.groups?.get("appName")?.value?.unescapeJava() ?: return
    val packageName = getPackageName(appName)

    if (displayId != null) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val componentStr = launchIntent?.component?.flattenToString()
        if (componentStr != null) {
            val command =
                "cmd activity start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $componentStr --display $displayId"
            SuExecutor.getInstance().execute(command)
        }
    } else {
        SuExecutor.getInstance()
            .execute("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }
    updatePriorityMapping(packageName, appName)
}

suspend fun tap(context: Context, args: String, mFloatingView: View, displayId: Int? = null) {
    context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
    waitForTouchThroughEnabled(mFloatingView)
    val re = Regex("""\[\s*(?<x>\d+)\s*,\s*(?<y>\d+)\s*]""")
    val x = re.find(args)!!.groups["x"]!!.value.toInt() / 1000f * width
    val y = re.find(args)!!.groups["y"]!!.value.toInt() / 1000f * height
    val command = if (displayId != null) "input -d $displayId tap $x $y" else "input tap $x $y"
    SuExecutor.getInstance().execute(command)
    context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun type(args: String, displayId: Int? = null) {
    val re = Regex(
        """text\s*=\s*"(?<txt>[^"\x5C]*(?:\\.[^"\x5C]*)*)"""", setOf(RegexOption.DOT_MATCHES_ALL)
    )
    val txt = re.find(args)?.groups?.get("txt")?.value?.unescapeJava() ?: return
    SuExecutor.getInstance().execute("am broadcast -a ADB_CLEAR_TEXT")
    delay(200)
    SuExecutor.getInstance().execute(
        "am broadcast -a ADB_INPUT_B64 --es msg ${
            Base64.encodeToString(
                txt.toByteArray(), Base64.NO_WRAP
            )
        }"
    )
}

suspend fun swipe(context: Context, args: String, mFloatingView: View, displayId: Int? = null) {
    context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
    waitForTouchThroughEnabled(mFloatingView)
    val re =
        Regex("""\[\s*(?<x1>\d+)\s*,\s*(?<y1>\d+)\s*].*?\[\s*(?<x2>\d+)\s*,\s*(?<y2>\d+)\s*]""")
    val x1 = re.find(args)!!.groups["x1"]!!.value.toInt() / 1000f * width
    val y1 = re.find(args)!!.groups["y1"]!!.value.toInt() / 1000f * height
    val x2 = re.find(args)!!.groups["x2"]!!.value.toInt() / 1000f * width
    val y2 = re.find(args)!!.groups["y2"]!!.value.toInt() / 1000f * height
    val dist_sq = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    val duration_ms = max(1000f, min(dist_sq / 1000, 2000f)).toLong()
    val command =
        if (displayId != null) "input -d $displayId swipe $x1 $y1 $x2 $y2 $duration_ms" else "input swipe $x1 $y1 $x2 $y2 $duration_ms"
    SuExecutor.getInstance().execute(command)
    delay(duration_ms)
    context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun back(displayId: Int? = null) {
    val command = if (displayId != null) "input -d $displayId keyevent 4" else "input keyevent 4"
    SuExecutor.getInstance().execute(command)
}

suspend fun home(displayId: Int? = null) {
    val command =
        if (displayId != null) "input -d $displayId keyevent KEYCODE_HOME" else "input keyevent KEYCODE_HOME"
    SuExecutor.getInstance().execute(command)
}

suspend fun longPress(context: Context, args: String, mFloatingView: View, displayId: Int? = null) {
    context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
    waitForTouchThroughEnabled(mFloatingView)
    val re = Regex("""\[\s*(?<x>\d+)\s*,\s*(?<y>\d+)\s*]""")
    val x = re.find(args)!!.groups["x"]!!.value.toInt() / 1000f * width
    val y = re.find(args)!!.groups["y"]!!.value.toInt() / 1000f * height
    val command =
        if (displayId != null) "input -d $displayId swipe $x $y $x $y 3000" else "input swipe $x $y $x $y 3000"
    val executor = SuExecutor.getInstance()
    executor.execute(command)
    delay(3000)
    context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun doubleTap(context: Context, args: String, mFloatingView: View, displayId: Int? = null) {
    context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
    waitForTouchThroughEnabled(mFloatingView)
    val re = Regex("""\[\s*(?<x>\d+)\s*,\s*(?<y>\d+)\s*]""")
    val x = re.find(args)!!.groups["x"]!!.value.toInt() / 1000f * width
    val y = re.find(args)!!.groups["y"]!!.value.toInt() / 1000f * height
    val command = if (displayId != null) "input -d $displayId tap $x $y" else "input tap $x $y"
    val executor = SuExecutor.getInstance()
    executor.execute(command)
    delay(200)
    executor.execute(command)
    context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun wait(args: String) {
    val re = Regex(
        """duration\s*=\s*"(?<duration>.*?)\s*sec(?:onds)?"""", setOf(RegexOption.IGNORE_CASE)
    )
    var tmp = re.find(args)?.groups?.get("duration")?.value ?: return
    if (tmp.last() == ' ') {
        tmp = tmp.dropLast(1)
    }
    delay((tmp.toFloat() * 1000).toLong())
}

suspend fun waitForTouchThroughEnabled(mFloatingView: View) {
    suspendCancellableCoroutine { cont ->
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if ((mFloatingView.layoutParams as WindowManager.LayoutParams).flags and FLAG_NOT_TOUCHABLE != 0) {
                    mFloatingView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    cont.resume(Unit) {}
                }
            }
        }
        mFloatingView.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }
}

