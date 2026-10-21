package app.gymquest.core.missions

import app.gymquest.core.exercise.ExerciseType
import app.gymquest.core.fitness.AgeBand
import app.gymquest.core.fitness.Equipment
import app.gymquest.core.fitness.Experience
import app.gymquest.core.tracking.ActivityType
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.Random
import kotlin.math.roundToInt

enum class MissionCategory(val label: String) { FITNESS("Entrenamiento"), CARDIO("Cardio"), WELLNESS("Bienestar"), SOCIAL("Social"), BONUS("Bonus") }

enum class MissionKind {
    REPS_ANY, REPS_EXERCISE, DISTANCE_KM, ACTIVE_MINUTES, WATER_GLASSES, SKINCARE_ROUTINE, MEALS_LOGGED,
    BOSS_WINS, FLAWLESS_BOSS, TERRITORY_CELLS, DUELS_PLAYED, WORKOUTS, SLEEP_LOGGED, WEIGH_IN,
}

data class Mission(
    val id: String,
    val kind: MissionKind,
    val category: MissionCategory,
    val title: String,
    val description: String,
    val target: Int,
    val xp: Int,
    val coins: Int,
    val exercise: ExerciseType? = null,
)

/** Things that happen while playing; the board turns them into mission progress. */
sealed interface GameEvent {
    data class Reps(val type: ExerciseType, val count: Int, val avgQuality: Double) : GameEvent
    data class Distance(val activity: ActivityType, val meters: Double) : GameEvent
    data class ActiveMinutes(val minutes: Int) : GameEvent
    data class Water(val glasses: Int) : GameEvent
    data object SkincareDone : GameEvent
    data object MealLogged : GameEvent
    data class BossWin(val bossId: String, val flawless: Boolean) : GameEvent
    data class CellsCaptured(val cells: Int) : GameEvent
    data object DuelPlayed : GameEvent
    data object WorkoutFinished : GameEvent
    data object SleepLogged : GameEvent
    data object WeighedIn : GameEvent
}

/** What the generator needs to know to hand out missions that make sense for this person. */
data class MissionContext(
    val level: Int,
    val age: Int,
    val experience: Experience,
    val equipment: Set<Equipment>,
)

private class Template(
    val key: String,
    val kind: MissionKind,
    val category: MissionCategory,
    val title: (Int) -> String,
    val description: String,
    val base: Int,
    val exercise: ExerciseType? = null,
    val minLevel: Int = 1,
    val needs: Equipment? = null,
    val highImpact: Boolean = false,
)

object DailyMissions {
    private val templates = listOf(
        Template("reps_any", MissionKind.REPS_ANY, MissionCategory.FITNESS, { "Haz $it repeticiones con la cámara" }, "Cualquier ejercicio cuenta.", 30),
        Template("squat", MissionKind.REPS_EXERCISE, MissionCategory.FITNESS, { "$it sentadillas" }, "Con la cámara, baja con control.", 25, ExerciseType.SQUAT),
        Template("pushup", MissionKind.REPS_EXERCISE, MissionCategory.FITNESS, { "$it lagartijas" }, "Pecho al suelo, cuerpo recto.", 15, ExerciseType.PUSH_UP),
        Template("situp", MissionKind.REPS_EXERCISE, MissionCategory.FITNESS, { "$it abdominales" }, "Sube con control.", 20, ExerciseType.SIT_UP),
        Template("lunge", MissionKind.REPS_EXERCISE, MissionCategory.FITNESS, { "$it zancadas" }, "Alterna las piernas.", 20, ExerciseType.LUNGE),
        Template("curl", MissionKind.REPS_EXERCISE, MissionCategory.FITNESS, { "$it curls de bíceps" }, "Con mancuernas.", 20, ExerciseType.BICEP_CURL, needs = Equipment.DUMBBELLS),
        Template("press", MissionKind.REPS_EXERCISE, MissionCategory.FITNESS, { "$it press de hombro" }, "Con mancuernas.", 15, ExerciseType.SHOULDER_PRESS, needs = Equipment.DUMBBELLS),
        Template("pullup", MissionKind.REPS_EXERCISE, MissionCategory.FITNESS, { "$it dominadas" }, "Barbilla sobre la barra.", 6, ExerciseType.PULL_UP, minLevel = 3, needs = Equipment.PULL_UP_BAR),
        Template("plank", MissionKind.REPS_EXERCISE, MissionCategory.FITNESS, { "Aguanta $it segundos de plancha" }, "Cuerpo en línea recta.", 45, ExerciseType.PLANK),
        Template("jacks", MissionKind.REPS_EXERCISE, MissionCategory.CARDIO, { "$it saltos de tijera" }, "De frente a la cámara.", 40, ExerciseType.JUMPING_JACK, highImpact = true),
        Template("rope", MissionKind.REPS_EXERCISE, MissionCategory.CARDIO, { "$it saltos de cuerda" }, "La cámara cuenta cada salto.", 100, ExerciseType.JUMP_ROPE, minLevel = 2, highImpact = true),
        Template("burpee", MissionKind.REPS_EXERCISE, MissionCategory.CARDIO, { "$it burpees" }, "De lado a la cámara.", 10, ExerciseType.BURPEE, minLevel = 4, highImpact = true),
        Template("walk", MissionKind.DISTANCE_KM, MissionCategory.CARDIO, { "Camina o corre $it km" }, "Registra tu salida con el GPS.", 2),
        Template("active", MissionKind.ACTIVE_MINUTES, MissionCategory.CARDIO, { "$it minutos de actividad" }, "Cualquier movimiento registrado.", 30),
        Template("cells", MissionKind.TERRITORY_CELLS, MissionCategory.CARDIO, { "Conquista $it zonas" }, "Cierra un circuito caminando.", 10, minLevel = 4),
        Template("water", MissionKind.WATER_GLASSES, MissionCategory.WELLNESS, { "Toma $it vasos de agua" }, "Registra cada vaso.", 8),
        Template("skin", MissionKind.SKINCARE_ROUTINE, MissionCategory.WELLNESS, { "Completa tu rutina de skincare" }, "Mañana y noche.", 1),
        Template("meals", MissionKind.MEALS_LOGGED, MissionCategory.WELLNESS, { "Registra $it comidas" }, "Foto o búsqueda.", 3),
        Template("sleep", MissionKind.SLEEP_LOGGED, MissionCategory.WELLNESS, { "Registra tu sueño de anoche" }, "Duerme lo que tu cuerpo necesita.", 1),
        Template("boss", MissionKind.BOSS_WINS, MissionCategory.BONUS, { "Derrota a $it jefe" }, "Pelea con repeticiones.", 1),
        Template("flawless", MissionKind.FLAWLESS_BOSS, MissionCategory.BONUS, { "Gana un combate sin recibir daño" }, "No descanses demasiado.", 1, minLevel = 3),
        Template("duel", MissionKind.DUELS_PLAYED, MissionCategory.SOCIAL, { "Juega un duelo" }, "Reta a un amigo.", 1, minLevel = 5),
        Template("workout", MissionKind.WORKOUTS, MissionCategory.BONUS, { "Termina un entrenamiento" }, "Una sesión completa.", 1),
    )

    fun generate(userId: String, date: LocalDate, ctx: MissionContext): List<Mission> {
        val rnd = Random(userId.hashCode().toLong() * 31 + date.toEpochDay())
        val allowed = templates.filter { allowed(it, ctx) }

        fun pick(category: MissionCategory, exclude: Set<String>): Template? =
            allowed.filter { it.category == category && it.key !in exclude }.let { if (it.isEmpty()) null else it[rnd.nextInt(it.size)] }

        val chosen = mutableListOf<Template>()
        for (cat in listOf(MissionCategory.FITNESS, MissionCategory.CARDIO, MissionCategory.WELLNESS)) {
            pick(cat, chosen.map { it.key }.toSet())?.let { chosen += it }
        }
        val bonusPool = allowed.filter { it.category == MissionCategory.BONUS || it.category == MissionCategory.SOCIAL }.filter { it.key !in chosen.map { c -> c.key } }
        if (bonusPool.isNotEmpty()) chosen += bonusPool[rnd.nextInt(bonusPool.size)]

        return chosen.mapIndexed { i, t -> build(t, ctx, "d${date.toEpochDay()}-$i", daily = true, bonus = i == 3) }
    }

    private fun allowed(t: Template, ctx: MissionContext): Boolean {
        if (ctx.level < t.minLevel) return false
        if (t.needs != null && t.needs !in ctx.equipment) return false
        val band = AgeBand.of(ctx.age)
        if (t.highImpact && (band == AgeBand.SENIOR || band == AgeBand.MATURE)) return false
        if (t.key == "pullup" && band == AgeBand.SENIOR) return false
        return true
    }

    private fun build(t: Template, ctx: MissionContext, id: String, daily: Boolean, bonus: Boolean): Mission {
        val target = scaled(t, ctx, daily)
        val tier = 1.0 + (if (bonus) 0.6 else 0.0)
        val xp = ((30 + ctx.level * 2).coerceAtMost(120) * tier).roundToInt()
        val coins = ((15 + ctx.level).coerceAtMost(60) * tier).roundToInt()
        return Mission(id, t.kind, t.category, t.title(target), t.description, target, if (daily) xp else xp * 4, if (daily) coins else coins * 4, t.exercise)
    }

    private fun scaled(t: Template, ctx: MissionContext, daily: Boolean): Int {
        var f = 1.0 + 0.025 * (ctx.level - 1)
        f = f.coerceAtMost(2.5)
        f *= when (ctx.experience) {
            Experience.BEGINNER -> 0.8
            Experience.INTERMEDIATE -> 1.0
            Experience.ADVANCED -> 1.2
        }
        f *= when (AgeBand.of(ctx.age)) {
            AgeBand.SENIOR -> 0.7
            AgeBand.MATURE -> 0.85
            AgeBand.TEEN -> 0.9
            else -> 1.0
        }
        // Counters that are not scaled by effort (one boss, one routine) stay as they are.
        val fixed = t.kind in setOf(MissionKind.SKINCARE_ROUTINE, MissionKind.SLEEP_LOGGED, MissionKind.FLAWLESS_BOSS, MissionKind.DUELS_PLAYED, MissionKind.WORKOUTS, MissionKind.WEIGH_IN)
        if (fixed) return t.base
        if (t.kind == MissionKind.WATER_GLASSES || t.kind == MissionKind.MEALS_LOGGED || t.kind == MissionKind.BOSS_WINS) return t.base
        val raw = t.base * f
        return when {
            t.kind == MissionKind.DISTANCE_KM -> (raw * 2).roundToInt().coerceAtLeast(2) / 2 // half-km steps
            raw >= 20 -> (raw / 5).roundToInt() * 5
            else -> raw.roundToInt().coerceAtLeast(1)
        }.coerceAtLeast(1)
    }

    /** Four weekly goals with bigger rewards. */
    fun generateWeekly(date: LocalDate, ctx: MissionContext): List<Mission> {
        val week = "${date.get(IsoFields.WEEK_BASED_YEAR)}-W${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        fun t(key: String, kind: MissionKind, cat: MissionCategory, base: Int, title: (Int) -> String, desc: String, minLevel: Int = 1) =
            Template(key, kind, cat, title, desc, base, minLevel = minLevel)
        val list = mutableListOf(
            t("w_boss", MissionKind.BOSS_WINS, MissionCategory.FITNESS, 3, { "Derrota a $it jefes" }, "Esta semana."),
            t("w_workouts", MissionKind.WORKOUTS, MissionCategory.FITNESS, if (ctx.experience == Experience.BEGINNER) 3 else 4, { "Termina $it entrenamientos" }, "Constancia."),
            t("w_km", MissionKind.DISTANCE_KM, MissionCategory.CARDIO, 10, { "Recorre $it km" }, "Caminando, corriendo o en bici."),
        )
        if (ctx.level >= 4) list += t("w_cells", MissionKind.TERRITORY_CELLS, MissionCategory.CARDIO, 40, { "Conquista $it zonas" }, "Cierra circuitos.")
        else list += t("w_water", MissionKind.WATER_GLASSES, MissionCategory.WELLNESS, 40, { "Toma $it vasos de agua" }, "Mantente hidratado.")
        return list.mapIndexed { i, tpl ->
            val m = build(tpl, ctx, "w$week-$i", daily = false, bonus = false)
            // Weekly targets are fixed numbers; only the rewards scale.
            m.copy(target = tpl.base, title = tpl.title(tpl.base))
        }
    }

    fun weekKey(date: LocalDate) = "${date.get(IsoFields.WEEK_BASED_YEAR)}-W${date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
}

data class MissionProgress(val mission: Mission, val progress: Double = 0.0, val claimed: Boolean = false) {
    val complete: Boolean get() = progress >= mission.target
    val fraction: Double get() = (progress / mission.target).coerceIn(0.0, 1.0)
}

data class MissionReward(val xp: Int, val coins: Int)

/** One period's missions (a day or a week) and how far along each one is. */
data class MissionBoard(
    val periodKey: String,
    val missions: List<MissionProgress>,
    val bonusClaimed: Boolean = false,
) {
    val allComplete: Boolean get() = missions.isNotEmpty() && missions.all { it.complete }
    val completedCount: Int get() = missions.count { it.complete }

    fun apply(event: GameEvent): MissionBoard =
        copy(missions = missions.map { p -> if (p.complete) p else p.copy(progress = p.progress + gain(p.mission, event)) })

    private fun gain(m: Mission, e: GameEvent): Double = when (m.kind) {
        MissionKind.REPS_ANY -> (e as? GameEvent.Reps)?.takeIf { it.avgQuality >= MIN_QUALITY }?.count?.toDouble() ?: 0.0
        MissionKind.REPS_EXERCISE -> (e as? GameEvent.Reps)?.takeIf { it.type == m.exercise && it.avgQuality >= MIN_QUALITY }?.count?.toDouble() ?: 0.0
        MissionKind.DISTANCE_KM -> (e as? GameEvent.Distance)?.let { it.meters / 1000.0 * (if (it.activity == ActivityType.RIDE) 0.4 else 1.0) } ?: 0.0
        MissionKind.ACTIVE_MINUTES -> (e as? GameEvent.ActiveMinutes)?.minutes?.toDouble() ?: 0.0
        MissionKind.WATER_GLASSES -> (e as? GameEvent.Water)?.glasses?.toDouble() ?: 0.0
        MissionKind.SKINCARE_ROUTINE -> if (e is GameEvent.SkincareDone) 1.0 else 0.0
        MissionKind.MEALS_LOGGED -> if (e is GameEvent.MealLogged) 1.0 else 0.0
        MissionKind.BOSS_WINS -> if (e is GameEvent.BossWin) 1.0 else 0.0
        MissionKind.FLAWLESS_BOSS -> if (e is GameEvent.BossWin && e.flawless) 1.0 else 0.0
        MissionKind.TERRITORY_CELLS -> (e as? GameEvent.CellsCaptured)?.cells?.toDouble() ?: 0.0
        MissionKind.DUELS_PLAYED -> if (e is GameEvent.DuelPlayed) 1.0 else 0.0
        MissionKind.WORKOUTS -> if (e is GameEvent.WorkoutFinished) 1.0 else 0.0
        MissionKind.SLEEP_LOGGED -> if (e is GameEvent.SleepLogged) 1.0 else 0.0
        MissionKind.WEIGH_IN -> if (e is GameEvent.WeighedIn) 1.0 else 0.0
    }

    /** Pays out a completed mission once. [multiplier] comes from the daily-completion streak and the economy policy. */
    fun claim(missionId: String, multiplier: Double = 1.0): Pair<MissionBoard, MissionReward?> {
        val p = missions.firstOrNull { it.mission.id == missionId } ?: return this to null
        if (!p.complete || p.claimed) return this to null
        val reward = MissionReward(p.mission.xp, (p.mission.coins * multiplier).roundToInt())
        return copy(missions = missions.map { if (it.mission.id == missionId) it.copy(claimed = true) else it }) to reward
    }

    /** A chest for finishing everything in the period. */
    fun claimBonus(multiplier: Double = 1.0): Pair<MissionBoard, MissionReward?> {
        if (!allComplete || bonusClaimed || missions.any { !it.claimed }) return this to null
        val xp = missions.sumOf { it.mission.xp } / 2
        val coins = (missions.sumOf { it.mission.coins } / 2.0 * multiplier).roundToInt()
        return copy(bonusClaimed = true) to MissionReward(xp, coins)
    }

    companion object {
        const val MIN_QUALITY = 0.4

        fun daily(userId: String, date: LocalDate, ctx: MissionContext): MissionBoard =
            MissionBoard(date.toString(), DailyMissions.generate(userId, date, ctx).map { MissionProgress(it) })

        fun weekly(date: LocalDate, ctx: MissionContext): MissionBoard =
            MissionBoard(DailyMissions.weekKey(date), DailyMissions.generateWeekly(date, ctx).map { MissionProgress(it) })
    }
}

object MissionStreak {
    /** Completing every daily mission on consecutive days boosts coin rewards: +10% per day, up to +50%. */
    fun multiplier(consecutiveDays: Int): Double = 1.0 + (consecutiveDays.coerceAtLeast(0) * 0.1).coerceAtMost(0.5)
}
