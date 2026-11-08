package com.xjyzs.operator.utils.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.xjyzs.operator.utils.data.ChatMessage
import com.xjyzs.operator.utils.data.Role

@Composable
fun MainScreenMessageItem(msg: ChatMessage, toolResults: Map<String, String>) {
    when (msg.role) {
        Role.USER -> UserItem(msg.text, true)
        Role.ASSISTANT -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (msg.reasoningContent?.isNotBlank()
                    ?: false
            ) AssistantReasoningItem(msg.reasoningContent, true)
            if (msg.text.isNotBlank()) Text(msg.text)
            msg.toolCalls.forEach { ToolCallItem(it, toolResults[it.id], true) }
        }

        Role.TOOL -> Unit
    }
}

@Composable
fun LeftDrawer(
    usePermanentDrawer: Boolean,
    drawerState: DrawerState,
    gesturesEnabled: Boolean = true,
    drawerContent: @Composable (() -> Unit),
    content: @Composable (() -> Unit)
) {
    if (usePermanentDrawer) {
        PermanentNavigationDrawer({ drawerContent() }) {
            content()
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled,
            drawerContent = { drawerContent() }) { content() }
    }
}