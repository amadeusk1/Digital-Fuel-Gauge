package com.fuelcheck

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fuelcheck.databinding.ActivityMainBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences

    private var gpsTracking = false
    private var pendingStartAfterNotificationPermission = false
    private var pendingFuelGaugeAnimate = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            ensureNotificationPermissionAndStart()
        } else {
            binding.formError.text = getString(R.string.gps_permission_denied)
            binding.formError.visibility = View.VISIBLE
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Tracking can still run as a foreground service; notification may be hidden if denied.
        if (pendingStartAfterNotificationPermission) {
            pendingStartAfterNotificationPermission = false
            startGpsTracking()
        }
    }

    private val fuelAddedLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingFuelGaugeAnimate = true
        }
    }

    private val trackingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TripTrackingService.ACTION_UPDATE -> {
                    val meters = intent.getDoubleExtra(TripTrackingService.EXTRA_METERS, 0.0)
                    onTrackingUpdate(meters)
                }
                TripTrackingService.ACTION_STOPPED -> {
                    val meters = intent.getDoubleExtra(TripTrackingService.EXTRA_METERS, 0.0)
                    val apply = intent.getBooleanExtra(
                        TripTrackingService.EXTRA_APPLY_DISTANCE,
                        true
                    )
                    onTrackingStopped(meters, apply)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(FuelAddedActivity.PREFS_NAME, Context.MODE_PRIVATE)
        restoreSavedValues()
        updateDaysSinceFuel()

        binding.calculateButton.setOnClickListener {
            hideKeyboard()
            calculate()
        }

        binding.fuelAddedButton.setOnClickListener {
            fuelAddedLauncher.launch(Intent(this, FuelAddedActivity::class.java))
        }

        binding.logButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
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

        val filter = IntentFilter().apply {
            addAction(TripTrackingService.ACTION_UPDATE)
            addAction(TripTrackingService.ACTION_STOPPED)
        }
        ContextCompat.registerReceiver(
            this,
            trackingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStart() {
        super.onStart()
        syncTrackingUiFromService()
        applyPendingGpsKilometersIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        restoreStatus(animate = pendingFuelGaugeAnimate)
        pendingFuelGaugeAnimate = false
        updateDaysSinceFuel()
    }

    override fun onDestroy() {
        unregisterReceiver(trackingReceiver)
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

    private fun restoreStatus(animate: Boolean = false) {
        val percent = prefs.getFloat(FuelAddedActivity.KEY_LAST_REMAINING_PCT, Float.NaN)
        val liters = prefs.getFloat(FuelAddedActivity.KEY_LAST_REMAINING_L, Float.NaN)
        val consumption = prefs.getFloat(FuelAddedActivity.KEY_CONSUMPTION, Float.NaN)

        if (!percent.isNaN() && !liters.isNaN()) {
            binding.gasGauge.setLevel(percent, animate = animate)
            val rangeKm = if (!consumption.isNaN() && consumption > 0f) {
                liters / consumption * 100.0
            } else {
                null
            }
            setStatus(liters.toDouble(), rangeKm, animate = animate)
        } else {
            binding.gasGauge.clearLevel()
            clearStatus()
        }
    }

    private fun updateDaysSinceFuel() {
        val lastEntry = FuelLogStore.load(prefs).firstOrNull()
        if (lastEntry == null) {
            binding.daysSinceFuel.visibility = View.GONE
            return
        }

        val days = ((System.currentTimeMillis() - lastEntry.timestampMs) /
            (24L * 60L * 60L * 1000L)).toInt().coerceAtLeast(0)
        binding.daysSinceFuel.text = when (days) {
            0 -> getString(R.string.days_since_fuel_today)
            1 -> getString(R.string.days_since_fuel_one)
            else -> getString(R.string.days_since_fuel, days)
        }
        binding.daysSinceFuel.visibility = View.VISIBLE
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
        if (gpsTracking || TripTrackingService.isRunning) {
            TripTrackingService.stop(this, applyDistance = true)
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
            ensureNotificationPermissionAndStart()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun ensureNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startGpsTracking()
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

    private fun startGpsTracking() {
        gpsTracking = true
        showTrackingActiveUi(0.0)
        TripTrackingService.start(this)
    }

    private fun syncTrackingUiFromService() {
        if (TripTrackingService.isRunning) {
            gpsTracking = true
            showTrackingActiveUi(TripTrackingService.currentMeters)
        }
    }

    private fun showTrackingActiveUi(meters: Double) {
        binding.gpsStatus.visibility = View.VISIBLE
        binding.gpsStatus.text = if (meters < 1.0) {
            getString(R.string.gps_waiting)
        } else {
            getString(R.string.gps_tracking, meters / 1000.0)
        }
        binding.gpsButton.setColorFilter(
            ContextCompat.getColor(this, R.color.fuel_error)
        )
        binding.gpsButton.contentDescription = getString(R.string.cd_gps_stop)
    }

    private fun onTrackingUpdate(meters: Double) {
        gpsTracking = true
        showTrackingActiveUi(meters)
    }

    private fun onTrackingStopped(meters: Double, applyDistance: Boolean) {
        gpsTracking = false
        binding.gpsButton.setColorFilter(
            ContextCompat.getColor(this, R.color.fuel_primary)
        )
        binding.gpsButton.contentDescription = getString(R.string.cd_gps)

        // Clear any persisted pending distance; this UI path owns the apply.
        prefs.edit().remove(TripTrackingService.KEY_PENDING_GPS_KM).apply()

        val km = meters / 1000.0

        if (!applyDistance || km < MIN_APPLY_KM) {
            binding.gpsStatus.visibility = View.GONE
            return
        }

        applyGpsKilometers(km)
    }

    private fun applyPendingGpsKilometersIfNeeded() {
        if (gpsTracking || TripTrackingService.isRunning) return
        val pending = prefs.getFloat(TripTrackingService.KEY_PENDING_GPS_KM, Float.NaN)
        if (pending.isNaN() || pending < MIN_APPLY_KM.toFloat()) return
        prefs.edit().remove(TripTrackingService.KEY_PENDING_GPS_KM).apply()
        applyGpsKilometers(pending.toDouble())
    }

    private fun applyGpsKilometers(km: Double) {
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
        private const val MIN_APPLY_KM = 0.05
    }
}
