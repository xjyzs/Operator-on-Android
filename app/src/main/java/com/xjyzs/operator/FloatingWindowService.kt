package com.xjyzs.operator


import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.RingtoneManager
import android.os.Build
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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
import com.xjyzs.operator.utils.PACKAGES_APP
import com.xjyzs.operator.utils.VirtualDisplayManager
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
    }

    private lateinit var lifecycleOwner: MyLifecycleOwner
    private lateinit var mWindowManager: WindowManager
    private lateinit var mFloatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
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
            "panel", "悬浮面板", NotificationManager.IMPORTANCE_LOW
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


    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        CpuFreq.destroy()
        serviceScope.cancel()

        if (isViewAdded) {
            try {
                layoutListener?.let {
                    mFloatingView.viewTreeObserver.removeOnGlobalLayoutListener(it)
                }
                mWindowManager.removeView(mFloatingView)
            } catch (e: Exception) {
                android.util.Log.e("FloatingWindowService", "Failed to remove view", e)
            }
            isViewAdded = false
        }
    }

    override fun onAccessibilityEvent(p0: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun createVirtualDisplay(context: Context) {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val densityDpi = dm.densityDpi
        val surface = android.view.Surface(android.graphics.SurfaceTexture(false))
        VirtualDisplayManager.create(context, width, height, densityDpi, surface)
    }

    fun captureLayout(displayId: Int? = null): String {
        val rootNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val targetId = displayId ?: 0

            // 1. 核心修复：getWindows() 只能拿到主屏，获取特定/所有屏幕必须用 windowsOnAllDisplays
            // 它返回一个 SparseArray，通过目标 targetId 获取对应的窗口列表
            val displayWindows = windowsOnAllDisplays.get(targetId) ?: emptyList()

            // 2. 健壮性修复：必须加上 it.root != null 的前置判断。
            // 如果系统返回了虚拟屏的 TYPE_APPLICATION 窗口，但画面还没渲染完成导致 root 为 null，
            // 需避免 appWindow 不为 null 但 appWindow.root 为 null 的情况。
            val appWindow = displayWindows.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null }
                ?: displayWindows.firstOrNull { it.root != null }

            // 3. 逻辑漏洞修复：如果明确要抓取虚拟屏，并且确实没找到节点时，直接返回 null 并报错，
            // 绝对不能再去拿 rootInActiveWindow（否则用户在操作主屏时依然会拿到主屏内容）。
            appWindow?.root ?: if (targetId == 0) rootInActiveWindow else null
        } else {
            rootInActiveWindow
        } ?: return JSONObject().apply {
            put("error", "无法获取 rootNode")
        }.toString()

        val screenWidth = if (displayId != null && displayId == VirtualDisplayManager.getDisplayId() && VirtualDisplayManager.getWidth() > 0) {
            VirtualDisplayManager.getWidth()
        } else {
            resources.displayMetrics.widthPixels
        }
        val screenHeight = if (displayId != null && displayId == VirtualDisplayManager.getDisplayId() && VirtualDisplayManager.getHeight() > 0) {
            VirtualDisplayManager.getHeight()
        } else {
            resources.displayMetrics.heightPixels
        }
        val elementList = mutableListOf<ElementInfo>()

        // 遍历抓取节点
        traverseNode(rootNode, elementList, screenWidth, screenHeight)

        val sb = StringBuilder()
        for (el in elementList) {
            if (el.text.isEmpty() || el.text.length > 100 && "base64," in el.text) continue
            val type = el.className.substringAfterLast(".")
            val parts = mutableListOf<String>()
            if (el.position == null) continue
            parts.add("pos:[${el.position.first},${el.position.second}]")
//            if (el.isClickable) {
//                parts.add("Clickable")
//            }
            sb.append("[${type}]${el.text}")
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
                    if (bounds.right in 0 until screenWidth && bounds.bottom in 0 until screenHeight) {
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
        isRunning = true
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

    val _virtualDisplayId = MutableStateFlow<Int?>(null)
    val virtualDisplayId = _virtualDisplayId.asStateFlow()

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
val panelMaxWidthDp = 600.dp

data class Msg(
    val role: String, var content: MutableState<JsonElement>
)

enum class RunningState {
    STOP, RUNNING, TAKE_OVER, CONNECTING
}

@SuppressLint(
    "LocalContextResourcesRead", "DiscouragedApi", "InternalInsetResource", "QueryPermissionsNeeded"
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
                        """你是一个专业的移动端智能体分析专家。你的任务是根据当前屏幕截图和历史操作，输出下一步操作来完成用户的任务。
# 输出格式要求
第一段：简短的推理说明。包含：当前页面的关键信息、上一步操作是否生效、为什么选择下一步操作。
第二段：单独一行具体的指令代码，不要加任何标点符号或额外文字。

# 操作指令字典
请严格使用以下指令，且坐标 [x,y] 的范围【绝对禁止】超过 999！坐标是千分比(0-999)，不是实际像素！
- do(action="Launch", app="xxx") : 启动目标app的唯一正确方式！绝对禁止使用 Home 退回桌面去滑动找图标，除非Launch没反应！
- do(action="Tap", element=[x,y]) : 点击特定点。坐标必须在 [0,0] 到 [999,999] 之间。
- do(action="Type", text="xxx") : 在当前已聚焦激活的输入框输入文本（输入前会自动清除原文本）。如果屏幕底部显示 'ADB Keyboard {ON}' 类似的文本，则代表键盘已打开
- do(action="Swipe", start=[x1,y1], end=[x2,y2]) : 滑动操作。常用于滚动、查找。坐标必须在 [0-999] 范围内。
- do(action="Long Press", element=[x,y]) : 长按操作，用于唤出菜单等。
- do(action="Double Tap", element=[x,y]) : 双击特定点。
- do(action="Take_over", message="xxx") : 遇到登录或安全验证，请求人类接管。
- do(action="Back") : 返回上一级屏幕或关闭弹窗。
- do(action="Home") : 回到系统桌面。
- do(action="Wait", duration="x seconds") : 等待页面加载（例如 duration="3 seconds"）。
- finish(message="xxx") : 任务准确完成时调用，message为最终结果说明。

# 必须严格遵循的规则
## 死循环预防机制（极重要）
状态校验：在第一段推理中，必须对比历史状态。如果执行了 Tap 或 Swipe 后，当前界面与之前高度相似（操作未生效），【绝对禁止】重复完全相同的操作！
破局策略：如果遇到操作无效，必须更换策略：稍微偏移坐标重新点击、增大滑动距离、反向滑动、点击左上角返回键、或者直接跳过该步骤。
## 提高触控精度
系统可能会提供部分元素的坐标，如果其中有你需要的，请尽量使用这些坐标。

# 场景与任务规则
系统浏览器：如需打开浏览器，请启动浏览器。
异常处理：进入无关页面先 Back；网络异常点击刷新；页面未加载最多连续 Wait 3次，否则 Back 重试。
搜索与查找：找不到目标（联系人/商品/店铺）时尝试 Swipe 滑动。如果没有合适结果，返回上一级重新尝试；连续3次搜索无果，执行 finish(message="原因")。
意图泛化：严格遵循用户意图，但允许灵活变通。如要求“咸咖啡”，可搜索“咸咖啡”或搜“咖啡”后滑动找“海盐”；找“XX群”找不到时，去掉“群”字重新搜索。
筛选条件：价格、时间等筛选条件若无完全匹配的，可适当放宽要求。
视频播放器：某些播放器会自动隐藏控制界面，你可能需要点击屏幕以显示控制界面并在单次回复中下达多个操作。
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
                context, "未授予读取应用列表权限，可能无法启动或识别所有应用", Toast.LENGTH_SHORT
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
        updatePriorityMapping(
            getDefaultBrowserPackage(context) ?: "com.android.browser", "系统浏览器"
        )
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
            if (msgs.last().role == "user") msgs.removeLast()
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
                    updateNotification(context, "执行中")
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
                                        updateNotification(context, "已取消")
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
                        updateNotification(context, "已取消")
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    runningState = RunningState.STOP
                    updateNotification(context, "错误")
                    val intent = Intent(context, DialogActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    intent.putExtra("title", "错误")
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
                        val virtualDisplayId = if (usesVirtualDisplay) SharedState._virtualDisplayId.value else null
                        operation(
                            found.groups["action"]!!.value, args, context, mFloatingView, virtualDisplayId
                        )
                        delay(1500)
                    } catch (_: Exception) {
                    }
                } else {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "ime set $ime"))
                    withContext(Dispatchers.Main) {
                        runningState = RunningState.TAKE_OVER
                        updateNotification(context, "请接管")
                    }
                    return@launch
                }
            }
            val found = finishRe.find(lastMsgText)
            if (found != null) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "ime set $ime"))
                withContext(Dispatchers.Main) {
                    runningState = RunningState.STOP
                    updateNotification(context, "已完成")
                    val channel = NotificationChannel(
                        "finish", "任务完成提醒", NotificationManager.IMPORTANCE_HIGH
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
                        NotificationCompat.Builder(context, "finish").setContentTitle("任务已完成!")
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
                        msgs.add(index, Msg("user", mutableStateOf(JsonArray().apply { add(textObj) })))
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
                                            hint = "输入指令..."
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
                                                    if (lastMsgIncomplete && msgs.last().role == "assistant") msgs.removeLast()
                                                    SharedState._newMsg.value = inputMsg
                                                    val process = Runtime.getRuntime().exec(
                                                        arrayOf(
                                                            "su",
                                                            "-c",
                                                            "settings get secure default_input_method"
                                                        )
                                                    )
                                                    ime = process.inputStream.bufferedReader()
                                                        .use { it.readText() }.trim()
                                                    process.waitFor()
                                                    if (SharedState._usesVirtualDisplay.value && !VirtualDisplayManager.isCreated()) {
                                                        withContext(Dispatchers.Main) {
                                                            FloatingWindowService.instance?.createVirtualDisplay(context)
                                                        }
                                                        delay(500)
                                                    }
                                                    send()
                                                    Runtime.getRuntime().exec(
                                                        arrayOf(
                                                            "su",
                                                            "-c",
                                                            "ime set com.android.adbkeyboard/.AdbIME"
                                                        )
                                                    )
                                                    updateNotification(context, "执行中")
                                                }

                                                RunningState.TAKE_OVER -> {
                                                    clearInputFocusAndAwait()
                                                    SharedState._newMsg.value = inputMsg
                                                    runningState = RunningState.CONNECTING
                                                    send()
                                                    Runtime.getRuntime().exec(
                                                        arrayOf(
                                                            "su",
                                                            "-c",
                                                            "ime set com.android.adbkeyboard/.AdbIME"
                                                        )
                                                    )
                                                    updateNotification(context, "执行中")
                                                }
                                                // 正在运行，取消任务
                                                else -> {
                                                    cancelRequested.set(true)
                                                    streamCallRef.getAndSet(null)?.cancel()
                                                    streamJobRef.getAndSet(null)?.cancel()
                                                    runningState = RunningState.STOP
                                                    updateNotification(context, "已取消")
                                                    context.sendBroadcast(Intent("ACTION_SHOW_FLOATING"))
                                                    Runtime.getRuntime()
                                                        .exec(arrayOf("su", "-c", "ime set $ime"))
                                                    if (msgs.last().role == "user" || msgs.last().role == "assistant" && msgs.last().content.value.asJsonPrimitive.asString.isEmpty()) msgs.removeAt(
                                                        msgs.lastIndex
                                                    )
                                                    else if (msgs.last().role == "assistant" && msgs.last().content.value.asJsonPrimitive.asString.isNotEmpty()) lastMsgIncomplete =
                                                        true
                                                    val serializableMsgs = msgs.map { msg ->
                                                        mapOf(
                                                            "role" to msg.role,
                                                            "content" to msg.content.value
                                                        )
                                                    }
                                                    val bodyMap = mapOf(
                                                        "model" to model,
                                                        "messages" to serializableMsgs.toList(),
                                                        "stream" to true
                                                    )
                                                    val requestBody = Gson().toJson(bodyMap)
                                                    File("/data/user/0/${context.packageName}/1.json").writeText(
                                                        requestBody
                                                    )
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
