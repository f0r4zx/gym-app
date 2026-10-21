package app.gymquest.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.sharing.DiaryActivity
import app.gymquest.core.sharing.DiaryCalendar
import app.gymquest.core.sharing.DiaryEntry
import app.gymquest.core.sharing.DiaryWorkout
import app.gymquest.core.tracking.ActivityType
import app.gymquest.data.AppState
import app.gymquest.data.enumOr
import app.gymquest.ui.Controller
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.theme.Mint
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private fun entriesOf(state: AppState): List<DiaryEntry> {
    val zone = ZoneId.systemDefault()
    fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val workouts = state.workouts.groupBy { dateOf(it.timeMs) }
    val activities = state.activities.groupBy { dateOf(it.timeMs) }
    val dates = workouts.keys + activities.keys + state.days.keys.map { LocalDate.ofEpochDay(it) }
    return dates.map { date ->
        val log = state.day(date.toEpochDay())
        DiaryEntry(
            date = date,
            workouts = workouts[date].orEmpty().map {
                DiaryWorkout(ExerciseType.fromId(it.exercise)?.displayName ?: it.exercise, (it.durationMs / 60_000).toInt().coerceAtLeast(1), it.calories, it.xp)
            },
            activities = activities[date].orEmpty().map {
                DiaryActivity(enumOr(it.type, ActivityType.WALK), it.distanceM / 1000.0, (it.movingMs / 60_000).toInt())
            },
            kcalEaten = log.foods.sumOf { it.kcal }.toInt().takeIf { log.foods.isNotEmpty() },
            waterMl = log.waterMl.takeIf { it > 0 },
            sleepHours = log.sleepStartMs?.let { s -> log.sleepEndMs?.let { e -> (e - s - log.sleepAwakeMs) / 3_600_000.0 } },
            weightKg = log.weightKg,
        )
    }
}

/** A month calendar of what you did, with flames on streak days. Can be shared as a short text. */
@Composable
fun DiaryScreen(state: AppState, c: Controller, onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = remember(state.workouts, state.activities, state.days) { entriesOf(state) }
    var monthOffset by rememberSaveable { mutableLongStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.from(c.today).plusMonths(monthOffset)
    val cal = remember(entries, month) { DiaryCalendar.month(month, entries) }
    val picked = selected?.let { LocalDate.parse(it) }?.let { d -> entries.firstOrNull { it.date == d } }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader("Diario", onBack)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { monthOffset-- }) { Text("‹") }
            Text(DiaryCalendar.summaryText(cal).substringBefore(":"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedButton(onClick = { monthOffset++ }, enabled = monthOffset < 0) { Text("›") }
        }
        GqCard {
            Row(Modifier.fillMaxWidth()) {
                listOf("L", "M", "M", "J", "V", "S", "D").forEach {
                    Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            cal.cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { cell ->
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                .background(if (cell.date == null) androidx.compose.ui.graphics.Color.Transparent else Mint.copy(alpha = 0.08f + cell.level * 0.22f))
                                .clickable(enabled = cell.date != null) { selected = cell.date.toString() },
                            contentAlignment = Alignment.Center,
                        ) {
                            cell.date?.let { d -> Text(if (cell.inStreak) "🔥" else d.dayOfMonth.toString(), fontSize = 13.sp) }
                        }
                    }
                    repeat(7 - week.size) { Box(Modifier.weight(1f)) }
                }
            }
            Muted(DiaryCalendar.summaryText(cal))
        }
        picked?.let { e ->
            GqCard {
                Text(e.date.toString(), fontWeight = FontWeight.Bold)
                if (!e.isActive) Muted("Día de descanso")
                e.workouts.forEach { Text("💪 ${it.title} · ${it.minutes} min · ${it.kcal} kcal") }
                e.activities.forEach { Text("🏃 ${it.type.label} · ${"%.1f".format(it.km)} km · ${it.minutes} min") }
                e.kcalEaten?.let { Muted("🍽 $it kcal") }
                e.waterMl?.let { Muted("💧 $it ml") }
                e.sleepHours?.let { Muted("😴 ${"%.1f".format(it)} h") }
            }
        }
        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "📅 " + DiaryCalendar.summaryText(cal))
                }
                context.startActivity(Intent.createChooser(send, "Compartir mi mes"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("📤 Compartir mi mes") }
        Muted("Al compartir solo se envía el resumen: nunca tu peso, comidas ni notas.")
    }
}
