package app.gymquest.core.notifications

import app.gymquest.core.fitness.Hydration
import app.gymquest.core.nutrition.MealType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class NotificationType(val label: String, val defaultOn: Boolean, val priority: Int) {
    STREAK_AT_RISK("Tu racha está en riesgo", true, 100),
    STREAK_MILESTONE("Hitos de racha", true, 95),
    WORKOUT_REMINDER("Recordatorio de entreno", true, 90),
    CHALLENGE("Retos y competencias", true, 80),
    FRIEND_ACTIVITY("Actividad de amigos", true, 70),
    RANKING("Cambios en el ranking", true, 65),
    BOSS_UNLOCKED("Jefes nuevos", true, 60),
    ACHIEVEMENT("Logros", true, 55),
    SKINCARE("Skincare", true, 50),
    WATER("Agua", true, 40),
    MEAL_LOG("Registro de comidas", false, 35),
    WEIGH_IN("Pesarte", true, 30),
    WEEKLY_SUMMARY("Resumen semanal", true, 20),
}

data class NotificationPrefs(
    val enabled: Set<NotificationType> = NotificationType.entries.filter { it.defaultOn }.toSet(),
    val quietStart: LocalTime = LocalTime.of(22, 0),
    val quietEnd: LocalTime = LocalTime.of(7, 0),
    val workoutTime: LocalTime = LocalTime.of(18, 0),
    val trainingDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
    val wakeHour: Int = 7,
    val sleepHour: Int = 23,
    val streakWarningTime: LocalTime = LocalTime.of(20, 0),
    val maxPerDay: Int = 10,
) {
    fun allows(type: NotificationType) = type in enabled

    fun isQuiet(t: LocalTime): Boolean =
        if (quietStart <= quietEnd) t >= quietStart && t < quietEnd else t >= quietStart || t < quietEnd
}

/** A local notification to schedule at [time] on [date]. [id] is stable so re-planning replaces it. */
data class ScheduledNotification(
    val id: String,
    val type: NotificationType,
    val title: String,
    val body: String,
    val date: LocalDate,
    val time: LocalTime,
    /** In-app destination to open when tapped. */
    val deepLink: String,
)

/** What has happened so far today, used to skip reminders for things already done. */
data class DayState(
    val date: LocalDate,
    val hasActivityToday: Boolean,
    val streakDays: Int,
    val waterMl: Int,
    val waterTargetMl: Int,
    val skincareMorningDone: Boolean = false,
    val skincareEveningDone: Boolean = false,
    val mealsLogged: Set<MealType> = emptySet(),
    val weighInDue: Boolean = false,
)

/** Things that happen in the app (or to your friends) that deserve an immediate notification. */
sealed interface AppEvent {
    data class StreakExtended(val days: Int) : AppEvent
    data class FriendPassedYou(val friendName: String, val newRank: Int) : AppEvent
    data class FriendStreakMilestone(val friendName: String, val days: Int) : AppEvent
    data class FriendFinishedWorkout(val friendName: String, val summary: String) : AppEvent
    data class ChallengeEnding(val name: String, val hoursLeft: Int, val yourRank: Int) : AppEvent
    data class LevelUp(val level: Int, val newBossNames: List<String>) : AppEvent
    data class AchievementUnlocked(val name: String) : AppEvent
    data class RankImproved(val newRank: Int, val groupName: String) : AppEvent
}

object NotificationPlanner {
    private const val MAX_WATER_REMINDERS = 4

    /** [n] items spread evenly across [list], keeping the first and last. */
    private fun <T> spread(list: List<T>, n: Int): List<T> {
        if (list.size <= n) return list
        return (0 until n).map { i -> list[(i * (list.size - 1) / (n - 1))] }
    }

    private val milestones = setOf(3, 7, 14, 21, 30, 50, 75, 100, 150, 200, 365, 500, 1000)

    fun isStreakMilestone(days: Int): Boolean = days in milestones

    /**
     * Reminders still ahead of [now] for today's [state]. Call it again whenever the state changes
     * (you logged water, finished a workout) and replace the previous batch: ids are stable.
     */
    fun planDay(prefs: NotificationPrefs, state: DayState, now: LocalTime): List<ScheduledNotification> {
        val out = mutableListOf<ScheduledNotification>()
        val date = state.date

        fun add(type: NotificationType, key: String, title: String, body: String, time: LocalTime, link: String) {
            if (!prefs.allows(type)) return
            if (time.isBefore(now) || time == now) return
            // Never wake anyone: reminders that fall in quiet hours are dropped, not delayed.
            if (prefs.isQuiet(time)) return
            out += ScheduledNotification("${type.name}_${date}_$key", type, title, body, date, time, link)
        }

        // ---- Streak
        if (!state.hasActivityToday && state.streakDays >= 2) {
            val days = state.streakDays
            add(
                NotificationType.STREAK_AT_RISK, "evening",
                "Tu racha de $days días está en riesgo",
                "Aún no entrenas hoy. Con 5 minutos la salvas.",
                prefs.streakWarningTime, "app://workout",
            )
            if (days >= 7) {
                add(
                    NotificationType.STREAK_AT_RISK, "last_call",
                    "Última llamada para tu racha de $days días",
                    "Quedan pocas horas: haz una serie rápida y no pierdas todo lo que has construido.",
                    prefs.quietStart.minusMinutes(30), "app://workout",
                )
            }
        }

        // ---- Workout
        if (!state.hasActivityToday && date.dayOfWeek in prefs.trainingDays) {
            add(NotificationType.WORKOUT_REMINDER, "main", "Hora de entrenar", "Tienes entreno hoy. ¡Vamos por él!", prefs.workoutTime, "app://plan")
        }

        // ---- Skincare
        if (!state.skincareMorningDone) {
            add(NotificationType.SKINCARE, "am", "Skincare de la mañana", "Lava tu cara, hidrata y no olvides el protector solar.", LocalTime.of(prefs.wakeHour, 30), "app://skincare")
        }
        if (!state.skincareEveningDone) {
            val t = LocalTime.of(prefs.sleepHour, 0).minusMinutes(90)
            add(NotificationType.SKINCARE, "pm", "Skincare de la noche", "Quita el sudor y el protector, e hidrata antes de dormir.", t, "app://skincare")
        }

        // ---- Water: only while you are not ahead of schedule
        val waterTimes = spread(Hydration.reminderHours(prefs.wakeHour, prefs.sleepHour), MAX_WATER_REMINDERS)
        for (h in waterTimes) {
            val t = LocalTime.of(h, 0)
            val pace = Hydration.pace(state.waterMl, state.waterTargetMl, h.toDouble(), prefs.wakeHour, prefs.sleepHour)
            if (pace == Hydration.Pace.AHEAD) continue
            val left = ((state.waterTargetMl - state.waterMl) / Hydration.GLASS_ML.toDouble()).coerceAtLeast(0.0).toInt()
            if (left == 0) continue
            add(NotificationType.WATER, "h$h", "Hora de tomar agua", "Te faltan $left vasos para tu meta de hoy.", t, "app://water")
        }

        // ---- Meals
        val mealReminders = listOf(
            Triple(MealType.BREAKFAST, LocalTime.of(10, 0), "¿Ya desayunaste? Regístralo."),
            Triple(MealType.LUNCH, LocalTime.of(15, 0), "¿Qué comiste hoy? Toma una foto y calculamos las calorías."),
            Triple(MealType.DINNER, LocalTime.of(21, 0), "Registra tu cena para cerrar el día."),
        )
        for ((meal, time, text) in mealReminders) {
            if (meal !in state.mealsLogged) add(NotificationType.MEAL_LOG, meal.name, "Registro de ${meal.label.lowercase()}", text, time, "app://nutrition")
        }

        // ---- Weekly
        if (state.weighInDue && date.dayOfWeek == DayOfWeek.SUNDAY) {
            add(NotificationType.WEIGH_IN, "sunday", "Día de pesarte", "Regístralo en ayunas para ver tu tendencia semanal.", LocalTime.of(9, 0), "app://progress")
        }
        if (date.dayOfWeek == DayOfWeek.SUNDAY) {
            add(NotificationType.WEEKLY_SUMMARY, "sunday", "Tu resumen semanal", "Mira cómo te fue esta semana y qué sigue.", LocalTime.of(19, 30), "app://summary")
        }

        return out.sortedWith(compareBy<ScheduledNotification> { it.time }.thenByDescending { it.type.priority })
            .let { enforceDailyCap(it, prefs.maxPerDay) }
    }

    /** An immediate notification for an in-app event, or null if the user turned that type off. */
    fun onEvent(event: AppEvent, prefs: NotificationPrefs, now: LocalDateTimeLike): ScheduledNotification? {
        val (type, title, body, link) = when (event) {
            is AppEvent.StreakExtended ->
                if (!isStreakMilestone(event.days)) return null
                else Quad(NotificationType.STREAK_MILESTONE, "🔥 ¡${event.days} días de racha!", milestoneText(event.days), "app://profile")
            is AppEvent.FriendPassedYou ->
                Quad(NotificationType.RANKING, "${event.friendName} te pasó en el ranking", "Ahora vas en el lugar ${event.newRank}. ¡Recupéralo hoy!", "app://ranking")
            is AppEvent.FriendStreakMilestone ->
                Quad(NotificationType.FRIEND_ACTIVITY, "${event.friendName} lleva ${event.days} días de racha", "¿Le echas ganas tú también?", "app://friends")
            is AppEvent.FriendFinishedWorkout ->
                Quad(NotificationType.FRIEND_ACTIVITY, "${event.friendName} acaba de entrenar", event.summary, "app://friends")
            is AppEvent.ChallengeEnding ->
                Quad(NotificationType.CHALLENGE, "${event.name} termina en ${event.hoursLeft} h", "Vas en el lugar ${event.yourRank}. Aún estás a tiempo.", "app://challenges")
            is AppEvent.LevelUp -> Quad(
                NotificationType.BOSS_UNLOCKED, "¡Nivel ${event.level}!",
                if (event.newBossNames.isEmpty()) "Sigue así." else "Nuevos jefes: ${event.newBossNames.joinToString()}.",
                "app://bosses",
            )
            is AppEvent.AchievementUnlocked -> Quad(NotificationType.ACHIEVEMENT, "Logro desbloqueado", event.name, "app://achievements")
            is AppEvent.RankImproved ->
                Quad(NotificationType.RANKING, "Subiste al lugar ${event.newRank}", "En ${event.groupName}. ¡Sigue así!", "app://ranking")
        }
        if (!prefs.allows(type)) return null
        // Social pings respect quiet hours; the app will show them when the user opens it.
        if (prefs.isQuiet(now.time)) return null
        return ScheduledNotification("${type.name}_${now.date}_${title.hashCode()}", type, title, body, now.date, now.time, link)
    }

    private fun milestoneText(days: Int) = when {
        days >= 365 -> "Un año entero entrenando. Eres una leyenda."
        days >= 100 -> "Tres dígitos. Esto ya es un estilo de vida."
        days >= 30 -> "Un mes sin fallar. El hábito ya es tuyo."
        days >= 7 -> "Una semana completa. ¡No pares!"
        else -> "Buen comienzo: lo difícil es empezar."
    }

    /** Keeps the most important notifications when there are more than [max], preserving time order. */
    private fun enforceDailyCap(list: List<ScheduledNotification>, max: Int): List<ScheduledNotification> {
        if (list.size <= max) return list
        val keep = list.sortedByDescending { it.type.priority }.take(max).map { it.id }.toSet()
        return list.filter { it.id in keep }
    }

    private data class Quad(val type: NotificationType, val title: String, val body: String, val link: String)
}

/** Minimal date+time pair so the planner stays free of clocks (and trivially testable). */
data class LocalDateTimeLike(val date: LocalDate, val time: LocalTime)
