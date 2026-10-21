package app.gymquest.core.platform

enum class ThermalState { NORMAL, LIGHT, MODERATE, SEVERE }

enum class PoseModel { FAST, ACCURATE }

enum class MapMode { MAP_3D, MAP_2D, MAP_MINIMAL }

/** What the phone is going through right now. */
data class DeviceState(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val powerSaver: Boolean,
    val thermal: ThermalState,
    /** Average time the pose pipeline takes per frame, in ms. */
    val avgFrameMs: Double,
    val freeMemoryMb: Int,
)

/** How hard the app works. Lower tiers keep it cool and smooth on modest phones. */
data class QualityProfile(
    val analysisFps: Int,
    val poseModel: PoseModel,
    val drawSkeleton: Boolean,
    val mapMode: MapMode,
    val animations: Boolean,
) {
    companion object {
        val FULL = QualityProfile(30, PoseModel.ACCURATE, true, MapMode.MAP_3D, true)
        val BALANCED = QualityProfile(20, PoseModel.FAST, true, MapMode.MAP_3D, true)
        val LIGHT = QualityProfile(15, PoseModel.FAST, true, MapMode.MAP_2D, true)
        val MINIMAL = QualityProfile(10, PoseModel.FAST, false, MapMode.MAP_MINIMAL, false)
        val TIERS = listOf(FULL, BALANCED, LIGHT, MINIMAL)
    }
}

/**
 * Chooses the quality tier from the phone's condition. It drops quickly when the phone struggles
 * (slow frames, heat, low battery) and recovers slowly after several good checks. If a recovery
 * attempt fails soon after, the next attempt waits twice as long, so it never flaps between tiers.
 */
class AdaptiveQuality(private val recoverAfterChecks: Int = 5) {
    var current: QualityProfile = QualityProfile.BALANCED
        private set

    private var goodChecks = 0
    private var required = recoverAfterChecks
    private var checksSinceUpgrade = Int.MAX_VALUE / 2

    /** Call every second or two with fresh readings. */
    fun update(state: DeviceState): QualityProfile {
        if (checksSinceUpgrade < Int.MAX_VALUE / 2) checksSinceUpgrade++
        val wanted = tierFor(state)
        val currentIndex = QualityProfile.TIERS.indexOf(current)
        when {
            wanted > currentIndex -> { // worse conditions: react immediately
                if (checksSinceUpgrade <= required * 3) required = (required * 2).coerceAtMost(MAX_REQUIRED) // that upgrade did not hold
                current = QualityProfile.TIERS[wanted]
                goodChecks = 0
            }
            wanted < currentIndex -> { // better conditions: wait until it stays good
                goodChecks++
                if (goodChecks >= required) {
                    current = QualityProfile.TIERS[currentIndex - 1]
                    goodChecks = 0
                    checksSinceUpgrade = 0
                }
            }
            else -> {
                goodChecks = 0
                // Long stable stretch: forget the penalty.
                if (checksSinceUpgrade > required * 6) required = recoverAfterChecks
            }
        }
        return current
    }

    /** Index into [QualityProfile.TIERS]: 0 = full quality, 3 = minimal. */
    private fun tierFor(s: DeviceState): Int {
        val currentIndex = QualityProfile.TIERS.indexOf(current)
        val budgetMs = 1000.0 / current.analysisFps
        var tier = when {
            s.avgFrameMs > budgetMs * 0.9 -> currentIndex + 1 // not keeping up
            s.avgFrameMs < budgetMs * 0.4 -> 0               // lots of headroom
            else -> currentIndex
        }
        when (s.thermal) {
            ThermalState.LIGHT -> tier = maxOf(tier, 1)
            ThermalState.MODERATE -> tier = maxOf(tier, 2)
            ThermalState.SEVERE -> tier = maxOf(tier, 3)
            ThermalState.NORMAL -> Unit
        }
        if (s.powerSaver) tier = maxOf(tier, 2)
        if (!s.isCharging && s.batteryPercent <= 15) tier = maxOf(tier, 2)
        if (!s.isCharging && s.batteryPercent <= 5) tier = 3
        if (s.freeMemoryMb < 150) tier = maxOf(tier, 2)
        return tier.coerceIn(0, QualityProfile.TIERS.lastIndex)
    }

    private companion object {
        const val MAX_REQUIRED = 300
    }
}

/** Skips frames to hit the target analysis rate: the camera may deliver 30 fps but there is no need to analyse all of them. */
class FramePacer(var targetFps: Int) {
    private var lastMs = Long.MIN_VALUE / 2

    fun shouldProcess(nowMs: Long): Boolean {
        val interval = 1000L / targetFps.coerceAtLeast(1)
        // A frame slightly early is fine; this keeps the average at the target without drift.
        if (nowMs - lastMs >= interval - interval / 8) {
            lastMs = nowMs
            return true
        }
        return false
    }
}

/** How often the GPS is asked for a position: often when moving fast, rarely when standing, sparingly on low battery. */
object GpsPolicy {
    fun intervalMs(speedMps: Double, batteryPercent: Int, charging: Boolean): Long {
        val base = when {
            speedMps >= 6.0 -> 1_000L
            speedMps >= 2.0 -> 1_000L
            speedMps >= 0.5 -> 2_000L
            else -> 5_000L
        }
        val factor = if (!charging && batteryPercent <= 15) 2L else 1L
        return base * factor
    }
}
