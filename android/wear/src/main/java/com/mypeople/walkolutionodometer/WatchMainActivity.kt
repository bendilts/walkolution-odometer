package com.mypeople.walkolutionodometer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

class WatchMainActivity : ComponentActivity() {
    private lateinit var messageClient: MessageClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        messageClient = Wearable.getMessageClient(this)

        setContent {
            MaterialTheme {
                WatchApp(
                    onReportAll = { sendReportAllMessage() }
                )
            }
        }
    }

    private fun sendReportAllMessage() {
        Log.i(TAG, "Sending Report All message to phone")

        // Send via Wearable Data Layer message
        val message = "/report_all".toByteArray()
        val nodeClient = Wearable.getNodeClient(this)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) {
                Log.w(TAG, "No connected nodes found")
            }
            for (node in nodes) {
                messageClient.sendMessage(node.id, "/report_all", message)
                    .addOnSuccessListener {
                        Log.i(TAG, "Report All message sent to ${node.displayName}")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to send Report All message to ${node.displayName}", e)
                    }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get connected nodes", e)
        }
    }

    companion object {
        private const val TAG = "WatchMainActivity"
    }
}

@Composable
fun WatchApp(onReportAll: () -> Unit) {
    val odometerData by WearDataListenerService.odometerData.collectAsState()
    var showReportAllDialog by remember { mutableStateOf(false) }

    // On Wear OS 6+, the app remains visible in ambient mode automatically
    // For now, show the active UI - system handles dimming
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        ActiveModeScreen(
            odometerData = odometerData,
            onReportAllClick = { showReportAllDialog = true }
        )
    }

    // Report All confirmation - simple overlay
    if (showReportAllDialog) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Upload unreported sessions to Strava?",
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Button(
                    onClick = {
                        showReportAllDialog = false
                        onReportAll()
                    },
                    colors = ButtonDefaults.primaryButtonColors()
                ) {
                    Text("Upload")
                }
                Button(
                    onClick = { showReportAllDialog = false },
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun ActiveModeScreen(
    odometerData: OdometerData,
    onReportAllClick: () -> Unit
) {
    // Simple Column layout - no scrolling needed!
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Connection status (if disconnected)
        if (!odometerData.isConnected) {
            Text(
                text = "Disconnected",
                fontSize = 11.sp,
                color = Color.Red,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Current Speed (large)
        Text(
            text = OdometerFormatting.formatSpeed(odometerData.currentSpeed),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = odometerData.speedUnit,
            fontSize = 14.sp,
            color = MaterialTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Distance row: session and lifetime
        SimpleStatRow(
            sessionValue = OdometerFormatting.formatDistanceWithUnit(odometerData.sessionDistance, odometerData.distanceUnit, false),
            lifetimeValue = OdometerFormatting.formatDistanceWithUnit(odometerData.lifetimeDistance, odometerData.distanceUnit, true)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Time row: session and lifetime
        SimpleStatRow(
            sessionValue = OdometerFormatting.formatSessionTime(odometerData.sessionTime),
            lifetimeValue = OdometerFormatting.formatLifetimeTime(odometerData.lifetimeTime)
        )

        // Report All button (only show if there are unreported sessions)
        if (odometerData.sessionDistance > 0f) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onReportAllClick,
                colors = ButtonDefaults.primaryButtonColors(),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(32.dp)
            ) {
                Text(
                    text = "Report All",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun SimpleStatRow(
    sessionValue: String,
    lifetimeValue: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Session value (medium, white)
        Text(
            text = sessionValue,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Lifetime value (smaller, gray)
        Text(
            text = lifetimeValue,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurfaceVariant
        )
    }
}
