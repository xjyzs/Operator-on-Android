package com.xjyzs.operator.utils

import android.app.NotificationManager
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.xjyzs.operator.DialogActivity
import com.xjyzs.operator.FloatingWindowService
import com.xjyzs.operator.R
import com.xjyzs.operator.RemoteBridge
import com.xjyzs.operator.utils.data.ChatMessage
import com.xjyzs.operator.utils.data.ChatRepository
import com.xjyzs.operator.utils.data.DisplayMetricsStore
import com.xjyzs.operator.utils.data.ImageMarker
import com.xjyzs.operator.utils.data.Role
import com.xjyzs.operator.utils.data.SharedState
import com.xjyzs.operator.utils.data.Status
import com.xjyzs.operator.utils.data.ToolCall
import com.xjyzs.operator.utils.data.toApiMessages
import com.xjyzs.operator.utils.execute.ActionFeedback
import com.xjyzs.operator.utils.execute.AgentTools
import com.xjyzs.operator.utils.execute.CPUFreq
import com.xjyzs.operator.utils.execute.MIN_DELAY_TIME_MILLISECOND
import com.xjyzs.operator.utils.execute.SHORT_DELAY_TIME_MILLISECOND
import com.xjyzs.operator.utils.execute.ShellExecutor
import com.xjyzs.operator.utils.execute.back
import com.xjyzs.operator.utils.execute.doubleTap
import com.xjyzs.operator.utils.execute.ensureAppsInit
import com.xjyzs.operator.utils.execute.enter
import com.xjyzs.operator.utils.execute.getAppName
import com.xjyzs.operator.utils.execute.getCurrentPkg
import com.xjyzs.operator.utils.execute.getDefaultLauncherPackage
import com.xjyzs.operator.utils.execute.lastMarker
import com.xjyzs.operator.utils.execute.longPress
import com.xjyzs.operator.utils.execute.screenshot
import com.xjyzs.operator.utils.execute.swipe
import com.xjyzs.operator.utils.execute.swipeUntil
import com.xjyzs.operator.utils.execute.tap
import com.xjyzs.operator.utils.execute.type
import com.xjyzs.operator.utils.execute.wait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

enum class RunningState { STOP, RUNNING, TAKE_OVER, CONNECTING }

val sharedOkHttpClient by lazy {
    androidx.startup.AppInitializer.getInstance(SharedState.appContext)
        .initializeComponent(okhttp3.internal.platform.PlatformInitializer::class.java)
    OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
}
val sharedGson = Gson()

object AgentController {
    private const val MAX_STEPS = 1000 // 最大步数，防止出错时死循环
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobRef = AtomicReference<Job?>(null)
    private val callRef = AtomicReference<Call?>(null)
    private val ctx get() = SharedState.appContext

    private val _state = MutableStateFlow(RunningState.STOP)
    val runningState: StateFlow<RunningState> = _state.asStateFlow()

    @Volatile
    private var sessionUsesVirtualDisplay: Boolean = true

    fun setState(s: RunningState) {
        _state.value = s
        RemoteBridge.runningState.value = s
    }

    private fun systemPrompt() = """你是移动端GUI自动化专家,请根据用户指令与屏幕信息操作设备完成任务
元素格式:[类型]文字 {状态} (x,y),坐标为0-999的归一化屏幕坐标
?表示可点击但无文字线索,请结合截图判断
#xxx是控件id名,可作为用途线索
状态:sel已选中,✓/☐勾选/未勾选,off禁用,edit输入框,empty输入框为空
scroll↓表示下方还有内容,↑表示上方还有内容,←→同理(要看下方内容需向上滑动)
没有scroll标记不代表一定不能滑动
屏幕上的品红准星或长箭头是你上次的操作,你可以据此判断上次是否点歪并修正
若界面无变化,优先尝试滑动刷新、重试或调整坐标,避免在同一位置重复无效操作
输入框:手机可能不显示输入法,点击输入框后,可以直接输入文字,无需等待竖线或键盘出现
浏览器:除非用户明确要求,否则优先使用系统浏览器
你可以在单轮回复中多次调用工具
启动应用:优先使用launch打开App
今天的日期是${
        LocalDate.now().format(
            DateTimeFormatter.ofPattern(
                "yyyy年MM月dd日EEEE", Locale.CHINA
            )
        )
    }"""

    fun send(text: String) {
        if (jobRef.get()?.isActive == true) return
        sessionUsesVirtualDisplay = SharedState._usesVirtualDisplay.value
        setState(RunningState.CONNECTING)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runAgent(text)
            } finally {
                jobRef.compareAndSet(coroutineContext.job, null)
                if (_state.value == RunningState.CONNECTING || _state.value == RunningState.RUNNING) {
                    onCancelled()
                }
            }
        }
        jobRef.set(job)
        job.start()
    }

    fun stop() {
        val job = jobRef.get()
        if (job != null && job.isActive) {
            job.cancel()
            callRef.get()?.cancel()
        } else if (_state.value == RunningState.TAKE_OVER) {
            scope.launch {
                onCancelled()
            }
        }
    }

    suspend fun awaitStopped() {
        jobRef.get()?.join()
    }

    suspend fun stopAndWait() {
        val job = jobRef.get()
        if (job != null && job.isActive) {
            job.cancel()
            callRef.get()?.cancel()
            job.join()
        } else if (_state.value == RunningState.TAKE_OVER) {
            onCancelled()
        }
    }

    private suspend fun appendUserMessage(text: String) {
        val msg = buildUserMessage(text, sessionUsesVirtualDisplay)
        ChatRepository.append(msg)
    }

    private suspend fun runAgent(text: String) {
        try {
            if (sessionUsesVirtualDisplay && InputControlUtils.displayId == -1) {
                InputControlUtils.init(ctx)
                InputControlUtils.createVirtualDisplay(
                    DisplayMetricsStore.width,
                    DisplayMetricsStore.height,
                    DisplayMetricsStore.densityDpi
                )
                if (InputControlUtils.displayId == -1) {
                    throw IllegalStateException("虚拟屏创建失败")
                }
            }
            ChatRepository.removeTrailing { it.role == Role.USER } // 上次出错/取消遗留的末尾 user
            lastMarker = null
            ActionFeedback.reset()
            appendUserMessage(text)
            repeat(MAX_STEPS) {
                val r = streamOnce()
                cleanupImages()

                // 未调用工具即视为完成
                if (r.toolCalls.isEmpty()) {
                    onFinished(null); return
                }

                val o = executeTools(r.toolCalls)
                when (o.kind) {
                    Kind.TAKE_OVER -> {
                        restoreIme()
                        setState(RunningState.TAKE_OVER)
                        NotificationUtils.hideTime()
                        NotificationUtils.update(
                            ctx,
                            ctx.getString(R.string.take_over),
                            ctx.getString(R.string.take_over)
                        )
                        return
                    }

                    Kind.FINISHED -> {
                        onFinished(o.message); return
                    }

                    Kind.CONTINUE -> Unit
                }
                appendUserMessage("") // 附最新截图
            }
        } catch (e: Exception) {
            if (e is CancellationException || !currentCoroutineContext().isActive) onCancelled()
            else handleError(e)
        }
    }

    private suspend fun cleanupImages() {
        val users = ChatRepository.messages.value.filter { it.role == Role.USER }
        val keepIds = users.takeLast(2).mapTo(HashSet()) { it.id }

        for (m in users) {
            if (m.inlineImage == null || m.id in keepIds) continue
            ChatRepository.update(m.id) { it.copy(inlineImage = null, imageMarker = null) }
        }
    }

    // 单轮流式请求处理
    private class ToolCallAcc {
        var id = "";
        var name = "";
        val args = StringBuilder()
    }

    private class StreamResult(val toolCalls: List<ToolCall>)

    private fun buildBody(): String = sharedGson.toJson(
        mapOf(
            "model" to SharedState.model,
            "messages" to ChatRepository.messages.value.toApiMessages(systemPrompt()),
            "tools" to AgentTools.all,
            "tool_choice" to "auto",
            "stream" to true,
            "stream_options" to mapOf("include_usage" to true),
        )
    )

    private suspend fun streamOnce(): StreamResult {
        val body = buildBody().toRequestBody("application/json".toMediaTypeOrNull())
        val call = sharedOkHttpClient.newCall(
            Request.Builder().url(SharedState.apiUrl).post(body)
                .addHeader("Authorization", "Bearer ${SharedState.apiKey}").build()
        )
        callRef.set(call)

        val text = StringBuilder()
        val reasoningContent = StringBuilder()
        val accs = sortedMapOf<Int, ToolCallAcc>()
        var assistantId: Long? = null
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException(response.body.string())
                setState(RunningState.RUNNING)
                val reader = response.body.charStream().buffered()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val json = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
                        ?: continue

                    SharedState.recordUsage(json)

                    val delta = json.getAsJsonArray("choices")
                        ?.firstOrNull()?.asJsonObject?.getAsJsonObject("delta") ?: continue

                    var updated = false

                    delta.get("reasoning_content")
                        ?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
                        ?.let { piece ->
                            reasoningContent.append(piece)
                            updated = true
                        }

                    delta.get("content")
                        ?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
                        ?.let { piece ->
                            text.append(piece)
                            updated = true
                        }

                    if (updated) {
                        val curReasoning = reasoningContent.toString().takeIf { it.isNotEmpty() }
                        val id = assistantId
                        if (id == null) {
                            assistantId = ChatRepository.append(
                                ChatMessage(
                                    role = Role.ASSISTANT,
                                    text = text.toString(),
                                    reasoningContent = curReasoning,
                                    status = Status.STREAMING
                                )
                            ).id
                        } else {
                            ChatRepository.update(id) {
                                it.copy(
                                    text = text.toString(), reasoningContent = curReasoning
                                )
                            }
                        }
                    }

                    delta.getAsJsonArray("tool_calls")?.forEach { e ->
                        val o = e.asJsonObject
                        val acc = accs.getOrPut(o.get("index")?.asInt ?: 0) { ToolCallAcc() }
                        o.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
                            ?.let { acc.id = it }
                        o.getAsJsonObject("function")?.let { f ->
                            f.get("name")?.takeIf { !it.isJsonNull }?.asString?.let {
                                if (acc.name.isEmpty()) acc.name = it
                            }
                            f.get("arguments")
                                ?.takeIf { !it.isJsonNull }?.asString?.let { acc.args.append(it) }
                        }
                    }
                }
            }
        } finally {
            callRef.compareAndSet(call, null)
        }

        val calls = accs.values.filter { it.name.isNotEmpty() }.map {
            ToolCall(
                it.id.ifEmpty { "call_" + UUID.randomUUID().toString().take(8) },
                it.name,
                it.args.toString().ifBlank { "{}" })
        }

        val finalReasoning = reasoningContent.toString().takeIf { it.isNotEmpty() }
        val id = assistantId
        when {
            id != null -> ChatRepository.update(id, persist = true) {
                it.copy(
                    reasoningContent = finalReasoning,
                    text = text.toString(),
                    toolCalls = calls,
                    status = Status.DONE
                )
            }

            calls.isNotEmpty() -> ChatRepository.append(
                ChatMessage(role = Role.ASSISTANT, toolCalls = calls)
            )
        }
        return StreamResult(calls)
    }

    // 工具执行
    private enum class Kind { CONTINUE, TAKE_OVER, FINISHED }
    private class ToolOutcome(val kind: Kind, val message: String = "")

    private fun toolMsg(callId: String, result: String) =
        ChatMessage(role = Role.TOOL, text = result, toolCallId = callId)

    private suspend fun executeTools(calls: List<ToolCall>): ToolOutcome {
        lastMarker = null
        val answered = mutableSetOf<String>()
        var outcome = ToolOutcome(Kind.CONTINUE)
        try {
            for (c in calls) {
                currentCoroutineContext().ensureActive()
                val args =
                    runCatching { JsonParser.parseString(c.arguments).asJsonObject }.getOrNull()
                val result = when {
                    args == null -> "error: arguments 不是合法 JSON"
                    c.name == "finish" -> {
                        outcome =
                            ToolOutcome(Kind.FINISHED, args.get("message")?.asString ?: ""); "ok"
                    }

                    c.name == "take_over" -> {
                        outcome = ToolOutcome(Kind.TAKE_OVER); "ok"
                    }

                    else -> try {
                        val vd = if (sessionUsesVirtualDisplay) {
                            InputControlUtils.displayId.takeIf { it != -1 }
                                ?: throw IllegalStateException("虚拟屏不可用")
                        } else null
                        runTool(c.name, args, vd)
                        delay(MIN_DELAY_TIME_MILLISECOND.milliseconds)
                        "ok"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "error: ${e.message}"
                    }
                }
                ChatRepository.append(toolMsg(c.id, result))
                answered += c.id
                if (outcome.kind != Kind.CONTINUE) break
            }
        } finally {
            val missing = calls.filter { it.id !in answered }
            if (missing.isNotEmpty()) withContext(NonCancellable) {
                missing.forEach { ChatRepository.append(toolMsg(it.id, "skipped")) }
            }
        }
        return outcome
    }

    private suspend fun runTool(name: String, a: JsonObject, displayId: Int?) {
        val intent = a["intent"]
        if (intent != null) NotificationUtils.update(ctx, title = intent.asString)
        when (name) {
            "tap" -> tap(ctx, a["x"].asFloat, a["y"].asFloat, displayId)
            "swipe" -> swipe(
                ctx, a["x1"].asFloat, a["y1"].asFloat, a["x2"].asFloat, a["y2"].asFloat, displayId
            )

            "tap_type_and_enter" -> {
                tap(ctx, a["x"].asFloat, a["y"].asFloat, displayId)
                CPUFreq.waitForPageStable(packageName = getCurrentPkg(displayId))
                type(a["text"].asString, displayId)
                CPUFreq.waitForPageStable(packageName = getCurrentPkg(displayId))
                enter(displayId)
            }

            "swipe_until" -> {
                swipeUntil(
                    ctx,
                    a["x1"].asFloat,
                    a["y1"].asFloat,
                    a["x2"].asFloat,
                    a["y2"].asFloat,
                    a["text"].asString,
                    a["max_swipes"].asInt,
                    displayId
                )
            }

            "type" -> type(a["text"].asString, displayId)
            "enter" -> enter(displayId)
            "launch" -> com.xjyzs.operator.utils.execute.launch(a["app"].asString, displayId)
            "back" -> back(displayId)
            "wait" -> wait(a["seconds"].asFloat)
            "long_press" -> longPress(ctx, a["x"].asFloat, a["y"].asFloat, displayId)
            "double_tap" -> doubleTap(ctx, a["x"].asFloat, a["y"].asFloat, displayId)
            else -> throw IllegalArgumentException("unknown tool: $name")
        }
    }

    private suspend fun restoreIme() {
        if (!sessionUsesVirtualDisplay && SharedState.ime.isNotBlank()) ShellExecutor.getInstance()
            .execute("ime set ${SharedState.ime}")
    }

    private suspend fun onFinished(message: String?) {
        restoreIme()
        setState(RunningState.STOP)
        NotificationUtils.hideTime()
        NotificationUtils.update(
            ctx, ctx.getString(R.string.completed), ctx.getString(R.string.completed)
        )
        if (message != null) {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                System.currentTimeMillis().toInt(),
                NotificationCompat.Builder(ctx, "finish")
                    .setContentTitle(ctx.getString(R.string.task_completed_title))
                    .setContentText(message).setSmallIcon(R.drawable.icon).build()
            )
        }
    }

    private suspend fun onCancelled() = withContext(NonCancellable) {
        ChatRepository.settle(Status.CANCELLED)
        ChatRepository.removeTrailing { it.role == Role.USER }
        restoreIme()
        setState(RunningState.STOP)
        NotificationUtils.hideTime()
        NotificationUtils.update(
            ctx, ctx.getString(R.string.cancelled), ctx.getString(R.string.cancelled)
        )
        ctx.sendBroadcast(Intent("ACTION_SHOW_FLOATING"))
    }

    private suspend fun handleError(e: Exception) = withContext(NonCancellable) {
        ChatRepository.settle(Status.ERROR)
        setState(RunningState.STOP)
        NotificationUtils.hideTime()
        NotificationUtils.update(ctx, ctx.getString(R.string.error), ctx.getString(R.string.error))
        val intent = Intent(ctx, DialogActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("title", ctx.getString(R.string.error_title))
            putExtra(
                "text",
                if (e.message?.contains("but no scheme was found") == true) "API 配置有误, 请点击右上角「齿轮」进入设置修改"
                else e.message
            )
        }
        ctx.startActivity(intent)
    }
}

data class LayoutResp(
    val layout: String, val accessibilityReadable: Boolean
)

suspend fun buildUserMessage(
    text: String, usesVirtualDisplay: Boolean = SharedState._usesVirtualDisplay.value
): ChatMessage {
    val prevImage = ChatRepository.messages.value.lastOrNull { it.role == Role.USER }?.inlineImage
    val marker = lastMarker
    lastMarker = null
    val virtualDisplayId = if (usesVirtualDisplay) {
        InputControlUtils.displayId.takeIf { it != -1 }
            ?: throw IllegalStateException("虚拟屏不可用")
    } else null
    val currentAppPackageName = getCurrentPkg(virtualDisplayId)
    CPUFreq.waitForPageStable(packageName = currentAppPackageName)
    ensureAppsInit(SharedState.appContext)
    val currentHomePackage = getDefaultLauncherPackage(context = SharedState.appContext)
    val currentApp = getAppName(currentAppPackageName)
    if (currentAppPackageName == "com.xjyzs.operator" || currentAppPackageName == currentHomePackage) {
        val userText =
            (if (text.isNotEmpty()) "<user_task>\n$text\n</user_task>\n" else "") + "<screen_layout>\nApp:系统桌面\n请先用launch启动应用\n</screen_layout>"
        return ChatMessage(
            role = Role.USER,
            text = userText,
            inlineImage = null,
            imageMarker = null,
            accessibilityReadable = true
        )
    } else {
        val (layout, base64) = coroutineScope {
            val layoutDeferred = async(Dispatchers.IO) {
                FloatingWindowService.getLayout(virtualDisplayId, currentAppPackageName)
            }
            val screenshotDeferred = async(Dispatchers.Default) {
                screenshot(virtualDisplayId)
            }
            Pair(layoutDeferred.await(), screenshotDeferred.await())
        }

        val curImage = base64.takeIf { it.isNotEmpty() }
        val warning = ActionFeedback.analyze(prevImage, curImage, marker)

        val userText =
            (if (text.isNotEmpty()) "<user_task>\n$text\n</user_task>\n" else "") + "<screen_layout>\n" + warning + "App:${currentApp}\n" + layout.layout + "\n</screen_layout>"
        return ChatMessage(
            role = Role.USER,
            text = userText,
            inlineImage = curImage,
            imageMarker = if (curImage != null) marker else null,
            accessibilityReadable = layout.accessibilityReadable
        )
    }
}
