package com.hdclark.gpxlogger

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class GpxManager(private val context: Context) {
    // For API 29+ we use MediaStore and track the URI
    private var currentUri: Uri? = null
    // For API 28 and below we use direct File I/O
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
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore API for Android 10+ to write to public Downloads
                initializeTrackWithMediaStore(storagePath, fileName)
            } else {
                // Use direct File I/O for Android 9 and below
                val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                initializeTrackWithFileIO(baseDir, storagePath, fileName)
            }
        } catch (e: Exception) {
            android.util.Log.e("GpxManager", "Error starting new track", e)
            null
        }
    }
    
    /**
     * Initialize a track file using MediaStore API for Android 10+ (API 29+).
     * This allows writing to the public Downloads directory without special permissions.
     */
    private fun initializeTrackWithMediaStore(folderName: String, fileName: String): File? {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$folderName"
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            // Mark as pending while we're writing
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create MediaStore entry for GPX file")
        
        // Write GPX header
        resolver.openOutputStream(uri, "w")?.use { outputStream ->
            outputStream.write(generateGpxHeader().toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Failed to open output stream for GPX file")
        
        currentUri = uri
        currentFile = null
        currentFileName = fileName
        locationCache.clear()
        lastFlushTime = System.currentTimeMillis()
        consecutiveFlushFailures = 0
        
        // Return a placeholder File for compatibility (actual path may not be accessible)
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$folderName/$fileName")
    }
    
    /**
     * Initialize a track file using direct File I/O for Android 9 and below (API 26-28).
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
        currentUri = null
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
        // Check if we have a valid target (either URI or File)
        if (currentUri == null && currentFile == null) return false
        if (locationCache.isEmpty()) return true
        
        // Skip flush if retry limit exceeded (unless forced for emergency/close)
        if (!forceFlush && consecutiveFlushFailures >= maxFlushRetries) {
            return false
        }
        
        return try {
            val trackPoints = buildTrackPointsXml()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentUri != null) {
                // Use MediaStore for Android 10+
                appendToMediaStoreFile(trackPoints)
            } else if (currentFile != null) {
                // Use direct File I/O for Android 9 and below
                FileWriter(currentFile, true).use { writer ->
                    writer.write(trackPoints)
                }
            } else {
                return false
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
    
    private fun appendToMediaStoreFile(content: String) {
        val uri = currentUri ?: return
        // Open in append mode
        context.contentResolver.openOutputStream(uri, "wa")?.use { outputStream ->
            outputStream.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Failed to open output stream for appending")
    }
    
    @Synchronized
    fun closeTrack() {
        // Flush any remaining cached locations - force flush even if retry limit exceeded
        flushCacheInternal(forceFlush = true)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && currentUri != null) {
                // Append footer and mark file as complete for MediaStore
                appendToMediaStoreFile(GPX_FOOTER)
                
                // Mark file as no longer pending
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(currentUri!!, contentValues, null, null)
            } else if (currentFile != null) {
                // Write footer using direct File I/O
                FileWriter(currentFile, true).use { writer ->
                    writer.write(GPX_FOOTER)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GpxManager", "Error writing GPX footer", e)
        }
        
        currentUri = null
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
        
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(baseDir, storagePath)
    }
    
    /**
     * Returns the full path where GPX files would be stored for a given folder name.
     * This can be used to preview the path before saving settings.
     */
    fun getStorageDirectoryForFolder(folderName: String?): File {
        val storagePath = sanitizeFolderName(folderName?.takeIf { it.isNotBlank() } ?: DEFAULT_STORAGE_FOLDER)
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(baseDir, storagePath)
    }
    
    /**
     * Returns information about the storage location accessibility.
     * Files are stored in the public Downloads directory and are accessible via file browsers.
     */
    fun getStorageAccessibilityInfo(): StorageAccessibilityInfo {
        val directory = getStorageDirectory()
        
        return StorageAccessibilityInfo(
            fullPath = directory.absolutePath,
            isFullyAccessible = true,
            message = "Files are saved to the public Downloads folder and accessible via file browsers"
        )
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
