package com.xjyzs.operator.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SharedMemory
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import com.xjyzs.operator.FloatingWindowService
import com.xjyzs.operator.dpi
import com.xjyzs.operator.height
import com.xjyzs.operator.width
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

var lastX1 = -1f
var lastY1 = -1f
var lastX2 = -1f
var lastY2 = -1f

suspend fun screenshot(mFloatingView: View, displayId: Int? = null): String {
    var savedTranslationX = 0f
    if (displayId == null || displayId == 0) {
        val dm = DisplayMetrics()
        mFloatingView.display.getRealMetrics(dm)
        width = dm.widthPixels
        height = dm.heightPixels
        dpi = dm.densityDpi
        InputControlUtils.setSize(width, height)
        savedTranslationX = withContext(Dispatchers.Main) {
            val saved = mFloatingView.translationX
            mFloatingView.translationX = 114514f
            saved
        }
        waitForViewGone(mFloatingView)
        awaitFrame()
        awaitFrame()
    }
    val pfd = withContext(Dispatchers.IO) {
        InputControlUtils.captureScreen(displayId ?: 0, lastX1, lastY1, lastX2, lastY2)
    } ?: return ""
    if (displayId == null || displayId == 0) withContext(NonCancellable) {
        withContext(Dispatchers.Main) {
            mFloatingView.translationX = savedTranslationX
        }
    }
    return withContext(Dispatchers.Default) {
        try {
            val sharedMemory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SharedMemory.fromFileDescriptor(pfd)
            } else {
                val constructor =
                    SharedMemory::class.java.getDeclaredConstructor(FileDescriptor::class.java)
                        .apply { isAccessible = true }
                constructor.newInstance(pfd.fileDescriptor)
            }
            val buffer = sharedMemory.mapReadOnly()
            val bytes = ByteArray(sharedMemory.size)
            buffer.get(bytes)
            SharedMemory.unmap(buffer)
            sharedMemory.close()
            if (bytes.isEmpty()) return@withContext ""
//            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
//            val fileName = "screenshot_$timeStamp.jpg"
//            val file = File("/data/user/10/com.xjyzs.operator/", fileName)
//            file.parentFile?.mkdirs()
//            file.writeBytes(bytes)

            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        } finally {
            pfd.close()
        }
    }
}

suspend fun getCurrentPkg(displayId: Int? = null): String {
    val command =
        if (displayId != null) "dumpsys activity activities | awk -v id=\"$displayId\" '\$0 ~ \"Display #\" id { flag = 1; next } \$0 ~ \"Display #[0-9]+\" { flag = 0 } flag && /ActivityRecord/ { print; exit }' | grep -oE '[a-zA-Z0-9._]+\\/[a-zA-Z0-9._]+' | head -n 1 | cut -d'/' -f1"
        else "dumpsys window | grep mCurrentFocus | grep -oE '[a-zA-Z0-9._]+\\/[a-zA-Z0-9._]+' | head -n 1 | cut -d'/' -f1"
    try {
        val executor = SuExecutor.getInstance()
        val result = executor.execute(command)
        val packageName = result.stdout.trim().ifEmpty { null }
        return packageName ?: "系统桌面"
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "系统桌面"
}

suspend fun operation(
    action: String, args: String, context: Context, mFloatingView: View, displayId: Int? = null
) {
    try {
        when (action) {
            "Launch" -> launch(args, displayId)
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

suspend fun launch(args: String, displayId: Int? = null) {
    lastX1 = -1f
    lastY1 = -1f
    lastX2 = -1f
    lastY2 = -1f
    val re = Regex("""app\s*=\s*"(?<appName>[^"\x5C]*(?:\\.[^"\x5C]*)*)"""")
    val appName = re.find(args)?.groups?.get("appName")?.value?.unescapeJava() ?: return
    val packageName = getPackageName(appName)

    if (displayId != null) InputControlUtils.moveAppToDisplay(packageName, displayId)
    else SuExecutor.getInstance()
        .execute("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    delay(400.milliseconds) // 等待应用启动
    updatePriorityMapping(packageName, appName)
}

suspend fun tap(context: Context, args: String, mFloatingView: View, displayId: Int? = null) {
    if (displayId == null || displayId == 0) {
        context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
        waitForTouchThroughEnabled(mFloatingView)
    }
    val re = Regex("""\[\s*(?<x>\d+)\s*,\s*(?<y>\d+)\s*]""")
    lastX1 = re.find(args)!!.groups["x"]!!.value.toFloat()
    lastY1 = re.find(args)!!.groups["y"]!!.value.toFloat()
    val x =
        lastX1 / 1000 * if (displayId == null || displayId == 0) width else InputControlUtils.vdWidth
    val y =
        lastY1 / 1000 * if (displayId == null || displayId == 0) height else InputControlUtils.vdHeight
    lastX2 = -1f
    lastY2 = -1f
    if (displayId == null || displayId == 0) SuExecutor.getInstance().execute("input tap $x $y")
    else InputControlUtils.tap(x.toInt(), y.toInt(), displayId)
    if (displayId == null || displayId == 0) context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun type(args: String, displayId: Int? = null) {
    lastX1 = -1f
    lastY1 = -1f
    lastX2 = -1f
    lastY2 = -1f
    val re = Regex(
        """text\s*=\s*"(?<txt>[^"\x5C]*(?:\\.[^"\x5C]*)*)"""", setOf(RegexOption.DOT_MATCHES_ALL)
    )
    val txt = re.find(args)?.groups?.get("txt")?.value?.unescapeJava() ?: return
    if (displayId == null || displayId == 0) {
        val suInstance = SuExecutor.getInstance()
        suInstance.execute("am broadcast -a ADB_CLEAR_TEXT")
        delay(200)
        suInstance.execute(
            "am broadcast -a ADB_INPUT_B64 --es msg ${
                Base64.encodeToString(txt.toByteArray(), Base64.NO_WRAP)
            }"
        )
    } else FloatingWindowService.performAutoInput(txt, displayId)
}

suspend fun swipe(context: Context, args: String, mFloatingView: View, displayId: Int? = null) {
    if (displayId == null || displayId == 0) {
        context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
        waitForTouchThroughEnabled(mFloatingView)
    }
    val re =
        Regex("""\[\s*(?<x1>\d+)\s*,\s*(?<y1>\d+)\s*].*?\[\s*(?<x2>\d+)\s*,\s*(?<y2>\d+)\s*]""")
    lastX1 = re.find(args)!!.groups["x1"]!!.value.toFloat()
    lastY1 = re.find(args)!!.groups["y1"]!!.value.toFloat()
    lastX2 = re.find(args)!!.groups["x2"]!!.value.toFloat()
    lastY2 = re.find(args)!!.groups["y2"]!!.value.toFloat()
    val x1 =
        lastX1 / 1000 * if (displayId == null || displayId == 0) width else InputControlUtils.vdWidth
    val y1 =
        lastY1 / 1000 * if (displayId == null || displayId == 0) height else InputControlUtils.vdHeight
    val x2 =
        lastX2 / 1000 * if (displayId == null || displayId == 0) width else InputControlUtils.vdWidth
    val y2 =
        lastY2 / 1000 * if (displayId == null || displayId == 0) height else InputControlUtils.vdHeight
    val dist_sq = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    val duration_ms = max(1000f, min(dist_sq / 1000, 2000f)).toLong()
    if (displayId == null || displayId == 0) SuExecutor.getInstance()
        .execute("input swipe $x1 $y1 $x2 $y2 $duration_ms")
    else {
        InputControlUtils.swipe(
            x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), displayId, duration_ms
        )
    }
    if (displayId == null || displayId == 0) context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
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

suspend fun longPress(
    context: Context, args: String, mFloatingView: View, displayId: Int? = null
) {
    if (displayId == null || displayId == 0) {
        context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
        waitForTouchThroughEnabled(mFloatingView)
    }
    val re = Regex("""\[\s*(?<x>\d+)\s*,\s*(?<y>\d+)\s*]""")
    lastX1 = re.find(args)!!.groups["x"]!!.value.toFloat()
    lastY1 = re.find(args)!!.groups["y"]!!.value.toFloat()
    lastX2 = -1f
    lastY2 = -1f
    val x =
        lastX1 / 1000 * if (displayId == null || displayId == 0) width else InputControlUtils.vdWidth
    val y =
        lastY1 / 1000 * if (displayId == null || displayId == 0) height else InputControlUtils.vdHeight
    if (displayId == null || displayId == 0) SuExecutor.getInstance()
        .execute("input swipe $x $y $x $y 1800")
    else {
        InputControlUtils.longPress(
            x.toInt(), y.toInt(), displayId, 1800
        )
    }
    if (displayId == null || displayId == 0) context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun doubleTap(
    context: Context, args: String, mFloatingView: View, displayId: Int? = null
) {
    if (displayId == null || displayId == 0) {
        context.sendBroadcast(Intent("ACTION_ENABLE_TOUCH_THROUGH"))
        waitForTouchThroughEnabled(mFloatingView)
    }
    val re = Regex("""\[\s*(?<x>\d+)\s*,\s*(?<y>\d+)\s*]""")
    lastX1 = re.find(args)!!.groups["x"]!!.value.toFloat()
    lastY1 = re.find(args)!!.groups["y"]!!.value.toFloat()
    lastX2 = -1f
    lastY2 = -1f
    val x =
        lastX1 / 1000 * if (displayId == null || displayId == 0) width else InputControlUtils.vdWidth
    val y =
        lastY1 / 1000 * if (displayId == null || displayId == 0) height else InputControlUtils.vdHeight
    if (displayId == null || displayId == 0) {
        SuExecutor.getInstance().execute("input tap $x $y")
        delay(100)
        SuExecutor.getInstance().execute("input tap $x $y")
    } else {
        InputControlUtils.doubleTap(
            x.toInt(),
            y.toInt(),
            displayId,
        )
    }
    if (displayId == null || displayId == 0) context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun wait(args: String) {
    val re = Regex(
        """duration\s*=\s*"(?<duration>.*?)\s*sec(?:onds)?"""", setOf(RegexOption.IGNORE_CASE)
    )
    var tmp = re.find(args)?.groups?.get("duration")?.value ?: return
    if (tmp.last() == ' ') {
        tmp = tmp.dropLast(1)
    }
    delay(((tmp.toFloat() * 1000).toLong() - 2000).coerceIn(1, 0x3f3f3f3f))
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

suspend fun waitForViewGone(view: View) {
    suspendCancellableCoroutine { cont ->
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.translationX == 114514f) {
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    if (cont.isActive) {
                        cont.resume(Unit) {}
                    }
                }
                return true
            }
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        cont.invokeOnCancellation {
            view.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }
}

suspend fun awaitFrame() = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { cont ->
        Choreographer.getInstance().postFrameCallback {
            if (cont.isActive) {
                cont.resume(Unit) {}
            }
        }
    }
}