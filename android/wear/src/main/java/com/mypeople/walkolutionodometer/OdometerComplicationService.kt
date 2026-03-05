package com.mypeople.walkolutionodometer

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import kotlinx.coroutines.flow.first

/**
 * Complication data source that provides unreported miles walked.
 * Shows progress towards daily goal.
 */
class OdometerComplicationService : ComplicationDataSourceService() {

    companion object {
        private const val TAG = "OdometerComplication"
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
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("3.2").build(),
                    contentDescription = PlainComplicationText.Builder("3.2 miles unreported").build()
                )
                    .setTitle(PlainComplicationText.Builder("mi").build())
                    .setTapAction(tapAction)
                    .build()
            }
            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder("3.2 unreported").build(),
                    contentDescription = PlainComplicationText.Builder("3.2 miles unreported").build()
                )
                    .setTitle(PlainComplicationText.Builder("mi").build())
                    .setTapAction(tapAction)
                    .build()
            }
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = 3.2f,
                    min = 0f,
                    max = 6.0f,
                    contentDescription = PlainComplicationText.Builder("3.2 of 6.0 miles").build()
                )
                    .setText(PlainComplicationText.Builder("3.2").build())
                    .setTitle(PlainComplicationText.Builder("mi").build())
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
            ComplicationType.SHORT_TEXT -> createShortTextComplication(data)
            ComplicationType.LONG_TEXT -> createLongTextComplication(data)
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

    private fun createShortTextComplication(data: OdometerData): ComplicationData {
        val distance = data.sessionDistance
        val unit = data.distanceUnit
        val text = formatSessionDistance(distance)

        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder("$distance $unit unreported").build()
        )
            .setTitle(PlainComplicationText.Builder(unit).build())
            .setTapAction(createTapAction())
            .build()
    }

    private fun createLongTextComplication(data: OdometerData): ComplicationData {
        val distance = data.sessionDistance
        val unit = data.distanceUnit
        val text = "${formatSessionDistance(distance)} unreported"

        return LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder("$distance $unit unreported").build()
        )
            .setTitle(PlainComplicationText.Builder(unit).build())
            .setTapAction(createTapAction())
            .build()
    }

    private fun createRangedValueComplication(data: OdometerData): ComplicationData {
        val distance = data.sessionDistance
        val dailyGoal = data.dailyGoalMiles
        val unit = data.distanceUnit
        val text = formatSessionDistance(distance)

        // Convert daily goal to current units if metric
        val goalInCurrentUnits = if (data.metric) {
            dailyGoal * 1.60934f  // Convert miles to km
        } else {
            dailyGoal
        }

        // Use the larger of distance or goal as max to ensure text always shows
        val maxValue = maxOf(distance, goalInCurrentUnits)

        return RangedValueComplicationData.Builder(
            value = distance,
            min = 0f,
            max = maxValue,
            contentDescription = PlainComplicationText.Builder("$distance of $goalInCurrentUnits $unit").build()
        )
            .setText(PlainComplicationText.Builder(text).build())
            .setTitle(PlainComplicationText.Builder(unit).build())
            .setTapAction(createTapAction())
            .build()
    }

    private fun formatSessionDistance(distance: Float): String {
        return String.format("%.1f", distance)
    }
}
