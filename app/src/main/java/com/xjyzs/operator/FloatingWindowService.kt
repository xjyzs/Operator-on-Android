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
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process.killProcess
import android.os.Process.myPid
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Gravity
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
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
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
    var isWindowFocusable = false
    override fun onCreate() {
        super.onCreate()
        instance = this
        val filter = IntentFilter().apply {
            addAction("ACTION_SHOW_FLOATING")
            addAction("ACTION_HIDE_FLOATING")
            addAction("ACTION_ENABLE_TOUCH_THROUGH")
            addAction("ACTION_DISABLE_TOUCH_THROUGH")
        }
        registerReceiver(
            showFloatingReceiver,
            filter,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        )
        val panelChannel = NotificationChannel(
            "panel", getString(R.string.floating_panel_channel), NotificationManager.IMPORTANCE_LOW
        )
        val finishPanel = NotificationChannel(
            "finish",
            getString(R.string.floating_panel_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(panelChannel)
        notificationManager.createNotificationChannel(finishPanel)
        val notification = NotificationCompat.Builder(this, "panel").setContentTitle("Operator")
            .setSmallIcon(R.drawable.icon).setOngoing(true).setRequestPromotedOngoing(true).build()
        startForeground(1001, notification)

        lifecycleOwner = MyLifecycleOwner().apply {
            mSavedStateRegistryController.performRestore(null)
            mLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        lifecycleOwner.onStart()

        mWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val cardMaxTotalWidth = (612f * dm.density).toInt() // panelMaxWidthDp(600) + 12dp padding
        layoutParams = WindowManager.LayoutParams(
            if (sw > cardMaxTotalWidth) cardMaxTotalWidth else MATCH_PARENT,
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
            }
        }
    }


    private fun cleanup() {
        isRunning = false
        instance = null
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

        val targetId = displayId ?: 0
        val isVirtual = targetId == InputControlUtils.displayId
        val screenWidth =
            if (isVirtual && SharedState._virtualDisplayWidth.value > 0) SharedState._virtualDisplayWidth.value
            else resources.displayMetrics.widthPixels
        val screenHeight =
            if (isVirtual && SharedState._virtualDisplayHeight.value > 0) SharedState._virtualDisplayHeight.value
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
            if (el.position == null) continue
            sb.append("[${type}]${if (isTooLong) el.text.substring(0, 100) else el.text}")
            sb.append(",pos:[${el.position.first},${el.position.second}]")
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
            val child = node.getChild(i) ?: continue
            traverseNode(child, list, screenWidth, screenHeight)
            child.recycle()
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
        info.flags =
            info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        setupKeyboardListener()
    }

    private var layoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private var wasKeyboardShowing = false
    private fun setupKeyboardListener() {
        if (layoutListener != null) return
        layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
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
val panelMaxWidthDp = 560.dp

private val sharedOkHttpClient by lazy {
    OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
}
private val sharedGson = Gson()

val operationRe = Regex(
    """do\s*\(\s*action\s*=\s*"(?<action>[^"\\]*(?:\\.[^"\\]*)*)"(?:\s*,\s*(?<args>(?:"(?:[^"\\]|\\.)*"|[^)])*))?\s*\)""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
)
val finishRe = Regex(
    """finish\s*\(\s*message\s*=\s*"(?<message>[^"\\]*(?:\\.[^"\\]*)*)"\s*\)""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
)

data class Msg(
    val role: String, var content: MutableState<JsonElement>
)

enum class RunningState {
    STOP, RUNNING, TAKE_OVER, CONNECTING
}

@SuppressLint(
    "LocalContextResourcesRead",
    "DiscouragedApi",
    "InternalInsetResource",
    "QueryPermissionsNeeded",
    "LocalContextGetResourceValueCall"
)
@Composable
fun FloatingPanel(
    mFloatingView: View,
    serviceScope: CoroutineScope,
    layoutParams: WindowManager.LayoutParams,
    mWindowManager: WindowManager
) {
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
        val ww = if (isMinimized.value) with(density) { 56.dp.toPx() } else mFloatingView.width.toFloat()
        val hh = if (isMinimized.value) with(density) { 56.dp.toPx() } else mFloatingView.height.toFloat()
        val boundX = ((sw - ww) / 2f).coerceAtLeast(0f)

        val targetX = if (isMinimized.value) {
            if (offsetX.value > 0) boundX + ww / 3f else -(boundX + ww / 3f)
        } else {
            offsetX.value.coerceIn(-boundX, boundX)
        }
        val targetY = offsetY.value.coerceIn(0f, (sh - hh).coerceAtLeast(0f))

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
        val dm = context.resources.displayMetrics
        val sw = dm.widthPixels
        if (!isMinimized.value) {
            val cardMaxTotalWidth = with(density) { (panelMaxWidthDp + 12.dp).toPx() }.toInt()
            layoutParams.width = if (sw > cardMaxTotalWidth) cardMaxTotalWidth else MATCH_PARENT
            try {
                mWindowManager.updateViewLayout(mFloatingView, layoutParams)
            } catch (_: Exception) {
            }
        }
        delay(100.milliseconds)
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
                        """你是移动端智能体专家。请根据屏幕截图和历史操作，输出下一步操作完成任务。

# 输出格式
简短推理，包括页面关键信息、上一步是否生效（结合截图灰色落点判断是否点偏或无响应）、下一步选择理由。
单独一行指令代码（绝对禁止附加任何标点或额外文字）。

# 操作指令字典
坐标 [x,y] 范围【绝对禁止】超过 999！坐标是千分比(0-999)。
- do(action="Launch", app="xxx"): 启动目标app。禁止用Home回到桌面滑动寻找。
- do(action="Tap", element=[x,y]): 点击。坐标范围 [0,0] 到 [999,999]。
- do(action="Type", text="xxx"): 输入文本（自动清除原有内容）。
- do(action="Swipe", start=[x1,y1], end=[x2,y2]): 滑动操作（坐标 [0-999]）。
- do(action="Long Press", element=[x,y]): 长按。
- do(action="Double Tap", element=[x,y]): 双击。
- do(action="Take_over", message="xxx"): 遇到登录/验证，请求人类接管。
- do(action="Back"): 返回。
- do(action="Wait", duration="x seconds"): 等待加载（如 "1.5 seconds"）。
- finish(message="xxx"): 任务完成时调用，message为最终结果。

# 核心规则
## 死循环预防
- **状态校验**：若执行 Tap/Swipe 后界面无变化或高度相似，**绝对禁止**重复完全相同的操作！
- **破局策略**：若上步无效，必须更换策略：稍微偏移坐标重新点击、改变滑动距离/方向、Back、或跳过该步骤。

## 触控精度与纠偏
- **灰色落点标记**：截图上的灰色半透明圆圈代表你上一步的物理点击落点。
  - **绝对禁止误判**：它**绝非页面加载（Loading）动画**！不要因此执行 Wait。
  - **位置纠偏**：若灰色圆圈偏离了目标元素（点歪/），下一步必须**主动计算偏差并修正坐标**，严禁在原错误坐标重复点击。
- **坐标优先**：若系统提供了元素坐标，尽量优先使用。

# 场景规则
- **浏览器**：打开网页必须启动系统浏览器。
- **异常处理**：无关页面先 Back；网络异常点刷新；未加载最多 Wait 3次，否则 Back 重试。
- **搜索查找**：找不到目标则 Swipe 寻找。连续3次搜索无果，执行 finish 说明原因。
- **意图泛化**：若无精准匹配（如联系人/筛选条件），允许灵活变通或放宽要求。
- **视频播放器**：若控制栏隐藏，点击屏幕使其显示，并允许单次回复下达多步操作。
今天的日期是: ${
                            LocalDate.now().format(
                                DateTimeFormatter.ofPattern(
                                    "yyyy年MM月dd日 EEEE", Locale.CHINA
                                )
                            )
                        }"""
                    )
                )
            )
        )
        val pm = context.packageManager
        val apps = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                pm.getInstalledApplications(0)
            }
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
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    suspend fun clearInputFocusAndAwait() {
        val service = FloatingWindowService.instance ?: return
        focusManager.clearFocus()
        repeat(20) {
            if (!service.isWindowFocusable) {
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
            val client = sharedOkHttpClient
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
                sharedGson.toJson(bodyMap).toRequestBody("application/json".toMediaTypeOrNull())
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
                                        updateNotification(
                                            context, context.getString(R.string.cancelled)
                                        )
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
                        delay(1500.milliseconds)
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
                    val notificationManager =
                        context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val notification = NotificationCompat.Builder(context, "finish")
                        .setContentTitle(context.getString(R.string.task_completed_title))
                        .setContentText(found.groups["message"]!!.value)
                        .setSmallIcon(R.drawable.icon).build()
                    notificationManager.notify(System.currentTimeMillis().toInt(), notification)
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
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
                            val cardMaxTotalWidthInt =
                                with(density) { (panelMaxWidthDp + 12.dp).toPx() }.toInt()
                            layoutParams.width =
                                if (sw > cardMaxTotalWidthInt) cardMaxTotalWidthInt else MATCH_PARENT
                            mWindowManager.updateViewLayout(mFloatingView, layoutParams)
                            isMinimized.value = false
                            delay(100.milliseconds)
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
            var isDraggingCard by remember { mutableStateOf(false) }
            val cardScale by animateFloatAsState(
                targetValue = if (isDraggingCard) 1.02f else 1f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 350,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                ),
                label = "cardScale"
            )
            Card(
                modifier = Modifier
                    .padding(6.dp)
                    .graphicsLayer {
                        scaleX = cardScale
                        scaleY = cardScale
                        transformOrigin = TransformOrigin(0.5f, 0.9f)
                    }
                    .widthIn(max = panelMaxWidthDp)
                    .fillMaxWidth()
                    .height(170.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(200.dp)
                                .height(24.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures(onDragStart = {
                                        isDraggingCard = true
                                    }, onDragEnd = {
                                        isDraggingCard = false
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
                                    }, onDragCancel = {
                                        isDraggingCard = false
                                        coroutineScope.launch { checkBoundsAndSnap() }
                                    }) { change, dragAmount ->
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
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        state = lazyListState
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var textValue by remember {
                            mutableStateOf(TextFieldValue(inputMsg))
                        }
                        LaunchedEffect(inputMsg) {
                            if (textValue.text != inputMsg) {
                                textValue = textValue.copy(
                                    text = inputMsg, selection = TextRange(inputMsg.length)
                                )
                            }
                        }
                        val onSurface = MaterialTheme.colorScheme.onSurface
                        val view = androidx.compose.ui.platform.LocalView.current
                        BasicTextField(
                            value = textValue,
                            onValueChange = { tfv ->
                                if (tfv.text != textValue.text) SharedState.update(tfv.text)
                                textValue = tfv
                            },
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, Color.Gray, RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .onFocusChanged { state ->
                                    val service = FloatingWindowService.instance
                                    if (state.isFocused) {
                                        service?.isWindowFocusable = true
                                        layoutParams.flags =
                                            (layoutParams.flags and FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                        layoutParams.softInputMode =
                                            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                        try {
                                            mWindowManager.updateViewLayout(
                                                mFloatingView, layoutParams
                                            )
                                        } catch (_: Exception) {
                                        }
                                        view.postDelayed({
                                            val imm =
                                                context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                            imm.showSoftInput(
                                                view, InputMethodManager.SHOW_IMPLICIT
                                            )
                                        }, 100)
                                    } else {
                                        service?.isWindowFocusable = false
                                        val imm =
                                            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                        imm.hideSoftInputFromWindow(
                                            view.windowToken, 0
                                        )
                                        layoutParams.flags =
                                            layoutParams.flags or FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                        layoutParams.softInputMode =
                                            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                                        try {
                                            mWindowManager.updateViewLayout(
                                                mFloatingView, layoutParams
                                            )
                                        } catch (_: Exception) {
                                        }
                                    }
                                },
                            textStyle = TextStyle(
                                color = onSurface, fontSize = 14.sp, lineHeight = 20.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 3,
                            decorationBox = { innerTextField ->
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (textValue.text.isEmpty()) {
                                        Text(
                                            text = context.getString(R.string.input_command_hint),
                                            style = TextStyle(
                                                color = Color.Gray,
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp
                                            ),
                                            maxLines = 1
                                        )
                                    }
                                    innerTextField()
                                }
                            })
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
                                                if (lastMsgIncomplete && msgs.last().role == "assistant") msgs.removeAt(
                                                    msgs.lastIndex
                                                )
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
                                                    delay(500.milliseconds)
                                                }
                                                send()
                                                if (!SharedState._usesVirtualDisplay.value) {
                                                    withContext(Dispatchers.IO) {
                                                        suInstance.execute("ime set com.android.adbkeyboard/.AdbIME")
                                                    }
                                                }
                                                updateNotification(
                                                    context,
                                                    context.getString(R.string.executing)
                                                )
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
                                                updateNotification(
                                                    context,
                                                    context.getString(R.string.executing)
                                                )
                                            }
                                            // 正在运行，取消任务
                                            else -> {
                                                cancelRequested.set(true)
                                                streamCallRef.getAndSet(null)?.cancel()
                                                streamJobRef.getAndSet(null)?.cancel()
                                                runningState = RunningState.STOP
                                                updateNotification(
                                                    context,
                                                    context.getString(R.string.cancelled)
                                                )
                                                context.sendBroadcast(Intent("ACTION_SHOW_FLOATING"))
                                                if (!SharedState._usesVirtualDisplay.value) {
                                                    withContext(Dispatchers.IO) {
                                                        suInstance.execute("ime set $ime")
                                                    }
                                                }
                                                if (msgs.last().role == "user" || msgs.last().role == "assistant" && msgs.last().content.value.asJsonPrimitive.asString.isEmpty()) msgs.removeAt(
                                                    msgs.lastIndex
                                                )
                                                else if (msgs.last().role == "assistant" && msgs.last().content.value.asJsonPrimitive.asString.isNotEmpty()) lastMsgIncomplete =
                                                    true
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