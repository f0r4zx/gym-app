package app.gymquest.core.avatar

import app.gymquest.core.fitness.BodyMetrics
import app.gymquest.core.fitness.Sex
import kotlin.math.roundToInt

enum class SkinTone { TONE_1, TONE_2, TONE_3, TONE_4, TONE_5, TONE_6 }
enum class HairStyle { SHORT, BUZZ, LONG, PONYTAIL, CURLY, BALD, BRAIDS, AFRO }

/**
 * The body proportions the renderer draws. All values are 0..1 sliders (0.5 = average) so the
 * avatar can be animated between two states, e.g. to replay your transformation over months.
 */
data class BodyShape(
    /** 0 = very lean, 1 = very heavy. Driven by body fat / BMI. */
    val fatness: Double,
    /** 0 = no visible muscle, 1 = very muscular. Driven by strength stats. */
    val muscle: Double,
    /** Height relative to the average, 0.5 = average adult. */
    val height: Double,
) {
    /** Torso width: wider with fat and muscle. */
    val torsoWidth: Double get() = 0.35 + fatness * 0.35 + muscle * 0.2
    val waist: Double get() = 0.3 + fatness * 0.5 - muscle * 0.05
    val shoulders: Double get() = 0.4 + muscle * 0.4 + fatness * 0.1
    val limbThickness: Double get() = 0.3 + fatness * 0.35 + muscle * 0.3
    val belly: Double get() = (fatness - 0.35).coerceAtLeast(0.0) * 1.4
}

data class AvatarLook(
    val skin: SkinTone = SkinTone.TONE_3,
    val hair: HairStyle = HairStyle.SHORT,
    val hairColor: Int = 0x2B1B12,
    val outfit: Outfit = Outfit.starter(),
)

object AvatarMorph {
    /**
     * Maps body composition to the avatar's fatness. Uses measured body-fat % when you have it and
     * falls back to BMI. The curve is calibrated per sex because the same percentage looks different.
     */
    fun fatness(sex: Sex, heightCm: Double, weightKg: Double, bodyFatPercent: Double?): Double {
        if (bodyFatPercent != null) {
            val (lean, avg, heavy) = when (sex) {
                Sex.FEMALE -> Triple(16.0, 28.0, 45.0)
                Sex.MALE -> Triple(7.0, 20.0, 38.0)
                Sex.OTHER -> Triple(12.0, 24.0, 42.0)
            }
            return piecewise(bodyFatPercent, lean to 0.0, avg to 0.4, heavy to 1.0)
        }
        val bmi = BodyMetrics.bmi(weightKg, heightCm)
        return piecewise(bmi, 16.0 to 0.0, 22.0 to 0.3, 27.0 to 0.55, 32.0 to 0.75, 40.0 to 1.0)
    }

    /** Muscle from the Strength stat plus a little from Endurance (lean, toned look). */
    fun muscle(stats: Map<Stat, Int>): Double {
        val str = (stats[Stat.STRENGTH] ?: 1) / 100.0
        val end = (stats[Stat.ENDURANCE] ?: 1) / 100.0
        return (str * 0.85 + end * 0.15).coerceIn(0.0, 1.0)
    }

    fun shape(sex: Sex, heightCm: Double, weightKg: Double, bodyFatPercent: Double?, stats: Map<Stat, Int>): BodyShape =
        BodyShape(
            fatness = fatness(sex, heightCm, weightKg, bodyFatPercent),
            muscle = muscle(stats),
            height = piecewise(heightCm, 145.0 to 0.0, 170.0 to 0.5, 195.0 to 1.0),
        )

    /** Linear blend between two shapes, for animating a transformation or a weight timeline. */
    fun blend(a: BodyShape, b: BodyShape, t: Double): BodyShape {
        val k = t.coerceIn(0.0, 1.0)
        fun mix(x: Double, y: Double) = x * (1 - k) + y * k // exact at both ends
        return BodyShape(mix(a.fatness, b.fatness), mix(a.muscle, b.muscle), mix(a.height, b.height))
    }

    /** One frame per [steps] along a weight history, for the "tu evolución" slider. */
    fun timeline(shapes: List<BodyShape>, steps: Int): List<BodyShape> {
        if (shapes.size < 2 || steps < 2) return shapes
        return (0 until steps).map { i ->
            val pos = i.toDouble() / (steps - 1) * (shapes.size - 1)
            val idx = pos.toInt().coerceAtMost(shapes.size - 2)
            blend(shapes[idx], shapes[idx + 1], pos - idx)
        }
    }

    fun describe(shape: BodyShape): String {
        val build = when {
            shape.fatness < 0.2 -> "muy delgado"
            shape.fatness < 0.45 -> "atlético"
            shape.fatness < 0.65 -> "robusto"
            else -> "corpulento"
        }
        val tone = if (shape.muscle > 0.6) "musculoso" else if (shape.muscle > 0.3) "tonificado" else "sin definir"
        return "$build, $tone (${(shape.fatness * 100).roundToInt()}% volumen)"
    }

    private fun piecewise(x: Double, vararg pts: Pair<Double, Double>): Double {
        if (x <= pts.first().first) return pts.first().second
        if (x >= pts.last().first) return pts.last().second
        val i = pts.indexOfFirst { it.first >= x }
        val (x0, y0) = pts[i - 1]
        val (x1, y1) = pts[i]
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }
}
