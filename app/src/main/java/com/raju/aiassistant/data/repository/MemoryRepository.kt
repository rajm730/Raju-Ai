package com.raju.aiassistant.data.repository

import com.raju.aiassistant.data.db.ActionLogDao
import com.raju.aiassistant.data.db.ActionLogEntity
import com.raju.aiassistant.data.db.MemoryDao
import com.raju.aiassistant.data.db.MemoryEntity
import com.raju.aiassistant.domain.assistant.ActionLog
import com.raju.aiassistant.domain.assistant.ActionStatus
import com.raju.aiassistant.domain.assistant.Memory
import com.raju.aiassistant.domain.assistant.MemoryStore
import com.raju.aiassistant.domain.assistant.looksSensitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MemoryRepository(private val dao: MemoryDao, private val clock: () -> Long = System::currentTimeMillis) : MemoryStore {
    val memories: Flow<List<Memory>> = dao.observeAll().map { rows -> rows.map { Memory(it.id, it.text, it.createdAt) } }

    override suspend fun all(): List<Memory> = dao.all().map { Memory(it.id, it.text, it.createdAt) }

    override suspend fun add(text: String): Long {
        require(!looksSensitive(text)) { "Sensitive text cannot be stored as a memory" }
        return dao.insert(MemoryEntity(text = text.trim(), createdAt = clock()))
    }

    /** Returns true only if a row was actually changed. */
    suspend fun update(id: Long, text: String): Boolean = !looksSensitive(text) && dao.update(id, text.trim()) > 0
    suspend fun delete(id: Long): Boolean = dao.delete(id) > 0
    suspend fun deleteAll() = dao.deleteAll()
}

data class ActionLogEntry(val id: Long, val summary: String, val status: ActionStatus, val createdAt: Long)

class ActionLogRepository(private val dao: ActionLogDao, private val clock: () -> Long = System::currentTimeMillis) : ActionLog {
    val recent: Flow<List<ActionLogEntry>> = dao.observeRecent(100).map { rows ->
        rows.map { ActionLogEntry(it.id, it.summary, ActionStatus.entries.firstOrNull { s -> s.name == it.status } ?: ActionStatus.FAILED, it.createdAt) }
    }

    override suspend fun log(type: String, summary: String, status: ActionStatus) {
        dao.insert(ActionLogEntity(type = type, summary = summary, status = status.name, createdAt = clock()))
        dao.trim(keep = 200)
    }

    suspend fun clear() = dao.deleteAll()
}
