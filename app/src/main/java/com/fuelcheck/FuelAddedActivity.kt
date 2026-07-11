package com.fuelcheck

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fuelcheck.databinding.ActivityFuelAddedBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.max
import kotlin.math.min

class FuelAddedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFuelAddedBinding
    private lateinit var prefs: android.content.SharedPreferences

    private var lastFullLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFuelAddedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restoreSavedValues()

        binding.backButton.setOnClickListener { finish() }

        binding.fueledUpButton.setOnClickListener {
            hideKeyboard()
            fullTankAdded()
        }

        binding.addLitersButton.setOnClickListener {
            hideKeyboard()
            addLitersToTank()
        }

        binding.litersAddedInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                addLitersToTank()
                true
            } else {
                false
            }
        }
    }

    private fun restoreSavedValues() {
        val lastFull = prefs.getFloat(KEY_LAST_FULL_KM, Float.NaN)
        if (!lastFull.isNaN() && lastFull > 0f) {
            binding.lastFullInput.setText(formatStoredNumber(lastFull.toDouble()))
            lastFullLocked = prefs.getBoolean(KEY_LAST_FULL_LOCKED, true)
        } else {
            lastFullLocked = false
        }
        applyLastFullLockState()
    }

    private fun fullTankAdded() {
        binding.formError.visibility = View.GONE
        binding.statusMessage.visibility = View.GONE
        binding.lastFullLayout.error = null
        binding.litersAddedLayout.error = null

        val tankCapacity = prefs.getFloat(KEY_TANK_CAPACITY, Float.NaN)
        val consumption = prefs.getFloat(KEY_CONSUMPTION, Float.NaN)
        if (tankCapacity.isNaN() || tankCapacity <= 0f ||
            consumption.isNaN() || consumption <= 0f
        ) {
            binding.formError.text = getString(R.string.error_need_vehicle)
            binding.formError.visibility = View.VISIBLE
            return
        }

        val currentKm = readPositive(binding.currentKmLayout, binding.currentKmInput) ?: return

        binding.lastFullInput.setText(formatStoredNumber(currentKm))
        prefs.edit()
            .putFloat(KEY_LAST_FULL_KM, currentKm.toFloat())
            .putFloat(KEY_LAST_REMAINING_L, tankCapacity)
            .putFloat(KEY_LAST_REMAINING_PCT, 100f)
            .putBoolean(KEY_LAST_FULL_LOCKED, true)
            .apply()

        lastFullLocked = true
        applyLastFullLockState()

        binding.statusMessage.text = getString(R.string.status_full_tank, tankCapacity.toDouble())
        binding.statusMessage.visibility = View.VISIBLE
    }

    private fun addLitersToTank() {
        binding.formError.visibility = View.GONE
        binding.statusMessage.visibility = View.GONE

        val added = readPositive(binding.litersAddedLayout, binding.litersAddedInput) ?: return
        val currentKm = readPositive(binding.currentKmLayout, binding.currentKmInput) ?: return

        val tankCapacity = prefs.getFloat(KEY_TANK_CAPACITY, Float.NaN)
        val consumption = prefs.getFloat(KEY_CONSUMPTION, Float.NaN)
        if (tankCapacity.isNaN() || tankCapacity <= 0f ||
            consumption.isNaN() || consumption <= 0f
        ) {
            binding.formError.text = getString(R.string.error_need_vehicle)
            binding.formError.visibility = View.VISIBLE
            return
        }

        val lastReadingRaw = binding.lastFullInput.text?.toString()?.trim().orEmpty()
        val lastReadingKm = lastReadingRaw.toDoubleOrNull()
        val savedRemaining = prefs.getFloat(KEY_LAST_REMAINING_L, Float.NaN)

        val remainingBeforeAdd = when {
            lastReadingKm != null && lastReadingKm > 0.0 && !savedRemaining.isNaN() -> {
                if (currentKm < lastReadingKm) {
                    binding.currentKmLayout.error = getString(R.string.error_current_before_last)
                    binding.formError.text = getString(R.string.error_current_before_last)
                    binding.formError.visibility = View.VISIBLE
                    return
                }
                val used = (currentKm - lastReadingKm) * consumption / 100.0
                max(0.0, savedRemaining.toDouble() - used)
            }
            lastReadingKm != null && lastReadingKm > 0.0 && currentKm >= lastReadingKm -> {
                max(0.0, tankCapacity - (currentKm - lastReadingKm) * consumption / 100.0)
            }
            !savedRemaining.isNaN() -> savedRemaining.toDouble()
            else -> {
                binding.formError.text = getString(R.string.error_need_reading)
                binding.formError.visibility = View.VISIBLE
                return
            }
        }

        val room = tankCapacity - remainingBeforeAdd
        if (added > room + 0.05) {
            binding.litersAddedLayout.error =
                getString(R.string.error_exceeds_tank, max(0.0, room))
            return
        }

        val newRemaining = min(tankCapacity.toDouble(), remainingBeforeAdd + added)
        val percent = (newRemaining / tankCapacity) * 100.0

        binding.lastFullInput.setText(formatStoredNumber(currentKm))
        prefs.edit()
            .putFloat(KEY_LAST_FULL_KM, currentKm.toFloat())
            .putBoolean(KEY_LAST_FULL_LOCKED, true)
            .putFloat(KEY_LAST_REMAINING_L, newRemaining.toFloat())
            .putFloat(KEY_LAST_REMAINING_PCT, percent.toFloat())
            .apply()

        lastFullLocked = true
        applyLastFullLockState()

        binding.statusMessage.text = getString(R.string.status_added, added, newRemaining)
        binding.statusMessage.visibility = View.VISIBLE

        binding.litersAddedInput.text = null
        binding.litersAddedLayout.error = null
    }

    private fun applyLastFullLockState() {
        binding.lastFullInput.isEnabled = !lastFullLocked
        binding.lastFullInput.isFocusable = !lastFullLocked
        binding.lastFullInput.isFocusableInTouchMode = !lastFullLocked
        binding.lastFullInput.setTextColor(
            ContextCompat.getColor(
                this,
                if (lastFullLocked) R.color.fuel_muted else R.color.fuel_text
            )
        )
        binding.lastFullLayout.helperText = if (lastFullLocked) {
            getString(R.string.helper_last_full_locked)
        } else {
            null
        }
        binding.fueledUpButton.isEnabled = true
        binding.addLitersButton.isEnabled = true
        binding.addLitersButton.alpha = 1f
        binding.litersAddedInput.isEnabled = true
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

    private fun hideKeyboard() {
        val view = currentFocus ?: binding.root
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    companion object {
        const val PREFS_NAME = "fuelcheck_prefs"
        const val KEY_TANK_CAPACITY = "tank_capacity"
        const val KEY_CONSUMPTION = "consumption"
        const val KEY_LAST_FULL_KM = "last_full_km"
        const val KEY_LAST_FULL_LOCKED = "last_full_locked"
        const val KEY_LAST_REMAINING_L = "last_remaining_l"
        const val KEY_LAST_REMAINING_PCT = "last_remaining_pct"
    }
}
