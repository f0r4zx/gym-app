package app.gymquest.core.fitness

import kotlin.math.floor
import kotlin.math.roundToInt

data class SetLog(val reps: Int, val weightKg: Double, val rpe: Double? = null) {
    val volume: Double get() = reps * weightKg
}

/** Estimated one-rep max and training percentages. */
object OneRepMax {
    /** Epley below 10 reps, Brzycki above (it overshoots less for high reps). */
    fun estimate(weightKg: Double, reps: Int): Double {
        if (reps <= 0 || weightKg <= 0) return 0.0
        if (reps == 1) return weightKg
        return if (reps <= 10) weightKg * (1 + reps / 30.0) else weightKg * 36.0 / (37.0 - reps.coerceAtMost(36))
    }

    fun weightForReps(oneRepMax: Double, reps: Int): Double {
        if (reps <= 1) return oneRepMax
        return if (reps <= 10) oneRepMax / (1 + reps / 30.0) else oneRepMax * (37.0 - reps.coerceAtMost(36)) / 36.0
    }

    fun percentTable(oneRepMax: Double): List<Pair<Int, Double>> =
        listOf(100, 95, 90, 85, 80, 75, 70, 65, 60, 50).map { it to oneRepMax * it / 100.0 }
}

object PlateCalculator {
    val DEFAULT_PLATES_KG = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)

    /**
     * Plates to load on each side of the bar, heaviest first. Returns the closest achievable
     * total in [achievedKg] when the target cannot be hit exactly with the plates available.
     */
    fun perSide(targetKg: Double, barKg: Double = 20.0, plates: List<Double> = DEFAULT_PLATES_KG): PlateLoad {
        var remaining = ((targetKg - barKg) / 2.0).coerceAtLeast(0.0)
        val used = mutableListOf<Double>()
        for (p in plates.sortedDescending()) {
            while (remaining + 1e-9 >= p) {
                used += p
                remaining -= p
            }
        }
        return PlateLoad(used, barKg + used.sum() * 2)
    }

    data class PlateLoad(val platesPerSide: List<Double>, val achievedKg: Double)
}

enum class OverloadAction { ADD_WEIGHT, ADD_REPS, HOLD, DELOAD }

data class OverloadSuggestion(val action: OverloadAction, val weightKg: Double, val reps: Int, val reason: String)

/** Double progression: fill the rep range, then add weight and drop back to the bottom of the range. */
object OverloadAdvisor {
    fun next(last: List<SetLog>, minReps: Int, maxReps: Int, incrementKg: Double = 2.5): OverloadSuggestion? {
        if (last.isEmpty()) return null
        val weight = last.maxOf { it.weightKg }
        val atWeight = last.filter { it.weightKg == weight }
        val worst = atWeight.minOf { it.reps }
        val avgRpe = last.mapNotNull { it.rpe }.takeIf { it.isNotEmpty() }?.average()

        return when {
            worst < minReps - 2 || (avgRpe != null && avgRpe >= 9.8 && worst < minReps) ->
                OverloadSuggestion(OverloadAction.DELOAD, roundToPlate(weight * 0.9, incrementKg), minReps, "Te costó mucho: baja el peso 10% y recupera la técnica.")
            worst >= maxReps && (avgRpe == null || avgRpe <= 9.0) ->
                OverloadSuggestion(OverloadAction.ADD_WEIGHT, weight + incrementKg, minReps, "Completaste todas las series arriba del rango: sube $incrementKg kg.")
            worst >= minReps ->
                OverloadSuggestion(OverloadAction.ADD_REPS, weight, worst + 1, "Mismo peso, intenta una repetición más en cada serie.")
            else ->
                OverloadSuggestion(OverloadAction.HOLD, weight, minReps, "Mantén el peso hasta llegar al mínimo de $minReps repeticiones en todas las series.")
        }
    }

    private fun roundToPlate(kg: Double, step: Double): Double = (kg / step).roundToInt() * step
}

/** Tracks best sets per exercise so the app can celebrate new records. */
object PersonalRecords {
    data class Record(val weightKg: Double, val reps: Int, val estimated1RM: Double)

    /** The best set by estimated 1RM, or null for bodyweight/no data. */
    fun best(sets: List<SetLog>): Record? =
        sets.filter { it.weightKg > 0 && it.reps > 0 }
            .maxByOrNull { OneRepMax.estimate(it.weightKg, it.reps) }
            ?.let { Record(it.weightKg, it.reps, OneRepMax.estimate(it.weightKg, it.reps)) }

    fun isNewRecord(history: List<SetLog>, candidate: SetLog): Boolean {
        val previous = best(history)?.estimated1RM ?: return candidate.weightKg > 0
        return OneRepMax.estimate(candidate.weightKg, candidate.reps) > previous + 1e-9
    }

    fun volume(sets: List<SetLog>): Double = floor(sets.sumOf { it.volume })
}
