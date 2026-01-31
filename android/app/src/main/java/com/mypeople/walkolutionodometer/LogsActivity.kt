package com.mypeople.walkolutionodometer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mypeople.walkolutionodometer.ui.theme.WalkolutionOdometerTheme
import kotlinx.coroutines.launch

// Sealed class to represent log entries
sealed class LogEntry {
    data class SingleLog(val text: String) : LogEntry()
    data class GroupedLogs(val firstLog: String, val lastLog: String, val count: Int) : LogEntry()
}

// Composable for a custom scrollbar indicator
@Composable
fun LazyColumnScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val totalItems = listState.layoutInfo.totalItemsCount

    if (totalItems > 0) {
        val thumbHeight = 0.3f // Proportional thumb height
        val scrollProgress = firstVisibleIndex.toFloat() / totalItems.coerceAtLeast(1)

        Box(
            modifier = modifier
                .width(6.dp)
                .fillMaxHeight()
        ) {
            // Scrollbar thumb
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(thumbHeight)
                    .align(Alignment.TopEnd)
                    .offset(y = (scrollProgress * (1f - thumbHeight) * 100).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Gray.copy(alpha = 0.5f))
            )
        }
    }
}

class LogsActivity : ComponentActivity() {

    private val bleServiceState = mutableStateOf<BleService?>(null)
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            val binder = service as BleService.LocalBinder
            bleServiceState.value = binder.getService()
            serviceBound = true

            // Notify service that activity is visible (in case onResume was called before binding)
            if (!isFinishing && !isDestroyed) {
                Log.d(TAG, "Notifying service that LogsActivity is visible")
                bleServiceState.value?.onLogsActivityVisible()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            bleServiceState.value = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind to BLE service
        val intent = Intent(this, BleService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            WalkolutionOdometerTheme {
                LogsScreen()
            }
        }

        // Debug: Force a log read when activity opens
        lifecycleScope.launch {
            kotlinx.coroutines.delay(500) // Wait for service binding
            Log.d(TAG, "LogsActivity opened, current log size: ${bleServiceState.value?.getCurrentLogs()?.length ?: 0}")
        }
    }

    override fun onResume() {
        super.onResume()
        // Notify service that LogsActivity is now visible
        bleServiceState.value?.onLogsActivityVisible()
    }

    override fun onPause() {
        super.onPause()
        // Notify service that LogsActivity is now hidden
        bleServiceState.value?.onLogsActivityHidden()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    // Helper function to check if a log line is a battery status message
    private fun isBatteryStatusMessage(line: String): Boolean {
        // Matches pattern like "[12345] 3456 mV, BLE: ..."
        val pattern = """^\[\d+\]\s+\d+\s+mV,\s+BLE:.*""".toRegex()
        return pattern.matches(line)
    }

    // Group consecutive battery status messages
    private fun groupLogLines(lines: List<String>): List<LogEntry> {
        val grouped = mutableListOf<LogEntry>()
        var i = 0

        while (i < lines.size) {
            val currentLine = lines[i]

            if (isBatteryStatusMessage(currentLine)) {
                // Count consecutive battery status messages
                var consecutiveCount = 1
                var lastIndex = i

                while (lastIndex + 1 < lines.size && isBatteryStatusMessage(lines[lastIndex + 1])) {
                    consecutiveCount++
                    lastIndex++
                }

                // Group if more than 3 consecutive messages
                if (consecutiveCount > 3) {
                    grouped.add(
                        LogEntry.GroupedLogs(
                            firstLog = currentLine,
                            lastLog = lines[lastIndex],
                            count = consecutiveCount - 2 // Exclude first and last from the "... X more ..." count
                        )
                    )
                    i = lastIndex + 1
                } else {
                    // Don't group if 3 or fewer
                    grouped.add(LogEntry.SingleLog(currentLine))
                    i++
                }
            } else {
                grouped.add(LogEntry.SingleLog(currentLine))
                i++
            }
        }

        return grouped
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LogsScreen() {
        // Track service state for recomposition
        val bleService by remember { bleServiceState }

        // Collect logs from service with proper lifecycle
        val logs = bleService?.logMessages?.collectAsState()?.value ?: ""

        // Debug: Log service connection state changes
        LaunchedEffect(bleService) {
            Log.d(TAG, "BLE Service state changed: ${bleService != null}")
        }

        // Debug: Log when logs state changes
        LaunchedEffect(logs) {
            Log.d(TAG, "Logs UI updated: ${logs.length} chars, first 100: ${logs.take(100)}")
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Device Logs") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            bleService?.clearLogs()
                            Log.d(TAG, "Clear button clicked")
                        }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear logs"
                            )
                        }
                        IconButton(onClick = {
                            bleService?.triggerLogRead()
                            Log.d(TAG, "Refresh button clicked, current logs: ${logs.length} chars")
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh logs"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        // Debug info
                        val isTruncated = logs.startsWith("...[truncated")
                        val displayText = buildString {
                            append("Service: ${if (bleService != null) "Connected" else "Not connected"}")
                            append(" | Logs: ${logs.length / 1024}KB")
                            if (isTruncated) {
                                append(" (showing last 200KB)")
                            }
                        }
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )

                        if (logs.isEmpty()) {
                            Text(
                                text = "No logs available yet...\nConnect to device to see logs.",
                                modifier = Modifier.padding(16.dp)
                            )
                        } else {
                            // Split logs into lines and group them
                            val groupedEntries = remember(logs) {
                                val lines = logs.split('\n')
                                groupLogLines(lines)
                            }

                            val listState = rememberLazyListState()
                            val horizontalScrollState = rememberScrollState()

                            // Track if we've done the initial scroll
                            var hasScrolledInitially by remember { mutableStateOf(false) }

                            // Auto-scroll to bottom on initial load and when near bottom
                            LaunchedEffect(groupedEntries.size) {
                                if (!hasScrolledInitially && groupedEntries.isNotEmpty()) {
                                    // Initial scroll to bottom
                                    listState.scrollToItem(groupedEntries.size - 1)
                                    hasScrolledInitially = true
                                } else {
                                    // Auto-scroll only if user is already near the bottom
                                    val isNearBottom = !listState.canScrollForward ||
                                        (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 3
                                    if (isNearBottom && groupedEntries.isNotEmpty()) {
                                        // Scroll to bottom immediately (not animated to avoid jitter)
                                        listState.scrollToItem(groupedEntries.size - 1)
                                    }
                                }
                            }

                            // Use LazyColumn for virtualized rendering (only visible items)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .horizontalScroll(horizontalScrollState)
                            ) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                ) {
                                    items(
                                        count = groupedEntries.size,
                                        key = { index -> index }
                                    ) { index ->
                                        when (val entry = groupedEntries[index]) {
                                            is LogEntry.SingleLog -> {
                                                Text(
                                                    text = entry.text,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    softWrap = false,
                                                    maxLines = 1,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                            is LogEntry.GroupedLogs -> {
                                                // Show first log
                                                Text(
                                                    text = entry.firstLog,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    softWrap = false,
                                                    maxLines = 1,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                // Show "... X more messages ..." line
                                                Text(
                                                    text = "... ${entry.count} more messages ...",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    fontStyle = FontStyle.Italic,
                                                    color = Color.Gray,
                                                    softWrap = false,
                                                    maxLines = 1,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 2.dp)
                                                )
                                                // Show last log
                                                Text(
                                                    text = entry.lastLog,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    softWrap = false,
                                                    maxLines = 1,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }

                                // Vertical scrollbar
                                LazyColumnScrollbar(
                                    listState = listState,
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .padding(end = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "LogsActivity"
    }
}
