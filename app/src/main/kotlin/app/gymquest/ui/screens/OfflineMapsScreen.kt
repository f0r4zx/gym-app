package app.gymquest.ui.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import app.gymquest.ui.components.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.tracking.GeoPoint
import app.gymquest.core.tracking.MapDetail
import app.gymquest.core.tracking.OfflineMapPlanner
import app.gymquest.data.AppState
import app.gymquest.map.OfflineDownloader
import app.gymquest.ui.Controller
import app.gymquest.ui.components.Bar
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.PrimaryButton
import app.gymquest.ui.components.SectionTitle
import app.gymquest.ui.theme.Danger
import app.gymquest.ui.theme.Mint
import com.google.android.gms.location.LocationServices
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

private class RegionRow(val region: OfflineRegion, val name: String)

/**
 * Downloads the map of a zone so the map (and GPS tracking on top of it) works with no internet.
 * The download is done by the map library's own offline store.
 */
@SuppressLint("MissingPermission")
@Composable
fun OfflineMapsScreen(state: AppState, c: Controller, onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember {
        MapLibre.getInstance(context)
        OfflineManager.getInstance(context)
    }
    var center by remember { mutableStateOf<GeoPoint?>(null) }
    var radiusKm by rememberSaveable { mutableIntStateOf(5) }
    var detailName by rememberSaveable { mutableStateOf(MapDetail.STANDARD.name) }
    var regions by remember { mutableStateOf<List<RegionRow>>(emptyList()) }
    var progress by remember { mutableStateOf<Double?>(null) }
    var statusText by remember { mutableStateOf("") }
    val detail = runCatching { MapDetail.valueOf(detailName) }.getOrDefault(MapDetail.STANDARD)

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                regions = offlineRegions.orEmpty().map { RegionRow(it, runCatching { String(it.metadata) }.getOrDefault("Zona")) }
            }

            override fun onError(error: String) {
                statusText = error
            }
        })
    }

    LaunchedEffect(Unit) {
        refresh()
        runCatching {
            LocationServices.getFusedLocationProviderClient(context).lastLocation.addOnSuccessListener { loc ->
                if (loc != null) center = GeoPoint(loc.latitude, loc.longitude)
            }
        }
    }

    val here = center
    val maxKm = here?.let { OfflineMapPlanner.maxRadiusKm(it, detail) } ?: 20
    val plan = here?.let { OfflineMapPlanner.around("Mi zona", it, radiusKm.coerceAtMost(maxKm).toDouble(), detail) }
    val freeBytes = context.filesDir.usableSpace

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader("Mapas sin internet", onBack)
        GqCard {
            Text("Descarga la zona donde sales a moverte", fontWeight = FontWeight.Bold)
            Muted("Con el mapa descargado, el GPS sigue mostrando tu ruta sin datos móviles ni wifi. El GPS en sí nunca necesita internet.")
        }

        GqCard {
            Text("Nueva descarga", fontWeight = FontWeight.Bold)
            if (here == null) {
                Muted("Aún no tengo tu ubicación. Abre el mapa una vez con el GPS activado y vuelve.")
            } else {
                Text("Radio: ${radiusKm.coerceAtMost(maxKm)} km alrededor de donde estás")
                ChoiceChips(listOf(2, 3, 5, 10, 20).filter { it <= maxKm }, radiusKm.coerceAtMost(maxKm), { "$it km" }, { radiusKm = it })
                Text("Nivel de detalle")
                ChoiceChips(MapDetail.entries, detail, { it.label }, { detailName = it.name })
                plan?.let {
                    Muted("≈ ${"%.0f".format(it.estimatedMb)} MB · ${it.tileCount} teselas · zoom ${it.minZoom}–${it.maxZoom}")
                    if (!OfflineMapPlanner.fitsOnDevice(it, freeBytes)) Text("No hay espacio suficiente en el teléfono.", color = Danger)
                }
                progress?.let { p ->
                    Bar(p, color = Mint, height = 12.dp)
                    Muted("Descargando… ${(p * 100).toInt()}%")
                }
                PrimaryButton(
                    "Descargar esta zona",
                    onClick = {
                        val pl = plan ?: return@PrimaryButton
                        progress = 0.0
                        OfflineDownloader.start(
                            context, state.settings.mapStyle, pl.bounds, pl.minZoom, pl.maxZoom,
                            "Zona ${regions.size + 1} · ${radiusKm.coerceAtMost(maxKm)} km",
                            onProgress = { progress = it },
                            onDone = {
                                progress = null
                                c.say("Mapa descargado :check:")
                                refresh()
                            },
                            onError = {
                                progress = null
                                statusText = it
                            },
                        )
                    },
                    enabled = plan != null && progress == null && OfflineMapPlanner.isAllowed(plan) && OfflineMapPlanner.fitsOnDevice(plan, freeBytes),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (statusText.isNotBlank()) Text(statusText, color = Danger)
        }

        SectionTitle("Zonas descargadas")
        if (regions.isEmpty()) Muted("Aún no has descargado ninguna.")
        regions.forEach { row ->
            GqCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = {
                        row.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() = refresh()
                            override fun onError(error: String) {
                                statusText = error
                            }
                        })
                    }) { Text("Borrar") }
                }
            }
        }

        GqCard {
            Muted("Los mapas vienen del proveedor configurado en Ajustes. Revisa sus condiciones de uso antes de descargar zonas grandes.")
        }
    }
}
