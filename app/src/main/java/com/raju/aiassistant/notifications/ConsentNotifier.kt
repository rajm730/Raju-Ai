package com.raju.aiassistant.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.raju.aiassistant.MainActivity
import com.raju.aiassistant.R
import com.raju.aiassistant.RajuApp
import com.raju.aiassistant.domain.notify.PendingAnnouncement
import com.raju.aiassistant.domain.notify.whoText
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows the "Read / Skip" question as an app notification so the user can answer while the app is
 * in the background. The notification never contains the message text itself.
 */
class ConsentNotifier(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Message announcements", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Asks before Seema AI reads a message aloud"
            setSound(null, null) // the assistant speaks; no extra ringtone
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** App-owned notifications need POST_NOTIFICATIONS on Android 13+ and must not be blocked in App info. */
    fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // POST_NOTIFICATIONS is checked in canPost() just before notify(); SecurityException is still caught.
    @SuppressLint("MissingPermission")
    fun show(p: PendingAnnouncement?) {
        val nm = NotificationManagerCompat.from(context)
        if (p == null) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        if (!canPost()) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${p.item.appLabel}: ${whoText(p.item)}")
            .setContentText("Read aloud? (message not shown here)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .setContentIntent(open)
            .addAction(0, "Read", action(ConsentActionReceiver.ACTION_READ, p.id))
            .addAction(0, "Skip", action(ConsentActionReceiver.ACTION_SKIP, p.id))
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission was revoked meanwhile; the in-app prompt still works.
        }
    }

    private fun action(action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, (action.hashCode() * 31 + id).toInt(),
        Intent(context, ConsentActionReceiver::class.java).setAction(action).putExtra(ConsentActionReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "message_consent"
        const val NOTIFICATION_ID = 4101
    }
}

/** Handles the Read / Skip buttons. Not exported: only this app's PendingIntents can reach it. */
class ConsentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as? RajuApp)?.container ?: return
        val id = intent.getLongExtra(EXTRA_ID, -1)
        NotificationManagerCompat.from(context).cancel(ConsentNotifier.NOTIFICATION_ID)
        when (intent.action) {
            // goAsync keeps this receiver (and the process) alive while the message is spoken. Android allows
            // only ~10 s for a receiver, so it is released after 9 s at the latest; speech itself continues.
            ACTION_READ -> {
                val pending = goAsync()
                val released = AtomicBoolean(false)
                val release: () -> Unit = { if (released.compareAndSet(false, true)) runCatching { pending.finish() } }
                Handler(Looper.getMainLooper()).postDelayed({ release() }, 9_000L)
                c.announcements.read(id, release)
            }
            ACTION_SKIP -> c.announcements.skip(id)
        }
    }

    companion object {
        const val ACTION_READ = "com.raju.aiassistant.action.READ_MESSAGE"
        const val ACTION_SKIP = "com.raju.aiassistant.action.SKIP_MESSAGE"
        const val EXTRA_ID = "announcement_id"
    }
}
