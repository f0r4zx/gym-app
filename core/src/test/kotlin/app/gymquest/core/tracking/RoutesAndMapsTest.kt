package app.gymquest.core.tracking

import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val ZOCALO = GeoPoint(19.4326, -99.1332)

private fun path(start: GeoPoint, vararg legs: Pair<Double, Double>, stepM: Double = 10.0): List<GeoPoint> {
    val out = mutableListOf(start)
    var cur = start
    for ((bearing, length) in legs) {
        var done = 0.0
        while (done < length) {
            val s = minOf(stepM, length - done)
            cur = Geo.destination(cur, bearing, s)
            out += cur
            done += s
        }
    }
    return out
}

class PolylineTest {
    @Test
    fun matchesGooglesDocumentedExample() {
        val pts = listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(pts))
        val back = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, back.size)
        assertEquals(43.252, back[2].lat, 1e-5)
        assertEquals(-126.453, back[2].lon, 1e-5)
    }

    @Test
    fun roundTripsARealTrack() {
        val track = path(ZOCALO, 45.0 to 3000.0, 135.0 to 2000.0)
        val back = Polyline.decode(Polyline.encode(track))
        assertEquals(track.size, back.size)
        for (i in track.indices step 17) assertTrue(Geo.distance(track[i], back[i]) < 1.5)
        assertTrue(Polyline.encode(track).length < track.size * 8)
    }

    @Test
    fun emptyAndSinglePoint() {
        assertEquals("", Polyline.encode(emptyList()))
        assertTrue(Polyline.decode("").isEmpty())
        val one = Polyline.decode(Polyline.encode(listOf(ZOCALO)))
        assertEquals(1, one.size)
    }

    @Test
    fun simplifyKeepsShapeAndDropsMostPoints() {
        val track = path(ZOCALO, 90.0 to 2000.0, 0.0 to 1500.0, 270.0 to 1000.0)
        val simple = Polyline.simplify(track, 5.0)
        assertTrue(simple.size < track.size / 10, "kept ${simple.size} of ${track.size}")
        assertTrue(simple.size >= 4)
        assertEquals(track.first(), simple.first())
        assertEquals(track.last(), simple.last())
        assertTrue(abs(Geo.length(simple) - Geo.length(track)) < 25.0)
    }

    @Test
    fun simplifyDoesNotCutRealCorners() {
        val l = path(ZOCALO, 0.0 to 500.0, 90.0 to 500.0)
        val simple = Polyline.simplify(l, 5.0)
        assertTrue(simple.size >= 3)
        val corner = Geo.destination(ZOCALO, 0.0, 500.0)
        assertTrue(simple.any { Geo.distance(it, corner) < 12.0 })
    }

    @Test
    fun routeCursorInterpolatesAndProjects() {
        val route = path(ZOCALO, 90.0 to 1000.0)
        val cursor = RouteCursor(route)
        assertEquals(1000.0, cursor.totalM, 2.0)
        val mid = cursor.pointAt(500.0)
        assertEquals(500.0, Geo.distance(ZOCALO, mid), 2.0)
        assertEquals(ZOCALO, cursor.pointAt(-10.0))
        assertEquals(cursor.totalM, Geo.distance(ZOCALO, cursor.pointAt(99_999.0)), 2.0)

        val beside = Geo.destination(Geo.destination(ZOCALO, 90.0, 300.0), 0.0, 40.0)
        val proj = cursor.project(beside)!!
        assertEquals(300.0, proj.alongM, 4.0)
        assertEquals(40.0, proj.offRouteM, 2.0)
    }
}

class RoutesTest {
    private fun route(id: String, owner: String, pts: List<GeoPoint>, type: ActivityType = ActivityType.RUN, t: Long = 1_000_000_000L, name: String = id) =
        RouteSummary(id, owner, type, name, pts, Geo.length(pts), 1_800_000, t)

    private fun noisy(pts: List<GeoPoint>, seed: Long, sigmaM: Double = 6.0): List<GeoPoint> {
        val r = Random(seed)
        return pts.map { Geo.destination(it, r.nextDouble() * 360, abs(r.nextGaussian()) * sigmaM) }
    }

    private val loop = path(ZOCALO, 90.0 to 1500.0, 0.0 to 1000.0, 270.0 to 1500.0, 180.0 to 1000.0)

    @Test
    fun sameRouteWithGpsNoiseIsVerySimilar() {
        val s = RouteFingerprint.similarity(loop, noisy(loop, 7))
        assertTrue(s > 0.55, "similarity $s")
    }

    @Test
    fun reversedDirectionIsTheSameRoute() {
        assertTrue(RouteFingerprint.similarity(loop, loop.reversed()) > 0.99)
    }

    @Test
    fun differentPlacesAreNotSimilar() {
        val far = path(GeoPoint(20.6597, -103.3496), 90.0 to 1500.0, 0.0 to 1000.0)
        assertEquals(0.0, RouteFingerprint.similarity(loop, far))
        val nearbyOther = path(Geo.destination(ZOCALO, 0.0, 3000.0), 90.0 to 1500.0, 0.0 to 1000.0)
        assertTrue(RouteFingerprint.similarity(loop, nearbyOther) < 0.1)
    }

    @Test
    fun sparsePointsStillCoverTheirCells() {
        val dense = path(ZOCALO, 90.0 to 2000.0, stepM = 10.0)
        val sparse = path(ZOCALO, 90.0 to 2000.0, stepM = 400.0)
        assertTrue(RouteFingerprint.similarity(dense, sparse) > 0.8)
    }

    @Test
    fun emptyRoutesHaveNoSimilarity() {
        assertEquals(0.0, RouteFingerprint.similarity(emptyList(), loop))
    }

    @Test
    fun clustersTheSameRouteRunByDifferentPeople() {
        val routes = listOf(
            route("a1", "ana", loop), route("b1", "beto", noisy(loop, 1)), route("c1", "carla", noisy(loop, 2)),
            route("a2", "ana", noisy(loop, 3)),
            route("x1", "dan", path(Geo.destination(ZOCALO, 0.0, 6000.0), 90.0 to 3000.0)),
        )
        val clusters = PopularRoutes.cluster(routes)
        assertEquals(2, clusters.size)
        val big = clusters.maxByOrNull { it.members.size }!!
        assertEquals(4, big.members.size)
        assertEquals(3, big.distinctUsers)
    }

    @Test
    fun differentActivitiesDoNotMerge() {
        val clusters = PopularRoutes.cluster(listOf(route("r", "ana", loop, ActivityType.RUN), route("b", "beto", loop, ActivityType.RIDE)))
        assertEquals(2, clusters.size)
    }

    @Test
    fun manyPeopleBeatOnePersonRepeating() {
        val now = 2_000_000_000L
        val other = path(Geo.destination(ZOCALO, 180.0, 8000.0), 90.0 to 2500.0, 0.0 to 800.0)
        val routes = listOf(
            route("s1", "solo", other, t = now - 1000), route("s2", "solo", other, t = now - 2000), route("s3", "solo", other, t = now - 3000),
            route("p1", "u1", loop, t = now - 1000), route("p2", "u2", loop, t = now - 1000), route("p3", "u3", loop, t = now - 1000),
        )
        val ranked = PopularRoutes.rank(PopularRoutes.cluster(routes), now)
        assertEquals("p1", ranked.first().representative.id.take(2).let { if (it == "p1") "p1" else ranked.first().representative.id }.takeIf { ranked.first().distinctUsers == 3 } ?: "wrong")
        assertTrue(ranked.first().distinctUsers == 3)
    }

    @Test
    fun oldRoutesDropOutOfTheRanking() {
        val now = 2_000_000_000_000L
        val old = route("o", "ana", loop, t = now - 200L * 86_400_000)
        val c = PopularRoutes.cluster(listOf(old))
        assertEquals(0.0, PopularRoutes.score(c.first(), now, 60))
    }

    @Test
    fun nearbyFilterUsesTheStartPoint() {
        val c = PopularRoutes.cluster(listOf(route("a", "ana", loop), route("b", "beto", path(GeoPoint(20.0, -103.0), 90.0 to 2000.0))))
        val near = PopularRoutes.near(c, ZOCALO, 2000.0)
        assertEquals(1, near.size)
    }
}

class OfflineMapsTest {
    @Test
    fun tileMathMatchesKnownTiles() {
        // z0 is one tile; z1 splits into 4. London at zoom 10 is the classic OSM wiki example (511/340).
        assertEquals(0, TileMath.tileX(10.0, 0))
        assertEquals(1, TileMath.tileX(10.0, 1))
        assertEquals(0, TileMath.tileX(-10.0, 1))
        assertEquals(0, TileMath.tileY(60.0, 1))
        assertEquals(1, TileMath.tileY(-60.0, 1))
        assertEquals(511, TileMath.tileX(-0.1278, 10))
        assertEquals(340, TileMath.tileY(51.5074, 10))
        // Mexico City, cross-checked against an independent implementation.
        assertEquals(57, TileMath.tileX(-99.1332, 8))
        assertEquals(113, TileMath.tileY(19.4326, 8))
        assertEquals(920, TileMath.tileX(-99.1332, 12))
        assertEquals(1822, TileMath.tileY(19.4326, 12))
    }

    @Test
    fun poleAndDatelineClamp() {
        assertEquals(0, TileMath.tileY(89.9, 5))
        assertEquals(31, TileMath.tileY(-89.9, 5))
        assertEquals(31, TileMath.tileX(180.0, 5))
    }

    @Test
    fun tileCountGrowsFourfoldPerZoom() {
        val b = OfflineMapPlanner.boundsAround(ZOCALO, 60.0)
        val z12 = TileMath.count(b, 12)
        val z14 = TileMath.count(b, 14)
        assertTrue(z14 in z12 * 13..z12 * 19, "z12=$z12 z14=$z14")
    }

    @Test
    fun cityPlanIsReasonableAndLargerAreasCostMore() {
        val city = OfflineMapPlanner.around("CDMX", ZOCALO, 10.0)
        val region = OfflineMapPlanner.around("Valle", ZOCALO, 40.0)
        assertTrue(OfflineMapPlanner.isAllowed(city))
        assertTrue(city.estimatedMb in 15.0..600.0, "city ${city.estimatedMb} MB")
        assertTrue(region.estimatedBytes > city.estimatedBytes * 3)
        assertTrue(city.minZoom < city.maxZoom)
    }

    @Test
    fun moreDetailMeansBiggerDownloads() {
        val b = OfflineMapPlanner.boundsAround(ZOCALO, 8.0)
        val overview = OfflineMapPlanner.plan("a", b, MapDetail.OVERVIEW)
        val standard = OfflineMapPlanner.plan("a", b, MapDetail.STANDARD)
        val detailed = OfflineMapPlanner.plan("a", b, MapDetail.DETAILED)
        assertTrue(overview.estimatedBytes < standard.estimatedBytes && standard.estimatedBytes < detailed.estimatedBytes)
    }

    @Test
    fun hugeRegionsAreRefusedAndMaxRadiusIsFound() {
        val huge = OfflineMapPlanner.around("País", ZOCALO, 400.0, MapDetail.DETAILED)
        assertTrue(!OfflineMapPlanner.isAllowed(huge))
        val maxKm = OfflineMapPlanner.maxRadiusKm(ZOCALO, MapDetail.STANDARD)
        assertTrue(maxKm in 5..200)
        assertTrue(OfflineMapPlanner.isAllowed(OfflineMapPlanner.around("x", ZOCALO, maxKm.toDouble())))
        if (maxKm < 200) assertTrue(!OfflineMapPlanner.isAllowed(OfflineMapPlanner.around("x", ZOCALO, maxKm + 1.0)))
    }

    @Test
    fun storageCheckKeepsAReserve() {
        val plan = OfflineMapPlanner.around("x", ZOCALO, 10.0)
        assertTrue(OfflineMapPlanner.fitsOnDevice(plan, 5L * 1024 * 1024 * 1024))
        assertTrue(!OfflineMapPlanner.fitsOnDevice(plan, plan.estimatedBytes + 100))
    }

    @Test
    fun coverageAndRouteCoverage() {
        val city = OfflineMapPlanner.around("CDMX", ZOCALO, 10.0)
        assertEquals(1, OfflineMapPlanner.coverage(ZOCALO, listOf(city)).size)
        assertTrue(OfflineMapPlanner.coverage(GeoPoint(20.6597, -103.3496), listOf(city)).isEmpty())
        val inside = path(ZOCALO, 90.0 to 3000.0)
        val leaving = path(ZOCALO, 90.0 to 30_000.0, stepM = 500.0)
        assertTrue(OfflineMapPlanner.routeCovered(inside, listOf(city)))
        assertTrue(!OfflineMapPlanner.routeCovered(leaving, listOf(city)))
        val share = OfflineMapPlanner.routeCoverage(leaving, listOf(city))
        assertTrue(share in 0.2..0.6, "share $share")
        assertEquals(0.0, OfflineMapPlanner.routeCoverage(emptyList(), listOf(city)))
    }

    @Test
    fun tripBoundsContainTheWholeRouteWithMargin() {
        val trip = path(ZOCALO, 45.0 to 40_000.0, stepM = 500.0)
        val b = OfflineMapPlanner.boundsForRoute(trip, 3.0)
        assertNotNull(b)
        assertTrue(trip.all { it in b })
        val tight = OfflineMapPlanner.boundsForRoute(trip, 0.0)!!
        assertTrue(b.north > tight.north && b.east > tight.east)
        assertEquals(null, OfflineMapPlanner.boundsForRoute(emptyList()))
    }

    @Test
    fun invalidBoundsAreRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { LatLonBounds(10.0, 10.0, 5.0, 20.0) }
    }
}

class FollowCameraTest {
    @Test
    fun bearingFollowsHeadingSmoothlyThroughNorth() {
        val cam = FollowCamera()
        cam.update(ZOCALO, 3.0, 350.0)
        var state = cam.update(ZOCALO, 3.0, 10.0)
        // Must go 350 -> 10 through 0, not the long way round through 180.
        assertTrue(state.bearing > 340.0 || state.bearing < 20.0, "bearing ${state.bearing}")
        repeat(40) { state = cam.update(ZOCALO, 3.0, 10.0) }
        assertEquals(10.0, state.bearing, 1.0)
    }

    @Test
    fun standingStillKeepsTheLastBearing() {
        val cam = FollowCamera()
        repeat(20) { cam.update(ZOCALO, 3.0, 90.0) }
        val still = cam.update(ZOCALO, 0.1, 270.0)
        assertEquals(90.0, still.bearing, 1.0)
    }

    @Test
    fun zoomsOutAsYouGoFaster() {
        assertTrue(FollowCamera.zoomFor(1.4) > FollowCamera.zoomFor(5.0))
        assertTrue(FollowCamera.zoomFor(5.0) > FollowCamera.zoomFor(15.0))
        assertEquals(13.5, FollowCamera.zoomFor(100.0))
        val cam = FollowCamera()
        val slow = cam.update(ZOCALO, 1.4, 0.0)
        var fast = slow
        repeat(60) { fast = cam.update(ZOCALO, 12.0, 0.0) }
        assertTrue(fast.zoom < slow.zoom)
        assertTrue(fast.tilt > 50.0)
    }

    @Test
    fun playbackKeyframesAreSmoothAndCoverTheRoute() {
        val route = path(ZOCALO, 0.0 to 1000.0, 90.0 to 1000.0, 180.0 to 1000.0)
        val frames = RoutePlayback.keyframes(route, 120)
        assertEquals(120, frames.size)
        assertEquals(ZOCALO, frames.first().center)
        assertTrue(Geo.distance(frames.last().center, route.last()) < 5.0)
        // A 90° corner is spread over several frames instead of snapping.
        assertTrue(RoutePlayback.maxTurn(frames) < 30.0, "turn ${RoutePlayback.maxTurn(frames)}")
        assertTrue(frames.all { it.tilt > 55.0 })
        assertTrue(RoutePlayback.keyframes(listOf(ZOCALO), 10).isEmpty())
    }
}
