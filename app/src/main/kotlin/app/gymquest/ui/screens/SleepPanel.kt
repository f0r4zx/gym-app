package app.gymquest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.wellness.BodyBattery
import app.gymquest.core.wellness.SleepEstimator
import app.gymquest.core.wellness.SleepNeed
import app.gymquest.core.wellness.SleepScore
import app.gymquest.core.wellness.SleepSession
import app.gymquest.core.wellness.SleepSource
import app.gymquest.core.wellness.WorkoutLoad
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.ui.Controller
import app.gymquest.ui.components.Bar
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.PrimaryButton
import app.gymquest.ui.theme.Danger
import app.gymquest.ui.theme.Gold
import app.gymquest.ui.theme.Mint
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private fun sessionOf(state: AppState, day: Long): SleepSession? {
    val d = state.days[day] ?: return null
    val start = d.sleepStartMs ?: return null
    val end = d.sleepEndMs ?: return null
    return SleepSession(start, end, d.sleepAwakeMs, runCatching { SleepSource.valueOf(d.sleepSource ?: "") }.getOrDefault(SleepSource.MANUAL), 0.8)
}

@Composable
internal fun SleepPanel(state: AppState, c: Controller) {
    val age = Derived.age(state, c.today)
    val need = SleepNeed.forAge(age)
    val todayKey = c.today.toEpochDay()
    val last = sessionOf(state, todayKey)
    val history = (0L..13L).mapNotNull { sessionOf(state, todayKey - it) }
    val debt = SleepScore.debtHours(history, need)
    val nowMs = System.currentTimeMillis()

    var bed by rememberSaveable { mutableIntStateOf(23) }
    var wake by rememberSaveable { mutableIntStateOf(7) }
    var awake by rememberSaveable { mutableIntStateOf(15) }

    GqCard {
        Text("Tu cuerpo necesita", fontWeight = FontWeight.Bold)
        Text("${"%.0f".format(need.minHours)}–${"%.0f".format(need.maxHours)} horas de sueño", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Muted("Recomendado para ${age} años. Si entrenas fuerte, necesitas un poco más.")
    }

    if (last != null) {
        val score = SleepScore.score(last, need)
        val workouts = state.workouts.filter { it.timeMs >= last.endMs }.map { WorkoutLoad(it.timeMs, (it.durationMs / 60_000).toInt(), 5.0) }
        val battery = BodyBattery.current(score, debt, last.endMs, nowMs, workouts)
        GqCard {
            Text("🔋 Batería corporal: ${battery.level}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Bar(
                battery.level / 100.0,
                color = when {
                    battery.level >= 60 -> Mint
                    battery.level >= 30 -> Gold
                    else -> Danger
                },
                height = 16.dp,
            )
            Text(battery.message)
            Muted("Dormiste ${"%.1f".format(last.asleepHours)} h · calidad $score/100 · deuda de sueño ${"%.1f".format(debt)} h")
            Muted("Hoy: ${battery.readiness.label}")
        }
    } else {
        GqCard {
            Text("Registra tu sueño de anoche para calcular tu batería corporal.", fontWeight = FontWeight.SemiBold)
            Muted("Más adelante podrá calcularse sola con el uso del teléfono o tu reloj.")
        }
    }

    GqCard {
        Text("Registrar anoche", fontWeight = FontWeight.Bold)
        Text("Me dormí a las ${bed % 24}:00")
        ChoiceChips(listOf(20, 21, 22, 23, 24, 25, 26, 27), bed, { "${it % 24} h" }, { bed = it })
        Text("Desperté a las $wake:00")
        ChoiceChips((4..11).toList(), wake, { "$it h" }, { wake = it })
        Text("Minutos despierto en la noche")
        ChoiceChips(listOf(0, 15, 30, 60, 90), awake, { "$it" }, { awake = it })
        PrimaryButton("Guardar sueño", {
            val zone = ZoneId.systemDefault()
            val wakeAt = c.today.atTime(wake, 0).atZone(zone)
            val bedDate: LocalDate = if (bed >= 24) c.today else c.today.minusDays(1)
            val bedAt = bedDate.atTime(bed % 24, 0).atZone(zone)
            c.act { Actions.logSleep(it, bedAt.toInstant().toEpochMilli(), wakeAt.toInstant().toEpochMilli(), awake * 60_000L, SleepSource.MANUAL.name, c.today) }
            c.say("Sueño guardado")
        })
    }

    val bedtime = BodyBattery.bedtimeFor(LocalTime.of(state.profile?.wakeHour ?: 7, 0), need)
    GqCard {
        Text("🌙 Esta noche", fontWeight = FontWeight.Bold)
        Text(SleepScore.tonightAdvice(debt, need))
        Muted("Para despertar a las ${state.profile?.wakeHour ?: 7}:00 con una noche completa, acuéstate a las ${bedtime.hour}:${"%02d".format(bedtime.minute)}.")
    }
}
