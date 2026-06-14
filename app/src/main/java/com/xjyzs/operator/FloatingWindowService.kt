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
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process.killProcess
import android.os.Process.myPid
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.xjyzs.operator.ui.theme.OperatorTheme
import com.xjyzs.operator.utils.APP_PACKAGES
import com.xjyzs.operator.utils.APP_PACKAGES_SPECIAL
import com.xjyzs.operator.utils.CpuFreq
import com.xjyzs.operator.utils.InputControlUtils
import com.xjyzs.operator.utils.PACKAGES_APP
import com.xjyzs.operator.utils.SuExecutor
import com.xjyzs.operator.utils.buildUserJson
import com.xjyzs.operator.utils.clickVibrate
import com.xjyzs.operator.utils.getDefaultBrowserPackage
import com.xjyzs.operator.utils.operation
import com.xjyzs.operator.utils.updatePriorityMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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
        fun getLayout(displayId: Int? = null): String {
            val service = instance ?: return ""
            return service.captureLayout(displayId)
        }

        fun disableService() {
            instance?.disableSelf()
        }

        fun performAutoInput(text: String, displayId: Int = 0): Boolean {
            return instance?.performAutoInput(text, displayId) ?: false
        }
    }

    private lateinit var lifecycleOwner: MyLifecycleOwner
    private lateinit var mWindowManager: WindowManager
    private lateinit var mFloatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var wakeLock: PowerManager.WakeLock
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var activeEditText: java.lang.ref.WeakReference<EditText>? = null
    var isWindowFocusable = false
    override fun onCreate() {
        super.onCreate()
        instance = this
        registerReceiver(
            showFloatingReceiver,
            IntentFilter("ACTION_SHOW_FLOATING"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        )
        registerReceiver(
            showFloatingReceiver,
            IntentFilter("ACTION_HIDE_FLOATING"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        )
        registerReceiver(
            showFloatingReceiver,
            IntentFilter("ACTION_ENABLE_TOUCH_THROUGH"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        )
        registerReceiver(
            showFloatingReceiver,
            IntentFilter("ACTION_DISABLE_TOUCH_THROUGH"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        )
        val channel = NotificationChannel(
            "panel", getString(R.string.floating_panel_channel), NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, "panel").setContentTitle("Operator")
            .setSmallIcon(R.drawable.icon).setOngoing(true).setRequestPromotedOngoing(true).build()
        startForeground(1001, notification)

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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
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
                        mFloatingView,
                        serviceScope,
                        layoutParams as WindowManager.LayoutParams,
                        mWindowManager
                    )
                }
            }
        }
        mWindowManager.addView(
            mFloatingView, layoutParams
        )
        isViewAdded = true
        setupKeyboardListener()

        serviceScope.launch {
            try {
                CpuFreq.init()
            } catch (_: Exception) {
            }
        }
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

        if (targetNode == null) {
            return false
        }

        return try {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            var success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!success) {
                success = performPasteFallback(this, text)
            }
            success
        } finally {
            targetNode.recycle()
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
        val success = runBlocking {
            SuExecutor.getInstance()
                .execute("input -d ${InputControlUtils.displayId} keycombination 113 29;sleep 0.1;input -d ${InputControlUtils.displayId} keyevent 279")
        }.isSuccess

        // 还原原剪贴板
        if (previousClip != null) {
            clipboard.setPrimaryClip(previousClip)
        }

        return success
    }

    private val showFloatingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_SHOW_FLOATING") {
                mFloatingView.visibility = View.VISIBLE
            }
            if (intent?.action == "ACTION_HIDE_FLOATING") {
                mFloatingView.visibility = View.GONE
            }
            if (intent?.action == "ACTION_ENABLE_TOUCH_THROUGH") {
                layoutParams.flags =
                    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE or FLAG_LAYOUT_NO_LIMITS
                mWindowManager.updateViewLayout(mFloatingView, layoutParams)
            }
            if (intent?.action == "ACTION_DISABLE_TOUCH_THROUGH") {
                layoutParams.flags =
                    FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS
                mWindowManager.updateViewLayout(mFloatingView, layoutParams)
            }
        }
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        isRunning = false
        instance = null
        CpuFreq.destroy()
        serviceScope.cancel()
        wakeLock.release()
        InputControlUtils.release()
        try {
            unregisterReceiver(showFloatingReceiver)
        } catch (_: Exception) {
        }
        if (isViewAdded) {
            try {
                layoutListener?.let { mFloatingView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
                mWindowManager.removeView(mFloatingView)
            } catch (_: Exception) {
            }
            isViewAdded = false
        }
        try {
            stopForeground(true)
        } catch (_: Exception) {
        }

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
        isRunning = false
        instance = null
        CpuFreq.destroy()
        serviceScope.cancel()
        wakeLock.release()
        InputControlUtils.release()
        unregisterReceiver(showFloatingReceiver)

        if (isViewAdded) {
            try {
                layoutListener?.let {
                    mFloatingView.viewTreeObserver.removeOnGlobalLayoutListener(it)
                }
                mWindowManager.removeView(mFloatingView)
            } catch (_: Exception) {
            }
            isViewAdded = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {}

    fun captureLayout(displayId: Int? = null): String {
        val rootNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val targetId = displayId ?: 0
            val displayWindows = windowsOnAllDisplays.get(targetId) ?: emptyList()
            val appWindow =
                displayWindows.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
                    ?: displayWindows.firstOrNull { it.root != null }
            appWindow?.root ?: if (targetId == 0) rootInActiveWindow else null
        } else {
            rootInActiveWindow
        } ?: return JSONObject().apply {
            put("error", getString(R.string.cannot_get_rootnode))
        }.toString()

        val screenWidth =
            if (displayId != null && displayId == InputControlUtils.displayId && SharedState._virtualDisplayWidth.value > 0)
                SharedState._virtualDisplayWidth.value
            else resources.displayMetrics.widthPixels
        val screenHeight =
            if (displayId != null && displayId == InputControlUtils.displayId && SharedState._virtualDisplayHeight.value > 0)
                SharedState._virtualDisplayHeight.value
            else resources.displayMetrics.heightPixels
        val elementList = mutableListOf<ElementInfo>()
        traverseNode(rootNode, elementList, screenWidth, screenHeight)

        val sb = StringBuilder()
        for (el in elementList) {
            val isEditText = el.className.contains("EditText")
            if (!isEditText && !el.isClickable && el.text.isEmpty()) continue
            val isTooLong = el.text.length > 100
            if (isTooLong && "base64," in el.text) continue
            val type = el.className.substringAfterLast(".")
            val parts = mutableListOf<String>()
            if (el.position == null) continue
            parts.add("pos:[${el.position.first},${el.position.second}]")
            sb.append("[${type}]${if (isTooLong) el.text.substring(0, 100) else el.text}")
            if (parts.isNotEmpty()) {
                sb.append(",${parts.joinToString(",")}")
            }
            sb.appendLine()
        }
        return sb.toString().trimEnd()
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
                val position =
                    if (bounds.right in 0..screenWidth && bounds.bottom in 0..screenHeight) {
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
            traverseNode(node.getChild(i), list, screenWidth, screenHeight)
        }
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
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info

        if (!isViewAdded) {
            try {
                mWindowManager.addView(mFloatingView, layoutParams)
                isViewAdded = true
                setupKeyboardListener()
            } catch (_: Exception) {
            }
        } else {
            setupKeyboardListener()
        }
    }

    private var layoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private var wasKeyboardShowing = false
    private fun setupKeyboardListener() {
        if (layoutListener != null) return
        layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val insets = ViewCompat.getRootWindowInsets(mFloatingView)
            val isKeyboardShowing = insets?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
            if (wasKeyboardShowing && !isKeyboardShowing && isWindowFocusable) {
                activeEditText?.get()?.clearFocus()
            }
            wasKeyboardShowing = isKeyboardShowing
        }
        mFloatingView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }
}

data class ElementInfo(
    val text: String, val className: String, val position: Pair<Int, Int>?, val isClickable: Boolean
)

private class MyLifecycleOwner : SavedStateRegistryOwner {
    val mLifecycleRegistry = LifecycleRegistry(this)
    val mSavedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle
        get() = mLifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = mSavedStateRegistryController.savedStateRegistry

    fun onStart() {
        mLifecycleRegistry.currentState = Lifecycle.State.STARTED
    }
}

object SharedState {
    val msgs = mutableStateListOf<Msg>()
    private val _input = MutableStateFlow("")
    val input = _input.asStateFlow()

    val _newMsg = MutableStateFlow("")
    val newMsg = _newMsg.asStateFlow()

    val _completionTokens = MutableStateFlow(0L)
    val completionTokens = _completionTokens.asStateFlow()

    val _promptTokens = MutableStateFlow(0L)
    val promptTokens = _promptTokens.asStateFlow()

    val _cachedTokens = MutableStateFlow(0L)
    val cachedTokens = _cachedTokens.asStateFlow()

    val _imageTokens = MutableStateFlow(0L)
    val imageTokens = _imageTokens.asStateFlow()
    val _usesVirtualDisplay = MutableStateFlow(true)
    val usesVirtualDisplay = _usesVirtualDisplay.asStateFlow()

    val _virtualDisplayWidth = MutableStateFlow(0)
    val _virtualDisplayHeight = MutableStateFlow(0)

    fun update(value: String) {
        _input.value = value
    }

    fun clearTokens() {
        _completionTokens.value = 0
        _promptTokens.value = 0
        _cachedTokens.value = 0
        _imageTokens.value = 0
    }
}

var width = 1080
var height = 2400
var dpi = 420
val panelMaxWidthDp = 600.dp

data class Msg(
    val role: String, var content: MutableState<JsonElement>
)

enum class RunningState {
    STOP, RUNNING, TAKE_OVER, CONNECTING
}

@SuppressLint(
    "LocalContextResourcesRead", "DiscouragedApi", "InternalInsetResource", "QueryPermissionsNeeded",
    "LocalContextGetResourceValueCall"
)
@Composable
fun FloatingPanel(
    mFloatingView: View,
    serviceScope: CoroutineScope,
    layoutParams: WindowManager.LayoutParams,
    mWindowManager: WindowManager
) {
    val layoutParams = remember { layoutParams }
    var runningState by remember { mutableStateOf(RunningState.STOP) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val coroutineScope = rememberCoroutineScope()
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    val isMinimized = remember { mutableStateOf(false) }
    val offsetX = remember { Animatable(layoutParams.x.toFloat()) }
    val offsetY = remember { Animatable(layoutParams.y.toFloat()) }
    val density = LocalDensity.current
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val resources = context.resources
    val suInstance = SuExecutor.getInstance()
    val statusBarHeight = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets =
                windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars()
                )
            insets.top
        } else {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        }
    }
    var lastMsgIncomplete = remember { false }

    val checkBoundsAndSnap: suspend () -> Unit = {
        val dm = context.resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels - statusBarHeight
        val wh =
            if (isMinimized.value) with(density) { 56.dp.toPx() }.toInt() else mFloatingView.height
        val boundMaxY = (sh - wh).coerceAtLeast(0).toFloat()
        val targetY = offsetY.value.coerceIn(0f, boundMaxY)

        val targetX: Float
        if (isMinimized.value) {
            val ww = with(density) { 56.dp.toPx() }
            val boundMaxX = ((sw - ww) / 2f).coerceAtLeast(0f) + (ww / 3f)
            targetX = if (offsetX.value > 0) boundMaxX else -boundMaxX
        } else {
            val panelMaxWidthPx = with(density) { panelMaxWidthDp.toPx() }
            if (sw < panelMaxWidthPx) {
                targetX = 0f
            } else {
                val cardMaxTotalWidth = with(density) { (panelMaxWidthDp + 12.dp).toPx() }
                val cardWidth = sw.toFloat().coerceAtMost(cardMaxTotalWidth)
                val boundMaxX = ((sw - cardWidth) / 2f).coerceAtLeast(0f)
                targetX = offsetX.value.coerceIn(-boundMaxX, boundMaxX)
            }
        }

        if (targetX != offsetX.value || targetY != offsetY.value) {
            coroutineScope.launch {
                offsetX.animateTo(targetX, spring(stiffness = Spring.StiffnessMediumLow)) {
                    layoutParams.x = value.toInt()
                    mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                }
            }
            coroutineScope.launch {
                offsetY.animateTo(targetY, spring(stiffness = Spring.StiffnessMediumLow)) {
                    layoutParams.y = value.toInt()
                    mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                }
            }
        }
    }

    LaunchedEffect(configuration) {
        delay(100)
        checkBoundsAndSnap()
    }
    LaunchedEffect(Unit) {
        val dm = DisplayMetrics()
        mFloatingView.display.getRealMetrics(dm)
        width = dm.widthPixels
        height = dm.heightPixels
        dpi = dm.densityDpi
    }
    val cancelRequested = remember { AtomicBoolean(false) }
    val operationRe = Regex(
        """do\s*\(\s*action\s*=\s*"(?<action>[^"\\]*(?:\\.[^"\\]*)*)"(?:\s*,\s*(?<args>(?:"(?:[^"\\]|\\.)*"|[^)])*))?\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    val finishRe = Regex(
        """finish\s*\(\s*message\s*=\s*"(?<message>[^"\\]*(?:\\.[^"\\]*)*)"\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    val lazyListState = rememberLazyListState()
    var ime = ""
    val apiPref = context.getSharedPreferences("api", Context.MODE_PRIVATE)
    var apiUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    val msgs = SharedState.msgs
    LaunchedEffect(Unit) {
        msgs.add(
            Msg(
                "system", mutableStateOf(
                    JsonPrimitive(
                        """Analyze the screen and history to output the next action to complete the user's task.

# OUTPUT FORMAT
1. Reasoning: Page state, did the last action succeed (check grey dot position for miss/unresponsiveness), next action logic.
2. Command: A single line of code. Strictly no markdown, punctuation, or extra text.

# ACTION DICTIONARY
Coordinates [x,y] are scaled [0,0] to [999,999] (0-999). Forbid values > 999.
- do(action="Launch", app="xxx"): Launch app. Do not go Home to search.
- do(action="Tap", element=[x,y]): Tap at [x,y].
- do(action="Type", text="xxx"): Type text (clears field first).
- do(action="Swipe", start=[x1,y1], end=[x2,y2]): Swipe start to end.
- do(action="Long Press", element=[x,y]): Long press at [x,y].
- do(action="Double Tap", element=[x,y]): Double tap at [x,y].
- do(action="Take_over", message="xxx"): Ask for user help (login/CAPTCHA).
- do(action="Back"): Press system Back.
- do(action="Wait", duration="x seconds"): Wait (e.g., "1.5 seconds").
- finish(message="xxx"): Task completed. 'message' is the final result.

# CORE RULES
## Language
- When answering, follow the user's original language.
## Infinite Loop Prevention
- **State Check:** If screen remains unchanged after Tap/Swipe, do not repeat the same action. Shift coordinates, swipe differently, go Back, or skip.

## Touch Correction
- **Grey Target Circle:** The grey circle is your previous tap location. It is NOT a loading indicator. If it missed, calculate the offset and adjust coordinates. Do not tap the exact same failed coordinates.
- **Coordinates First:** Prefer provided element coordinates over visual estimation.

## Scenarios
- **Web:** Opening URLs must use the default browser.
- **Errors:** Go Back to close popups; Refresh on network error. Wait <= 3 times on loading, then Back to retry.
- **Search:** Swipe to find targets. If search fails 3 times, finish() with explanation.
- **Flexibility:** Adapt or relax filters if no exact match is found.
- **Video:** Tap screen once to show controls. Multiple actions can be queued in one reply.

Today is: ${
                            LocalDate.now().format(
                                DateTimeFormatter.ofPattern(
                                    "EEEE, MMMM dd, yyyy", Locale.US
                                )
                            )
                        }"""
                    )
                )
            )
        )
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getInstalledApplications(0)
        }
        APP_PACKAGES_SPECIAL.forEach { (appName, packageName) ->
            PACKAGES_APP[packageName] = appName
            APP_PACKAGES[appName] = packageName
            val lowercased = appName.lowercase(Locale.US)
            if (lowercased != appName) {
                APP_PACKAGES[lowercased] = packageName
            }
        }
        if (apps.size < 5) {
            // 兼容模式
            Toast.makeText(
                context, context.getString(R.string.app_list_permission_warning), Toast.LENGTH_SHORT
            ).show()
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = context.packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_ALL
            )
            for (i in apps) {
                val label = i.loadLabel(context.packageManager).toString()
                APP_PACKAGES[label] = i.activityInfo.packageName
                val lowercasedLabel = label.lowercase()
                if (lowercasedLabel != label) {
                    APP_PACKAGES[lowercasedLabel] = i.activityInfo.packageName
                }
                for (i in APP_PACKAGES) {
                    PACKAGES_APP[i.value.lowercase(Locale.US)] = i.key
                }
            }
        } else {
            for (appInfo in apps) {
                val packageName = appInfo.packageName
                val systemLabel = appInfo.loadLabel(pm).toString()
                PACKAGES_APP[packageName] = systemLabel
                APP_PACKAGES[systemLabel] = packageName
                val lowercasedLabel = systemLabel.lowercase(Locale.US)
                if (lowercasedLabel != systemLabel) {
                    APP_PACKAGES[lowercasedLabel] = packageName
                }
            }
        }
        val browserPkg = getDefaultBrowserPackage(context) ?: "com.android.browser"
        updatePriorityMapping(browserPkg, context.getString(R.string.system_browser))
        updatePriorityMapping(browserPkg, context.getString(R.string.browser))
    }
    LaunchedEffect(Unit) {
        apiUrl = apiPref.getString("apiUrl", "")!!
        if (!apiUrl.endsWith("chat/completions")) {
            apiUrl += if (apiUrl.endsWith("/")) "chat/completions"
            else "/chat/completions"
        }
        apiKey = apiPref.getString("apiKey", "")!!
        model = apiPref.getString("model", "")!!
        SharedState._promptTokens.value = apiPref.getLong("promptTokens", 0L)
        SharedState._cachedTokens.value = apiPref.getLong("cachedTokens", 0L)
        SharedState._imageTokens.value = apiPref.getLong("imageTokens", 0L)
        SharedState._completionTokens.value = apiPref.getLong("completionTokens", 0L)
        SharedState._usesVirtualDisplay.value = apiPref.getBoolean("usesVirtualDisplay", true)
    }

    val inputMsg by SharedState.input.collectAsState()
    val streamJobRef = remember { AtomicReference<Job?>(null) }
    val streamCallRef = remember { AtomicReference<Call?>(null) }
    suspend fun clearInputFocusAndAwait() {
        val service = FloatingWindowService.instance ?: return
        service.activeEditText?.get()?.clearFocus()
        repeat(20) {
            val hasActiveEditText = service.activeEditText?.get() != null
            if (!service.isWindowFocusable && !hasActiveEditText) {
                return
            }
            delay(16)
        }
    }

    fun send() {
        cancelRequested.set(false)
        runningState = RunningState.CONNECTING
        val streamJob = serviceScope.launch {
            var activeCall: Call? = null
            val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
            if (msgs.last().role == "user") msgs.removeAt(msgs.lastIndex)
            msgs.add(buildUserJson(inputMsg, mFloatingView))
            SharedState.update("")
            val serializableMsgs = msgs.map { msg ->
                mapOf("role" to msg.role, "content" to msg.content.value)
            }
            val bodyMap = mapOf(
                "model" to model,
                "messages" to serializableMsgs.toList(),
                "stream" to true,
                "stream_options" to mapOf("include_usage" to true),
                "thinking" to mapOf("type" to "disabled"),
            )
            val requestBody =
                Gson().toJson(bodyMap).toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder().url(apiUrl).post(requestBody)
                .addHeader("Authorization", "Bearer $apiKey").build()
            try {
                val call = client.newCall(request)
                activeCall = call
                streamCallRef.set(call)
                val response = call.execute()
                withContext(Dispatchers.Main) {
                    runningState = RunningState.RUNNING
                    updateNotification(context, context.getString(R.string.executing))
                }
                response.body.byteStream().use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        var line: String?
                        // 解析
                        while (reader.readLine().also { line = it } != null) {
                            //running = 1
                            try {
                                val cleanLine = line?.removePrefix("data: ")?.trim()
                                val json = JsonParser.parseString(cleanLine).asJsonObject
                                try {
                                    val usage = json.getAsJsonObject("usage")?.asJsonObject
                                    if (usage?.isJsonNull == false) {
                                        val completionTokens =
                                            usage.getAsJsonPrimitive("completion_tokens")?.asInt
                                        val promptTokens =
                                            usage.getAsJsonPrimitive("prompt_tokens")?.asInt
                                        val promptTokensDetails =
                                            usage.getAsJsonObject("prompt_tokens_details")
                                        if (completionTokens != null) SharedState._completionTokens.value += completionTokens

                                        if (promptTokens != null) SharedState._promptTokens.value += promptTokens

                                        if (promptTokensDetails != null) {
                                            val cachedTokens =
                                                promptTokensDetails.getAsJsonPrimitive("cached_tokens")?.asInt
                                            val imageTokens =
                                                promptTokensDetails.getAsJsonPrimitive("image_tokens")?.asInt
                                            if (cachedTokens != null) SharedState._cachedTokens.value += cachedTokens
                                            if (imageTokens != null) SharedState._imageTokens.value += imageTokens
                                        }
                                        apiPref.edit {
                                            putLong("promptTokens", SharedState._promptTokens.value)
                                            putLong("cachedTokens", SharedState._cachedTokens.value)
                                            putLong("imageTokens", SharedState._imageTokens.value)
                                            putLong(
                                                "completionTokens",
                                                SharedState._completionTokens.value
                                            )
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                                val choices =
                                    json.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                                val delta = choices?.getAsJsonObject("delta")
                                if (cancelRequested.getAndSet(false)) {
                                    withContext(Dispatchers.Main) {
                                        runningState = RunningState.STOP
                                        updateNotification(context, context.getString(R.string.cancelled))
                                    }
                                    break
                                }
                                if (delta != null) {
                                    withContext(Dispatchers.Main) {
                                        if (msgs.last().role != "assistant") msgs.add(
                                            Msg(
                                                "assistant", mutableStateOf(JsonPrimitive(""))
                                            )
                                        )
                                        msgs.last().content.value = JsonPrimitive(
                                            msgs.last().content.value.asJsonPrimitive.asString + (delta.get(
                                                "content"
                                            )?.asString)
                                        )
                                        lazyListState.scrollToItem(msgs.lastIndex, 0x3f3f3f3f)
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (cancelRequested.getAndSet(false)) {
                    withContext(Dispatchers.Main) {
                        runningState = RunningState.STOP
                        updateNotification(context, context.getString(R.string.cancelled))
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    runningState = RunningState.STOP
                    updateNotification(context, context.getString(R.string.error))
                    val intent = Intent(context, DialogActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    intent.putExtra("title", context.getString(R.string.error_title))
                    intent.putExtra("text", e.stackTraceToString())
                    context.startActivity(intent)

                }
                return@launch
            } finally {
                activeCall?.let { streamCallRef.compareAndSet(it, null) }
            }
            val lastMsgContent = msgs.last().content.value
            val lastMsgText =
                if (lastMsgContent.isJsonPrimitive) lastMsgContent.asJsonPrimitive.asString else ""
            val founds = operationRe.findAll(lastMsgText)
            for (found in founds) {
                if (found.groups["action"]!!.value != "Take_over") {
                    try {
                        val args =
                            if (found.groups["args"] != null) found.groups["args"]!!.value else ""
                        val usesVirtualDisplay = SharedState._usesVirtualDisplay.value
                        val virtualDisplayId =
                            if (usesVirtualDisplay && InputControlUtils.displayId != -1) InputControlUtils.displayId else null
                        operation(
                            found.groups["action"]!!.value,
                            args,
                            context,
                            mFloatingView,
                            virtualDisplayId
                        )
                        delay(1500)
                    } catch (_: Exception) {
                    }
                } else {
                    if (!SharedState._usesVirtualDisplay.value) suInstance.execute("ime set $ime")

                    withContext(Dispatchers.Main) {
                        runningState = RunningState.TAKE_OVER
                        updateNotification(context, context.getString(R.string.take_over))
                    }
                    return@launch
                }
            }
            val found = finishRe.find(lastMsgText)
            if (found != null) {
                if (!SharedState._usesVirtualDisplay.value) suInstance.execute("ime set $ime")
                withContext(Dispatchers.Main) {
                    runningState = RunningState.STOP
                    updateNotification(context, context.getString(R.string.completed))
                    val channel = NotificationChannel(
                        "finish", context.getString(R.string.task_completion_channel), NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        enableVibration(true)
                        setSound(
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null
                        )
                    }
                    val notificationManager =
                        context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.createNotificationChannel(channel)
                    val notification =
                        NotificationCompat.Builder(context, "finish").setContentTitle(context.getString(R.string.task_completed_title))
                            .setContentText(found.groups["message"]!!.value)
                            .setSmallIcon(R.drawable.icon).build()
                    notificationManager.notify(System.currentTimeMillis().toInt(), notification)
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                // 移除上一轮的图片，用新对象替换以触发 Compose 状态更新
                if (msgs.size >= 2 && msgs[msgs.size - 2].role == "user") {
                    val userMsg = msgs[msgs.size - 2]
                    val userContent = userMsg.content.value
                    if (userContent.isJsonArray && userContent.asJsonArray.size() > 1) {
                        val textObj = userContent.asJsonArray[0]
                        val index = msgs.size - 2
                        msgs.removeAt(index)
                        msgs.add(
                            index, Msg("user", mutableStateOf(JsonArray().apply { add(textObj) }))
                        )
                    }
                }
                send()
            }
        }
        streamJobRef.set(streamJob)
        if (!streamJob.isActive) {
            streamJobRef.compareAndSet(streamJob, null)
        }
    }
    Box(contentAlignment = Alignment.Center) {
        if (isMinimized.value) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { coroutineScope.launch { checkBoundsAndSnap() } },
                            onDragCancel = { coroutineScope.launch { checkBoundsAndSnap() } }) { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                offsetX.snapTo(offsetX.value + dragAmount.x)
                                val dm = context.resources.displayMetrics
                                val boundMaxY =
                                    (dm.heightPixels - mFloatingView.height).coerceAtLeast(0)
                                        .toFloat()
                                val newY = (offsetY.value - dragAmount.y).coerceIn(0f, boundMaxY)
                                offsetY.snapTo(newY)

                                layoutParams.x = offsetX.value.toInt()
                                layoutParams.y = offsetY.value.toInt()
                                mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                            }
                        }
                    }
                    .clickable {
                        coroutineScope.launch {
                            val dm = context.resources.displayMetrics
                            val sw = dm.widthPixels
                            val panelMaxWidthPx = with(density) { panelMaxWidthDp.toPx() }
                            val targetX = if (sw < panelMaxWidthPx) 0f else {
                                val cardMaxTotalWidth =
                                    with(density) { (panelMaxWidthDp + 12.dp).toPx() }
                                val cardWidth = sw.toFloat().coerceAtMost(cardMaxTotalWidth)
                                val boundMaxX = ((sw - cardWidth) / 2f).coerceAtLeast(0f)
                                offsetX.value.coerceIn(-boundMaxX, boundMaxX)
                            }
                            offsetX.snapTo(targetX)
                            layoutParams.x = targetX.toInt()
                            layoutParams.width = MATCH_PARENT
                            mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                            isMinimized.value = false

                            delay(100)
                            checkBoundsAndSnap()
                        }
                    }, contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.icon),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        } else {
            Card(
                modifier = Modifier
                    .padding(6.dp)
                    .widthIn(max = panelMaxWidthDp)
                    .fillMaxWidth()
                    .height(170.dp), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
                ), shape = RoundedCornerShape(24.dp)
            ) {
                Box {
                    Scaffold(containerColor = Color.Transparent, bottomBar = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(1.dp))
                            var textValue by remember { mutableStateOf(inputMsg) }

                            LaunchedEffect(inputMsg) {
                                if (textValue != inputMsg) {
                                    textValue = inputMsg
                                }
                            }
                            val onSurface = MaterialTheme.colorScheme.onSurface
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(114514.dp),
                                border = BorderStroke(1.dp, Color.Gray),
                                color = Color.Transparent
                            ) {
                                AndroidView(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    factory = { ctx ->
                                        EditText(ctx).apply {
                                            background = null
                                            setPadding(0, 0, 0, 0)
                                            maxLines = 1
                                            textSize = 14f
                                            setTextColor(onSurface.toArgb())
                                            hint = ctx.getString(R.string.input_command_hint)
                                            setHintTextColor(android.graphics.Color.GRAY)
                                            setOnFocusChangeListener { _, hasFocus ->
                                                val service = FloatingWindowService.instance
                                                if (hasFocus) {
                                                    service?.activeEditText =
                                                        java.lang.ref.WeakReference(this)
                                                    service?.isWindowFocusable = true
                                                    layoutParams.flags =
                                                        (layoutParams.flags and FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                                    layoutParams.softInputMode =
                                                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                                    mWindowManager.updateViewLayout(
                                                        mFloatingView, layoutParams
                                                    )
                                                    postDelayed({
                                                        val imm =
                                                            ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                                        imm.showSoftInput(
                                                            this, InputMethodManager.SHOW_IMPLICIT
                                                        )
                                                    }, 100)
                                                } else {
                                                    service?.isWindowFocusable = false
                                                    service?.activeEditText = null
                                                    val imm =
                                                        ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                                    imm.hideSoftInputFromWindow(windowToken, 0)
                                                    layoutParams.flags =
                                                        layoutParams.flags or FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                                    layoutParams.softInputMode =
                                                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                                                    mWindowManager.updateViewLayout(
                                                        mFloatingView, layoutParams
                                                    )
                                                }
                                            }
                                            setOnKeyListener { _, keyCode, event ->
                                                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                                                    clearFocus()
                                                    true
                                                } else false
                                            }
                                            addTextChangedListener(object : TextWatcher {
                                                override fun beforeTextChanged(
                                                    s: CharSequence?,
                                                    start: Int,
                                                    count: Int,
                                                    after: Int
                                                ) {
                                                }

                                                override fun onTextChanged(
                                                    s: CharSequence?,
                                                    start: Int,
                                                    before: Int,
                                                    count: Int
                                                ) {
                                                    val str = s?.toString() ?: ""
                                                    if (str != textValue) {
                                                        textValue = str
                                                        SharedState.update(str)
                                                    }
                                                }

                                                override fun afterTextChanged(s: Editable?) {}
                                            })
                                        }
                                    },
                                    update = { editText ->
                                        if (editText.text.toString() != textValue) {
                                            editText.setText(textValue)
                                            editText.setSelection(textValue.length)
                                        }
                                    })
                            }
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable {
                                        clickVibrate(vibrator)
                                        coroutineScope.launch {
                                            when (runningState) {
                                                RunningState.STOP -> {
                                                    clearInputFocusAndAwait()
                                                    if (lastMsgIncomplete && msgs.last().role == "assistant") msgs.removeAt(msgs.lastIndex)
                                                    SharedState._newMsg.value = inputMsg
                                                    withContext(Dispatchers.IO) {
                                                        val result =
                                                            suInstance.execute("settings get secure default_input_method")
                                                        ime = result.stdout.trim()
                                                    }
                                                    if (SharedState._usesVirtualDisplay.value && InputControlUtils.displayId == -1) {
                                                        withContext(Dispatchers.Main) {
                                                            val dm =
                                                                context.resources.displayMetrics
                                                            val w = dm.widthPixels
                                                            val h = dm.heightPixels
                                                            val dpi = dm.densityDpi
                                                            SharedState._virtualDisplayWidth.value =
                                                                w
                                                            SharedState._virtualDisplayHeight.value =
                                                                h
                                                            InputControlUtils.createVirtualDisplay(
                                                                w, h, dpi
                                                            )
                                                        }
                                                        delay(500)
                                                    }
                                                    send()
                                                    if (!SharedState._usesVirtualDisplay.value) {
                                                        withContext(Dispatchers.IO) {
                                                            suInstance.execute("ime set com.android.adbkeyboard/.AdbIME")
                                                        }
                                                    }
                                                    updateNotification(context, context.getString(R.string.executing))
                                                }

                                                RunningState.TAKE_OVER -> {
                                                    clearInputFocusAndAwait()
                                                    SharedState._newMsg.value = inputMsg
                                                    runningState = RunningState.CONNECTING
                                                    send()
                                                    if (!SharedState._usesVirtualDisplay.value) {
                                                        withContext(Dispatchers.IO) {
                                                            suInstance.execute("ime set com.android.adbkeyboard/.AdbIME")
                                                        }
                                                    }
                                                    updateNotification(context, context.getString(R.string.executing))
                                                }
                                                // 正在运行，取消任务
                                                else -> {
                                                    cancelRequested.set(true)
                                                    streamCallRef.getAndSet(null)?.cancel()
                                                    streamJobRef.getAndSet(null)?.cancel()
                                                    runningState = RunningState.STOP
                                                    updateNotification(context, context.getString(R.string.cancelled))
                                                    context.sendBroadcast(Intent("ACTION_SHOW_FLOATING"))
                                                    if (!SharedState._usesVirtualDisplay.value) {
                                                        withContext(Dispatchers.IO) {
                                                            suInstance.execute("ime set $ime")
                                                        }
                                                    }
                                                    if (msgs.last().role == "user" || msgs.last().role == "assistant" && msgs.last().content.value.asJsonPrimitive.asString.isEmpty()) msgs.removeAt(
                                                        msgs.lastIndex
                                                    )
                                                    else if (msgs.last().role == "assistant" && msgs.last().content.value.asJsonPrimitive.asString.isNotEmpty())
                                                        lastMsgIncomplete = true
//                                                    val serializableMsgs = msgs.map { msg ->
//                                                        mapOf(
//                                                            "role" to msg.role,
//                                                            "content" to msg.content.value
//                                                        )
//                                                    }
//                                                    val bodyMap = mapOf(
//                                                        "model" to model,
//                                                        "messages" to serializableMsgs.toList(),
//                                                        "stream" to true
//                                                    )
//                                                    val requestBody = Gson().toJson(bodyMap)
//                                                    File("/data/user/10/${context.packageName}/1.json").writeText(
//                                                        requestBody
//                                                    )
                                                    return@launch
                                                }
                                            }
                                        }

                                    }, contentAlignment = Alignment.Center
                            ) {
                                val icon = when (runningState) {
                                    RunningState.STOP -> Icons.Default.ArrowUpward
                                    RunningState.RUNNING -> ImageVector.vectorResource(R.drawable.ic_rectangle)
                                    else -> Icons.AutoMirrored.Filled.ArrowForward
                                }
                                if (runningState != RunningState.CONNECTING) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        Modifier.padding(4.dp), color = Color.White
                                    )
                                }
                            }
                            Spacer(Modifier.width(1.dp))
                        }
                    }) { paddingValues ->
                        LazyColumn(
                            Modifier
                                .padding(paddingValues)
                                .fillMaxSize(), state = lazyListState
                        ) {
                            itemsIndexed(msgs) { _, msg ->
                                val content = msg.content.value
                                if (msg.role == "assistant") {
                                    val text =
                                        if (content.isJsonPrimitive) content.asJsonPrimitive.asString else ""
                                    Text(text)
                                } else if (msg.role == "user") {
                                    val userText = if (content.isJsonArray) {
                                        content.asJsonArray.firstOrNull {
                                            it.isJsonObject && it.asJsonObject.has(
                                                "text"
                                            )
                                        }?.asJsonObject?.get("text")?.asString ?: ""
                                    } else {
                                        try {
                                            content.asString ?: ""
                                        } catch (_: Exception) {
                                            ""
                                        }
                                    }
                                    Text(
                                        userText, color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent)
                    ) {
                        Box(
                            Modifier
                                .height(20.dp)
                                .width(200.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            val dm = context.resources.displayMetrics
                                            val sw = dm.widthPixels
                                            val threshold = with(density) { 60.dp.toPx() }
                                            val distToEdge = (sw / 2f) - abs(offsetX.value)
                                            if (distToEdge < threshold) {
                                                isMinimized.value = true
                                                coroutineScope.launch {
                                                    val ww = with(density) { 56.dp.toPx() }
                                                    val boundMaxX =
                                                        ((sw - ww) / 2f).coerceAtLeast(0f) + (ww / 3f)
                                                    val targetX =
                                                        if (offsetX.value > 0) boundMaxX else -boundMaxX
                                                    offsetX.snapTo(targetX)
                                                    layoutParams.x = targetX.toInt()
                                                    layoutParams.width = WRAP_CONTENT
                                                    mWindowManager.updateViewLayout(
                                                        mFloatingView, layoutParams
                                                    )

                                                    checkBoundsAndSnap()
                                                }
                                            } else {
                                                coroutineScope.launch { checkBoundsAndSnap() }
                                            }
                                        },
                                        onDragCancel = { coroutineScope.launch { checkBoundsAndSnap() } }) { change, dragAmount ->
                                        change.consume()
                                        coroutineScope.launch {
                                            offsetX.snapTo(offsetX.value + dragAmount.x)
                                            val dm = context.resources.displayMetrics
                                            val boundMaxY =
                                                (dm.heightPixels - mFloatingView.height).coerceAtLeast(
                                                    0
                                                ).toFloat()
                                            val newY = (offsetY.value - dragAmount.y).coerceIn(
                                                0f, boundMaxY
                                            )
                                            offsetY.snapTo(newY)

                                            layoutParams.x = offsetX.value.toInt()
                                            layoutParams.y = offsetY.value.toInt()
                                            mWindowManager.updateViewLayout(
                                                mFloatingView, layoutParams
                                            )
                                        }
                                    }
                                }, contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(100.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                                    .width(80.dp)
                                    .height(4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

fun updateNotification(context: Context, txt: String) {
    val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val notification = NotificationCompat.Builder(context, "panel").setContentTitle("Operator")
        .setSmallIcon(R.drawable.icon).setOngoing(true).setRequestPromotedOngoing(true)
        .setShortCriticalText(txt).build()
    notificationManager.notify(1001, notification)
}
