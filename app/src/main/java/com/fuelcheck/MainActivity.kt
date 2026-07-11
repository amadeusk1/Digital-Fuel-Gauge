package com.fuelcheck

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fuelcheck.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var gpsTracking = false
    private var gpsMeters = 0.0
    private var lastGpsLocation: Location? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startGpsTracking()
        } else {
            binding.formError.text = getString(R.string.gps_permission_denied)
            binding.formError.visibility = View.VISIBLE
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            onGpsLocation(location)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(FuelAddedActivity.PREFS_NAME, Context.MODE_PRIVATE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
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

        binding.gpsButton.setOnClickListener {
            hideKeyboard()
            toggleGpsTracking()
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
        if (!gpsTracking) {
            restoreStatus()
        }
    }

    override fun onPause() {
        if (gpsTracking) {
            // Keep session totals; pause updates while in background
            fusedLocationClient.removeLocationUpdates(locationCallback)
            lastGpsLocation = null
        }
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (gpsTracking && hasLocationPermission()) {
            requestLocationUpdates()
        }
    }

    override fun onDestroy() {
        if (gpsTracking) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        super.onDestroy()
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

    private fun toggleGpsTracking() {
        binding.formError.visibility = View.GONE
        if (gpsTracking) {
            stopGpsTracking(applyDistance = true)
        } else {
            val odometerRaw = binding.currentKmInput.text?.toString()?.trim().orEmpty()
            if (odometerRaw.toDoubleOrNull() == null || odometerRaw.toDouble() <= 0.0) {
                binding.currentKmLayout.error = getString(R.string.gps_need_odometer)
                binding.formError.text = getString(R.string.gps_need_odometer)
                binding.formError.visibility = View.VISIBLE
                return
            }
            ensureLocationPermissionAndStart()
        }
    }

    private fun ensureLocationPermissionAndStart() {
        if (!isLocationEnabled()) {
            binding.formError.text = getString(R.string.gps_unavailable)
            binding.formError.visibility = View.VISIBLE
            return
        }
        if (hasLocationPermission()) {
            startGpsTracking()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    private fun startGpsTracking() {
        gpsTracking = true
        gpsMeters = 0.0
        lastGpsLocation = null
        binding.gpsStatus.visibility = View.VISIBLE
        binding.gpsStatus.text = getString(R.string.gps_waiting)
        binding.gpsButton.setColorFilter(
            ContextCompat.getColor(this, R.color.fuel_error)
        )
        binding.gpsButton.contentDescription = getString(R.string.cd_gps_stop)
        requestLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun onGpsLocation(location: Location) {
        if (!gpsTracking) return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_METERS) return

        val previous = lastGpsLocation
        lastGpsLocation = location
        if (previous != null) {
            val delta = previous.distanceTo(location).toDouble()
            if (delta >= MIN_SEGMENT_METERS && delta < MAX_SEGMENT_METERS) {
                gpsMeters += delta
            }
        }

        val km = gpsMeters / 1000.0
        binding.gpsStatus.text = getString(R.string.gps_tracking, km)
    }

    private fun stopGpsTracking(applyDistance: Boolean) {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        gpsTracking = false
        lastGpsLocation = null
        binding.gpsButton.setColorFilter(
            ContextCompat.getColor(this, R.color.fuel_primary)
        )
        binding.gpsButton.contentDescription = getString(R.string.cd_gps)

        val km = gpsMeters / 1000.0
        gpsMeters = 0.0

        if (!applyDistance || km < 0.05) {
            binding.gpsStatus.visibility = View.GONE
            return
        }

        val currentRaw = binding.currentKmInput.text?.toString()?.trim().orEmpty()
        val currentKm = currentRaw.toDoubleOrNull()
        if (currentKm == null || currentKm <= 0.0) {
            binding.gpsStatus.visibility = View.GONE
            binding.formError.text = getString(R.string.gps_need_odometer)
            binding.formError.visibility = View.VISIBLE
            return
        }

        val newOdometer = currentKm + km
        binding.currentKmInput.setText(formatStoredNumber(newOdometer))
        binding.currentKmLayout.error = null
        binding.gpsStatus.text = getString(R.string.gps_added, km)
        binding.gpsStatus.visibility = View.VISIBLE

        // Recalculate fuel using the GPS-updated odometer when possible
        val hasVehicle =
            !prefs.getFloat(FuelAddedActivity.KEY_CONSUMPTION, Float.NaN).isNaN() &&
                !prefs.getFloat(FuelAddedActivity.KEY_TANK_CAPACITY, Float.NaN).isNaN() &&
                !prefs.getFloat(FuelAddedActivity.KEY_LAST_FULL_KM, Float.NaN).isNaN()
        if (hasVehicle) {
            calculate()
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
            String.format("%.1f", value).trimEnd('0').trimEnd('.')
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
        private const val MAX_ACCURACY_METERS = 40f
        private const val MIN_SEGMENT_METERS = 5.0
        private const val MAX_SEGMENT_METERS = 2000.0
    }
}
