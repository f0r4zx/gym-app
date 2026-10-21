package app.gymquest.core.sharing

import app.gymquest.core.fitness.GymCatalog
import app.gymquest.core.fitness.WeeklyPlan
import app.gymquest.core.social.Relation
import app.gymquest.core.social.Privacy
import app.gymquest.core.social.Visibility
import app.gymquest.core.tracking.ActivityType
import app.gymquest.core.wellness.Mood
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

@Serializable
data class SharedItem(val id: String, val sets: Int, val min: Int, val max: Int, val rest: Int)

@Serializable
data class SharedDay(val title: String, val items: List<SharedItem>)

@Serializable
data class SharedRoutine(val v: Int = 1, val name: String, val author: String? = null, val days: List<SharedDay>)

sealed interface DecodeResult {
    data class Ok(val routine: SharedRoutine, val warnings: List<String>) : DecodeResult
    data class Invalid(val reason: String) : DecodeResult
}

/**
 * Turns a routine into a short code (or link / QR) and back, so you can send "my leg day" to a friend
 * through any messenger. Decoding treats the code as untrusted input: it is size-limited, validated
 * and unknown exercises are dropped.
 */
object RoutineShare {
    const val VERSION = 1
    const val MAX_CODE_CHARS = 4_000
    private const val MAX_JSON_BYTES = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun fromPlan(plan: WeeklyPlan, name: String, author: String?): SharedRoutine = SharedRoutine(
        VERSION, clean(name, 40), author?.let { clean(it, 30) },
        plan.days.filter { it.items.isNotEmpty() }.map { d ->
            SharedDay(clean(d.title, 40), d.items.map { SharedItem(it.exercise.id, it.sets, it.minReps, it.maxReps, it.restSeconds) })
        },
    )

    fun encode(r: SharedRoutine): String {
        val raw = json.encodeToString(r).toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun decode(code: String): DecodeResult {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CODE_CHARS) return DecodeResult.Invalid("El código no es válido.")
        val compressed = runCatching { Base64.getUrlDecoder().decode(trimmed) }.getOrNull() ?: return DecodeResult.Invalid("El código no es válido.")
        val raw = inflate(compressed) ?: return DecodeResult.Invalid("El código está dañado o es demasiado grande.")
        val parsed = runCatching { json.decodeFromString<SharedRoutine>(raw.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return DecodeResult.Invalid("No pude leer la rutina.")
        if (parsed.v > VERSION) return DecodeResult.Invalid("Esta rutina es de una versión más nueva de la app. Actualiza para abrirla.")
        return validate(parsed)
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        return try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_JSON_BYTES) return null // zip-bomb guard
            }
            out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun validate(r: SharedRoutine): DecodeResult {
        val warnings = mutableListOf<String>()
        if (r.days.isEmpty() || r.days.size > 7) return DecodeResult.Invalid("La rutina debe tener entre 1 y 7 días.")
        val days = r.days.map { d ->
            val items = d.items.take(15).mapNotNull { item ->
                if (GymCatalog.byId(item.id) == null) {
                    warnings += "Ejercicio desconocido omitido: ${clean(item.id, 30)}"
                    return@mapNotNull null
                }
                SharedItem(item.id, item.sets.coerceIn(1, 10), item.min.coerceIn(1, 200), item.max.coerceIn(item.min.coerceIn(1, 200), 200), item.rest.coerceIn(0, 600))
            }
            SharedDay(clean(d.title, 40).ifBlank { "Día" }, items)
        }.filter { it.items.isNotEmpty() }
        if (days.isEmpty()) return DecodeResult.Invalid("La rutina no tiene ejercicios que esta versión conozca.")
        return DecodeResult.Ok(SharedRoutine(VERSION, clean(r.name, 40).ifBlank { "Rutina compartida" }, r.author?.let { clean(it, 30) }, days), warnings)
    }

    fun link(code: String, baseUrl: String = "gymquest://routine/"): String = baseUrl + code

    /** The code inside a gymquest:// or https link, or the input itself if it is already a bare code. */
    fun codeFromLink(link: String): String = link.trim().substringAfterLast('/')

    /** A readable version for pasting into WhatsApp or a note. */
    fun toText(r: SharedRoutine): String = buildString {
        appendLine("🏋️ ${r.name}" + (r.author?.let { " — por $it" } ?: ""))
        for (d in r.days) {
            appendLine()
            appendLine(d.title)
            for (i in d.items) {
                val name = GymCatalog.byId(i.id)?.name ?: i.id
                val reps = if (i.min == i.max) "${i.min}" else "${i.min}-${i.max}"
                appendLine("• $name — ${i.sets}×$reps, descanso ${i.rest}s")
            }
        }
    }.trimEnd()

    private fun clean(s: String, max: Int) = s.filter { !it.isISOControl() }.trim().take(max)
}

data class DiaryWorkout(val title: String, val minutes: Int, val kcal: Int, val points: Int = 0)

data class DiaryActivity(val type: ActivityType, val km: Double, val minutes: Int)

data class DiaryEntry(
    val date: LocalDate,
    val workouts: List<DiaryWorkout> = emptyList(),
    val activities: List<DiaryActivity> = emptyList(),
    val kcalEaten: Int? = null,
    val waterMl: Int? = null,
    val mood: Mood? = null,
    val sleepHours: Double? = null,
    val weightKg: Double? = null,
    val note: String = "",
) {
    val activeMinutes: Int get() = workouts.sumOf { it.minutes } + activities.sumOf { it.minutes }
    val km: Double get() = activities.sumOf { it.km }
    val isActive: Boolean get() = activeMinutes > 0
}

data class DayCell(
    /** Null for the blank squares that align the first week. */
    val date: LocalDate?,
    /** 0 = rest day .. 4 = very active; drives the heat-map colour. */
    val level: Int,
    val hasWorkout: Boolean,
    val hasActivity: Boolean,
    val inStreak: Boolean,
    val mood: Mood?,
)

data class CalendarMonth(
    val month: YearMonth,
    val cells: List<DayCell>,
    val activeDays: Int,
    val totalMinutes: Int,
    val totalKm: Double,
    val longestStreakInMonth: Int,
)

object DiaryCalendar {
    private val monthNames = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    fun level(minutes: Int): Int = when {
        minutes <= 0 -> 0
        minutes < 15 -> 1
        minutes < 30 -> 2
        minutes < 60 -> 3
        else -> 4
    }

    /** Monday-first month grid. Days that continue a streak of 2+ active days are flagged for the "flame" effect. */
    fun month(ym: YearMonth, entries: List<DiaryEntry>): CalendarMonth {
        val byDate = entries.groupBy { it.date }.mapValues { (_, list) ->
            DiaryEntry(
                list.first().date,
                list.flatMap { it.workouts }, list.flatMap { it.activities },
                mood = list.firstNotNullOfOrNull { it.mood },
            )
        }
        val first = ym.atDay(1)
        val lead = (first.dayOfWeek.value - DayOfWeek.MONDAY.value)
        val cells = mutableListOf<DayCell>()
        repeat(lead) { cells += DayCell(null, 0, false, false, false, null) }

        var streak = 0
        var longest = 0
        var activeDays = 0
        var minutes = 0
        var km = 0.0
        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            val e = byDate[date]
            val active = e?.isActive == true
            streak = if (active) streak + 1 else 0
            longest = maxOf(longest, streak)
            if (active) activeDays++
            minutes += e?.activeMinutes ?: 0
            km += e?.km ?: 0.0
            cells += DayCell(date, level(e?.activeMinutes ?: 0), e?.workouts?.isNotEmpty() == true, e?.activities?.isNotEmpty() == true, active && streak >= 2, e?.mood)
        }
        // Mark the first day of each streak too, so the whole run lights up.
        val marked = cells.toMutableList()
        for (i in 0 until marked.size - 1) {
            val c = marked[i]
            val next = marked[i + 1]
            if (c.date != null && c.level > 0 && !c.inStreak && next.inStreak) marked[i] = c.copy(inStreak = true)
        }
        return CalendarMonth(ym, marked, activeDays, minutes, km, longest)
    }

    fun summaryText(m: CalendarMonth): String {
        val name = monthNames[m.month.monthValue - 1].replaceFirstChar { it.uppercase() }
        val hours = m.totalMinutes / 60
        val mins = m.totalMinutes % 60
        return "$name ${m.month.year}: ${m.activeDays} días activos, ${hours} h ${mins} min de entrenamiento, " +
            "${"%.1f".format(m.totalKm)} km, mejor racha del mes: ${m.longestStreakInMonth} días."
    }
}

/** Decides what part of the diary a friend can see. Weight, food and notes are personal and hidden by default. */
object DiaryShare {
    fun visible(entries: List<DiaryEntry>, relation: Relation, visibility: Visibility, showBodyAndFood: Boolean = false): List<DiaryEntry> {
        if (!Privacy.canSee(visibility, relation)) return emptyList()
        if (relation == Relation.SELF) return entries
        return entries.map { e ->
            if (showBodyAndFood) e.copy(note = "") // notes are never shared
            else e.copy(weightKg = null, kcalEaten = null, waterMl = null, sleepHours = null, note = "")
        }
    }
}
