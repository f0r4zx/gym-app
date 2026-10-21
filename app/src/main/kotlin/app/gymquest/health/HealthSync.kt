package app.gymquest.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.gymquest.core.wellness.SleepSession
import app.gymquest.core.wellness.SleepSource
import java.time.Duration
import java.time.Instant

/**
 * Reads sleep and steps from Health Connect, the Android hub that smartwatches and other fitness
 * apps (Google Fit, Samsung Health, Fitbit...) write into. Everything is read on the phone.
 */
object HealthSync {
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    fun available(context: Context): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(context: Context): Boolean {
        if (!available(context)) return false
        return runCatching { HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(permissions) }
            .getOrDefault(false)
    }

    /** The most recent night of sleep (at least 3 hours) that ended in the last 36 hours. */
    suspend fun lastNight(context: Context, now: Instant = Instant.now()): SleepSession? = runCatching {
        val client = HealthConnectClient.getOrCreate(context)
        val records = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minus(Duration.ofHours(36)), now)),
        ).records
        val best = records
            .filter { Duration.between(it.startTime, it.endTime).toHours() >= 3 }
            .maxByOrNull { it.endTime } ?: return@runCatching null
        val awakeMs = best.stages
            .filter {
                it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
                    it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED ||
                    it.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
            }
            .sumOf { Duration.between(it.startTime, it.endTime).toMillis() }
        SleepSession(best.startTime.toEpochMilli(), best.endTime.toEpochMilli(), awakeMs, SleepSource.WEARABLE, 0.95)
    }.getOrNull()

    suspend fun stepsSince(context: Context, start: Instant, now: Instant = Instant.now()): Long? = runCatching {
        val client = HealthConnectClient.getOrCreate(context)
        client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, now)))[StepsRecord.COUNT_TOTAL]
    }.getOrNull()
}
