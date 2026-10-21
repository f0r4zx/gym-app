package app.gymquest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.wellness.WeightEntry
import app.gymquest.core.wellness.WeightTrend
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.ui.Controller
import app.gymquest.ui.components.BarChart
import app.gymquest.ui.components.BigStat
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.LineChart
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.SectionTitle
import app.gymquest.ui.theme.Mint
import app.gymquest.ui.theme.Sky
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How you are doing over time: weight, weekly activity and your totals. */
@Composable
fun ProgressScreen(state: AppState, c: Controller, onBack: () -> Unit) {
    val today = c.today
    val streak = Derived.streak(state, today)
    val weights = remember(state.weights) { state.weights.sortedBy { it.epochDay }.map { WeightEntry(LocalDate.ofEpochDay(it.epochDay), it.kg) } }
    val smoothed = remember(weights) { WeightTrend.smoothed(weights) }

    // Active minutes per week for the last 8 weeks (week 0 = this one).
    val weekMinutes = remember(state.days, today) {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        (7 downTo 0).map { back ->
            val start = monday.minusWeeks(back.toLong())
            (0L..6L).sumOf { d -> state.day(start.plusDays(d).toEpochDay()).activeMinutes }.toDouble()
        }
    }
    val weekLabels = remember(today) { (7 downTo 0).map { if (it == 0) "Hoy" else "-$it" } }
    val reps = state.game.repsByExercise.entries.sortedByDescending { it.value }.take(6)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader("Mi progreso", onBack)

        GqCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigStat("${streak.current}", "días de racha")
                BigStat("${state.workouts.size}", "entrenos")
                BigStat("${"%.0f".format(state.game.totalKm)}", "km")
                BigStat("${state.game.repsByExercise.values.sum()}", "repeticiones")
            }
        }

        SectionTitle("Peso")
        GqCard {
            if (weights.size < 2) {
                Muted("Registra tu peso en Vida → Peso al menos dos veces para ver tu tendencia.")
            } else {
                LineChart(weights.map { it.kg }, smooth = smoothed.map { it.kg }, color = Sky)
                val first = weights.first()
                val last = weights.last()
                val days = ChronoUnit.DAYS.between(first.date, last.date).coerceAtLeast(1)
                Muted("${"%.1f".format(first.kg)} kg → ${"%.1f".format(last.kg)} kg en $days días (la línea fuerte quita el ruido del día a día).")
            }
        }

        SectionTitle("Actividad por semana (minutos)")
        GqCard {
            BarChart(weekMinutes, weekLabels, color = Mint)
            Muted("Esta semana: ${weekMinutes.last().toInt()} min · la anterior: ${weekMinutes.getOrElse(weekMinutes.size - 2) { 0.0 }.toInt()} min")
        }

        SectionTitle("Tus ejercicios favoritos")
        GqCard {
            if (reps.isEmpty()) Muted("Aún no hay repeticiones. ¡Pelea con un jefe!")
            reps.forEach { (id, count) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ExerciseType.fromId(id)?.displayName ?: id)
                    Text("$count", fontWeight = FontWeight.Bold)
                }
            }
        }
        Muted("Jefes vencidos: ${state.game.bosses.size} · logros: ${state.game.achievements.size} · mejor racha: ${streak.longest} días")
    }
}
