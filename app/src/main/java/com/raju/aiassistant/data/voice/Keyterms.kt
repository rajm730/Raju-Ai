package com.raju.aiassistant.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Names of the user's starred (favourite) contacts, for better recognition; empty without permission. */
fun starredContactNames(context: Context, limit: Int = 30): List<String> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return runCatching {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.STARRED} = 1",
            null,
            null,
        )?.use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext() && names.size < limit) {
                // The first name is what people say ("Riyashree ko call karo"), and keyterms work best short.
                c.getString(0)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.length >= 3 }?.let(names::add)
            }
            names.distinct()
        }.orEmpty()
    }.getOrDefault(emptyList())
}
