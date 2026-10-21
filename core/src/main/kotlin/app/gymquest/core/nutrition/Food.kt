package app.gymquest.core.nutrition

import app.gymquest.core.fitness.DietPreferences
import app.gymquest.core.fitness.DietStyle
import kotlin.math.roundToInt

data class Macros(
    val kcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double = 0.0,
) {
    operator fun plus(o: Macros) = Macros(kcal + o.kcal, proteinG + o.proteinG, carbsG + o.carbsG, fatG + o.fatG, fiberG + o.fiberG)
    operator fun minus(o: Macros) = Macros(kcal - o.kcal, proteinG - o.proteinG, carbsG - o.carbsG, fatG - o.fatG, fiberG - o.fiberG)
    operator fun times(k: Double) = Macros(kcal * k, proteinG * k, carbsG * k, fatG * k, fiberG * k)

    /** Calories implied by the macros (Atwater factors). */
    val atwaterKcal: Double get() = proteinG * 4 + carbsG * 4 + fatG * 9

    fun rounded() = Macros(kcal.roundToInt().toDouble(), proteinG.roundToInt().toDouble(), carbsG.roundToInt().toDouble(), fatG.roundToInt().toDouble(), fiberG.roundToInt().toDouble())

    companion object {
        val ZERO = Macros()
        fun sum(items: Iterable<Macros>): Macros = items.fold(ZERO) { a, b -> a + b }
    }
}

enum class FoodCategory(val label: String) {
    PROTEIN("Proteínas"), DAIRY("Lácteos"), GRAIN("Cereales y tubérculos"), LEGUME("Legumbres"),
    FRUIT("Frutas"), VEGETABLE("Verduras"), FAT("Grasas y semillas"), DISH("Platillos"),
    SNACK("Botanas y dulces"), DRINK("Bebidas"),
}

/** Ingredients a food contains, used for diets and allergies. */
enum class FoodFlag { MEAT, FISH, DAIRY, EGG, GLUTEN, NUTS, HONEY }

enum class MealType(val label: String) {
    BREAKFAST("Desayuno"), LUNCH("Comida"), SNACK("Colación"), DINNER("Cena")
}

data class Food(
    val id: String,
    val name: String,
    val category: FoodCategory,
    /** Nutrients per 100 g (as eaten, i.e. cooked where applicable). */
    val per100g: Macros,
    val servingG: Int,
    val servingName: String,
    val flags: Set<FoodFlag> = emptySet(),
    /** Meals this food is a natural fit for, used by the planner. */
    val meals: Set<MealType> = MealType.entries.toSet(),
) {
    fun macrosFor(grams: Double): Macros = per100g * (grams / 100.0)

    fun serving(): Macros = macrosFor(servingG.toDouble())

    fun fits(diet: DietPreferences): Boolean {
        if (FoodFlag.MEAT in flags || FoodFlag.FISH in flags) if (diet.style != DietStyle.OMNIVORE) return false
        if (diet.style == DietStyle.VEGAN && (FoodFlag.DAIRY in flags || FoodFlag.EGG in flags || FoodFlag.HONEY in flags)) return false
        if (diet.glutenFree && FoodFlag.GLUTEN in flags) return false
        if (diet.lactoseFree && FoodFlag.DAIRY in flags) return false
        if (diet.noNuts && FoodFlag.NUTS in flags) return false
        return true
    }
}
