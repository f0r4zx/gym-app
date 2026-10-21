package app.gymquest.core.fitness

import app.gymquest.core.exercise.MuscleGroup
import java.time.LocalDate

data class Recommendation(val exercise: GymExercise, val score: Double, val reasons: List<String>)

/**
 * Ranks exercises for a specific person: their goal, age, experience and equipment. Camera-tracked
 * exercises get a small boost because they can be counted and used against bosses.
 */
object ExerciseRecommender {

    fun recommend(profile: UserProfile, today: LocalDate, limit: Int = 8): List<Recommendation> {
        val age = profile.ageOn(today)
        val pool = GymCatalog.available(profile.equipment, profile.experience)
            .filter { AgeGuidance.allows(it, age, profile.experience) }
        return pool
            .map { score(it, profile, age) }
            .sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.exercise.name })
            .take(limit)
    }

    /** The best picks for each muscle group, for a "what should I train" browser. */
    fun byMuscle(profile: UserProfile, today: LocalDate, perGroup: Int = 3): Map<MuscleGroup, List<Recommendation>> =
        recommend(profile, today, limit = Int.MAX_VALUE)
            .groupBy { it.exercise.primary }
            .mapValues { it.value.take(perGroup) }

    private fun score(ex: GymExercise, p: UserProfile, age: Int): Recommendation {
        var score = 0.0
        val reasons = mutableListOf<String>()
        val bigMuscle = ex.primary in setOf(MuscleGroup.QUADS, MuscleGroup.GLUTES, MuscleGroup.BACK, MuscleGroup.CHEST, MuscleGroup.HAMSTRINGS, MuscleGroup.FULL_BODY)

        when (p.goal) {
            Goal.LOSE_FAT -> {
                if (ex.compound && bigMuscle) { score += 3.0; reasons += "Trabaja muchos músculos a la vez: gasta más calorías." }
                if (ex.cardio) { score += 2.0; reasons += "Sube tu gasto calórico." }
                if (ex.compound) score += 1.0
            }
            Goal.BUILD_MUSCLE -> {
                if (ex.compound) { score += 3.5; reasons += "Ejercicio base para ganar masa muscular." }
                if (!ex.cardio) score += 1.0
                if (ex.heavyLoad) { score += 0.5; reasons += "Permite progresar con más peso." }
                if (ex.cardio) score -= 1.5
            }
            Goal.ENDURANCE -> {
                if (ex.cardio) { score += 3.0; reasons += "Mejora tu resistencia cardiovascular." }
                if (ex.impact != Impact.HIGH && !ex.heavyLoad && ex.compound) score += 1.5
                if (ex.timed) { score += 1.0; reasons += "Aguanta más tiempo bajo tensión." }
            }
            Goal.MAINTAIN, Goal.GENERAL_HEALTH -> {
                if (ex.compound) { score += 2.0; reasons += "Un buen ejercicio completo para mantenerte fuerte." }
                if (ex.cardio) score += 1.0
                if (ex.balance) score += 1.0
            }
        }

        val ageBonus = AgeGuidance.ageBonus(ex, age)
        if (ageBonus > 0) {
            score += ageBonus
            reasons += when (AgeBand.of(age)) {
                AgeBand.TEEN -> "Perfecto para aprender técnica; la cámara cuenta tus repeticiones."
                AgeBand.SENIOR, AgeBand.MATURE -> if (ex.balance) "Mejora tu equilibrio y previene caídas." else "Cuida tus articulaciones."
                else -> "Amable con tus articulaciones."
            }
        }

        if (ex.tracked != null) { score += 0.75; reasons += "La cámara cuenta tus repeticiones y puedes usarlo contra jefes." }
        // Beginners progress faster on simpler, stable movements.
        if (p.experience == Experience.BEGINNER && ex.compound && ex.needs == setOf(Equipment.BODYWEIGHT)) score += 0.5
        if (p.experience == Experience.ADVANCED && ex.minLevel == Experience.ADVANCED) score += 0.5

        return Recommendation(ex, score, reasons.distinct().take(3))
    }
}
