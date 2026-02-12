package com.mypeople.walkolutionodometer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.mypeople.walkolutionodometer.ui.theme.WalkolutionOdometerTheme
import kotlinx.coroutines.launch

data class OdometerData(
    val sessionMiles: Float = 0f,
    val totalMiles: Float = 0f,
    val sessionTime: String = "0:00",
    val totalTime: String = "0:00",
    val runningAvgSpeed: Float = 0f,
    val sessionAvgSpeed: Float = 0f,
    val voltageV: Float = 0f,
    val sessionId: Int = 0,
    val sessionRotations: Int = 0,
    val sessionTimeSeconds: Int = 0,
    val metric: Boolean = false  // true = km/km/h, false = mi/mph
) {
    val distanceUnit: String get() = if (metric) "km" else "mi"
    val speedUnit: String get() = if (metric) "km/h" else "mph"
    val distanceLabel: String get() = if (metric) "kilometers" else "miles"
}

data class SessionRecord(
    val sessionId: Int,
    val rotationCount: Int,
    val activeTimeSeconds: Int,
    val startTimeUnix: Long,
    val endTimeUnix: Long
) {
    val miles: Float get() = rotationCount * MILES_PER_ROTATION
    val formattedTime: String get() = formatTime(activeTimeSeconds)

    val avgSpeed: Float get() = if (activeTimeSeconds > 0) {
        miles / (activeTimeSeconds / 3600f)
    } else 0f

    val startDateStr: String
        get() = if (startTimeUnix == 0L) {
            "Unknown"
        } else {
            val dateFormat = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
            dateFormat.format(java.util.Date(startTimeUnix * 1000))
        }

    val endDateStr: String
        get() = if (endTimeUnix == 0L) {
            "Unknown"
        } else {
            val dateFormat = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
            dateFormat.format(java.util.Date(endTimeUnix * 1000))
        }

    val dateStr: String get() = startDateStr

    private fun formatTime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }

    companion object {
        const val CM_PER_ROTATION = 34.56f
        const val METERS_PER_MILE = 1609.344f
        const val MILES_PER_ROTATION = CM_PER_ROTATION / 100.0f / METERS_PER_MILE
    }
}


// Top-level constants for use in SessionRecord
private const val CM_PER_ROTATION = 34.56f
private const val METERS_PER_MILE = 1609.344f
private const val MILES_PER_ROTATION = CM_PER_ROTATION / 100.0f / METERS_PER_MILE

private fun formatTimeFromSeconds(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}

class MainActivity : ComponentActivity() {
    // Service binding
    private var bleService: BleService? = null
    private var serviceBound = false

    // State from service (collected from StateFlows)
    private var connectionStatus = mutableStateOf("Disconnected")
    private var odometerData = mutableStateOf(OdometerData())
    private var unreportedSessions = mutableStateOf<List<SessionRecord>>(emptyList())
    private var isConnected = mutableStateOf(false)

    // BLE queue status
    private var bleQueueSize = mutableStateOf(0)
    private var bleProcessing = mutableStateOf(false)
    private var bleLastError = mutableStateOf<String?>(null)

    // Strava integration
    private lateinit var stravaRepository: StravaRepository
    private var stravaAuthenticated = mutableStateOf(false)
    private var stravaAthleteName = mutableStateOf<String?>(null)
    private var stravaStatus = mutableStateOf<String?>(null)

    // Session caching for offline upload
    private lateinit var sessionCacheManager: SessionCacheManager
    private var uploadingSessions = mutableStateOf<Set<Int>>(emptySet())

    companion object {
        private const val TAG = "MainActivity"
        const val CM_PER_ROTATION = 34.56f
        const val METERS_PER_MILE = 1609.344f
        const val MILES_PER_ROTATION = CM_PER_ROTATION / 100.0f / METERS_PER_MILE
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            serviceBound = true
            Log.i(TAG, "BleService connected")

            // Notify service that app has resumed (may need to restart scan)
            bleService?.onAppResumed()

            // Collect state from service
            lifecycleScope.launch {
                bleService?.connectionStatus?.collect { status ->
                    connectionStatus.value = status
                }
            }
            lifecycleScope.launch {
                bleService?.unreportedSessions?.collect { sessions ->
                    unreportedSessions.value = sessions
                }
            }
            lifecycleScope.launch {
                bleService?.odometerData?.collect { data ->
                    odometerData.value = data
                }
            }
            lifecycleScope.launch {
                bleService?.isConnected?.collect { connected ->
                    isConnected.value = connected
                }
            }
            lifecycleScope.launch {
                bleService?.bleQueueSize?.collect { size ->
                    bleQueueSize.value = size
                }
            }
            lifecycleScope.launch {
                bleService?.bleProcessing?.collect { processing ->
                    bleProcessing.value = processing
                }
            }
            lifecycleScope.launch {
                bleService?.bleLastError?.collect { error ->
                    bleLastError.value = error
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            serviceBound = false
            Log.w(TAG, "BleService disconnected")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            Log.i(TAG, "All permissions granted")
            // Start and bind to service
            startBleService()
            bleService?.onPermissionsGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Strava repository
        stravaRepository = StravaRepository(this)

        // Initialize session cache manager
        sessionCacheManager = SessionCacheManager(this)

        // Check existing auth status
        lifecycleScope.launch {
            stravaAuthenticated.value = stravaRepository.isAuthenticated()
            stravaAthleteName.value = stravaRepository.getAthleteName()
        }

        setContent {
            WalkolutionOdometerTheme {
                OdometerScreenWithTopBar(
                    connectionStatus = connectionStatus.value,
                    odometerData = odometerData.value,
                    unreportedSessions = unreportedSessions.value,
                    onUploadSessions = { sessions -> uploadSessionsToStrava(sessions) },
                    onDiscardSession = { session -> discardSession(session) },
                    stravaAuthenticated = stravaAuthenticated.value,
                    uploadingSessions = uploadingSessions.value,
                    isBleConnected = isConnected.value,
                    bleQueueSize = bleQueueSize.value,
                    bleProcessing = bleProcessing.value,
                    bleLastError = bleLastError.value,
                    onOpenSettings = { openSettings() },
                    onOpenSetLifetimeTotals = { openSetLifetimeTotals() },
                    onOpenStravaSettings = { openStravaSettings() },
                    onOpenLogs = { openLogs() }
                )
            }
        }

        // Check permissions and start service
        checkPermissionsAndStartService()
    }

    override fun onStart() {
        super.onStart()
        // Bind to service if it's running
        if (!serviceBound) {
            Intent(this, BleService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Unbind from service but don't stop it
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun checkPermissionsAndStartService() {
        val permissions = mutableListOf<String>()

        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Notification permission for API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            startBleService()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startBleService() {
        val serviceIntent = Intent(this, BleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Bind to service
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun uploadSessionToStrava(session: SessionRecord) {
        uploadSessionsToStrava(listOf(session))
    }

    private fun uploadSessionsToStrava(sessions: List<SessionRecord>) {
        lifecycleScope.launch {
            if (sessions.isEmpty()) return@launch

            // Check if any sessions are already uploaded
            val alreadyUploaded = sessions.any { sessionCacheManager.isSessionUploaded(it.sessionId) }
            if (alreadyUploaded) {
                stravaStatus.value = "Some sessions already uploaded to Strava"
                return@launch
            }

            // Mark all sessions as uploading
            val sessionIds = sessions.map { it.sessionId }.toSet()
            uploadingSessions.value = uploadingSessions.value + sessionIds
            stravaStatus.value = "Uploading ${sessions.size} session(s) to Strava..."

            // Create combined session for upload
            val combinedSession = SessionRecord(
                sessionId = sessions.first().sessionId,
                rotationCount = sessions.sumOf { it.rotationCount },
                activeTimeSeconds = sessions.sumOf { it.activeTimeSeconds },
                startTimeUnix = sessions.mapNotNull {
                    if (it.startTimeUnix == 0L) null else it.startTimeUnix
                }.minOrNull() ?: 0L,
                endTimeUnix = sessions.mapNotNull {
                    if (it.endTimeUnix == 0L) null else it.endTimeUnix
                }.maxOrNull() ?: 0L
            )

            val result = stravaRepository.uploadSession(combinedSession)
            if (result.isSuccess) {
                val activity = result.getOrNull()!!
                stravaStatus.value = "Uploaded to Strava!"

                val totalDistanceMeters = combinedSession.rotationCount * CM_PER_ROTATION / 100f

                // Mark ALL original sessions as uploaded
                for (session in sessions) {
                    sessionCacheManager.addUploadedSession(
                        UploadedSession(
                            sessionId = session.sessionId,
                            stravaActivityId = activity.id,
                            uploadTimestamp = System.currentTimeMillis() / 1000,
                            distanceMeters = session.rotationCount * CM_PER_ROTATION / 100f,
                            elapsedTimeSeconds = session.activeTimeSeconds
                        )
                    )
                }

                // If BLE is connected, send confirmations to Pico in batch
                if (isConnected.value) {
                    val sessionIdsToMark = sessions.map { it.sessionId }
                    bleService?.markMultipleSessionsReported(sessionIdsToMark)
                }

                // Remove ALL sessions from displayed list
                unreportedSessions.value = unreportedSessions.value.filter {
                    it.sessionId !in sessionIds
                }
                bleService?.updateUnreportedSessions(unreportedSessions.value)
            } else {
                stravaStatus.value = "Upload failed: ${result.exceptionOrNull()?.message}"
            }

            // Remove all sessions from uploading set
            uploadingSessions.value = uploadingSessions.value - sessionIds
        }
    }

    private fun markSessionReported(sessionId: Int) {
        bleService?.markSessionReported(sessionId)
    }

    private fun discardSession(session: SessionRecord) {
        lifecycleScope.launch {
            Log.i("MainActivity", "Discarding session ${session.sessionId}, connected=${isConnected.value}")

            // Mark session as discarded
            sessionCacheManager.addDiscardedSession(session.sessionId)

            // If BLE is connected, send the discard command immediately
            if (isConnected.value) {
                Log.i("MainActivity", "Sending mark reported for discarded session ${session.sessionId}")
                markSessionReported(session.sessionId)
            } else {
                Log.i("MainActivity", "Not connected - session ${session.sessionId} will be marked when reconnected")
            }

            // Remove session from displayed list immediately
            unreportedSessions.value = unreportedSessions.value.filter {
                it.sessionId != session.sessionId
            }
            bleService?.updateUnreportedSessions(unreportedSessions.value)
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun openSetLifetimeTotals() {
        val intent = Intent(this, SetLifetimeTotalsActivity::class.java)
        startActivity(intent)
    }

    private fun openStravaSettings() {
        val intent = Intent(this, StravaSettingsActivity::class.java)
        startActivity(intent)
    }

    private fun openLogs() {
        val intent = Intent(this, LogsActivity::class.java)
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdometerScreenWithTopBar(
    connectionStatus: String,
    odometerData: OdometerData,
    unreportedSessions: List<SessionRecord>,
    onUploadSessions: (List<SessionRecord>) -> Unit,
    onDiscardSession: (SessionRecord) -> Unit,
    stravaAuthenticated: Boolean,
    uploadingSessions: Set<Int>,
    isBleConnected: Boolean,
    bleQueueSize: Int,
    bleProcessing: Boolean,
    bleLastError: String?,
    onOpenSettings: () -> Unit,
    onOpenSetLifetimeTotals: () -> Unit,
    onOpenStravaSettings: () -> Unit,
    onOpenLogs: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Walkolution Odometer",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    onOpenSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Strava Settings") },
                                onClick = {
                                    showMenu = false
                                    onOpenStravaSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Set Lifetime Totals") },
                                onClick = {
                                    showMenu = false
                                    onOpenSetLifetimeTotals()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Device Logs") },
                                onClick = {
                                    showMenu = false
                                    onOpenLogs()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        OdometerScreen(
            connectionStatus = connectionStatus,
            odometerData = odometerData,
            unreportedSessions = unreportedSessions,
            onUploadSessions = onUploadSessions,
            onDiscardSession = onDiscardSession,
            stravaAuthenticated = stravaAuthenticated,
            uploadingSessions = uploadingSessions,
            isBleConnected = isBleConnected,
            bleQueueSize = bleQueueSize,
            bleProcessing = bleProcessing,
            bleLastError = bleLastError,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
fun OdometerScreen(
    connectionStatus: String,
    odometerData: OdometerData,
    unreportedSessions: List<SessionRecord>,
    onUploadSessions: (List<SessionRecord>) -> Unit,
    onDiscardSession: (SessionRecord) -> Unit,
    stravaAuthenticated: Boolean,
    uploadingSessions: Set<Int>,
    isBleConnected: Boolean,
    bleQueueSize: Int,
    bleProcessing: Boolean,
    bleLastError: String?,
    modifier: Modifier = Modifier
) {
    var selectedSessions by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var showStravaConfirmation by remember { mutableStateOf(false) }

    if (showDiscardConfirmation) {
        val selectedSessionsList = unreportedSessions.filter { it.sessionId in selectedSessions }
        val totalMiles = selectedSessionsList.sumOf { it.miles.toDouble() }.toFloat()
        val totalSeconds = selectedSessionsList.sumOf { it.activeTimeSeconds }
        val sessionCountText = if (selectedSessionsList.size == 1) "1 session" else "${selectedSessionsList.size} sessions"

        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard Sessions?") },
            text = {
                Text("This will permanently delete $sessionCountText (${String.format("%.2f %s", totalMiles, odometerData.distanceUnit)}, ${formatTimeFromSeconds(totalSeconds)}) without uploading them anywhere. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedSessionsList.forEach { onDiscardSession(it) }
                        selectedSessions = emptySet()
                        showDiscardConfirmation = false
                    }
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showStravaConfirmation) {
        val selectedSessionsList = unreportedSessions.filter { it.sessionId in selectedSessions }
        val totalMiles = selectedSessionsList.sumOf { it.miles.toDouble() }.toFloat()
        val totalSeconds = selectedSessionsList.sumOf { it.activeTimeSeconds }
        val sessionCountText = if (selectedSessionsList.size == 1) "1 session" else "${selectedSessionsList.size} sessions"

        AlertDialog(
            onDismissRequest = { showStravaConfirmation = false },
            title = { Text("Send to Strava?") },
            text = {
                Text("Upload $sessionCountText (${String.format("%.2f %s", totalMiles, odometerData.distanceUnit)}, ${formatTimeFromSeconds(totalSeconds)}) to Strava?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUploadSessions(selectedSessionsList)
                        selectedSessions = emptySet()
                        showStravaConfirmation = false
                    }
                ) {
                    Text("Upload")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStravaConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = if (selectedSessions.isNotEmpty()) 80.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
        Text(
            text = connectionStatus,
            fontSize = 14.sp,
            color = if (connectionStatus == "Connected") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )

        // BLE Queue Status (only show if queue is active or there's an error)
        if (bleQueueSize > 0 || bleProcessing || bleLastError != null) {
            Text(
                text = buildString {
                    if (bleProcessing) append("Processing")
                    if (bleQueueSize > 0) {
                        if (isNotEmpty()) append(" • ")
                        append("$bleQueueSize queued")
                    }
                    if (bleLastError != null) {
                        if (isNotEmpty()) append(" • ")
                        append("Error")
                    }
                },
                fontSize = 12.sp,
                color = if (bleLastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = "Current Speed",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = String.format("%.2f %s", odometerData.runningAvgSpeed, odometerData.speedUnit),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Session Average",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 24.dp)
        )
        Text(
            text = String.format("%.2f %s", odometerData.sessionAvgSpeed, odometerData.speedUnit),
            fontSize = 36.sp,
            fontWeight = FontWeight.SemiBold
        )

        Divider(modifier = Modifier.padding(vertical = 24.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Session",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Distance:", fontSize = 18.sp)
                Text(
                    text = String.format("%.2f %s", odometerData.sessionMiles, odometerData.distanceUnit),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Time:", fontSize = 18.sp)
                Text(
                    text = odometerData.sessionTime,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "Lifetime",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, top = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Distance:", fontSize = 18.sp)
                Text(
                    text = String.format("%.2f %s", odometerData.totalMiles, odometerData.distanceUnit),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Time:", fontSize = 18.sp)
                Text(
                    text = odometerData.totalTime,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = String.format("Power: %.2fV", odometerData.voltageV),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (unreportedSessions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Divider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Unreported Sessions",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp, top = 8.dp)
                )

                unreportedSessions.sortedByDescending { it.sessionId }.forEach { session ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        onClick = {
                            selectedSessions = if (session.sessionId in selectedSessions) {
                                selectedSessions - session.sessionId
                            } else {
                                selectedSessions + session.sessionId
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = session.sessionId in selectedSessions,
                                onCheckedChange = null  // Handled by Card onClick
                            )

                            Text(
                                text = session.endDateStr,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )

                            Text(
                                text = session.formattedTime,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 12.dp)
                            )

                            Text(
                                text = String.format("%.2f %s", session.miles, odometerData.distanceUnit),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }  // End of Column

    // Bottom action bar when sessions are selected
    if (selectedSessions.isNotEmpty()) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            shadowElevation = 8.dp,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (stravaAuthenticated) {
                    Button(
                        onClick = { showStravaConfirmation = true },
                        enabled = selectedSessions.none { it in uploadingSessions },
                        modifier = Modifier.weight(1f)
                    ) {
                        val isUploading = selectedSessions.any { it in uploadingSessions }
                        Text(if (isUploading) "Uploading..." else "Send to Strava (${selectedSessions.size})")
                    }

                    OutlinedButton(
                        onClick = { showDiscardConfirmation = true },
                        enabled = selectedSessions.none { it in uploadingSessions },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Discard (${selectedSessions.size})")
                    }
                } else {
                    Button(
                        onClick = { showDiscardConfirmation = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear (${selectedSessions.size})")
                    }
                }
            }
        }
    }
}  // End of Box
}
