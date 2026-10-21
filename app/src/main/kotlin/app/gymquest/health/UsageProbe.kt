package app.gymquest.health

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import app.gymquest.core.wellness.DailyUsage
import app.gymquest.core.wellness.ScreenEvent
import java.time.ZoneId

/**
 * Reads when the screen turned on and off (Android's "usage access", which the user grants in
 * Settings). It never looks at which apps were used. It is used to guess sleep without a smartwatch
 * and to report screen time.
 */
object UsageProbe {
    @Suppress("DEPRECATION")
    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun screenEvents(context: Context, fromMs: Long, toMs: Long): List<ScreenEvent> {
        if (Build.VERSION.SDK_INT < 28 || !hasAccess(context)) return emptyList()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = runCatching { manager.queryEvents(fromMs, toMs) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ScreenEvent>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> out += ScreenEvent(e.timeStamp, true)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> out += ScreenEvent(e.timeStamp, false)
            }
        }
        return out
    }

    /** Screen time so far today, pick-ups, and how much of it was in the small hours. */
    fun dailyUsage(context: Context, nowMs: Long, zone: ZoneId): DailyUsage? {
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val lateStart = dayStart - 3_600_000L // 23:00 yesterday
        val lateEnd = dayStart + 5 * 3_600_000L // 05:00 today
        val events = screenEvents(context, dayStart - 6 * 3_600_000L, nowMs)
        if (events.isEmpty()) return null

        var onSince: Long? = null
        var total = 0L
        var pickups = 0
        var late = 0L
        var longest = 0L
        for (e in events.sortedBy { it.timeMs }) {
            if (e.on) {
                if (onSince == null) onSince = e.timeMs
            } else if (onSince != null) {
                val start = onSince
                onSince = null
                late += (minOf(e.timeMs, lateEnd) - maxOf(start, lateStart)).coerceAtLeast(0)
                val s = maxOf(start, dayStart)
                if (e.timeMs > s) {
                    total += e.timeMs - s
                    longest = maxOf(longest, e.timeMs - s)
                    if (start >= dayStart) pickups++
                }
            }
        }
        onSince?.let { start ->
            val s = maxOf(start, dayStart)
            total += (nowMs - s).coerceAtLeast(0)
        }
        return DailyUsage(total, pickups, late, longest)
    }
}
