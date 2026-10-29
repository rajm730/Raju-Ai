package com.raju.aiassistant.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.raju.aiassistant.MainActivity
import com.raju.aiassistant.R
import com.raju.aiassistant.RajuApp
import com.raju.aiassistant.domain.business.Period
import com.raju.aiassistant.domain.business.morningBriefing
import com.raju.aiassistant.domain.business.periodRange
import com.raju.aiassistant.domain.business.summarizeExpenses
import com.raju.aiassistant.ui.LaunchRequest
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Background business reminders (WorkManager). Android decides the exact minute (battery saving), so
 * "7 AM" means around 7. Everything is computed from the phone's own records — no AI call, no data
 * leaves the phone. Tapping a notification opens Seema, who then explains it in detail.
 */
object BusinessScheduler {
    private const val MORNING = "morning_briefing"
    private const val WEEKLY = "weekly_expense_report"

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val now = LocalDateTime.now()
        wm.enqueueUniquePeriodicWork(
            MORNING, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MorningBriefingWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntil(now, nextMorning(now)), TimeUnit.MILLISECONDS)
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            WEEKLY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(delayUntil(now, nextSundayEvening(now)), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    internal fun nextMorning(now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(LocalTime.of(7, 0))
        return if (now.isBefore(today)) today else today.plusDays(1)
    }

    internal fun nextSundayEvening(now: LocalDateTime): LocalDateTime {
        val sunday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(LocalTime.of(20, 0))
        return if (now.isBefore(sunday)) sunday else sunday.plusWeeks(1)
    }

    private fun delayUntil(now: LocalDateTime, at: LocalDateTime) = Duration.between(now, at).toMillis().coerceAtLeast(0)
}

class MorningBriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RajuApp).container
        if (!c.settingsRepository.settings.first().businessReminders) return Result.success()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val repo = c.businessRepository
        runCatching { repo.pruneOldJourneys(today) }
        val (from, to) = periodRange(Period.YESTERDAY, System.currentTimeMillis(), zone)
        val text = morningBriefing(today, repo.journeysFrom(today), repo.expensesBetween(from, to)) ?: return Result.success()
        BusinessNotifier(applicationContext).show(BusinessNotifier.MORNING_ID, "☀️ Seema: aaj ki briefing", text, LaunchRequest.ACTION_BRIEFING)
        return Result.success()
    }
}

class WeeklyReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as RajuApp).container
        if (!c.settingsRepository.settings.first().businessReminders) return Result.success()
        val (from, to) = periodRange(Period.WEEK, System.currentTimeMillis(), ZoneId.systemDefault())
        val expenses = c.businessRepository.expensesBetween(from, to)
        if (expenses.isEmpty()) return Result.success()
        BusinessNotifier(applicationContext).show(
            BusinessNotifier.WEEKLY_ID, "📊 Seema: hafte ka hisaab",
            summarizeExpenses(expenses, Period.WEEK, hindi = true), LaunchRequest.ACTION_WEEKLY_REPORT,
        )
        return Result.success()
    }
}

class BusinessNotifier(private val context: Context) {
    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Briefings & khata", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Morning briefing (journeys, spending) and the weekly expense report"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // POST_NOTIFICATIONS is checked right before notify(); SecurityException is still caught.
    @SuppressLint("MissingPermission")
    fun show(id: Int, title: String, text: String, action: String) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked meanwhile: nothing to show.
        }
    }

    companion object {
        const val CHANNEL_ID = "business_briefings"
        const val MORNING_ID = 4201
        const val WEEKLY_ID = 4202
    }
}
