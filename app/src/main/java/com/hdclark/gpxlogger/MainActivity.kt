package com.hdclark.gpxlogger

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var locationCountText: TextView
    private lateinit var lastLocationText: TextView
    private lateinit var gpxFileText: TextView
    private lateinit var durationText: TextView
    private lateinit var distanceText: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var settingsButton: Button
    private var batteryDialogShownThisSession = false
    
    // Statistics tracking
    private var startTime: Long = 0
    private var totalDistance: Float = 0f
    private var lastLocationUpdateTime: Long = 0
    
    // Handler for periodic UI updates
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (LocationService.isRunning) {
                updateElapsedTime()
                handler.postDelayed(this, UI_UPDATE_INTERVAL_MS)
            }
        }
    }

    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LocationService.ACTION_LOCATION_UPDATE -> {
                    val lat = intent.getDoubleExtra("latitude", 0.0)
                    val lon = intent.getDoubleExtra("longitude", 0.0)
                    val count = intent.getIntExtra("count", 0)
                    val fileName = intent.getStringExtra("fileName") ?: ""
                    startTime = intent.getLongExtra("startTime", 0)
                    totalDistance = intent.getFloatExtra("totalDistance", 0f)
                    lastLocationUpdateTime = intent.getLongExtra("lastLocationUpdateTime", 0)
                    
                    lastLocationText.text = String.format("%.6f, %.6f", lat, lon)
                    locationCountText.text = count.toString()
                    gpxFileText.text = fileName
                    updateStatisticsDisplay()
                }
                LocationService.ACTION_SERVICE_STARTED -> {
                    updateUIForRunningState(true)
                }
                LocationService.ACTION_SERVICE_STOPPED -> {
                    updateUIForRunningState(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        locationCountText = findViewById(R.id.locationCountText)
        lastLocationText = findViewById(R.id.lastLocationText)
        gpxFileText = findViewById(R.id.gpxFileText)
        durationText = findViewById(R.id.durationText)
        distanceText = findViewById(R.id.distanceText)
        lastUpdateText = findViewById(R.id.lastUpdateText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        settingsButton = findViewById(R.id.settingsButton)

        startButton.setOnClickListener {
            if (checkPermissions()) {
                startLocationService()
            } else {
                requestPermissions()
            }
        }

        stopButton.setOnClickListener {
            stopLocationService()
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Register broadcast receiver
        val filter = IntentFilter().apply {
            addAction(LocationService.ACTION_LOCATION_UPDATE)
            addAction(LocationService.ACTION_SERVICE_STARTED)
            addAction(LocationService.ACTION_SERVICE_STOPPED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(locationUpdateReceiver, filter)

        // Check if service is already running
        if (LocationService.isRunning) {
            updateUIForRunningState(true)
        }

        // Check battery optimization status
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                // Check if user has already dismissed the dialog
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val dismissed = prefs.getBoolean(KEY_BATTERY_DIALOG_DISMISSED, false)
                // Prevent showing dialog during config changes or if already shown this session
                if (!dismissed && !batteryDialogShownThisSession && !isFinishing) {
                    showBatteryOptimizationDialog()
                }
            } else {
                // Battery optimization is disabled; clear any previous dismissal so the
                // dialog can be shown again if optimization is re-enabled later.
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_BATTERY_DIALOG_DISMISSED, false)) {
                    prefs.edit().remove(KEY_BATTERY_DIALOG_DISMISSED).apply()
                }
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        batteryDialogShownThisSession = true
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_message)
            .setPositiveButton(R.string.battery_optimization_disable) { _, _ ->
                requestBatteryOptimizationExemption()
            }
            .setNegativeButton(R.string.battery_optimization_later) { _, _ ->
                // Remember that user dismissed the dialog
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_BATTERY_DIALOG_DISMISSED, true).apply()
            }
            .show()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    R.string.battery_settings_not_available,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(locationUpdateReceiver)
    }

    private fun checkPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        
        // WRITE_EXTERNAL_STORAGE is only needed for API 26-28
        val storage = if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return fineLocation && notification && storage
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // WRITE_EXTERNAL_STORAGE is only needed for API 26-28
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationService()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopLocationService() {
        val intent = Intent(this, LocationService::class.java)
        stopService(intent)
    }

    private fun updateUIForRunningState(isRunning: Boolean) {
        statusText.text = if (isRunning) getString(R.string.status_running) else getString(R.string.status_stopped)
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
        
        if (isRunning) {
            // Start periodic updates
            handler.post(updateRunnable)
        } else {
            // Stop periodic updates and reset statistics display
            handler.removeCallbacks(updateRunnable)
            resetStatisticsDisplay()
        }
    }
    
    private fun updateStatisticsDisplay() {
        // Update duration
        if (startTime > 0) {
            val durationMs = System.currentTimeMillis() - startTime
            durationText.text = formatDuration(durationMs)
        }
        
        // Update distance
        distanceText.text = formatDistance(totalDistance)
        
        // Update elapsed time since last update
        updateElapsedTime()
    }
    
    private fun updateElapsedTime() {
        if (lastLocationUpdateTime > 0) {
            val elapsedSeconds = ((System.currentTimeMillis() - lastLocationUpdateTime) / MILLIS_PER_SECOND).toInt()
            lastUpdateText.text = getString(R.string.seconds_ago, elapsedSeconds)
        }
    }
    
    private fun resetStatisticsDisplay() {
        durationText.text = "-"
        distanceText.text = "-"
        lastUpdateText.text = "-"
        startTime = 0
        totalDistance = 0f
        lastLocationUpdateTime = 0
    }
    
    private fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds / MILLIS_PER_SECOND) % SECONDS_PER_MINUTE
        val minutes = (milliseconds / (MILLIS_PER_SECOND * SECONDS_PER_MINUTE)) % MINUTES_PER_HOUR
        val hours = milliseconds / (MILLIS_PER_SECOND * SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatDistance(meters: Float): String {
        return if (meters >= METERS_PER_KILOMETER) {
            String.format("%.2f km", meters / METERS_PER_KILOMETER)
        } else {
            String.format("%.0f m", meters)
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val PREFS_NAME = "GPXLoggerPrefs"
        private const val KEY_BATTERY_DIALOG_DISMISSED = "battery_dialog_dismissed"
        private const val UI_UPDATE_INTERVAL_MS = 1000L
        private const val MILLIS_PER_SECOND = 1000L
        private const val SECONDS_PER_MINUTE = 60
        private const val MINUTES_PER_HOUR = 60
        private const val METERS_PER_KILOMETER = 1000
    }
}
