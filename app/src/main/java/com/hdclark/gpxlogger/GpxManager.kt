package com.hdclark.gpxlogger

import android.content.Context
import android.location.Location
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class GpxManager(private val context: Context) {
    private var currentFile: File? = null
    private var currentFileName: String? = null
    
    private var locationCache = mutableListOf<Location>()
    private var lastFlushTime = System.currentTimeMillis()
    private var consecutiveFlushFailures = 0
    private val maxFlushRetries = 3
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    
    private fun generateGpxHeader(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="GPXLogger"
  xmlns="http://www.topografix.com/GPX/1/1"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">
  <trk>
    <name>GPS Track ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}</name>
    <trkseg>
"""
    }
    
    @Synchronized
    fun startNewTrack(): File? {
        return try {
            val timestamp = fileNameFormat.format(Date())
            val fileName = "$timestamp.gpx"
            
            // Get storage path from preferences and sanitize it
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val rawStoragePath = prefs.getString("storage_path", DEFAULT_STORAGE_FOLDER)?.takeIf { it.isNotBlank() } ?: DEFAULT_STORAGE_FOLDER
            val storagePath = sanitizeFolderName(rawStoragePath)
            
            // Prefer the app's external media directory, which is generally accessible
            // to other apps (e.g., file browsers, Termux, syncing apps).
            val baseDir = getMediaBaseDirectory()
            initializeTrackWithFileIO(baseDir, storagePath, fileName)
        } catch (e: Exception) {
            android.util.Log.e("GpxManager", "Error starting new track", e)
            null
        }
    }
    
    /**
     * Returns the preferred storage directory. Prefers the external media directory
     * (Android/media/<package_name>/) which is generally accessible to other apps,
     * but may fall back to app-private storage if the media directory is unavailable.
     */
    private fun getMediaBaseDirectory(): File {
        val mediaDirs = context.externalMediaDirs
        if (mediaDirs.isNotEmpty() && mediaDirs[0] != null) {
            return mediaDirs[0]
        }
        // Fallback (should not normally occur on API 26+)
        android.util.Log.w("GpxManager", "externalMediaDirs unavailable, falling back to app-private storage")
        return context.getExternalFilesDir(null) ?: context.filesDir
    }
    
    /**
     * Initialize a track file using direct File I/O.
     */
    private fun initializeTrackWithFileIO(baseDir: File, folderName: String, fileName: String): File? {
        val gpxDir = File(baseDir, folderName)
        
        // Verify the resolved path is within the base directory to prevent directory traversal
        val isValidPath = try {
            gpxDir.canonicalPath.startsWith(baseDir.canonicalPath)
        } catch (e: IOException) {
            android.util.Log.e("GpxManager", "Error resolving canonical path, using default", e)
            false
        }
        
        val targetDir = if (isValidPath) gpxDir else File(baseDir, DEFAULT_STORAGE_FOLDER)
        
        if (!targetDir.exists()) {
            val created = targetDir.mkdirs()
            if (!created && !targetDir.exists()) {
                throw IllegalStateException("Failed to create GPX directory: ${targetDir.absolutePath}")
            }
        }
        
        val file = File(targetDir, fileName)
        
        // Write GPX header
        FileWriter(file, false).use { writer ->
            writer.write(generateGpxHeader())
        }
        
        currentFile = file
        currentFileName = fileName
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
        if (currentFile == null) return false
        if (locationCache.isEmpty()) return true
        
        // Skip flush if retry limit exceeded (unless forced for emergency/close)
        if (!forceFlush && consecutiveFlushFailures >= maxFlushRetries) {
            return false
        }
        
        return try {
            val trackPoints = buildTrackPointsXml()
            val file = currentFile ?: return false
            
            FileWriter(file, true).use { writer ->
                writer.write(trackPoints)
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
    
    private fun buildTrackPointsXml(): String {
        val sb = StringBuilder()
        for (location in locationCache) {
            val timestamp = dateFormat.format(Date(location.time))
            sb.append("""      <trkpt lat="${location.latitude}" lon="${location.longitude}">
        <ele>${location.altitude}</ele>
        <time>$timestamp</time>
      </trkpt>
""")
        }
        return sb.toString()
    }
    
    @Synchronized
    fun closeTrack() {
        // Flush any remaining cached locations - force flush even if retry limit exceeded
        flushCacheInternal(forceFlush = true)
        
        try {
            val file = currentFile
            if (file != null) {
                FileWriter(file, true).use { writer ->
                    writer.write(GPX_FOOTER)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GpxManager", "Error writing GPX footer", e)
        }
        
        currentFile = null
        currentFileName = null
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
        return currentFileName ?: ""
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
     * Returns the full path where GPX files will be stored.
     * This can be used to display the path in settings.
     */
    fun getStorageDirectory(): File {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rawStoragePath = prefs.getString("storage_path", DEFAULT_STORAGE_FOLDER)?.takeIf { it.isNotBlank() } ?: DEFAULT_STORAGE_FOLDER
        val storagePath = sanitizeFolderName(rawStoragePath)
        
        val baseDir = getMediaBaseDirectory()
        return File(baseDir, storagePath)
    }
    
    /**
     * Returns the full path where GPX files would be stored for a given folder name.
     * This can be used to preview the path before saving settings.
     */
    fun getStorageDirectoryForFolder(folderName: String?): File {
        val storagePath = sanitizeFolderName(folderName?.takeIf { it.isNotBlank() } ?: DEFAULT_STORAGE_FOLDER)
        val baseDir = getMediaBaseDirectory()
        return File(baseDir, storagePath)
    }
    
    /**
     * Returns information about the storage location accessibility.
     * Checks whether the resolved base directory is the external media directory
     * (accessible to other apps) or a fallback app-private directory.
     */
    fun getStorageAccessibilityInfo(): StorageAccessibilityInfo {
        val directory = getStorageDirectory()
        val baseDir = getMediaBaseDirectory()
        val mediaDirs = context.externalMediaDirs
        val isMediaDir = mediaDirs.isNotEmpty() && mediaDirs[0] != null &&
            baseDir.absolutePath == mediaDirs[0].absolutePath
        
        return if (isMediaDir) {
            StorageAccessibilityInfo(
                fullPath = directory.absolutePath,
                isFullyAccessible = true,
                message = "Files are saved to the Android/media/ directory and accessible to other apps"
            )
        } else {
            StorageAccessibilityInfo(
                fullPath = directory.absolutePath,
                isFullyAccessible = false,
                message = "Warning: files are in app-private storage and may not be accessible to other apps"
            )
        }
    }
    
    data class StorageAccessibilityInfo(
        val fullPath: String,
        val isFullyAccessible: Boolean,
        val message: String
    )
    
    companion object {
        private const val DEFAULT_STORAGE_FOLDER = "GPXLogger"
        private const val GPX_FOOTER = """    </trkseg>
  </trk>
</gpx>
"""
    }
}
