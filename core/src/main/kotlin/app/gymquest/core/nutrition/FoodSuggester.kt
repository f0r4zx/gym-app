package app.gymquest.core.nutrition

import app.gymquest.core.fitness.DietPreferences
import kotlin.math.max
import kotlin.math.min

data class FoodSuggestion(val food: Food, val grams: Int, val macros: Macros, val reason: String)

/** Recommends foods that fill what is still missing from today's targets. */
object FoodSuggester {

    fun suggest(
        remaining: Macros,
        diet: DietPreferences,
        mealType: MealType? = null,
        limit: Int = 5,
        exclude: Set<String> = emptySet(),
    ): List<FoodSuggestion> {
        val kcalLeft = remaining.kcal
        if (kcalLeft < 40) return emptyList()

        val candidates = FoodDb.all.filter {
            it.fits(diet) && it.id !in exclude &&
                it.category != FoodCategory.DISH && it.category != FoodCategory.DRINK && it.category != FoodCategory.SNACK &&
                (mealType == null || mealType in it.meals)
        }

        return candidates.mapNotNull { food ->
            val s = food.serving()
            if (s.kcal > kcalLeft * 1.15) return@mapNotNull null
            val p = fill(s.proteinG, remaining.proteinG)
            val c = fill(s.carbsG, remaining.carbsG)
            val f = fill(s.fatG, remaining.fatG)
            val overshoot = max(0.0, s.proteinG - max(remaining.proteinG, 0.0)) * 0.02 +
                max(0.0, s.fatG - max(remaining.fatG, 0.0)) * 0.03
            // Protein is the hardest macro to hit and the one that matters most, so it weighs most.
            val score = p * 3.0 + c * 1.0 + f * 0.8 + s.fiberG * 0.04 - overshoot
            if (score <= 0.05) return@mapNotNull null
            Triple(food, s, score)
        }
            .sortedByDescending { it.third }
            .take(limit)
            .map { (food, s, _) -> FoodSuggestion(food, food.servingG, s.rounded(), reason(food, s, remaining)) }
    }

    private fun fill(amount: Double, needed: Double): Double =
        if (needed <= 1.0) 0.0 else min(amount, needed) / needed

    private fun reason(food: Food, s: Macros, remaining: Macros): String = when {
        remaining.proteinG > 15 && s.proteinG >= 10 -> "Aporta ${s.proteinG.toInt()} g de proteína (te faltan ${remaining.proteinG.toInt()} g)."
        remaining.carbsG > 30 && s.carbsG >= 15 -> "Te da ${s.carbsG.toInt()} g de carbohidratos de energía."
        remaining.fatG > 10 && s.fatG >= 5 -> "Grasas buenas: ${s.fatG.toInt()} g."
        else -> "Ligero (${s.kcal.toInt()} kcal) y con fibra."
    }
}
