package com.fuelcheck

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
    private var fullTankSelected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFuelAddedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restoreSavedValues()
        updateFullTankButton()

        binding.backButton.setOnClickListener { finish() }

        binding.fullTankButton.setOnClickListener {
            hideKeyboard()
            setFullTankSelected(!fullTankSelected)
        }

        binding.addFuelButton.setOnClickListener {
            hideKeyboard()
            submitFuel()
        }

        binding.litersAddedInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!s.isNullOrBlank() && fullTankSelected) {
                    setFullTankSelected(false)
                }
            }
        })

        binding.litersAddedInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                submitFuel()
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

    private fun setFullTankSelected(selected: Boolean) {
        fullTankSelected = selected
        if (selected) {
            binding.litersAddedLayout.error = null
            binding.litersAddedInput.setText("")
            binding.litersAddedInput.clearFocus()
        }
        updateFullTankButton()
    }

    private fun updateFullTankButton() {
        if (fullTankSelected) {
            binding.fullTankButton.setBackgroundColor(
                ContextCompat.getColor(this, R.color.fuel_primary)
            )
            binding.fullTankButton.setTextColor(
                ContextCompat.getColor(this, R.color.fuel_on_primary)
            )
            binding.fullTankButton.strokeWidth = 0
        } else {
            binding.fullTankButton.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.transparent)
            )
            binding.fullTankButton.setTextColor(
                ContextCompat.getColor(this, R.color.fuel_primary)
            )
            binding.fullTankButton.strokeWidth =
                (1.5f * resources.displayMetrics.density).toInt()
        }
    }

    private fun submitFuel() {
        if (fullTankSelected) {
            fullTankAdded()
        } else {
            addLitersToTank()
        }
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

        FuelLogStore.add(
            prefs,
            FuelLogEntry(
                timestampMs = System.currentTimeMillis(),
                odometerKm = currentKm,
                litersAdded = tankCapacity.toDouble(),
                remainingLiters = tankCapacity.toDouble(),
                isFullTank = true
            )
        )

        lastFullLocked = true
        applyLastFullLockState()

        setResult(RESULT_OK)
        finish()
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

        FuelLogStore.add(
            prefs,
            FuelLogEntry(
                timestampMs = System.currentTimeMillis(),
                odometerKm = currentKm,
                litersAdded = added,
                remainingLiters = newRemaining,
                isFullTank = false
            )
        )

        lastFullLocked = true
        applyLastFullLockState()

        setResult(RESULT_OK)
        finish()
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
        binding.addFuelButton.isEnabled = true
        binding.fullTankButton.isEnabled = true
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
