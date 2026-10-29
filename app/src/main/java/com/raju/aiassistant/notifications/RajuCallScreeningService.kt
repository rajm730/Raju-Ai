package com.raju.aiassistant.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.raju.aiassistant.RajuApp
import com.raju.aiassistant.domain.notify.callAnnouncementText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Announces incoming SIM calls. Android only binds this service after the user grants Raju AI the
 * "call screening" role (Settings → Calls). Every call is allowed through unchanged — the app never
 * blocks, rejects, answers, records or silences calls. The caller's name is looked up in Contacts
 * only if Contacts permission was granted; nothing about the call is stored.
 */
@RequiresApi(29)
class RajuCallScreeningService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        // Respond immediately so the call is never delayed: allow, don't silence, don't log.
        respondToCall(details, CallResponse.Builder().build())

        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val c = (applicationContext as? RajuApp)?.container ?: return
        val s = c.currentSettings.value
        if (!s.announceCalls || s.assistantPaused || s.safetyMode) return
        val number = details.handle?.schemeSpecificPart
        if (!c.callDeduper.shouldAnnounce(number, System.currentTimeMillis())) return

        c.appScope.launch {
            val name = number?.let { lookupContactName(it) }
            c.speaker.speak(callAnnouncementText(name, number, s.speakCallerNumber))
        }
    }

    private suspend fun lookupContactName(number: String): String? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(this@RajuCallScreeningService, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext null
        }
        runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull()
    }
}
