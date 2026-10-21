package app.gymquest.core.nutrition

import app.gymquest.core.fitness.DietPreferences

/** A label from an image classifier (ML Kit Image Labeling returns English names). */
data class ImageLabel(val text: String, val confidence: Double)

enum class PortionSize(val label: String, val factor: Double) {
    SMALL("Pequeña", 0.7), MEDIUM("Normal", 1.0), LARGE("Grande", 1.4), EXTRA("Extra grande", 1.8)
}

data class FoodMatch(val food: Food, val confidence: Double, val grams: Int) {
    val macros: Macros get() = food.macrosFor(grams.toDouble())
}

/**
 * Turns generic image labels ("Pizza", "Fast food", "Salad") into foods from the database so a
 * photo can pre-fill a meal log. This is a starting point the user confirms and adjusts, not a
 * calorie measurement: portions are estimated from typical servings and the chosen plate size.
 */
object FoodMatcher {

    /** Labels the classifier emits for almost every food photo; they carry no information. */
    private val generic = setOf(
        "food", "dish", "cuisine", "ingredient", "recipe", "tableware", "plate", "meal", "produce", "dishware",
        "cooking", "staple food", "comfort food", "finger food", "natural foods", "superfood", "serveware", "drink",
        "comida", "plato",
    )

    private val keywords: Map<String, List<String>> = mapOf(
        "pizza" to listOf("pizza"),
        "hamburger" to listOf("hamburger"), "burger" to listOf("hamburger"), "cheeseburger" to listOf("hamburger"),
        "hamburguesa" to listOf("hamburger"),
        "hot dog" to listOf("hotdog"), "sausage" to listOf("hotdog"),
        "taco" to listOf("tacos_pastor", "tacos_carnitas"),
        "burrito" to listOf("burrito"), "quesadilla" to listOf("quesadilla"), "enchilada" to listOf("enchiladas"),
        "tamale" to listOf("tamal"), "tamal" to listOf("tamal"),
        "chilaquiles" to listOf("chilaquiles"), "pozole" to listOf("pozole"),
        "sushi" to listOf("sushi"), "sashimi" to listOf("sushi"),
        "french fries" to listOf("fries"), "fries" to listOf("fries"), "papas fritas" to listOf("fries"), "fast food" to listOf("hamburger", "fries"),
        "sandwich" to listOf("sandwich"), "sándwich" to listOf("sandwich"), "torta" to listOf("sandwich"),
        "salad" to listOf("salad_mixed"), "ensalada" to listOf("salad_mixed"), "caesar" to listOf("caesar"),
        "soup" to listOf("soup"), "broth" to listOf("soup"), "sopa" to listOf("soup"), "stew" to listOf("soup"),
        "pasta" to listOf("pasta_sauce"), "spaghetti" to listOf("pasta_sauce"), "noodle" to listOf("pasta_sauce"), "lasagna" to listOf("pasta_sauce"),
        "scrambled" to listOf("scrambled_eggs"), "omelette" to listOf("scrambled_eggs"), "omelet" to listOf("scrambled_eggs"),
        "egg" to listOf("egg", "scrambled_eggs"), "huevo" to listOf("egg", "scrambled_eggs"),
        "pancake" to listOf("pancakes"), "waffle" to listOf("pancakes"), "hot cake" to listOf("pancakes"),
        "rice" to listOf("rice_white"), "arroz" to listOf("rice_white"), "fried rice" to listOf("chicken_rice"),
        "chicken" to listOf("chicken_breast", "chicken_rice"), "pollo" to listOf("chicken_breast"), "poultry" to listOf("chicken_breast"),
        "steak" to listOf("beef_steak"), "beef" to listOf("beef_steak"), "meat" to listOf("beef_steak", "chicken_breast", "pork_loin"), "carne" to listOf("beef_steak"),
        "pork" to listOf("pork_loin"), "turkey" to listOf("turkey_breast"),
        "salmon" to listOf("salmon"), "fish" to listOf("tilapia", "salmon"), "seafood" to listOf("shrimp", "salmon"), "shrimp" to listOf("shrimp"), "camarón" to listOf("shrimp"), "tuna" to listOf("tuna_can"),
        "tofu" to listOf("tofu"), "bean" to listOf("beans_black", "beans_pinto"), "frijol" to listOf("beans_black", "beans_pinto"),
        "lentil" to listOf("lentils"), "chickpea" to listOf("chickpeas"), "hummus" to listOf("hummus"),
        "bread" to listOf("bread_white", "bread_whole"), "toast" to listOf("bread_whole"), "baguette" to listOf("bread_white"), "pan" to listOf("bread_white"),
        "tortilla" to listOf("tortilla_corn"), "baked goods" to listOf("pan_dulce", "bread_white"), "pastry" to listOf("pan_dulce"),
        "oat" to listOf("oats"), "cereal" to listOf("cornflakes"), "granola" to listOf("granola"),
        "potato" to listOf("potato", "fries"), "papa" to listOf("potato"), "sweet potato" to listOf("sweet_potato"), "corn" to listOf("corn"), "elote" to listOf("corn"),
        "banana" to listOf("banana"), "plátano" to listOf("banana"), "apple" to listOf("apple"), "manzana" to listOf("apple"),
        "orange" to listOf("orange"), "naranja" to listOf("orange"), "strawberry" to listOf("strawberry"), "fresa" to listOf("strawberry"),
        "mango" to listOf("mango"), "papaya" to listOf("papaya"), "grape" to listOf("grapes"), "uva" to listOf("grapes"),
        "watermelon" to listOf("watermelon"), "sandía" to listOf("watermelon"), "pineapple" to listOf("pineapple"), "piña" to listOf("pineapple"),
        "blueberry" to listOf("blueberry"), "melon" to listOf("melon"), "kiwi" to listOf("kiwi"), "pear" to listOf("pear"),
        "avocado" to listOf("avocado"), "aguacate" to listOf("avocado"), "guacamole" to listOf("avocado"),
        "fruit" to listOf("apple", "banana", "orange", "strawberry"), "fruta" to listOf("apple", "banana", "orange"),
        "broccoli" to listOf("broccoli"), "brócoli" to listOf("broccoli"), "spinach" to listOf("spinach"), "tomato" to listOf("tomato"), "jitomate" to listOf("tomato"),
        "cucumber" to listOf("cucumber"), "carrot" to listOf("carrot"), "zanahoria" to listOf("carrot"), "lettuce" to listOf("lettuce"),
        "mushroom" to listOf("mushroom"), "nopal" to listOf("nopal"), "cactus" to listOf("nopal"), "bell pepper" to listOf("bell_pepper"), "cauliflower" to listOf("cauliflower"),
        "vegetable" to listOf("broccoli", "carrot", "tomato", "zucchini"), "verdura" to listOf("broccoli", "carrot", "zucchini"), "leaf vegetable" to listOf("spinach", "lettuce"),
        "almond" to listOf("almonds"), "peanut" to listOf("peanuts"), "walnut" to listOf("walnuts"), "nut" to listOf("almonds", "peanuts", "walnuts"),
        "yogurt" to listOf("greek_yogurt"), "yoghurt" to listOf("greek_yogurt"), "cheese" to listOf("cheese_chihuahua", "panela"), "queso" to listOf("panela", "cheese_chihuahua"),
        "milk" to listOf("milk_whole"), "leche" to listOf("milk_whole"),
        "ice cream" to listOf("ice_cream"), "helado" to listOf("ice_cream"), "gelato" to listOf("ice_cream"),
        "cake" to listOf("cake"), "pastel" to listOf("cake"), "cupcake" to listOf("cake"), "dessert" to listOf("cake", "ice_cream"),
        "donut" to listOf("donut"), "doughnut" to listOf("donut"), "cookie" to listOf("cookies"), "biscuit" to listOf("cookies"), "galleta" to listOf("cookies"),
        "chocolate" to listOf("dark_chocolate"), "candy" to listOf("cookies"), "chips" to listOf("chips"), "snack" to listOf("chips"), "junk food" to listOf("fries", "hamburger", "chips"),
        "soda" to listOf("soda"), "cola" to listOf("soda"), "juice" to listOf("orange_juice"), "jugo" to listOf("orange_juice"), "beer" to listOf("beer"), "cerveza" to listOf("beer"),
        "coffee" to listOf("coffee_milk"), "café" to listOf("coffee_milk"),
    )

    // Longer, more specific keywords win over short ones that happen to be substrings.
    private val orderedKeywords = keywords.keys.sortedByDescending { it.length }

    fun match(
        labels: List<ImageLabel>,
        diet: DietPreferences? = null,
        size: PortionSize = PortionSize.MEDIUM,
        limit: Int = 5,
    ): List<FoodMatch> {
        val scores = linkedMapOf<String, Double>()
        for (label in labels) {
            val text = FoodDb.normalize(label.text)
            if (text in generic) continue
            val key = orderedKeywords.firstOrNull { k ->
                val nk = FoodDb.normalize(k)
                text == nk || text.contains(nk) || (nk.length > 3 && nk.contains(text) && text.length > 3)
            } ?: continue
            val ids = keywords.getValue(key)
            ids.forEachIndexed { rank, id ->
                // The first food listed is the most typical one for the label.
                val c = label.confidence * (1.0 - 0.12 * rank)
                if (c > (scores[id] ?: 0.0)) scores[id] = c
            }
        }
        return scores.entries
            .mapNotNull { (id, c) -> FoodDb.byId(id)?.let { it to c } }
            .filter { (food, _) -> diet == null || food.fits(diet) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (food, c) -> FoodMatch(food, c, (food.servingG * size.factor / 5).toInt() * 5) }
    }
}
