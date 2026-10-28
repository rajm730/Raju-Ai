package com.raju.aiassistant.core.security

/**
 * Masks a secret for display, keeping only the last four characters visible.
 * Short secrets are fully masked so nothing meaningful leaks.
 */
fun maskSecret(secret: String): String {
    val trimmed = secret.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.length <= 8) return "•".repeat(trimmed.length)
    return "•".repeat(8) + trimmed.takeLast(4)
}
