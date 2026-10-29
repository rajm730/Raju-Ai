package com.raju.aiassistant.domain.tools

import com.raju.aiassistant.domain.assistant.SettingsPage
import com.raju.aiassistant.domain.model.MusicService

data class Contact(val name: String, val number: String)

/** Real outcome of a device action. */
sealed interface ToolOutcome {
    data class Done(val message: String) : ToolOutcome
    data class Failed(val message: String) : ToolOutcome
    data class NeedsPermission(val permission: String, val reason: String) : ToolOutcome
}

sealed interface ContactLookup {
    data class Found(val contacts: List<Contact>) : ContactLookup
    data class NeedsPermission(val permission: String, val reason: String) : ContactLookup
    data class Failed(val message: String) : ContactLookup
}

/** Phone capabilities the assistant may use. Implemented with public Android APIs/intents only. */
interface DeviceTools {
    suspend fun findContacts(query: String): ContactLookup
    fun dial(contact: Contact, direct: Boolean): ToolOutcome
    fun openApp(name: String): ToolOutcome
    fun openSettings(page: SettingsPage): ToolOutcome
    fun batteryStatus(): ToolOutcome
    fun playMusic(query: String, service: MusicService): ToolOutcome
    fun openWhatsApp(number: String?, text: String): ToolOutcome
}

/** Digits-only number for wa.me links; 10-digit Indian mobile numbers get the 91 prefix. */
fun normalizeForWhatsApp(number: String): String? {
    val digits = number.filter { it.isDigit() }
    return when {
        digits.length == 10 -> "91$digits"
        digits.length == 11 && digits.startsWith("0") -> "91" + digits.drop(1)
        digits.length in 11..15 -> digits
        else -> null
    }
}

/** Deduplicates contacts by number (last 10 digits). */
fun distinctContacts(contacts: List<Contact>): List<Contact> =
    contacts.distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
