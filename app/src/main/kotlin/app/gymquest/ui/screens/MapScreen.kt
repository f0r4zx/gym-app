package app.gymquest.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gymquest.core.territory.ClaimValidator
import app.gymquest.core.territory.LoopDetector
import app.gymquest.core.territory.TerritoryGrid
import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.tracking.CameraState
import app.gymquest.core.tracking.FollowCamera
import app.gymquest.core.tracking.Formats
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.RecorderState
import app.gymquest.data.Actions
import app.gymquest.data.ActivityDto
import app.gymquest.data.AppState
import app.gymquest.data.Reward
import app.gymquest.data.enumOr
import app.gymquest.tracking.FinishedTrack
import app.gymquest.tracking.TrackingService
import app.gymquest.tracking.TrackingState
import app.gymquest.ui.Controller
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.GymMap
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.PrimaryButton
import app.gymquest.ui.theme.Gold
import com.google.android.gms.location.LocationServices
import java.util.UUID

private class ActivityResultInfo(val dto: ActivityDto, val type: ActivityType, val reward: Reward, val loops: Int)

@Composable
fun MapScreen(state: AppState, c: Controller) {
    var page by rememberSaveable { mutableStateOf("map") }
    if (page == "offline") {
        BackHandler { page = "map" }
        OfflineMapsScreen(state, c) { page = "map" }
    } else {
        LiveMap(state, c, openOffline = { page = "offline" })
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun LiveMap(state: AppState, c: Controller, openOffline: () -> Unit) {
    val context = LocalContext.current
    val live by TrackingState.live.collectAsStateWithLifecycle()
    val finished by TrackingState.finished.collectAsStateWithLifecycle()
    var type by rememberSaveable { mutableStateOf(ActivityType.WALK.name) }
    var lastKnown by remember { mutableStateOf<GeoPoint?>(null) }
    var result by remember { mutableStateOf<ActivityResultInfo?>(null) }
    val follow = remember { FollowCamera() }
    val activityType = enumOr(type, ActivityType.WALK)

    fun hasLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun refreshLastKnown() {
        if (!hasLocation()) return
        LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener { loc ->
            if (loc != null) lastKnown = GeoPoint(loc.latitude, loc.longitude)
        }
    }

    val startRecording = {
        TrackingService.start(context, activityType, state.profile?.weightKg ?: 70.0)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true || granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            refreshLastKnown()
            startRecording()
        } else {
            c.say("Necesito el permiso de ubicación para grabar tu recorrido.")
        }
    }

    LaunchedEffect(Unit) { refreshLastKnown() }

    // Save a finished recording exactly once.
    LaunchedEffect(finished) {
        val f = finished ?: return@LaunchedEffect
        TrackingState.consumeFinished()
        result = save(f, c)
    }

    val tickMs by produceState(System.currentTimeMillis(), live.active) {
        while (live.active) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    val position = live.lastPoint ?: lastKnown
    // Only move the camera when a new position arrives, not on every one-second clock tick.
    val camera: CameraState? = remember(live.active, live.lastPoint, lastKnown) {
        val p = live.lastPoint
        when {
            live.active && p != null -> follow.update(p, live.speedMps, live.headingDeg)
            lastKnown != null -> CameraState(lastKnown!!, 0.0, 45.0, 15.5)
            else -> null
        }
    }

    Box(Modifier.fillMaxSize()) {
        GymMap(
            styleUrl = state.settings.mapStyle,
            track = live.track,
            trackVersion = live.trackVersion,
            position = position,
            camera = camera,
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = result
            when {
                r != null -> ResultCard(r) { result = null }
                live.active -> ActivePanel(live, tickMs)
                else -> IdlePanel(
                    state = state,
                    selected = activityType,
                    onSelect = { type = it.name },
                    onStart = {
                        if (hasLocation()) startRecording()
                        else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
                    },
                    onOffline = openOffline,
                )
            }
        }
    }
}

/** Turns a finished recording into XP, stats and (for closed loops) conquered zones. */
private fun save(f: FinishedTrack, c: Controller): ActivityResultInfo? {
    val s = f.summary
    if (s.distanceM < 50) {
        c.say("Recorrido muy corto: no se guardó.")
        return null
    }
    var cells = 0
    var loops = 0
    val verdict = ClaimValidator.check(s.type, s.avgSpeedMps, s.movingMs, claimsToday = 0)
    if (verdict == ClaimValidator.Verdict.OK) {
        val found = LoopDetector.find(f.track)
        loops = found.size
        cells = found.flatMap { TerritoryGrid.cellsInside(it.polygon) }.distinct().size
    }
    val dto = ActivityDto(
        id = UUID.randomUUID().toString(), timeMs = f.startedAtMs, type = s.type.name, distanceM = s.distanceM,
        movingMs = s.movingMs, elapsedMs = s.elapsedMs, gainM = s.elevationGainM, lossM = s.elevationLossM,
        calories = s.calories, polyline = f.polyline, cellsCaptured = cells,
    )
    val reward = c.act { Actions.finishActivity(it, dto, s.type, c.today) }
    return ActivityResultInfo(dto, s.type, reward, loops)
}

@Composable
private fun IdlePanel(state: AppState, selected: ActivityType, onSelect: (ActivityType) -> Unit, onStart: () -> Unit, onOffline: () -> Unit) {
    GqCard {
        Text("¿Qué vas a hacer?", fontWeight = FontWeight.Bold)
        ChoiceChips(ActivityType.entries, selected, { it.label }, onSelect)
        PrimaryButton("▶ Empezar", onStart, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onOffline) { Text("📥 Mapas sin internet") }
            val km = state.activities.sumOf { it.distanceM } / 1000.0
            Muted("Total: ${"%.1f".format(km)} km")
        }
        Muted("El GPS funciona sin internet. Para ver el mapa sin conexión, descarga tu zona antes.")
    }
}

@Composable
private fun ActivePanel(live: app.gymquest.tracking.LiveTrack, nowMs: Long) {
    val context = LocalContext.current
    val elapsed = (nowMs - live.startedAtMs).coerceAtLeast(0)
    val paused = live.state == RecorderState.MANUAL_PAUSED || live.state == RecorderState.AUTO_PAUSED
    GqCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Distancia", Formats.distance(live.distanceM))
            Stat("Tiempo", Formats.duration(elapsed))
            Stat(
                if (live.type.usesPace) "Ritmo" else "Velocidad",
                if (live.type.usesPace) Formats.pace(live.speedMps) else "${Formats.speedKmh(live.speedMps)} km/h",
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Muted("⛰ +${live.gainM.toInt()} m")
            Muted("🔥 ${live.calories} kcal")
            Muted(
                when (live.state) {
                    RecorderState.WAITING_FOR_FIX -> "Buscando GPS…"
                    RecorderState.AUTO_PAUSED -> "Pausa automática"
                    RecorderState.MANUAL_PAUSED -> "En pausa"
                    RecorderState.RECORDING -> "Grabando"
                },
            )
        }
        live.accuracyM?.let { if (it > 25) Muted("Señal débil (±${it.toInt()} m): sal a un lugar abierto.") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { TrackingService.command(context, if (live.state == RecorderState.MANUAL_PAUSED) TrackingService.ACTION_RESUME else TrackingService.ACTION_PAUSE) },
                modifier = Modifier.weight(1f),
            ) { Text(if (live.state == RecorderState.MANUAL_PAUSED) "▶ Reanudar" else if (paused) "⏸ Pausado" else "⏸ Pausar") }
            PrimaryButton("⏹ Terminar", { TrackingService.command(context, TrackingService.ACTION_STOP) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Muted(label)
    }
}

@Composable
private fun ResultCard(r: ActivityResultInfo, onClose: () -> Unit) {
    val dto = r.dto
    GqCard {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("¡${r.type.label} terminada! 🎉", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text("${Formats.distance(dto.distanceM)} en ${Formats.duration(dto.movingMs)}", fontWeight = FontWeight.Bold)
            val avg = if (dto.movingMs > 0) dto.distanceM / (dto.movingMs / 1000.0) else 0.0
            Muted(
                (if (r.type.usesPace) "Ritmo medio ${Formats.pace(avg)}" else "Velocidad media ${Formats.speedKmh(avg)} km/h") +
                    " · +${dto.gainM.toInt()} m · ${dto.calories} kcal",
            )
            if (r.loops > 0) Text("🗺 Cerraste ${r.loops} circuito(s): ${dto.cellsCaptured} zonas conquistadas", color = Gold, fontWeight = FontWeight.Bold)
            if (r.reward.xp > 0) Text("+${r.reward.xp} XP", fontWeight = FontWeight.SemiBold)
            r.reward.newLevel?.let { Text("🎉 ¡Nivel $it!", fontWeight = FontWeight.Bold) }
            PrimaryButton("Listo", onClose, Modifier.fillMaxWidth())
        }
    }
}
