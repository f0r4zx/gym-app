package app.gymquest.core.competition

import app.gymquest.core.exercise.ExerciseType

/** What a duel or guild war is fought over. */
enum class Discipline(val label: String, val exercise: ExerciseType?, val unit: String, val minRepIntervalMs: Long) {
    PUSH_UPS("Guerra de lagartijas", ExerciseType.PUSH_UP, "reps", 450),
    SQUATS("Guerra de sentadillas", ExerciseType.SQUAT, "reps", 600),
    JUMP_ROPE("Guerra de cuerda", ExerciseType.JUMP_ROPE, "saltos", 160),
    JUMPING_JACKS("Guerra de saltos de tijera", ExerciseType.JUMPING_JACK, "reps", 280),
    SIT_UPS("Guerra de abdominales", ExerciseType.SIT_UP, "reps", 500),
    PULL_UPS("Guerra de dominadas", ExerciseType.PULL_UP, "reps", 900),
    BURPEES("Guerra de burpees", ExerciseType.BURPEE, "reps", 1_500),
    PLANK("Guerra de plancha", ExerciseType.PLANK, "segundos", 900),
    ANY_REPS("Guerra de repeticiones", null, "puntos", 0),
    DISTANCE("Guerra de kilómetros", null, "km", 0),
    TERRITORY("Guerra de territorio", null, "zonas", 0);

    /** Duels are decided by camera-counted reps; distance and territory only make sense for wars. */
    val isDuelable: Boolean get() = exercise != null

    /** Whether reps of [type] score in this discipline. */
    fun accepts(type: ExerciseType): Boolean = exercise == type || this == ANY_REPS

    companion object {
        /** Relative difficulty so a mixed-exercise war is fair: a pull-up is worth more than a jump-rope hop. */
        fun weight(type: ExerciseType): Double = when (type) {
            ExerciseType.PULL_UP -> 3.0
            ExerciseType.BURPEE -> 2.0
            ExerciseType.PUSH_UP, ExerciseType.DEADLIFT -> 1.0
            ExerciseType.SQUAT, ExerciseType.SIT_UP, ExerciseType.LUNGE, ExerciseType.SHOULDER_PRESS, ExerciseType.BICEP_CURL,
            ExerciseType.TRICEPS_EXTENSION, ExerciseType.GLUTE_BRIDGE, ExerciseType.LATERAL_RAISE -> 0.8
            ExerciseType.JUMPING_JACK, ExerciseType.MOUNTAIN_CLIMBER, ExerciseType.HIGH_KNEES -> 0.4
            ExerciseType.JUMP_ROPE -> 0.25
            ExerciseType.PLANK, ExerciseType.WALL_SIT -> 0.3
        }

        /** Points for [reps] of [type] at average [quality]; sloppy reps (<0.4) do not score. */
        fun points(type: ExerciseType, reps: Int, quality: Double): Double =
            if (quality < MIN_SCORING_QUALITY) 0.0 else reps * quality * weight(type)

        const val MIN_SCORING_QUALITY = 0.4
    }
}

/** Standard Elo rating maths shared by guild wars. */
object Elo {
    fun expected(own: Int, opponent: Int): Double = 1.0 / (1.0 + Math.pow(10.0, (opponent - own) / 400.0))

    /** New rating after a game; [score] is 1 for a win, 0.5 for a draw and 0 for a loss. */
    fun update(own: Int, opponent: Int, score: Double, k: Int = 32): Int =
        Math.round(own + k * (score - expected(own, opponent))).toInt()
}
