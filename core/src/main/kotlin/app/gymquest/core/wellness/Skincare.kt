package app.gymquest.core.wellness

import java.time.LocalDate

enum class SkinType(val label: String) {
    NORMAL("Normal"), OILY("Grasa"), DRY("Seca"), COMBINATION("Mixta"), SENSITIVE("Sensible")
}

enum class DayPart(val label: String) { MORNING("Mañana"), EVENING("Noche") }

data class SkincareStep(
    val id: String,
    val name: String,
    val part: DayPart,
    val description: String,
    val optional: Boolean = false,
    /** Only done on some days of the week (exfoliation, masks). */
    val weekly: Boolean = false,
)

object SkincareRoutines {
    fun forSkin(type: SkinType, acneProne: Boolean = false): List<SkincareStep> {
        val cleanser = when (type) {
            SkinType.OILY -> "Limpiador en gel"
            SkinType.DRY, SkinType.SENSITIVE -> "Limpiador suave sin fragancia"
            else -> "Limpiador facial"
        }
        val moisturizer = when (type) {
            SkinType.OILY -> "Hidratante ligera en gel"
            SkinType.DRY -> "Crema hidratante rica"
            else -> "Hidratante"
        }
        val steps = mutableListOf(
            SkincareStep("am_wash", "Lavar la cara", DayPart.MORNING, "$cleanser, agua tibia, 30 segundos."),
            SkincareStep("am_moisturizer", "Hidratar", DayPart.MORNING, "$moisturizer."),
            SkincareStep("am_spf", "Protector solar", DayPart.MORNING, "SPF 30 o más, aunque esté nublado. Reaplica cada 2–3 horas al sol."),
            SkincareStep("pm_wash", "Lavar la cara", DayPart.EVENING, "$cleanser. Quita el protector solar y el sudor."),
        )
        if (acneProne) {
            steps += SkincareStep("pm_treatment", "Tratamiento para acné", DayPart.EVENING, "Ácido salicílico o peróxido de benzoilo en las zonas con brotes.", optional = true)
        } else if (type != SkinType.SENSITIVE) {
            steps += SkincareStep("pm_treatment", "Sérum o retinol", DayPart.EVENING, "Empieza 2 noches por semana si usas retinol.", optional = true)
        }
        steps += SkincareStep("pm_moisturizer", "Hidratar", DayPart.EVENING, "$moisturizer.")
        steps += SkincareStep("pm_lips", "Bálsamo de labios", DayPart.EVENING, "Labios hidratados.", optional = true)
        if (type != SkinType.SENSITIVE) {
            steps += SkincareStep("wk_exfoliate", "Exfoliar", DayPart.EVENING, "1–2 veces por semana, sin tallar.", optional = true, weekly = true)
        }
        steps += SkincareStep("wk_mask", "Mascarilla", DayPart.EVENING, "Una vez por semana si quieres.", optional = true, weekly = true)
        return steps
    }
}

/** One day of skincare tracking. Immutable: every action returns the updated day. */
data class SkincareDay(
    val date: LocalDate,
    val completed: Set<String> = emptySet(),
    val faceWashes: Int = 0,
) {
    fun toggle(stepId: String): SkincareDay =
        copy(completed = if (stepId in completed) completed - stepId else completed + stepId)

    fun washFace(): SkincareDay = copy(faceWashes = faceWashes + 1)

    /** Share of the daily (non-optional, non-weekly) steps that are done. */
    fun fraction(routine: List<SkincareStep>): Double {
        val required = routine.filter { !it.optional && !it.weekly }
        if (required.isEmpty()) return 1.0
        return required.count { it.id in completed }.toDouble() / required.size
    }

    fun isComplete(routine: List<SkincareStep>): Boolean = fraction(routine) >= 1.0
}

object SkincareStats {
    /** Consecutive complete days ending today (or yesterday, if today is not done yet). */
    fun streak(days: List<SkincareDay>, routine: List<SkincareStep>, today: LocalDate): Int {
        val done = days.filter { it.isComplete(routine) }.map { it.date }.toSet()
        var cursor = if (today in done) today else today.minusDays(1)
        var n = 0
        while (cursor in done) {
            n++
            cursor = cursor.minusDays(1)
        }
        return n
    }

    fun washAdvice(washes: Int): String = when {
        washes == 0 -> "Aún no te lavas la cara hoy."
        washes <= 2 -> "Perfecto: $washes ${if (washes == 1) "lavada" else "lavadas"} hoy."
        washes == 3 -> "Ya van 3 lavadas. Si fue por el sudor del gimnasio, solo enjuaga con agua."
        else -> "Demasiadas lavadas ($washes): resecan la piel y empeoran el brillo y el acné. Mejor solo agua."
    }
}
