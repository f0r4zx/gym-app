package app.gymquest.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.fitness.AgeGuidance
import app.gymquest.core.fitness.ExerciseRecommender
import app.gymquest.core.fitness.WorkoutPlanner
import app.gymquest.core.fitness.GymCatalog
import app.gymquest.core.sharing.DecodeResult
import app.gymquest.core.sharing.RoutineShare
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.ui.Controller
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.Pill
import app.gymquest.ui.components.SectionTitle
import app.gymquest.ui.theme.Danger
import app.gymquest.ui.theme.Mint
import java.time.temporal.IsoFields

/** What to train: advice for the person's age, the best exercises for their goal and a weekly plan. */
@Composable
fun PlanScreen(state: AppState, c: Controller, onTrain: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val profile = Derived.profile(state) ?: return
    val age = profile.ageOn(c.today)
    val advice = remember(age) { AgeGuidance.advice(age) }
    val recommended = remember(state.profile, c.today) { ExerciseRecommender.recommend(profile, c.today, 8) }
    val week = c.today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    val plan = remember(state.profile, week) { WorkoutPlanner.plan(profile, rotation = week, today = c.today) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHeader("Mi plan", onBack)

        GqCard {
            Pill("${advice.band.label} · ${profile.goal.label}")
            Text(advice.headline, fontWeight = FontWeight.Bold)
            advice.focus.forEach { Text("• $it") }
            Muted("Meta semanal: ${advice.weekly.moderateMinutes} min de actividad, fuerza ${advice.weekly.strengthDays} días" +
                (if (advice.weekly.balanceDays > 0) ", equilibrio ${advice.weekly.balanceDays} días" else "") + ".")
            Muted(advice.weekly.note)
            Muted("Calentamiento: ${advice.warmupMinutes} min · pulso máximo estimado ${advice.maxHeartRate} lpm · máx. ${advice.maxHardDaysPerWeek} días fuertes por semana.")
            advice.cautions.forEach { Text("⚠ $it", color = Danger, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
        }

        SectionTitle("Los mejores ejercicios para ti")
        recommended.forEach { r ->
            val tracked = r.exercise.tracked
            GqCard(onClick = tracked?.let { t -> { onTrain("free:${t.id}") } }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(r.exercise.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (tracked != null) Pill("📷 Con cámara", Mint.copy(alpha = 0.25f))
                }
                Muted(r.exercise.primary.label)
                r.reasons.take(2).forEach { Muted("• $it") }
            }
        }

        SectionTitle("Tu semana de entrenamiento")
        plan.days.forEach { d ->
            GqCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Día ${d.dayNumber}: ${d.title}", fontWeight = FontWeight.Bold)
                    if (d.isRest) Pill("Descanso")
                }
                if (d.focus.isNotBlank()) Muted(d.focus)
                d.items.forEach { i ->
                    val reps = if (i.minReps == i.maxReps) "${i.minReps}" else "${i.minReps}-${i.maxReps}"
                    Text("• ${i.exercise.name} — ${i.sets}×$reps${if (i.exercise.timed) " s" else ""}, descanso ${i.restSeconds} s")
                }
                d.cardioNote?.let { Muted("🏃 $it") }
            }
        }
        plan.notes.forEach { Muted("ℹ $it") }

        SectionTitle("Rutinas de amigos")
        ImportRoutine(c)
        state.savedRoutines.forEachIndexed { index, r ->
            GqCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.name}${r.author?.let { " · por $it" } ?: ""}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { c.change { Actions.deleteRoutine(it, index) } }) { Text("Borrar") }
                }
                r.days.forEach { d ->
                    Text(d.title, fontWeight = FontWeight.SemiBold)
                    d.items.forEach { i ->
                        val name = GymCatalog.byId(i.id)
                        val reps = if (i.min == i.max) "${i.min}" else "${i.min}-${i.max}"
                        Muted("• ${name?.name ?: i.id} — ${i.sets}×$reps, descanso ${i.rest}s")
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                val routine = RoutineShare.fromPlan(plan, "Rutina de ${profile.name}", profile.name)
                val text = RoutineShare.toText(routine) + "\n\n" + RoutineShare.link(RoutineShare.encode(routine))
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, "Compartir rutina"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("📤 Compartir mi rutina") }
        Muted("Las recomendaciones son orientativas. Si tienes una lesión o condición médica, consulta a un profesional.")
    }
}


/** Paste a code or link that a friend sent and keep the routine. The code is checked before it is used. */
@Composable
private fun ImportRoutine(c: Controller) {
    var text by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    GqCard {
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(RoutineShare.MAX_CODE_CHARS + 64) },
            label = { Text("Pega aquí el código o enlace de la rutina") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                when (val r = RoutineShare.decode(RoutineShare.codeFromLink(text))) {
                    is DecodeResult.Ok -> {
                        c.change { Actions.saveRoutine(it, r.routine) }
                        message = "Guardada: ${r.routine.name}" + if (r.warnings.isNotEmpty()) " (${r.warnings.size} aviso/s)" else ""
                        text = ""
                    }
                    is DecodeResult.Invalid -> message = r.reason
                }
            },
            enabled = text.isNotBlank(),
        ) { Text("Guardar rutina") }
        if (message.isNotBlank()) Muted(message)
    }
}
