package app.gymquest.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.fitness.ActivityLevel
import app.gymquest.core.fitness.BodyMetrics
import app.gymquest.core.fitness.DietStyle
import app.gymquest.core.fitness.Equipment
import app.gymquest.core.fitness.Experience
import app.gymquest.core.fitness.Goal
import app.gymquest.core.fitness.HealthAdvisor
import app.gymquest.core.fitness.NutritionPlanner
import app.gymquest.core.fitness.Sex
import app.gymquest.core.fitness.Severity
import app.gymquest.core.wellness.SkinType
import app.gymquest.data.ProfileDto
import app.gymquest.data.enumOr
import app.gymquest.data.toProfile
import app.gymquest.ui.components.Bar
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.MultiChips
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.NumberField
import app.gymquest.ui.components.PrimaryButton
import java.time.LocalDate
import kotlin.math.roundToInt

private const val STEPS = 7

@Composable
fun OnboardingScreen(today: LocalDate, onDone: (ProfileDto) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf(Sex.OTHER.name) }
    var height by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf(Goal.GENERAL_HEALTH.name) }
    var activity by rememberSaveable { mutableStateOf(ActivityLevel.LIGHT.name) }
    var experience by rememberSaveable { mutableStateOf(Experience.BEGINNER.name) }
    var days by rememberSaveable { mutableIntStateOf(3) }
    var equipment by rememberSaveable { mutableStateOf(Equipment.BODYWEIGHT.name) }
    var diet by rememberSaveable { mutableStateOf(DietStyle.OMNIVORE.name) }
    var gluten by rememberSaveable { mutableStateOf(false) }
    var lactose by rememberSaveable { mutableStateOf(false) }
    var nuts by rememberSaveable { mutableStateOf(false) }
    var skin by rememberSaveable { mutableStateOf(SkinType.NORMAL.name) }
    var acne by rememberSaveable { mutableStateOf(false) }
    var wake by rememberSaveable { mutableIntStateOf(7) }
    var sleep by rememberSaveable { mutableIntStateOf(23) }

    val ageN = age.toIntOrNull()
    val heightN = height.toDoubleOrNull()
    val weightN = weight.toDoubleOrNull()
    val targetN = target.toDoubleOrNull()
    val valid = when (step) {
        0 -> name.isNotBlank() && ageN != null && ageN in 10..100
        1 -> heightN != null && heightN in 100.0..250.0 && weightN != null && weightN in 25.0..350.0
        else -> true
    }

    fun build() = ProfileDto(
        name = name.trim(),
        birthEpochDay = today.minusYears((ageN ?: 25).toLong()).toEpochDay(),
        sex = sex,
        heightCm = heightN ?: 170.0,
        weightKg = weightN ?: 70.0,
        goal = goal,
        activity = activity,
        experience = experience,
        daysPerWeek = days,
        equipment = equipment.split(',').filter { it.isNotBlank() }.ifEmpty { listOf(Equipment.BODYWEIGHT.name) },
        targetWeightKg = targetN,
        dietStyle = diet,
        glutenFree = gluten, lactoseFree = lactose, noNuts = nuts,
        skinType = skin, acneProne = acne,
        wakeHour = wake, sleepHour = sleep,
    )

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Bar((step + 1).toDouble() / STEPS)
        Spacer(Modifier.height(4.dp))
        Muted("Paso ${step + 1} de $STEPS")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (step) {
                0 -> {
                    Title("¡Bienvenido a GymQuest!", "Convierte cada repetición, cada paso y cada vaso de agua en experiencia. Cuéntame de ti para armar tu plan.")
                    OutlinedTextField(name, { name = it.take(24) }, label = { Text("Tu nombre o apodo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    NumberField(age, { age = it }, "Edad", suffix = "años", decimals = false, modifier = Modifier.fillMaxWidth())
                    Text("Sexo (para calcular tu metabolismo)", fontWeight = FontWeight.SemiBold)
                    ChoiceChips(Sex.entries, enumOr(sex, Sex.OTHER), { it.label }, { sex = it.name })
                }
                1 -> {
                    Title("Tu cuerpo", "Con esto calculo tus calorías, tu agua y cómo se verá tu personaje. Puedes cambiarlo cuando quieras.")
                    NumberField(height, { height = it }, "Estatura", suffix = "cm", modifier = Modifier.fillMaxWidth())
                    NumberField(weight, { weight = it }, "Peso actual", suffix = "kg", modifier = Modifier.fillMaxWidth())
                    NumberField(target, { target = it }, "Peso objetivo (opcional)", suffix = "kg", modifier = Modifier.fillMaxWidth())
                }
                2 -> {
                    Title("¿Cuál es tu objetivo?", "Ajusto tus calorías, los ejercicios y las misiones a esto.")
                    Goal.entries.forEach { g ->
                        GqCard(
                            onClick = { goal = g.name },
                            container = if (goal == g.name) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface,
                        ) {
                            Text(g.label, fontWeight = FontWeight.Bold)
                            Muted(g.description)
                        }
                    }
                }
                3 -> {
                    Title("Tu rutina actual", "Para no pasarte ni quedarte corto al empezar.")
                    Text("¿Qué tan activo eres en el día a día?", fontWeight = FontWeight.SemiBold)
                    ChoiceChips(ActivityLevel.entries, enumOr(activity, ActivityLevel.LIGHT), { it.label }, { activity = it.name })
                    Text("Experiencia entrenando", fontWeight = FontWeight.SemiBold)
                    ChoiceChips(Experience.entries, enumOr(experience, Experience.BEGINNER), { it.label }, { experience = it.name })
                    Text("Días por semana que quieres entrenar: $days", fontWeight = FontWeight.SemiBold)
                    ChoiceChips((1..7).toList(), days, { it.toString() }, { days = it })
                }
                4 -> {
                    Title("Equipo y comida", "Solo te sugiero ejercicios y platillos que sí puedas hacer y comer.")
                    Text("Equipo disponible", fontWeight = FontWeight.SemiBold)
                    val owned = equipment.split(',').filter { it.isNotBlank() }.toSet()
                    MultiChips(
                        options = Equipment.entries,
                        selected = owned.mapNotNull { runCatching { Equipment.valueOf(it) }.getOrNull() }.toSet(),
                        label = { it.label },
                        onToggle = { e ->
                            val next = if (e.name in owned) owned - e.name else owned + e.name
                            equipment = next.ifEmpty { setOf(Equipment.BODYWEIGHT.name) }.joinToString(",")
                        },
                    )
                    Text("Tipo de dieta", fontWeight = FontWeight.SemiBold)
                    ChoiceChips(DietStyle.entries, enumOr(diet, DietStyle.OMNIVORE), { it.label }, { diet = it.name })
                    SwitchRow("Sin gluten", gluten) { gluten = it }
                    SwitchRow("Sin lácteos (lactosa)", lactose) { lactose = it }
                    SwitchRow("Alergia a nueces", nuts) { nuts = it }
                }
                5 -> {
                    Title("Piel y descanso", "Para tu rutina de cuidado facial y tu batería corporal.")
                    Text("Tipo de piel", fontWeight = FontWeight.SemiBold)
                    ChoiceChips(SkinType.entries, enumOr(skin, SkinType.NORMAL), { it.label }, { skin = it.name })
                    SwitchRow("Tiendo a tener acné", acne) { acne = it }
                    Text("Despiertas a las $wake:00", fontWeight = FontWeight.SemiBold)
                    ChoiceChips((4..10).toList(), wake, { "$it h" }, { wake = it })
                    Text("Te duermes a las ${sleep % 24}:00", fontWeight = FontWeight.SemiBold)
                    ChoiceChips(listOf(20, 21, 22, 23, 24, 25, 26), sleep, { "${it % 24} h" }, { sleep = it })
                }
                else -> Summary(build(), today)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.height(50.dp)) { Text("Atrás") }
            PrimaryButton(
                text = if (step == STEPS - 1) "¡Empezar mi aventura!" else "Siguiente",
                onClick = { if (step == STEPS - 1) onDone(build()) else step++ },
                enabled = valid,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
    Muted(subtitle)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Summary(dto: ProfileDto, today: LocalDate) {
    val profile = dto.toProfile()
    val targets = NutritionPlanner.targets(profile, today)
    val bmi = BodyMetrics.bmi(profile.weightKg, profile.heightCm)
    val notes = HealthAdvisor.review(profile, today)
    Title("Tu plan está listo, ${dto.name}", "Esto es lo que calculé con tus datos. Son estimaciones, no consejo médico.")
    GqCard {
        Text("Cada día", fontWeight = FontWeight.Bold)
        Text("🔥 ${targets.kcal} kcal  ·  💧 ${targets.waterMl / 1000.0} L de agua")
        Text("Proteína ${targets.proteinG} g  ·  Carbohidratos ${targets.carbsG} g  ·  Grasa ${targets.fatG} g")
        Muted("IMC ${"%.1f".format(bmi)} · metabolismo basal ${targets.bmr} kcal · gasto total ${targets.tdee} kcal")
        if (targets.weeklyChangeKg != 0.0) {
            val kg = (targets.weeklyChangeKg * 100).roundToInt() / 100.0
            Muted("Ritmo sugerido: ${if (kg > 0) "+" else ""}$kg kg por semana")
        }
    }
    notes.forEach { n ->
        GqCard {
            Text(
                when (n.severity) {
                    Severity.WARNING -> "⚠️ ${n.text}"
                    Severity.TIP -> "💡 ${n.text}"
                    Severity.INFO -> "ℹ️ ${n.text}"
                },
            )
        }
    }
    Muted("Todo se guarda en tu teléfono y funciona sin internet.")
}
