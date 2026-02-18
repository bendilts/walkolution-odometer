package com.mypeople.walkolutionodometer

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class WearMessageListener : WearableListenerService() {
    companion object {
        private const val TAG = "WearMessageListener"
        const val REPORT_ALL_PATH = "/report_all"
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.i(TAG, "Message received: ${messageEvent.path}")

        when (messageEvent.path) {
            REPORT_ALL_PATH -> {
                Log.i(TAG, "Received report all sessions request from watch")
                // Trigger upload via MainActivity
                val intent = Intent(this, MainActivity::class.java).apply {
                    action = "com.mypeople.walkolutionodometer.REPORT_ALL"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        }
    }
}
