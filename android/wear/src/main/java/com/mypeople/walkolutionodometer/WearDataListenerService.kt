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

        // Track last displayed complication value to avoid unnecessary updates
        private var lastDisplayedDistance: String? = null

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

                    // Only update complication if the displayed value will actually change
                    requestComplicationUpdateIfNeeded(newData)
                }
            }
        }
    }

    /**
     * Requests a complication update only if the displayed value has changed.
     * This avoids unnecessary updates when the raw value changes but the rounded display
     * value (e.g., "3.2mi") remains the same.
     */
    private fun requestComplicationUpdateIfNeeded(data: OdometerData) {
        // Format the distance exactly as it will appear in the complication
        val displayedDistance = String.format("%.1f", data.sessionDistance) + data.distanceUnit

        // Only update if the displayed value has actually changed
        if (displayedDistance != lastDisplayedDistance) {
            lastDisplayedDistance = displayedDistance
            requestComplicationUpdate()
            Log.d(TAG, "Complication display changed to: $displayedDistance")
        }
    }

    /**
     * Requests an immediate update of all active complications.
     */
    private fun requestComplicationUpdate() {
        try {
            val componentName = ComponentName(this, OdometerComplicationService::class.java)
            val requester = ComplicationDataSourceUpdateRequester.create(
                context = this,
                complicationDataSourceComponent = componentName
            )
            requester.requestUpdateAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request complication update: ${e.message}")
        }
    }
}
