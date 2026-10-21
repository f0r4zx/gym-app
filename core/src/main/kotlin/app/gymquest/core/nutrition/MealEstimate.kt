package app.gymquest.core.nutrition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EstimatedItem(
    val name: String = "",
    val grams: Double = 0.0,
    val kcal: Double = 0.0,
    @SerialName("protein_g") val proteinG: Double = 0.0,
    @SerialName("carbs_g") val carbsG: Double = 0.0,
    @SerialName("fat_g") val fatG: Double = 0.0,
) {
    val macros: Macros get() = Macros(kcal, proteinG, carbsG, fatG)
}

@Serializable
data class EstimatedMeal(
    val items: List<EstimatedItem> = emptyList(),
    val confidence: Double = 0.5,
    val notes: String = "",
) {
    val totals: Macros get() = Macros.sum(items.map { it.macros })
}

/**
 * Prompt and parser for estimating a meal from a photo with a vision-capable model. The model's
 * answer is treated as untrusted text: it is parsed leniently and every number is sanity-checked.
 */
object MealEstimates {

    const val SYSTEM_PROMPT = """Eres un nutriólogo que estima calorías y macros a partir de fotos de comida.
Identifica cada alimento visible y estima su peso en gramos usando como referencia el tamaño del plato, los cubiertos y las manos si aparecen.
Responde ÚNICAMENTE con un objeto JSON, sin texto antes ni después ni bloques de código, con esta forma exacta:
{"items":[{"name":"nombre en español","grams":0,"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0}],"confidence":0.0,"notes":"una frase corta"}
"confidence" va de 0 a 1 (qué tan seguro estás). Si la imagen no contiene comida, responde {"items":[],"confidence":0,"notes":"No veo comida en la foto"}.
Los valores son estimaciones: no inventes precisión y redondea."""

    const val USER_PROMPT = "Estima los alimentos, gramos, calorías y macros de esta comida."

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private const val MAX_ITEMS = 12
    private const val MAX_ITEM_KCAL = 3_000.0
    private const val MAX_ITEM_GRAMS = 2_000.0

    /** Parses and validates a model reply. Returns null if it has no usable food items. */
    fun parse(reply: String): EstimatedMeal? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val raw = runCatching { json.decodeFromString<EstimatedMeal>(reply.substring(start, end + 1)) }.getOrNull() ?: return null

        val items = raw.items
            .filter { it.name.isNotBlank() }
            .take(MAX_ITEMS)
            .map(::sanitise)
            .filter { it.kcal > 0 }
        if (items.isEmpty()) return null
        return EstimatedMeal(items, raw.confidence.coerceIn(0.0, 1.0), raw.notes.take(200))
    }

    private fun sanitise(item: EstimatedItem): EstimatedItem {
        val p = item.proteinG.coerceIn(0.0, 400.0)
        val c = item.carbsG.coerceIn(0.0, 600.0)
        val f = item.fatG.coerceIn(0.0, 400.0)
        val fromMacros = p * 4 + c * 4 + f * 9
        var kcal = item.kcal.coerceIn(0.0, MAX_ITEM_KCAL)
        kcal = when {
            kcal <= 0.0 -> fromMacros
            fromMacros > 0 && kotlin.math.abs(kcal - fromMacros) / fromMacros > 0.35 -> (kcal + fromMacros) / 2 // they disagree: split the difference
            else -> kcal
        }
        return item.copy(
            name = item.name.trim().take(60),
            grams = item.grams.coerceIn(0.0, MAX_ITEM_GRAMS),
            kcal = kcal.coerceIn(0.0, MAX_ITEM_KCAL),
            proteinG = p, carbsG = c, fatG = f,
        )
    }
}

data class LoggedFood(
    val name: String,
    val mealType: MealType,
    val macros: Macros,
    val timeMs: Long,
    val foodId: String? = null,
    val grams: Double? = null,
)
