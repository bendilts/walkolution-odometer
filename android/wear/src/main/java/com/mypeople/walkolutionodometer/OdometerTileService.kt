package com.mypeople.walkolutionodometer

import android.content.ComponentName
import android.content.Intent
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Border
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.tiles.EventBuilders.TileEnterEvent
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val RESOURCES_VERSION = "1"

class OdometerTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Track last displayed complication value to avoid unnecessary updates
    private var lastDisplayedDistance: String? = null

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> {
        val deviceParams = requestParams.deviceConfiguration
        val odometerData = runBlocking { WearDataListenerService.odometerData.first() }

        return Futures.immediateFuture(
            Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(
                    Timeline.Builder()
                        .addTimelineEntry(
                            TimelineEntry.Builder()
                                .setLayout(
                                    LayoutElementBuilders.Layout.Builder()
                                        .setRoot(createTileLayout(deviceParams, odometerData))
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .setFreshnessIntervalMillis(30_000) // Update every 30 seconds
                .build()
        )
    }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> {
        return Futures.immediateFuture(
            Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .build()
        )
    }

    override fun onTileEnterEvent(requestParams: TileEnterEvent) {
        // Request update when tile is visible
        getUpdater(this).requestUpdate(OdometerTileService::class.java)

        // Also trigger complication update if the displayed value has changed
        requestComplicationUpdateIfNeeded()
    }

    /**
     * Requests a complication update only if the displayed value has changed.
     */
    private fun requestComplicationUpdateIfNeeded() {
        serviceScope.launch {
            try {
                val data = WearDataListenerService.odometerData.first()
                val displayedDistance = String.format("%.1f", data.sessionDistance) + data.distanceUnit

                // Only update if the displayed value has actually changed
                if (displayedDistance != lastDisplayedDistance) {
                    lastDisplayedDistance = displayedDistance
                    requestComplicationUpdate()
                }
            } catch (e: Exception) {
                // Silently ignore if no complications are active
            }
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
            // Silently ignore if no complications are active
        }
    }

    private fun createTileLayout(
        deviceParams: DeviceParameters,
        data: OdometerData
    ): LayoutElement {
        return Column.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                Modifiers.Builder()
                    .setPadding(
                        Padding.Builder()
                            .setAll(dp(10f))
                            .build()
                    )
                    .setClickable(
                        Clickable.Builder()
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName("com.mypeople.walkolutionodometer.WatchMainActivity")
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
            .apply {
                // Connection status (if needed)
                if (!data.isConnected) {
                    addContent(
                        Text.Builder()
                            .setText("Disconnected")
                            .setFontStyle(
                                FontStyle.Builder()
                                    .setSize(sp(11f))
                                    .setColor(argb(0xFFFF5555.toInt()))
                                    .build()
                            )
                            .build()
                    )
                }

                // Speed (large) - 3.8
                addContent(
                    Text.Builder()
                        .setText(OdometerFormatting.formatSpeed(data.currentSpeed))
                        .setFontStyle(
                            FontStyle.Builder()
                                .setSize(sp(48f))
                                .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                .setColor(argb(0xFFFFFFFF.toInt()))
                                .build()
                        )
                        .build()
                )

                // Speed unit (small) - mph
                addContent(
                    Text.Builder()
                        .setText(data.speedUnit)
                        .setFontStyle(
                            FontStyle.Builder()
                                .setSize(sp(14f))
                                .setColor(argb(0xFFB0BEC5.toInt()))
                                .build()
                        )
                        .build()
                )

                addContent(Spacer.Builder().setHeight(dp(12f)).build())

                // Distance row: (medium) 1.4mi (gray) 38mi
                addContent(createSimpleStatRow(
                    OdometerFormatting.formatDistanceWithUnit(data.sessionDistance, data.distanceUnit, false),
                    OdometerFormatting.formatDistanceWithUnit(data.lifetimeDistance, data.distanceUnit, true),
                    18f,  // medium size
                    14f   // gray secondary size
                ))

                addContent(Spacer.Builder().setHeight(dp(4f)).build())

                // Time row: (medium) 1:48:31 (gray) 71hr
                addContent(createSimpleStatRow(
                    OdometerFormatting.formatSessionTime(data.sessionTime),
                    OdometerFormatting.formatLifetimeTime(data.lifetimeTime),
                    18f,  // medium size
                    14f   // gray secondary size
                ))

                // Report All button (only if there are unreported sessions)
                if (data.sessionDistance > 0f) {
                    addContent(Spacer.Builder().setHeight(dp(6f)).build())
                    addContent(createReportAllButton())
                }
            }
            .build()
    }

    private fun createReportAllButton(): LayoutElement {
        return Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.wrap())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.wrap())
            .setModifiers(
                Modifiers.Builder()
                    .setPadding(
                        Padding.Builder()
                            .setStart(dp(8f))
                            .setEnd(dp(8f))
                            .setTop(dp(4f))
                            .setBottom(dp(4f))
                            .build()
                    )
                    .setBackground(
                        Background.Builder()
                            .setColor(argb(0xFF1976D2.toInt())) // Blue button
                            .setCorner(Corner.Builder().setRadius(dp(16f)).build())
                            .build()
                    )
                    .setClickable(
                        Clickable.Builder()
                            .setOnClick(
                                // Launch the watch app which will handle reporting
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName(packageName)
                                            .setClassName("com.mypeople.walkolutionodometer.WatchMainActivity")
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(
                Text.Builder()
                    .setText("Report All")
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(12f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun createSimpleStatRow(
        sessionValue: String,
        lifetimeValue: String,
        sessionSize: Float,
        lifetimeSize: Float
    ): LayoutElement {
        return Row.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(Spacer.Builder().setWidth(androidx.wear.protolayout.DimensionBuilders.weight(1f)).build())
            .addContent(
                Text.Builder()
                    .setText(sessionValue)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(sessionSize))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setWidth(dp(12f)).build())
            .addContent(
                Text.Builder()
                    .setText(lifetimeValue)
                    .setFontStyle(
                        FontStyle.Builder()
                            .setSize(sp(lifetimeSize))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                            .setColor(argb(0xFFB0BEC5.toInt())) // Gray
                            .build()
                    )
                    .build()
            )
            .addContent(Spacer.Builder().setWidth(androidx.wear.protolayout.DimensionBuilders.weight(1f)).build())
            .build()
    }
}
