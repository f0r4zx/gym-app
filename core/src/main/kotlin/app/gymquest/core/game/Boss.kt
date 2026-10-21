package app.gymquest.core.game

import app.gymquest.core.exercise.ExerciseType

enum class BossTier(val label: String) { EASY("Fácil"), MEDIUM("Medio"), HARD("Difícil"), EPIC("Épico") }

/**
 * A monster you defeat with repetitions. Damage comes from reps of [exercises]; the boss hits
 * back if you rest longer than [attackIntervalMs].
 */
data class Boss(
    val id: String,
    val name: String,
    val title: String,
    val tier: BossTier,
    /** Reps of any of these hurt the boss. The final boss accepts several. */
    val exercises: List<ExerciseType>,
    val maxHp: Int,
    val attackIntervalMs: Long,
    val attackDamage: Int,
    val timeLimitMs: Long,
    val unlockLevel: Int,
    val xpReward: Int,
    /** Cosmetic item id dropped the first time you win (see avatar items in the app). */
    val lootId: String,
    val intro: String,
    val victoryLine: String,
) {
    val primaryExercise: ExerciseType get() = exercises.first()
}

object Bosses {
    val all: List<Boss> = listOf(
        Boss(
            "slime_squat", "Slime Sentadillas", "El viscoso del gimnasio", BossTier.EASY,
            listOf(ExerciseType.SQUAT), 150, 9_000, 8, 4 * 60_000L, 1, 80, "slime_helmet",
            "Un slime enorme bloquea la puerta. ¡Aplástalo con sentadillas profundas!",
            "El slime se derrite en un charco. ¡Piernas de acero!",
        ),
        Boss(
            "golem_pushup", "Gólem de Piedra", "Guardián de la cantera", BossTier.EASY,
            listOf(ExerciseType.PUSH_UP), 160, 9_000, 8, 4 * 60_000L, 1, 80, "stone_gloves",
            "El gólem levanta los puños. Respóndele con lagartijas.",
            "El gólem se desmorona. ¡Pecho de granito!",
        ),
        Boss(
            "bat_jacks", "Murciélago Saltarín", "Terror de la noche", BossTier.EASY,
            listOf(ExerciseType.JUMPING_JACK), 200, 7_000, 8, 4 * 60_000L, 2, 100, "bat_wings",
            "Revolotea sin parar. Abre y cierra con saltos de tijera.",
            "El murciélago cae noqueado. ¡Cardio de campeón!",
        ),
        Boss(
            "orc_curl", "Orco de Hierro", "Señor de las mancuernas", BossTier.MEDIUM,
            listOf(ExerciseType.BICEP_CURL), 220, 9_000, 10, 5 * 60_000L, 3, 130, "iron_bracers",
            "Sus bíceps son enormes. Supéralos con curls.",
            "El orco se rinde. ¡Brazos de acero!",
        ),
        Boss(
            "hydra_pullup", "Hidra de Dominadas", "Tres cabezas, una barra", BossTier.HARD,
            listOf(ExerciseType.PULL_UP), 180, 12_000, 12, 5 * 60_000L, 4, 180, "hydra_scale",
            "Cada dominada corta una cabeza. ¡Sube la barbilla por encima!",
            "La hidra se hunde. ¡Espalda de dragón!",
        ),
        Boss(
            "gargoyle_situp", "Gárgola Abdominal", "Vigía de la torre", BossTier.MEDIUM,
            listOf(ExerciseType.SIT_UP), 240, 9_000, 10, 5 * 60_000L, 4, 140, "gargoyle_belt",
            "Se aferra al muro. Rómpela con abdominales.",
            "La gárgola se hace polvo. ¡Core de acero!",
        ),
        Boss(
            "minotaur_lunge", "Minotauro del Laberinto", "Cornamenta y furia", BossTier.MEDIUM,
            listOf(ExerciseType.LUNGE), 260, 10_000, 12, 5 * 60_000L, 5, 150, "minotaur_horns",
            "Embiste sin parar. Esquívalo con zancadas.",
            "El minotauro cae de rodillas. ¡Zancadas legendarias!",
        ),
        Boss(
            "dragon_press", "Dragón del Hombro", "Guardián del pico", BossTier.HARD,
            listOf(ExerciseType.SHOULDER_PRESS), 280, 10_000, 12, 5 * 60_000L, 6, 190, "dragon_pauldrons",
            "Escupe fuego desde arriba. ¡Empuja hacia el cielo!",
            "El dragón se retira. ¡Hombros de titán!",
        ),
        Boss(
            "kraken_plank", "Kraken del Core", "Tentáculos del abismo", BossTier.HARD,
            listOf(ExerciseType.PLANK), 180, 6_000, 10, 4 * 60_000L, 6, 190, "kraken_tentacle",
            "Te atrapa con sus tentáculos. Aguanta la plancha: cada segundo cuenta.",
            "El kraken suelta su presa. ¡Abdomen de acero!",
        ),
        Boss(
            "phoenix_burpee", "Fénix Ardiente", "Renace de sus cenizas", BossTier.HARD,
            listOf(ExerciseType.BURPEE), 260, 14_000, 14, 6 * 60_000L, 7, 220, "phoenix_feather",
            "Arde y renace. Solo los burpees apagan su fuego.",
            "El fénix se apaga por fin. ¡Resistencia total!",
        ),
        Boss(
            "titan_deadlift", "Titán Levantador", "El que mueve montañas", BossTier.HARD,
            listOf(ExerciseType.DEADLIFT), 240, 12_000, 14, 6 * 60_000L, 8, 230, "titan_belt",
            "Levanta peñascos como si fueran plumas. Levanta más que él.",
            "El titán se inclina ante ti. ¡Fuerza bruta!",
        ),
        Boss(
            "rope_king", "Rey de la Cuerda", "Soberano del salto", BossTier.MEDIUM,
            listOf(ExerciseType.JUMP_ROPE), 600, 6_000, 8, 5 * 60_000L, 5, 160, "rope_crown",
            "Su cuerda silba en el aire. Salta sin tropezar.",
            "El rey pierde su corona. ¡Pies de pluma!",
        ),
        Boss(
            "titan_gym", "Titán del Gimnasio", "Jefe final", BossTier.EPIC,
            listOf(
                ExerciseType.SQUAT, ExerciseType.PUSH_UP, ExerciseType.LUNGE, ExerciseType.SIT_UP,
                ExerciseType.JUMPING_JACK, ExerciseType.BURPEE,
            ),
            900, 11_000, 15, 12 * 60_000L, 10, 600, "titan_crown",
            "Domina todos los ejercicios. Cambia entre ellos: la variedad le duele más.",
            "El Titán cae. Eres leyenda del gimnasio.",
        ),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Boss? = byId[id]

    fun forExercise(type: ExerciseType): List<Boss> = all.filter { type in it.exercises }

    fun unlockedAt(level: Int): List<Boss> = all.filter { it.unlockLevel <= level }
}
