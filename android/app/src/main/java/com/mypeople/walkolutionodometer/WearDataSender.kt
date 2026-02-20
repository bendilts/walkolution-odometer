package com.mypeople.walkolutionodometer

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WearDataSender(private val context: Context) {
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "WearDataSender"
        private const val ODOMETER_PATH = "/odometer"
    }

    fun sendOdometerData(data: OdometerData, isConnected: Boolean, dailyGoalMiles: Float = 6.0f) {
        scope.launch {
            try {
                val putDataReq = PutDataMapRequest.create(ODOMETER_PATH).apply {
                    dataMap.putFloat("currentSpeed", data.runningAvgSpeed)
                    // Send unreported totals instead of current session
                    dataMap.putFloat("sessionDistance", data.unreportedMiles)
                    dataMap.putString("sessionTime", data.unreportedTime)
                    dataMap.putFloat("lifetimeDistance", data.totalMiles)
                    dataMap.putString("lifetimeTime", data.totalTime)
                    dataMap.putBoolean("metric", data.metric)
                    dataMap.putBoolean("isConnected", isConnected)
                    dataMap.putFloat("dailyGoalMiles", dailyGoalMiles)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }.asPutDataRequest()
                    .setUrgent()  // Send immediately

                dataClient.putDataItem(putDataReq).addOnSuccessListener { result ->
                    Log.d(TAG, "Data sent to watch: ${result.uri}")
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send data to watch: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send data to watch: ${e.message}")
            }
        }
    }
}
