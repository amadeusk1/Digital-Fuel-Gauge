package com.digitalfuelgauge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class TripTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var gpsMeters = 0.0
    private var lastGpsLocation: Location? = null
    private var applyDistanceOnStop = true

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            onGpsLocation(location)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                applyDistanceOnStop = intent.getBooleanExtra(EXTRA_APPLY_DISTANCE, true)
                stopTracking()
            }
            else -> {
                if (!isRunning) {
                    if (!hasLocationPermission()) {
                        sendBroadcast(
                            Intent(ACTION_STOPPED).apply {
                                setPackage(packageName)
                                putExtra(EXTRA_METERS, 0.0)
                                putExtra(EXTRA_APPLY_DISTANCE, false)
                            }
                        )
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startTracking()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep tracking in the foreground service; do not stop on app swipe-away.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (isRunning) {
            // Process/system teardown — persist distance so MainActivity can apply it later.
            persistPendingIfNeeded(gpsMeters, apply = true)
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (_: SecurityException) {
                // Permission may have been revoked.
            }
            isRunning = false
            currentMeters = 0.0
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        gpsMeters = 0.0
        lastGpsLocation = null
        currentMeters = 0.0
        isRunning = true
        applyDistanceOnStop = true

        prefs().edit()
            .remove(KEY_PENDING_GPS_KM)
            .commit()

        val notification = buildNotification(getString(R.string.gps_waiting))
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        )

        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateIntervalMillis(1000L)
                .setMinUpdateDistanceMeters(3f)
                .setWaitForAccurateLocation(false)
                .build()
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            isRunning = false
            currentMeters = 0.0
            sendBroadcast(
                Intent(ACTION_STOPPED).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_METERS, 0.0)
                    putExtra(EXTRA_APPLY_DISTANCE, false)
                }
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        broadcastUpdate()
    }

    private fun onGpsLocation(location: Location) {
        if (!isRunning) return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_METERS) return
        if (location.isFromMockProviderCompat()) return

        val previous = lastGpsLocation
        lastGpsLocation = location
        if (previous != null) {
            val dtMs = location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos
            if (dtMs <= 0L) return

            val delta = previous.distanceTo(location).toDouble()
            // Ignore tiny GPS jitter and impossible jumps.
            if (delta >= MIN_SEGMENT_METERS && delta < MAX_SEGMENT_METERS) {
                val dtSec = dtMs / 1_000_000_000.0
                val speedMps = if (dtSec > 0.0) delta / dtSec else 0.0
                if (speedMps <= MAX_SPEED_MPS) {
                    gpsMeters += delta
                }
            }
        }

        currentMeters = gpsMeters
        updateNotification()
        broadcastUpdate()
    }

    private fun stopTracking() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (_: SecurityException) {
            // Permission may have been revoked.
        }

        val meters = gpsMeters
        gpsMeters = 0.0
        lastGpsLocation = null
        currentMeters = 0.0
        isRunning = false

        // commit() so MainActivity can safely read pending before/with the broadcast.
        if (applyDistanceOnStop) {
            persistPendingIfNeeded(meters, apply = true)
        } else {
            prefs().edit().remove(KEY_PENDING_GPS_KM).commit()
        }

        sendBroadcast(
            Intent(ACTION_STOPPED).apply {
                setPackage(packageName)
                putExtra(EXTRA_METERS, meters)
                putExtra(EXTRA_APPLY_DISTANCE, applyDistanceOnStop)
            }
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun persistPendingIfNeeded(meters: Double, apply: Boolean) {
        if (!apply) return
        val km = meters / 1000.0
        if (km < MIN_APPLY_KM) {
            prefs().edit().remove(KEY_PENDING_GPS_KM).commit()
            return
        }
        prefs().edit()
            .putFloat(KEY_PENDING_GPS_KM, km.toFloat())
            .commit()
    }

    private fun broadcastUpdate() {
        sendBroadcast(
            Intent(ACTION_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_METERS, gpsMeters)
            }
        )
    }

    private fun updateNotification() {
        val km = gpsMeters / 1000.0
        val text = if (gpsMeters < 1.0) {
            getString(R.string.gps_waiting)
        } else {
            getString(R.string.notification_tracking, km)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TripTrackingService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_APPLY_DISTANCE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_gps)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun prefs(): SharedPreferences {
        VehicleStore.ensureMigrated(this)
        return VehicleStore.prefs(this)
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

    private fun Location.isFromMockProviderCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        }
    }

    companion object {
        const val CHANNEL_ID = "trip_tracking"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.digitalfuelgauge.action.STOP_TRACKING"
        const val ACTION_UPDATE = "com.digitalfuelgauge.action.TRACKING_UPDATE"
        const val ACTION_STOPPED = "com.digitalfuelgauge.action.TRACKING_STOPPED"
        const val EXTRA_METERS = "meters"
        const val EXTRA_APPLY_DISTANCE = "apply_distance"
        const val KEY_PENDING_GPS_KM = "pending_gps_km"
        const val KEY_GPS_BASELINE_KM = "gps_baseline_km"

        private const val MAX_ACCURACY_METERS = 50f
        private const val MIN_SEGMENT_METERS = 3.0
        private const val MAX_SEGMENT_METERS = 2000.0
        private const val MAX_SPEED_MPS = 70.0 // ~252 km/h
        private const val MIN_APPLY_KM = 0.05

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var currentMeters = 0.0
            private set

        fun start(context: Context) {
            val intent = Intent(context, TripTrackingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, applyDistance: Boolean) {
            if (!isRunning) return
            val intent = Intent(context, TripTrackingService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_APPLY_DISTANCE, applyDistance)
            }
            // Service is already a started foreground service; startService delivers the stop.
            context.startService(intent)
        }
    }
}
