package app.gymquest.core.exercise

import app.gymquest.core.pose.Joint

enum class ExerciseCategory(val label: String) {
    STRENGTH("Fuerza"),
    CARDIO("Cardio"),
    CORE("Abdomen"),
}

enum class MuscleGroup(val label: String) {
    CHEST("Pecho"), BACK("Espalda"), SHOULDERS("Hombros"), BICEPS("Bíceps"), TRICEPS("Tríceps"),
    CORE("Abdomen"), GLUTES("Glúteos"), QUADS("Cuádriceps"), HAMSTRINGS("Isquiotibiales"),
    CALVES("Pantorrillas"), FULL_BODY("Cuerpo completo"),
}

/** How the exercise is scored. */
enum class CountingMode { REPS, HOLD }

/** Where the phone should be placed for the camera to see the movement clearly. */
enum class ViewHint(val label: String) {
    SIDE("De lado"),
    FRONT("De frente"),
    ANY("Cualquier ángulo"),
}

/**
 * Every exercise the camera can count. [id] is stable and is what gets persisted/synced; never
 * reuse or rename one.
 */
enum class ExerciseType(
    val id: String,
    val displayName: String,
    val category: ExerciseCategory,
    val mode: CountingMode,
    val view: ViewHint,
    val muscles: List<MuscleGroup>,
    /** MET value from the Compendium of Physical Activities (approximate). */
    val met: Double,
    /** Typical seconds per rep (or 1.0 for holds, which are scored per second). */
    val secondsPerRep: Double,
    /** Joints that must be visible for the counter to work. */
    val requiredJoints: List<Joint>,
    val setupTip: String,
    /** Rough movement frequency in Hz; sets how open the smoothing filter must be. */
    val motionHz: Double = 0.4,
) {
    SQUAT(
        "squat", "Sentadillas", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.ANY,
        listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), 5.0, 3.0,
        listOf(Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE),
        "Pon el celular a 2–3 metros, apoyado a la altura de tu cintura. Se te debe ver de cuerpo completo.",
    ),
    PUSH_UP(
        "push_up", "Lagartijas", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.SIDE,
        listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS), 5.0, 2.5,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_ELBOW, Joint.RIGHT_ELBOW, Joint.LEFT_WRIST, Joint.RIGHT_WRIST, Joint.LEFT_HIP, Joint.RIGHT_HIP),
        "Pon el celular en el suelo a 2 metros, de lado a ti, para que se vea tu cuerpo completo.",
    ),
    PULL_UP(
        "pull_up", "Dominadas", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.ANY,
        listOf(MuscleGroup.BACK, MuscleGroup.BICEPS), 6.0, 3.5,
        listOf(Joint.NOSE, Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_ELBOW, Joint.RIGHT_ELBOW, Joint.LEFT_WRIST, Joint.RIGHT_WRIST),
        "Coloca el celular de frente a 2–3 metros; que se vean tus manos en la barra y tu cabeza.",
    ),
    LUNGE(
        "lunge", "Zancadas", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.SIDE,
        listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), 4.0, 3.0,
        listOf(Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE),
        "Pon el celular de lado a ti, a 2–3 metros, de cuerpo completo.",
    ),
    JUMPING_JACK(
        "jumping_jack", "Saltos de tijera", ExerciseCategory.CARDIO, CountingMode.REPS, ViewHint.FRONT,
        listOf(MuscleGroup.FULL_BODY), 8.0, 1.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_WRIST, Joint.RIGHT_WRIST, Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE),
        "Pon el celular de frente a 2.5–3 metros para que quepan tus brazos arriba y tus pies separados.",
        motionHz = 1.0,
    ),
    BICEP_CURL(
        "bicep_curl", "Curl de bíceps", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.ANY,
        listOf(MuscleGroup.BICEPS), 3.5, 3.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_ELBOW, Joint.RIGHT_ELBOW, Joint.LEFT_WRIST, Joint.RIGHT_WRIST),
        "Pon el celular de frente (o de lado) a 2 metros. Se debe ver de tus hombros a tus manos.",
    ),
    SHOULDER_PRESS(
        "shoulder_press", "Press de hombro", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.FRONT,
        listOf(MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS), 3.5, 3.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_ELBOW, Joint.RIGHT_ELBOW, Joint.LEFT_WRIST, Joint.RIGHT_WRIST),
        "Pon el celular de frente a 2.5 metros; deja espacio sobre tu cabeza para ver tus manos arriba.",
    ),
    LATERAL_RAISE(
        "lateral_raise", "Elevaciones laterales", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.FRONT,
        listOf(MuscleGroup.SHOULDERS), 3.0, 3.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_ELBOW, Joint.RIGHT_ELBOW, Joint.LEFT_HIP, Joint.RIGHT_HIP),
        "Pon el celular de frente a 2.5 metros.",
    ),
    TRICEPS_EXTENSION(
        "triceps_extension", "Extensión de tríceps", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.SIDE,
        listOf(MuscleGroup.TRICEPS), 3.5, 3.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_ELBOW, Joint.RIGHT_ELBOW, Joint.LEFT_WRIST, Joint.RIGHT_WRIST),
        "Pon el celular de lado a ti, a 2 metros.",
    ),
    SIT_UP(
        "sit_up", "Abdominales", ExerciseCategory.CORE, CountingMode.REPS, ViewHint.SIDE,
        listOf(MuscleGroup.CORE), 3.8, 2.5,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE),
        "Pon el celular en el suelo, de lado a ti, a 2 metros.",
    ),
    DEADLIFT(
        "deadlift", "Peso muerto", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.SIDE,
        listOf(MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.BACK), 6.0, 3.5,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE),
        "Pon el celular de lado a ti, a 2–3 metros, de cuerpo completo.",
    ),
    GLUTE_BRIDGE(
        "glute_bridge", "Puente de glúteos", ExerciseCategory.STRENGTH, CountingMode.REPS, ViewHint.SIDE,
        listOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), 3.5, 3.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE),
        "Pon el celular en el suelo, de lado a ti, a 2 metros.",
    ),
    MOUNTAIN_CLIMBER(
        "mountain_climber", "Escaladores", ExerciseCategory.CARDIO, CountingMode.REPS, ViewHint.SIDE,
        listOf(MuscleGroup.CORE, MuscleGroup.FULL_BODY), 8.0, 1.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE),
        "Pon el celular en el suelo, de lado a ti, a 2 metros.",
        motionHz = 2.0,
    ),
    HIGH_KNEES(
        "high_knees", "Rodillas arriba", ExerciseCategory.CARDIO, CountingMode.REPS, ViewHint.ANY,
        listOf(MuscleGroup.QUADS, MuscleGroup.CORE), 8.0, 0.6,
        listOf(Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE),
        "Pon el celular a 2–3 metros, de cuerpo completo.",
        motionHz = 2.0,
    ),
    BURPEE(
        "burpee", "Burpees", ExerciseCategory.CARDIO, CountingMode.REPS, ViewHint.SIDE,
        listOf(MuscleGroup.FULL_BODY), 8.0, 4.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE, Joint.LEFT_HIP, Joint.RIGHT_HIP),
        "Pon el celular de lado a ti, a 3 metros, para que quepas de pie y tumbado.",
    ),
    JUMP_ROPE(
        "jump_rope", "Saltar la cuerda", ExerciseCategory.CARDIO, CountingMode.REPS, ViewHint.ANY,
        listOf(MuscleGroup.CALVES, MuscleGroup.FULL_BODY), 11.0, 0.5,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE),
        "Pon el celular a 3 metros, de cuerpo completo; deja espacio sobre tu cabeza.",
        motionHz = 3.0,
    ),
    PLANK(
        "plank", "Plancha", ExerciseCategory.CORE, CountingMode.HOLD, ViewHint.SIDE,
        listOf(MuscleGroup.CORE, MuscleGroup.SHOULDERS), 3.0, 1.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE),
        "Pon el celular en el suelo, de lado a ti, a 2 metros. Cada segundo con buena postura cuenta.",
    ),
    WALL_SIT(
        "wall_sit", "Sentadilla isométrica", ExerciseCategory.STRENGTH, CountingMode.HOLD, ViewHint.SIDE,
        listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), 3.5, 1.0,
        listOf(Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE, Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE),
        "Pon el celular de lado a ti, a 2.5 metros, de cuerpo completo.",
    );

    /** Approximate kcal burned for [amount] reps (or seconds for holds) at [weightKg]. */
    fun caloriesFor(amount: Int, weightKg: Double): Double {
        val seconds = amount * secondsPerRep
        return met * weightKg * (seconds / 3600.0)
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): ExerciseType? = byId[id]
    }
}
