package com.hdclark.gpxlogger

import android.content.Context
import android.location.Location
import android.os.Environment
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class GpxManager(private val context: Context) {
    private var currentFile: File? = null
    private var locationCache = mutableListOf<Location>()
    private var lastFlushTime = System.currentTimeMillis()
    private var consecutiveFlushFailures = 0
    private val maxFlushRetries = 3
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    
    @Synchronized
    fun startNewTrack(): File? {
        return try {
            val timestamp = fileNameFormat.format(Date())
            val fileName = "$timestamp.gpx"
            
            // Get storage path from preferences and sanitize it
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val rawStoragePath = prefs.getString("storage_path", DEFAULT_STORAGE_FOLDER)?.takeIf { it.isNotBlank() } ?: DEFAULT_STORAGE_FOLDER
            val storagePath = sanitizeFolderName(rawStoragePath)
            
            // Store in app-specific external Downloads directory (scoped-storage compatible)
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val gpxDir = File(baseDir, storagePath)
            
            // Verify the resolved path is within the base directory to prevent directory traversal
            if (!gpxDir.canonicalPath.startsWith(baseDir.canonicalPath)) {
                android.util.Log.e("GpxManager", "Invalid storage path detected, using default")
                return startNewTrackWithDefaultPath(baseDir, fileName)
            }
            
            if (!gpxDir.exists()) {
                val created = gpxDir.mkdirs()
                if (!created && !gpxDir.exists()) {
                    throw IllegalStateException("Failed to create GPX directory: ${gpxDir.absolutePath}")
                }
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
            consecutiveFlushFailures = 0
            file
        } catch (e: Exception) {
            android.util.Log.e("GpxManager", "Error starting new track", e)
            null
        }
    }
    
    @Synchronized
    fun addLocation(location: Location) {
        locationCache.add(location)
        
        // Don't attempt flush if we've exceeded retry limit
        if (consecutiveFlushFailures >= maxFlushRetries) {
            return
        }
        
        // Flush cache if 15 minutes have passed or cache is large
        val currentTime = System.currentTimeMillis()
        val fifteenMinutesInMs = 15 * 60 * 1000
        
        if (currentTime - lastFlushTime >= fifteenMinutesInMs || locationCache.size >= 100) {
            flushCache()
        }
    }
    
    @Synchronized
    private fun flushCache(): Boolean {
        return flushCacheInternal(forceFlush = false)
    }
    
    @Synchronized
    private fun flushCacheInternal(forceFlush: Boolean): Boolean {
        val file = currentFile ?: return false
        if (locationCache.isEmpty()) return true
        
        // Skip flush if retry limit exceeded (unless forced for emergency/close)
        if (!forceFlush && consecutiveFlushFailures >= maxFlushRetries) {
            return false
        }
        
        return try {
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
            consecutiveFlushFailures = 0
            true
        } catch (e: Exception) {
            // Log but don't clear cache if write fails, allowing retry
            android.util.Log.e("GpxManager", "Error flushing cache to file", e)
            consecutiveFlushFailures++
            false
        }
    }
    
    @Synchronized
    fun closeTrack() {
        // Flush any remaining cached locations - force flush even if retry limit exceeded
        flushCacheInternal(forceFlush = true)
        
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
     * Delegates to closeTrack() to avoid code duplication.
     */
    @Synchronized
    fun emergencyFlush() {
        // Delegate to closeTrack() so flushing and footer-writing logic stay in one place
        closeTrack()
    }
    
    /**
     * Returns true if flush operations have failed too many times and the manager
     * should stop attempting to write.
     */
    @Synchronized
    fun hasExceededRetryLimit(): Boolean {
        return consecutiveFlushFailures >= maxFlushRetries
    }
    
    @Synchronized
    fun getCurrentFileName(): String {
        return currentFile?.name ?: ""
    }
    
    /**
     * Sanitizes a folder name by removing directory traversal sequences and 
     * filesystem-unsafe characters.
     */
    private fun sanitizeFolderName(name: String): String {
        // Remove directory separators and parent directory references
        var sanitized = name.replace(Regex("[/\\\\]"), "_")
        sanitized = sanitized.replace("..", "_")
        
        // Remove other filesystem-unsafe characters
        sanitized = sanitized.replace(Regex("[<>:\"|?*]"), "_")
        
        // Remove leading/trailing dots and spaces
        sanitized = sanitized.trim().trim('.')
        
        // If result is empty or only underscores, use default
        return sanitized.takeIf { it.isNotBlank() && it.any { c -> c != '_' } } ?: DEFAULT_STORAGE_FOLDER
    }
    
    /**
     * Fallback method to create a track with the default storage folder.
     */
    private fun startNewTrackWithDefaultPath(baseDir: File, fileName: String): File? {
        val gpxDir = File(baseDir, DEFAULT_STORAGE_FOLDER)
        if (!gpxDir.exists()) {
            val created = gpxDir.mkdirs()
            if (!created && !gpxDir.exists()) {
                throw IllegalStateException("Failed to create GPX directory: ${gpxDir.absolutePath}")
            }
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
        consecutiveFlushFailures = 0
        return file
    }
    
    companion object {
        private const val DEFAULT_STORAGE_FOLDER = "GPXLogger"
    }
}
