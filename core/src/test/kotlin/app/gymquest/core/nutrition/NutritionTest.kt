package app.gymquest.core.nutrition

import app.gymquest.core.fitness.ActivityLevel
import app.gymquest.core.fitness.DietPreferences
import app.gymquest.core.fitness.DietStyle
import app.gymquest.core.fitness.Experience
import app.gymquest.core.fitness.Goal
import app.gymquest.core.fitness.NutritionPlanner
import app.gymquest.core.fitness.NutritionTargets
import app.gymquest.core.fitness.Sex
import app.gymquest.core.fitness.UserProfile
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY = LocalDate.of(2026, 10, 21)

private fun targetsFor(
    sex: Sex = Sex.MALE, weight: Double = 80.0, height: Double = 178.0, goal: Goal = Goal.MAINTAIN,
    level: ActivityLevel = ActivityLevel.MODERATE, diet: DietPreferences = DietPreferences(),
): NutritionTargets = NutritionPlanner.targets(
    UserProfile("t", TODAY.minusYears(30), sex, height, weight, goal, level, Experience.INTERMEDIATE, 4, diet = diet),
    TODAY,
)

class FoodDbTest {
    @Test
    fun idsAreUnique() {
        assertEquals(FoodDb.all.size, FoodDb.all.map { it.id }.toSet().size)
    }

    @Test
    fun caloriesAgreeWithMacros() {
        // Catches typos in the table: Atwater energy should be close to the stated kcal.
        for (f in FoodDb.all) {
            val m = f.per100g
            val atwater = m.atwaterKcal
            if (m.kcal < 20) continue
            val alcoholAllowance = if (f.id == "beer") 15.0 else 0.0
            val tolerance = maxOf(0.18 * m.kcal, 14.0) + alcoholAllowance
            assertTrue(abs(atwater - m.kcal) <= tolerance, "${f.id}: kcal=${m.kcal} but macros give $atwater")
        }
    }

    @Test
    fun valuesArePlausible() {
        for (f in FoodDb.all) {
            val m = f.per100g
            assertTrue(m.kcal in 0.0..900.0, f.id)
            assertTrue(m.proteinG + m.carbsG + m.fatG <= 101.0, "${f.id} macros exceed 100 g")
            assertTrue(f.servingG in 5..500, f.id)
        }
    }

    @Test
    fun servingMathScalesLinearly() {
        val chicken = FoodDb.byId("chicken_breast")!!
        assertEquals(165.0, chicken.macrosFor(100.0).kcal, 1e-9)
        assertEquals(330.0, chicken.macrosFor(200.0).kcal, 1e-9)
        assertEquals(chicken.macrosFor(chicken.servingG.toDouble()), chicken.serving())
    }

    @Test
    fun dietFiltersRespectIngredients() {
        val chicken = FoodDb.byId("chicken_breast")!!
        val egg = FoodDb.byId("egg")!!
        val yogurt = FoodDb.byId("greek_yogurt")!!
        val pasta = FoodDb.byId("pasta")!!
        val almonds = FoodDb.byId("almonds")!!
        val honey = FoodDb.byId("honey")!!

        val veg = DietPreferences(DietStyle.VEGETARIAN)
        val vegan = DietPreferences(DietStyle.VEGAN)
        assertTrue(!chicken.fits(veg) && egg.fits(veg) && yogurt.fits(veg))
        assertTrue(!egg.fits(vegan) && !yogurt.fits(vegan) && !honey.fits(vegan))
        assertTrue(!pasta.fits(DietPreferences(glutenFree = true)))
        assertTrue(!yogurt.fits(DietPreferences(lactoseFree = true)))
        assertTrue(!almonds.fits(DietPreferences(noNuts = true)))
        assertTrue(FoodDb.byId("tofu")!!.fits(vegan))
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        assertTrue(FoodDb.search("PLATANO").any { it.id == "banana" })
        assertTrue(FoodDb.search("brocoli").any { it.id == "broccoli" })
        assertTrue(FoodDb.search("pollo").first().id.startsWith("chicken"))
        assertTrue(FoodDb.search("zzzz").isEmpty())
        assertTrue(FoodDb.search("  ").isEmpty())
    }

    @Test
    fun searchHonoursDiet() {
        val results = FoodDb.search("pollo", DietPreferences(DietStyle.VEGETARIAN))
        assertTrue(results.none { FoodFlag.MEAT in it.flags })
    }
}

class MealPlannerTest {
    private val dietCases = listOf(
        DietPreferences(),
        DietPreferences(DietStyle.VEGETARIAN),
        DietPreferences(DietStyle.VEGAN),
        DietPreferences(glutenFree = true, lactoseFree = true),
        DietPreferences(DietStyle.VEGAN, glutenFree = true, noNuts = true),
    )

    @Test
    fun dayPlanHitsCaloriesAndProtein() {
        for (goal in listOf(Goal.LOSE_FAT, Goal.BUILD_MUSCLE, Goal.MAINTAIN)) for (diet in dietCases) for (day in 0..6) {
            val targets = targetsFor(goal = goal, diet = diet)
            val plan = MealPlanner.planDay(targets, diet, day)
            val t = plan.totals
            assertTrue(abs(t.kcal - targets.kcal) <= targets.kcal * 0.12, "$goal/$diet/day$day kcal ${t.kcal.toInt()} vs ${targets.kcal}")
            assertTrue(t.proteinG >= targets.proteinG * 0.80, "$goal/$diet/day$day protein ${t.proteinG.toInt()} vs ${targets.proteinG}")
        }
    }

    @Test
    fun mealsFollowTheDietAndAllergies() {
        for (diet in dietCases) for (day in 0..6) {
            val plan = MealPlanner.planDay(targetsFor(diet = diet), diet, day)
            for (item in plan.meals.flatMap { it.items }) {
                assertTrue(item.food.fits(diet), "${item.food.name} breaks $diet")
            }
        }
    }

    @Test
    fun fourMealsInOrderWithSensibleShares() {
        val targets = targetsFor()
        val plan = MealPlanner.planDay(targets, DietPreferences())
        assertEquals(listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.SNACK, MealType.DINNER), plan.meals.map { it.type })
        assertEquals(4, plan.meals.size)
        val lunch = plan.meals[1].macros.kcal
        val snack = plan.meals[2].macros.kcal
        assertTrue(lunch > snack * 2)
        assertTrue(plan.meals.all { it.items.isNotEmpty() })
    }

    @Test
    fun portionsAreRealisticAndRoundedTo5g() {
        val plan = MealPlanner.planDay(targetsFor(weight = 95.0, goal = Goal.BUILD_MUSCLE), DietPreferences(), 2)
        for (item in plan.meals.flatMap { it.items }) {
            assertTrue(item.grams > 0 && item.grams % 5 == 0, "${item.food.name} ${item.grams}g")
            assertTrue(item.grams <= item.food.servingG * 5, "${item.food.name} ${item.grams}g is absurd")
        }
    }

    @Test
    fun differentDaysGiveVariety() {
        val targets = targetsFor()
        val plans = (0..6).map { d -> MealPlanner.planDay(targets, DietPreferences(), d).meals.flatMap { it.items }.map { it.food.id } }
        assertTrue(plans.toSet().size >= 5, "week looks repetitive")
    }

    @Test
    fun smallAppetitesAndBigAppetitesBothWork() {
        val small = targetsFor(sex = Sex.FEMALE, weight = 48.0, height = 155.0, goal = Goal.LOSE_FAT, level = ActivityLevel.SEDENTARY)
        val big = targetsFor(weight = 110.0, height = 190.0, goal = Goal.BUILD_MUSCLE, level = ActivityLevel.VERY_ACTIVE)
        for (t in listOf(small, big)) {
            val plan = MealPlanner.planDay(t, DietPreferences())
            assertTrue(abs(plan.totals.kcal - t.kcal) <= t.kcal * 0.12, "kcal ${plan.totals.kcal} vs ${t.kcal}")
        }
    }

    @Test
    fun shoppingListAddsUpGrams() {
        val targets = targetsFor()
        val week = MealPlanner.planWeek(targets, DietPreferences())
        assertEquals(7, week.size)
        val list = MealPlanner.shoppingList(week)
        val totalFromPlan = week.sumOf { d -> d.meals.sumOf { m -> m.items.sumOf { it.grams } } }
        assertEquals(totalFromPlan, list.sumOf { it.grams })
        assertEquals(list.size, list.map { it.food.id }.toSet().size)
    }
}

class FoodSuggesterTest {
    @Test
    fun missingProteinSuggestsProteinFoods() {
        val remaining = Macros(kcal = 600.0, proteinG = 60.0, carbsG = 40.0, fatG = 15.0)
        val s = FoodSuggester.suggest(remaining, DietPreferences())
        assertTrue(s.isNotEmpty())
        assertTrue(s.first().macros.proteinG >= 15, "top suggestion ${s.first().food.name}")
        assertTrue(s.all { it.macros.kcal <= remaining.kcal * 1.15 })
    }

    @Test
    fun suggestionsRespectDiet() {
        val s = FoodSuggester.suggest(Macros(600.0, 50.0, 60.0, 15.0), DietPreferences(DietStyle.VEGAN))
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { it.food.fits(DietPreferences(DietStyle.VEGAN)) })
    }

    @Test
    fun nothingToSuggestWhenYouAreDone() {
        assertTrue(FoodSuggester.suggest(Macros(20.0, 0.0, 0.0, 0.0), DietPreferences()).isEmpty())
    }

    @Test
    fun smallRemainingBudgetOnlySuggestsSmallFoods() {
        val s = FoodSuggester.suggest(Macros(120.0, 10.0, 10.0, 3.0), DietPreferences())
        assertTrue(s.all { it.macros.kcal <= 140 })
    }

    @Test
    fun excludeAndMealTypeFilters() {
        val all = FoodSuggester.suggest(Macros(500.0, 40.0, 50.0, 15.0), DietPreferences(), limit = 20)
        val excluded = FoodSuggester.suggest(Macros(500.0, 40.0, 50.0, 15.0), DietPreferences(), exclude = setOf(all.first().food.id), limit = 20)
        assertTrue(excluded.none { it.food.id == all.first().food.id })
        val breakfast = FoodSuggester.suggest(Macros(500.0, 40.0, 50.0, 15.0), DietPreferences(), MealType.BREAKFAST, limit = 20)
        assertTrue(breakfast.all { MealType.BREAKFAST in it.food.meals })
    }
}

class FoodMatcherTest {
    private fun labels(vararg l: Pair<String, Double>) = l.map { ImageLabel(it.first, it.second) }

    @Test
    fun mapsCommonLabelsToFoods() {
        val m = FoodMatcher.match(labels("Food" to 0.99, "Pizza" to 0.92, "Cuisine" to 0.9))
        assertEquals("pizza", m.first().food.id)
        assertTrue(m.none { it.food.id == "food" })
    }

    @Test
    fun ignoresGenericLabelsOnly() {
        assertTrue(FoodMatcher.match(labels("Food" to 0.9, "Tableware" to 0.8, "Dish" to 0.8)).isEmpty())
        assertTrue(FoodMatcher.match(labels("Mountain" to 0.9, "Sky" to 0.8)).isEmpty())
    }

    @Test
    fun confidenceOrdersResultsAndRankDiscountsAlternatives() {
        val m = FoodMatcher.match(labels("Hamburger" to 0.9, "Banana" to 0.6))
        assertEquals("hamburger", m[0].food.id)
        assertEquals("banana", m[1].food.id)
        val tacos = FoodMatcher.match(labels("Taco" to 0.8))
        assertTrue(tacos[0].confidence > tacos[1].confidence)
    }

    @Test
    fun longerKeywordsBeatSubstrings() {
        // "ice cream" must not be read as "rice"; "sweet potato" is not plain "potato".
        assertEquals("ice_cream", FoodMatcher.match(labels("Ice cream" to 0.9)).first().food.id)
        assertEquals("sweet_potato", FoodMatcher.match(labels("Sweet potato" to 0.9)).first().food.id)
        assertEquals("hotdog", FoodMatcher.match(labels("Hot dog" to 0.9)).first().food.id)
    }

    @Test
    fun portionSizeScalesGrams() {
        val medium = FoodMatcher.match(labels("Pizza" to 0.9), size = PortionSize.MEDIUM).first()
        val large = FoodMatcher.match(labels("Pizza" to 0.9), size = PortionSize.LARGE).first()
        assertTrue(large.grams > medium.grams && large.macros.kcal > medium.macros.kcal)
        assertEquals(medium.food.servingG / 5 * 5, medium.grams)
    }

    @Test
    fun dietFilterRemovesIncompatibleSuggestions() {
        val m = FoodMatcher.match(labels("Meat" to 0.9, "Salad" to 0.7), DietPreferences(DietStyle.VEGAN))
        assertTrue(m.none { FoodFlag.MEAT in it.food.flags })
        assertTrue(m.any { it.food.id == "salad_mixed" })
    }

    @Test
    fun understandsSpanishLabelsToo() {
        assertEquals("banana", FoodMatcher.match(labels("Plátano" to 0.9)).first().food.id)
    }
}

class MealEstimateTest {
    @Test
    fun parsesCleanJson() {
        val meal = MealEstimates.parse(
            """{"items":[{"name":"Tacos al pastor","grams":180,"kcal":380,"protein_g":20,"carbs_g":36,"fat_g":16}],"confidence":0.7,"notes":"3 tacos"}""",
        )
        assertNotNull(meal)
        assertEquals(1, meal.items.size)
        assertEquals(380.0, meal.totals.kcal, 40.0)
        assertEquals(0.7, meal.confidence, 1e-9)
    }

    @Test
    fun toleratesCodeFencesAndChatter() {
        val reply = "Claro, aquí está:\n```json\n{\"items\":[{\"name\":\"Manzana\",\"grams\":150,\"kcal\":80,\"protein_g\":0.4,\"carbs_g\":21,\"fat_g\":0.3}],\"confidence\":0.9}\n```\nEspero que sirva."
        val meal = MealEstimates.parse(reply)
        assertNotNull(meal)
        assertEquals("Manzana", meal.items.single().name)
    }

    @Test
    fun rejectsGarbageAndEmptyMeals() {
        assertNull(MealEstimates.parse("no hay json aquí"))
        assertNull(MealEstimates.parse("{\"items\":[],\"confidence\":0,\"notes\":\"No veo comida\"}"))
        assertNull(MealEstimates.parse("{ esto no es json }"))
        assertNull(MealEstimates.parse(""))
    }

    @Test
    fun clampsAbsurdNumbersAndFixesInconsistentCalories() {
        val meal = MealEstimates.parse(
            """{"items":[
                {"name":"Locura","grams":99999,"kcal":900000,"protein_g":10,"carbs_g":10,"fat_g":10},
                {"name":"Sin kcal","grams":100,"kcal":0,"protein_g":20,"carbs_g":30,"fat_g":10},
                {"name":"Incoherente","grams":100,"kcal":1000,"protein_g":10,"carbs_g":20,"fat_g":5}
            ],"confidence":7}""",
        )
        assertNotNull(meal)
        assertTrue(meal.items.all { it.grams <= 2000 && it.kcal <= 3000 })
        assertEquals(1.0, meal.confidence)
        assertEquals(290.0, meal.items[1].kcal, 1.0) // derived from macros
        assertTrue(meal.items[2].kcal < 1000) // pulled toward what the macros imply
    }

    @Test
    fun capsTheNumberOfItemsAndDropsNameless() {
        val items = (1..30).joinToString(",") { """{"name":"Item $it","grams":10,"kcal":50,"protein_g":1,"carbs_g":10,"fat_g":1}""" }
        val meal = MealEstimates.parse("""{"items":[{"name":"","kcal":100},$items]}""")
        assertNotNull(meal)
        assertEquals(12, meal.items.size)
        assertTrue(meal.items.none { it.name.isBlank() })
    }

    @Test
    fun promptsMentionTheJsonContract() {
        assertTrue(MealEstimates.SYSTEM_PROMPT.contains("protein_g") && MealEstimates.SYSTEM_PROMPT.contains("ÚNICAMENTE"))
    }
}
