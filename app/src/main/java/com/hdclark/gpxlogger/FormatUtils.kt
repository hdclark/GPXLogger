package com.hdclark.gpxlogger

/**
 * Utility object containing formatting functions for duration and distance.
 * Shared between MainActivity and LocationService to avoid code duplication.
 */
object FormatUtils {
    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60
    private const val METERS_PER_KILOMETER = 1000

    /**
     * Formats a duration in milliseconds to HH:MM:SS format.
     */
    fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds / MILLIS_PER_SECOND) % SECONDS_PER_MINUTE
        val minutes = (milliseconds / (MILLIS_PER_SECOND * SECONDS_PER_MINUTE)) % MINUTES_PER_HOUR
        val hours = milliseconds / (MILLIS_PER_SECOND * SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Formats a distance in meters to a human-readable string.
     * Uses meters for distances under 1km, kilometers otherwise.
     */
    fun formatDistance(meters: Float): String {
        return if (meters >= METERS_PER_KILOMETER) {
            String.format("%.2f km", meters / METERS_PER_KILOMETER)
        } else {
            String.format("%.0f m", meters)
        }
    }
}
