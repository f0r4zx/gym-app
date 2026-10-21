package app.gymquest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.missions.MissionStreak
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.ui.Controller
import app.gymquest.ui.components.Bar
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.Pill
import app.gymquest.ui.components.PrimaryButton
import app.gymquest.ui.theme.Gold
import app.gymquest.ui.theme.Mint
import kotlin.math.roundToInt

@Composable
fun MissionsScreen(state: AppState, c: Controller, onBack: () -> Unit) {
    val board = Derived.missionBoard(state, c.today)
    val multiplier = MissionStreak.multiplier(state.game.missionStreak)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader("Misiones diarias", onBack)
        GqCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("${board.completedCount} de ${board.missions.size} completadas", fontWeight = FontWeight.Bold)
                    Muted("Cambian cada día según tu nivel, edad y equipo")
                }
                Pill("🔥 x${"%.1f".format(multiplier)}", Gold.copy(alpha = 0.25f))
            }
            Bar(board.completedCount.toDouble() / board.missions.size.coerceAtLeast(1), color = Gold, height = 12.dp)
            Muted("Racha de días completando todas: ${state.game.missionStreak}. Cada día seguido sube el multiplicador de recompensas (máx. x1.5).")
        }

        board.missions.forEach { p ->
            GqCard(container = if (p.claimed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Pill(p.mission.category.label)
                    Text("+${(p.mission.xp * multiplier).roundToInt()} XP  ·  +${(p.mission.coins * multiplier).roundToInt()} 🪙", style = MaterialTheme.typography.labelMedium)
                }
                Text(p.mission.title, fontWeight = FontWeight.Bold)
                Muted(p.mission.description)
                Bar(p.fraction, color = if (p.complete) Mint else MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Muted("${formatProgress(p.progress)} / ${p.mission.target}")
                    when {
                        p.claimed -> Text("✅ Reclamada")
                        p.complete -> PrimaryButton("Reclamar", { c.act { Actions.claimMission(it, p.mission.id, c.today) } })
                        else -> Unit
                    }
                }
            }
        }

        if (board.allComplete) {
            GqCard(container = Gold.copy(alpha = 0.18f)) {
                Text("🎉 ¡Completaste todo el día!", fontWeight = FontWeight.Bold)
                if (board.bonusClaimed) Text("✅ Bonus reclamado")
                else PrimaryButton("Reclamar bonus del día", { c.act { Actions.claimMissionBonus(it, c.today) } })
            }
        }
    }
}

private fun formatProgress(v: Double): String = if (v == v.toInt().toDouble()) v.toInt().toString() else "%.1f".format(v)
