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
import androidx.core.graphics.createBitmap
import com.xjyzs.operator.height
import com.xjyzs.operator.width
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object Screenshot {
    init {
        System.loadLibrary("native-lib")
    }

    private external fun scaleImageJNI(
        srcArray: ByteArray, srcW: Int, srcH: Int,
        dstArray: ByteArray, dstW: Int, dstH: Int
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

    suspend fun screenshot(mFloatingView: View): String {
        mFloatingView.translationX += 114514f

        var success = false
        var pixelCount = 0

        withContext(Dispatchers.IO) {
            try {
                initProcess()

                while ((bis?.available() ?: 0) > 0) {
                    bis?.read()
                }

                os?.write("screencap 2>/dev/null\n".toByteArray())
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
                e.printStackTrace()
                suProcess?.destroy()
                suProcess = null
                os = null
                bis = null
            }
        }
        mFloatingView.translationX -= 114514f

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
                    finalBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(scaledBuffer, 0, scaledPixelCount))
                } else {
                    finalBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rawBuffer, 0, pixelCount))
                }

                val baos = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                finalBitmap.recycle()

                return@withContext "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext ""
            }
        }
    }
}

fun getCurrentApp(): String {
    val re = Regex("""Window\{.*? u.*? (?<packageName>.*?)/""")
    val process = Runtime.getRuntime().exec(
        arrayOf("su", "-c", "dumpsys window | grep mCurrentFocus")
    )
    val result = process.inputStream.bufferedReader().use {
        it.readText()
    }
    val packageName = re.find(result)?.groups?.get("packageName")?.value
    process.waitFor()
    return getAppName(packageName ?: "系统桌面")
}

suspend fun operation(action: String, args: String, context: Context, mFloatingView: View) {
    try {
        when (action) {
            "Launch" -> launch(args)
            "Tap" -> tap(context, args, mFloatingView)
            "Type" -> type(args)
            "Swipe" -> swipe(context, args, mFloatingView)
            "Back" -> back()
            "Home" -> home()
            "Long Press" -> longPress(context, args, mFloatingView)
            "Double Tap" -> doubleTap(context, args, mFloatingView)
            "Wait" -> wait(args)
        }
    } catch (_: Exception) {
    }
}

fun launch(args: String) {
    val re = Regex("""app\s*=\s*"(?<appName>[^"\x5C]*(?:\\.[^"\x5C]*)*)"""")
    val appName = re.find(args)?.groups?.get("appName")?.value?.unescapeJava() ?: return
    val packageName=getPackageName(appName)
    Runtime.getRuntime().exec(
        arrayOf(
            "su",
            "-c",
            "monkey -p ${packageName} -c android.intent.category.LAUNCHER 1"
        )
    )
    updatePriorityMapping(packageName,appName)
}

suspend fun tap(context: Context, args: String, mFloatingView: View) {
    context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
    waitForTouchThroughEnabled(mFloatingView)
    val re = Regex("""\[\s*(?<x>\d+)\s*,\s*(?<y>\d+)\s*]""")
    val x = re.find(args)!!.groups["x"]!!.value.toInt() / 1000f * width
    val y = re.find(args)!!.groups["y"]!!.value.toInt() / 1000f * height
    Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
    context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun type(args: String) {
    val re = Regex(
        """text\s*=\s*"(?<txt>[^"\x5C]*(?:\\.[^"\x5C]*)*)"""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    val txt = re.find(args)?.groups?.get("txt")?.value?.unescapeJava() ?: return
    Log.d("type", txt)
    Runtime.getRuntime().exec(arrayOf("su", "-c", "am broadcast -a ADB_CLEAR_TEXT"))
    delay(200)
    Runtime.getRuntime().exec(
        arrayOf(
            "su",
            "-c",
            "am broadcast -a ADB_INPUT_B64 --es msg ${
                Base64.encodeToString(
                    txt.toByteArray(),
                    Base64.NO_WRAP
                )
            }"
        )
    )

}

suspend fun swipe(context: Context, args: String, mFloatingView: View) {
    context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
    waitForTouchThroughEnabled(mFloatingView)
    val re = Regex("""\[\s*(?<x1>\d+)\s*,\s*(?<y1>\d+)\s*].*?\[\s*(?<x2>\d+)\s*,\s*(?<y2>\d+)\s*]""")
    val x1 = re.find(args)!!.groups["x1"]!!.value.toInt() / 1000f * width
    val y1 = re.find(args)!!.groups["y1"]!!.value.toInt() / 1000f * height
    val x2 = re.find(args)!!.groups["x2"]!!.value.toInt() / 1000f * width
    val y2 = re.find(args)!!.groups["y2"]!!.value.toInt() / 1000f * height
    val dist_sq = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    val duration_ms = max(1000f, min(dist_sq / 1000, 2000f)).toLong()
    Runtime.getRuntime().exec(arrayOf("su", "-c", "input swipe $x1 $y1 $x2 $y2 $duration_ms"))
    delay(duration_ms)
    context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

fun back() {
    Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 4"))
}

fun home() {
    Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent KEYCODE_HOME"))
}

suspend fun longPress(context: Context, args: String, mFloatingView: View) {
    context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
    waitForTouchThroughEnabled(mFloatingView)
    val re = Regex("""\[\s*(?<x>\d+)\s*,\s*(?<y>\d+)\s*]""")
    val x = re.find(args)!!.groups["x"]!!.value.toInt() / 1000f * width
    val y = re.find(args)!!.groups["y"]!!.value.toInt() / 1000f * height
    Runtime.getRuntime().exec(arrayOf("su", "-c", "input swipe $x $y $x $y 3000"))
    delay(3000)
    context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun doubleTap(context: Context, args: String, mFloatingView: View) {
    context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
    waitForTouchThroughEnabled(mFloatingView)
    val re = Regex("""\[\s*(?<x>\d+)\s*,\s*(?<y>\d+)\s*]""")
    val x = re.find(args)!!.groups["x"]!!.value.toInt() / 1000f * width
    val y = re.find(args)!!.groups["y"]!!.value.toInt() / 1000f * height
    Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
    delay(200)
    Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
    context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun wait(args: String) {
    val re = Regex("""duration\s*=\s*"(?<duration>.*?)\s*sec(?:onds)?"""", setOf(RegexOption.IGNORE_CASE))
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

