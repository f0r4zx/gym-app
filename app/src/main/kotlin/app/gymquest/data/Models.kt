package app.gymquest.data

import app.gymquest.core.fitness.ActivityLevel
import app.gymquest.core.fitness.DietPreferences
import app.gymquest.core.fitness.DietStyle
import app.gymquest.core.fitness.Equipment
import app.gymquest.core.fitness.Experience
import app.gymquest.core.fitness.Goal
import app.gymquest.core.fitness.Sex
import app.gymquest.core.fitness.UserProfile
import app.gymquest.core.nutrition.MealType
import app.gymquest.core.wellness.SkinType
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> runCatching { enumValueOf<E>(n) }.getOrNull() } ?: default

@Serializable
data class ProfileDto(
    val name: String,
    val birthEpochDay: Long,
    val sex: String,
    val heightCm: Double,
    val weightKg: Double,
    val goal: String,
    val activity: String,
    val experience: String,
    val daysPerWeek: Int,
    val equipment: List<String>,
    val targetWeightKg: Double? = null,
    val dietStyle: String = DietStyle.OMNIVORE.name,
    val glutenFree: Boolean = false,
    val lactoseFree: Boolean = false,
    val noNuts: Boolean = false,
    val skinType: String = SkinType.NORMAL.name,
    val acneProne: Boolean = false,
    val wakeHour: Int = 7,
    val sleepHour: Int = 23,
)

fun ProfileDto.toProfile(): UserProfile = UserProfile(
    name = name,
    birthDate = LocalDate.ofEpochDay(birthEpochDay),
    sex = enumOr(sex, Sex.OTHER),
    heightCm = heightCm.coerceIn(100.0, 250.0),
    weightKg = weightKg.coerceIn(25.0, 350.0),
    goal = enumOr(goal, Goal.GENERAL_HEALTH),
    activityLevel = enumOr(activity, ActivityLevel.LIGHT),
    experience = enumOr(experience, Experience.BEGINNER),
    daysPerWeek = daysPerWeek.coerceIn(1, 7),
    equipment = equipment.mapNotNull { runCatching { Equipment.valueOf(it) }.getOrNull() }.toSet().ifEmpty { setOf(Equipment.BODYWEIGHT) },
    targetWeightKg = targetWeightKg,
    diet = DietPreferences(enumOr(dietStyle, DietStyle.OMNIVORE), glutenFree, lactoseFree, noNuts),
)

@Serializable
data class FoodEntryDto(
    val name: String,
    val mealType: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val timeMs: Long,
    val foodId: String? = null,
    val grams: Double? = null,
) {
    fun meal(): MealType = enumOr(mealType, MealType.SNACK)
}

@Serializable
data class DayLogDto(
    val waterMl: Int = 0,
    val skincareSteps: List<String> = emptyList(),
    val faceWashes: Int = 0,
    val foods: List<FoodEntryDto> = emptyList(),
    val weightKg: Double? = null,
    val sleepStartMs: Long? = null,
    val sleepEndMs: Long? = null,
    val sleepAwakeMs: Long = 0,
    val sleepSource: String? = null,
    val activeMinutes: Int = 0,
    val workouts: Int = 0,
    val mood: String? = null,
    val note: String = "",
)

@Serializable
data class WeightDto(val epochDay: Long, val kg: Double)

@Serializable
data class WorkoutDto(
    val timeMs: Long,
    val exercise: String,
    val reps: Int,
    val avgQuality: Double,
    val durationMs: Long,
    val bossId: String? = null,
    val victory: Boolean? = null,
    val xp: Int = 0,
    val calories: Int = 0,
)

@Serializable
data class ActivityDto(
    val id: String,
    val timeMs: Long,
    val type: String,
    val distanceM: Double,
    val movingMs: Long,
    val elapsedMs: Long,
    val gainM: Double,
    val lossM: Double,
    val calories: Int,
    val polyline: String,
    val cellsCaptured: Int = 0,
)

@Serializable
data class OfflineRegionDto(val name: String, val south: Double, val west: Double, val north: Double, val east: Double, val minZoom: Int, val maxZoom: Int, val bytes: Long)

@Serializable
data class SettingsDto(
    val userId: String = UUID.randomUUID().toString(),
    val voice: String = "NORMAL",
    val strictness: String = "NORMAL",
    val useFrontCamera: Boolean = false,
    val showSkeleton: Boolean = true,
    val notificationTypes: List<String> = emptyList(),
    val bpmSync: Boolean = false,
    val restMusic: Boolean = true,
    val streakVisibility: String = "PUBLIC",
    val statsVisibility: String = "FRIENDS",
    val offlineRegions: List<OfflineRegionDto> = emptyList(),
    val onboardingDone: Boolean = false,
    val skinTone: String = "TONE_3",
    val hairStyle: String = "SHORT",
    val hairColor: Int = 0x2B1B12,
)

@Serializable
data class GameDto(
    val xp: Int = 0,
    val coins: Long = 50,
    val inventory: Map<String, Int> = emptyMap(),
    val equipped: Map<String, String> = emptyMap(),
    val bosses: Set<String> = emptySet(),
    val flawlessWins: Int = 0,
    val bestCombo: Int = 0,
    val repsByExercise: Map<String, Int> = emptyMap(),
    val totalKm: Double = 0.0,
    val longestRunKm: Double = 0.0,
    val longestRideKm: Double = 0.0,
    val achievements: Set<String> = emptySet(),
    val activeDays: Set<Long> = emptySet(),
    val statBuckets: Map<String, Map<Long, Double>> = emptyMap(),
    val waterGlasses: Int = 0,
    val skincareDays: Int = 0,
    val mealsLogged: Int = 0,
    val purchased: Set<String> = emptySet(),
    val territoryCells: Int = 0,
    val missionDay: String = "",
    val missionProgress: Map<String, Double> = emptyMap(),
    val missionClaimed: Set<String> = emptySet(),
    val missionBonusClaimed: Boolean = false,
    val missionStreak: Int = 0,
    val lastAllDoneDay: Long = -10,
    val weekKey: String = "",
    val weekProgress: Map<String, Double> = emptyMap(),
    val weekClaimed: Set<String> = emptySet(),
)

@Serializable
data class AppState(
    val profile: ProfileDto? = null,
    val settings: SettingsDto = SettingsDto(),
    val game: GameDto = GameDto(),
    val days: Map<Long, DayLogDto> = emptyMap(),
    val weights: List<WeightDto> = emptyList(),
    val workouts: List<WorkoutDto> = emptyList(),
    val activities: List<ActivityDto> = emptyList(),
) {
    fun day(epochDay: Long): DayLogDto = days[epochDay] ?: DayLogDto()
}
