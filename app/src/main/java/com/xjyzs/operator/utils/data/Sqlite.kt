package com.xjyzs.operator.utils.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.xjyzs.operator.utils.sharedGson

enum class Role { USER, ASSISTANT, TOOL }
enum class Status { STREAMING, DONE, ERROR, CANCELLED }

@Immutable
data class ChatMessage(
    val id: Long = 0,
    val role: Role,
    val text: String = "",
    val reasoningContent: String? = null,
    val accessibilityReadable: Boolean? = null,
    val inlineImage: String? = null,
    val imagePath: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val status: Status = Status.DONE,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages", indices = [Index("sessionId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val text: String,
    val reasoningContent: String?,
    val accessibilityReadable: Boolean?,
    val imagePath: String?,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val status: String,
    val createdAt: Long
)

fun ChatMessage.toEntity(sid: Long) = MessageEntity(
    id,
    sid,
    role.name,
    text,
    reasoningContent,
    accessibilityReadable,
    imagePath,
    toolCalls.takeIf { it.isNotEmpty() }?.let { sharedGson.toJson(it) },
    toolCallId,
    status.name,
    createdAt
)

fun MessageEntity.toModel() = ChatMessage(
    id,
    Role.valueOf(role),
    text,
    reasoningContent,
    accessibilityReadable,
    null,
    imagePath,
    toolCallsJson?.let { sharedGson.fromJson(it, Array<ToolCall>::class.java).toList() }
        ?: emptyList(),
    toolCallId,
    Status.valueOf(status),
    createdAt)

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(e: MessageEntity): Long

    @Update
    suspend fun update(e: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM messages WHERE sessionId = :sid ORDER BY id DESC")
    suspend fun latest(sid: Long): List<MessageEntity>

    @Query("SELECT MAX(sessionId) FROM messages")
    suspend fun maxSessionId(): Long?

    /**
     * 修复上次进程被杀时遗留的流式消息
     */
    @Query("UPDATE messages SET status = 'CANCELLED' WHERE status = 'STREAMING'")
    suspend fun fixStreaming()

    @Query("SELECT DISTINCT sessionId FROM messages ORDER BY sessionId DESC")
    suspend fun allSessionIds(): List<Long>

    @Query("SELECT * FROM messages WHERE sessionId = :sid AND role = 'USER' ORDER BY id ASC LIMIT 1")
    suspend fun firstUserMessage(sid: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE sessionId = :sid")
    suspend fun deleteSession(sid: Long)
}

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "chat.db")
                .build().also { inst = it }
        }
    }
}