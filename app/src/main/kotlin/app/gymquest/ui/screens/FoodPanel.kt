package app.gymquest.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gymquest.core.nutrition.Food
import app.gymquest.core.nutrition.FoodDb
import app.gymquest.core.nutrition.FoodSuggester
import app.gymquest.core.nutrition.MealType
import app.gymquest.data.Actions
import app.gymquest.data.AppState
import app.gymquest.data.Derived
import app.gymquest.data.FoodEntryDto
import app.gymquest.data.enumOr
import app.gymquest.ui.Controller
import app.gymquest.ui.components.ChoiceChips
import app.gymquest.ui.components.GqCard
import app.gymquest.ui.components.LabeledBar
import app.gymquest.ui.components.Muted
import app.gymquest.ui.components.NumberField
import app.gymquest.ui.components.PrimaryButton
import app.gymquest.ui.components.SectionTitle
import app.gymquest.ui.theme.Gold
import app.gymquest.ui.theme.Mint
import app.gymquest.ui.theme.Sky
import java.time.LocalTime
import androidx.compose.foundation.layout.Column
import kotlin.math.roundToInt

private fun defaultMeal(hour: Int): MealType = when (hour) {
    in 4..10 -> MealType.BREAKFAST
    in 11..16 -> MealType.LUNCH
    in 17..21 -> MealType.DINNER
    else -> MealType.SNACK
}

@Composable
internal fun FoodPanel(state: AppState, c: Controller) {
    val targets = Derived.targets(state, c.today) ?: return
    val profile = Derived.profile(state) ?: return
    val day = state.day(c.today.toEpochDay())
    val eaten = Derived.eatenToday(state, c.today)

    var mealName by rememberSaveable { mutableStateOf(defaultMeal(LocalTime.now().hour).name) }
    val meal = enumOr(mealName, MealType.SNACK)
    var query by rememberSaveable { mutableStateOf("") }
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    var gramsText by rememberSaveable { mutableStateOf("") }

    GqCard {
        Text("Hoy: ${eaten.kcal.roundToInt()} de ${targets.kcal} kcal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LabeledBar("Calorías", "${eaten.kcal.roundToInt()} / ${targets.kcal}", eaten.kcal / targets.kcal)
        LabeledBar("Proteína", "${eaten.proteinG.roundToInt()} / ${targets.proteinG} g", eaten.proteinG / targets.proteinG, Mint)
        LabeledBar("Carbohidratos", "${eaten.carbsG.roundToInt()} / ${targets.carbsG} g", eaten.carbsG / targets.carbsG, Sky)
        LabeledBar("Grasa", "${eaten.fatG.roundToInt()} / ${targets.fatG} g", eaten.fatG / targets.fatG, Gold)
    }

    SectionTitle("Agregar comida")
    ChoiceChips(MealType.entries, meal, { it.label }, { mealName = it.name })
    OutlinedTextField(
        value = query,
        onValueChange = { query = it; pickedId = null },
        label = { Text("Buscar (ej. pollo, arroz, manzana)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    val picked = pickedId?.let { FoodDb.byId(it) }
    if (picked != null) {
        val grams = gramsText.toDoubleOrNull() ?: picked.servingG.toDouble()
        val macros = picked.macrosFor(grams)
        GqCard {
            Text(picked.name, fontWeight = FontWeight.Bold)
            Muted("1 porción = ${picked.servingName} (${picked.servingG} g)")
            NumberField(gramsText, { gramsText = it }, "Gramos (por defecto ${picked.servingG})", suffix = "g", modifier = Modifier.fillMaxWidth())
            Text("${macros.kcal.roundToInt()} kcal · P ${macros.proteinG.roundToInt()} g · C ${macros.carbsG.roundToInt()} g · G ${macros.fatG.roundToInt()} g")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Agregar", {
                    c.act { Actions.logFood(it, entryFor(picked, grams, meal), c.today) }
                    pickedId = null; query = ""; gramsText = ""
                })
                OutlinedButton(onClick = { pickedId = null; gramsText = "" }) { Text("Cancelar") }
            }
        }
    } else if (query.isNotBlank()) {
        val results = remember(query, state.profile) { FoodDb.search(query, profile.diet, limit = 8) }
        if (results.isEmpty()) Muted("No encontré \"$query\". Prueba otra palabra o agrégalo a mano abajo.")
        results.forEach { f ->
            GqCard(onClick = { pickedId = f.id; gramsText = "" }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f))
                    Muted("${f.serving().kcal.roundToInt()} kcal / ${f.servingName}")
                }
            }
        }
    }

    // Suggestions that fill whatever is still missing today.
    val remaining = app.gymquest.core.nutrition.Macros(
        targets.kcal - eaten.kcal, targets.proteinG - eaten.proteinG, targets.carbsG - eaten.carbsG, targets.fatG - eaten.fatG,
    )
    val suggestions = remember(remaining.kcal.roundToInt() / 50, meal, state.profile) {
        FoodSuggester.suggest(remaining, profile.diet, meal, limit = 4)
    }
    if (suggestions.isNotEmpty() && picked == null && query.isBlank()) {
        SectionTitle("Te sugiero para ${meal.label.lowercase()}")
        suggestions.forEach { s ->
            GqCard(onClick = { c.act { Actions.logFood(it, entryFor(s.food, s.grams.toDouble(), meal), c.today) } }) {
                Text("${s.food.name} · ${s.grams} g", fontWeight = FontWeight.SemiBold)
                Muted("${s.macros.kcal.roundToInt()} kcal · P ${s.macros.proteinG.roundToInt()} g — ${s.reason}")
                Text("Toca para agregar", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    ManualFood(meal) { entry -> c.act { Actions.logFood(it, entry, c.today) } }

    SectionTitle("Lo que comiste hoy")
    if (day.foods.isEmpty()) Muted("Aún no registras nada. Cada comida registrada da XP.")
    MealType.entries.forEach { type ->
        val rows = day.foods.withIndex().filter { it.value.meal() == type }
        if (rows.isEmpty()) return@forEach
        GqCard {
            Text(type.label, fontWeight = FontWeight.Bold)
            rows.forEach { (index, f) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name)
                        Muted("${f.kcal.roundToInt()} kcal · P ${f.protein.roundToInt()} g")
                    }
                    Text(
                        "✕", modifier = Modifier.clickable { c.change { Actions.removeFood(it, index, c.today) } }.padding(12.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun entryFor(food: Food, grams: Double, meal: MealType): FoodEntryDto {
    val m = food.macrosFor(grams)
    return FoodEntryDto(food.name, meal.name, m.kcal, m.proteinG, m.carbsG, m.fatG, System.currentTimeMillis(), food.id, grams)
}

@Composable
private fun ManualFood(meal: MealType, onAdd: (FoodEntryDto) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var kcal by rememberSaveable { mutableStateOf("") }
    var protein by rememberSaveable { mutableStateOf("") }
    var carbs by rememberSaveable { mutableStateOf("") }
    var fat by rememberSaveable { mutableStateOf("") }
    if (!open) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Agregar a mano (sin buscar)") }
        return
    }
    GqCard {
        Text("Agregar a mano", fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        NumberField(kcal, { kcal = it }, "Calorías", suffix = "kcal", modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(protein, { protein = it }, "Proteína g", modifier = Modifier.weight(1f))
            NumberField(carbs, { carbs = it }, "Carbos g", modifier = Modifier.weight(1f))
            NumberField(fat, { fat = it }, "Grasa g", modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Agregar", {
                onAdd(
                    FoodEntryDto(
                        name.ifBlank { "Comida" }, meal.name, kcal.toDoubleOrNull() ?: 0.0,
                        protein.toDoubleOrNull() ?: 0.0, carbs.toDoubleOrNull() ?: 0.0, fat.toDoubleOrNull() ?: 0.0,
                        System.currentTimeMillis(),
                    ),
                )
                name = ""; kcal = ""; protein = ""; carbs = ""; fat = ""; open = false
            }, enabled = kcal.toDoubleOrNull() != null)
            OutlinedButton(onClick = { open = false }) { Text("Cancelar") }
        }
    }
}
