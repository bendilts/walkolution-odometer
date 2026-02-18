package com.mypeople.walkolutionodometer

import android.util.Log
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

                    val newData = OdometerData(
                        currentSpeed = currentSpeed,
                        sessionDistance = sessionDistance,
                        sessionTime = sessionTime ?: "0:00",
                        lifetimeDistance = lifetimeDistance,
                        lifetimeTime = lifetimeTime ?: "0:00",
                        metric = metric,
                        isConnected = isConnected
                    )

                    _odometerData.value = newData
                    Log.i(TAG, "Updated odometer data: speed=${currentSpeed}, session=${sessionDistance}, connected=${isConnected}")
                }
            }
        }
    }
}
