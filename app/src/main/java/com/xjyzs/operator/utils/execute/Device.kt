package com.xjyzs.operator.utils.execute

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import com.xjyzs.operator.FloatingWindowService
import com.xjyzs.operator.utils.data.DisplayMetricsStore
import com.xjyzs.operator.utils.InputControlUtils
import com.xjyzs.operator.utils.data.SharedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

// 采用 0-999 坐标
var lastX1 = -1f
var lastY1 = -1f
var lastX2 = -1f
var lastY2 = -1f

suspend fun screenshot(displayId: Int? = null): String {
    val isMainScreen = (displayId == null || displayId == 0)
    if (isMainScreen) {
        val dm = DisplayMetrics()
        SharedState.appContext.getSystemService(DisplayManager::class.java).getDisplay(0)
            .getRealMetrics(dm)
    }

    val capture: suspend () -> ParcelFileDescriptor? = {
        withContext(Dispatchers.IO) {
            InputControlUtils.captureScreen(displayId ?: 0, lastX1, lastY1, lastX2, lastY2)
        }
    }
    val pfd = (if (isMainScreen) SharedState.withOverlayHidden(capture) else capture()) ?: return ""

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
        val executor = ShellExecutor.getInstance()
        val result = executor.execute(command)
        val packageName = result.stdout.trim().ifEmpty { null }
        return packageName ?: "系统桌面\n请先用Launch启动应用"
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "系统桌面\n请先用Launch启动应用"
}

suspend fun tap(context: Context, x: Float, y: Float, displayId: Int? = null) {
    if (x > 999 || y > 999) throw IllegalArgumentException("坐标范围应在[0,999]内")
    val isMainScreen = (displayId == null || displayId == 0)
    lastX1 = x
    lastY1 = y
    val finalX = toRealX(x)
    val finalY = toRealY(y)
    lastX2 = -1f; lastY2 = -1f
    val doOperation: suspend () -> Unit = {
        ShellExecutor.getInstance().execute("input tap $finalX $finalY")
    }
    if (isMainScreen) SharedState.withTouchThroughEnabled(doOperation)
    else InputControlUtils.tap(finalX.toInt(), finalY.toInt(), displayId)
    if (isMainScreen) context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun swipe(
    context: Context, x1: Float, y1: Float, x2: Float, y2: Float, displayId: Int? = null
) {
    if (x1 > 999 || y1 > 999 || x2 > 999 || y2 > 999) throw IllegalArgumentException("坐标范围应在[0,999]内")
    val isMainScreen = (displayId == null || displayId == 0)
    lastX1 = x1
    lastY1 = y1
    lastX2 = x2
    lastY2 = y2
    val x1 = toRealX(x1)
    val y1 = toRealY(y1)
    val x2 = toRealX(x2)
    val y2 = toRealY(y2)
    val distSq = (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    val durationMs = max(1000f, min(distSq / 1000, 2000f)).toLong()
    val doOperation: suspend () -> Unit = {
        ShellExecutor.getInstance().execute("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }
    if (isMainScreen) SharedState.withTouchThroughEnabled(doOperation)
    else {
        InputControlUtils.swipe(
            x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), displayId, durationMs
        )
    }
    if (isMainScreen) context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun type(txt: String, displayId: Int? = null) {
    lastX1 = -1f; lastY1 = -1f; lastX2 = -1f; lastY2 = -1f
    if (displayId == null || displayId == 0) {
        val suInstance = ShellExecutor.getInstance()
        suInstance.execute("am broadcast -a ADB_CLEAR_TEXT")
        delay(200.milliseconds)
        suInstance.execute(
            "am broadcast -a ADB_INPUT_B64 --es msg ${
                Base64.encodeToString(txt.toByteArray(), Base64.NO_WRAP)
            }"
        )
    } else FloatingWindowService.performAutoInput(txt, displayId)
}

suspend fun enter(displayId: Int? = null) {
    lastX1 = -1f; lastY1 = -1f; lastX2 = -1f; lastY2 = -1f
    if (displayId == null || displayId == 0) {
        ShellExecutor.getInstance().execute("input keyevent 66")
    } else InputControlUtils.pressImeAction(displayId)
}

suspend fun launch(app: String, displayId: Int? = null) {
    lastX1 = -1f; lastY1 = -1f; lastX2 = -1f; lastY2 = -1f
    val packageName = getPackageName(app) ?: throw IllegalArgumentException("应用不存在: $app")
    if (displayId != null) InputControlUtils.moveAppToDisplay(packageName, displayId)
    else ShellExecutor.getInstance()
        .execute("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n $(cmd package resolve-activity --brief $packageName | tail -n 1)")
    delay(400.milliseconds) // 等待应用启动
    updatePriorityMapping(packageName, app)
}

suspend fun back(displayId: Int? = null) {
    val command = if (displayId != null) "input -d $displayId keyevent 4" else "input keyevent 4"
    ShellExecutor.getInstance().execute(command)
}

suspend fun wait(seconds: Float) {
    delay((seconds * 1000).toLong().coerceIn(1, 0x3f3f3f3f).milliseconds)
}

suspend fun home(displayId: Int? = null) {
    val command =
        if (displayId != null) "input -d $displayId keyevent KEYCODE_HOME" else "input keyevent KEYCODE_HOME"
    ShellExecutor.getInstance().execute(command)
}

suspend fun longPress(context: Context, x: Float, y: Float, displayId: Int? = null) {
    if (x > 999 || y > 999) throw IllegalArgumentException("坐标范围应在[0,999]内")
    val isMainScreen = (displayId == null || displayId == 0)
    lastX1 = x
    lastY1 = y
    val finalX = toRealX(x)
    val finalY = toRealY(y)
    lastX2 = -1f; lastY2 = -1f
    val doOperation: suspend () -> Unit = {
        ShellExecutor.getInstance().execute("input swipe $finalX $finalY $finalX $finalY 1800")
    }
    if (isMainScreen) SharedState.withTouchThroughEnabled(doOperation)
    else {
        InputControlUtils.longPress(
            finalX.toInt(), finalY.toInt(), displayId, 1800
        )
    }
    if (isMainScreen) context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
}

suspend fun doubleTap(context: Context, x: Float, y: Float, displayId: Int? = null) {
    if (x > 999 || y > 999) throw IllegalArgumentException("坐标范围应在[0,999]内")
    val isMainScreen = (displayId == null || displayId == 0)
    lastX1 = x
    lastY1 = y
    val finalX = toRealX(x)
    val finalY = toRealY(y)
    lastX2 = -1f; lastY2 = -1f
    val doOperation: suspend () -> Unit = {
        ShellExecutor.getInstance().execute("input tap $finalX $finalY")
        delay(100.milliseconds)
        ShellExecutor.getInstance().execute("input tap $finalX $finalY")
    }
    if (isMainScreen) {
        SharedState.withTouchThroughEnabled(doOperation)
    } else {
        InputControlUtils.doubleTap(finalX.toInt(), finalY.toInt(), displayId)
    }
    if (isMainScreen) context.sendBroadcast(Intent("ACTION_DISABLE_TOUCH_THROUGH"))
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

private fun toRealX(x: Float): Float {
    return x / 1000 * DisplayMetricsStore.width
}

private fun toRealY(y: Float): Float {
    return y / 1000 * DisplayMetricsStore.height
}