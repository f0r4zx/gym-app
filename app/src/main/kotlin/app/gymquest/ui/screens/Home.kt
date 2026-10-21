package app.gymquest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.game.Progression
import app.gymquest.core.wellness.DailyCoach
import app.gymquest.core.wellness.DaySnapshot
import app.gymquest.core.wellness.Mood
import app.gymquest.core.wellness.SkincareDay
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.data.enumOr
import app.gymquest.ui.Controller
import app.gymquest.ui.Sub
import app.gymquest.ui.Tab
import app.gymquest.ui.components.Bar
import app.gymquest.ui.components.BigStat
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.Pill
import app.gymquest.ui.components.SectionTitle
import app.gymquest.ui.theme.Gold
import app.gymquest.ui.theme.Mint
import app.gymquest.ui.theme.Sky
import java.time.LocalTime

@Composable
fun HomeScreen(state: AppState, c: Controller, open: (Sub) -> Unit, goTo: (Tab) -> Unit) {
    val today = c.today
    val profile = Derived.profile(state) ?: return
    val progress = Progression.progress(state.game.xp)
    val streak = Derived.streak(state, today)
    val targets = Derived.targets(state, today)
    val day = state.day(today.toEpochDay())
    val eaten = Derived.eatenToday(state, today)
    val board = Derived.missionBoard(state, today)
    val routine = Derived.skincareRoutine(state)
    val now = LocalTime.now()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Hola, ${profile.name} 👋", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Muted(if (streak.activeToday) "Hoy ya cuenta para tu racha" else "Entrena hoy para mantener tu racha")
            }
            OutlinedButton(onClick = { open(Sub.SETTINGS) }) { Text("⚙️") }
        }

        GqCard(onClick = { open(Sub.CHARACTER) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Nivel ${progress.level} · ${progress.title}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Muted("${progress.xpIntoLevel} / ${progress.xpForNext} XP para el siguiente nivel")
                }
                Pill("🪙 ${state.game.coins}", Gold.copy(alpha = 0.25f))
            }
            Bar(progress.fraction, color = MaterialTheme.colorScheme.primary, height = 12.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("🔥 Racha ${streak.current} ${if (streak.current == 1) "día" else "días"}")
                Pill("🏅 Récord ${streak.longest}")
            }
        }

        // The coach reads the day so far and says what to do next.
        if (targets != null) {
            val report = DailyCoach.report(
                DaySnapshot(
                    goal = profile.goal, targets = targets, eaten = eaten, waterMl = day.waterMl,
                    workoutsToday = day.workouts, activeMinutes = day.activeMinutes,
                    skincareFraction = SkincareDay(today, day.skincareSteps.toSet()).fraction(routine),
                    hourNow = now.hour + now.minute / 60.0, streakDays = streak.current,
                    wakeHour = state.profile?.wakeHour ?: 7, sleepHour = (state.profile?.sleepHour ?: 23) % 24,
                ),
            )
            GqCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(moodEmoji(report.mood), style = MaterialTheme.typography.displaySmall)
                    Column(Modifier.weight(1f)) {
                        Text(report.headline, fontWeight = FontWeight.Bold)
                        Muted("Puntaje del día: ${report.score}/100")
                    }
                }
                Bar(report.score / 100.0, color = Mint)
                report.tips.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GqCard(Modifier.weight(1f), onClick = { goTo(Tab.LIFE) }) {
                    BigStat("${eaten.kcal.toInt()}", "de ${targets.kcal} kcal")
                    Bar(eaten.kcal / targets.kcal, color = MaterialTheme.colorScheme.primary, height = 6.dp)
                }
                GqCard(Modifier.weight(1f), onClick = { goTo(Tab.LIFE) }) {
                    BigStat("${"%.1f".format(day.waterMl / 1000.0)} L", "de ${targets.waterMl / 1000.0} L")
                    Bar(day.waterMl.toDouble() / targets.waterMl, color = Sky, height = 6.dp)
                }
                GqCard(Modifier.weight(1f)) {
                    BigStat("${day.activeMinutes}", "min activos")
                    Bar(day.activeMinutes / 30.0, color = Mint, height = 6.dp)
                }
            }
        }

        SectionTitle("Misiones de hoy")
        GqCard(onClick = { open(Sub.MISSIONS) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${board.completedCount} de ${board.missions.size} completadas", fontWeight = FontWeight.Bold)
                Text("Ver ›", color = MaterialTheme.colorScheme.primary)
            }
            Bar(board.completedCount.toDouble() / board.missions.size.coerceAtLeast(1), color = Gold)
            board.missions.take(3).forEach { m ->
                Text("${if (m.claimed) "✅" else if (m.complete) "🎁" else "⬜"} ${m.mission.title}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionTitle("¿Qué hacemos hoy?")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GqCard(Modifier.weight(1f), onClick = { goTo(Tab.TRAIN) }) {
                Text("⚔️", style = MaterialTheme.typography.headlineMedium)
                Text("Pelear con un jefe", fontWeight = FontWeight.Bold)
                Muted("Cuenta repeticiones con la cámara")
            }
            GqCard(Modifier.weight(1f), onClick = { goTo(Tab.MAP) }) {
                Text("🏃", style = MaterialTheme.typography.headlineMedium)
                Text("Salir a moverte", fontWeight = FontWeight.Bold)
                Muted("Camina, corre o pedalea con GPS")
            }
        }
    }
}

private fun moodEmoji(mood: Mood) = when (mood) {
    Mood.GREAT -> "🤩"
    Mood.GOOD -> "😄"
    Mood.OK -> "🙂"
    Mood.TIRED -> "😴"
    Mood.SAD -> "😕"
}
