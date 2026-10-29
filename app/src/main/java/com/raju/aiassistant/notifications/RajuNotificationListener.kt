package com.raju.aiassistant.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.raju.aiassistant.RajuApp
import com.raju.aiassistant.domain.assistant.announcementText
import com.raju.aiassistant.domain.model.AnnounceApp
import kotlinx.coroutines.launch

/**
 * Runs only after the user enables Notification Access in system settings. Reads the sender and
 * preview that the notification itself exposes, for apps the user allowed, and speaks them.
 * Message content is never logged or stored.
 */
class RajuNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val container = (applicationContext as? RajuApp)?.container ?: return
        val settings = container.currentSettings.value
        if (!settings.announcementsActive) return
        if (sbn.packageName !in settings.announceApps) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        if (!container.announcementDeduper.shouldAnnounce(sbn.packageName, title.orEmpty(), text.orEmpty(), System.currentTimeMillis())) return

        val label = AnnounceApp.entries.firstOrNull { it.packageName == sbn.packageName }?.label ?: "Message"
        val speech = announcementText(label, title, text)
        container.appScope.launch {
            // Don't talk over the assistant's own speech.
            if (!container.speaker.speaking.value) container.speaker.speak(speech)
        }
    }
}
