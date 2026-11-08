package com.xjyzs.operator.utils.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParser
import com.xjyzs.operator.utils.data.ChatMessage
import com.xjyzs.operator.utils.data.Role
import com.xjyzs.operator.utils.data.ToolCall

@Composable
fun OverlayMessageItem(msg: ChatMessage, toolResults: Map<String, String>) {
    when (msg.role) {
        Role.USER -> UserItem(msg.text)
        Role.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (msg.reasoningContent?.isNotBlank()
                    ?: false
            ) AssistantReasoningItem(msg.reasoningContent)
            if (msg.text.isNotBlank()) Text(msg.text)
            msg.toolCalls.forEach { ToolCallItem(it, toolResults[it.id]) }
        }

        Role.TOOL -> Unit
    }
}

data class ParsedMessage(
    val userTask: String, val screenLayout: String
)

/** tap(x=100, y=200) */
fun ToolCall.display(): String {
    val args = runCatching { JsonParser.parseString(arguments).asJsonObject }.getOrNull()
    val argText = args?.entrySet()?.joinToString(", ") { (k, v) ->
        "$k=" + if (v.isJsonPrimitive) v.asJsonPrimitive.asString else v.toString()
    } ?: arguments
    return "$name($argText)"
}
