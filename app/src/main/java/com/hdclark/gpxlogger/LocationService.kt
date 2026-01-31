package com.hdclark.gpxlogger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.google.android.gms.location.*

class LocationService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var gpxManager: GpxManager
    private var locationCount = 0
    private var startTime: Long = 0
    private var totalDistance: Float = 0f
    private var lastLocation: Location? = null
    private var lastNotificationUpdate: Long = 0
    private var lastLocationUpdateTime: Long = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        gpxManager = GpxManager(this)
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    handleLocationUpdate(location)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Acquire wake lock to prevent CPU from sleeping
        acquireWakeLock()
        
        startLocationUpdates()
        
        isRunning = true
        
        // Notify UI that service started
        val startIntent = Intent(ACTION_SERVICE_STARTED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(startIntent)
        
        return START_STICKY
    }

    private fun acquireWakeLock() {
        // Release any existing wake lock first to prevent leaks if onStartCommand is called multiple times
        // This is safe to call even if no wake lock exists (releaseWakeLock handles null case)
        releaseWakeLock()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GPXLogger::LocationWakeLock"
        ).apply {
            // Acquire with 1-hour timeout as a failsafe; service normally releases on stop
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun startLocationUpdates() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalSeconds = prefs.getString("logging_interval", "10")?.toLongOrNull() ?: 10L
        val intervalMs = intervalSeconds * 1000
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs
        ).apply {
            setMinUpdateIntervalMillis(intervalMs)
            setMaxUpdateDelayMillis(intervalMs * 2)
        }.build()

        try {
            // Start new GPX track
            val file = gpxManager.startNewTrack()
            if (file == null) {
                android.util.Log.e("LocationService", "Failed to create GPX file, stopping service")
                stopSelf()
                return
            }
            locationCount = 0
            startTime = System.currentTimeMillis()
            totalDistance = 0f
            lastLocation = null
            lastNotificationUpdate = 0
            
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            
            // Update notification immediately to show initial state
            updateNotification()
        } catch (e: SecurityException) {
            // Permission not granted
            stopSelf()
        }
    }

    private fun handleLocationUpdate(location: Location) {
        try {
            locationCount++
            lastLocationUpdateTime = System.currentTimeMillis()

            // Calculate distance from last location
            lastLocation?.let { last ->
                totalDistance += last.distanceTo(location)
            }
            lastLocation = location
        
            gpxManager.addLocation(location)
        
            // Update notification with statistics (throttled to once every 5 seconds)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastNotificationUpdate >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                updateNotification()
                lastNotificationUpdate = currentTime
            }
            
            // Check if file operations are consistently failing
            if (gpxManager.hasExceededRetryLimit()) {
                android.util.Log.e("LocationService", "File operations failed too many times, stopping service")
                handleFatalError()
                return
            }
            
            // Broadcast location update to UI
            val intent = Intent(ACTION_LOCATION_UPDATE).apply {
                putExtra("latitude", location.latitude)
                putExtra("longitude", location.longitude)
                putExtra("count", locationCount)
                putExtra("fileName", gpxManager.getCurrentFileName())
                putExtra("startTime", startTime)
                putExtra("totalDistance", totalDistance)
                putExtra("lastLocationUpdateTime", lastLocationUpdateTime)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error handling location update", e)
            handleFatalError()
        }
    }
    
    /**
     * Handles fatal errors by attempting to save data and stopping the service.
     * This method performs emergency flush, removes location updates, notifies the UI,
     * and stops the service.
     */
    private fun handleFatalError() {
        // Attempt emergency flush to save as much data as possible
        gpxManager.emergencyFlush()
        // Stop receiving further location updates
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (removeEx: Exception) {
            android.util.Log.e("LocationService", "Error removing location updates after failure", removeEx)
        }
        // Notify UI that the service is stopping due to an error
        val errorIntent = Intent(ACTION_SERVICE_STOPPED).apply {
            putExtra("stopped_due_to_error", true)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(errorIntent)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error removing location updates", e)
        }
        
        try {
            gpxManager.closeTrack()
        } catch (e: Exception) {
            android.util.Log.e("LocationService", "Error closing track", e)
            // Attempt emergency flush to save as much data as possible
            gpxManager.emergencyFlush()
        }
        
        // Release wake lock
        releaseWakeLock()
        
        isRunning = false
        
        // Notify UI that service stopped
        val stopIntent = Intent(ACTION_SERVICE_STOPPED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(stopIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.logging_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GPS logging service notifications"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.logging_notification_title))
            .setContentText(getString(R.string.logging_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val duration = FormatUtils.formatDuration(System.currentTimeMillis() - startTime)
        val distance = FormatUtils.formatDistance(totalDistance)
        val statsText = "$duration • $distance • $locationCount points"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.logging_notification_title))
            .setContentText(statsText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "GPXLoggerChannel"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 5000L
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour failsafe timeout
        
        const val ACTION_LOCATION_UPDATE = "com.hdclark.gpxlogger.LOCATION_UPDATE"
        const val ACTION_SERVICE_STARTED = "com.hdclark.gpxlogger.SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED = "com.hdclark.gpxlogger.SERVICE_STOPPED"
        
        var isRunning = false
            private set
    }
}
