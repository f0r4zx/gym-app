package app.gymquest.core.nutrition

import app.gymquest.core.fitness.DietPreferences
import app.gymquest.core.nutrition.FoodCategory.DAIRY
import app.gymquest.core.nutrition.FoodCategory.DISH
import app.gymquest.core.nutrition.FoodCategory.DRINK
import app.gymquest.core.nutrition.FoodCategory.FAT
import app.gymquest.core.nutrition.FoodCategory.FRUIT
import app.gymquest.core.nutrition.FoodCategory.GRAIN
import app.gymquest.core.nutrition.FoodCategory.LEGUME
import app.gymquest.core.nutrition.FoodCategory.PROTEIN
import app.gymquest.core.nutrition.FoodCategory.SNACK
import app.gymquest.core.nutrition.FoodCategory.VEGETABLE
import app.gymquest.core.nutrition.FoodFlag.DAIRY as MILK
import app.gymquest.core.nutrition.FoodFlag.EGG
import app.gymquest.core.nutrition.FoodFlag.FISH
import app.gymquest.core.nutrition.FoodFlag.GLUTEN
import app.gymquest.core.nutrition.FoodFlag.HONEY
import app.gymquest.core.nutrition.FoodFlag.MEAT
import app.gymquest.core.nutrition.FoodFlag.NUTS
import app.gymquest.core.nutrition.MealType.BREAKFAST
import app.gymquest.core.nutrition.MealType.DINNER
import app.gymquest.core.nutrition.MealType.LUNCH
import app.gymquest.core.nutrition.MealType.SNACK as SNACK_MEAL

/**
 * Built-in food table. Values are per 100 g as eaten (cooked where it applies) and come from
 * standard composition tables (USDA / SMAE); they are close approximations, not lab values.
 */
object FoodDb {
    private val MAIN = setOf(LUNCH, DINNER)
    private val MORNING = setOf(BREAKFAST)
    private val LIGHT = setOf(BREAKFAST, SNACK_MEAL)
    private val SNACK_ONLY = setOf(SNACK_MEAL)
    private val ALL_DAY = MealType.entries.toSet()

    private fun f(
        id: String, name: String, cat: FoodCategory,
        kcal: Double, p: Double, c: Double, fat: Double, fiber: Double,
        servingG: Int, serving: String,
        flags: Set<FoodFlag> = emptySet(), meals: Set<MealType> = ALL_DAY,
    ) = Food(id, name, cat, Macros(kcal, p, c, fat, fiber), servingG, serving, flags, meals)

    val all: List<Food> = listOf(
        // ---- Proteínas
        f("chicken_breast", "Pechuga de pollo (cocida)", PROTEIN, 165.0, 31.0, 0.0, 3.6, 0.0, 120, "1 filete", setOf(MEAT), MAIN),
        f("chicken_thigh", "Muslo de pollo sin piel (cocido)", PROTEIN, 209.0, 26.0, 0.0, 10.9, 0.0, 100, "1 muslo", setOf(MEAT), MAIN),
        f("beef_lean", "Carne de res magra (cocida)", PROTEIN, 217.0, 26.0, 0.0, 12.0, 0.0, 120, "1 porción", setOf(MEAT), MAIN),
        f("beef_steak", "Bistec de res (cocido)", PROTEIN, 190.0, 29.0, 0.0, 8.0, 0.0, 120, "1 bistec", setOf(MEAT), MAIN),
        f("pork_loin", "Lomo de cerdo (cocido)", PROTEIN, 143.0, 26.0, 0.0, 3.5, 0.0, 120, "1 filete", setOf(MEAT), MAIN),
        f("turkey_breast", "Pechuga de pavo (cocida)", PROTEIN, 135.0, 30.0, 0.0, 1.0, 0.0, 100, "1 porción", setOf(MEAT), MAIN),
        f("turkey_ham", "Jamón de pavo", PROTEIN, 105.0, 17.0, 2.0, 3.0, 0.0, 40, "2 rebanadas", setOf(MEAT), LIGHT + LUNCH),
        f("salmon", "Salmón (cocido)", PROTEIN, 206.0, 22.0, 0.0, 12.0, 0.0, 120, "1 filete", setOf(FISH), MAIN),
        f("tilapia", "Tilapia (cocida)", PROTEIN, 128.0, 26.0, 0.0, 2.7, 0.0, 120, "1 filete", setOf(FISH), MAIN),
        f("tuna_can", "Atún en agua (lata)", PROTEIN, 116.0, 26.0, 0.0, 0.8, 0.0, 80, "1/2 lata", setOf(FISH), ALL_DAY),
        f("shrimp", "Camarón (cocido)", PROTEIN, 99.0, 24.0, 0.2, 0.3, 0.0, 100, "10 piezas", setOf(FISH), MAIN),
        f("egg", "Huevo entero (cocido)", PROTEIN, 155.0, 13.0, 1.1, 11.0, 0.0, 50, "1 huevo", setOf(EGG), ALL_DAY),
        f("egg_white", "Clara de huevo", PROTEIN, 52.0, 11.0, 0.7, 0.2, 0.0, 33, "1 clara", setOf(EGG), ALL_DAY),
        f("tofu", "Tofu firme", PROTEIN, 144.0, 17.0, 3.0, 9.0, 2.0, 120, "1 porción", emptySet(), ALL_DAY),
        f("tempeh", "Tempeh", PROTEIN, 193.0, 20.0, 9.0, 11.0, 0.0, 100, "1 porción", emptySet(), MAIN),
        f("whey", "Proteína en polvo (whey)", PROTEIN, 400.0, 80.0, 8.0, 6.0, 0.0, 30, "1 scoop", setOf(MILK), LIGHT),
        f("pea_protein", "Proteína vegetal en polvo", PROTEIN, 380.0, 78.0, 6.0, 6.0, 2.0, 30, "1 scoop", emptySet(), LIGHT),
        // ---- Lácteos
        f("greek_yogurt", "Yogur griego natural", DAIRY, 59.0, 10.0, 3.6, 0.4, 0.0, 170, "1 vaso", setOf(MILK), LIGHT),
        f("cottage", "Requesón", DAIRY, 98.0, 11.0, 3.4, 4.3, 0.0, 100, "1/2 taza", setOf(MILK), LIGHT + LUNCH),
        f("panela", "Queso panela", DAIRY, 240.0, 18.0, 3.0, 17.0, 0.0, 40, "2 rebanadas", setOf(MILK), ALL_DAY),
        f("milk_whole", "Leche entera", DAIRY, 61.0, 3.2, 4.8, 3.3, 0.0, 240, "1 vaso", setOf(MILK), LIGHT),
        f("milk_skim", "Leche descremada", DAIRY, 34.0, 3.4, 5.0, 0.1, 0.0, 240, "1 vaso", setOf(MILK), LIGHT),
        f("almond_milk", "Leche de almendra sin azúcar", DAIRY, 15.0, 0.6, 0.6, 1.2, 0.2, 240, "1 vaso", setOf(NUTS), LIGHT),
        f("soy_milk", "Leche de soya sin azúcar", DAIRY, 33.0, 2.9, 1.7, 1.8, 0.4, 240, "1 vaso", emptySet(), LIGHT),
        f("cheese_chihuahua", "Queso Chihuahua / manchego", DAIRY, 360.0, 25.0, 1.0, 28.0, 0.0, 30, "1 rebanada", setOf(MILK), ALL_DAY),
        // ---- Cereales y tubérculos
        f("rice_white", "Arroz blanco (cocido)", GRAIN, 130.0, 2.7, 28.0, 0.3, 0.4, 160, "3/4 taza", emptySet(), MAIN),
        f("rice_brown", "Arroz integral (cocido)", GRAIN, 123.0, 2.7, 25.6, 1.0, 1.8, 160, "3/4 taza", emptySet(), MAIN),
        f("pasta", "Pasta (cocida)", GRAIN, 158.0, 5.8, 31.0, 0.9, 1.8, 140, "1 taza", setOf(GLUTEN), MAIN),
        f("oats", "Avena (cruda)", GRAIN, 389.0, 16.9, 66.0, 6.9, 10.6, 40, "1/2 taza", setOf(GLUTEN), MORNING),
        f("tortilla_corn", "Tortilla de maíz", GRAIN, 218.0, 5.7, 44.6, 2.9, 6.3, 30, "1 tortilla", emptySet(), ALL_DAY),
        f("tortilla_flour", "Tortilla de harina", GRAIN, 297.0, 8.0, 49.0, 7.0, 2.5, 45, "1 tortilla", setOf(GLUTEN), ALL_DAY),
        f("bread_whole", "Pan integral", GRAIN, 247.0, 13.0, 41.0, 3.4, 7.0, 28, "1 rebanada", setOf(GLUTEN), ALL_DAY),
        f("bread_white", "Pan blanco", GRAIN, 265.0, 9.0, 49.0, 3.2, 2.7, 28, "1 rebanada", setOf(GLUTEN), ALL_DAY),
        f("potato", "Papa (cocida)", GRAIN, 87.0, 1.9, 20.0, 0.1, 1.8, 170, "1 papa mediana", emptySet(), MAIN),
        f("sweet_potato", "Camote (cocido)", GRAIN, 90.0, 2.0, 21.0, 0.2, 3.3, 150, "1 camote", emptySet(), MAIN),
        f("quinoa", "Quinoa (cocida)", GRAIN, 120.0, 4.4, 21.0, 1.9, 2.8, 150, "3/4 taza", emptySet(), MAIN),
        f("corn", "Elote (cocido)", GRAIN, 96.0, 3.4, 21.0, 1.5, 2.4, 130, "1 elote", emptySet(), ALL_DAY),
        f("cornflakes", "Cereal de hojuelas", GRAIN, 357.0, 7.5, 84.0, 0.4, 3.3, 30, "1 taza", setOf(GLUTEN), MORNING),
        f("granola", "Granola", GRAIN, 471.0, 10.0, 64.0, 20.0, 5.3, 30, "1/3 taza", setOf(GLUTEN, NUTS), LIGHT),
        f("rice_cake", "Galleta de arroz inflado", GRAIN, 387.0, 8.0, 82.0, 2.8, 4.2, 18, "2 piezas", emptySet(), LIGHT),
        // ---- Legumbres
        f("beans_black", "Frijoles negros (cocidos)", LEGUME, 132.0, 8.9, 23.7, 0.5, 8.7, 130, "3/4 taza", emptySet(), ALL_DAY),
        f("beans_pinto", "Frijoles pintos / bayos (cocidos)", LEGUME, 143.0, 9.0, 26.0, 0.7, 9.0, 130, "3/4 taza", emptySet(), ALL_DAY),
        f("lentils", "Lentejas (cocidas)", LEGUME, 116.0, 9.0, 20.0, 0.4, 7.9, 150, "3/4 taza", emptySet(), MAIN),
        f("chickpeas", "Garbanzos (cocidos)", LEGUME, 164.0, 8.9, 27.4, 2.6, 7.6, 130, "3/4 taza", emptySet(), MAIN),
        f("edamame", "Edamame", LEGUME, 121.0, 12.0, 9.0, 5.0, 5.2, 100, "1/2 taza", emptySet(), LIGHT + LUNCH),
        f("hummus", "Hummus", LEGUME, 166.0, 8.0, 14.0, 10.0, 6.0, 50, "3 cdas", emptySet(), SNACK_ONLY),
        // ---- Frutas
        f("banana", "Plátano", FRUIT, 89.0, 1.1, 22.8, 0.3, 2.6, 118, "1 pieza", emptySet(), LIGHT),
        f("apple", "Manzana", FRUIT, 52.0, 0.3, 13.8, 0.2, 2.4, 180, "1 pieza", emptySet(), LIGHT),
        f("orange", "Naranja", FRUIT, 47.0, 0.9, 11.8, 0.1, 2.4, 130, "1 pieza", emptySet(), LIGHT),
        f("strawberry", "Fresas", FRUIT, 32.0, 0.7, 7.7, 0.3, 2.0, 150, "1 taza", emptySet(), LIGHT),
        f("mango", "Mango", FRUIT, 60.0, 0.8, 15.0, 0.4, 1.6, 165, "1 taza", emptySet(), LIGHT),
        f("papaya", "Papaya", FRUIT, 43.0, 0.5, 11.0, 0.3, 1.7, 150, "1 taza", emptySet(), LIGHT),
        f("grapes", "Uvas", FRUIT, 69.0, 0.7, 18.0, 0.2, 0.9, 100, "15 uvas", emptySet(), LIGHT),
        f("watermelon", "Sandía", FRUIT, 30.0, 0.6, 7.6, 0.2, 0.4, 200, "1 taza", emptySet(), LIGHT),
        f("pineapple", "Piña", FRUIT, 50.0, 0.5, 13.0, 0.1, 1.4, 165, "1 taza", emptySet(), LIGHT),
        f("blueberry", "Arándanos", FRUIT, 57.0, 0.7, 14.5, 0.3, 2.4, 100, "3/4 taza", emptySet(), LIGHT),
        f("melon", "Melón", FRUIT, 34.0, 0.8, 8.2, 0.2, 0.9, 170, "1 taza", emptySet(), LIGHT),
        f("kiwi", "Kiwi", FRUIT, 61.0, 1.1, 14.7, 0.5, 3.0, 75, "1 pieza", emptySet(), LIGHT),
        f("pear", "Pera", FRUIT, 57.0, 0.4, 15.0, 0.1, 3.1, 170, "1 pieza", emptySet(), LIGHT),
        f("avocado", "Aguacate", FAT, 160.0, 2.0, 8.5, 14.7, 6.7, 70, "1/3 pieza", emptySet(), ALL_DAY),
        // ---- Verduras
        f("broccoli", "Brócoli (cocido)", VEGETABLE, 34.0, 2.8, 6.6, 0.4, 2.6, 100, "1 taza", emptySet(), MAIN),
        f("spinach", "Espinaca", VEGETABLE, 23.0, 2.9, 3.6, 0.4, 2.2, 60, "2 tazas", emptySet(), ALL_DAY),
        f("tomato", "Jitomate", VEGETABLE, 18.0, 0.9, 3.9, 0.2, 1.2, 120, "1 pieza", emptySet(), ALL_DAY),
        f("cucumber", "Pepino", VEGETABLE, 15.0, 0.7, 3.6, 0.1, 0.5, 120, "1/2 pieza", emptySet(), ALL_DAY),
        f("carrot", "Zanahoria", VEGETABLE, 41.0, 0.9, 9.6, 0.2, 2.8, 80, "1 pieza", emptySet(), ALL_DAY),
        f("lettuce", "Lechuga", VEGETABLE, 15.0, 1.4, 2.9, 0.2, 1.3, 70, "2 tazas", emptySet(), MAIN),
        f("zucchini", "Calabacita", VEGETABLE, 17.0, 1.2, 3.1, 0.3, 1.0, 120, "1 pieza", emptySet(), MAIN),
        f("mushroom", "Champiñones", VEGETABLE, 22.0, 3.1, 3.3, 0.3, 1.0, 100, "1 taza", emptySet(), MAIN),
        f("nopal", "Nopales (cocidos)", VEGETABLE, 16.0, 1.3, 3.3, 0.1, 2.2, 120, "1 taza", emptySet(), ALL_DAY),
        f("bell_pepper", "Pimiento", VEGETABLE, 31.0, 1.0, 6.0, 0.3, 2.1, 100, "1 pieza", emptySet(), ALL_DAY),
        f("cauliflower", "Coliflor (cocida)", VEGETABLE, 25.0, 1.9, 5.0, 0.3, 2.0, 100, "1 taza", emptySet(), MAIN),
        f("green_beans", "Ejotes (cocidos)", VEGETABLE, 31.0, 1.8, 7.0, 0.2, 3.4, 100, "1 taza", emptySet(), MAIN),
        f("chayote", "Chayote (cocido)", VEGETABLE, 19.0, 0.8, 4.5, 0.1, 1.7, 120, "1 pieza", emptySet(), MAIN),
        // ---- Grasas y semillas
        f("almonds", "Almendras", FAT, 579.0, 21.0, 22.0, 50.0, 12.5, 25, "20 piezas", setOf(NUTS), LIGHT),
        f("peanuts", "Cacahuates", FAT, 567.0, 26.0, 16.0, 49.0, 8.5, 25, "1/4 taza", setOf(NUTS), LIGHT),
        f("walnuts", "Nueces", FAT, 654.0, 15.0, 14.0, 65.0, 6.7, 25, "6 mitades", setOf(NUTS), LIGHT),
        f("peanut_butter", "Crema de cacahuate", FAT, 588.0, 25.0, 20.0, 50.0, 6.0, 16, "1 cda", setOf(NUTS), LIGHT),
        f("olive_oil", "Aceite de oliva", FAT, 884.0, 0.0, 0.0, 100.0, 0.0, 5, "1 cdita", emptySet(), MAIN),
        f("chia", "Semillas de chía", FAT, 486.0, 17.0, 42.0, 31.0, 34.0, 15, "1 cda", emptySet(), LIGHT),
        f("butter", "Mantequilla", FAT, 717.0, 0.9, 0.1, 81.0, 0.0, 5, "1 cdita", setOf(MILK), ALL_DAY),
        // ---- Platillos
        f("pizza", "Pizza (rebanada)", DISH, 266.0, 11.0, 33.0, 10.0, 2.3, 110, "1 rebanada", setOf(GLUTEN, MILK), MAIN),
        f("hamburger", "Hamburguesa con pan", DISH, 250.0, 13.0, 26.0, 10.0, 1.5, 200, "1 hamburguesa", setOf(MEAT, GLUTEN), MAIN),
        f("hotdog", "Hot dog", DISH, 247.0, 10.0, 18.0, 15.0, 0.8, 100, "1 pieza", setOf(MEAT, GLUTEN), MAIN),
        f("tacos_pastor", "Tacos al pastor", DISH, 210.0, 12.0, 20.0, 9.0, 2.0, 150, "3 tacos", setOf(MEAT), MAIN),
        f("tacos_carnitas", "Tacos de carnitas", DISH, 250.0, 14.0, 19.0, 13.0, 2.0, 150, "3 tacos", setOf(MEAT), MAIN),
        f("quesadilla", "Quesadilla", DISH, 290.0, 13.0, 25.0, 16.0, 2.0, 120, "1 quesadilla", setOf(MILK, GLUTEN), ALL_DAY),
        f("burrito", "Burrito", DISH, 206.0, 8.0, 29.0, 6.5, 3.0, 250, "1 burrito", setOf(MEAT, GLUTEN), MAIN),
        f("enchiladas", "Enchiladas", DISH, 170.0, 8.0, 17.0, 8.0, 2.0, 250, "3 enchiladas", setOf(MEAT, MILK), MAIN),
        f("chilaquiles", "Chilaquiles", DISH, 190.0, 6.0, 20.0, 9.5, 2.0, 250, "1 plato", setOf(MILK), ALL_DAY),
        f("tamal", "Tamal", DISH, 190.0, 5.0, 24.0, 8.5, 2.0, 130, "1 tamal", setOf(MEAT), ALL_DAY),
        f("pozole", "Pozole", DISH, 95.0, 8.0, 10.0, 2.5, 2.0, 400, "1 plato", setOf(MEAT), MAIN),
        f("sushi", "Sushi (rollo)", DISH, 145.0, 5.0, 30.0, 0.7, 1.0, 180, "6 piezas", setOf(FISH), MAIN),
        f("fries", "Papas fritas", DISH, 312.0, 3.4, 41.0, 15.0, 3.8, 120, "1 porción", emptySet(), MAIN),
        f("sandwich", "Sándwich", DISH, 230.0, 11.0, 28.0, 8.0, 2.0, 180, "1 sándwich", setOf(GLUTEN, MEAT), ALL_DAY),
        f("salad_mixed", "Ensalada mixta con aderezo", DISH, 70.0, 2.0, 7.0, 4.0, 2.0, 200, "1 plato", emptySet(), MAIN),
        f("caesar", "Ensalada César con pollo", DISH, 150.0, 12.0, 6.0, 8.0, 1.5, 250, "1 plato", setOf(MEAT, MILK, GLUTEN), MAIN),
        f("soup", "Sopa / caldo de verduras", DISH, 40.0, 2.0, 6.0, 1.0, 1.0, 300, "1 plato", emptySet(), MAIN),
        f("scrambled_eggs", "Huevos revueltos", DISH, 170.0, 11.0, 2.0, 13.0, 0.0, 120, "2 huevos", setOf(EGG), ALL_DAY),
        f("pasta_sauce", "Pasta con salsa", DISH, 160.0, 5.5, 28.0, 3.0, 2.0, 250, "1 plato", setOf(GLUTEN), MAIN),
        f("chicken_rice", "Arroz con pollo", DISH, 150.0, 11.0, 17.0, 4.0, 1.0, 300, "1 plato", setOf(MEAT), MAIN),
        f("pancakes", "Hot cakes", DISH, 227.0, 6.0, 28.0, 10.0, 1.0, 120, "3 piezas", setOf(GLUTEN, MILK, EGG), MORNING),
        // ---- Botanas, dulces y bebidas
        f("ice_cream", "Helado", SNACK, 207.0, 3.5, 24.0, 11.0, 0.7, 100, "1 bola", setOf(MILK), SNACK_ONLY),
        f("cake", "Pastel", SNACK, 350.0, 4.0, 55.0, 13.0, 1.0, 90, "1 rebanada", setOf(GLUTEN, MILK, EGG), SNACK_ONLY),
        f("donut", "Dona", SNACK, 400.0, 5.0, 46.0, 22.0, 1.5, 60, "1 dona", setOf(GLUTEN, MILK, EGG), SNACK_ONLY),
        f("cookies", "Galletas", SNACK, 450.0, 5.5, 68.0, 17.0, 2.0, 30, "3 galletas", setOf(GLUTEN, MILK), SNACK_ONLY),
        f("pan_dulce", "Pan dulce", SNACK, 380.0, 7.0, 58.0, 13.0, 1.5, 70, "1 pieza", setOf(GLUTEN, MILK, EGG), SNACK_ONLY),
        f("dark_chocolate", "Chocolate amargo 70%", SNACK, 598.0, 7.8, 46.0, 43.0, 11.0, 20, "2 cuadros", emptySet(), SNACK_ONLY),
        f("chips", "Papitas fritas (bolsa)", SNACK, 536.0, 7.0, 53.0, 35.0, 4.4, 30, "1 bolsita", emptySet(), SNACK_ONLY),
        f("honey", "Miel", SNACK, 304.0, 0.3, 82.0, 0.0, 0.2, 21, "1 cda", setOf(HONEY), ALL_DAY),
        f("soda", "Refresco", DRINK, 42.0, 0.0, 10.6, 0.0, 0.0, 355, "1 lata", emptySet(), SNACK_ONLY),
        f("orange_juice", "Jugo de naranja", DRINK, 45.0, 0.7, 10.4, 0.2, 0.2, 240, "1 vaso", emptySet(), LIGHT),
        f("beer", "Cerveza", DRINK, 43.0, 0.5, 3.6, 0.0, 0.0, 355, "1 botella", setOf(GLUTEN), SNACK_ONLY),
        f("coffee_milk", "Café con leche", DRINK, 40.0, 2.0, 4.0, 1.5, 0.0, 240, "1 taza", setOf(MILK), LIGHT),
        f("sports_drink", "Bebida deportiva", DRINK, 26.0, 0.0, 6.4, 0.0, 0.0, 500, "1 botella", emptySet(), ALL_DAY),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Food? = byId[id]

    fun search(query: String, diet: DietPreferences? = null, limit: Int = 25): List<Food> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        return all.asSequence()
            .filter { diet == null || it.fits(diet) }
            .map { it to score(normalize(it.name), q) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(name: String, q: String): Int = when {
        name == q -> 100
        name.startsWith(q) -> 80
        name.split(" ", "/", "(", ")").any { it.startsWith(q) } -> 60
        name.contains(q) -> 40
        else -> 0
    }

    fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase().trim(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

}
