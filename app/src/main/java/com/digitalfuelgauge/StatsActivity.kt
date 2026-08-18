package com.digitalfuelgauge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.digitalfuelgauge.databinding.ActivityStatsBinding
import com.digitalfuelgauge.databinding.ViewStatCardBinding
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private val dateFormat: DateFormat =
        DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        bindStats()
    }

    override fun onResume() {
        super.onResume()
        bindStats()
    }

    private fun bindStats() {
        val vehicle = VehicleStore.active(this)
        if (vehicle != null && vehicle.name.isNotBlank()) {
            binding.vehicleName.visibility = View.VISIBLE
            binding.vehicleName.text = vehicle.name
        } else {
            binding.vehicleName.visibility = View.GONE
        }

        val stats = FuelStats.compute(FuelLogStore.load(this))
        if (stats.fillCount == 0) {
            binding.emptyMessage.visibility = View.VISIBLE
            binding.statsScroll.visibility = View.GONE
            return
        }

        binding.emptyMessage.visibility = View.GONE
        binding.statsScroll.visibility = View.VISIBLE

        val hasIntervals = stats.intervalsNewestFirst.isNotEmpty()
        binding.needMoreMessage.visibility = if (hasIntervals) View.GONE else View.VISIBLE
        binding.heroCard.visibility = if (stats.avgDaysBetween != null) View.VISIBLE else View.GONE
        binding.intervalsLabel.visibility = if (hasIntervals) View.VISIBLE else View.GONE
        binding.intervalsContainer.visibility = if (hasIntervals) View.VISIBLE else View.GONE

        if (stats.avgDaysBetween != null) {
            binding.heroDays.text = formatDays(stats.avgDaysBetween)
            binding.heroDistance.text = if (stats.avgKmBetween != null) {
                getString(R.string.stats_hero_distance, stats.avgKmBetween)
            } else {
                getString(R.string.stats_dash)
            }
        }

        bindCard(binding.lastDaysCard, getString(R.string.stats_last_gap), formatDays(stats.lastDaysBetween))
        bindCard(binding.sinceLastCard, getString(R.string.stats_since_last), formatDays(stats.daysSinceLastFill))
        bindCard(binding.shortestDaysCard, getString(R.string.stats_shortest_gap), formatDays(stats.minDaysBetween))
        bindCard(binding.longestDaysCard, getString(R.string.stats_longest_gap), formatDays(stats.maxDaysBetween))
        bindCard(binding.avgKmCard, getString(R.string.stats_avg_distance), formatKm(stats.avgKmBetween))
        bindCard(binding.lastKmCard, getString(R.string.stats_last_distance), formatKm(stats.lastKmBetween))
        bindCard(binding.totalKmCard, getString(R.string.stats_total_distance), formatKm(stats.totalKm))
        bindCard(
            binding.rangeKmCard,
            getString(R.string.stats_distance_range),
            formatKmRange(stats.minKmBetween, stats.maxKmBetween)
        )
        bindCard(binding.totalLitersCard, getString(R.string.stats_total_fuel), formatLiters(stats.totalLiters))
        bindCard(binding.avgLitersCard, getString(R.string.stats_avg_fill), formatLiters(stats.avgLiters))
        bindCard(
            binding.fillsCard,
            getString(R.string.stats_fills),
            stats.fillCount.toString()
        )
        bindCard(
            binding.fillMixCard,
            getString(R.string.stats_fill_mix),
            getString(R.string.stats_fill_mix_value, stats.fullFillCount, stats.partialFillCount)
        )

        bindIntervals(stats.intervalsNewestFirst)
    }

    private fun bindIntervals(intervals: List<FuelInterval>) {
        binding.intervalsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        intervals.forEach { interval ->
            val row = inflater.inflate(R.layout.item_stats_interval, binding.intervalsContainer, false)
            val dates = getString(
                R.string.stats_interval_dates,
                dateFormat.format(Date(interval.fromMs)),
                dateFormat.format(Date(interval.toMs))
            )
            val values = getString(
                R.string.stats_interval_values,
                formatDays(interval.days),
                formatKm(interval.km.takeUnless { it.isNaN() })
            )
            row.findViewById<TextView>(R.id.intervalDates).text = dates
            row.findViewById<TextView>(R.id.intervalValues).text = values
            binding.intervalsContainer.addView(row)
        }
    }

    private fun bindCard(card: ViewStatCardBinding, label: String, value: String) {
        card.statLabel.text = label
        card.statValue.text = value
    }

    private fun formatDays(days: Double?): String {
        if (days == null) return getString(R.string.stats_dash)
        val rounded = (days * 10.0).roundToInt() / 10.0
        if (abs(rounded - 1.0) < 0.05) return getString(R.string.stats_days_one)
        val number = if (abs(rounded % 1.0) < 0.05) {
            rounded.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", rounded)
        }
        return getString(R.string.stats_days, number)
    }

    private fun formatKm(km: Double?): String {
        if (km == null) return getString(R.string.stats_dash)
        return getString(R.string.stats_km, km)
    }

    private fun formatKmRange(minKm: Double?, maxKm: Double?): String {
        if (minKm == null || maxKm == null) return getString(R.string.stats_dash)
        if (abs(minKm - maxKm) < 0.5) return formatKm(minKm)
        return getString(R.string.stats_km_range, minKm, maxKm)
    }

    private fun formatLiters(liters: Double?): String {
        if (liters == null) return getString(R.string.stats_dash)
        return getString(R.string.stats_liters, liters)
    }
}
