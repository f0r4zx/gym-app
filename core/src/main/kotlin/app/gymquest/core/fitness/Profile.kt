package app.gymquest.core.fitness

import java.time.LocalDate
import java.time.Period

enum class Sex(val label: String) {
    MALE("Hombre"), FEMALE("Mujer"), OTHER("Prefiero no decirlo")
}

enum class Goal(val label: String, val description: String) {
    LOSE_FAT("Definir / bajar grasa", "Perder grasa conservando el músculo."),
    BUILD_MUSCLE("Aumentar volumen", "Ganar masa muscular con un pequeño superávit."),
    MAINTAIN("Mantenerme", "Mantener tu peso y tu forma actual."),
    ENDURANCE("Mejorar resistencia", "Correr, pedalear y aguantar más."),
    GENERAL_HEALTH("Salud general", "Moverte más y sentirte mejor."),
}

enum class ActivityLevel(val label: String, val factor: Double) {
    SEDENTARY("Sedentario (casi no me muevo)", 1.2),
    LIGHT("Ligero (1–3 días de ejercicio)", 1.375),
    MODERATE("Moderado (3–5 días)", 1.55),
    ACTIVE("Activo (6–7 días)", 1.725),
    VERY_ACTIVE("Muy activo (trabajo físico + entreno)", 1.9),
}

enum class Experience(val label: String) {
    BEGINNER("Principiante"), INTERMEDIATE("Intermedio"), ADVANCED("Avanzado")
}

enum class Equipment(val label: String) {
    BODYWEIGHT("Solo mi cuerpo"),
    DUMBBELLS("Mancuernas"),
    BARBELL("Barra y discos"),
    BENCH("Banco"),
    PULL_UP_BAR("Barra de dominadas"),
    MACHINES("Máquinas"),
    CABLES("Poleas"),
    BANDS("Bandas elásticas"),
    KETTLEBELL("Pesa rusa"),
}

enum class DietStyle(val label: String) {
    OMNIVORE("Como de todo"), VEGETARIAN("Vegetariano"), VEGAN("Vegano")
}

data class DietPreferences(
    val style: DietStyle = DietStyle.OMNIVORE,
    val glutenFree: Boolean = false,
    val lactoseFree: Boolean = false,
    val noNuts: Boolean = false,
)

data class UserProfile(
    val name: String,
    val birthDate: LocalDate,
    val sex: Sex,
    val heightCm: Double,
    val weightKg: Double,
    val goal: Goal,
    val activityLevel: ActivityLevel = ActivityLevel.LIGHT,
    val experience: Experience = Experience.BEGINNER,
    val daysPerWeek: Int = 3,
    val equipment: Set<Equipment> = setOf(Equipment.BODYWEIGHT),
    val targetWeightKg: Double? = null,
    val diet: DietPreferences = DietPreferences(),
    val restingHeartRate: Int? = null,
) {
    init {
        require(heightCm in 100.0..250.0) { "Estatura fuera de rango: $heightCm" }
        require(weightKg in 25.0..350.0) { "Peso fuera de rango: $weightKg" }
        require(daysPerWeek in 1..7) { "Días por semana: $daysPerWeek" }
    }

    fun ageOn(date: LocalDate): Int = Period.between(birthDate, date).years
}
