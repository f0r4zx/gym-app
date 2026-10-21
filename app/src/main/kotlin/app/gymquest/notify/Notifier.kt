package app.gymquest.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.gymquest.GymQuestApp
import app.gymquest.MainActivity
import app.gymquest.R
import app.gymquest.core.fitness.Hydration
import app.gymquest.core.nutrition.MealType
import app.gymquest.core.notifications.DayState
import app.gymquest.core.notifications.NotificationPlanner
import app.gymquest.core.notifications.NotificationPrefs
import app.gymquest.core.notifications.NotificationType
import app.gymquest.core.wellness.DayPart
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Local reminders (water, skincare, workout, streak at risk...). They are planned on the phone and
 * delivered by WorkManager, so they arrive with no internet. Each time the app is left, the day is
 * planned again from what you already did, so you are never reminded of something you finished.
 */
object Notifier {
    private const val CHANNEL = "reminders"
    private const val TAG = "gq-reminder"
    private const val REPLAN_NAME = "gq-replan"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Recordatorios", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Agua, skincare, entrenamientos y tu racha"
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun prefsFrom(state: AppState): NotificationPrefs {
        val chosen = state.settings.notificationTypes.mapNotNull { runCatching { NotificationType.valueOf(it) }.getOrNull() }.toSet()
        return NotificationPrefs(
            enabled = if (state.settings.notificationTypes.isEmpty()) NotificationType.entries.filter { it.defaultOn }.toSet() else chosen,
            wakeHour = state.profile?.wakeHour ?: 7,
            sleepHour = (state.profile?.sleepHour ?: 23) % 24,
        )
    }

    fun dayState(state: AppState, today: LocalDate): DayState? {
        val profile = Derived.profile(state) ?: return null
        val day = state.day(today.toEpochDay())
        val routine = Derived.skincareRoutine(state)
        fun done(part: DayPart): Boolean {
            val needed = routine.filter { it.part == part && !it.optional && !it.weekly }
            return needed.isNotEmpty() && needed.all { it.id in day.skincareSteps }
        }
        val lastWeigh = state.weights.maxOfOrNull { it.epochDay } ?: Long.MIN_VALUE / 2
        return DayState(
            date = today,
            hasActivityToday = Derived.streak(state, today).activeToday,
            streakDays = Derived.streak(state, today).current,
            waterMl = day.waterMl,
            waterTargetMl = Hydration.targetMl(profile.weightKg, day.activeMinutes),
            skincareMorningDone = done(DayPart.MORNING),
            skincareEveningDone = done(DayPart.EVENING),
            mealsLogged = day.foods.map { it.meal() }.toSet<MealType>(),
            weighInDue = today.toEpochDay() - lastWeigh >= 7,
        )
    }

    /** Throws away the old reminders and schedules the ones still ahead today. */
    fun replan(context: Context, state: AppState, today: LocalDate = LocalDate.now(), now: LocalTime = LocalTime.now()) {
        val work = WorkManager.getInstance(context)
        work.cancelAllWorkByTag(TAG)
        if (state.profile == null) return
        val ds = dayState(state, today) ?: return
        val zone = ZoneId.systemDefault()
        for (n in NotificationPlanner.planDay(prefsFrom(state), ds, now)) {
            val at = n.date.atTime(n.time).atZone(zone).toInstant().toEpochMilli()
            val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .setInputData(workDataOf("id" to n.id, "title" to n.title, "body" to n.body, "link" to n.deepLink))
                .build()
            work.enqueueUniqueWork(n.id, ExistingWorkPolicy.REPLACE, request)
        }
    }

    /** Re-plans every few hours even if the app is not opened, so tomorrow's reminders still arrive. */
    fun schedulePeriodicReplan(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReplanWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(REPLAN_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, id: String, title: String, body: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, id.hashCode(), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gq)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val data: Data = inputData
        val title = data.getString("title") ?: return Result.success()
        Notifier.show(applicationContext, data.getString("id") ?: title, title, data.getString("body") ?: "")
        return Result.success()
    }
}

class ReplanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as GymQuestApp).repo
        Notifier.replan(applicationContext, repo.state.value)
        return Result.success()
    }
}
