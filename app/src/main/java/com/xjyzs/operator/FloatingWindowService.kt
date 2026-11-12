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
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
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
import com.xjyzs.operator.utils.InputControlUtils
import com.xjyzs.operator.utils.LayoutResp
import com.xjyzs.operator.utils.MyLifecycleOwner
import com.xjyzs.operator.utils.data.DisplayMetricsStore.height
import com.xjyzs.operator.utils.data.DisplayMetricsStore.width
import com.xjyzs.operator.utils.data.SharedState
import com.xjyzs.operator.utils.execute.CPUFreq
import com.xjyzs.operator.utils.execute.screenshot
import com.xjyzs.operator.utils.ui.FloatingOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
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
        fun getLayout(displayId: Int? = null, currentAppPackageName: String): LayoutResp {
            if (SharedState.isIgnoreAppLayout(currentAppPackageName)) return LayoutResp("", false)
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
        CPUFreq.destroy()
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

        val tree = buildTree(rootNode, null, 0, BuildCtx(MAX_NODES), isRoot = true)
            ?: return LayoutResp("", false)

        val minPx = (4 * resources.displayMetrics.density).toInt().coerceAtLeast(3)
        val elements = extractElements(tree, width, height, minPx)

        val sb = StringBuilder()
        rootNode.packageName?.let { sb.append("app:").appendLine(it) }
        for (e in elements) sb.appendLine(render(e, width, height))
        return LayoutResp(sb.toString().trimEnd(), checkAccessibilityReadable(elements))
    }

    private val MAX_DEPTH = 50
    private val MAX_NODES = 1500
    private val MAX_INTERACTIVE = 100
    private val MAX_TEXT = 200
    private val CURRENCY = "¥￥\$€£₩"
    private val WS = Regex("\\s+")
    private val OPEN_BRACKET_WS = Regex("([(\\[{（【])\\s+")
    private val CLOSE_BRACKET_WS = Regex("\\s+([)\\]}）】])")
    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val OBFUSCATED_ID = Regex("^(?:[a-z]{1,2}|[a-z]?\\d+[a-z]?\\d*)$")
    private val GRID = doubleArrayOf(0.5, 0.25, 0.75, 0.1, 0.9)

    private class BuildCtx(var left: Int)

    private class UiNode(
        val rect: Rect,
        val type: String,
        val label: String,
        val idHint: String,
        val clickable: Boolean,
        val editable: Boolean,
        val emptyInput: Boolean,
        var scroll: String,
        val checkState: Int,
        val selected: Boolean,
        val enabled: Boolean,
        var hidden: String = ""
    ) {
        val children = ArrayList<UiNode>(2)
        var parent: UiNode? = null
        var order = 0
        var hasInteractiveDesc = false
        val interactive get() = clickable || editable
        val area get() = rect.width().toLong() * rect.height()
    }

    private class Cand(val node: UiNode, var label: String, val coversChildren: Boolean)

    private class LayoutEl(
        var type: String,
        var label: String,
        var flags: String,
        var px: Int,
        var py: Int,
        val rect: Rect,
        var interactive: Boolean
    )

    private fun buildTree(
        node: AccessibilityNodeInfo,
        parent: UiNode?,
        depth: Int,
        ctx: BuildCtx,
        isRoot: Boolean = false
    ): UiNode? {
        if (!isRoot && !node.isVisibleToUser) return null
        if (depth > MAX_DEPTH || ctx.left <= 0) return null
        ctx.left--

        val rect = Rect().also { node.getBoundsInScreen(it) }
        val editable = node.isEditable
        val u = UiNode(
            rect = rect,
            type = shortType(node.className?.toString().orEmpty()),
            label = ownLabel(node),
            idHint = idHint(node),
            clickable = node.isClickable || node.isCheckable,
            editable = editable,
            emptyInput = editable && node.text.isNullOrEmpty(),
            scroll = scrollDirs(node),
            checkState = if (!node.isCheckable) 0 else if (node.isChecked) 2 else 1,
            selected = node.isSelected,
            enabled = node.isEnabled
        )
        u.parent = parent

        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            try {
                if (!c.isVisibleToUser) {
                    // 不可见子树不输出，但可为无标签的可交互父节点提供文字
                    if (u.interactive && u.label.isEmpty() && u.hidden.length < 100) {
                        u.hidden = (u.hidden + " " + harvestLabel(c)).trim()
                    }
                } else {
                    buildTree(c, u, depth + 1, ctx)?.let { u.children.add(it) }
                }
            } finally {
                recycleCompat(c)
            }
        }
        u.hasInteractiveDesc = u.children.any { it.interactive || it.hasInteractiveDesc }
        return u
    }

    @Suppress("DEPRECATION")
    private fun recycleCompat(n: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) try {
            n.recycle()
        } catch (_: Exception) {
        }
    }

    private fun shortType(cls: String) = when {
        cls.contains("EditText") -> "Edit"
        cls.contains("Button") -> "Btn"
        cls.contains("ImageView") -> "Img"
        cls.contains("CheckBox") -> "Check"
        cls.contains("Switch") || cls.contains("Toggle") -> "Switch"
        cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("ScrollView") || cls.contains(
            "ViewPager"
        ) -> "Scroll"

        cls.contains("TextView") -> "Text"
        else -> "View"
    }

    private fun cut(s: String, max: Int) = if (s.length > max) s.take(max) + "…" else s

    /** 清洗文本：折叠空白、压缩括号内空格、截断；只去掉 Unicode 私有区字符和 base64 */
    private fun clean(s: CharSequence?, max: Int): String {
        if (s.isNullOrBlank()) return ""
        var t = s.toString()
        if (t.length > 100 && "base64," in t) return ""
        t = t.filter { Character.getType(it) != Character.PRIVATE_USE.toInt() }
        t = WS.replace(t, " ").trim()
        t = OPEN_BRACKET_WS.replace(t, "$1")
        t = CLOSE_BRACKET_WS.replace(t, "$1")
        return cut(t, max)
    }

    private fun ownLabel(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return if (n.text.isNullOrEmpty()) "" else "••••"
        val interactive = n.isClickable || n.isCheckable || n.isEditable
        val max = if (interactive) MAX_INTERACTIVE else MAX_TEXT

        val c = ArrayList<CharSequence?>(10)
        c.add(n.text)
        c.add(n.contentDescription)
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                val method = n.javaClass.getMethod("getSupplementalDescription")
                c.add(method.invoke(n) as? CharSequence)
            } catch (_: Exception) {
            }
        }
        n.extras?.let { extras ->
            c.add(extras.getCharSequence("androidx.view.accessibility.AccessibilityNodeInfoCompat.SUPPLEMENTAL_DESCRIPTION_KEY"))
            c.add(extras.getCharSequence("AccessibilityNodeInfo.roleDescription"))
        }

        if (Build.VERSION.SDK_INT >= 26) c.add(n.hintText)
        if (Build.VERSION.SDK_INT >= 28) {
            c.add(n.tooltipText); c.add(n.paneTitle)
        }
        if (Build.VERSION.SDK_INT >= 30) c.add(n.stateDescription)
        if (Build.VERSION.SDK_INT >= 34) c.add(n.containerTitle)

        for (x in c) clean(x, max).let { if (it.isNotEmpty()) return it }
        if (interactive) {
            val lb = try {
                n.labeledBy
            } catch (_: Exception) {
                null
            }
            if (lb != null) {
                try {
                    val lbLabel = ownLabel(lb)
                    if (lbLabel.isNotEmpty()) return cut(lbLabel, max)
                } finally {
                    recycleCompat(lb)
                }
            }
        }
        return ""
    }

    /** 收集被剪掉（不可见）子树里的文字，只给无标签的可交互父节点提供标签 */
    private fun harvestLabel(
        n: AccessibilityNodeInfo, depth: Int = 0, budget: IntArray = intArrayOf(12)
    ): String {
        if (depth > 3 || budget[0]-- <= 0) return ""
        ownLabel(n).let { if (it.isNotEmpty()) return it }
        val parts = ArrayList<String>()
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            try {
                harvestLabel(c, depth + 1, budget).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            } finally {
                recycleCompat(c)
            }
        }
        return parts.joinToString(" ")
    }

    /** 资源 id 的语义名 */
    private fun idHint(n: AccessibilityNodeInfo): String {
        val id = n.viewIdResourceName?.substringAfter("id/", "") ?: return ""
        return if (id.length >= 3 && !OBFUSCATED_ID.matches(id)) id else ""
    }

    /** 箭头滚动标识 */
    private fun scrollDirs(n: AccessibilityNodeInfo): String {
        if (!n.isScrollable) return ""
        val a = n.actionList
        val sb = StringBuilder()
        if (Build.VERSION.SDK_INT >= 23) {
            if (a.contains(AccessibilityAction.ACTION_SCROLL_UP)) sb.append('↑')
            if (a.contains(AccessibilityAction.ACTION_SCROLL_DOWN)) sb.append('↓')
            if (a.contains(AccessibilityAction.ACTION_SCROLL_LEFT)) sb.append('←')
            if (a.contains(AccessibilityAction.ACTION_SCROLL_RIGHT)) sb.append('→')
        }
        if (sb.isEmpty()) {
            if (a.contains(AccessibilityAction.ACTION_SCROLL_BACKWARD)) sb.append('↑')
            if (a.contains(AccessibilityAction.ACTION_SCROLL_FORWARD)) sb.append('↓')
        }
        return sb.toString()
    }

    /** 滚动方向只在容器尺寸足够大时才有意义 */
    private fun effScroll(n: UiNode, w: Int, h: Int): String = n.scroll.filter {
        ((it == '↑' || it == '↓') && n.rect.height() >= h / 4) || ((it == '←' || it == '→') && n.rect.width() >= w / 4)
    }

    private fun tokens(s: String): List<String> =
        s.lowercase().split(TOKEN_SPLIT).filter { it.isNotEmpty() }

    private fun containsTokens(big: List<String>, small: List<String>): Boolean {
        if (small.isEmpty() || small.size > big.size) return false
        for (i in 0..big.size - small.size) {
            var ok = true
            for (j in small.indices) if (big[i + j] != small[j]) {
                ok = false; break
            }
            if (ok) return true
        }
        return false
    }

    private fun dedupeParts(parts: List<String>): List<String> {
        val toks = parts.map { tokens(it) }
        return parts.filterIndexed { i, _ ->
            toks[i].isEmpty() || toks.indices.none { j ->
                j != i && containsTokens(
                    toks[j], toks[i]
                ) && (toks[j].size > toks[i].size || (toks[j].size == toks[i].size && j < i))
            }
        }
    }

    private fun joinParts(parts: List<String>, max: Int): String {
        val sb = StringBuilder()
        var prev = ""
        for (p in parts) {
            if (sb.isNotEmpty() && !(prev.length == 1 && prev[0] in CURRENCY)) sb.append(' ')
            sb.append(p)
            prev = p
        }
        return cut(sb.toString(), max)
    }

    private fun isPureText(n: UiNode) =
        n.label.isNotEmpty() && n.children.isEmpty() && !n.interactive && n.scroll.isEmpty() && !n.selected && n.checkState == 0

    /** 同一行且间距小于字高的 0.4 倍，视为同一句话被拆成的行内片段 */
    private fun isInline(a: UiNode, b: UiNode): Boolean {
        val ov = minOf(a.rect.bottom, b.rect.bottom) - maxOf(a.rect.top, b.rect.top)
        val minH = minOf(a.rect.height(), b.rect.height())
        if (minH <= 0 || ov * 2 < minH) return false
        val gap = maxOf(b.rect.left - a.rect.right, a.rect.left - b.rect.right) // 兼容 RTL
        return gap <= minH * 0.4
    }

    /** 只合并同一父节点下"相邻"的行内片段，不同栏、不同列的间距远大于阈值，不会被合并 */
    private fun groupInlineText(n: UiNode) {
        n.children.forEach { groupInlineText(it) }
        if (n.children.size < 2) return
        val out = ArrayList<UiNode>(n.children.size)
        var cur = ArrayList<UiNode>()
        fun flush() {
            if (cur.size >= 2) {
                val union = Rect(cur[0].rect).also { u -> cur.forEach { u.union(it.rect) } }
                out.add(
                    UiNode(
                        union,
                        "Text",
                        joinParts(dedupeParts(cur.map { it.label }), MAX_TEXT),
                        "",
                        false,
                        false,
                        false,
                        "",
                        0,
                        false,
                        true
                    ).also { it.parent = n })
            } else out.addAll(cur)
            cur = ArrayList()
        }
        for (c in n.children) {
            if (!isPureText(c)) {
                flush(); out.add(c); continue
            }
            if (cur.isNotEmpty() && !isInline(cur.last(), c)) flush()
            cur.add(c)
        }
        flush()
        n.children.clear()
        n.children.addAll(out)
    }

    private fun extractElements(root: UiNode, w: Int, h: Int, minPx: Int): List<LayoutEl> {
        if (w <= 0 || h <= 0) return emptyList()

        groupInlineText(root)
        val all = ArrayList<UiNode>()
        fun flatten(n: UiNode) {
            n.order = all.size
            n.scroll = effScroll(n, w, h)
            all.add(n)
            for (c in n.children) flatten(c)
        }
        flatten(root)

        val screenArea = w.toLong() * h
        val cands = ArrayList<Cand>()
        fun visit(n: UiNode, covered: Boolean) {
            var cc = covered
            if (!covered) {
                decide(n, w, h, screenArea, minPx)?.let {
                    cands.add(it)
                    if (it.coversChildren) cc = true
                }
            }
            for (c in n.children) visit(c, cc)
        }
        visit(root, false)

        val overlays = HashSet<UiNode>()
        for (c in cands) {
            if (c.label != "?" || !c.node.interactive) continue
            val inside = cands.count { o ->
                o !== c && o.label != "?" && !isRelated(
                    o.node, c.node
                ) && c.node.rect.contains(o.node.rect)
            }
            if (inside >= 2) overlays.add(c.node)
        }
        cands.removeAll { it.node in overlays }

        val blockers =
            all.filter { it.interactive && it.area <= screenArea * 0.7 && it !in overlays }

        val out = ArrayList<LayoutEl>()
        for (c in cands) {
            val n = c.node
            val vis = visibleRect(n.rect, w, h) ?: continue
            val point: Pair<Int, Int> = if (n.interactive) {
                val bl = blockers.filter {
                    it.order > n.order && !isRelated(it, n) && Rect.intersects(it.rect, vis)
                }
                pickPoint(vis, bl) ?: continue
            } else {
                vis.centerX() to vis.centerY()
            }

            out.add(
                LayoutEl(
                    typeOf(n), c.label, flagsOf(n), point.first, point.second, vis, n.interactive
                )
            )
        }
        return mergeDuplicates(out)
    }

    private fun decide(n: UiNode, w: Int, h: Int, screenArea: Long, minPx: Int): Cand? {
        if (visibleRect(n.rect, w, h) == null) return null
        val own = n.label

        // 尺寸小到无法被看清的节点
        val tiny = if (n.interactive) n.rect.width() < minPx || n.rect.height() < minPx
        else n.rect.height() < minPx || (n.rect.width() < minPx && own.length > 1)
        if (tiny && n.scroll.isEmpty()) return null

        // 可交互节点
        if (n.interactive) {
            if (n.hasInteractiveDesc) {
                return if (own.isEmpty()) null else Cand(n, own, false)
            }
            val parts = ArrayList<String>()
            descendantTexts(n, parts)
            if (n.hidden.isNotEmpty()) parts.add(cut(n.hidden, 40))
            val merged = joinParts(
                dedupeParts((if (own.isNotEmpty()) listOf(own) else emptyList()) + parts),
                MAX_INTERACTIVE
            )
            val label = merged.ifEmpty { if (n.idHint.isNotEmpty()) "#${n.idHint}" else "?" }
            return Cand(n, label, true)
        }

        // 可滚动容器
        if (n.scroll.isNotEmpty()) {
            return Cand(n, if (n.idHint.isNotEmpty()) "#${n.idHint}" else "", false)
        }

        // 已勾选的节点
        val small = n.area <= screenArea / 4
        if ((n.selected || n.checkState == 2) && small) {
            if (own.isNotEmpty()) return Cand(n, own, !n.hasInteractiveDesc)
            if (!n.hasInteractiveDesc) {
                val parts = ArrayList<String>()
                descendantTexts(n, parts)
                if (parts.isNotEmpty()) return Cand(
                    n, joinParts(dedupeParts(parts), MAX_TEXT), true
                )
            }
            return null
        }

        // 普通带文字节点
        if (own.isNotEmpty()) return Cand(n, own, !n.hasInteractiveDesc)
        return null
    }

    /** 收集子孙文字，有文字时丢弃图片类子节点的描述，避免"图片说明"混进标题 */
    private fun descendantTexts(n: UiNode, out: MutableList<String>, limit: Int = 8) {
        val txt = ArrayList<String>()
        val img = ArrayList<String>()
        fun walk(x: UiNode) {
            for (c in x.children) {
                if (txt.size >= limit) return
                if (c.label.isNotEmpty()) (if (c.type == "Img") img else txt).add(cut(c.label, 40))
                else walk(c)
            }
        }
        walk(n)
        out.addAll(if (txt.isNotEmpty()) txt else img)
    }

    private fun typeOf(n: UiNode) = when {
        n.editable -> "Edit"
        n.scroll.isNotEmpty() && !n.interactive -> "Scroll"
        else -> n.type
    }

    private fun flagsOf(n: UiNode): String {
        val f = ArrayList<String>(3)
        if (n.selected) f.add("sel")
        when (n.checkState) {
            2 -> f.add("✓"); 1 -> f.add("☐")
        }
        if (!n.enabled) f.add("off")
        if (n.editable) {
            f.add("edit"); if (n.emptyInput) f.add("empty")
        }
        if (n.scroll.isNotEmpty()) f.add("scroll${n.scroll}")
        return f.joinToString(",")
    }

    private fun visibleRect(r: Rect, w: Int, h: Int): Rect? {
        val v = Rect(r)
        if (!v.intersect(0, 0, w, h)) return null
        return if (v.width() >= 4 && v.height() >= 4) v else null
    }

    private fun isAncestor(a: UiNode, b: UiNode): Boolean {
        var p = b.parent
        while (p != null) {
            if (p === a) return true; p = p.parent
        }
        return false
    }

    private fun isRelated(a: UiNode, b: UiNode) = isAncestor(a, b) || isAncestor(b, a)

    /** 先试中心，再试网格点，返回第一个没被遮挡的点 */
    private fun pickPoint(v: Rect, blockers: List<UiNode>): Pair<Int, Int>? {
        for (fy in GRID) for (fx in GRID) {
            val x = (v.left + v.width() * fx).toInt()
            val y = (v.top + v.height() * fy).toInt()
            if (blockers.none { it.rect.contains(x, y) }) return x to y
        }
        return null
    }

    private fun area(r: Rect) = r.width().toLong() * r.height()

    private fun interArea(a: Rect, b: Rect): Long {
        val iw = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (iw > 0 && ih > 0) iw.toLong() * ih else 0L
    }

    private fun sameThing(a: LayoutEl, b: LayoutEl): Boolean {
        if (a.type == "Scroll" || b.type == "Scroll") return false
        val inter = interArea(a.rect, b.rect)
        if (inter == 0L) return false
        val aa = area(a.rect);
        val ba = area(b.rect)
        val iou = inter.toDouble() / (aa + ba - inter)
        if (iou >= 0.8) return true
        val aq = a.label == "?";
        val bq = b.label == "?"
        if (aq || bq) return iou >= 0.6
        val minA = minOf(aa, ba).coerceAtLeast(1)
        return inter.toDouble() / minA >= 0.6 && (containsTokens(
            tokens(a.label), tokens(b.label)
        ) || containsTokens(
            tokens(b.label), tokens(a.label)
        ))
    }

    private fun mergeInto(prev: LayoutEl, e: LayoutEl) {
        prev.label = when {
            e.label.isEmpty() || e.label == "?" -> prev.label
            prev.label.isEmpty() || prev.label == "?" -> e.label
            else -> {
                val tp = tokens(prev.label);
                val te = tokens(e.label)
                when {
                    containsTokens(tp, te) -> prev.label
                    containsTokens(te, tp) -> e.label
                    else -> prev.label + "/" + e.label
                }
            }
        }
        // 两者一个可点一个不可点时，采用可点那个的坐标
        if (e.interactive && !prev.interactive) {
            prev.px = e.px; prev.py = e.py; prev.interactive = true
        }
        if (prev.flags.isEmpty()) prev.flags = e.flags
        if ((prev.type == "View" || prev.type == "Text") && e.type != "View" && e.type != "Text") prev.type =
            e.type
    }

    private fun mergeDuplicates(list: List<LayoutEl>): List<LayoutEl> {
        val merged = ArrayList<LayoutEl>()
        for (e in list) {
            val prev = merged.takeLast(12).lastOrNull { sameThing(it, e) }
            if (prev == null) merged.add(e) else mergeInto(prev, e)
        }
        return merged
    }

    private fun render(e: LayoutEl, w: Int, h: Int) = buildString {
        val x = (e.px * 999L / w).toInt().coerceIn(0, 999)
        val y = (e.py * 999L / h).toInt().coerceIn(0, 999)
        if (e.type != "View" && e.type != "Text") append('[').append(e.type).append(']')
        append(e.label)
        if (e.flags.isNotEmpty()) append(" {").append(e.flags).append('}')
        append(" (").append(x).append(',').append(y).append(')')
    }

    /** 判断无障碍获取的内容是否是空壳 */
    private fun checkAccessibilityReadable(elements: List<LayoutEl>): Boolean {
        val textElements = elements.filter { it.label.isNotBlank() }
        if (textElements.isEmpty()) return false

        // 中间有内容，说明可读
        val bodyTextCount = textElements.count {
            val middleY = (it.rect.top - it.rect.bottom) / 2
            middleY in 131..869
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
            info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
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
