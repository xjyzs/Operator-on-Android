package com.xjyzs.operator.utils

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonElement
import com.xjyzs.operator.RunningState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

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

    var runningState by mutableStateOf(RunningState.STOP)
    var apiUrl by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("")

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

data class Msg(
    val role: String,
    var content: MutableState<JsonElement>,
    val id: String = UUID.randomUUID().toString()
)