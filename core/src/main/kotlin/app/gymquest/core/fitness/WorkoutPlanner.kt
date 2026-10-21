package app.gymquest.core.fitness

import app.gymquest.core.exercise.MuscleGroup
import java.time.LocalDate

data class PlanItem(
    val exercise: GymExercise,
    val sets: Int,
    /** Rep range, or seconds when the exercise is timed. */
    val minReps: Int,
    val maxReps: Int,
    val restSeconds: Int,
)

data class PlanDay(
    val dayNumber: Int,
    val title: String,
    val focus: String,
    val items: List<PlanItem>,
    /** Suggested outdoor cardio for the day (walk, run, ride), if any. */
    val cardioNote: String? = null,
) {
    val isRest: Boolean get() = items.isEmpty() && cardioNote == null
}

data class WeeklyPlan(val goal: Goal, val days: List<PlanDay>, val notes: List<String>)

/**
 * Builds a weekly routine from goal, schedule, experience and the equipment you actually have.
 * Deterministic: [rotation] swaps in alternative exercises from week to week for variety.
 */
object WorkoutPlanner {

    private enum class Slot(val muscle: MuscleGroup, val compound: Boolean?) {
        CHEST_C(MuscleGroup.CHEST, true), CHEST_I(MuscleGroup.CHEST, false),
        BACK_C(MuscleGroup.BACK, true), BACK_C2(MuscleGroup.BACK, true),
        SHOULDER_C(MuscleGroup.SHOULDERS, true), SHOULDER_I(MuscleGroup.SHOULDERS, false),
        BICEPS(MuscleGroup.BICEPS, null), TRICEPS(MuscleGroup.TRICEPS, null),
        QUAD_C(MuscleGroup.QUADS, true), QUAD_C2(MuscleGroup.QUADS, true),
        HAM(MuscleGroup.HAMSTRINGS, null), GLUTE(MuscleGroup.GLUTES, null), CALF(MuscleGroup.CALVES, null),
        CORE(MuscleGroup.CORE, null), CORE2(MuscleGroup.CORE, null),
    }

    private class Template(val title: String, val focus: String, val slots: List<Slot>)

    private val fullBody = Template(
        "Cuerpo completo", "Todo el cuerpo",
        listOf(Slot.QUAD_C, Slot.CHEST_C, Slot.BACK_C, Slot.SHOULDER_C, Slot.HAM, Slot.CORE),
    )
    private val upper = Template(
        "Tren superior", "Pecho, espalda, hombros y brazos",
        listOf(Slot.CHEST_C, Slot.BACK_C, Slot.SHOULDER_C, Slot.BACK_C2, Slot.BICEPS, Slot.TRICEPS),
    )
    private val lower = Template(
        "Tren inferior", "Piernas, glúteos y abdomen",
        listOf(Slot.QUAD_C, Slot.HAM, Slot.QUAD_C2, Slot.GLUTE, Slot.CALF, Slot.CORE),
    )
    private val push = Template(
        "Empuje", "Pecho, hombros y tríceps",
        listOf(Slot.CHEST_C, Slot.SHOULDER_C, Slot.CHEST_I, Slot.SHOULDER_I, Slot.TRICEPS),
    )
    private val pull = Template(
        "Tirón", "Espalda y bíceps",
        listOf(Slot.BACK_C, Slot.BACK_C2, Slot.SHOULDER_I, Slot.BICEPS, Slot.CORE),
    )
    private val legs = Template(
        "Piernas", "Piernas, glúteos y pantorrillas",
        listOf(Slot.QUAD_C, Slot.HAM, Slot.QUAD_C2, Slot.GLUTE, Slot.CALF, Slot.CORE2),
    )

    private fun layout(days: Int): List<Template?> = when (days) {
        1 -> listOf(fullBody, null, null, null, null, null, null)
        2 -> listOf(fullBody, null, null, fullBody, null, null, null)
        3 -> listOf(fullBody, null, fullBody, null, fullBody, null, null)
        4 -> listOf(upper, lower, null, upper, lower, null, null)
        5 -> listOf(push, pull, legs, null, upper, lower, null)
        6 -> listOf(push, pull, legs, push, pull, legs, null)
        else -> listOf(push, pull, legs, push, pull, legs, null)
    }

    fun plan(profile: UserProfile, rotation: Int = 0, today: LocalDate = LocalDate.now()): WeeklyPlan {
        val age = profile.ageOn(today)
        val advice = AgeGuidance.advice(age)
        val pool = GymCatalog.available(profile.equipment, profile.experience)
            .filter { AgeGuidance.allows(it, age, profile.experience) }
            .ifEmpty { GymCatalog.available(setOf(Equipment.BODYWEIGHT), Experience.BEGINNER).filter { it.impact == Impact.LOW } }
        // Older lifters recover more slowly: cap the number of hard days.
        val days = minOf(profile.daysPerWeek, advice.maxHardDaysPerWeek)
        val templates = layout(days)
        val planDays = templates.mapIndexed { index, template ->
            if (template == null) {
                PlanDay(index + 1, "Descanso", "Camina, estira o haz movilidad", emptyList(), restDayCardio(profile.goal))
            } else {
                buildDay(index + 1, template, pool, profile, rotation + index)
            }
        }
        return WeeklyPlan(profile.goal, planDays, notes(profile, advice, days))
    }

    private fun buildDay(number: Int, t: Template, pool: List<GymExercise>, profile: UserProfile, rotation: Int): PlanDay {
        val used = mutableSetOf<String>()
        val items = t.slots.mapNotNull { slot ->
            val candidates = pool.filter { ex ->
                ex.primary == slot.muscle && !ex.cardio && ex.id !in used &&
                    (slot.compound == null || ex.compound == slot.compound || (slot.compound && ex.compound))
            }.ifEmpty {
                // Fall back to anything for that muscle, then to any other compound lift.
                pool.filter { it.primary == slot.muscle && !it.cardio && it.id !in used }
            }
            if (candidates.isEmpty()) return@mapNotNull null
            val chosen = candidates[(rotation + slot.ordinal) % candidates.size]
            used += chosen.id
            scheme(chosen, profile.goal)
        }
        val finisher = finisher(profile, pool, used)
        return PlanDay(number, t.title, t.focus, items + listOfNotNull(finisher), cardioNote(profile.goal))
    }

    private fun scheme(ex: GymExercise, goal: Goal): PlanItem {
        if (ex.timed) {
            return PlanItem(ex, sets = 3, minReps = 30, maxReps = 60, restSeconds = 45)
        }
        return when (goal) {
            Goal.BUILD_MUSCLE ->
                if (ex.compound) PlanItem(ex, 4, 6, 10, 120) else PlanItem(ex, 3, 10, 15, 75)
            Goal.LOSE_FAT ->
                if (ex.compound) PlanItem(ex, 3, 10, 12, 60) else PlanItem(ex, 3, 12, 15, 45)
            Goal.ENDURANCE -> PlanItem(ex, 3, 15, 20, 45)
            Goal.MAINTAIN, Goal.GENERAL_HEALTH ->
                if (ex.compound) PlanItem(ex, 3, 8, 12, 90) else PlanItem(ex, 2, 10, 15, 60)
        }
    }

    /** Fat-loss and endurance days end with a short conditioning block. */
    private fun finisher(profile: UserProfile, pool: List<GymExercise>, used: Set<String>): PlanItem? {
        if (profile.goal != Goal.LOSE_FAT && profile.goal != Goal.ENDURANCE) return null
        val cardio = pool.firstOrNull { it.cardio && it.id !in used && it.id != "mountain_climber" } ?: return null
        val seconds = if (cardio.timed) 60 else 40
        return PlanItem(cardio, sets = 3, minReps = seconds / 2, maxReps = seconds, restSeconds = 30)
    }

    private fun cardioNote(goal: Goal): String? = when (goal) {
        Goal.LOSE_FAT -> "Después de pesas: 15–20 min de caminata rápida o bici suave."
        Goal.ENDURANCE -> "Después de pesas: 10 min de trote suave para soltar."
        else -> null
    }

    private fun restDayCardio(goal: Goal): String = when (goal) {
        Goal.LOSE_FAT -> "Camina 30–45 min a paso ligero (registra la caminata con GPS)."
        Goal.ENDURANCE -> "Sal a correr suave 30–40 min o pedalea 45–60 min."
        Goal.BUILD_MUSCLE -> "Descansa, duerme bien y camina 20 min."
        else -> "Camina 30 min o haz movilidad y estiramientos."
    }

    private fun notes(profile: UserProfile, advice: AgeAdvice, trainingDays: Int): List<String> {
        val n = mutableListOf<String>()
        n += "Calienta ${advice.warmupMinutes} min antes de cada sesión y estira al terminar."
        if (trainingDays < profile.daysPerWeek) {
            n += "Por tu edad te recomendamos máximo $trainingDays días intensos por semana; usa los demás para caminar y estirar."
        }
        n += advice.cautions
        if (profile.experience == Experience.BEGINNER) n += "Empieza con poco peso y aprende la técnica; sube la carga poco a poco."
        if (profile.goal == Goal.ENDURANCE) n += "Agrega 2 salidas de cardio por semana con el GPS: una larga y suave y otra con intervalos."
        if (Equipment.BODYWEIGHT in profile.equipment && profile.equipment.size == 1) {
            n += "Sin equipo: usa la cámara para contar tus repeticiones y pelear contra jefes."
        }
        return n
    }
}
