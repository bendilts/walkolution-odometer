package com.mypeople.walkolutionodometer

import android.content.ComponentName
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WearDataListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WearDataListener"
        const val ODOMETER_PATH = "/odometer"

        // Shared state flow for odometer data
        private val _odometerData = MutableStateFlow(OdometerData())
        val odometerData = _odometerData.asStateFlow()

        // Track last displayed complication values to avoid unnecessary updates
        private var lastDisplayedDistance: String? = null
        private var lastDisplayedSpeed: String? = null
        private var lastSpeedUpdateTime: Long = 0

        // Speed complication throttling constants
        private const val MIN_SPEED_UPDATE_INTERVAL_MS = 3000L // 3 seconds minimum between updates
        private const val MIN_SPEED_CHANGE_THRESHOLD = 0.2f // Minimum speed change to trigger update (mph/kph)
        private const val MIN_SPEED_THRESHOLD = 0.1f // Below this speed, consider stationary

        fun updateData(data: OdometerData) {
            _odometerData.value = data
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.i(TAG, "onDataChanged: ${dataEvents.count} events")

        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path
                Log.d(TAG, "Data changed for path: $path")

                if (path == ODOMETER_PATH) {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

                    val currentSpeed = dataMap.getFloat("currentSpeed", 0f)
                    val sessionDistance = dataMap.getFloat("sessionDistance", 0f)
                    val sessionTime = dataMap.getString("sessionTime", "0:00")
                    val lifetimeDistance = dataMap.getFloat("lifetimeDistance", 0f)
                    val lifetimeTime = dataMap.getString("lifetimeTime", "0:00")
                    val metric = dataMap.getBoolean("metric", false)
                    val isConnected = dataMap.getBoolean("isConnected", false)
                    val dailyGoalMiles = dataMap.getFloat("dailyGoalMiles", 6.0f)

                    val newData = OdometerData(
                        currentSpeed = currentSpeed,
                        sessionDistance = sessionDistance,
                        sessionTime = sessionTime ?: "0:00",
                        lifetimeDistance = lifetimeDistance,
                        lifetimeTime = lifetimeTime ?: "0:00",
                        metric = metric,
                        isConnected = isConnected,
                        dailyGoalMiles = dailyGoalMiles
                    )

                    _odometerData.value = newData
                    Log.i(TAG, "Updated odometer data: speed=${currentSpeed}, session=${sessionDistance}, connected=${isConnected}")

                    // Only update complications if the displayed values will actually change
                    requestDistanceComplicationUpdateIfNeeded(newData)
                    requestSpeedComplicationUpdateIfNeeded(newData)
                }
            }
        }
    }

    /**
     * Requests a distance complication update only if the displayed value has changed.
     * This avoids unnecessary updates when the raw value changes but the rounded display
     * value (e.g., "3.2mi") remains the same.
     */
    private fun requestDistanceComplicationUpdateIfNeeded(data: OdometerData) {
        // Format the distance exactly as it will appear in the complication
        val displayedDistance = String.format("%.1f", data.sessionDistance) + data.distanceUnit

        // Only update if the displayed value has actually changed
        if (displayedDistance != lastDisplayedDistance) {
            lastDisplayedDistance = displayedDistance
            requestDistanceComplicationUpdate()
            Log.d(TAG, "Distance complication display changed to: $displayedDistance")
        }
    }

    /**
     * Requests a speed complication update with smart throttling to save battery.
     *
     * Battery-saving strategy:
     * 1. Only update when connected AND moving (speed > threshold)
     * 2. Require minimum time interval between updates (3 seconds)
     * 3. Require meaningful speed change (>0.2 mph/kph)
     *
     * When disconnected or stationary, immediately update to 0.0 and stop.
     */
    private fun requestSpeedComplicationUpdateIfNeeded(data: OdometerData) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - lastSpeedUpdateTime

        // Extract last speed value from the cached display string
        val lastSpeed = lastDisplayedSpeed?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull() ?: 0f
        val currentSpeed = data.currentSpeed
        val speedChange = Math.abs(currentSpeed - lastSpeed)

        // Format the speed exactly as it will appear in the complication
        val displayedSpeed = String.format("%.1f", currentSpeed) + data.speedUnit

        // Battery-saving condition 1: Not connected or stationary -> update to 0.0 immediately
        if (!data.isConnected || currentSpeed < MIN_SPEED_THRESHOLD) {
            val stoppedSpeed = "0.0" + data.speedUnit
            if (lastDisplayedSpeed != stoppedSpeed) {
                lastDisplayedSpeed = stoppedSpeed
                lastSpeedUpdateTime = currentTime
                requestSpeedComplicationUpdate()
                Log.d(TAG, "Speed complication updated to 0.0 (disconnected or stationary)")
            }
            return
        }

        // Battery-saving condition 2: Connected and moving -> apply throttling
        val shouldUpdate =
            // Display value actually changed
            displayedSpeed != lastDisplayedSpeed &&
            // AND either:
            (
                // Enough time has passed since last update
                timeSinceLastUpdate >= MIN_SPEED_UPDATE_INTERVAL_MS ||
                // OR speed changed significantly (allows quick updates for big changes)
                speedChange >= MIN_SPEED_CHANGE_THRESHOLD
            )

        if (shouldUpdate) {
            lastDisplayedSpeed = displayedSpeed
            lastSpeedUpdateTime = currentTime
            requestSpeedComplicationUpdate()
            Log.d(TAG, "Speed complication updated to: $displayedSpeed (change: ${String.format("%.2f", speedChange)}, interval: ${timeSinceLastUpdate}ms)")
        } else {
            Log.v(TAG, "Speed update skipped (change: ${String.format("%.2f", speedChange)}, interval: ${timeSinceLastUpdate}ms)")
        }
    }

    /**
     * Requests an immediate update of all active distance complications.
     */
    private fun requestDistanceComplicationUpdate() {
        try {
            val componentName = ComponentName(this, OdometerComplicationService::class.java)
            val requester = ComplicationDataSourceUpdateRequester.create(
                context = this,
                complicationDataSourceComponent = componentName
            )
            requester.requestUpdateAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request distance complication update: ${e.message}")
        }
    }

    /**
     * Requests an immediate update of all active speed complications.
     */
    private fun requestSpeedComplicationUpdate() {
        try {
            val componentName = ComponentName(this, SpeedComplicationService::class.java)
            val requester = ComplicationDataSourceUpdateRequester.create(
                context = this,
                complicationDataSourceComponent = componentName
            )
            requester.requestUpdateAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request speed complication update: ${e.message}")
        }
    }
}
