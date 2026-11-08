package com.xjyzs.operator.utils.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xjyzs.operator.R
import com.xjyzs.operator.utils.AgentController
import com.xjyzs.operator.utils.InputControlUtils
import com.xjyzs.operator.utils.RunningState
import com.xjyzs.operator.utils.data.DisplayMetricsStore
import com.xjyzs.operator.utils.data.SharedState
import com.xjyzs.operator.utils.data.SharedState.ime
import com.xjyzs.operator.utils.data.ToolCall
import com.xjyzs.operator.utils.execute.ShellExecutor
import com.xjyzs.operator.utils.updateNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

suspend fun mainButtonAction(
    text: String,
    suInstance: ShellExecutor,
    context: Context,
    clearTextField: () -> Unit,
    clearInputFocusAndAwait: suspend () -> Unit
) {
    when (AgentController.runningState.value) {
        RunningState.STOP, RunningState.TAKE_OVER -> {
            if (text.isBlank() && AgentController.runningState.value == RunningState.STOP) return
            clearInputFocusAndAwait()
            if (AgentController.runningState.value == RunningState.STOP) {
                withContext(Dispatchers.IO) {
                    ime =
                        suInstance.execute("settings get secure default_input_method").stdout.trim()
                }
                if (SharedState._usesVirtualDisplay.value && InputControlUtils.displayId == -1) {
                    InputControlUtils.createVirtualDisplay(
                        DisplayMetricsStore.width,
                        DisplayMetricsStore.height,
                        DisplayMetricsStore.densityDpi
                    )
                    delay(500.milliseconds)
                }
            }
            clearTextField()
            AgentController.send(text)
            if (!SharedState._usesVirtualDisplay.value) {
                withContext(Dispatchers.IO) { suInstance.execute("ime set com.android.adbkeyboard/.AdbIME") }
            }
            updateNotification(context, context.getString(R.string.executing))
        }

        else -> AgentController.stop()
    }
}

fun parseContent(rawText: String): ParsedMessage {
    val userTaskRegex = Regex("<user_task>(.*?)</user_task>", RegexOption.DOT_MATCHES_ALL)
    val screenLayoutRegex =
        Regex("<screen_layout>(.*?)</screen_layout>", RegexOption.DOT_MATCHES_ALL)

    val userTask = userTaskRegex.find(rawText)?.groupValues?.get(1)?.trim().orEmpty()
    val screenLayout = screenLayoutRegex.find(rawText)?.groupValues?.get(1)?.trim().orEmpty()

    return ParsedMessage(userTask, screenLayout)
}

@Composable
fun UserItem(text: String, isMainScreen: Boolean = false) {
    val parsed = remember(text) { parseContent(text) }
    if (parsed.userTask.isNotEmpty()) Row {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 0.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Text(
                parsed.userTask,
                style = if (isMainScreen) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
            )
        }
    }
    var expanded by remember { mutableStateOf(false) }
    if (parsed.screenLayout.isNotEmpty()) {
        val style =
            if (isMainScreen) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = !expanded }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    null,
                    Modifier.size(if (isMainScreen) 16.dp else 12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "屏幕布局 长度${parsed.screenLayout.length}",
                    style = style,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) Text(
                parsed.screenLayout,
                style = style,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

fun String.findLastNonBlankLine(): String? {
    val lastCharIndex = indexOfLast { !it.isWhitespace() }
    if (lastCharIndex == -1) return null
    val lineEnd = indexOfAny(
        charArrayOf('\n', '\r'), startIndex = lastCharIndex
    ).let { if (it == -1) length else it }
    val lineStart = lastIndexOfAny(
        charArrayOf('\n', '\r'), startIndex = lastCharIndex
    ).let { if (it == -1) 0 else it + 1 }
    return substring(lineStart, lineEnd)
}

@Composable
fun AssistantReasoningItem(text: String, isMainScreen: Boolean = false) {
    val style =
        if (isMainScreen) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { expanded = !expanded }) {
        if (!expanded) {
            val displayTxt = remember(text) { text.findLastNonBlankLine() }
            if (displayTxt != null) Text(
                displayTxt,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else Text(text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolCallItem(call: ToolCall, result: String?, isMainScreen: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val isError = result?.startsWith("error") == true
    val fontColor =
        if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val style =
        if (isMainScreen) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                result == null -> LoadingIndicator(Modifier.size(12.dp))
                isError -> Icon(
                    Icons.Default.Error,
                    null,
                    Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                result == "skipped" -> Text(
                    "–", color = MaterialTheme.colorScheme.onSurfaceVariant, style = style
                )

                else -> Icon(
                    ImageVector.vectorResource(R.drawable.ic_tool_call),
                    null,
                    if (isMainScreen) Modifier.size(16.dp) else Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                call.display(),
                style = style,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                color = fontColor
            )
        }
        if (expanded && result != null) {
            Text(result, style = style, color = fontColor)
        }
    }
}