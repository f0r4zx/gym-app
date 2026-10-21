package app.gymquest.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import app.gymquest.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.routes.ReportType
import app.gymquest.core.routes.RouteAhead
import app.gymquest.core.routes.RouteReport
import app.gymquest.core.routes.TravelMode
import app.gymquest.core.tracking.Formats
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.MapDetail
import app.gymquest.core.tracking.OfflineMapPlanner
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.enumOr
import app.gymquest.map.OfflineDownloader
import app.gymquest.routing.Place
import app.gymquest.routing.PlannedRoute
import app.gymquest.routing.RoutePlanning
import app.gymquest.ui.Controller
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.PrimaryButton
import app.gymquest.ui.theme.Gold
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TRAVEL = listOf(TravelMode.WALK, TravelMode.RUN, TravelMode.BIKE, TravelMode.CAR)
private val REPORTABLE = listOf(
    ReportType.TRAFFIC, ReportType.ACCIDENT, ReportType.ROADWORK, ReportType.HAZARD, ReportType.POTHOLE,
    ReportType.CLOSED_ROAD, ReportType.BIKE_LANE_BLOCKED, ReportType.DANGER_ZONE, ReportType.WATER_FOUNTAIN, ReportType.SCENIC,
)

/** Plan a trip to a destination (type a place or long-press the map) and mark hazards along the way. */
@Composable
fun TripPanel(
    state: AppState,
    c: Controller,
    position: GeoPoint?,
    travel: TravelMode,
    onTravel: (TravelMode) -> Unit,
    planned: PlannedRoute?,
    busy: Boolean,
    onDestination: (GeoPoint) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Double?>(null) }
    var reporting by remember { mutableStateOf(false) }

    GqCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(":compass: Ir a un destino", fontWeight = FontWeight.Bold)
            Text(":close:", modifier = Modifier.clickableNoRipple(onClose))
        }
        ChoiceChips(TRAVEL, travel, { it.label }, onTravel)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, label = { Text("Buscar lugar") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    searching = true
                    scope.launch {
                        results = RoutePlanning.search(query)
                        searching = false
                        if (results.isEmpty()) c.say("No encontré ese lugar (¿hay internet?). Mantén pulsado en el mapa para elegirlo.")
                    }
                },
                enabled = query.isNotBlank() && !searching,
            ) { Text(if (searching) "…" else "Buscar") }
        }
        results.forEach { p ->
            Text(
                ":pin: ${p.name}",
                modifier = Modifier.clickableNoRipple {
                    results = emptyList()
                    onDestination(p.point)
                },
            )
        }
        Muted("También puedes mantener pulsado un punto del mapa para elegirlo como destino.")

        if (busy) Muted("Calculando ruta…")
        planned?.let { r ->
            val eta = if (r.isGuideLine) r.durationS else r.durationS
            Text("${Formats.distance(r.distanceM)} · ≈ ${(eta / 60).roundToInt()} min ${travel.label.lowercase()}", fontWeight = FontWeight.Bold)
            Muted("Fuente: ${r.source}" + if (r.isGuideLine) ". Sin internet solo puedo dibujar una línea guía; el camino real necesita conexión." else "")

            val now = System.currentTimeMillis()
            val reports = state.reports.map {
                RouteReport(it.id, enumOr(it.type, ReportType.HAZARD), GeoPoint(it.lat, it.lon), it.createdMs, state.settings.userId)
            }
            val onRoute = RouteAhead.reportsOnRoute(r.points, reports, now)
            if (onRoute.isNotEmpty()) {
                Text(":alert: ${onRoute.size} reporte(s) en tu ruta", color = Gold, fontWeight = FontWeight.SemiBold)
                onRoute.take(3).forEach { (rep, d) -> Muted("• ${rep.type.label} a ${Formats.distance(d)}") }
            }

            if (!r.isGuideLine) {
                val bounds = OfflineMapPlanner.boundsForRoute(r.points, 3.0)
                val plan = bounds?.let { OfflineMapPlanner.plan("Ruta", it, MapDetail.STANDARD) }
                if (plan != null && OfflineMapPlanner.isAllowed(plan)) {
                    val p = progress
                    if (p != null) {
                        Muted("Descargando mapa de la ruta… ${(p * 100).toInt()}%")
                    } else {
                        OutlinedButton(
                            onClick = {
                                progress = 0.0
                                OfflineDownloader.start(
                                    context, state.settings.mapStyle, plan.bounds, plan.minZoom, plan.maxZoom, "Ruta · ${Formats.distance(r.distanceM)}",
                                    onProgress = { progress = it },
                                    onDone = { progress = null; c.say("Mapa de la ruta descargado :check:") },
                                    onError = { progress = null; c.say(it) },
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(":download: Mapa de esta ruta sin internet (≈ ${plan.estimatedMb.roundToInt()} MB)") }
                    }
                }
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Quitar ruta") }
        }

        // Community hazards: saved on the phone now; shared with others once the server exists.
        if (!reporting) {
            OutlinedButton(onClick = { reporting = true }, enabled = position != null, modifier = Modifier.fillMaxWidth()) { Text(":alert: Reportar algo aquí") }
        } else {
            Text("¿Qué hay aquí?", fontWeight = FontWeight.SemiBold)
            ChoiceChips(REPORTABLE, ReportType.HAZARD, { it.label }, { type ->
                position?.let { p ->
                    c.change { Actions.addReport(it, type.name, p, System.currentTimeMillis()) }
                    c.say("Reporte guardado: ${type.label}")
                }
                reporting = false
            })
            Muted("Los reportes se comparten con otros corredores cuando el servidor esté activo.")
        }
        PrimaryButton("Listo", onClose, Modifier.fillMaxWidth())
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(onClick = onClick),
)
