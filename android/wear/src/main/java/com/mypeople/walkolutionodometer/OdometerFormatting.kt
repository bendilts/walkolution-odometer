package com.mypeople.walkolutionodometer

/**
 * Shared formatting utilities for odometer data
 * Used by both the app UI and tile to ensure consistent display
 */
object OdometerFormatting {

    fun formatSpeed(speed: Float): String {
        return String.format("%.1f", speed)
    }

    fun formatSessionDistance(distance: Float): String {
        return String.format("%.1f", distance)
    }

    fun formatLifetimeDistance(distance: Float): String {
        return String.format("%.0f", distance)
    }

    fun formatSessionTime(time: String): String {
        return time // Keep as is (H:MM:SS or M:SS)
    }

    fun formatLifetimeTime(time: String): String {
        // Parse "H:MM:SS" or "M:SS" format and return just hours for large values
        val parts = time.split(":")
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toIntOrNull() ?: 0
                "${hours}hr"
            }
            2 -> {
                // Less than 1 hour - show as M:SS
                time
            }
            else -> time
        }
    }

    fun formatDistanceWithUnit(distance: Float, unit: String, isLifetime: Boolean): String {
        return if (isLifetime) {
            "${formatLifetimeDistance(distance)}$unit"
        } else {
            "${formatSessionDistance(distance)}$unit"
        }
    }
}
