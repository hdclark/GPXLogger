package com.hdclark.gpxlogger

import android.content.Context
import android.location.Location
import android.os.Build
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileWriter
import java.io.IOException
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
            
            // Use app-specific external storage which works reliably on all Android versions
            // On Android 10+ (API 29+), scoped storage restricts direct access to public directories
            // App-specific external storage is accessible without special permissions
            val baseDir = getAppExternalStorageDirectory()
            val gpxDir = File(baseDir, storagePath)
            
            // Verify the resolved path is within the base directory to prevent directory traversal
            val isValidPath = try {
                gpxDir.canonicalPath.startsWith(baseDir.canonicalPath)
            } catch (e: IOException) {
                android.util.Log.e("GpxManager", "Error resolving canonical path, using default", e)
                false
            }
            
            if (!isValidPath) {
                android.util.Log.e("GpxManager", "Invalid storage path detected, using default")
                return initializeTrackFile(baseDir, DEFAULT_STORAGE_FOLDER, fileName)
            }
            
            initializeTrackFile(baseDir, storagePath, fileName)
        } catch (e: Exception) {
            android.util.Log.e("GpxManager", "Error starting new track", e)
            null
        }
    }
    
    /**
     * Common helper method to initialize a track file with GPX header.
     * Eliminates code duplication between startNewTrack and fallback path.
     */
    private fun initializeTrackFile(baseDir: File, folderName: String, fileName: String): File? {
        val gpxDir = File(baseDir, folderName)
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
     * Gets the app-specific external storage directory for storing GPX files.
     * This directory is accessible without special permissions on all Android versions.
     * On Android 10+ (API 29+), this is the recommended approach as scoped storage
     * restricts direct access to public directories.
     * 
     * Files stored here can still be accessed via file managers that support
     * browsing app-specific directories (e.g., at Android/data/com.hdclark.gpxlogger/files/).
     */
    private fun getAppExternalStorageDirectory(): File {
        return context.getExternalFilesDir(null) ?: context.filesDir
    }
    
    /**
     * Returns the full path where GPX files will be stored.
     * This can be used to display the path in settings.
     */
    fun getStorageDirectory(): File {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rawStoragePath = prefs.getString("storage_path", DEFAULT_STORAGE_FOLDER)?.takeIf { it.isNotBlank() } ?: DEFAULT_STORAGE_FOLDER
        val storagePath = sanitizeFolderName(rawStoragePath)
        
        val baseDir = getAppExternalStorageDirectory()
        return File(baseDir, storagePath)
    }
    
    /**
     * Returns the full path where GPX files would be stored for a given folder name.
     * This can be used to preview the path before saving settings.
     */
    fun getStorageDirectoryForFolder(folderName: String?): File {
        val storagePath = sanitizeFolderName(folderName?.takeIf { it.isNotBlank() } ?: DEFAULT_STORAGE_FOLDER)
        val baseDir = getAppExternalStorageDirectory()
        return File(baseDir, storagePath)
    }
    
    /**
     * Returns information about the storage location accessibility.
     * On Android 10+ (API 29+), app-specific external storage may have limited visibility
     * in some file browsers due to scoped storage restrictions.
     */
    fun getStorageAccessibilityInfo(): StorageAccessibilityInfo {
        val directory = getStorageDirectory()
        // On Android 9 (API 28) and below, files are directly accessible by all file manager apps
        // On Android 10+ (API 29+), scoped storage restricts access to app-specific directories
        val hasDirectFileAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        
        return StorageAccessibilityInfo(
            fullPath = directory.absolutePath,
            isFullyAccessible = hasDirectFileAccess,
            message = if (hasDirectFileAccess) {
                "Files are accessible via standard file manager apps"
            } else {
                "Files are stored in app-specific storage. Access via file manager at: Android/data/${context.packageName}/files/"
            }
        )
    }
    
    data class StorageAccessibilityInfo(
        val fullPath: String,
        val isFullyAccessible: Boolean,
        val message: String
    )
    
    companion object {
        private const val DEFAULT_STORAGE_FOLDER = "GPXLogger"
    }
}
