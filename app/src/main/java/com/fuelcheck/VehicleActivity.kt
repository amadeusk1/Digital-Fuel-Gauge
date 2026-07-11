package com.fuelcheck

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.fuelcheck.databinding.ActivityVehicleBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class VehicleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVehicleBinding
    private var addingCar = false
    private var selectedVehicleId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVehicleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedVehicleId = VehicleStore.active(this)?.id
        refreshCarsUi()
        loadSelectedIntoForm()

        binding.backButton.setOnClickListener {
            if (addingCar) {
                cancelAddCar()
            } else {
                finish()
            }
        }

        binding.addCarButton.setOnClickListener {
            hideKeyboard()
            enterAddCarMode()
        }

        binding.saveButton.setOnClickListener {
            hideKeyboard()
            if (addingCar) {
                saveNewCar()
            } else {
                saveEdits()
            }
        }

        binding.resetButton.setOnClickListener {
            hideKeyboard()
            confirmResetApp()
        }

        binding.currentKmInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                if (addingCar) saveNewCar() else saveEdits()
                true
            } else {
                false
            }
        }
    }

    private fun refreshCarsUi() {
        val vehicles = VehicleStore.loadAll(this)
        binding.carsChipGroup.removeAllViews()

        vehicles.forEach { vehicle ->
            val chip = Chip(this).apply {
                text = vehicle.name
                isCheckable = true
                isChecked = vehicle.id == selectedVehicleId
                setOnClickListener {
                    if (addingCar) {
                        cancelAddCar()
                    }
                    selectedVehicleId = vehicle.id
                    VehicleStore.setActiveId(this@VehicleActivity, vehicle.id)
                    loadSelectedIntoForm()
                    refreshCarsUi()
                    binding.statusMessage.text = getString(R.string.vehicle_switched, vehicle.name)
                    binding.statusMessage.visibility = View.VISIBLE
                    setResult(RESULT_OK)
                }
            }
            binding.carsChipGroup.addView(chip)
        }
    }

    private fun loadSelectedIntoForm() {
        val vehicle = VehicleStore.loadAll(this).firstOrNull { it.id == selectedVehicleId }
            ?: VehicleStore.active(this)
            ?: return

        selectedVehicleId = vehicle.id
        binding.carNameInput.setText(vehicle.name)
        if (!vehicle.consumption.isNaN() && vehicle.consumption > 0f) {
            binding.consumptionInput.setText(formatStoredNumber(vehicle.consumption.toDouble()))
        } else {
            binding.consumptionInput.setText("")
        }
        if (!vehicle.tankCapacity.isNaN() && vehicle.tankCapacity > 0f) {
            binding.tankCapacityInput.setText(formatStoredNumber(vehicle.tankCapacity.toDouble()))
        } else {
            binding.tankCapacityInput.setText("")
        }
        clearErrors()
    }

    private fun enterAddCarMode() {
        addingCar = true
        binding.subtitleText.text = getString(R.string.add_car_subtitle)
        binding.carsLabel.visibility = View.GONE
        binding.carsScroll.visibility = View.GONE
        binding.addCarButton.text = getString(R.string.action_cancel_add_car)
        binding.currentKmLayout.visibility = View.VISIBLE
        binding.fullTankNote.visibility = View.VISIBLE
        binding.saveButton.text = getString(R.string.action_add_car)
        binding.carNameInput.setText("")
        binding.consumptionInput.setText("")
        binding.tankCapacityInput.setText("")
        binding.currentKmInput.setText("")
        binding.statusMessage.visibility = View.GONE
        clearErrors()
    }

    private fun cancelAddCar() {
        addingCar = false
        binding.subtitleText.text = getString(R.string.vehicle_subtitle)
        binding.carsLabel.visibility = View.VISIBLE
        binding.carsScroll.visibility = View.VISIBLE
        binding.addCarButton.text = getString(R.string.action_add_car)
        binding.currentKmLayout.visibility = View.GONE
        binding.fullTankNote.visibility = View.GONE
        binding.saveButton.text = getString(R.string.action_save_vehicle)
        binding.currentKmInput.setText("")
        loadSelectedIntoForm()
        refreshCarsUi()
        binding.statusMessage.visibility = View.GONE
        clearErrors()
    }

    private fun saveEdits() {
        binding.formError.visibility = View.GONE
        binding.statusMessage.visibility = View.GONE

        val current = VehicleStore.loadAll(this).firstOrNull { it.id == selectedVehicleId }
            ?: VehicleStore.active(this)
        if (current == null) {
            binding.formError.text = getString(R.string.error_need_vehicle)
            binding.formError.visibility = View.VISIBLE
            return
        }

        val name = binding.carNameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.carNameLayout.error = getString(R.string.error_need_car_name)
            return
        }
        binding.carNameLayout.error = null

        val consumption = readPositive(binding.consumptionLayout, binding.consumptionInput) ?: return
        val tankCapacity = readPositive(binding.tankCapacityLayout, binding.tankCapacityInput) ?: return

        val updated = current.copy(
            name = name,
            consumption = consumption.toFloat(),
            tankCapacity = tankCapacity.toFloat()
        )
        VehicleStore.save(this, updated, setActive = true)
        selectedVehicleId = updated.id
        refreshCarsUi()

        binding.statusMessage.text = getString(R.string.vehicle_saved)
        binding.statusMessage.visibility = View.VISIBLE
        setResult(RESULT_OK)
        finish()
    }

    private fun saveNewCar() {
        binding.formError.visibility = View.GONE
        binding.statusMessage.visibility = View.GONE

        val name = binding.carNameInput.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.carNameLayout.error = getString(R.string.error_need_car_name)
            return
        }
        binding.carNameLayout.error = null

        val consumption = readPositive(binding.consumptionLayout, binding.consumptionInput) ?: return
        val tankCapacity = readPositive(binding.tankCapacityLayout, binding.tankCapacityInput) ?: return
        val odometer = readPositive(binding.currentKmLayout, binding.currentKmInput) ?: return

        val vehicle = VehicleStore.createFullTankVehicle(
            context = this,
            name = name,
            consumption = consumption,
            tankCapacity = tankCapacity,
            odometerKm = odometer,
            setActive = true
        )
        selectedVehicleId = vehicle.id
        addingCar = false
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmResetApp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_app_title)
            .setMessage(R.string.reset_app_message)
            .setNegativeButton(R.string.reset_app_cancel, null)
            .setPositiveButton(R.string.reset_app_confirm) { _, _ ->
                resetApp()
            }
            .show()
    }

    private fun resetApp() {
        if (TripTrackingService.isRunning) {
            TripTrackingService.stop(this, applyDistance = false)
        }
        VehicleStore.clearAll(this)
        startActivity(
            Intent(this, OnboardingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    private fun clearErrors() {
        binding.carNameLayout.error = null
        binding.consumptionLayout.error = null
        binding.tankCapacityLayout.error = null
        binding.currentKmLayout.error = null
        binding.formError.visibility = View.GONE
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
            String.format("%.1f", value).trimEnd('0').trimEnd('.')
        }
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: binding.root
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
