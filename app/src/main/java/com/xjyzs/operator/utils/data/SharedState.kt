package com.xjyzs.operator.utils.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.google.gson.JsonObject
import com.xjyzs.operator.utils.execute.BitmapEditor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

interface OverlayController {
    suspend fun <T> withHidden(block: suspend () -> T): T
    suspend fun <T> withTouchThroughEnabled(block: suspend () -> T): T
}

object SharedState {
    var ime = ""

    // Tokens
    val _completionTokens = MutableStateFlow(0L)
    val completionTokens = _completionTokens.asStateFlow()

    val _promptTokens = MutableStateFlow(0L)
    val promptTokens = _promptTokens.asStateFlow()

    val _cachedTokens = MutableStateFlow(0L)
    val cachedTokens = _cachedTokens.asStateFlow()

    val _imageTokens = MutableStateFlow(0L)
    val imageTokens = _imageTokens.asStateFlow()

    // 用户设置
    var apiUrl by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("")
    val _usesVirtualDisplay = MutableStateFlow(true)
    val usesVirtualDisplay = _usesVirtualDisplay.asStateFlow()

    // 其他
    lateinit var appContext: Context

    val imeLst =  mutableStateListOf<String>()

    @Volatile
    var overlay: OverlayController? = null

    lateinit var apiPref: SharedPreferences

    fun init(context: Context) {
        if (::apiPref.isInitialized) return
        appContext = context.applicationContext
        apiPref = appContext.getSharedPreferences("api", Context.MODE_PRIVATE)
        updateApiUrl(apiPref.getString("apiUrl", "")!!)
        apiKey = apiPref.getString("apiKey", "")!!
        model = apiPref.getString("model", "")!!
        _promptTokens.value = apiPref.getLong("promptTokens", 0L)
        _cachedTokens.value = apiPref.getLong("cachedTokens", 0L)
        _imageTokens.value = apiPref.getLong("imageTokens", 0L)
        _completionTokens.value = apiPref.getLong("completionTokens", 0L)
        _usesVirtualDisplay.value = apiPref.getBoolean("usesVirtualDisplay", true)
    }

    fun updateApiUrl(url: String){
        apiUrl=url
        if (!apiUrl.endsWith("chat/completions")) {
            apiUrl += if (apiUrl.endsWith("/")) "chat/completions"
            else "/chat/completions"
        }
    }

    suspend fun <T> withOverlayHidden(block: suspend () -> T): T =
        overlay?.withHidden(block) ?: block()

    suspend fun <T> withTouchThroughEnabled(block: suspend () -> T): T =
        overlay?.withTouchThroughEnabled(block) ?: block()

    fun clearTokens() {
        _completionTokens.value = 0
        _promptTokens.value = 0
        _cachedTokens.value = 0
        _imageTokens.value = 0
    }

    fun isIgnoreAppLayout(packageName: String): Boolean {
        return appContext.getSharedPreferences("ignored_layout_apps", Context.MODE_PRIVATE)
            .getBoolean(packageName, false)
    }

    fun recordUsage(json: JsonObject) {
        try {
            val usage = json.getAsJsonObject("usage")?.asJsonObject
            if (usage?.isJsonNull == false) {
                val completionTokens = usage.getAsJsonPrimitive("completion_tokens")?.asInt
                val promptTokens = usage.getAsJsonPrimitive("prompt_tokens")?.asInt
                val promptTokensDetails = usage.getAsJsonObject("prompt_tokens_details")
                if (completionTokens != null) _completionTokens.value += completionTokens

                if (promptTokens != null) _promptTokens.value += promptTokens

                if (promptTokensDetails != null) {
                    val cachedTokens =
                        promptTokensDetails.getAsJsonPrimitive("cached_tokens")?.asInt
                    val imageTokens = promptTokensDetails.getAsJsonPrimitive("image_tokens")?.asInt
                    if (cachedTokens != null) _cachedTokens.value += cachedTokens
                    if (imageTokens != null) _imageTokens.value += imageTokens
                }
                apiPref.edit {
                    putLong("promptTokens", _promptTokens.value)
                    putLong("cachedTokens", _cachedTokens.value)
                    putLong("imageTokens", _imageTokens.value)
                    putLong("completionTokens", _completionTokens.value)
                }
            }
        } catch (_: Exception) {
        }
    }
}

data class ToolCall(val id: String, val name: String, val arguments: String)

fun List<ChatMessage>.toApiMessages(system: String): List<Map<String, Any?>> {
    val sendIds = imageIdsToSend()

    val out = ArrayList<Map<String, Any?>>(size + 1)
    out += mapOf("role" to "system", "content" to system)
    for (m in this) when (m.role) {
        Role.USER -> {
            val img = if (m.id in sendIds) {
                val raw = m.inlineImage
                if (raw != null && m.imageMarker != null) {
                    annotateImageWithMarker(raw, m.imageMarker)
                } else raw
            } else null
            when {
                img != null -> out += mapOf(
                    "role" to "user", "content" to listOf(
                        mapOf("type" to "text", "text" to m.text),
                        mapOf("type" to "image_url", "image_url" to mapOf("url" to img))
                    )
                )

                m.text.isNotBlank() -> out += mapOf("role" to "user", "content" to m.text)
            }
        }

        Role.ASSISTANT -> if (m.text.isNotBlank() || !m.reasoningContent.isNullOrBlank() || m.toolCalls.isNotEmpty()) out += buildMap {
            put("role", "assistant")
            put("content", m.text)
            if (!m.reasoningContent.isNullOrBlank()) put(
                "reasoning_content", m.reasoningContent
            )
            if (m.toolCalls.isNotEmpty()) put("tool_calls", m.toolCalls.map {
                mapOf(
                    "id" to it.id,
                    "type" to "function",
                    "function" to mapOf("name" to it.name, "arguments" to it.arguments)
                )
            })
        }

        Role.TOOL -> out += mapOf(
            "role" to "tool", "tool_call_id" to m.toolCallId, "content" to m.text
        )
    }
    return out
}

private fun ChatMessage.hasImage() = inlineImage != null

/** 在发给 AI 的图片上绘制点击/滑动标识，不修改内存中保存的原图 */
internal fun annotateImageWithMarker(dataUrl: String, marker: ImageMarker): String {
    val b64 = dataUrl.substringAfter("base64,")
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    val opts = BitmapFactory.Options().apply {
        inMutable = true
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return dataUrl
    var bitmap = decoded
    return try {
        if (!bitmap.isMutable) {
            bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: return dataUrl
            decoded.recycle()
        }
        val kWidth = bitmap.width / 999f
        val kHeight = bitmap.height / 999f
        val dpi = DisplayMetricsStore.densityDpi.takeIf { it > 0 } ?: 480
        val pxPerDp = ((dpi / 160f) * (dpi / 720f)).coerceAtLeast(0.5f)
        val canvas = Canvas(bitmap)
        when (marker) {
            is ImageMarker.Swipe -> BitmapEditor.drawSwipeArrow(
                canvas = canvas,
                x1 = marker.x1 * kWidth,
                y1 = marker.y1 * kHeight,
                x2 = marker.x2 * kWidth,
                y2 = marker.y2 * kHeight,
                pxPerDp = pxPerDp
            )

            is ImageMarker.Tap -> BitmapEditor.drawTapReticle(
                canvas = canvas,
                cx = marker.x * kWidth,
                cy = marker.y * kHeight,
                pxPerDp = pxPerDp
            )
        }
        val bos = ByteArrayOutputStream(bytes.size)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        dataUrl
    } finally {
        bitmap.recycle()
    }
}

/** 本次请求需要携带图片的 USER 消息 id */
fun List<ChatMessage>.imageIdsToSend(): Set<Long> {
    val users = filter { it.role == Role.USER }
    val cur = users.lastOrNull() ?: return emptySet()
    val prev = users.getOrNull(users.size - 2)
    return buildSet {
        if (cur.hasImage()) add(cur.id)
        if (prev != null && prev.accessibilityReadable == false && prev.hasImage()) add(prev.id)
    }
}
