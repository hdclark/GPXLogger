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
        
        startLocationUpdates()
        
        isRunning = true
        
        // Notify UI that service started
        val startIntent = Intent(ACTION_SERVICE_STARTED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(startIntent)
        
        return START_STICKY
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
            gpxManager.startNewTrack()
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
        } catch (e: SecurityException) {
            // Permission not granted
            stopSelf()
        }
    }

    private fun handleLocationUpdate(location: Location) {
        locationCount++
        
        // Calculate distance from last location
        lastLocation?.let { last ->
            totalDistance += last.distanceTo(location)
        }
        lastLocation = location
        
        gpxManager.addLocation(location)
        
        // Update notification with statistics (throttled to once every 5 seconds)
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationUpdate >= 5000) {
            updateNotification()
            lastNotificationUpdate = currentTime
        }
        
        // Broadcast location update to UI
        val intent = Intent(ACTION_LOCATION_UPDATE).apply {
            putExtra("latitude", location.latitude)
            putExtra("longitude", location.longitude)
            putExtra("count", locationCount)
            putExtra("fileName", gpxManager.getCurrentFileName())
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        
        fusedLocationClient.removeLocationUpdates(locationCallback)
        gpxManager.closeTrack()
        
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

        val duration = formatDuration(System.currentTimeMillis() - startTime)
        val distance = formatDistance(totalDistance)
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

    private fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds / 1000) % 60
        val minutes = (milliseconds / (1000 * 60)) % 60
        val hours = milliseconds / (1000 * 60 * 60)
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatDistance(meters: Float): String {
        return if (meters >= 1000) {
            String.format("%.2f km", meters / 1000)
        } else {
            String.format("%.0f m", meters)
        }
    }

    companion object {
        private const val CHANNEL_ID = "GPXLoggerChannel"
        private const val NOTIFICATION_ID = 1
        
        const val ACTION_LOCATION_UPDATE = "com.hdclark.gpxlogger.LOCATION_UPDATE"
        const val ACTION_SERVICE_STARTED = "com.hdclark.gpxlogger.SERVICE_STARTED"
        const val ACTION_SERVICE_STOPPED = "com.hdclark.gpxlogger.SERVICE_STOPPED"
        
        var isRunning = false
            private set
    }
}
