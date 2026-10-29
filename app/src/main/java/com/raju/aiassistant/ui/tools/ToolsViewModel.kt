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
import kotlinx.coroutines.flow.map
import com.raju.aiassistant.domain.business.Expense
import com.raju.aiassistant.domain.business.Journey
import com.raju.aiassistant.domain.business.Period
import com.raju.aiassistant.domain.business.formatRupees
import com.raju.aiassistant.domain.business.normalizeCategory
import com.raju.aiassistant.domain.business.periodRange
import com.raju.aiassistant.domain.business.rupeesToPaise
import java.time.LocalDate

class ToolsViewModel(private val c: AppContainer) : ViewModel() {
    val memories: StateFlow<List<Memory>> = c.memoryRepository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history: StateFlow<List<ActionLogEntry>> = c.actionLogRepository.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val expenses: StateFlow<List<Expense>> = c.businessRepository.recentExpenses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val journeys: StateFlow<List<Journey>> = c.businessRepository.upcomingJourneys(LocalDate.now())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Today's and this week's totals, computed from the same records Seema uses. */
    val totals: StateFlow<Pair<Long, Long>> = expenses.map {
        val now = System.currentTimeMillis()
        val (dayFrom, dayTo) = periodRange(Period.TODAY, now)
        val (weekFrom, weekTo) = periodRange(Period.WEEK, now)
        c.businessRepository.expensesBetween(dayFrom, dayTo).sumOf { e -> e.amountPaise } to
            c.businessRepository.expensesBetween(weekFrom, weekTo).sumOf { e -> e.amountPaise }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L to 0L)

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

    fun addExpense(amount: String, note: String) = viewModelScope.launch {
        val paise = rupeesToPaise(amount)
        _message.value = when {
            paise == null -> "Enter an amount, e.g. 250."
            else -> runCatching { c.businessRepository.addExpense(paise, note.ifBlank { "kharcha" }, normalizeCategory(note)) }
                .fold({ "Saved ${formatRupees(paise)} ${note.trim()}." }, { "Could not save the expense." })
        }
    }

    fun deleteExpense(id: Long) = viewModelScope.launch {
        _message.value = if (c.businessRepository.deleteExpense(id)) "Expense deleted." else "Expense was not found."
    }

    fun removeJourney(pnr: String) = viewModelScope.launch {
        _message.value = if (c.businessRepository.removeJourney(pnr)) "PNR $pnr removed." else "PNR was not found."
    }

    /** No free official PNR API exists: open the official page with the PNR on the clipboard. */
    fun checkPnr(pnr: String) {
        c.deviceTools.copyToClipboard(pnr)
        val outcome = c.deviceTools.openWebPage("https://www.indianrail.gov.in/enquiry/PNR/PnrEnquiry.html?locale=en")
        _message.value = if (outcome is ToolOutcome.Done) "PNR $pnr copied — paste it on the official page." else (outcome as? ToolOutcome.Failed)?.message
    }

    fun clearHistory() = viewModelScope.launch {
        c.actionLogRepository.clear()
        _message.value = "Action history cleared."
    }
}
