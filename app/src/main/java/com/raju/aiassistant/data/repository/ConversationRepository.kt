package com.raju.aiassistant.data.repository

import com.raju.aiassistant.data.db.ChatDao
import com.raju.aiassistant.data.db.ConversationEntity
import com.raju.aiassistant.data.db.MessageEntity
import com.raju.aiassistant.domain.model.ChatMessage
import com.raju.aiassistant.domain.model.ChatRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationRepository(
    private val dao: ChatDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Returns the most recent conversation, creating one if none exists yet. */
    suspend fun currentConversationId(): Long =
        dao.latestConversation()?.id ?: dao.insertConversation(
            ConversationEntity(title = "New chat", createdAt = clock(), updatedAt = clock())
        )

    fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        dao.observeMessages(conversationId).map { rows -> rows.map { it.toDomain() } }

    suspend fun addMessage(conversationId: Long, role: ChatRole, text: String): Long {
        val now = clock()
        val id = dao.insertMessage(
            MessageEntity(conversationId = conversationId, role = role.name, text = text, createdAt = now)
        )
        dao.touchConversation(conversationId, now)
        return id
    }

    suspend fun clearAll() = dao.deleteAllConversations()

    private fun MessageEntity.toDomain() = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = ChatRole.entries.firstOrNull { it.name == role } ?: ChatRole.SYSTEM,
        text = text,
        createdAt = createdAt,
    )
}
