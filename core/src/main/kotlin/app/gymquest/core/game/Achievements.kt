package app.gymquest.core.game

import app.gymquest.core.exercise.ExerciseType

/** Lifetime counters that achievements are measured against. */
data class PlayerStats(
    val repsByExercise: Map<ExerciseType, Int> = emptyMap(),
    val totalKm: Double = 0.0,
    val longestRunKm: Double = 0.0,
    val longestRideKm: Double = 0.0,
    val bossesDefeated: Set<String> = emptySet(),
    val flawlessWins: Int = 0,
    val bestCombo: Int = 0,
    val longestStreak: Int = 0,
    val currentStreak: Int = 0,
    val workouts: Int = 0,
    val waterGlasses: Int = 0,
    val skincareDays: Int = 0,
    val mealsLogged: Int = 0,
    val friends: Int = 0,
    val level: Int = 1,
) {
    val totalReps: Int get() = repsByExercise.values.sum()
    fun reps(type: ExerciseType): Int = repsByExercise[type] ?: 0
}

data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val xp: Int,
    val target: Double,
    val metric: (PlayerStats) -> Double,
) {
    fun current(stats: PlayerStats): Double = metric(stats)
    fun fraction(stats: PlayerStats): Double = (metric(stats) / target).coerceIn(0.0, 1.0)
    fun isMet(stats: PlayerStats): Boolean = metric(stats) >= target
}

object Achievements {
    val all: List<Achievement> = listOf(
        Achievement("first_rep", "Primer paso", "Haz tu primera repetición con la cámara.", 10, 1.0) { it.totalReps.toDouble() },
        Achievement("reps_100", "Calentando motores", "100 repeticiones en total.", 30, 100.0) { it.totalReps.toDouble() },
        Achievement("reps_1000", "Mil y contando", "1,000 repeticiones en total.", 100, 1_000.0) { it.totalReps.toDouble() },
        Achievement("reps_10000", "Máquina", "10,000 repeticiones en total.", 400, 10_000.0) { it.totalReps.toDouble() },
        Achievement("squat_500", "Rey de las sentadillas", "500 sentadillas.", 120, 500.0) { it.reps(ExerciseType.SQUAT).toDouble() },
        Achievement("pushup_500", "Pecho de acero", "500 lagartijas.", 120, 500.0) { it.reps(ExerciseType.PUSH_UP).toDouble() },
        Achievement("pullup_100", "Espalda de dragón", "100 dominadas.", 150, 100.0) { it.reps(ExerciseType.PULL_UP).toDouble() },
        Achievement("situp_500", "Abdomen de hierro", "500 abdominales.", 120, 500.0) { it.reps(ExerciseType.SIT_UP).toDouble() },
        Achievement("plank_600", "Roca inmóvil", "10 minutos de plancha acumulados.", 120, 600.0) { it.reps(ExerciseType.PLANK).toDouble() },
        Achievement("boss_first", "Cazador de monstruos", "Derrota a tu primer jefe.", 50, 1.0) { it.bossesDefeated.size.toDouble() },
        Achievement("boss_5", "Matadragones", "Derrota a 5 jefes distintos.", 150, 5.0) { it.bossesDefeated.size.toDouble() },
        Achievement("boss_all", "Leyenda del gimnasio", "Derrota a todos los jefes.", 500, Bosses.all.size.toDouble()) { it.bossesDefeated.size.toDouble() },
        Achievement("flawless_3", "Intocable", "Gana 3 combates sin recibir daño.", 120, 3.0) { it.flawlessWins.toDouble() },
        Achievement("combo_20", "Combo imparable", "Encadena 20 repeticiones perfectas seguidas.", 100, 20.0) { it.bestCombo.toDouble() },
        Achievement("streak_7", "Una semana de fuego", "7 días seguidos entrenando.", 100, 7.0) { it.longestStreak.toDouble() },
        Achievement("streak_30", "Hábito de hierro", "30 días seguidos entrenando.", 400, 30.0) { it.longestStreak.toDouble() },
        Achievement("streak_100", "Imparable", "100 días seguidos entrenando.", 1_500, 100.0) { it.longestStreak.toDouble() },
        Achievement("km_10", "Primeros kilómetros", "10 km acumulados al aire libre.", 40, 10.0) { it.totalKm },
        Achievement("km_100", "Explorador", "100 km acumulados al aire libre.", 200, 100.0) { it.totalKm },
        Achievement("km_1000", "Trotamundos", "1,000 km acumulados al aire libre.", 1_000, 1_000.0) { it.totalKm },
        Achievement("run_5k", "Corredor de 5K", "Corre 5 km en una sola salida.", 120, 5.0) { it.longestRunKm },
        Achievement("run_10k", "Corredor de 10K", "Corre 10 km en una sola salida.", 250, 10.0) { it.longestRunKm },
        Achievement("ride_50k", "Ciclista de fondo", "Pedalea 50 km en una sola salida.", 250, 50.0) { it.longestRideKm },
        Achievement("water_100", "Bien hidratado", "Registra 100 vasos de agua.", 80, 100.0) { it.waterGlasses.toDouble() },
        Achievement("skincare_30", "Piel de campeón", "30 días de rutina de skincare.", 150, 30.0) { it.skincareDays.toDouble() },
        Achievement("meals_100", "Cuenta claras", "Registra 100 comidas.", 120, 100.0) { it.mealsLogged.toDouble() },
        Achievement("friends_5", "Equipo", "Ten 5 amigos en tu grupo.", 80, 5.0) { it.friends.toDouble() },
        Achievement("level_10", "Atleta", "Llega al nivel 10.", 100, 10.0) { it.level.toDouble() },
        Achievement("level_25", "Guerrero", "Llega al nivel 25.", 300, 25.0) { it.level.toDouble() },
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Achievement? = byId[id]

    /** Achievements that [stats] satisfy and are not in [unlocked] yet. */
    fun newlyUnlocked(stats: PlayerStats, unlocked: Set<String>): List<Achievement> =
        all.filter { it.id !in unlocked && it.isMet(stats) }
}
