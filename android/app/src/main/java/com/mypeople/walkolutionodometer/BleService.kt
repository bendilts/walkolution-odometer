package com.mypeople.walkolutionodometer

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class UserSettings(
    val metric: Boolean
)

class BleService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // BLE components
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var notificationsEnabled = false

    // Session cache manager for syncing confirmations
    private lateinit var sessionCacheManager: SessionCacheManager

    // State exposed to UI via StateFlow
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _odometerData = MutableStateFlow(OdometerData())
    val odometerData: StateFlow<OdometerData> = _odometerData.asStateFlow()

    private val _unreportedSessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val unreportedSessions: StateFlow<List<SessionRecord>> = _unreportedSessions.asStateFlow()

    private val _userSettings = MutableStateFlow<UserSettings?>(null)
    val userSettings: StateFlow<UserSettings?> = _userSettings.asStateFlow()

    // Log storage - unlimited since logs are now grouped/compressed
    private val _logMessages = MutableStateFlow<String>("")
    val logMessages: StateFlow<String> = _logMessages.asStateFlow()

    // Notification
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // AlarmManager for periodic connection attempts
    private lateinit var alarmManager: AlarmManager
    private var periodicConnectionPendingIntent: PendingIntent? = null

    // Track pending scan attempts to prevent race conditions
    private var pendingScanRunnable: Runnable? = null
    private var isConnecting = false
    private var connectionAttemptStartTime = 0L
    private var lastGattActivityTime = 0L
    private var consecutiveFailures = 0
    private var lastScanCallbackTime = 0L
    private var scanStartTime = 0L

    // MTU negotiation
    private val desiredMtu = 185

    // Watchdog timer to detect and recover from stuck states
    private var watchdogRunnable: Runnable? = null

    // Connection retry backoff
    private var retryDelayMs = 1000L
    private val minRetryDelayMs = 1000L
    private val maxRetryDelayMs = 10000L

    // Bluetooth state receiver
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                handleBluetoothStateChange(state)
            } else if (action == PERIODIC_CONNECTION_ACTION) {
                Log.i(TAG, "Periodic connection alarm triggered")
                handlePeriodicConnectionAttempt()
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    companion object {
        private const val TAG = "BleService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "walkolution_ble_channel"
        private const val PERIODIC_CONNECTION_ACTION = "com.mypeople.walkolutionodometer.PERIODIC_CONNECTION_ATTEMPT"
        private const val PERIODIC_CONNECTION_REQUEST_CODE = 1001

        // UUIDs matching the Pico
        val ODOMETER_SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        val ODOMETER_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        val SESSIONS_LIST_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
        val MARK_REPORTED_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")
        val TIME_SYNC_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef4")
        val USER_SETTINGS_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef5")
        val SET_LIFETIME_TOTALS_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef7")
        val LOGS_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef8")
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CM_PER_ROTATION = 34.56f
        const val METERS_PER_MILE = 1609.344f
        const val MILES_PER_ROTATION = CM_PER_ROTATION / 100.0f / METERS_PER_MILE
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BleService created")

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        sessionCacheManager = SessionCacheManager(this)

        createNotificationChannel()

        // Register Bluetooth state change receiver and periodic connection receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(PERIODIC_CONNECTION_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "BleService started")

        // Start as foreground service
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Load cached sessions (filtering out uploaded and discarded)
        serviceScope.launch {
            val cached = sessionCacheManager.getCachedSessions()
            if (cached.isNotEmpty()) {
                val uploadedIds = sessionCacheManager.getUploadedSessions().map { it.sessionId }.toSet()
                val discardedIds = sessionCacheManager.getDiscardedSessions().map { it.sessionId }.toSet()
                val filteredCached = cached.filter {
                    it.sessionId !in uploadedIds && it.sessionId !in discardedIds
                }
                _unreportedSessions.value = filteredCached
            }
        }

        // Start scanning if we have permissions
        if (hasBluetoothPermissions()) {
            startBluetoothScan()
        }

        // Start watchdog to detect stuck states
        startWatchdog()

        // Schedule periodic connection attempts
        schedulePeriodicConnectionAttempts()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "BleService destroyed")

        // Unregister Bluetooth state change receiver
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered")
        }

        // Cancel any pending scan attempts
        pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingScanRunnable = null
        isConnecting = false

        // Cancel watchdog
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null

        // Cancel periodic connection attempts
        cancelPeriodicConnectionAttempts()

        stopBluetoothScan()
        if (hasBluetoothConnectPermission()) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        }

        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Walkolution Odometer",
                NotificationManager.IMPORTANCE_LOW // Low importance = no sound, minimized
            ).apply {
                description = "Shows current walking session progress"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val data = _odometerData.value
        val status = _connectionStatus.value

        val contentText = if (_isConnected.value && data.sessionRotations > 0) {
            String.format("%.2f mi • %s • %.1f mph",
                data.sessionMiles, data.sessionTime, data.sessionAvgSpeed)
        } else if (_isConnected.value) {
            "Connected - No active session"
        } else if (_scanning.value) {
            "Scanning for device..."
        } else {
            status
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Walkolution Odometer")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_directions) // TODO: Use custom icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // Watchdog to detect and recover from stuck states
    private fun startWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }

        watchdogRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()

                // Check if Bluetooth is enabled
                if (bluetoothAdapter?.isEnabled != true) {
                    if (_isConnected.value || isConnecting || _scanning.value) {
                        Log.w(TAG, "Watchdog: Bluetooth is OFF but we think we're active, cleaning up")
                        _isConnected.value = false
                        isConnecting = false
                        _scanning.value = false
                        cleanupGatt()
                        _connectionStatus.value = "Bluetooth is OFF"
                        updateNotification()
                    }
                    // Schedule next check and return
                    mainHandler.postDelayed(this, 5000)
                    return
                }

                // Check permissions
                if (!hasBluetoothPermissions()) {
                    if (_scanning.value || isConnecting || _isConnected.value) {
                        Log.w(TAG, "Watchdog: Missing permissions but we think we're active, cleaning up")
                        _scanning.value = false
                        isConnecting = false
                        _isConnected.value = false
                        cleanupGatt()
                        _connectionStatus.value = "Missing permissions"
                        updateNotification()
                    }
                    // Schedule next check and return
                    mainHandler.postDelayed(this, 5000)
                    return
                }

                // Check if we're stuck in connecting state
                if (isConnecting && connectionAttemptStartTime > 0) {
                    val connectingDuration = now - connectionAttemptStartTime
                    if (connectingDuration > 15000) {
                        Log.w(TAG, "Watchdog: Connection attempt stuck for ${connectingDuration}ms, forcing reset")
                        forceConnectionReset("Stuck in connecting state")
                    }
                }

                // Check if GATT object is stale (connected but no activity)
                if (_isConnected.value && bluetoothGatt != null && lastGattActivityTime > 0) {
                    val idleDuration = now - lastGattActivityTime
                    if (idleDuration > 30000) {
                        Log.w(TAG, "Watchdog: GATT connection idle for ${idleDuration}ms, testing connection")
                        // Try to read services as a health check
                        try {
                            val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
                            if (service == null) {
                                Log.e(TAG, "Watchdog: Service disappeared, forcing reconnect")
                                forceConnectionReset("Service disappeared")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Watchdog: Exception checking service: ${e.message}")
                            forceConnectionReset("Exception checking service")
                        }
                    }
                }

                // Check if we should be scanning but aren't
                if (!_isConnected.value && !isConnecting && !_scanning.value) {
                    Log.w(TAG, "Watchdog: Not connected, not connecting, not scanning - starting scan")
                    startBluetoothScan()
                }

                // Check if scanner is stuck (scanning flag is true but no callbacks received)
                if (_scanning.value && scanStartTime > 0) {
                    val scanDuration = now - scanStartTime
                    val timeSinceLastCallback = now - lastScanCallbackTime

                    // If we've been "scanning" for >15 seconds with no callbacks, scanner is stuck
                    if (scanDuration > 15000 && (lastScanCallbackTime == 0L || timeSinceLastCallback > 15000)) {
                        Log.w(TAG, "Watchdog: Scanner stuck - scanning for ${scanDuration}ms but no callbacks for ${timeSinceLastCallback}ms")
                        _scanning.value = false
                        scanStartTime = 0
                        lastScanCallbackTime = 0
                        resetBluetoothScanner()

                        mainHandler.postDelayed({
                            Log.i(TAG, "Restarting scan after stuck detection")
                            startBluetoothScan()
                        }, 1000)
                    }
                }

                // Check if GATT object exists but we're not connected
                if (bluetoothGatt != null && !_isConnected.value && !isConnecting) {
                    val staleDuration = now - lastGattActivityTime
                    if (staleDuration > 10000) {
                        Log.w(TAG, "Watchdog: Stale GATT object detected, cleaning up")
                        cleanupGatt()
                    }
                }

                // Schedule next watchdog check
                mainHandler.postDelayed(this, 5000)
            }
        }.also { mainHandler.postDelayed(it, 5000) }
    }

    private fun forceConnectionReset(reason: String) {
        Log.i(TAG, "Forcing connection reset: $reason")

        isConnecting = false
        connectionAttemptStartTime = 0
        _isConnected.value = false
        notificationsEnabled = false

        cleanupGatt()
        stopBluetoothScan()

        // Increment failure counter and use backoff
        consecutiveFailures++
        retryDelayMs = minOf(retryDelayMs * 2, maxRetryDelayMs)

        _connectionStatus.value = "Reconnecting... (attempt ${consecutiveFailures})"
        updateNotification()

        // Schedule retry with backoff
        pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingScanRunnable = Runnable {
            Log.i(TAG, "Retrying connection after reset")
            resetBluetoothScanner()
            startBluetoothScan()
        }.also { mainHandler.postDelayed(it, retryDelayMs) }
    }

    private fun cleanupGatt() {
        stopLogPolling()
        // Clear any pending BLE requests
        bleRequestQueue.clear()
        processingBleRequest = false
        try {
            if (hasBluetoothConnectPermission()) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during GATT cleanup: ${e.message}")
        }
        bluetoothGatt = null
        lastGattActivityTime = 0
    }

    private fun resetBluetoothScanner() {
        if (hasBluetoothPermissions()) {
            // First, aggressively stop any existing scans
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
                Log.d(TAG, "Force stopped existing scan during reset")
            } catch (e: Exception) {
                Log.d(TAG, "Exception during force stop (may be expected): ${e.message}")
            }

            stopBluetoothScan()

            // Force re-get the scanner instance
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            Log.i(TAG, "BLE scanner reset")
        }
    }

    // Schedule periodic connection attempts using AlarmManager (every 30 minutes)
    private fun schedulePeriodicConnectionAttempts() {
        val intent = Intent(PERIODIC_CONNECTION_ACTION).apply {
            setPackage(packageName)
        }

        periodicConnectionPendingIntent = PendingIntent.getBroadcast(
            this,
            PERIODIC_CONNECTION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule for every 30 minutes
        val intervalMillis = 30 * 60 * 1000L // 30 minutes
        val triggerAtMillis = System.currentTimeMillis() + intervalMillis

        try {
            // Use setExactAndAllowWhileIdle to work even in Doze mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    periodicConnectionPendingIntent!!
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    periodicConnectionPendingIntent!!
                )
            }
            Log.i(TAG, "Scheduled periodic connection attempt in 30 minutes")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to schedule periodic alarm: ${e.message}")
        }
    }

    // Cancel periodic connection attempts
    private fun cancelPeriodicConnectionAttempts() {
        periodicConnectionPendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.i(TAG, "Cancelled periodic connection attempts")
        }
        periodicConnectionPendingIntent = null
    }

    // Handle periodic connection attempt triggered by alarm
    private fun handlePeriodicConnectionAttempt() {
        Log.i(TAG, "=== PERIODIC CONNECTION ATTEMPT ===")
        Log.i(TAG, "Connected: ${_isConnected.value}, Connecting: $isConnecting, Scanning: ${_scanning.value}")

        // Reschedule for next interval
        schedulePeriodicConnectionAttempts()

        // Only attempt connection if not already connected or connecting
        if (!_isConnected.value && !isConnecting) {
            // Check if we have permissions and Bluetooth is enabled
            if (!hasBluetoothPermissions()) {
                Log.w(TAG, "Periodic attempt skipped: missing permissions")
                _connectionStatus.value = "Missing Bluetooth permissions"
                updateNotification()
                return
            }

            if (bluetoothAdapter?.isEnabled != true) {
                Log.w(TAG, "Periodic attempt skipped: Bluetooth is OFF")
                _connectionStatus.value = "Bluetooth is OFF"
                updateNotification()
                return
            }

            Log.i(TAG, "Periodic attempt: forcing scan restart")

            // Cancel any pending scan attempts
            pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingScanRunnable = null

            // Reset the scanner and start fresh
            resetBluetoothScanner()

            // Start scan immediately
            mainHandler.post {
                startBluetoothScan()
            }
        } else {
            Log.i(TAG, "Periodic attempt skipped: already connected or connecting")
        }
    }

    private fun handleBluetoothStateChange(state: Int) {
        Log.i(TAG, "Bluetooth state changed: $state")
        when (state) {
            BluetoothAdapter.STATE_OFF -> {
                Log.w(TAG, "Bluetooth turned OFF - cleaning up connections")
                _isConnected.value = false
                isConnecting = false
                connectionAttemptStartTime = 0
                notificationsEnabled = false
                _scanning.value = false
                _connectionStatus.value = "Bluetooth OFF"

                // Cancel pending operations
                pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingScanRunnable = null

                // Clean up GATT without permission checks (BT is off anyway)
                try {
                    bluetoothGatt?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing GATT: ${e.message}")
                }
                bluetoothGatt = null
                lastGattActivityTime = 0
                bluetoothLeScanner = null

                updateNotification()
            }
            BluetoothAdapter.STATE_ON -> {
                Log.i(TAG, "Bluetooth turned ON - reinitializing")
                // Reinitialize adapter and scanner
                val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                bluetoothAdapter = bluetoothManager.adapter
                bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

                // Reset failure counters
                consecutiveFailures = 0
                retryDelayMs = minRetryDelayMs

                // Start fresh scan if we have permissions
                if (hasBluetoothPermissions()) {
                    _connectionStatus.value = "Bluetooth ON - Starting scan..."
                    updateNotification()

                    // Delay slightly to let BT adapter fully initialize
                    mainHandler.postDelayed({
                        Log.i(TAG, "Starting scan after Bluetooth ON")
                        startBluetoothScan()
                    }, 500)
                } else {
                    _connectionStatus.value = "Bluetooth ON - Missing permissions"
                    updateNotification()
                }
            }
            BluetoothAdapter.STATE_TURNING_OFF -> {
                Log.i(TAG, "Bluetooth turning OFF")
                _connectionStatus.value = "Bluetooth turning OFF..."
                updateNotification()
            }
            BluetoothAdapter.STATE_TURNING_ON -> {
                Log.i(TAG, "Bluetooth turning ON")
                _connectionStatus.value = "Bluetooth turning ON..."
                updateNotification()
            }
        }
    }

    // Permission checks
    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    // BLE Scanning
    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            lastScanCallbackTime = System.currentTimeMillis()

            try {
                if (!hasBluetoothConnectPermission()) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission in scan callback")
                    stopBluetoothScan()
                    _connectionStatus.value = "Permission denied"
                    updateNotification()
                    return
                }

                val deviceName = result.device.name
                val scanRecord = result.scanRecord
                val localName = scanRecord?.deviceName

                Log.d(TAG, "Scan result: device=$deviceName, localName=$localName, address=${result.device.address}")

                val nameToCheck = localName ?: deviceName
                if (nameToCheck == "Walk Odo" || nameToCheck == "Walkolution Odo") {
                    if (bluetoothGatt == null && !_isConnected.value && !isConnecting) {
                        Log.i(TAG, "Found Walkolution device, connecting...")
                        stopBluetoothScan()
                        connectToDevice(result.device)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in scan callback: ${e.message}")
                stopBluetoothScan()
                _connectionStatus.value = "Permission denied"
                updateNotification()
            } catch (e: Exception) {
                Log.e(TAG, "Exception in scan callback: ${e.message}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            _scanning.value = false
            scanStartTime = 0
            lastScanCallbackTime = 0

            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Error code: $errorCode"
            }
            _connectionStatus.value = "Scan failed: $errorMessage"
            updateNotification()

            // Special handling for SCAN_FAILED_ALREADY_STARTED
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
                handleScanAlreadyStartedError()
                return
            }

            // Increment failure counter and retry with backoff
            consecutiveFailures++

            // Reset scanner and retry
            pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingScanRunnable = Runnable {
                Log.i(TAG, "Retrying scan after failure (attempt ${consecutiveFailures})")
                resetBluetoothScanner()
                startBluetoothScan()
            }.also { mainHandler.postDelayed(it, minOf(consecutiveFailures * 2000L, 10000L)) }
        }
    }

    private fun handleScanAlreadyStartedError() {
        Log.w(TAG, "Scan already started - forcing stop before retry")
        // Force stop any existing scan
        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception force-stopping scan: ${e.message}")
        }

        // Retry quickly after forcing stop
        pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingScanRunnable = Runnable {
            Log.i(TAG, "Retrying scan after forced stop")
            startBluetoothScan()
        }.also { mainHandler.postDelayed(it, 500) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            lastGattActivityTime = System.currentTimeMillis()

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Connection failed with status: $status")
                _connectionStatus.value = "Connection error: $status"
                _isConnected.value = false
                isConnecting = false
                connectionAttemptStartTime = 0
                consecutiveFailures++

                cleanupGatt()

                // Use exponential backoff for retries
                retryDelayMs = minOf(retryDelayMs * 2, maxRetryDelayMs)

                // Cancel any pending scan and schedule a new one
                pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
                pendingScanRunnable = Runnable {
                    resetBluetoothScanner()
                    startBluetoothScan()
                }.also { mainHandler.postDelayed(it, retryDelayMs) }
                updateNotification()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "BLE connected, requesting MTU...")
                    _isConnected.value = true
                    isConnecting = false
                    connectionAttemptStartTime = 0
                    consecutiveFailures = 0
                    retryDelayMs = minRetryDelayMs
                    _connectionStatus.value = "Connected - Requesting MTU..."
                    // Cancel any pending scan attempts since we're now connected
                    pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingScanRunnable = null

                    try {
                        if (hasBluetoothConnectPermission()) {
                            gatt.requestMtu(desiredMtu)
                            gatt.discoverServices()
                        } else {
                            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission after connection")
                            forceConnectionReset("Permission denied after connection")
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException during MTU/discovery: ${e.message}")
                        forceConnectionReset("Permission denied during MTU/discovery")
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during MTU/discovery: ${e.message}")
                        forceConnectionReset("Error during MTU/discovery")
                    }
                    updateNotification()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "BLE disconnected")
                    _isConnected.value = false
                    isConnecting = false
                    connectionAttemptStartTime = 0
                    _connectionStatus.value = "Disconnected"
                    notificationsEnabled = false

                    // Stop log polling on disconnect
                    stopLogPolling()

                    cleanupGatt()

                    // Cancel any pending scan and schedule a new one
                    pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingScanRunnable = Runnable {
                        startBluetoothScan()
                    }.also { mainHandler.postDelayed(it, 1000) }
                    updateNotification()
                }
                else -> {
                    Log.w(TAG, "Unexpected connection state: $newState")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            lastGattActivityTime = System.currentTimeMillis()

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(ODOMETER_SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "Odometer service not found! Forcing reconnect")
                    _connectionStatus.value = "Service not found"
                    updateNotification()
                    forceConnectionReset("Service not found")
                    return
                }

                val characteristic = service.getCharacteristic(ODOMETER_CHARACTERISTIC_UUID)
                if (characteristic == null) {
                    Log.e(TAG, "Odometer characteristic not found! Forcing reconnect")
                    _connectionStatus.value = "Characteristic not found"
                    updateNotification()
                    forceConnectionReset("Characteristic not found")
                    return
                }

                try {
                    if (hasBluetoothConnectPermission()) {
                        gatt.setCharacteristicNotification(characteristic, true)

                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        if (descriptor != null) {
                            Log.i(TAG, "Writing to CCCD to enable notifications...")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        } else {
                            Log.e(TAG, "CCCD descriptor not found! Forcing reconnect")
                            _connectionStatus.value = "Descriptor not found"
                            updateNotification()
                            forceConnectionReset("Descriptor not found")
                        }
                    } else {
                        Log.e(TAG, "Missing BLUETOOTH_CONNECT permission during service discovery")
                        forceConnectionReset("Permission denied")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException enabling notifications: ${e.message}")
                    forceConnectionReset("Permission denied during setup")
                } catch (e: Exception) {
                    Log.e(TAG, "Exception enabling notifications: ${e.message}")
                    forceConnectionReset("Error during setup")
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status. Forcing reconnect")
                _connectionStatus.value = "Service discovery failed: $status"
                updateNotification()
                forceConnectionReset("Service discovery failed: $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: mtu=$mtu, status=$status")
            lastGattActivityTime = System.currentTimeMillis()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            lastGattActivityTime = System.currentTimeMillis()

            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    notificationsEnabled = true
                    Log.i(TAG, "Notifications enabled successfully!")
                    _connectionStatus.value = "Connected"

                    // Start the BLE request queue with initial setup sequence
                    mainHandler.postDelayed({
                        enqueueBleRequest(BleRequest.SendTimeSync)
                        // ReadUserSettings, ReadSessionsList, and StartLogPolling will be queued
                        // automatically by the time sync completion callback
                    }, 100)
                } else {
                    notificationsEnabled = false
                    Log.e(TAG, "Failed to enable notifications, status: $status. Forcing reconnect")
                    _connectionStatus.value = "Failed to enable notifications: $status"
                    updateNotification()
                    forceConnectionReset("Failed to enable notifications: $status")
                    return
                }
                updateNotification()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "onCharacteristicWrite: ${characteristic.uuid}, status=$status")
            lastGattActivityTime = System.currentTimeMillis()

            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    TIME_SYNC_CHARACTERISTIC_UUID -> {
                        Log.i(TAG, "Time sync sent successfully")
                        // Complete the request and queue next operations
                        completeBleRequest()
                        enqueueBleRequest(BleRequest.ReadUserSettings)
                        enqueueBleRequest(BleRequest.ReadSessionsList)
                        enqueueBleRequest(BleRequest.StartLogPolling)
                    }
                    MARK_REPORTED_CHARACTERISTIC_UUID -> {
                        Log.i(TAG, "Session marked as reported successfully")
                        completeBleRequest()
                    }
                    USER_SETTINGS_CHARACTERISTIC_UUID -> {
                        Log.i(TAG, "User settings written successfully")
                        completeBleRequest()
                    }
                }
            } else {
                Log.w(TAG, "Characteristic write failed: ${characteristic.uuid}, status=$status")
                completeBleRequest()
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            lastGattActivityTime = System.currentTimeMillis()
            Log.d(TAG, "onCharacteristicRead (API 33+): UUID=${characteristic.uuid}, status=$status, size=${value.size}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    SESSIONS_LIST_CHARACTERISTIC_UUID -> {
                        Log.i(TAG, "Sessions list read successful, ${value.size} bytes")
                        parseSessionsList(value)
                        completeBleRequest()
                    }
                    USER_SETTINGS_CHARACTERISTIC_UUID -> {
                        parseUserSettings(value)
                        completeBleRequest()
                    }
                    LOGS_CHARACTERISTIC_UUID -> {
                        parseLogs(value)
                        // This is polled, not queued, so don't complete
                    }
                }
            } else {
                Log.w(TAG, "Characteristic read failed: ${characteristic.uuid}, status=$status")
                // Complete request even on failure to avoid queue getting stuck
                when (characteristic.uuid) {
                    SESSIONS_LIST_CHARACTERISTIC_UUID, USER_SETTINGS_CHARACTERISTIC_UUID -> completeBleRequest()
                }
            }
        }

        @Deprecated("Deprecated in API level 33")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            lastGattActivityTime = System.currentTimeMillis()
            Log.d(TAG, "onCharacteristicRead (deprecated): UUID=${characteristic.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristic.value?.let { value ->
                    Log.d(TAG, "Characteristic value size: ${value.size}")
                    when (characteristic.uuid) {
                        SESSIONS_LIST_CHARACTERISTIC_UUID -> {
                            Log.i(TAG, "Sessions list read successful (deprecated API), ${value.size} bytes")
                            parseSessionsList(value)
                            completeBleRequest()
                        }
                        USER_SETTINGS_CHARACTERISTIC_UUID -> {
                            parseUserSettings(value)
                            completeBleRequest()
                        }
                        LOGS_CHARACTERISTIC_UUID -> {
                            parseLogs(value)
                            // This is polled, not queued, so don't complete
                        }
                    }
                } ?: Log.w(TAG, "Characteristic value is null")
            } else {
                Log.w(TAG, "Characteristic read failed: ${characteristic.uuid}, status=$status")
                // Complete request even on failure to avoid queue getting stuck
                when (characteristic.uuid) {
                    SESSIONS_LIST_CHARACTERISTIC_UUID, USER_SETTINGS_CHARACTERISTIC_UUID -> completeBleRequest()
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            lastGattActivityTime = System.currentTimeMillis()
            if (characteristic.uuid == ODOMETER_CHARACTERISTIC_UUID) {
                parseOdometerData(value)
            }
        }

        @Deprecated("Deprecated in API level 33")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            lastGattActivityTime = System.currentTimeMillis()
            if (characteristic.uuid == ODOMETER_CHARACTERISTIC_UUID) {
                characteristic.value?.let { parseOdometerData(it) }
            }
        }
    }

    private fun parseOdometerData(data: ByteArray) {
        if (data.size < 28) {
            Log.e(TAG, "Data too short: ${data.size} bytes, expected at least 28")
            return
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val sessionRotations = buffer.int.toUInt()
        val totalRotations = buffer.int.toUInt()
        val sessionTimeSeconds = buffer.int.toUInt()
        val totalTimeSeconds = buffer.int.toUInt()
        val runningAvgSpeed = buffer.float
        val sessionAvgSpeed = buffer.float
        val voltageMv = buffer.int.toUInt()

        val sessionId = if (data.size >= 32) buffer.int else 0

        // Parse settings if included (65+ bytes)
        val metric = if (data.size >= 33) buffer.get().toInt() != 0 else false
        val ssid = if (data.size >= 65) {
            val ssidBytes = ByteArray(32)
            buffer.get(ssidBytes)
            String(ssidBytes).trim('\u0000')
        } else ""

        val newData = OdometerData(
            sessionMiles = sessionRotations.toFloat() * MILES_PER_ROTATION,
            totalMiles = totalRotations.toFloat() * MILES_PER_ROTATION,
            sessionTime = formatTime(sessionTimeSeconds.toInt()),
            totalTime = formatTime(totalTimeSeconds.toInt()),
            runningAvgSpeed = runningAvgSpeed,
            sessionAvgSpeed = sessionAvgSpeed,
            voltageV = voltageMv.toFloat() / 1000f,
            sessionId = sessionId,
            sessionRotations = sessionRotations.toInt(),
            sessionTimeSeconds = sessionTimeSeconds.toInt(),
            metric = metric
        )

        Log.d(TAG, "Parsed data: sessRot=$sessionRotations, runSpeed=$runningAvgSpeed ${if (metric) "km/h" else "mph"}, sessionId=$sessionId, metric=$metric, ssid=$ssid")

        val previousSessionId = _odometerData.value.sessionId
        _odometerData.value = newData

        // Check for sessions uploaded while offline
        if (sessionId > 0 && sessionId != previousSessionId) {
            serviceScope.launch {
                if (sessionCacheManager.isSessionUploaded(sessionId)) {
                    Log.i(TAG, "Current session $sessionId was uploaded offline - sending confirmation")
                    markSessionReported(sessionId)
                    sessionCacheManager.removeUploadedSession(sessionId)
                }
            }
        }

        updateNotification()
    }

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

    fun startBluetoothScan() {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions for scan")
            _connectionStatus.value = "Missing Bluetooth permissions"
            _scanning.value = false
            updateNotification()
            return
        }

        // Don't start scanning if already connected or connecting
        if (_isConnected.value || isConnecting) {
            Log.d(TAG, "Skipping scan - already connected or connecting")
            return
        }

        // Check if Bluetooth is enabled
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth is not enabled")
            _connectionStatus.value = "Bluetooth is OFF"
            _scanning.value = false
            updateNotification()
            return
        }

        if (!_scanning.value) {
            Log.i(TAG, "Starting BLE scan...")
            try {
                bluetoothLeScanner?.startScan(leScanCallback)
                _scanning.value = true
                scanStartTime = System.currentTimeMillis()
                lastScanCallbackTime = 0
                _connectionStatus.value = "Scanning for device..."
                updateNotification()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException starting scan: ${e.message}")
                _scanning.value = false
                scanStartTime = 0
                lastScanCallbackTime = 0
                _connectionStatus.value = "Permission denied"
                updateNotification()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalStateException starting scan: ${e.message}")
                _scanning.value = false
                scanStartTime = 0
                lastScanCallbackTime = 0
                _connectionStatus.value = "Bluetooth error"
                updateNotification()
                // Retry with backoff
                mainHandler.postDelayed({
                    resetBluetoothScanner()
                    startBluetoothScan()
                }, 2000)
            } catch (e: Exception) {
                Log.e(TAG, "Exception starting scan: ${e.message}")
                _scanning.value = false
                scanStartTime = 0
                lastScanCallbackTime = 0
                _connectionStatus.value = "Scan failed"
                updateNotification()
            }
        }
    }

    private fun stopBluetoothScan() {
        if (!hasBluetoothPermissions()) {
            _scanning.value = false
            scanStartTime = 0
            lastScanCallbackTime = 0
            return
        }

        // Always try to stop scan, even if we think it's not running
        // This handles cases where Android has a stale scan registered
        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.d(TAG, "stopScan() called")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping scan: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping scan: ${e.message}")
        } finally {
            _scanning.value = false
            scanStartTime = 0
            lastScanCallbackTime = 0
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            _connectionStatus.value = "Permission denied"
            updateNotification()
            return
        }

        // Clean up any existing connection first
        if (bluetoothGatt != null) {
            Log.w(TAG, "Existing GATT connection found, cleaning up before new connection")
            cleanupGatt()
        }

        isConnecting = true
        connectionAttemptStartTime = System.currentTimeMillis()
        lastGattActivityTime = System.currentTimeMillis()
        _connectionStatus.value = "Connecting..."
        // Cancel any pending scan attempts while connecting
        pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingScanRunnable = null

        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
            updateNotification()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException connecting to device: ${e.message}")
            isConnecting = false
            connectionAttemptStartTime = 0
            _connectionStatus.value = "Permission denied"
            updateNotification()

            // Retry scanning after delay
            mainHandler.postDelayed({
                startBluetoothScan()
            }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "Exception connecting to device: ${e.message}")
            isConnecting = false
            connectionAttemptStartTime = 0
            _connectionStatus.value = "Connection failed"
            updateNotification()

            // Retry scanning after delay
            mainHandler.postDelayed({
                startBluetoothScan()
            }, 2000)
        }
    }

    // Public API to read sessions list (queues the request)
    private fun readSessionsList() {
        enqueueBleRequest(BleRequest.ReadSessionsList)
    }

    // Execute sessions list read
    private fun executeReadSessionsList() {
        Log.i(TAG, "Executing read sessions list")

        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Cannot read sessions list - missing permission")
            completeBleRequest()
            return
        }

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        if (service == null) {
            Log.w(TAG, "Cannot read sessions list - service not found")
            completeBleRequest()
            return
        }

        val characteristic = service.getCharacteristic(SESSIONS_LIST_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.w(TAG, "Cannot read sessions list - characteristic not found")
            completeBleRequest()
            return
        }

        Log.d(TAG, "Reading sessions list characteristic... UUID=${characteristic.uuid}")
        val result = bluetoothGatt?.readCharacteristic(characteristic)
        Log.d(TAG, "readCharacteristic returned: $result")
        // Will be completed in onCharacteristicRead callback
    }

    private fun parseSessionsList(data: ByteArray) {
        Log.d(TAG, "parseSessionsList: received ${data.size} bytes")

        val recordSize = 20
        if (data.size % recordSize != 0) {
            Log.e(TAG, "Invalid sessions list size: ${data.size} bytes")
            return
        }

        val sessionCount = data.size / recordSize
        val sessions = mutableListOf<SessionRecord>()

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sessionCount) {
            val sessionId = buffer.int
            val rotationCount = buffer.int
            val activeTimeSeconds = buffer.int
            val startTimeUnix = buffer.int.toLong() and 0xFFFFFFFFL
            val endTimeUnix = buffer.int.toLong() and 0xFFFFFFFFL

            sessions.add(
                SessionRecord(
                    sessionId = sessionId,
                    rotationCount = rotationCount,
                    activeTimeSeconds = activeTimeSeconds,
                    startTimeUnix = startTimeUnix,
                    endTimeUnix = endTimeUnix
                )
            )
        }

        serviceScope.launch {
            sessionCacheManager.cacheSessions(sessions)

            val uploadedSessions = sessionCacheManager.getUploadedSessions()
            val uploadedIds = uploadedSessions.map { it.sessionId }.toSet()

            val discardedSessions = sessionCacheManager.getDiscardedSessions()
            val discardedIds = discardedSessions.map { it.sessionId }.toSet()

            // Send confirmations for uploaded sessions
            for (session in sessions) {
                if (session.sessionId in uploadedIds) {
                    Log.i(TAG, "Syncing confirmation for uploaded session ${session.sessionId}")
                    markSessionReported(session.sessionId)
                    sessionCacheManager.removeUploadedSession(session.sessionId)
                }
            }

            // Send confirmations for discarded sessions
            for (session in sessions) {
                if (session.sessionId in discardedIds) {
                    Log.i(TAG, "Syncing confirmation for discarded session ${session.sessionId}")
                    markSessionReported(session.sessionId)
                    sessionCacheManager.removeDiscardedSession(session.sessionId)
                }
            }

            val filteredSessions = sessions.filter {
                it.sessionId !in uploadedIds && it.sessionId !in discardedIds
            }
            _unreportedSessions.value = filteredSessions
            Log.i(TAG, "Loaded ${sessions.size} sessions, showing ${filteredSessions.size}")
        }
    }

    private fun parseUserSettings(data: ByteArray) {
        Log.d(TAG, "parseUserSettings: received ${data.size} bytes")

        if (data.size != 1) {
            Log.e(TAG, "Invalid user settings size: ${data.size} bytes, expected 1")
            return
        }

        val metric = data[0].toInt() != 0

        _userSettings.value = UserSettings(metric)
        Log.i(TAG, "Loaded user settings: metric=$metric")
    }

    // Public API to mark session reported (queues the request)
    fun markSessionReported(sessionId: Int) {
        enqueueBleRequest(BleRequest.MarkSessionReported(sessionId))
        // Also queue a sessions list read to refresh the list
        enqueueBleRequest(BleRequest.ReadSessionsList)
    }

    // Execute mark session reported
    private fun executeMarkSessionReported(sessionId: Int) {
        Log.i(TAG, "Executing mark session $sessionId as reported")

        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Cannot mark session reported - missing permission")
            completeBleRequest()
            return
        }

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(MARK_REPORTED_CHARACTERISTIC_UUID)
        if (characteristic != null) {
            val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(sessionId)
            val data = buffer.array()

            Log.d(TAG, "Marking session $sessionId as reported...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
            // Will be completed in onCharacteristicWrite callback
        } else {
            Log.w(TAG, "Mark reported characteristic not found")
            completeBleRequest()
        }
    }

    // Public API to send time sync (queues the request)
    private fun sendTimeSync() {
        enqueueBleRequest(BleRequest.SendTimeSync)
    }

    // Execute time sync write
    private fun executeSendTimeSync() {
        Log.i(TAG, "Executing time sync")

        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Cannot send time sync - missing permission")
            completeBleRequest()
            return
        }

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        if (service == null) {
            Log.w(TAG, "Cannot send time sync - service not found")
            completeBleRequest()
            return
        }

        val characteristic = service.getCharacteristic(TIME_SYNC_CHARACTERISTIC_UUID)
        if (characteristic != null) {
            val unixTimestamp = (System.currentTimeMillis() / 1000).toInt()

            // Get timezone offset in seconds
            val timeZone = java.util.TimeZone.getDefault()
            val timezoneOffsetSeconds = timeZone.getOffset(System.currentTimeMillis()) / 1000

            // Send 8 bytes: 4 bytes timestamp + 4 bytes timezone offset
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(unixTimestamp)
            buffer.putInt(timezoneOffsetSeconds)
            val data = buffer.array()

            Log.i(TAG, "Sending time sync: timestamp=$unixTimestamp, timezone_offset=${timezoneOffsetSeconds}s (${timezoneOffsetSeconds/3600.0f}h)")
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
            Log.i(TAG, "Time sync write result: $result")
            // Will be completed in onCharacteristicWrite callback
        } else {
            Log.w(TAG, "Time sync characteristic not found")
            completeBleRequest()
        }
    }

    // Called when permissions are granted (from MainActivity)
    fun onPermissionsGranted() {
        Log.i(TAG, "Permissions granted, performing aggressive cleanup and restart")

        // Cancel all pending operations
        pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingScanRunnable = null

        // Force cleanup of any stale state
        _isConnected.value = false
        isConnecting = false
        connectionAttemptStartTime = 0
        notificationsEnabled = false
        _scanning.value = false
        consecutiveFailures = 0
        retryDelayMs = minRetryDelayMs

        // Clean up GATT and scanner
        cleanupGatt()

        // Reinitialize Bluetooth adapter and scanner
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        // Verify we actually have permissions and Bluetooth is enabled
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "onPermissionsGranted called but permissions still missing!")
            _connectionStatus.value = "Missing permissions"
            updateNotification()
            return
        }

        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth is not enabled")
            _connectionStatus.value = "Bluetooth is OFF"
            updateNotification()
            return
        }

        // Start fresh scan
        _connectionStatus.value = "Starting scan..."
        updateNotification()

        mainHandler.postDelayed({
            Log.i(TAG, "Starting fresh scan after permissions granted")
            startBluetoothScan()
        }, 500)
    }

    // Called when service is re-bound (e.g., app returns to foreground)
    fun onAppResumed() {
        Log.i(TAG, "App resumed, checking connection state - connected=${_isConnected.value}, connecting=$isConnecting, scanning=${_scanning.value}")

        // Check if Bluetooth is still enabled
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "App resumed but Bluetooth is OFF")
            if (_isConnected.value || isConnecting || _scanning.value) {
                _isConnected.value = false
                isConnecting = false
                _scanning.value = false
                cleanupGatt()
                _connectionStatus.value = "Bluetooth is OFF"
                updateNotification()
            }
            return
        }

        // Check permissions
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "App resumed but permissions are missing")
            _connectionStatus.value = "Missing permissions"
            updateNotification()
            return
        }

        // If we should be scanning but aren't, restart the scan
        if (!_isConnected.value && !isConnecting && !_scanning.value) {
            Log.i(TAG, "Restarting scan after app resumed")
            pendingScanRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingScanRunnable = null
            resetBluetoothScanner()
            startBluetoothScan()
        } else {
            Log.i(TAG, "Skipping scan restart - already in desired state")
        }
    }

    // Update sessions list from external source (after filtering out uploaded)
    fun updateUnreportedSessions(sessions: List<SessionRecord>) {
        _unreportedSessions.value = sessions
    }

    // Public API to read user settings (queues the request)
    fun readUserSettings() {
        enqueueBleRequest(BleRequest.ReadUserSettings)
    }

    // Execute user settings read
    private fun executeReadUserSettings() {
        Log.i(TAG, "Executing read user settings")

        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Cannot read user settings - missing permission")
            completeBleRequest()
            return
        }

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(USER_SETTINGS_CHARACTERISTIC_UUID)
        if (characteristic != null) {
            Log.d(TAG, "Reading user settings characteristic...")
            bluetoothGatt?.readCharacteristic(characteristic)
            // Will be completed in onCharacteristicRead callback
        } else {
            Log.w(TAG, "User settings characteristic not found")
            completeBleRequest()
        }
    }

    // Public API to write user settings (queues the request)
    fun writeUserSettings(metric: Boolean) {
        enqueueBleRequest(BleRequest.WriteUserSettings(metric))
    }

    // Execute write user settings
    private fun executeWriteUserSettings(metric: Boolean) {
        Log.i(TAG, "Executing write user settings")

        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Cannot write user settings - missing permission")
            completeBleRequest()
            return
        }

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(USER_SETTINGS_CHARACTERISTIC_UUID)
        if (characteristic != null) {
            // Create 1-byte packet: metric only (no WiFi)
            val data = byteArrayOf(if (metric) 1.toByte() else 0.toByte())

            Log.d(TAG, "Writing user settings: metric=$metric")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
            // Will be completed in onCharacteristicWrite callback
        } else {
            Log.w(TAG, "User settings characteristic not found")
            completeBleRequest()
        }
    }

    // Set lifetime totals (hours and distance)
    fun setLifetimeTotals(hours: Float, distance: Float) {
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Cannot set lifetime totals - missing permission")
            return
        }

        if (bluetoothGatt == null) {
            Log.w(TAG, "Cannot set lifetime totals - not connected")
            return
        }

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(SET_LIFETIME_TOTALS_CHARACTERISTIC_UUID)
        if (characteristic != null) {
            // Create 8-byte packet: 4 bytes hours (float) + 4 bytes distance (float)
            val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putFloat(hours)
            buffer.putFloat(distance)
            val data = buffer.array()

            Log.d(TAG, "Setting lifetime totals: hours=$hours, distance=$distance")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                bluetoothGatt?.writeCharacteristic(characteristic)
            }
        } else {
            Log.w(TAG, "Set lifetime totals characteristic not found")
        }
    }

    // Track if last log read returned data (for adaptive polling)
    private var lastLogReadHadData = false

    // Track if LogsActivity is visible (for adaptive polling)
    private var logsActivityVisible = false

    // BLE request queue for serializing operations
    private sealed class BleRequest {
        object SendTimeSync : BleRequest()
        object ReadUserSettings : BleRequest()
        object ReadSessionsList : BleRequest()
        object StartLogPolling : BleRequest()
        data class MarkSessionReported(val sessionId: Int) : BleRequest()
        data class WriteUserSettings(val metric: Boolean) : BleRequest()
    }
    private val bleRequestQueue = mutableListOf<BleRequest>()
    private var processingBleRequest = false

    // Enqueue a BLE request and process if not already processing
    private fun enqueueBleRequest(request: BleRequest) {
        bleRequestQueue.add(request)
        Log.d(TAG, "Enqueued BLE request: ${request::class.simpleName}, queue size: ${bleRequestQueue.size}")
        processNextBleRequest()
    }

    // Process the next BLE request in the queue
    private fun processNextBleRequest() {
        if (processingBleRequest) {
            Log.v(TAG, "Already processing a BLE request, skipping")
            return
        }

        if (bleRequestQueue.isEmpty()) {
            Log.v(TAG, "BLE request queue is empty")
            return
        }

        if (!_isConnected.value || !notificationsEnabled) {
            Log.w(TAG, "Not connected or notifications disabled, clearing queue")
            bleRequestQueue.clear()
            processingBleRequest = false
            return
        }

        processingBleRequest = true
        val request = bleRequestQueue.removeAt(0)
        Log.i(TAG, "Processing BLE request: ${request::class.simpleName}, ${bleRequestQueue.size} remaining")

        when (request) {
            is BleRequest.SendTimeSync -> executeSendTimeSync()
            is BleRequest.ReadUserSettings -> executeReadUserSettings()
            is BleRequest.ReadSessionsList -> executeReadSessionsList()
            is BleRequest.StartLogPolling -> executeStartLogPolling()
            is BleRequest.MarkSessionReported -> executeMarkSessionReported(request.sessionId)
            is BleRequest.WriteUserSettings -> executeWriteUserSettings(request.metric)
        }
    }

    // Mark the current BLE request as complete and process the next one
    private fun completeBleRequest(delayMs: Long = 50) {
        Log.d(TAG, "BLE request complete, scheduling next after ${delayMs}ms")
        processingBleRequest = false
        mainHandler.postDelayed({
            processNextBleRequest()
        }, delayMs)
    }

    // Runnable for periodic log polling
    private val logPollingRunnable: Runnable = object : Runnable {
        override fun run() {
            if (_isConnected.value && notificationsEnabled) {
                readLogs()
                // Adaptive polling based on activity visibility and data availability:
                // - If last read had data, poll immediately (50ms) to drain the buffer
                // - If LogsActivity is visible, poll every 1 second
                // - If LogsActivity is not visible, poll every 60 seconds
                val delay = when {
                    lastLogReadHadData -> 50L
                    logsActivityVisible -> 1000L
                    else -> 60000L
                }
                Log.d(TAG, "Log poll cycle: hadData=$lastLogReadHadData, visible=$logsActivityVisible, nextDelay=${delay}ms")
                mainHandler.postDelayed(this, delay)
            } else {
                Log.w(TAG, "Log polling stopped: connected=${_isConnected.value}, notifications=$notificationsEnabled")
            }
        }
    }

    // Public API to start log polling (queues the request)
    private fun startLogPolling() {
        enqueueBleRequest(BleRequest.StartLogPolling)
    }

    // Execute start log polling
    private fun executeStartLogPolling() {
        Log.i(TAG, "========== STARTING LOG POLLING ==========")
        Log.i(TAG, "Connected: ${_isConnected.value}, Notifications: $notificationsEnabled")
        mainHandler.removeCallbacks(logPollingRunnable)
        // Start immediately, then continue every second
        mainHandler.post(logPollingRunnable)
        completeBleRequest()
    }

    // Stop periodic log polling
    private fun stopLogPolling() {
        Log.d(TAG, "Stopping log polling")
        mainHandler.removeCallbacks(logPollingRunnable)
    }

    // Read logs from device
    private fun readLogs() {
        if (!hasBluetoothConnectPermission()) {
            return
        }

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        if (service == null) {
            Log.w(TAG, "Cannot read logs - service not found")
            return
        }

        val characteristic = service.getCharacteristic(LOGS_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.w(TAG, "Cannot read logs - characteristic not found")
            return
        }

        try {
            val result = bluetoothGatt?.readCharacteristic(characteristic)
            Log.v(TAG, "Read logs characteristic: result=$result")
        } catch (e: Exception) {
            Log.e(TAG, "Exception reading logs: ${e.message}")
        }
    }

    // Parse and append logs data
    private fun parseLogs(data: ByteArray) {
        if (data.isEmpty()) {
            Log.d(TAG, "Received empty log data (no new logs)")
            lastLogReadHadData = false
            return
        }

        try {
            // Convert bytes to string and append to existing logs
            val newLogs = String(data, Charsets.UTF_8)
            val currentLogs = _logMessages.value + newLogs

            _logMessages.value = currentLogs
            lastLogReadHadData = true
            Log.d(TAG, "Received ${data.size} bytes of logs: ${newLogs.take(50)}... (total: ${currentLogs.length} chars, will poll again immediately)")
        } catch (e: Exception) {
            Log.e(TAG, "Exception parsing logs: ${e.message}")
            lastLogReadHadData = false
        }
    }

    // Get current accumulated logs
    fun getCurrentLogs(): String {
        return _logMessages.value
    }

    // Clear all accumulated logs
    fun clearLogs() {
        Log.i(TAG, "Clearing all accumulated logs")
        _logMessages.value = ""
    }

    // Manually trigger a log read and drain buffer (for manual refresh)
    fun triggerLogRead() {
        Log.i(TAG, "Manual log read triggered - will drain buffer")
        if (_isConnected.value && notificationsEnabled) {
            // Remove scheduled poll and force rapid polling to drain buffer
            mainHandler.removeCallbacks(logPollingRunnable)
            lastLogReadHadData = true  // Force rapid polling mode
            mainHandler.post(logPollingRunnable)
        } else {
            Log.w(TAG, "Cannot trigger log read - not connected or notifications disabled")
        }
    }

    // Called when LogsActivity becomes visible
    fun onLogsActivityVisible() {
        Log.i(TAG, "========== LOGS ACTIVITY VISIBLE ==========")
        Log.i(TAG, "Connected: ${_isConnected.value}, Notifications: $notificationsEnabled")
        logsActivityVisible = true

        // Trigger an immediate poll when activity becomes visible
        // Remove any pending polls and restart immediately
        if (_isConnected.value && notificationsEnabled) {
            Log.i(TAG, "Cancelling any pending polls and starting immediate log drain")
            mainHandler.removeCallbacks(logPollingRunnable)
            // Force immediate read to drain any accumulated logs
            lastLogReadHadData = true  // This will make it poll rapidly until buffer is drained
            mainHandler.post(logPollingRunnable)
            Log.i(TAG, "Log polling restarted in rapid mode")
        } else {
            Log.w(TAG, "Cannot start log polling - not connected or notifications disabled")
        }
    }

    // Called when LogsActivity becomes hidden
    fun onLogsActivityHidden() {
        Log.i(TAG, "LogsActivity is now hidden - decreasing log poll rate")
        logsActivityVisible = false
        // No need to restart polling, it will adjust on next iteration
    }
}
