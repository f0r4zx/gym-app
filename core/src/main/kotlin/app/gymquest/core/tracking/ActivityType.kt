package app.gymquest.core.tracking

enum class ActivityType(
    val label: String,
    /** Fastest speed we believe is real for this activity; faster GPS jumps are discarded. */
    val maxSpeedMps: Double,
    /** Below this the person is considered stopped (auto-pause). */
    val stoppedBelowMps: Double,
    /** True when pace (min/km) is the natural unit; otherwise speed (km/h). */
    val usesPace: Boolean,
) {
    WALK("Caminata", 5.0, 0.4, true),
    RUN("Carrera", 11.0, 0.9, true),
    RIDE("Bicicleta", 28.0, 1.2, false),
    HIKE("Senderismo", 4.5, 0.3, true);

    /** Metabolic equivalent at a given speed (Compendium of Physical Activities, interpolated). */
    fun met(speedKmh: Double): Double = when (this) {
        WALK -> when {
            speedKmh < 2.7 -> 2.0
            speedKmh < 4.0 -> 2.8
            speedKmh < 4.8 -> 3.3
            speedKmh < 5.6 -> 3.8
            speedKmh < 6.4 -> 4.8
            speedKmh < 7.2 -> 6.0
            else -> 7.0
        }
        RUN -> interpolate(RUN_TABLE, speedKmh)
        RIDE -> when {
            speedKmh < 16.0 -> 4.0
            speedKmh < 19.3 -> 6.0
            speedKmh < 22.5 -> 8.0
            speedKmh < 25.7 -> 10.0
            speedKmh < 30.6 -> 12.0
            else -> 15.8
        }
        HIKE -> 6.0
    }

    /** Calories for [movingMs] of movement at an average [speedMps]. */
    fun calories(weightKg: Double, movingMs: Long, speedMps: Double): Double =
        met(speedMps * 3.6) * weightKg * (movingMs / 3_600_000.0)

    private companion object {
        val RUN_TABLE = listOf(
            6.4 to 6.0, 8.0 to 8.3, 9.7 to 9.8, 11.3 to 11.0, 12.9 to 11.8,
            14.5 to 12.8, 16.1 to 14.5, 17.7 to 16.0, 19.3 to 19.0,
        )

        fun interpolate(table: List<Pair<Double, Double>>, x: Double): Double {
            if (x <= table.first().first) return table.first().second
            if (x >= table.last().first) return table.last().second
            val i = table.indexOfFirst { it.first >= x }
            val (x0, y0) = table[i - 1]
            val (x1, y1) = table[i]
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
    }
}
