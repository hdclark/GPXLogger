package com.hdclark.gpxlogger

import android.content.Context
import android.location.Location
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class GpxManager(private val context: Context) {
    private var currentFile: File? = null
    private var locationCache = mutableListOf<Location>()
    private var lastFlushTime = System.currentTimeMillis()
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    fun startNewTrack(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "$timestamp.gpx"
        
        // Store in Downloads/GPXLogger directory which is accessible via standard file browsers
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val gpxDir = File(downloadsDir, "GPXLogger")
        if (!gpxDir.exists()) {
            gpxDir.mkdirs()
        }
        val file = File(gpxDir, fileName)
        
        // Write GPX header
        FileWriter(file, false).use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="GPXLogger"
  xmlns="http://www.topografix.com/GPX/1/1"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <trk>
    <name>GPS Track ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}</name>
    <trkseg>
""")
        }
        
        currentFile = file
        locationCache.clear()
        lastFlushTime = System.currentTimeMillis()
        return file
    }
    
    fun addLocation(location: Location) {
        locationCache.add(location)
        
        // Flush cache if 15 minutes have passed or cache is large
        val currentTime = System.currentTimeMillis()
        val fifteenMinutesInMs = 15 * 60 * 1000
        
        if (currentTime - lastFlushTime >= fifteenMinutesInMs || locationCache.size >= 100) {
            flushCache()
        }
    }
    
    private fun flushCache() {
        val file = currentFile ?: return
        if (locationCache.isEmpty()) return
        
        try {
            FileWriter(file, true).use { writer ->
                for (location in locationCache) {
                    val timestamp = dateFormat.format(Date(location.time))
                    writer.write("""      <trkpt lat="${location.latitude}" lon="${location.longitude}">
        <ele>${location.altitude}</ele>
        <time>$timestamp</time>
      </trkpt>
""")
                }
            }
            
            locationCache.clear()
            lastFlushTime = System.currentTimeMillis()
        } catch (e: Exception) {
            // Log but don't clear cache if write fails, allowing retry
            android.util.Log.e("GpxManager", "Error flushing cache to file", e)
        }
    }
    
    fun closeTrack() {
        // Flush any remaining cached locations
        flushCache()
        
        val file = currentFile ?: return
        
        try {
            // Write GPX footer
            FileWriter(file, true).use { writer ->
                writer.write("""    </trkseg>
  </trk>
</gpx>
""")
            }
        } catch (e: Exception) {
            android.util.Log.e("GpxManager", "Error writing GPX footer", e)
        }
        
        currentFile = null
    }
    
    /**
     * Emergency flush for error conditions. Attempts to flush all cached data
     * and close the file even if the information is incomplete.
     */
    fun emergencyFlush() {
        val file = currentFile ?: return
        
        try {
            // First, try to flush any cached locations
            if (locationCache.isNotEmpty()) {
                FileWriter(file, true).use { writer ->
                    for (location in locationCache) {
                        val timestamp = dateFormat.format(Date(location.time))
                        writer.write("""      <trkpt lat="${location.latitude}" lon="${location.longitude}">
        <ele>${location.altitude}</ele>
        <time>$timestamp</time>
      </trkpt>
""")
                    }
                }
                locationCache.clear()
            }
            
            // Then write the GPX footer to make the file valid
            FileWriter(file, true).use { writer ->
                writer.write("""    </trkseg>
  </trk>
</gpx>
""")
            }
        } catch (e: Exception) {
            android.util.Log.e("GpxManager", "Error during emergency flush", e)
        }
        
        currentFile = null
    }
    
    fun getCurrentFileName(): String {
        return currentFile?.name ?: ""
    }
}
