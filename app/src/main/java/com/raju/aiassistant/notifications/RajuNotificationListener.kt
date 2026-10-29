package com.raju.aiassistant.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.raju.aiassistant.RajuApp
import com.raju.aiassistant.domain.model.AnnounceApp
import com.raju.aiassistant.domain.notify.NotificationFields
import com.raju.aiassistant.domain.notify.parseNotification
import com.raju.aiassistant.domain.notify.voipCallText
import kotlinx.coroutines.launch

/**
 * Runs only after the user enables Notification Access in system settings. Reads only what the
 * notification itself shows (sender, preview) for apps the user allowed, and hands it to the
 * [com.raju.aiassistant.domain.notify.AnnouncementCoordinator], which asks before reading.
 * Message content is never logged or stored.
 */
class RajuNotificationListener : NotificationListenerService() {

    private val container get() = (applicationContext as? RajuApp)?.container

    override fun onListenerConnected() {
        container?.listenerConnected?.value = true
    }

    override fun onListenerDisconnected() {
        container?.listenerConnected?.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val c = container ?: return
        val settings = c.currentSettings.value
        if (!settings.announcementsActive) return
        if (sbn.packageName !in settings.announceApps) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val fields = NotificationFields(
            packageName = sbn.packageName,
            key = sbn.key,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            conversationTitle = style?.conversationTitle?.toString(),
            isGroupConversation = style?.isGroupConversation == true,
            messages = style?.messages.orEmpty().map { m -> m.person?.name?.toString() to m.text?.toString() },
            category = n.category,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        val label = AnnounceApp.entries.firstOrNull { it.packageName == sbn.packageName }?.label ?: "Message"
        val item = parseNotification(fields, label) ?: return
        val now = System.currentTimeMillis()
        if (!c.announcementDeduper.shouldAnnounce(item.packageName, item.sender.orEmpty() + item.group.orEmpty(), (item.preview ?: "") + item.isCall, now)) return

        if (item.isCall) {
            if (settings.announceAppCalls) c.appScope.launch { c.speaker.speak(voipCallText(item)) }
        } else {
            c.announcements.onMessage(item)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        container?.announcements?.onRemoved(sbn.key)
    }
}
