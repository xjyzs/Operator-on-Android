package com.xjyzs.operator.utils.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.File

object ChatRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ready = CompletableDeferred<Unit>()
    private lateinit var dao: MessageDao
    private var sessionId = 1L

    private val _currentSessionId = MutableStateFlow(1L)
    val currentSessionIdState: StateFlow<Long> = _currentSessionId.asStateFlow()
    val currentSessionId: Long get() = sessionId

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** {tool_call_id: 结果} */
    val toolResults: StateFlow<Map<String, String>> = messages
        .map { list -> list.filter { it.role == Role.TOOL && it.toolCallId != null }
            .associate { it.toolCallId!! to it.text } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    suspend fun init(context: Context) {
        if (ready.isCompleted) return
        dao = AppDatabase.get(context).messageDao()
        dao.fixStreaming()
        sessionId = dao.maxSessionId() ?: 1L
        _currentSessionId.value = sessionId
        val rows = dao.latest(sessionId).asReversed().map { it.toModel() }
        // 窗口必须从 USER 开始，否则可能切断 assistant(tool_calls) 与 tool 的配对
        _messages.value = repairDangling(rows.dropWhile { it.role != Role.USER })
        ready.complete(Unit)
    }

    /** 给没有回应的 tool_call 补一条 cancelled，否则下次请求 400 */
    private suspend fun repairDangling(list: List<ChatMessage>): List<ChatMessage> {
        val i = list.indexOfLast { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
        if (i < 0) return list
        val answered = list.drop(i + 1).mapNotNull { it.toolCallId }.toSet()
        val missing = list[i].toolCalls.filter { it.id !in answered }
        if (missing.isEmpty()) return list
        val fixes = missing.map {
            val m = ChatMessage(role = Role.TOOL, text = "cancelled", toolCallId = it.id)
            m.copy(id = dao.insert(m.toEntity(sessionId)))
        }
        return list + fixes
    }

    suspend fun append(m: ChatMessage): ChatMessage {
        ready.await()
        val saved = m.copy(id = dao.insert(m.toEntity(sessionId)))
        _messages.update { it + saved }
        return saved
    }

    /**
     * 更新上下文
     *
     * [persist] 是否存入数据库
     */
    suspend fun update(id: Long, persist: Boolean = false, f: (ChatMessage) -> ChatMessage) {
        var updated: ChatMessage? = null
        _messages.update { list ->
            val i = list.indexOfLast { it.id == id }
            if (i < 0) list
            else list.toMutableList().also { updated = f(it[i]); it[i] = updated }
        }
        if (persist) updated?.let { dao.update(it.toEntity(sessionId)) }
    }

    suspend fun removeTrailing(pred: (ChatMessage) -> Boolean) {
        while (true) {
            val last = _messages.value.lastOrNull()?.takeIf(pred) ?: return
            last.imagePath?.let { File(it).delete() }
            _messages.update { l -> if (l.lastOrNull()?.id == last.id) l.dropLast(1) else l }
            dao.delete(last.id)
        }
    }

    /** 取消/出错后收尾 */
    suspend fun settle(status: Status) {
        removeTrailing { it.role == Role.ASSISTANT && it.text.isBlank() && it.toolCalls.isEmpty() }
        _messages.value.lastOrNull()?.takeIf { it.status == Status.STREAMING }?.let { m ->
            update(m.id, persist = true) { it.copy(status = status) }
        }
    }

    suspend fun newSession() {
        ready.await()
        sessionId = (dao.maxSessionId() ?: 0L) + 1
        _currentSessionId.value = sessionId
        _messages.value = emptyList()
    }

    suspend fun switchSession(sid: Long) {
        ready.await()
        sessionId = sid
        _currentSessionId.value = sid
        val rows = dao.latest(sessionId).asReversed().map { it.toModel() }
        _messages.value = repairDangling(rows.dropWhile { it.role != Role.USER })
    }

    suspend fun deleteSession(sid: Long) {
        ready.await()
        val messages = dao.latest(sid).map { it.toModel() }
        messages.forEach { m -> m.imagePath?.let { File(it).delete() } }
        dao.deleteSession(sid)
        if (sessionId == sid) {
            newSession()
        }
    }

    suspend fun getAllSessionIds(): List<Long> {
        ready.await()
        val ids = dao.allSessionIds().toMutableList()
        if (sessionId !in ids) {
            ids.add(0, sessionId)
        }
        return ids
    }

    suspend fun getSessionFirstUserMessage(sid: Long): String? {
        ready.await()
        return dao.firstUserMessage(sid)?.text
    }
}
