package app.gymquest.core.fitness

enum class AgeBand(val label: String, val range: IntRange) {
    TEEN("13–17 años", 13..17),
    YOUNG_ADULT("18–29 años", 18..29),
    ADULT("30–39 años", 30..39),
    MIDLIFE("40–49 años", 40..49),
    MATURE("50–64 años", 50..64),
    SENIOR("65 años o más", 65..120);

    companion object {
        fun of(age: Int): AgeBand = entries.first { age in it.range || (it == TEEN && age < 13) }
    }
}

/** Weekly activity targets (WHO 2020 physical activity guidelines, simplified). */
data class WeeklyTargets(
    val moderateMinutes: Int,
    val strengthDays: Int,
    val balanceDays: Int,
    val note: String,
)

data class AgeAdvice(
    val band: AgeBand,
    val headline: String,
    val focus: List<String>,
    val warmupMinutes: Int,
    val maxHardDaysPerWeek: Int,
    val weekly: WeeklyTargets,
    val cautions: List<String>,
    val maxHeartRate: Int,
)

/**
 * What changes with age: which exercises to favour, which to avoid, how long to warm up and how
 * hard to push. General guidance for healthy people, not medical advice.
 */
object AgeGuidance {
    /** The app is for 13+; younger users should not set calorie or weight goals. */
    const val MIN_AGE = 13

    fun advice(age: Int): AgeAdvice {
        val band = AgeBand.of(age)
        val hr = BodyMetrics.maxHeartRate(age.coerceAtLeast(MIN_AGE))
        return when (band) {
            AgeBand.TEEN -> AgeAdvice(
                band, "Aprende la técnica y diviértete: tu cuerpo todavía está creciendo.",
                listOf("Ejercicios con tu propio peso", "Deportes y juegos", "Técnica antes que carga"),
                warmupMinutes = 8, maxHardDaysPerWeek = 5,
                weekly = WeeklyTargets(420, 3, 0, "Se recomienda al menos 60 minutos de actividad al día, con fuerza 3 días por semana."),
                cautions = listOf(
                    "Evita levantar el máximo posible (pruebas de 1RM) y las barras pesadas sin un adulto o entrenador que te supervise.",
                    "No hagas dietas de déficit calórico: come suficiente para crecer.",
                ),
                maxHeartRate = hr,
            )
            AgeBand.YOUNG_ADULT -> AgeAdvice(
                band, "Es el mejor momento para construir fuerza y masa muscular.",
                listOf("Levantamientos compuestos", "Cardio variado", "Constancia"),
                warmupMinutes = 5, maxHardDaysPerWeek = 6,
                weekly = WeeklyTargets(150, 2, 0, "Mínimo 150 minutos moderados (o 75 intensos) y fuerza 2 días; más es mejor."),
                cautions = listOf("Sube las cargas poco a poco: la técnica es lo que previene lesiones."),
                maxHeartRate = hr,
            )
            AgeBand.ADULT -> AgeAdvice(
                band, "Mantén y construye músculo; la fuerza protege tu metabolismo.",
                listOf("Fuerza 3 días", "Cardio moderado", "Movilidad de cadera y hombros"),
                warmupMinutes = 6, maxHardDaysPerWeek = 6,
                weekly = WeeklyTargets(150, 2, 0, "Mínimo 150 minutos moderados y fuerza 2 días por semana."),
                cautions = listOf("Duerme 7–9 horas: la recuperación pesa más que a los 20."),
                maxHeartRate = hr,
            )
            AgeBand.MIDLIFE -> AgeAdvice(
                band, "Fuerza y movilidad: calienta más y recupérate mejor.",
                listOf("Fuerza con buena técnica", "Movilidad", "Cardio de bajo a moderado impacto"),
                warmupMinutes = 10, maxHardDaysPerWeek = 5,
                weekly = WeeklyTargets(150, 2, 0, "150 minutos moderados y fuerza 2 días; añade estiramientos."),
                cautions = listOf(
                    "Calienta 10 minutos antes de entrenar fuerte.",
                    "Deja 48 horas entre sesiones intensas del mismo músculo.",
                ),
                maxHeartRate = hr,
            )
            AgeBand.MATURE -> AgeAdvice(
                band, "Conserva músculo y huesos con fuerza y bajo impacto.",
                listOf("Fuerza para piernas y espalda", "Equilibrio", "Caminata rápida o bici"),
                warmupMinutes = 10, maxHardDaysPerWeek = 4,
                weekly = WeeklyTargets(150, 2, 2, "150 minutos moderados, fuerza 2 días y equilibrio 2 días."),
                cautions = listOf(
                    "Prefiere ejercicios de bajo impacto; evita saltos repetidos si te duelen las rodillas.",
                    "Consulta a tu médico si tienes presión alta, problemas de corazón o articulares.",
                ),
                maxHeartRate = hr,
            )
            AgeBand.SENIOR -> AgeAdvice(
                band, "Fuerza de piernas y equilibrio para mantener tu independencia.",
                listOf("Levantarte de una silla", "Equilibrio", "Caminatas diarias"),
                warmupMinutes = 10, maxHardDaysPerWeek = 3,
                weekly = WeeklyTargets(150, 2, 3, "150 minutos de actividad suave, fuerza 2 días y equilibrio 3 días para prevenir caídas."),
                cautions = listOf(
                    "Haz los ejercicios de equilibrio cerca de una pared o silla firme.",
                    "Consulta a tu médico antes de empezar o subir la intensidad.",
                    "Detente si sientes mareo, dolor en el pecho o falta de aire.",
                ),
                maxHeartRate = hr,
            )
        }
    }

    /** Whether an exercise is a sensible default for someone this old. */
    fun allows(ex: GymExercise, age: Int, experience: Experience): Boolean {
        val band = AgeBand.of(age)
        return when (band) {
            AgeBand.TEEN -> !ex.heavyLoad
            AgeBand.MATURE -> ex.impact != Impact.HIGH || experience == Experience.ADVANCED
            AgeBand.SENIOR -> ex.impact == Impact.LOW && !ex.heavyLoad && ex.id !in setOf("pike_pushup", "bulgarian", "diamond_pushup", "superman")
            else -> true
        }
    }

    /** Extra weight given to exercises that help most at this age (higher = recommended sooner). */
    fun ageBonus(ex: GymExercise, age: Int): Double = when (AgeBand.of(age)) {
        AgeBand.TEEN -> if (ex.tracked != null) 1.0 else 0.0
        AgeBand.MIDLIFE -> if (ex.impact == Impact.LOW) 0.5 else 0.0
        AgeBand.MATURE -> (if (ex.balance) 1.0 else 0.0) + (if (ex.impact == Impact.LOW) 0.5 else 0.0)
        AgeBand.SENIOR -> (if (ex.balance) 2.0 else 0.0) + (if (ex.compound) 1.0 else 0.0)
        else -> 0.0
    }
}
