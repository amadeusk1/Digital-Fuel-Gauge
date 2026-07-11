package com.fuelcheck

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
                return START_NOT_STICKY
            }
            else -> {
                if (!isRunning) {
                    startTracking()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (isRunning) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
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
        getSharedPreferences(FuelAddedActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_GPS_KM)
            .apply()

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

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )

        broadcastUpdate()
    }

    private fun onGpsLocation(location: Location) {
        if (!isRunning) return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_METERS) return

        val previous = lastGpsLocation
        lastGpsLocation = location
        if (previous != null) {
            val delta = previous.distanceTo(location).toDouble()
            if (delta >= MIN_SEGMENT_METERS && delta < MAX_SEGMENT_METERS) {
                gpsMeters += delta
            }
        }

        currentMeters = gpsMeters
        updateNotification()
        broadcastUpdate()
    }

    private fun stopTracking() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val meters = gpsMeters
        gpsMeters = 0.0
        lastGpsLocation = null
        currentMeters = 0.0
        isRunning = false

        if (applyDistanceOnStop && meters / 1000.0 >= 0.05) {
            getSharedPreferences(FuelAddedActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putFloat(KEY_PENDING_GPS_KM, (meters / 1000.0).toFloat())
                .apply()
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

    companion object {
        const val CHANNEL_ID = "trip_tracking"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.fuelcheck.action.STOP_TRACKING"
        const val ACTION_UPDATE = "com.fuelcheck.action.TRACKING_UPDATE"
        const val ACTION_STOPPED = "com.fuelcheck.action.TRACKING_STOPPED"
        const val EXTRA_METERS = "meters"
        const val EXTRA_APPLY_DISTANCE = "apply_distance"
        const val KEY_PENDING_GPS_KM = "pending_gps_km"

        private const val MAX_ACCURACY_METERS = 40f
        private const val MIN_SEGMENT_METERS = 5.0
        private const val MAX_SEGMENT_METERS = 2000.0

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
            context.startService(intent)
        }
    }
}
