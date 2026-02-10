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
            val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
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

data class SessionGroup(
    val sessions: List<SessionRecord>,
    val totalRotationCount: Int,
    val totalActiveTimeSeconds: Int,
    val earliestStartTimeUnix: Long,
    val latestEndTimeUnix: Long
) {
    val miles: Float get() = totalRotationCount * MILES_PER_ROTATION
    val formattedTime: String get() = formatTime(totalActiveTimeSeconds)

    val avgSpeed: Float get() = if (totalActiveTimeSeconds > 0) {
        miles / (totalActiveTimeSeconds / 3600f)
    } else 0f

    val startDateStr: String
        get() = if (earliestStartTimeUnix == 0L) {
            "Unknown"
        } else {
            val dateFormat = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
            dateFormat.format(java.util.Date(earliestStartTimeUnix * 1000))
        }

    val endDateStr: String
        get() = if (latestEndTimeUnix == 0L) {
            "Unknown"
        } else {
            val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            dateFormat.format(java.util.Date(latestEndTimeUnix * 1000))
        }

    val sessionIds: List<Int> get() = sessions.map { it.sessionId }

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

/**
 * Groups sessions according to these rules:
 * 1. Consecutive sessions without timestamps are joined together
 * 2. Sessions WITH timestamps that are less than 30 minutes apart are joined together,
 *    including any sessions without timestamps that fall between them
 * 3. Any session < 0.25 miles ALWAYS joins to the following sessions (not worth reporting alone)
 *
 * When joining sessions:
 * - Keep the latest end time
 * - Keep the earliest start time
 * - Add up all walking time and distance
 */
fun groupSessions(sessions: List<SessionRecord>): List<SessionGroup> {
    if (sessions.isEmpty()) return emptyList()

    val groups = mutableListOf<SessionGroup>()
    var currentGroup = mutableListOf<SessionRecord>()

    val sortedSessions = sessions.sortedBy {
        if (it.startTimeUnix == 0L) Long.MAX_VALUE else it.startTimeUnix
    }

    for (session in sortedSessions) {
        if (currentGroup.isEmpty()) {
            // Start a new group
            currentGroup.add(session)
        } else {
            val lastSession = currentGroup.last()

            // Check if we should join this session to the current group
            val shouldJoin = when {
                // Rule 3: If last session was < 0.25 miles, always join it to this one
                lastSession.miles < 0.25f -> true

                // Both have no timestamps - join them
                lastSession.endTimeUnix == 0L && session.startTimeUnix == 0L -> true

                // Last has no timestamp, this has timestamp - don't join
                lastSession.endTimeUnix == 0L && session.startTimeUnix != 0L -> false

                // Last has timestamp, this has no timestamp - check if there's a valid session after this
                lastSession.endTimeUnix != 0L && session.startTimeUnix == 0L -> {
                    // Look ahead to see if there's another timestamped session nearby
                    val nextTimestampedSession = sortedSessions.dropWhile { it.sessionId <= session.sessionId }
                        .firstOrNull { it.startTimeUnix != 0L }

                    if (nextTimestampedSession != null) {
                        // Check if next timestamped session is within 30 minutes of last timestamped in group
                        val gapSeconds = nextTimestampedSession.startTimeUnix - lastSession.endTimeUnix
                        gapSeconds < 30 * 60
                    } else {
                        // No more timestamped sessions, keep with current group
                        true
                    }
                }

                // Both have timestamps - check if gap is less than 30 minutes
                else -> {
                    val gapSeconds = session.startTimeUnix - lastSession.endTimeUnix
                    gapSeconds < 30 * 60
                }
            }

            if (shouldJoin) {
                currentGroup.add(session)
            } else {
                // Finalize current group and start a new one
                groups.add(createSessionGroup(currentGroup))
                currentGroup = mutableListOf(session)
            }
        }
    }

    // Don't forget the last group
    if (currentGroup.isNotEmpty()) {
        groups.add(createSessionGroup(currentGroup))
    }

    return groups
}

private fun createSessionGroup(sessions: List<SessionRecord>): SessionGroup {
    val totalRotations = sessions.sumOf { it.rotationCount }
    val totalTime = sessions.sumOf { it.activeTimeSeconds }
    val earliestStart = sessions.mapNotNull {
        if (it.startTimeUnix == 0L) null else it.startTimeUnix
    }.minOrNull() ?: 0L
    val latestEnd = sessions.mapNotNull {
        if (it.endTimeUnix == 0L) null else it.endTimeUnix
    }.maxOrNull() ?: 0L

    return SessionGroup(
        sessions = sessions,
        totalRotationCount = totalRotations,
        totalActiveTimeSeconds = totalTime,
        earliestStartTimeUnix = earliestStart,
        latestEndTimeUnix = latestEnd
    )
}

/**
 * Finds the group that the current session would belong to if it were in the list.
 * Returns null if the current session wouldn't be grouped with any existing sessions.
 *
 * Rules for joining current session to previous group:
 * 1. Join if the entire most-recent group is < 0.25 miles total
 * 2. Join if any session in the most-recent group ended less than 30 minutes ago
 */
fun findGroupForCurrentSession(
    currentSessionId: Int,
    currentSessionEndTime: Long,
    currentSessionMiles: Float,
    sessionGroups: List<SessionGroup>
): SessionGroup? {
    if (currentSessionId == 0) return null
    if (sessionGroups.isEmpty()) return null

    // The current session would be the most recent one
    // Check if it would join with the last group (most recent group)
    val lastGroup = sessionGroups.lastOrNull() ?: return null

    // Rule 1: Join if the entire most-recent group is < 0.25 miles total
    if (lastGroup.miles < 0.25f) {
        return lastGroup
    }

    // Rule 2: Join if any session in the most-recent group ended less than 30 minutes ago
    // Need to check current time against each session's end time
    val currentTime = System.currentTimeMillis() / 1000
    val hasRecentSession = lastGroup.sessions.any { session ->
        if (session.endTimeUnix == 0L) {
            // Session without timestamp - treat as recent
            true
        } else {
            val timeSinceEnd = currentTime - session.endTimeUnix
            timeSinceEnd < 30 * 60
        }
    }

    return if (hasRecentSession) {
        lastGroup
    } else {
        null
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
    private var sessionGroups = mutableStateOf<List<SessionGroup>>(emptyList())
    private var currentSessionGroup = mutableStateOf<SessionGroup?>(null)
    private var isConnected = mutableStateOf(false)

    // Computed state for display - includes grouped sessions
    private var displaySessionMiles = mutableStateOf(0f)
    private var displaySessionTime = mutableStateOf("0:00")
    private var displaySessionAvgSpeed = mutableStateOf(0f)

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
                    updateSessionGroupsAndCurrentGroup()
                }
            }
            lifecycleScope.launch {
                bleService?.odometerData?.collect { data ->
                    odometerData.value = data
                    updateSessionGroupsAndCurrentGroup()
                }
            }
            lifecycleScope.launch {
                bleService?.isConnected?.collect { connected ->
                    isConnected.value = connected
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
                    displaySessionMiles = displaySessionMiles.value,
                    displaySessionTime = displaySessionTime.value,
                    displaySessionAvgSpeed = displaySessionAvgSpeed.value,
                    hasGroupedSessions = currentSessionGroup.value != null,
                    sessionGroups = sessionGroups.value,
                    onUploadGroup = { group -> uploadGroupToStrava(group) },
                    onDiscardGroup = { group -> discardGroup(group) },
                    stravaAuthenticated = stravaAuthenticated.value,
                    onUploadCurrentSession = { uploadCurrentSessionToStrava() },
                    uploadingSessions = uploadingSessions.value,
                    isBleConnected = isConnected.value,
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

    private fun updateSessionGroupsAndCurrentGroup() {
        val allGroups = groupSessions(unreportedSessions.value)
        val currentData = odometerData.value

        // Calculate current session's estimated end time
        val currentSessionEndTime = if (currentData.sessionId > 0 && currentData.sessionTimeSeconds > 0) {
            System.currentTimeMillis() / 1000
        } else {
            0L
        }

        // Find which group the current session belongs to
        val groupForCurrent = findGroupForCurrentSession(
            currentData.sessionId,
            currentSessionEndTime,
            currentData.sessionMiles,
            allGroups
        )

        if (groupForCurrent != null) {
            // Create a new group that includes the current session
            val currentSession = SessionRecord(
                sessionId = currentData.sessionId,
                rotationCount = currentData.sessionRotations,
                activeTimeSeconds = currentData.sessionTimeSeconds,
                startTimeUnix = currentSessionEndTime - currentData.sessionTimeSeconds,
                endTimeUnix = currentSessionEndTime
            )

            val combinedSessions = groupForCurrent.sessions + currentSession
            val combinedGroup = createSessionGroup(combinedSessions)
            currentSessionGroup.value = combinedGroup

            // Filter out the group that contains the current session
            sessionGroups.value = allGroups.filter { it != groupForCurrent }

            // Update display stats to show combined values
            displaySessionMiles.value = combinedGroup.miles
            displaySessionTime.value = combinedGroup.formattedTime
            displaySessionAvgSpeed.value = combinedGroup.avgSpeed
        } else {
            // Current session doesn't belong to any group
            currentSessionGroup.value = null
            sessionGroups.value = allGroups

            // Display only current session stats
            displaySessionMiles.value = currentData.sessionMiles
            displaySessionTime.value = currentData.sessionTime
            displaySessionAvgSpeed.value = currentData.sessionAvgSpeed
        }
    }

    private fun uploadGroupToStrava(group: SessionGroup) {
        lifecycleScope.launch {
            // Check if any sessions in the group are already uploaded
            val alreadyUploaded = group.sessionIds.any { sessionCacheManager.isSessionUploaded(it) }
            if (alreadyUploaded) {
                stravaStatus.value = "Some sessions already uploaded to Strava"
                return@launch
            }

            // Mark all sessions in the group as "uploading"
            uploadingSessions.value = uploadingSessions.value + group.sessionIds.toSet()
            stravaStatus.value = "Uploading ${group.sessions.size} session(s) to Strava..."

            // Create a single SessionRecord representing the entire group
            val groupSession = SessionRecord(
                sessionId = group.sessions.first().sessionId, // Use first session ID for tracking
                rotationCount = group.totalRotationCount,
                activeTimeSeconds = group.totalActiveTimeSeconds,
                startTimeUnix = group.earliestStartTimeUnix,
                endTimeUnix = group.latestEndTimeUnix
            )

            val result = stravaRepository.uploadSession(groupSession)
            if (result.isSuccess) {
                val activity = result.getOrNull()!!
                stravaStatus.value = "Uploaded to Strava!"

                val distanceMeters = group.totalRotationCount * CM_PER_ROTATION / 100f

                // Mark ALL sessions in the group as uploaded
                for (session in group.sessions) {
                    sessionCacheManager.addUploadedSession(
                        UploadedSession(
                            sessionId = session.sessionId,
                            stravaActivityId = activity.id,
                            uploadTimestamp = System.currentTimeMillis() / 1000,
                            distanceMeters = distanceMeters / group.sessions.size, // Split distance evenly
                            elapsedTimeSeconds = session.activeTimeSeconds
                        )
                    )

                    // If BLE is connected, send confirmation to Pico for each session
                    if (isConnected.value) {
                        markSessionReported(session.sessionId)
                    }
                }

                // Remove all sessions in the group from displayed list
                unreportedSessions.value = unreportedSessions.value.filter {
                    it.sessionId !in group.sessionIds
                }
                bleService?.updateUnreportedSessions(unreportedSessions.value)
                updateSessionGroupsAndCurrentGroup()
            } else {
                stravaStatus.value = "Upload failed: ${result.exceptionOrNull()?.message}"
            }

            // Remove all sessions from uploading set
            uploadingSessions.value = uploadingSessions.value - group.sessionIds.toSet()
        }
    }

    private fun uploadCurrentSessionToStrava() {
        val currentData = odometerData.value
        if (currentData.sessionId == 0 || currentData.sessionRotations == 0) {
            stravaStatus.value = "No session to upload"
            return
        }

        lifecycleScope.launch {
            // Check if the current session belongs to a group
            val group = currentSessionGroup.value

            if (group != null) {
                // Upload the entire group including the current session
                val alreadyUploaded = group.sessionIds.any { sessionCacheManager.isSessionUploaded(it) }
                if (alreadyUploaded) {
                    stravaStatus.value = "Some sessions already uploaded to Strava"
                    return@launch
                }

                // Mark all sessions in the group as "uploading"
                uploadingSessions.value = uploadingSessions.value + group.sessionIds.toSet()
                val sessionCount = group.sessions.size
                stravaStatus.value = "Uploading $sessionCount session(s) to Strava..."

                // Create a single SessionRecord representing the entire group
                val groupSession = SessionRecord(
                    sessionId = group.sessions.first().sessionId,
                    rotationCount = group.totalRotationCount,
                    activeTimeSeconds = group.totalActiveTimeSeconds,
                    startTimeUnix = group.earliestStartTimeUnix,
                    endTimeUnix = group.latestEndTimeUnix
                )

                val result = stravaRepository.uploadSession(groupSession)
                if (result.isSuccess) {
                    val activity = result.getOrNull()!!
                    stravaStatus.value = "Uploaded to Strava!"

                    val distanceMeters = group.totalRotationCount * CM_PER_ROTATION / 100f

                    // Mark ALL sessions in the group as uploaded (excluding current, which isn't saved yet)
                    for (session in group.sessions) {
                        if (session.sessionId != currentData.sessionId) {
                            sessionCacheManager.addUploadedSession(
                                UploadedSession(
                                    sessionId = session.sessionId,
                                    stravaActivityId = activity.id,
                                    uploadTimestamp = System.currentTimeMillis() / 1000,
                                    distanceMeters = distanceMeters / group.sessions.size,
                                    elapsedTimeSeconds = session.activeTimeSeconds
                                )
                            )

                            // If BLE is connected, send confirmation to Pico for each session
                            if (isConnected.value) {
                                markSessionReported(session.sessionId)
                            }
                        }
                    }

                    // Mark current session as uploaded
                    sessionCacheManager.addUploadedSession(
                        UploadedSession(
                            sessionId = currentData.sessionId,
                            stravaActivityId = activity.id,
                            uploadTimestamp = System.currentTimeMillis() / 1000,
                            distanceMeters = distanceMeters / group.sessions.size,
                            elapsedTimeSeconds = currentData.sessionTimeSeconds
                        )
                    )

                    // Send confirmation for current session
                    if (isConnected.value) {
                        markSessionReported(currentData.sessionId)
                    }

                    // Remove all non-current sessions in the group from displayed list
                    unreportedSessions.value = unreportedSessions.value.filter {
                        it.sessionId !in group.sessionIds || it.sessionId == currentData.sessionId
                    }
                    bleService?.updateUnreportedSessions(unreportedSessions.value)
                    updateSessionGroupsAndCurrentGroup()
                } else {
                    stravaStatus.value = "Upload failed: ${result.exceptionOrNull()?.message}"
                }

                // Remove all sessions from uploading set
                uploadingSessions.value = uploadingSessions.value - group.sessionIds.toSet()
            } else {
                // Upload just the current session (not part of any group)
                if (sessionCacheManager.isSessionUploaded(currentData.sessionId)) {
                    stravaStatus.value = "Already uploaded to Strava"
                    return@launch
                }

                uploadingSessions.value = uploadingSessions.value + currentData.sessionId
                stravaStatus.value = "Uploading current session..."

                val currentTime = System.currentTimeMillis() / 1000
                val startTime = currentTime - currentData.sessionTimeSeconds
                val session = SessionRecord(
                    sessionId = currentData.sessionId,
                    rotationCount = currentData.sessionRotations,
                    activeTimeSeconds = currentData.sessionTimeSeconds,
                    startTimeUnix = startTime,
                    endTimeUnix = currentTime
                )

                val result = stravaRepository.uploadSession(session)
                if (result.isSuccess) {
                    val activity = result.getOrNull()!!
                    stravaStatus.value = "Uploaded to Strava!"

                    val distanceMeters = session.rotationCount * CM_PER_ROTATION / 100f

                    sessionCacheManager.addUploadedSession(
                        UploadedSession(
                            sessionId = session.sessionId,
                            stravaActivityId = activity.id,
                            uploadTimestamp = currentTime,
                            distanceMeters = distanceMeters,
                            elapsedTimeSeconds = session.activeTimeSeconds
                        )
                    )

                    // If BLE is connected, send confirmation to Pico
                    if (isConnected.value) {
                        markSessionReported(session.sessionId)
                    }
                } else {
                    stravaStatus.value = "Upload failed: ${result.exceptionOrNull()?.message}"
                }

                uploadingSessions.value = uploadingSessions.value - currentData.sessionId
            }
        }
    }

    private fun markSessionReported(sessionId: Int) {
        bleService?.markSessionReported(sessionId)
    }

    private fun discardGroup(group: SessionGroup) {
        lifecycleScope.launch {
            // Mark ALL sessions in the group as discarded
            for (session in group.sessions) {
                sessionCacheManager.addDiscardedSession(session.sessionId)

                // If BLE is connected, send the discard command immediately
                if (isConnected.value) {
                    markSessionReported(session.sessionId)
                }
            }

            // Remove all sessions in the group from displayed list immediately
            unreportedSessions.value = unreportedSessions.value.filter {
                it.sessionId !in group.sessionIds
            }
            bleService?.updateUnreportedSessions(unreportedSessions.value)
            updateSessionGroupsAndCurrentGroup()
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
    displaySessionMiles: Float,
    displaySessionTime: String,
    displaySessionAvgSpeed: Float,
    hasGroupedSessions: Boolean,
    sessionGroups: List<SessionGroup>,
    onUploadGroup: (SessionGroup) -> Unit,
    onDiscardGroup: (SessionGroup) -> Unit,
    stravaAuthenticated: Boolean,
    onUploadCurrentSession: () -> Unit,
    uploadingSessions: Set<Int>,
    isBleConnected: Boolean,
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
            displaySessionMiles = displaySessionMiles,
            displaySessionTime = displaySessionTime,
            displaySessionAvgSpeed = displaySessionAvgSpeed,
            hasGroupedSessions = hasGroupedSessions,
            sessionGroups = sessionGroups,
            onUploadGroup = onUploadGroup,
            onDiscardGroup = onDiscardGroup,
            stravaAuthenticated = stravaAuthenticated,
            onUploadCurrentSession = onUploadCurrentSession,
            uploadingSessions = uploadingSessions,
            isBleConnected = isBleConnected,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
fun OdometerScreen(
    connectionStatus: String,
    odometerData: OdometerData,
    displaySessionMiles: Float,
    displaySessionTime: String,
    displaySessionAvgSpeed: Float,
    hasGroupedSessions: Boolean,
    sessionGroups: List<SessionGroup>,
    onUploadGroup: (SessionGroup) -> Unit,
    onDiscardGroup: (SessionGroup) -> Unit,
    stravaAuthenticated: Boolean,
    onUploadCurrentSession: () -> Unit,
    uploadingSessions: Set<Int>,
    isBleConnected: Boolean,
    modifier: Modifier = Modifier
) {
    var groupToConfirmDelete by remember { mutableStateOf<SessionGroup?>(null) }

    groupToConfirmDelete?.let { group ->
        val sessionCountText = if (group.sessions.size == 1) "1 session" else "${group.sessions.size} sessions"
        AlertDialog(
            onDismissRequest = { groupToConfirmDelete = null },
            title = { Text("Discard Sessions?") },
            text = {
                Text("This will permanently delete $sessionCountText (${String.format("%.2f %s", group.miles, odometerData.distanceUnit)}, ${group.formattedTime}) without uploading it anywhere. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDiscardGroup(group)
                        groupToConfirmDelete = null
                    }
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToConfirmDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = connectionStatus,
            fontSize = 14.sp,
            color = if (connectionStatus == "Connected") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )

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
            text = String.format("%.2f %s", displaySessionAvgSpeed, odometerData.speedUnit),
            fontSize = 36.sp,
            fontWeight = FontWeight.SemiBold
        )

        Divider(modifier = Modifier.padding(vertical = 24.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Session",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (hasGroupedSessions) {
                    Text(
                        text = "includes previous",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Distance:", fontSize = 18.sp)
                Text(
                    text = String.format("%.2f %s", displaySessionMiles, odometerData.distanceUnit),
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
                    text = displaySessionTime,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            val canUploadCurrentSession = stravaAuthenticated &&
                odometerData.sessionId > 0 &&
                odometerData.sessionRotations > 0 &&
                (!isBleConnected || odometerData.runningAvgSpeed == 0f)

            if (canUploadCurrentSession) {
                val isUploading = odometerData.sessionId in uploadingSessions
                Button(
                    onClick = onUploadCurrentSession,
                    enabled = !isUploading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    if (isUploading) {
                        Text("Uploading...")
                    } else {
                        Text("Upload to Strava")
                    }
                }
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

            if (sessionGroups.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Divider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Unreported Sessions",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp, top = 8.dp)
                )

                sessionGroups.forEach { group ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            // Show date range
                            Text(
                                text = "${group.startDateStr} - ${group.endDateStr}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            // Show session count if more than 1
                            if (group.sessions.size > 1) {
                                Text(
                                    text = "(${group.sessions.size} sessions combined)",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Walking time:", fontSize = 14.sp)
                                Text(text = group.formattedTime, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Distance:", fontSize = 14.sp)
                                Text(text = String.format("%.2f %s", group.miles, odometerData.distanceUnit), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Avg speed:", fontSize = 14.sp)
                                Text(text = String.format("%.2f mph", group.avgSpeed), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }

                            // Check if any session in the group is uploading
                            val isUploading = group.sessionIds.any { it in uploadingSessions }

                            if (stravaAuthenticated) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { onUploadGroup(group) },
                                        enabled = !isUploading,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (isUploading) "Uploading..." else "Upload")
                                    }

                                    OutlinedButton(
                                        onClick = { groupToConfirmDelete = group },
                                        enabled = !isUploading,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Discard")
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { groupToConfirmDelete = group },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                ) {
                                    Text("Clear Session${if (group.sessions.size > 1) "s" else ""}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
