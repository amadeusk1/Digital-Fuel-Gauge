package com.fuelcheck

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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.calculateButton.setOnClickListener {
            hideKeyboard()
            calculate()
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

    private fun calculate() {
        binding.formError.visibility = View.GONE

        val lastFull = readPositive(binding.lastFullLayout, binding.lastFullInput) ?: return
        val currentKm = readPositive(binding.currentKmLayout, binding.currentKmInput) ?: return
        val consumption = readPositive(binding.consumptionLayout, binding.consumptionInput) ?: return
        val tankCapacity = readPositive(binding.tankCapacityLayout, binding.tankCapacityInput) ?: return

        if (currentKm < lastFull) {
            binding.currentKmLayout.error = getString(R.string.error_current_before_last)
            binding.formError.text = getString(R.string.error_current_before_last)
            binding.formError.visibility = View.VISIBLE
            binding.resultPanel.visibility = View.GONE
            return
        }

        val drivenKm = currentKm - lastFull
        val usedLiters = drivenKm * consumption / 100.0
        val remainingLiters = max(0.0, tankCapacity - usedLiters)
        val percent = (remainingLiters / tankCapacity) * 100.0
        val rangeKm = remainingLiters / consumption * 100.0

        binding.remainingValue.text = getString(R.string.format_liters, remainingLiters)
        binding.percentValue.text = getString(R.string.format_percent_of_tank, percent)
        binding.rangeValue.text = getString(R.string.format_range, rangeKm)

        showResult()
    }

    private fun showResult() {
        val panel = binding.resultPanel
        if (panel.visibility != View.VISIBLE) {
            panel.alpha = 0f
            panel.translationY = 24f
            panel.visibility = View.VISIBLE
            panel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
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

    private fun hideKeyboard() {
        val view = currentFocus ?: binding.root
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
