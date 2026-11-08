package com.xjyzs.operator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process.killProcess
import android.os.Process.myPid
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.xjyzs.operator.ui.FloatingPanel
import com.xjyzs.operator.ui.applyFloatingPanelPosition
import com.xjyzs.operator.ui.theme.OperatorTheme
import com.xjyzs.operator.utils.CpuFreq
import com.xjyzs.operator.utils.InputControlUtils
import com.xjyzs.operator.utils.LayoutResp
import com.xjyzs.operator.utils.MyLifecycleOwner
import com.xjyzs.operator.utils.data.DisplayMetricsStore.height
import com.xjyzs.operator.utils.data.DisplayMetricsStore.width
import com.xjyzs.operator.utils.data.SharedState
import com.xjyzs.operator.utils.execute.screenshot
import com.xjyzs.operator.utils.ui.FloatingOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import kotlin.system.exitProcess
import android.graphics.Color as AndroidColor

@SuppressLint("AccessibilityPolicy")
class FloatingWindowService : AccessibilityService() {
    companion object {
        // 无障碍服务是否正在运行
        @Volatile
        var isRunning = false
            private set

        @Volatile
        var instance: FloatingWindowService? = null
        fun getLayout(displayId: Int? = null): LayoutResp {
            val service = instance ?: return LayoutResp("", false)
            return service.captureLayout(displayId)
        }

        fun performAutoInput(text: String, displayId: Int = 0): Boolean {
            return instance?.performAutoInput(text, displayId) ?: false
        }

        suspend fun captureScreenForWeb(): String {
            return try {
                screenshot(InputControlUtils.displayId)
            } catch (_: Exception) {
                ""
            }
        }
    }

    private lateinit var lifecycleOwner: MyLifecycleOwner
    private lateinit var mWindowManager: WindowManager
    private lateinit var mFloatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var wakeLock: PowerManager.WakeLock
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var isWindowFocusable = false
    override fun onCreate() {
        super.onCreate()
        instance = this

        // 广播接收器
        val filter = IntentFilter().apply {
            addAction("ACTION_SHOW_FLOATING")
            addAction("ACTION_HIDE_FLOATING")
            addAction("ACTION_ENABLE_TOUCH_THROUGH")
            addAction("ACTION_DISABLE_TOUCH_THROUGH")
            addAction("ACTION_KILL_SELF")
        }
        registerReceiver(
            showFloatingReceiver,
            filter,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        )

        // 通知
        val panelChannel = NotificationChannel(
            "real_time", "Agent 执行状态", NotificationManager.IMPORTANCE_LOW
        )
        val finishPanel = NotificationChannel(
            "finish", "任务完成通知", NotificationManager.IMPORTANCE_HIGH
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(panelChannel)
        notificationManager.createNotificationChannel(finishPanel)
        val notification =
            NotificationCompat.Builder(this, "real_time").setContentTitle("Operator 正在运行中")
                .setSmallIcon(R.drawable.icon).setOngoing(true).setRequestPromotedOngoing(true)
                .build()
        startForeground(1001, notification)


        // 启动悬浮窗
        lifecycleOwner = MyLifecycleOwner().apply {
            mSavedStateRegistryController.performRestore(null)
            mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        lifecycleOwner.onStart()

        mWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        layoutParams = WindowManager.LayoutParams(
            WRAP_CONTENT,
            WRAP_CONTENT,
            TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            applyFloatingPanelPosition(this@FloatingWindowService)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        mFloatingView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setBackgroundColor(AndroidColor.TRANSPARENT)
            setContent {
                OperatorTheme {
                    FloatingPanel(
                        mFloatingView, layoutParams as WindowManager.LayoutParams, mWindowManager
                    )
                }
            }
        }
        mWindowManager.addView(
            mFloatingView, layoutParams
        )
        SharedState.overlay = FloatingOverlay(mFloatingView)


        setupKeyboardListener()

        // 唤醒锁
        val powerManager = this.getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Operator::VirtualDisplayWakeLock"
        )
        wakeLock.acquire()
    }

    fun performAutoInput(text: String, targetDisplayId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }

        // Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val inputMethod = this.inputMethod
                val inputConnection = inputMethod?.currentInputConnection
                if (inputConnection != null) {
                    inputConnection.commitText(text, 1, null)
                    return true
                }
            } catch (_: Exception) {
            }
        }

        // 其他情况
        val displays = windowsOnAllDisplays
        val virtualWindows = displays.get(targetDisplayId)
        if (virtualWindows.isNullOrEmpty()) {
            return false
        }

        var targetNode: AccessibilityNodeInfo? = null
        for (window in virtualWindows) {
            val rootNode = window.root ?: continue
            targetNode = findFocusedNodeManually(rootNode)
            rootNode.recycle()

            if (targetNode != null) {
                break
            }
        }

        return try {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            var success =
                targetNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) ?: false
            if (!success) success = performPasteFallback(this, text)
            success
        } catch (_: Exception) {
            performPasteFallback(this, text)
        } finally {
            targetNode?.recycle()
        }
    }

    private fun findFocusedNodeManually(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedNodeManually(child)
            child.recycle()
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun performPasteFallback(context: Context, text: String): Boolean {
        val clipboard =
            context.getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return false

        // 备份当前剪贴板
        val previousClip = clipboard.primaryClip

        val clip = ClipData.newPlainText("paste", text)
        clipboard.setPrimaryClip(clip)
        InputControlUtils.pasteShortcut(InputControlUtils.displayId)

        // 还原剪贴板
        if (previousClip != null) {
            clipboard.setPrimaryClip(previousClip)
        }

        return true
    }

    private val showFloatingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_SHOW_FLOATING" -> mFloatingView.visibility = View.VISIBLE
                "ACTION_HIDE_FLOATING" -> mFloatingView.visibility = View.GONE
                "ACTION_ENABLE_TOUCH_THROUGH" -> {
                    layoutParams.flags =
                        FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_NO_LIMITS
                    mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                }

                "ACTION_DISABLE_TOUCH_THROUGH" -> {
                    layoutParams.flags =
                        FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS
                    mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                }

                "ACTION_KILL_SELF" -> {
                    cleanup()
                    stopForeground(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        disableSelf()
                    } else {
                        stopSelf()
                    }
                    killProcess(myPid())
                    exitProcess(0)
                }
            }
        }
    }

    private fun cleanup() {
        isRunning = false
        instance = null
        WebRemoteServer.stop()
        CpuFreq.destroy()
        serviceScope.cancel()
        wakeLock.release()
        InputControlUtils.release()
        if (isViewAdded) {
            try {
                layoutListener?.let { mFloatingView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
                mWindowManager.removeView(mFloatingView)
            } catch (_: Exception) {
            }
            isViewAdded = false
        }
        SharedState.overlay = FloatingOverlay(mFloatingView)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        cleanup()
        try {
            unregisterReceiver(showFloatingReceiver)
        } catch (_: Exception) {
        }
        stopForeground(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            disableSelf()
        } else {
            stopSelf()
        }
        killProcess(myPid())
        exitProcess(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        try {
            unregisterReceiver(showFloatingReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {}

    fun captureLayout(displayId: Int? = null): LayoutResp {
        val rootNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val targetId = displayId ?: 0
            val displayWindows = windowsOnAllDisplays.get(targetId) ?: emptyList()
            val appWindow =
                displayWindows.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
                    ?: displayWindows.firstOrNull { it.root != null }
            appWindow?.root ?: if (targetId == 0) rootInActiveWindow else null
        } else {
            rootInActiveWindow
        } ?: return LayoutResp(
            JSONObject().apply {
                put("error", getString(R.string.cannot_get_screen_layout))
            }.toString(), false
        )
        val elementList = mutableListOf<ElementInfo>()

        traverseNode(rootNode, elementList, width, height)

        val sb = StringBuilder()
        for (el in elementList) {
            if (el.position == null) continue
            val isTooLong = el.text.length > 100
            if (isTooLong && "base64," in el.text) continue
            val type = el.className.substringAfterLast(".")
            val textContent = if (isTooLong) el.text.substring(0, 100) else el.text
            sb.append("[${type}]${textContent}")
            sb.append(",pos:(${el.position.first},${el.position.second})")
            sb.appendLine()
        }
        val isReadable = checkAccessibilityReadable(elementList)
        return LayoutResp(sb.toString().trimEnd(), isReadable)
    }

    fun traverseNode(
        node: AccessibilityNodeInfo?,
        list: MutableList<ElementInfo>,
        screenWidth: Int,
        screenHeight: Int
    ) {
        if (node == null) return
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val isActionable =
            node.isClickable || text.isNotEmpty() || className.contains("Button") || className.contains(
                "EditText"
            )
        if (isActionable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val centerX = (bounds.left + bounds.right) / 2
                val centerY = (bounds.top + bounds.bottom) / 2
                val isVisibleOnScreen =
                    screenWidth > 0 && screenHeight > 0 && bounds.right > 0 && bounds.left < screenWidth && bounds.bottom > 0 && bounds.top < screenHeight
                val position = if (isVisibleOnScreen) {
                    val posX = (centerX * 999 / screenWidth).coerceIn(0, 999)
                    val posY = (centerY * 999 / screenHeight).coerceIn(0, 999)
                    Pair(posX, posY)
                } else null
                list.add(
                    ElementInfo(
                        text = text,
                        className = className,
                        position = position,
                        isClickable = node.isClickable
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, list, screenWidth, screenHeight)
            try {
                child.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 判断无障碍获取的内容是否是空壳
     */
    private fun checkAccessibilityReadable(elements: List<ElementInfo>): Boolean {
        val textElements = elements.filter {
            it.position != null && it.text.isNotBlank()
        }
        if (textElements.isEmpty()) return false

        // 中间有内容，说明可读
        val bodyTextCount = textElements.count {
            val y = it.position!!.second
            y in 131..869
        }
        return bodyTextCount > 0
    }

    private var isViewAdded = false
    override fun onServiceConnected() {
        super.onServiceConnected()
        // 使划卡时服务能被清理
        try {
            val intent = Intent(this, FloatingWindowService::class.java)
            startService(intent)
        } catch (_: Exception) {
        }
        isRunning = true
        // 成为屏幕阅读器，防止某些应用不提供节点
        val info = serviceInfo
        info.feedbackType =
            AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags =
            info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        setupKeyboardListener()
    }

    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var wasKeyboardShowing = false
    private fun setupKeyboardListener() {
        if (layoutListener != null) return
        layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val insets = ViewCompat.getRootWindowInsets(mFloatingView)
            val isKeyboardShowing = insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
            if (wasKeyboardShowing && !isKeyboardShowing && isWindowFocusable) {
                mFloatingView.clearFocus()
            }
            wasKeyboardShowing = isKeyboardShowing
        }
        mFloatingView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }
}

data class ElementInfo(
    val text: String, val className: String, val position: Pair<Int, Int>?, val isClickable: Boolean
)
