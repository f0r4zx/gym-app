package app.gymquest.core.wellness

import app.gymquest.core.fitness.Goal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class WeightEntry(val date: LocalDate, val kg: Double)

enum class TrendStatus { INSUFFICIENT_DATA, ON_TRACK, TOO_FAST, TOO_SLOW, WRONG_DIRECTION }

data class TrendAssessment(val status: TrendStatus, val weeklyRateKg: Double?, val message: String)

/** Weight naturally bounces ±1 kg a day with water and food, so look at the trend, not the scale. */
object WeightTrend {

    /** Exponentially smoothed weight per entry (alpha 0.25), oldest first. */
    fun smoothed(entries: List<WeightEntry>): List<WeightEntry> {
        val sorted = entries.sortedBy { it.date }
        var ema = Double.NaN
        return sorted.map { e ->
            ema = if (ema.isNaN()) e.kg else ema + 0.25 * (e.kg - ema)
            WeightEntry(e.date, ema)
        }
    }

    /**
     * Least-squares slope of the last [windowDays] days, in kg per week. Needs at least 3 entries
     * spanning a week or more.
     */
    fun weeklyRateKg(entries: List<WeightEntry>, windowDays: Int = 28): Double? {
        if (entries.isEmpty()) return null
        val latest = entries.maxOf { it.date }
        val window = entries.filter { ChronoUnit.DAYS.between(it.date, latest) <= windowDays }.sortedBy { it.date }
        if (window.size < 3) return null
        val x0 = window.first().date
        if (ChronoUnit.DAYS.between(x0, window.last().date) < 7) return null

        val xs = window.map { ChronoUnit.DAYS.between(x0, it.date).toDouble() }
        val ys = window.map { it.kg }
        val mx = xs.average()
        val my = ys.average()
        val varX = xs.sumOf { (it - mx) * (it - mx) }
        if (varX == 0.0) return null
        val slopePerDay = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / varX
        return slopePerDay * 7.0
    }

    fun assess(entries: List<WeightEntry>, goal: Goal): TrendAssessment {
        val rate = weeklyRateKg(entries)
            ?: return TrendAssessment(TrendStatus.INSUFFICIENT_DATA, null, "Registra tu peso unas cuantas veces más (durante al menos una semana) para ver tu tendencia.")
        val current = entries.maxByOrNull { it.date }!!.kg
        val pct = rate / current * 100.0 // % of body weight per week

        return when (goal) {
            Goal.LOSE_FAT -> when {
                pct > 0.1 -> TrendAssessment(TrendStatus.WRONG_DIRECTION, rate, "Tu peso sube ${fmt(rate)} kg/semana. Revisa tus porciones y tus pasos diarios.")
                pct < -1.0 -> TrendAssessment(TrendStatus.TOO_FAST, rate, "Bajas ${fmt(-rate)} kg/semana: demasiado rápido, podrías perder músculo. Come un poco más.")
                pct > -0.2 -> TrendAssessment(TrendStatus.TOO_SLOW, rate, "Vas muy lento (${fmt(-rate)} kg/semana). Un pequeño ajuste en calorías o más caminatas ayuda.")
                else -> TrendAssessment(TrendStatus.ON_TRACK, rate, "¡Vas muy bien! Bajas ${fmt(-rate)} kg/semana, un ritmo sano.")
            }
            Goal.BUILD_MUSCLE -> when {
                pct < -0.1 -> TrendAssessment(TrendStatus.WRONG_DIRECTION, rate, "Estás perdiendo peso (${fmt(rate)} kg/semana). Para ganar volumen necesitas comer más.")
                pct > 0.6 -> TrendAssessment(TrendStatus.TOO_FAST, rate, "Subes ${fmt(rate)} kg/semana: parte será grasa. Baja un poco las calorías.")
                pct < 0.05 -> TrendAssessment(TrendStatus.TOO_SLOW, rate, "Tu peso casi no sube. Agrega unas 150–250 kcal al día.")
                else -> TrendAssessment(TrendStatus.ON_TRACK, rate, "¡Buen ritmo! Subes ${fmt(rate)} kg/semana.")
            }
            else -> if (abs(pct) <= 0.3) {
                TrendAssessment(TrendStatus.ON_TRACK, rate, "Tu peso está estable.")
            } else {
                TrendAssessment(TrendStatus.WRONG_DIRECTION, rate, "Tu peso cambia ${fmt(rate)} kg/semana aunque querías mantenerte.")
            }
        }
    }

    private fun fmt(x: Double) = "%.2f".format(x)
}
