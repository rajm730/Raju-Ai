package com.raju.aiassistant.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.raju.aiassistant.data.repository.ActionLogEntry
import com.raju.aiassistant.di.AppContainer
import com.raju.aiassistant.domain.assistant.ActionStatus
import com.raju.aiassistant.domain.assistant.Memory
import com.raju.aiassistant.domain.assistant.looksSensitive
import com.raju.aiassistant.domain.tools.ToolOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ToolsViewModel(private val c: AppContainer) : ViewModel() {
    val memories: StateFlow<List<Memory>> = c.memoryRepository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history: StateFlow<List<ActionLogEntry>> = c.actionLogRepository.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    /** Real result of the last quick action / memory operation. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun battery() = runTool("Battery status") { c.deviceTools.batteryStatus() }
    fun openApp(name: String) = runTool("Open app: $name") { c.deviceTools.openApp(name) }
    fun playMusic(query: String) = runTool("Play music: $query") { c.deviceTools.playMusic(query, c.currentSettings.value.musicService) }

    /** Web research goes through the assistant so results are summarised with sources in Chat. */
    fun askWeb(query: String) = c.engine.submit("Search the web and answer with sources: $query")

    private fun runTool(label: String, block: () -> ToolOutcome) {
        if (c.currentSettings.value.safetyMode && !label.startsWith("Battery")) {
            _message.value = "Safety Mode is on — phone actions are paused."
            return
        }
        val outcome = block()
        val (status, text) = when (outcome) {
            is ToolOutcome.Done -> ActionStatus.SUCCESS to outcome.message
            is ToolOutcome.Failed -> ActionStatus.FAILED to outcome.message
            is ToolOutcome.NeedsPermission -> ActionStatus.FAILED to outcome.reason
        }
        _message.value = text
        viewModelScope.launch { runCatching { c.actionLogRepository.log("Quick", "$label — $text", status) } }
    }

    fun addMemory(text: String) = viewModelScope.launch {
        _message.value = when {
            text.isBlank() -> "Memory is empty."
            looksSensitive(text) -> "Not saved: looks like a password, PIN, OTP, card number or key."
            else -> runCatching { c.memoryRepository.add(text) }.fold({ "Memory saved." }, { "Could not save memory." })
        }
    }

    fun updateMemory(id: Long, text: String) = viewModelScope.launch {
        _message.value = if (c.memoryRepository.update(id, text)) "Memory updated." else "Not updated (empty, sensitive or missing)."
    }

    fun deleteMemory(id: Long) = viewModelScope.launch {
        _message.value = if (c.memoryRepository.delete(id)) "Memory deleted." else "Memory was not found."
    }

    fun clearHistory() = viewModelScope.launch {
        c.actionLogRepository.clear()
        _message.value = "Action history cleared."
    }
}
