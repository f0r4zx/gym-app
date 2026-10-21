package app.gymquest.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gymquest.core.tracking.CameraState
import app.gymquest.core.tracking.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val TRACK_SOURCE = "gq-track"
private const val ME_SOURCE = "gq-me"

/**
 * An interactive map (vector tiles, tilt for 3D buildings) that draws the recorded route and a dot
 * for your position. When the style has been downloaded for offline use it loads from the phone.
 */
@Composable
fun GymMap(
    styleUrl: String,
    track: List<GeoPoint>,
    trackVersion: Int,
    position: GeoPoint?,
    camera: CameraState?,
    modifier: Modifier = Modifier,
    onReady: (MapLibreMap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centered by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    destroyed = true
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    LaunchedEffect(styleUrl, mapView) {
        mapView.getMapAsync { m ->
            map = m
            onReady(m)
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.setStyle(Style.Builder().fromUri(styleUrl)) { s ->
                s.addSource(GeoJsonSource(TRACK_SOURCE, FeatureCollection.fromFeatures(emptyList<Feature>())))
                s.addLayer(
                    LineLayer("gq-track-casing", TRACK_SOURCE).withProperties(
                        PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(9f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                s.addLayer(
                    LineLayer("gq-track-line", TRACK_SOURCE).withProperties(
                        PropertyFactory.lineColor("#FF7A1A"), PropertyFactory.lineWidth(5.5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    ),
                )
                s.addSource(GeoJsonSource(ME_SOURCE, FeatureCollection.fromFeatures(emptyList<Feature>())))
                s.addLayer(
                    CircleLayer("gq-me-dot", ME_SOURCE).withProperties(
                        PropertyFactory.circleRadius(8f), PropertyFactory.circleColor("#4DA3FF"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
                    ),
                )
                style = s
            }
        }
    }

    // Redraw the route only when the service says the track changed.
    LaunchedEffect(style, trackVersion) {
        val s = style ?: return@LaunchedEffect
        val source = s.getSourceAs<GeoJsonSource>(TRACK_SOURCE) ?: return@LaunchedEffect
        if (track.size >= 2) {
            source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(track.map { Point.fromLngLat(it.lon, it.lat) })))
        } else {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        }
    }

    LaunchedEffect(style, position) {
        val s = style ?: return@LaunchedEffect
        val source = s.getSourceAs<GeoJsonSource>(ME_SOURCE) ?: return@LaunchedEffect
        if (position != null) {
            source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat)))
        }
    }

    LaunchedEffect(map, camera) {
        val m = map ?: return@LaunchedEffect
        val cam = camera ?: return@LaunchedEffect
        val target = CameraPosition.Builder()
            .target(LatLng(cam.center.lat, cam.center.lon))
            .bearing(cam.bearing)
            .tilt(cam.tilt)
            .zoom(cam.zoom)
            .build()
        if (!centered) {
            m.cameraPosition = target
            centered = true
        } else {
            m.animateCamera(CameraUpdateFactory.newCameraPosition(target), 900)
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
