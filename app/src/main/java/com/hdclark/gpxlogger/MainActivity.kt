package com.hdclark.gpxlogger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var locationCountText: TextView
    private lateinit var lastLocationText: TextView
    private lateinit var gpxFileText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var settingsButton: Button

    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LocationService.ACTION_LOCATION_UPDATE -> {
                    val lat = intent.getDoubleExtra("latitude", 0.0)
                    val lon = intent.getDoubleExtra("longitude", 0.0)
                    val count = intent.getIntExtra("count", 0)
                    val fileName = intent.getStringExtra("fileName") ?: ""
                    
                    lastLocationText.text = String.format("%.6f, %.6f", lat, lon)
                    locationCountText.text = count.toString()
                    gpxFileText.text = fileName
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
    }

    override fun onDestroy() {
        super.onDestroy()
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
        
        // WRITE_EXTERNAL_STORAGE is only needed for API 23-28
        val storage = if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P) {
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
        
        // WRITE_EXTERNAL_STORAGE is only needed for API 23-28
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P) {
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
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
