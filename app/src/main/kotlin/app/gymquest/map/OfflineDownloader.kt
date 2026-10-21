package app.gymquest.map

import android.content.Context
import app.gymquest.core.tracking.LatLonBounds
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/** Downloads a map area into the map library's offline store so it can be shown with no internet. */
object OfflineDownloader {
    fun manager(context: Context): OfflineManager {
        MapLibre.getInstance(context)
        return OfflineManager.getInstance(context)
    }

    fun start(
        context: Context,
        styleUrl: String,
        bounds: LatLonBounds,
        minZoom: Int,
        maxZoom: Int,
        label: String,
        onProgress: (Double) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val box = LatLngBounds.Builder()
            .include(LatLng(bounds.south, bounds.west))
            .include(LatLng(bounds.north, bounds.east))
            .build()
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl, box, minZoom.toDouble(), maxZoom.toDouble(), context.resources.displayMetrics.density,
        )
        manager(context).createOfflineRegion(
            definition, label.toByteArray(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val required = status.requiredResourceCount.coerceAtLeast(1)
                            onProgress(status.completedResourceCount.toDouble() / required)
                            if (status.isComplete) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                onDone()
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            onError("${error.reason}: ${error.message}")
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            onError("Demasiadas teselas (límite $limit).")
                        }
                    })
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    onError(error)
                }
            },
        )
    }
}
