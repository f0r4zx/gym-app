package app.gymquest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import app.gymquest.core.fitness.BodyMetrics
import app.gymquest.core.fitness.Hydration
import app.gymquest.core.wellness.DayPart
import app.gymquest.core.wellness.SkincareDay
import app.gymquest.core.wellness.SkincareStats
import app.gymquest.core.wellness.TrendStatus
import app.gymquest.core.wellness.WeightEntry
import app.gymquest.core.wellness.WeightTrend
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.ui.Controller
import app.gymquest.ui.components.Bar
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.NumberField
import app.gymquest.ui.components.PrimaryButton
import app.gymquest.ui.components.SectionTitle
import app.gymquest.ui.theme.Mint
import app.gymquest.ui.theme.Sky
import java.time.LocalDate
import java.time.LocalTime

private enum class LifeTab(val label: String) {
    WATER("💧 Agua"), FOOD("🍽️ Comida"), SKIN("🧴 Piel"), WEIGHT("⚖️ Peso"), SLEEP("😴 Sueño")
}

@Composable
fun LifeScreen(state: AppState, c: Controller) {
    var tabName by rememberSaveable { mutableStateOf(LifeTab.WATER.name) }
    val tab = LifeTab.valueOf(tabName)
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vida y bienestar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        ChoiceChips(LifeTab.entries, tab, { it.label }, { tabName = it.name })
        when (tab) {
            LifeTab.WATER -> WaterPanel(state, c)
            LifeTab.FOOD -> FoodPanel(state, c)
            LifeTab.SKIN -> SkinPanel(state, c)
            LifeTab.WEIGHT -> WeightPanel(state, c)
            LifeTab.SLEEP -> SleepPanel(state, c)
        }
    }
}

// ---- Water -----------------------------------------------------------------------------------

@Composable
private fun WaterPanel(state: AppState, c: Controller) {
    val profile = Derived.profile(state) ?: return
    val day = state.day(c.today.toEpochDay())
    val target = Hydration.targetMl(profile.weightKg, day.activeMinutes)
    val glasses = Hydration.glasses(day.waterMl)
    val goalGlasses = Hydration.glasses(target)
    val now = LocalTime.now()
    val pace = Hydration.pace(day.waterMl, target, now.hour + now.minute / 60.0, state.profile?.wakeHour ?: 7, (state.profile?.sleepHour ?: 23) % 24)

    GqCard {
        Text("🥤 $glasses de $goalGlasses vasos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Muted("${day.waterMl} ml de $target ml (vaso de ${Hydration.GLASS_ML} ml)")
        Bar(day.waterMl.toDouble() / target, color = Sky, height = 16.dp)
        Text(
            when (pace) {
                Hydration.Pace.AHEAD -> "¡Vas adelantado! Sigue así."
                Hydration.Pace.ON_TRACK -> "Vas al ritmo justo."
                Hydration.Pace.BEHIND -> "Vas atrasado: toma un vaso ahora."
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("+1 vaso", { c.act { Actions.addWater(it, 1, c.today) } }, Modifier.weight(1f))
            OutlinedButton(onClick = { c.act { Actions.addWater(it, -1, c.today) } }, enabled = day.waterMl > 0) { Text("−1") }
        }
    }
    val hours = Hydration.reminderHours(state.profile?.wakeHour ?: 7, state.profile?.sleepHour ?: 23)
    GqCard {
        Text("Recordatorios sugeridos", fontWeight = FontWeight.Bold)
        Muted(hours.joinToString("  ·  ") { "$it:00" })
        Muted("Se ajustan si entrenas: ${"%.1f".format(target / 1000.0)} L hoy para tu peso y tu actividad.")
    }
}

// ---- Skin care ---------------------------------------------------------------------------------

@Composable
private fun SkinPanel(state: AppState, c: Controller) {
    val routine = Derived.skincareRoutine(state)
    val day = state.day(c.today.toEpochDay())
    val done = SkincareDay(c.today, day.skincareSteps.toSet())
    GqCard {
        Text("Rutina de hoy", fontWeight = FontWeight.Bold)
        Bar(done.fraction(routine), color = Mint)
        for (part in DayPart.entries) {
            val steps = routine.filter { it.part == part }
            if (steps.isEmpty()) continue
            SectionTitle(part.label)
            steps.forEach { step ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = step.id in done.completed, onCheckedChange = { c.act { s -> Actions.toggleSkincare(s, step.id, c.today) } })
                    Column(Modifier.weight(1f)) {
                        Text(step.name + if (step.optional) " (opcional)" else "")
                        Muted(step.description)
                    }
                }
            }
        }
    }
    GqCard {
        Text("🚿 Lavadas de cara hoy: ${day.faceWashes}", fontWeight = FontWeight.Bold)
        Muted(SkincareStats.washAdvice(day.faceWashes))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Me lavé la cara", { c.change { Actions.washFace(it, c.today) } })
            OutlinedButton(onClick = { c.change { Actions.undoFaceWash(it, c.today) } }, enabled = day.faceWashes > 0) { Text("Deshacer") }
        }
    }
}

// ---- Weight ---------------------------------------------------------------------------------------

@Composable
private fun WeightPanel(state: AppState, c: Controller) {
    val profile = Derived.profile(state) ?: return
    var input by rememberSaveable { mutableStateOf("") }
    val entries = state.weights.map { WeightEntry(LocalDate.ofEpochDay(it.epochDay), it.kg) }
    val assessment = WeightTrend.assess(entries, profile.goal)
    val bmi = BodyMetrics.bmi(profile.weightKg, profile.heightCm)
    val range = BodyMetrics.healthyWeightRangeKg(profile.heightCm)

    GqCard {
        Text("${"%.1f".format(profile.weightKg)} kg", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Muted("IMC ${"%.1f".format(bmi)} · ${BodyMetrics.bmiCategory(bmi).label} · rango saludable ${"%.0f".format(range.start)}–${"%.0f".format(range.endInclusive)} kg")
        Text(
            when (assessment.status) {
                TrendStatus.ON_TRACK -> "✅ ${assessment.message}"
                TrendStatus.INSUFFICIENT_DATA -> "ℹ️ ${assessment.message}"
                else -> "⚠️ ${assessment.message}"
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(input, { input = it }, "Peso de hoy", suffix = "kg", modifier = Modifier.weight(1f))
            PrimaryButton("Guardar", {
                val kg = input.toDoubleOrNull()
                if (kg != null && kg in 25.0..350.0) {
                    c.change { Actions.logWeight(it, kg, c.today) }
                    input = ""
                    c.say("Peso guardado")
                }
            }, enabled = input.toDoubleOrNull() != null)
        }
    }
    if (state.weights.size > 1) {
        GqCard {
            Text("Historial", fontWeight = FontWeight.Bold)
            state.weights.takeLast(8).reversed().forEach {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LocalDate.ofEpochDay(it.epochDay).toString())
                    Text("${"%.1f".format(it.kg)} kg", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
