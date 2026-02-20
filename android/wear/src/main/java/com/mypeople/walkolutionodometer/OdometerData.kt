package com.mypeople.walkolutionodometer

data class OdometerData(
    val currentSpeed: Float = 0f,
    val sessionDistance: Float = 0f,
    val sessionTime: String = "0:00",
    val lifetimeDistance: Float = 0f,
    val lifetimeTime: String = "0:00",
    val metric: Boolean = false,
    val isConnected: Boolean = false,
    val dailyGoalMiles: Float = 6.0f
) {
    val distanceUnit: String get() = if (metric) "km" else "mi"
    val speedUnit: String get() = if (metric) "km/h" else "mph"
}
