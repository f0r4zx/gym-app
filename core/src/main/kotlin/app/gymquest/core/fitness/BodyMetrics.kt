package app.gymquest.core.fitness

import kotlin.math.log10
import kotlin.math.pow

enum class BmiCategory(val label: String) {
    UNDERWEIGHT("Bajo peso"), NORMAL("Peso saludable"), OVERWEIGHT("Sobrepeso"), OBESE("Obesidad")
}

data class HeartRateZone(val number: Int, val name: String, val minBpm: Int, val maxBpm: Int)

/** Standard body-composition and energy formulas. All are estimates, not medical measurements. */
object BodyMetrics {
    private const val CM_PER_INCH = 2.54

    fun bmi(weightKg: Double, heightCm: Double): Double = weightKg / (heightCm / 100.0).pow(2)

    fun bmiCategory(bmi: Double): BmiCategory = when {
        bmi < 18.5 -> BmiCategory.UNDERWEIGHT
        bmi < 25.0 -> BmiCategory.NORMAL
        bmi < 30.0 -> BmiCategory.OVERWEIGHT
        else -> BmiCategory.OBESE
    }

    /** Healthy weight range (BMI 18.5–24.9) for a height. */
    fun healthyWeightRangeKg(heightCm: Double): ClosedFloatingPointRange<Double> {
        val m2 = (heightCm / 100.0).pow(2)
        return (18.5 * m2)..(24.9 * m2)
    }

    /** Basal metabolic rate, Mifflin–St Jeor (kcal/day). "Other" uses the midpoint of both constants. */
    fun bmr(sex: Sex, weightKg: Double, heightCm: Double, age: Int): Double {
        val base = 10 * weightKg + 6.25 * heightCm - 5 * age
        return base + when (sex) {
            Sex.MALE -> 5.0
            Sex.FEMALE -> -161.0
            Sex.OTHER -> -78.0
        }
    }

    fun tdee(bmr: Double, level: ActivityLevel): Double = bmr * level.factor

    fun tdee(profile: UserProfile, age: Int): Double =
        tdee(bmr(profile.sex, profile.weightKg, profile.heightCm, age), profile.activityLevel)

    /** Body-fat % from BMI, age and sex (Deurenberg). Rough; good for tracking trends. */
    fun bodyFatFromBmi(bmi: Double, age: Int, sex: Sex): Double {
        val s = when (sex) {
            Sex.MALE -> 1.0
            Sex.FEMALE -> 0.0
            Sex.OTHER -> 0.5
        }
        return (1.20 * bmi + 0.23 * age - 10.8 * s - 5.4).coerceIn(3.0, 60.0)
    }

    /**
     * Body-fat % from tape measurements (US Navy method). [hipCm] is only used for women/other.
     * Returns null if the measurements are impossible (e.g. neck wider than waist).
     */
    fun bodyFatNavy(sex: Sex, heightCm: Double, neckCm: Double, waistCm: Double, hipCm: Double? = null): Double? {
        // The published equations are in inches.
        val h = heightCm / CM_PER_INCH
        val neck = neckCm / CM_PER_INCH
        val waist = waistCm / CM_PER_INCH
        if (waist - neck <= 0) return null
        val male = 86.010 * log10(waist - neck) - 70.041 * log10(h) + 36.76
        val female = hipCm?.let { 163.205 * log10(waist + it / CM_PER_INCH - neck) - 97.684 * log10(h) - 78.387 }
        val value = when (sex) {
            Sex.MALE -> male
            Sex.FEMALE -> female ?: return null
            Sex.OTHER -> if (female != null) (male + female) / 2 else male
        }
        return value.takeIf { it.isFinite() }?.coerceIn(2.0, 60.0)
    }

    fun leanMassKg(weightKg: Double, bodyFatPercent: Double): Double = weightKg * (1 - bodyFatPercent / 100.0)

    fun waistToHeight(waistCm: Double, heightCm: Double): Double = waistCm / heightCm

    /** Tanaka formula, better than 220-age for adults. */
    fun maxHeartRate(age: Int): Int = (208 - 0.7 * age).toInt()

    /** Five training zones. With a resting rate it uses Karvonen (heart-rate reserve). */
    fun heartRateZones(maxHr: Int, restingHr: Int? = null): List<HeartRateZone> {
        val names = listOf("Recuperación", "Quema grasa", "Aeróbica", "Umbral", "Máximo")
        val edges = listOf(0.50, 0.60, 0.70, 0.80, 0.90, 1.00)
        fun bpm(p: Double) = if (restingHr != null) (restingHr + (maxHr - restingHr) * p).toInt() else (maxHr * p).toInt()
        return names.indices.map { i -> HeartRateZone(i + 1, names[i], bpm(edges[i]), bpm(edges[i + 1])) }
    }

    /** Energy burned by an activity with a given MET value. */
    fun caloriesFromMet(met: Double, weightKg: Double, minutes: Double): Double = met * weightKg * (minutes / 60.0)
}
