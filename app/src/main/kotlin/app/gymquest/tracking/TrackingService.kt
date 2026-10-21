package app.gymquest.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.gymquest.MainActivity
import app.gymquest.core.platform.GpsPolicy
import app.gymquest.core.tracking.ActivityRecorder
import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.tracking.Formats
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.Polyline
import app.gymquest.core.tracking.RecorderState
import app.gymquest.core.tracking.TrackPoint
import app.gymquest.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Records a walk, run, ride or hike with the screen off. It runs as a foreground service (with the
 * "recording" notification) so Android does not stop it, uses only the phone's GPS chip (no
 * internet), and asks for fixes less often when you are standing still or the battery is low.
 */
class TrackingService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private var recorder: ActivityRecorder? = null
    private var callback: LocationCallback? = null
    private var type = ActivityType.WALK
    private var startedAtMs = 0L
    private var intervalMs = 1_000L
    private var lastRetuneMs = 0L
    private var lastTrackCopyMs = 0L
    private var trackVersion = 0
    private var cachedTrack: List<GeoPoint> = emptyList()
    private var lastNotifyMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var journal: TrackJournal? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> recorder?.pause()
            ACTION_RESUME -> recorder?.resume()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start(intent: Intent) {
        if (recorder != null) return
        type = runCatching { ActivityType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "") }.getOrDefault(ActivityType.WALK)
        val weight = intent.getDoubleExtra(EXTRA_WEIGHT, 70.0)
        recorder = ActivityRecorder(type, weight)
        startedAtMs = System.currentTimeMillis()
        journal = TrackJournal(this).also { it.begin(type, weight, startedAtMs) }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Buscando señal GPS…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gymquest:tracking")
            ?.apply { setReferenceCounted(false); acquire(WAKE_LOCK_MAX_MS) }

        TrackingState.publish(LiveTrack(active = true, type = type, startedAtMs = startedAtMs))
        requestUpdates(intervalMs)
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(interval: Long) {
        callback?.let { fused.removeLocationUpdates(it) }
        intervalMs = interval
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .setWaitForAccurateLocation(false)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { onFix(it) }
            }
        }
        callback = cb
        try {
            fused.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopRecording()
        }
    }

    private fun onFix(loc: Location) {
        val rec = recorder ?: return
        val point = TrackPoint(
            timeMs = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            altitudeM = if (loc.hasAltitude()) loc.altitude else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 20.0,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
        )
        journal?.add(point)
        rec.add(point)
        val now = System.currentTimeMillis()
        // Copying the whole track at every fix would waste memory; every 2 s is plenty for drawing.
        if (now - lastTrackCopyMs >= TRACK_COPY_MS || cachedTrack.isEmpty()) {
            cachedTrack = rec.track.toList()
            lastTrackCopyMs = now
            trackVersion++
        }
        val summary = rec.summary()
        TrackingState.publish(
            LiveTrack(
                active = true, type = type, state = rec.state, startedAtMs = startedAtMs,
                distanceM = rec.distanceM, movingMs = rec.movingMs, speedMps = rec.currentSpeedMps,
                maxSpeedMps = rec.maxSpeedMps, gainM = rec.elevationGainM, lossM = rec.elevationLossM,
                calories = summary.calories,
                lastPoint = GeoPoint(loc.latitude, loc.longitude),
                headingDeg = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.8f) loc.bearing.toDouble() else null,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                track = cachedTrack, splits = rec.splits.toList(), trackVersion = trackVersion,
            ),
        )
        if (now - lastNotifyMs >= NOTIFY_MS) {
            lastNotifyMs = now
            notify(
                when (rec.state) {
                    RecorderState.WAITING_FOR_FIX -> "Buscando señal GPS…"
                    RecorderState.AUTO_PAUSED, RecorderState.MANUAL_PAUSED -> "En pausa · ${Formats.distance(rec.distanceM)}"
                    RecorderState.RECORDING -> "${Formats.distance(rec.distanceM)} · ${Formats.duration(rec.movingMs)}"
                },
            )
        }
        retune(now, rec.currentSpeedMps)
    }

    /** Re-asks for fixes at a calmer pace when standing still or low on battery, and faster when moving. */
    private fun retune(now: Long, speedMps: Double) {
        if (now - lastRetuneMs < RETUNE_MS) return
        lastRetuneMs = now
        val battery = getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100
        val wanted = GpsPolicy.intervalMs(speedMps, level, battery?.isCharging ?: false)
        if (wanted != intervalMs) requestUpdates(wanted)
    }

    private fun stopRecording() {
        val rec = recorder
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
        if (rec != null && rec.track.size >= 2) {
            val simplified = Polyline.simplify(rec.track, 5.0)
            TrackingState.finish(FinishedTrack(rec.summary(), startedAtMs, Polyline.encode(simplified), rec.track.toList()))
        } else {
            TrackingState.publish(LiveTrack())
        }
        recorder = null
        journal?.clear()
        journal = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        callback?.let { fused.removeLocationUpdates(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- notification -------------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Grabación de actividad", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Aviso mientras GymQuest graba tu recorrido"
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gq)
            .setContentTitle("GymQuest · ${type.label}")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .addAction(0, "Terminar", stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun notify(text: String) {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    companion object {
        const val ACTION_START = "app.gymquest.tracking.START"
        const val ACTION_PAUSE = "app.gymquest.tracking.PAUSE"
        const val ACTION_RESUME = "app.gymquest.tracking.RESUME"
        const val ACTION_STOP = "app.gymquest.tracking.STOP"
        const val EXTRA_TYPE = "type"
        const val EXTRA_WEIGHT = "weight"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        private const val TRACK_COPY_MS = 2_000L
        private const val NOTIFY_MS = 5_000L
        private const val RETUNE_MS = 10_000L
        private const val WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context, type: ActivityType, weightKg: Double) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type.name)
                .putExtra(EXTRA_WEIGHT, weightKg)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun command(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}
