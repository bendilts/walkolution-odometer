package com.mypeople.walkolutionodometer

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

/**
 * Complication data source that provides current walking speed.
 * Updates frequently (every few seconds) to show real-time speed.
 */
class SpeedComplicationService : ComplicationDataSourceService() {

    companion object {
        private const val TAG = "SpeedComplication"
    }

    private fun createTapAction(): PendingIntent {
        val intent = Intent(this, WatchMainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val tapAction = createTapAction()

        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 2.5f,
                    min = 0f,
                    max = 6.0f,
                    contentDescription = PlainComplicationText.Builder("Speed: 2.5 mph").build()
                )
                    .setText(PlainComplicationText.Builder("2.5").build())
                    .setTitle(PlainComplicationText.Builder("mph").build())
                    .setTapAction(tapAction)
                    .build()
            }
            else -> null
        }
    }

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        Log.d(TAG, "onComplicationRequest: type=${request.complicationType}")

        // Get current odometer data from shared state flow
        val data = WearDataListenerService.odometerData.value

        val complicationData = when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> createRangedValueComplication(data)
            else -> {
                Log.w(TAG, "Unsupported complication type: ${request.complicationType}")
                null
            }
        }

        if (complicationData != null) {
            listener.onComplicationData(complicationData)
        } else {
            listener.onComplicationData(null)
        }
    }

    private fun createRangedValueComplication(data: OdometerData): ComplicationData {
        val speed = data.currentSpeed
        val unit = data.speedUnit

        // Fixed range: 0-6 mph or 0-10 km/h (approximately equivalent)
        val maxSpeed = if (data.metric) 10.0f else 6.0f

        return RangedValueComplicationData.Builder(
            value = speed,
            min = 0f,
            max = maxSpeed,
            contentDescription = PlainComplicationText.Builder("Speed: ${formatSpeed(speed)} $unit").build()
        )
            .setText(PlainComplicationText.Builder(formatSpeed(speed)).build())
            .setTitle(PlainComplicationText.Builder(unit).build())
            .setTapAction(createTapAction())
            .build()
    }

    private fun formatSpeed(speed: Float): String {
        return String.format("%.1f", speed)
    }
}
