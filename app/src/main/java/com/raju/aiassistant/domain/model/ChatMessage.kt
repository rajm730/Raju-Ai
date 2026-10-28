package com.raju.aiassistant.domain.model

enum class ChatRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: Long = 0,
    val conversationId: Long,
    val role: ChatRole,
    val text: String,
    val createdAt: Long,
)
