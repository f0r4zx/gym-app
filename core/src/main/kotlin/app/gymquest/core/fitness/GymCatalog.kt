package app.gymquest.core.fitness

import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.exercise.MuscleGroup

/**
 * An exercise you can put in a routine and log sets for. If [tracked] is set, the camera can
 * also count it and fight bosses with it.
 */
enum class Impact { LOW, MODERATE, HIGH }

data class GymExercise(
    val id: String,
    val name: String,
    val primary: MuscleGroup,
    /** All of these must be available for the exercise to be offered. */
    val needs: Set<Equipment> = setOf(Equipment.BODYWEIGHT),
    val tracked: ExerciseType? = null,
    val compound: Boolean = false,
    val minLevel: Experience = Experience.BEGINNER,
    val timed: Boolean = false,
    val cardio: Boolean = false,
    /** Stress on joints (jumping, landing). Matters for older users and for knees/back issues. */
    val impact: Impact = Impact.LOW,
    /** Heavy barbell lifts that need good technique and, for teens, supervision. */
    val heavyLoad: Boolean = false,
    /** Especially good for balance and fall prevention. */
    val balance: Boolean = false,
)

object GymCatalog {
    private val BW = setOf(Equipment.BODYWEIGHT)
    private val DB = setOf(Equipment.DUMBBELLS)
    private val DB_BENCH = setOf(Equipment.DUMBBELLS, Equipment.BENCH)
    private val BB = setOf(Equipment.BARBELL)
    private val BB_BENCH = setOf(Equipment.BARBELL, Equipment.BENCH)

    private val base: List<GymExercise> = listOf(
        // ---- Chest
        GymExercise("pushup", "Lagartijas", MuscleGroup.CHEST, BW, ExerciseType.PUSH_UP, compound = true),
        GymExercise("bench_press", "Press de banca con barra", MuscleGroup.CHEST, BB_BENCH, compound = true),
        GymExercise("db_incline_press", "Press inclinado con mancuernas", MuscleGroup.CHEST, DB_BENCH, compound = true),
        GymExercise("db_floor_press", "Press en el suelo con mancuernas", MuscleGroup.CHEST, DB, compound = true),
        GymExercise("db_fly", "Aperturas con mancuernas", MuscleGroup.CHEST, DB_BENCH),
        GymExercise("cable_fly", "Cruce de poleas", MuscleGroup.CHEST, setOf(Equipment.CABLES)),
        GymExercise("chest_press_machine", "Press de pecho en máquina", MuscleGroup.CHEST, setOf(Equipment.MACHINES), compound = true),
        GymExercise("diamond_pushup", "Lagartijas diamante", MuscleGroup.CHEST, BW, ExerciseType.PUSH_UP, minLevel = Experience.INTERMEDIATE),
        // ---- Back
        GymExercise("pullup", "Dominadas", MuscleGroup.BACK, setOf(Equipment.PULL_UP_BAR), ExerciseType.PULL_UP, compound = true, minLevel = Experience.INTERMEDIATE),
        GymExercise("db_row", "Remo con mancuerna", MuscleGroup.BACK, DB, compound = true),
        GymExercise("barbell_row", "Remo con barra", MuscleGroup.BACK, BB, compound = true, minLevel = Experience.INTERMEDIATE),
        GymExercise("lat_pulldown", "Jalón al pecho", MuscleGroup.BACK, setOf(Equipment.CABLES), compound = true),
        GymExercise("seated_row", "Remo en polea", MuscleGroup.BACK, setOf(Equipment.CABLES), compound = true),
        GymExercise("band_row", "Remo con banda", MuscleGroup.BACK, setOf(Equipment.BANDS), compound = true),
        GymExercise("deadlift", "Peso muerto", MuscleGroup.BACK, BB, ExerciseType.DEADLIFT, compound = true, minLevel = Experience.INTERMEDIATE),
        GymExercise("superman", "Superman", MuscleGroup.BACK, BW, timed = true),
        // ---- Shoulders
        GymExercise("db_shoulder_press", "Press de hombro con mancuernas", MuscleGroup.SHOULDERS, DB, ExerciseType.SHOULDER_PRESS, compound = true),
        GymExercise("ohp", "Press militar con barra", MuscleGroup.SHOULDERS, BB, compound = true, minLevel = Experience.INTERMEDIATE),
        GymExercise("lateral_raise", "Elevaciones laterales", MuscleGroup.SHOULDERS, DB, ExerciseType.LATERAL_RAISE),
        GymExercise("rear_delt_fly", "Pájaros (deltoides posterior)", MuscleGroup.SHOULDERS, DB),
        GymExercise("face_pull", "Face pull", MuscleGroup.SHOULDERS, setOf(Equipment.CABLES)),
        GymExercise("pike_pushup", "Lagartijas en pica", MuscleGroup.SHOULDERS, BW, compound = true, minLevel = Experience.INTERMEDIATE),
        // ---- Biceps
        GymExercise("db_curl", "Curl con mancuernas", MuscleGroup.BICEPS, DB, ExerciseType.BICEP_CURL),
        GymExercise("hammer_curl", "Curl martillo", MuscleGroup.BICEPS, DB, ExerciseType.BICEP_CURL),
        GymExercise("barbell_curl", "Curl con barra", MuscleGroup.BICEPS, BB),
        GymExercise("band_curl", "Curl con banda", MuscleGroup.BICEPS, setOf(Equipment.BANDS)),
        GymExercise("cable_curl", "Curl en polea", MuscleGroup.BICEPS, setOf(Equipment.CABLES)),
        // ---- Triceps
        GymExercise("db_overhead_ext", "Extensión de tríceps sobre la cabeza", MuscleGroup.TRICEPS, DB, ExerciseType.TRICEPS_EXTENSION),
        GymExercise("bench_dip", "Fondos en banco", MuscleGroup.TRICEPS, setOf(Equipment.BENCH)),
        GymExercise("pushdown", "Jalón de tríceps en polea", MuscleGroup.TRICEPS, setOf(Equipment.CABLES)),
        GymExercise("close_grip_bench", "Press cerrado", MuscleGroup.TRICEPS, BB_BENCH, compound = true),
        GymExercise("kickback", "Patada de tríceps", MuscleGroup.TRICEPS, DB),
        // ---- Legs
        GymExercise("squat", "Sentadillas", MuscleGroup.QUADS, BW, ExerciseType.SQUAT, compound = true),
        GymExercise("goblet_squat", "Sentadilla goblet", MuscleGroup.QUADS, DB, ExerciseType.SQUAT, compound = true),
        GymExercise("back_squat", "Sentadilla con barra", MuscleGroup.QUADS, BB, ExerciseType.SQUAT, compound = true, minLevel = Experience.INTERMEDIATE),
        GymExercise("lunge", "Zancadas", MuscleGroup.QUADS, BW, ExerciseType.LUNGE, compound = true),
        GymExercise("db_lunge", "Zancadas con mancuernas", MuscleGroup.QUADS, DB, ExerciseType.LUNGE, compound = true),
        GymExercise("bulgarian", "Sentadilla búlgara", MuscleGroup.QUADS, BW, compound = true, minLevel = Experience.INTERMEDIATE),
        GymExercise("leg_press", "Prensa de piernas", MuscleGroup.QUADS, setOf(Equipment.MACHINES), compound = true),
        GymExercise("leg_extension", "Extensión de cuádriceps", MuscleGroup.QUADS, setOf(Equipment.MACHINES)),
        GymExercise("rdl", "Peso muerto rumano", MuscleGroup.HAMSTRINGS, DB, compound = true),
        GymExercise("barbell_rdl", "Peso muerto rumano con barra", MuscleGroup.HAMSTRINGS, BB, compound = true, minLevel = Experience.INTERMEDIATE),
        GymExercise("leg_curl", "Curl femoral", MuscleGroup.HAMSTRINGS, setOf(Equipment.MACHINES)),
        GymExercise("glute_bridge", "Puente de glúteos", MuscleGroup.GLUTES, BW, ExerciseType.GLUTE_BRIDGE),
        GymExercise("hip_thrust", "Hip thrust con barra", MuscleGroup.GLUTES, BB_BENCH, compound = true),
        GymExercise("calf_raise", "Elevación de talones", MuscleGroup.CALVES, BW),
        GymExercise("wall_sit", "Sentadilla isométrica", MuscleGroup.QUADS, BW, ExerciseType.WALL_SIT, timed = true),
        // ---- Core
        GymExercise("plank", "Plancha", MuscleGroup.CORE, BW, ExerciseType.PLANK, timed = true),
        GymExercise("situp", "Abdominales", MuscleGroup.CORE, BW, ExerciseType.SIT_UP),
        GymExercise("leg_raise", "Elevación de piernas", MuscleGroup.CORE, BW),
        GymExercise("russian_twist", "Giros rusos", MuscleGroup.CORE, BW),
        GymExercise("dead_bug", "Dead bug", MuscleGroup.CORE, BW),
        GymExercise("mountain_climber", "Escaladores", MuscleGroup.CORE, BW, ExerciseType.MOUNTAIN_CLIMBER, cardio = true),
        // ---- Low-impact, balance and beginner-friendly
        GymExercise("wall_pushup", "Lagartijas en la pared", MuscleGroup.CHEST, BW),
        GymExercise("step_up", "Subir un escalón", MuscleGroup.QUADS, BW, compound = true),
        GymExercise("single_leg_stand", "Equilibrio en un pie", MuscleGroup.CORE, BW, timed = true, balance = true),
        GymExercise("march", "Marcha en el lugar", MuscleGroup.FULL_BODY, BW, cardio = true),
        // ---- Cardio
        GymExercise("jumping_jack", "Saltos de tijera", MuscleGroup.FULL_BODY, BW, ExerciseType.JUMPING_JACK, cardio = true),
        GymExercise("burpee", "Burpees", MuscleGroup.FULL_BODY, BW, ExerciseType.BURPEE, cardio = true, minLevel = Experience.INTERMEDIATE),
        GymExercise("jump_rope", "Saltar la cuerda", MuscleGroup.CALVES, BW, ExerciseType.JUMP_ROPE, cardio = true),
        GymExercise("high_knees", "Rodillas arriba", MuscleGroup.QUADS, BW, ExerciseType.HIGH_KNEES, cardio = true),
    )

    private val highImpact = setOf("jumping_jack", "burpee", "jump_rope")
    private val moderateImpact = setOf("high_knees", "mountain_climber", "bulgarian", "lunge", "db_lunge", "step_up")
    private val heavy = setOf("bench_press", "back_squat", "deadlift", "barbell_row", "ohp", "barbell_rdl", "hip_thrust", "close_grip_bench")
    private val balanceFriendly = setOf("single_leg_stand", "step_up", "squat", "lunge", "calf_raise", "march", "glute_bridge")

    val all: List<GymExercise> = base.map { ex ->
        ex.copy(
            impact = when (ex.id) {
                in highImpact -> Impact.HIGH
                in moderateImpact -> Impact.MODERATE
                else -> Impact.LOW
            },
            heavyLoad = ex.id in heavy,
            balance = ex.balance || ex.id in balanceFriendly,
        )
    }

    private val byId = all.associateBy { it.id }

    fun byId(id: String): GymExercise? = byId[id]

    fun forMuscle(muscle: MuscleGroup): List<GymExercise> = all.filter { it.primary == muscle }

    fun available(equipment: Set<Equipment>, level: Experience): List<GymExercise> =
        all.filter { ex -> level.ordinal >= ex.minLevel.ordinal && ex.needs.all { it in equipment || it == Equipment.BODYWEIGHT } }
}
