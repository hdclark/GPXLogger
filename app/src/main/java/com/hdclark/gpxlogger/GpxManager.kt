package com.hdclark.gpxlogger

import android.content.Context
import android.location.Location
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
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "gpx_track_$timestamp.gpx"
        val file = File(context.getExternalFilesDir(null), fileName)
        
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
    }
    
    fun closeTrack() {
        // Flush any remaining cached locations
        flushCache()
        
        val file = currentFile ?: return
        
        // Write GPX footer
        FileWriter(file, true).use { writer ->
            writer.write("""    </trkseg>
  </trk>
</gpx>
""")
        }
        
        currentFile = null
    }
    
    fun getCurrentFileName(): String {
        return currentFile?.name ?: ""
    }
}
