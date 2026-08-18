package com.digitalfuelgauge

data class FuelInterval(
    val fromMs: Long,
    val toMs: Long,
    val days: Double,
    val km: Double,
    val litersAdded: Double
)

data class FuelStats(
    val fillCount: Int,
    val fullFillCount: Int,
    val partialFillCount: Int,
    val daysSinceLastFill: Double?,
    val avgDaysBetween: Double?,
    val lastDaysBetween: Double?,
    val minDaysBetween: Double?,
    val maxDaysBetween: Double?,
    val avgKmBetween: Double?,
    val lastKmBetween: Double?,
    val minKmBetween: Double?,
    val maxKmBetween: Double?,
    val totalKm: Double?,
    val totalLiters: Double,
    val avgLiters: Double?,
    val intervalsNewestFirst: List<FuelInterval>
) {
    companion object {
        fun compute(
            entries: List<FuelLogEntry>,
            nowMs: Long = System.currentTimeMillis()
        ): FuelStats {
            if (entries.isEmpty()) {
                return FuelStats(
                    fillCount = 0,
                    fullFillCount = 0,
                    partialFillCount = 0,
                    daysSinceLastFill = null,
                    avgDaysBetween = null,
                    lastDaysBetween = null,
                    minDaysBetween = null,
                    maxDaysBetween = null,
                    avgKmBetween = null,
                    lastKmBetween = null,
                    minKmBetween = null,
                    maxKmBetween = null,
                    totalKm = null,
                    totalLiters = 0.0,
                    avgLiters = null,
                    intervalsNewestFirst = emptyList()
                )
            }

            val chronological = entries.sortedBy { it.timestampMs }
            val intervals = ArrayList<FuelInterval>(chronological.size.coerceAtLeast(1) - 1)
            for (i in 1 until chronological.size) {
                val previous = chronological[i - 1]
                val next = chronological[i]
                val deltaMs = next.timestampMs - previous.timestampMs
                if (deltaMs <= 0L) continue
                val km = next.odometerKm - previous.odometerKm
                intervals.add(
                    FuelInterval(
                        fromMs = previous.timestampMs,
                        toMs = next.timestampMs,
                        days = deltaMs / MS_PER_DAY,
                        km = if (km >= 0.0) km else Double.NaN,
                        litersAdded = next.litersAdded
                    )
                )
            }

            val days = intervals.map { it.days }
            val validKm = intervals.map { it.km }.filter { !it.isNaN() && it >= 0.0 }
            val first = chronological.first()
            val last = chronological.last()
            val spanKm = last.odometerKm - first.odometerKm
            val totalLiters = chronological.sumOf { it.litersAdded }

            return FuelStats(
                fillCount = chronological.size,
                fullFillCount = chronological.count { it.isFullTank },
                partialFillCount = chronological.count { !it.isFullTank },
                daysSinceLastFill = ((nowMs - last.timestampMs) / MS_PER_DAY).coerceAtLeast(0.0),
                avgDaysBetween = days.averageOrNull(),
                lastDaysBetween = days.lastOrNull(),
                minDaysBetween = days.minOrNull(),
                maxDaysBetween = days.maxOrNull(),
                avgKmBetween = validKm.averageOrNull(),
                lastKmBetween = intervals.lastOrNull()?.km?.takeUnless { it.isNaN() },
                minKmBetween = validKm.minOrNull(),
                maxKmBetween = validKm.maxOrNull(),
                totalKm = if (spanKm >= 0.0) spanKm else null,
                totalLiters = totalLiters,
                avgLiters = if (chronological.isNotEmpty()) totalLiters / chronological.size else null,
                intervalsNewestFirst = intervals.reversed()
            )
        }

        private const val MS_PER_DAY = 86_400_000.0

        private fun List<Double>.averageOrNull(): Double? =
            if (isEmpty()) null else average()
    }
}
