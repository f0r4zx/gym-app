package app.gymquest.core.nutrition

import app.gymquest.core.fitness.DietPreferences
import app.gymquest.core.fitness.NutritionTargets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class MealItem(val food: Food, val grams: Int) {
    val macros: Macros get() = food.macrosFor(grams.toDouble())
}

data class PlannedMeal(val type: MealType, val items: List<MealItem>) {
    val macros: Macros get() = Macros.sum(items.map { it.macros })
}

data class DayPlan(val meals: List<PlannedMeal>) {
    val totals: Macros get() = Macros.sum(meals.map { it.macros })
}

/**
 * Builds a day of meals that lands on your calorie and protein targets. For each meal it picks a
 * protein, a carb, a vegetable/fruit and a fat that suit your diet, fixes the small items and then
 * solves the two quantities that matter (protein and carb grams) so calories and protein match.
 */
object MealPlanner {

    private val splits = listOf(
        MealType.BREAKFAST to 0.25,
        MealType.LUNCH to 0.33,
        MealType.SNACK to 0.12,
        MealType.DINNER to 0.30,
    )

    /** Very large targets are spread over five meals; nobody can eat 1,500 kcal in one sitting. */
    private val bigAppetiteSplits = listOf(
        MealType.BREAKFAST to 0.22,
        MealType.LUNCH to 0.27,
        MealType.SNACK to 0.12,
        MealType.DINNER to 0.27,
        MealType.SNACK to 0.12,
    )

    private const val BIG_APPETITE_KCAL = 3_300

    /** Protein grams per 100 kcal a food needs to be the "protein" of a meal. Legumes are mostly carbs. */
    private const val MIN_PROTEIN_DENSITY = 8.0

    private val proteinPools = mapOf(
        MealType.BREAKFAST to listOf("egg", "greek_yogurt", "cottage", "turkey_ham", "tofu", "whey", "panela", "pea_protein", "egg_white"),
        MealType.LUNCH to listOf("chicken_breast", "beef_steak", "salmon", "tilapia", "turkey_breast", "pork_loin", "shrimp", "tuna_can", "chicken_thigh", "tofu", "tempeh", "edamame", "egg", "lentils", "chickpeas"),
        MealType.DINNER to listOf("tilapia", "chicken_breast", "shrimp", "turkey_breast", "salmon", "tuna_can", "egg", "tofu", "tempeh", "edamame", "beef_lean", "lentils"),
        MealType.SNACK to listOf("greek_yogurt", "whey", "cottage", "edamame", "panela", "turkey_ham", "pea_protein", "egg", "tuna_can"),
    )
    private val carbPools = mapOf(
        MealType.BREAKFAST to listOf("oats", "tortilla_corn", "bread_whole", "cornflakes", "potato", "sweet_potato"),
        MealType.LUNCH to listOf("rice_white", "rice_brown", "pasta", "potato", "sweet_potato", "quinoa", "tortilla_corn", "beans_black"),
        MealType.DINNER to listOf("sweet_potato", "rice_brown", "tortilla_corn", "quinoa", "potato", "bread_whole", "beans_pinto"),
        MealType.SNACK to listOf("banana", "apple", "orange", "grapes", "mango", "pineapple", "pear", "rice_cake"),
    )
    private val sidePools = mapOf(
        MealType.BREAKFAST to listOf("strawberry", "banana", "papaya", "apple", "mango", "blueberry", "pineapple", "kiwi"),
        MealType.LUNCH to listOf("broccoli", "lettuce", "zucchini", "nopal", "mushroom", "cauliflower", "green_beans", "chayote", "spinach"),
        MealType.DINNER to listOf("spinach", "zucchini", "mushroom", "broccoli", "nopal", "cucumber", "bell_pepper", "green_beans"),
        MealType.SNACK to listOf("carrot", "cucumber", "strawberry", "kiwi"),
    )
    /** Concentrated protein used to top up a meal whose main protein food cannot reach the target. */
    private val boosterPools = mapOf(
        MealType.BREAKFAST to listOf("whey", "pea_protein", "greek_yogurt", "egg_white", "cottage", "turkey_ham"),
        MealType.LUNCH to listOf("tuna_can", "turkey_breast", "chicken_breast", "edamame", "tofu", "pea_protein", "greek_yogurt", "egg_white"),
        MealType.DINNER to listOf("tuna_can", "turkey_breast", "chicken_breast", "edamame", "tofu", "egg_white", "pea_protein", "greek_yogurt"),
        MealType.SNACK to listOf("whey", "pea_protein", "greek_yogurt", "cottage", "edamame"),
    )
    private val fatPools = mapOf(
        MealType.BREAKFAST to listOf("avocado", "almonds", "peanut_butter", "chia", "walnuts"),
        MealType.LUNCH to listOf("avocado", "olive_oil", "almonds"),
        MealType.DINNER to listOf("olive_oil", "avocado", "walnuts"),
        MealType.SNACK to listOf("almonds", "peanut_butter", "walnuts", "chia"),
    )

    fun planDay(targets: NutritionTargets, diet: DietPreferences, dayIndex: Int = 0): DayPlan {
        val layout = if (targets.kcal > BIG_APPETITE_KCAL) bigAppetiteSplits else splits
        var snacks = 0
        val meals = layout.map { (type, share) ->
            // A second snack must differ from the first one.
            val seed = dayIndex + if (type == MealType.SNACK) snacks++ * 2 else 0
            buildMeal(
                type,
                kcal = targets.kcal * share,
                protein = targets.proteinG * share,
                diet = diet,
                seed = seed,
            )
        }
        return DayPlan(meals)
    }

    fun planWeek(targets: NutritionTargets, diet: DietPreferences): List<DayPlan> =
        (0 until 7).map { planDay(targets, diet, it) }

    private fun pool(ids: List<String>, diet: DietPreferences): List<Food> =
        ids.mapNotNull { FoodDb.byId(it) }.filter { it.fits(diet) }

    private fun pick(pool: List<Food>, seed: Int, salt: Int): Food? =
        if (pool.isEmpty()) null else pool[(seed * 3 + salt).mod(pool.size)]

    private fun buildMeal(type: MealType, kcal: Double, protein: Double, diet: DietPreferences, seed: Int): PlannedMeal {
        val proteins = pool(proteinPools.getValue(type), diet)
        val dense = proteins.filter { it.per100g.proteinG / it.per100g.kcal * 100.0 >= MIN_PROTEIN_DENSITY }
        val a = pick(dense.ifEmpty { proteins }, seed, type.ordinal)
        val b = pick(pool(carbPools.getValue(type), diet), seed, type.ordinal + 1)
        val side = pick(pool(sidePools.getValue(type), diet), seed, type.ordinal + 2)
        val fat = pick(pool(fatPools.getValue(type), diet), seed, type.ordinal + 3)

        val fixed = mutableListOf<MealItem>()
        if (side != null) {
            // Vegetables are a free-ish side; fruit at breakfast/snack counts for real.
            val grams = round5(side.servingG * if (type == MealType.SNACK) 0.8 else 1.0)
            fixed += MealItem(side, grams)
        }
        var fatIndex = -1
        if (fat != null) {
            fatIndex = fixed.size
            // Fats are dense: never let them take more than ~30% of the meal's calories.
            val perGram = fat.per100g.kcal / 100.0
            val grams = min(fat.servingG * if (type == MealType.SNACK) 0.8 else 1.0, kcal * 0.30 / perGram)
            fixed += MealItem(fat, round5(grams))
        }

        // Eggs, edamame etc. cannot reach a big protein target in a realistic portion: add a booster.
        if (a != null) {
            val fixedProtein = Macros.sum(fixed.map { it.macros }).proteinG
            val reachable = a.per100g.proteinG / 100.0 * a.servingG * 3.0
            val gap = protein - fixedProtein - reachable * 0.9
            if (gap > 6.0) {
                val used = setOfNotNull(a.id, b?.id, side?.id, fat?.id)
                val booster = boosterPools.getValue(type).mapNotNull { FoodDb.byId(it) }
                    .firstOrNull { it.fits(diet) && it.id !in used }
                if (booster != null) {
                    val perGram = booster.per100g.proteinG / 100.0
                    fixed += MealItem(booster, round5((gap / perGram).coerceIn(10.0, booster.servingG * 2.5)))
                }
            }
        }

        val fixedMacros = Macros.sum(fixed.map { it.macros })
        val kcalLeft = kcal - fixedMacros.kcal
        val proteinLeft = protein - fixedMacros.proteinG

        if (a == null && b == null) return PlannedMeal(type, fixed)
        if (a == null || b == null) {
            val only = a ?: b!!
            val grams = clampGrams(only, kcalLeft / only.per100g.kcal * 100.0)
            return PlannedMeal(type, listOf(MealItem(only, grams)) + fixed)
        }

        var (gA, gB) = solve(a, b, kcalLeft, proteinLeft)
        var items = listOf(MealItem(a, round5(gA)), MealItem(b, round5(gB)))
        var total = Macros.sum(items.map { it.macros }) + fixedMacros

        // Still short on calories (big appetite, lean foods): add more of the fat, then more carbs.
        var residual = kcal - total.kcal
        if (residual > 120 && fatIndex >= 0) {
            val current = fixed[fatIndex]
            val extra = min(residual / current.food.per100g.kcal * 100.0, current.food.servingG * 3.0)
            fixed[fatIndex] = MealItem(current.food, round5(current.grams + extra))
            total = Macros.sum(items.map { it.macros }) + Macros.sum(fixed.map { it.macros })
            residual = kcal - total.kcal
        }
        if (residual > 120) {
            val bump = min(residual / b.per100g.kcal * 100.0, b.servingG * 2.0)
            gB = round5(gB + bump).toDouble()
            items = listOf(MealItem(a, round5(gA)), MealItem(b, round5(gB)))
        }
        return PlannedMeal(type, items + fixed)
    }

    /**
     * Solves for grams of [a] (protein food) and [b] (carb food) so that
     *   kcal(a) + kcal(b) = [kcal]   and   protein(a) + protein(b) = [protein].
     * Falls back to hitting calories alone when the exact solution is not a sensible portion.
     */
    private fun solve(a: Food, b: Food, kcal: Double, protein: Double): Pair<Double, Double> {
        val ka = a.per100g.kcal / 100.0
        val kb = b.per100g.kcal / 100.0
        val pa = a.per100g.proteinG / 100.0
        val pb = b.per100g.proteinG / 100.0
        val loA = a.servingG * 0.35
        val hiA = a.servingG * 3.0
        val loB = b.servingG * 0.2
        val hiB = b.servingG * 3.5

        val det = ka * pb - kb * pa
        if (abs(det) > 1e-9) {
            val gA = (kcal * pb - kb * protein) / det
            val gB = (ka * protein - kcal * pa) / det
            if (gA in loA..hiA && gB in loB..hiB) return gA to gB
        }
        // Fix the protein food to cover as much protein as sensible, then fill calories with the carb.
        val gA = (protein / max(pa, 1e-6)).coerceIn(loA, hiA)
        val gB = ((kcal - gA * ka) / kb).coerceIn(loB, hiB)
        return gA to gB
    }

    private fun clampGrams(food: Food, grams: Double): Int =
        round5(grams.coerceIn(food.servingG * 0.5, food.servingG * 3.0))

    private fun round5(x: Double): Int = max(5, (x / 5.0).roundToInt() * 5)

    /** Everything to buy for a set of days, grouped by food. */
    fun shoppingList(plans: List<DayPlan>): List<MealItem> =
        plans.flatMap { it.meals }.flatMap { it.items }
            .groupBy { it.food.id }
            .map { (_, items) -> MealItem(items.first().food, items.sumOf { it.grams }) }
            .sortedWith(compareBy({ it.food.category.ordinal }, { it.food.name }))
}
