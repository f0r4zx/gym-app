package app.gymquest.core.wellness

import app.gymquest.core.fitness.Goal
import app.gymquest.core.fitness.Hydration
import app.gymquest.core.fitness.NutritionTargets
import app.gymquest.core.nutrition.Macros
import kotlin.math.abs
import kotlin.math.roundToInt

/** How you (and your avatar) are doing today. */
enum class Mood(val label: String) {
    GREAT("Increíble"), GOOD("Bien"), OK("Regular"), TIRED("Cansado"), SAD("Desanimado")
}

data class DaySnapshot(
    val goal: Goal,
    val targets: NutritionTargets,
    val eaten: Macros,
    val waterMl: Int,
    val workoutsToday: Int,
    val activeMinutes: Int,
    val skincareFraction: Double,
    val hourNow: Double,
    val streakDays: Int = 0,
    val wakeHour: Int = 7,
    val sleepHour: Int = 23,
)

data class CoachReport(val mood: Mood, val score: Int, val headline: String, val tips: List<String>)

/**
 * Reads the day so far and says how you are doing in plain language. The tone is encouraging:
 * it points out what to do next instead of scolding.
 */
object DailyCoach {

    fun report(s: DaySnapshot): CoachReport {
        val dayFraction = ((s.hourNow - s.wakeHour) / (s.sleepHour - s.wakeHour).toDouble()).coerceIn(0.0, 1.0)
        val tips = mutableListOf<String>()

        // ---- Food (30 pts) and protein (15 pts)
        val kcalScore = foodScore(s, dayFraction)
        val proteinFraction = (s.eaten.proteinG / s.targets.proteinG).coerceIn(0.0, 1.0)
        val proteinScore = if (dayFraction < 0.25) 1.0 else (proteinFraction / dayFraction.coerceAtLeast(0.3)).coerceIn(0.0, 1.0)

        // ---- Water (20 pts)
        val waterTarget = s.targets.waterMl
        val pace = Hydration.pace(s.waterMl, waterTarget, s.hourNow, s.wakeHour, s.sleepHour)
        val waterScore = when (pace) {
            Hydration.Pace.AHEAD -> 1.0
            Hydration.Pace.ON_TRACK -> 0.8
            Hydration.Pace.BEHIND -> (s.waterMl.toDouble() / (waterTarget * dayFraction.coerceAtLeast(0.2))).coerceIn(0.0, 0.7)
        }

        // ---- Movement (25 pts) and skin (10 pts)
        val moveScore = when {
            s.workoutsToday > 0 || s.activeMinutes >= 30 -> 1.0
            s.activeMinutes > 0 -> s.activeMinutes / 30.0
            dayFraction < 0.5 -> 0.7 // the day is young
            else -> 0.0
        }
        val skinScore = if (dayFraction < 0.3) 1.0 else s.skincareFraction.coerceIn(0.0, 1.0)

        val score = (kcalScore * 30 + proteinScore * 15 + waterScore * 20 + moveScore * 25 + skinScore * 10).roundToInt().coerceIn(0, 100)

        // ---- Tips, most useful first
        val glassesLeft = ((waterTarget - s.waterMl) / Hydration.GLASS_ML.toDouble()).coerceAtLeast(0.0).roundToInt()
        if (pace == Hydration.Pace.BEHIND && glassesLeft > 0) tips += "Vas atrasado con el agua: toma un vaso ahora (te faltan $glassesLeft)."
        val proteinLeft = (s.targets.proteinG - s.eaten.proteinG).roundToInt()
        if (proteinLeft > 25 && dayFraction > 0.4) tips += "Te faltan $proteinLeft g de proteína: pollo, huevo, yogur griego o atún te ayudan."
        if (s.eaten.kcal > s.targets.kcal * 1.1) {
            tips += if (s.goal == Goal.LOSE_FAT) "Ya pasaste tu meta de calorías de hoy. Sin drama: mañana retomas, y una caminata ligera ayuda."
            else "Ya superaste tu meta de calorías de hoy; elige algo ligero para cerrar el día."
        } else if (dayFraction > 0.85 && s.eaten.kcal < s.targets.kcal * 0.7) {
            tips += "Hoy comiste poco para tu meta. Una comida completa te ayudará a recuperar y rendir mañana."
        }
        if (s.workoutsToday == 0 && s.activeMinutes < 20 && dayFraction in 0.4..0.95) {
            tips += "Aún no te mueves hoy: 20 minutos de caminata o una serie rápida contra un jefe bastan."
        }
        if (s.skincareFraction < 1.0 && dayFraction > 0.8) tips += "No olvides tu rutina de skincare de la noche."
        if (s.streakDays >= 3) tips += "Llevas ${s.streakDays} días seguidos. ¡No rompas la racha!"
        if (tips.isEmpty()) tips += "Todo va en orden. Sigue así."

        val mood = when {
            score >= 80 -> Mood.GREAT
            score >= 60 -> Mood.GOOD
            score >= 40 -> Mood.OK
            score >= 20 -> Mood.TIRED
            else -> Mood.SAD
        }
        val headline = when (mood) {
            Mood.GREAT -> "¡Vas increíble hoy!"
            Mood.GOOD -> "Buen día, vas bien."
            Mood.OK -> "Vas a medias: todavía puedes cerrar fuerte."
            Mood.TIRED -> "Hoy ha sido flojo, pero aún hay tiempo."
            Mood.SAD -> "Empecemos con un paso pequeño."
        }
        return CoachReport(mood, score, headline, tips.take(4))
    }

    private fun foodScore(s: DaySnapshot, dayFraction: Double): Double {
        if (s.targets.kcal <= 0) return 1.0
        if (dayFraction < 0.2 && s.eaten.kcal == 0.0) return 0.8
        val expected = s.targets.kcal * dayFraction.coerceAtLeast(0.15)
        val ratio = s.eaten.kcal / expected
        // Eating a bit ahead of the clock is fine; far behind or far over is not.
        val over = (s.eaten.kcal / s.targets.kcal - 1.0).coerceAtLeast(0.0)
        val dev = if (ratio >= 1.0) abs(ratio - 1.0) * 0.5 else abs(1.0 - ratio)
        return (1.0 - dev * 1.2 - over * 2.0).coerceIn(0.0, 1.0)
    }
}
