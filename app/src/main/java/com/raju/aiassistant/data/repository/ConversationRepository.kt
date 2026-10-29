package com.raju.aiassistant.data.repository

import com.raju.aiassistant.data.db.ChatDao
import com.raju.aiassistant.data.db.ConversationEntity
import com.raju.aiassistant.data.db.MessageEntity
import com.raju.aiassistant.domain.assistant.ChatHistory
import com.raju.aiassistant.domain.model.ChatMessage
import com.raju.aiassistant.domain.model.ChatRole
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversationRepository(
    private val dao: ChatDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatHistory {
    private val lock = Mutex()
    private var conversationId: Long? = null

    private suspend fun currentConversationId(): Long = lock.withLock {
        conversationId ?: (dao.latestConversation()?.id ?: dao.insertConversation(
            ConversationEntity(title = "Chat", createdAt = clock(), updatedAt = clock())
        )).also { conversationId = it }
    }

    override suspend fun recent(limit: Int): List<ChatMessage> = dao.recentMessages(limit).map { it.toDomain() }

    override suspend fun add(role: ChatRole, text: String): Long {
        val id = currentConversationId()
        val now = clock()
        val rowId = dao.insertMessage(MessageEntity(conversationId = id, role = role.name, text = text, createdAt = now))
        dao.touchConversation(id, now)
        return rowId
    }

    override suspend fun clear() {
        lock.withLock {
            dao.deleteAllConversations()
            conversationId = null
        }
    }

    private fun MessageEntity.toDomain() = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = ChatRole.entries.firstOrNull { it.name == role } ?: ChatRole.SYSTEM,
        text = text,
        createdAt = createdAt,
    )
}
