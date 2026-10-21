package app.gymquest.core.fitness

import java.time.LocalDate
import kotlin.math.roundToInt

data class NutritionTargets(
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int,
    val waterMl: Int,
    val bmr: Int,
    val tdee: Int,
    /** Expected weight change per week at these targets, in kg (negative = losing). */
    val weeklyChangeKg: Double,
    val notes: List<String>,
)

enum class Severity { INFO, TIP, WARNING }

data class AdviceNote(val severity: Severity, val text: String)

object NutritionPlanner {
    private const val KCAL_PER_KG = 7700.0
    private const val DISCLAIMER = "Son estimaciones generales; no sustituyen la opinión de un médico o nutriólogo."

    fun targets(profile: UserProfile, today: LocalDate, exerciseMinutesToday: Int = 0): NutritionTargets {
        val age = profile.ageOn(today)
        val bmr = BodyMetrics.bmr(profile.sex, profile.weightKg, profile.heightCm, age)
        val tdee = BodyMetrics.tdee(bmr, profile.activityLevel)
        val bmi = BodyMetrics.bmi(profile.weightKg, profile.heightCm)
        val notes = mutableListOf<String>()

        var kcal = when (profile.goal) {
            Goal.LOSE_FAT -> {
                val pct = when {
                    bmi >= 30 -> 0.25
                    bmi >= 25 -> 0.20
                    else -> 0.12
                }
                // Never lose faster than ~1% of body weight per week.
                val cap = profile.weightKg * 0.01 * KCAL_PER_KG / 7.0
                tdee - minOf(tdee * pct, cap)
            }
            Goal.BUILD_MUSCLE -> tdee * when (profile.experience) {
                Experience.BEGINNER -> 1.12
                Experience.INTERMEDIATE -> 1.10
                Experience.ADVANCED -> 1.07
            }
            Goal.ENDURANCE -> tdee * 1.05
            Goal.MAINTAIN, Goal.GENERAL_HEALTH -> tdee
        }

        val floor = if (profile.sex == Sex.MALE) 1500.0 else 1200.0
        if (kcal < floor) {
            kcal = floor
            notes += "Subimos tu meta a $floor kcal: comer menos que eso no es seguro sin supervisión médica."
        }

        val protein = (profile.weightKg * when (profile.goal) {
            Goal.LOSE_FAT -> 2.0
            Goal.BUILD_MUSCLE -> 1.8
            Goal.MAINTAIN, Goal.ENDURANCE -> 1.5
            Goal.GENERAL_HEALTH -> 1.3
        }).coerceAtMost(kcal * 0.40 / 4.0)

        val fat = maxOf(kcal * 0.27 / 9.0, profile.weightKg * 0.7)
        val carbs = ((kcal - protein * 4 - fat * 9) / 4.0).coerceAtLeast(80.0)

        val weekly = (kcal - tdee) * 7.0 / KCAL_PER_KG
        val water = Hydration.targetMl(profile.weightKg, exerciseMinutesToday)

        notes += when (profile.goal) {
            Goal.LOSE_FAT -> "Déficit moderado para perder grasa sin perder músculo: prioriza la proteína y entrena fuerza."
            Goal.BUILD_MUSCLE -> "Superávit pequeño para ganar músculo con poca grasa. Sube la carga poco a poco."
            Goal.ENDURANCE -> "Más carbohidratos para sostener tus entrenos de cardio."
            Goal.MAINTAIN -> "Comes lo que gastas: ideal para mantener tu peso."
            Goal.GENERAL_HEALTH -> "Enfócate en comida real, moverte a diario y dormir bien."
        }
        notes += DISCLAIMER

        return NutritionTargets(
            kcal = kcal.roundToInt(),
            proteinG = protein.roundToInt(),
            carbsG = carbs.roundToInt(),
            fatG = fat.roundToInt(),
            fiberG = (kcal / 1000.0 * 14).roundToInt(),
            waterMl = water,
            bmr = bmr.roundToInt(),
            tdee = tdee.roundToInt(),
            weeklyChangeKg = weekly,
            notes = notes,
        )
    }
}

/** Sanity checks on a goal so the app never nudges someone toward something unsafe. */
object HealthAdvisor {
    fun review(profile: UserProfile, today: LocalDate): List<AdviceNote> {
        val notes = mutableListOf<AdviceNote>()
        val age = profile.ageOn(today)
        val bmi = BodyMetrics.bmi(profile.weightKg, profile.heightCm)
        val category = BodyMetrics.bmiCategory(bmi)

        if (age < 16) {
            notes += AdviceNote(Severity.WARNING, "Si tienes menos de 16 años, consulta a un médico o entrenador antes de seguir un plan de calorías.")
        }
        notes += AdviceNote(Severity.INFO, "Tu IMC es ${"%.1f".format(bmi)} (${category.label}). Es una referencia general: no distingue músculo de grasa.")

        val target = profile.targetWeightKg
        if (target != null) {
            val targetBmi = BodyMetrics.bmi(target, profile.heightCm)
            val range = BodyMetrics.healthyWeightRangeKg(profile.heightCm)
            if (targetBmi < 18.5) {
                notes += AdviceNote(Severity.WARNING, "Tu peso meta (${target.format1()} kg) queda por debajo del rango saludable (${range.start.format1()}–${range.endInclusive.format1()} kg). Te recomendamos hablar con un profesional de salud.")
            }
            if (profile.goal == Goal.LOSE_FAT && target >= profile.weightKg) {
                notes += AdviceNote(Severity.TIP, "Tu meta es bajar grasa, pero tu peso meta no es menor al actual. ¿Quieres revisarlo?")
            }
            if (profile.goal == Goal.BUILD_MUSCLE && target <= profile.weightKg) {
                notes += AdviceNote(Severity.TIP, "Quieres ganar volumen, pero tu peso meta no es mayor al actual. ¿Quieres revisarlo?")
            }
            val targets = NutritionPlanner.targets(profile, today)
            val weeks = WeightForecast.weeksToReach(profile.weightKg, target, targets.weeklyChangeKg)
            if (weeks != null) {
                notes += AdviceNote(Severity.INFO, "Con este plan llegarías a ${target.format1()} kg en unas $weeks semanas (aprox.).")
            }
        }

        if (category == BmiCategory.UNDERWEIGHT && profile.goal == Goal.LOSE_FAT) {
            notes += AdviceNote(Severity.WARNING, "Con tu IMC actual no es recomendable bajar más de peso. Mejor enfócate en fuerza y alimentación.")
        }
        notes += AdviceNote(Severity.INFO, "Si tienes alguna condición médica, lesión o tomas medicamentos, consulta a tu médico antes de entrenar fuerte.")
        return notes
    }

    private fun Double.format1() = "%.1f".format(this)
}

object WeightForecast {
    /** Weeks to get from [current] to [target] at [weeklyChangeKg], or null if the trend goes the wrong way. */
    fun weeksToReach(current: Double, target: Double, weeklyChangeKg: Double): Int? {
        val delta = target - current
        if (kotlin.math.abs(delta) < 0.25) return 0
        if (weeklyChangeKg == 0.0 || (delta > 0) != (weeklyChangeKg > 0)) return null
        return kotlin.math.ceil(delta / weeklyChangeKg).toInt()
    }
}
