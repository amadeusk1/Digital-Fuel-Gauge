package com.fuelcheck

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.fuelcheck.databinding.ActivityMainBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(FuelAddedActivity.PREFS_NAME, Context.MODE_PRIVATE)
        restoreSavedValues()

        binding.calculateButton.setOnClickListener {
            hideKeyboard()
            calculate()
        }

        binding.fuelAddedButton.setOnClickListener {
            startActivity(Intent(this, FuelAddedActivity::class.java))
        }

        binding.stationsButton.setOnClickListener {
            openGasBuddy()
        }

        binding.tankCapacityInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                calculate()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        restoreStatus()
    }

    private fun restoreSavedValues() {
        val tankCapacity = prefs.getFloat(FuelAddedActivity.KEY_TANK_CAPACITY, Float.NaN)
        if (!tankCapacity.isNaN() && tankCapacity > 0f) {
            binding.tankCapacityInput.setText(formatStoredNumber(tankCapacity.toDouble()))
        }

        val consumption = prefs.getFloat(FuelAddedActivity.KEY_CONSUMPTION, Float.NaN)
        if (!consumption.isNaN() && consumption > 0f) {
            binding.consumptionInput.setText(formatStoredNumber(consumption.toDouble()))
        }

        restoreStatus()
    }

    private fun restoreStatus() {
        val percent = prefs.getFloat(FuelAddedActivity.KEY_LAST_REMAINING_PCT, Float.NaN)
        val liters = prefs.getFloat(FuelAddedActivity.KEY_LAST_REMAINING_L, Float.NaN)
        val consumption = prefs.getFloat(FuelAddedActivity.KEY_CONSUMPTION, Float.NaN)

        if (!percent.isNaN() && !liters.isNaN()) {
            binding.gasGauge.setLevel(percent, animate = false)
            val rangeKm = if (!consumption.isNaN() && consumption > 0f) {
                liters / consumption * 100.0
            } else {
                null
            }
            setStatus(liters.toDouble(), rangeKm, animate = false)
        } else {
            binding.gasGauge.clearLevel()
            clearStatus()
        }
    }

    private fun updateGaugeAndStatus(remainingLiters: Double, percent: Double, rangeKm: Double?) {
        binding.gasGauge.setLevel(percent.toFloat(), animate = true)
        setStatus(remainingLiters, rangeKm, animate = true)
    }

    private fun setStatus(remainingLiters: Double, rangeKm: Double?, animate: Boolean) {
        binding.statusLiters.text = getString(R.string.status_liters, remainingLiters)
        if (rangeKm != null) {
            binding.statusRange.text = getString(R.string.status_range, rangeKm)
            binding.statusRange.visibility = View.VISIBLE
        } else {
            binding.statusRange.visibility = View.GONE
        }

        if (animate) {
            animateStatus()
        }
    }

    private fun clearStatus() {
        binding.statusLiters.text = getString(R.string.status_empty)
        binding.statusRange.visibility = View.GONE
    }

    private fun animateStatus() {
        listOf(binding.statusLiters, binding.statusRange).forEach { view ->
            if (view.visibility != View.VISIBLE) return@forEach
            view.alpha = 0f
            view.translationY = 12f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun calculate() {
        binding.formError.visibility = View.GONE

        val currentKm = readPositive(binding.currentKmLayout, binding.currentKmInput) ?: return
        val consumption = readPositive(binding.consumptionLayout, binding.consumptionInput) ?: return
        val tankCapacity = readPositive(binding.tankCapacityLayout, binding.tankCapacityInput) ?: return

        val lastFull = prefs.getFloat(FuelAddedActivity.KEY_LAST_FULL_KM, Float.NaN)
        if (lastFull.isNaN() || lastFull <= 0f) {
            binding.formError.text = getString(R.string.error_need_last_reading)
            binding.formError.visibility = View.VISIBLE
            return
        }

        if (currentKm < lastFull) {
            binding.currentKmLayout.error = getString(R.string.error_current_before_last)
            binding.formError.text = getString(R.string.error_current_before_last)
            binding.formError.visibility = View.VISIBLE
            return
        }

        val savedRemaining = prefs.getFloat(FuelAddedActivity.KEY_LAST_REMAINING_L, Float.NaN)

        val remainingLiters = if (!savedRemaining.isNaN()) {
            val used = (currentKm - lastFull) * consumption / 100.0
            max(0.0, min(tankCapacity, savedRemaining.toDouble() - used))
        } else {
            val usedLiters = (currentKm - lastFull) * consumption / 100.0
            max(0.0, tankCapacity - usedLiters)
        }

        val percent = (remainingLiters / tankCapacity) * 100.0
        val rangeKm = remainingLiters / consumption * 100.0

        prefs.edit()
            .putFloat(FuelAddedActivity.KEY_TANK_CAPACITY, tankCapacity.toFloat())
            .putFloat(FuelAddedActivity.KEY_CONSUMPTION, consumption.toFloat())
            .putFloat(FuelAddedActivity.KEY_LAST_FULL_KM, currentKm.toFloat())
            .putBoolean(FuelAddedActivity.KEY_LAST_FULL_LOCKED, true)
            .putFloat(FuelAddedActivity.KEY_LAST_REMAINING_L, remainingLiters.toFloat())
            .putFloat(FuelAddedActivity.KEY_LAST_REMAINING_PCT, percent.toFloat())
            .apply()

        updateGaugeAndStatus(remainingLiters, percent, rangeKm)
    }

    private fun readPositive(
        layout: TextInputLayout,
        input: TextInputEditText
    ): Double? {
        layout.error = null
        val raw = input.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            layout.error = getString(R.string.error_required)
            return null
        }
        val value = raw.toDoubleOrNull()
        if (value == null || value <= 0.0) {
            layout.error = getString(R.string.error_positive)
            return null
        }
        return value
    }

    private fun formatStoredNumber(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    private fun openGasBuddy() {
        val webUri = Uri.parse(GASBUDDY_HOME)
        val appIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            setPackage(GASBUDDY_PACKAGE)
        }
        try {
            startActivity(appIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$GASBUDDY_PACKAGE")
                    )
                )
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            }
        }
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: binding.root
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    companion object {
        private const val GASBUDDY_HOME = "https://www.gasbuddy.com/home"
        private const val GASBUDDY_PACKAGE = "com.gasbuddymobile.android"
    }
}
