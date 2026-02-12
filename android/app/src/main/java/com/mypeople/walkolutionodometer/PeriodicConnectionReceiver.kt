package com.mypeople.walkolutionodometer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * BroadcastReceiver for handling periodic connection attempts.
 * This receiver is registered in AndroidManifest.xml and survives even if the service is killed.
 */
class PeriodicConnectionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PeriodicConnReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "========== PERIODIC CONNECTION BROADCAST RECEIVED ==========")
        Log.i(TAG, "Action: ${intent.action}")
        Log.i(TAG, "Time: ${System.currentTimeMillis()}")

        // Acquire a wake lock to ensure the device stays awake for the connection attempt
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WalkolutionOdometer::PeriodicConnectionWakeLock"
        ).apply {
            // Hold wake lock for 3 minutes (enough time to scan and connect)
            acquire(3 * 60 * 1000L)
        }

        Log.i(TAG, "Wake lock acquired")

        // Start or restart the BleService
        val serviceIntent = Intent(context, BleService::class.java).apply {
            putExtra("periodic_wakeup", true)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
                Log.i(TAG, "Started BleService as foreground service")
            } else {
                context.startService(serviceIntent)
                Log.i(TAG, "Started BleService")
            }

            // Release wake lock after a delay (the service will keep itself alive)
            // Wake lock will auto-release after 3 minutes if not released earlier
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BleService: ${e.message}", e)
            // Release wake lock immediately on error
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
