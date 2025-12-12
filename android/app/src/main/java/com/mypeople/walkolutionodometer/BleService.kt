package com.mypeople.walkolutionodometer

import android.Manifest
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
    val metric: Boolean,
    val ssid: String,
    val wifiPassword: String
)

enum class WifiValidationStatus(val value: Int) {
    IDLE(0),
    TESTING(1),
    SUCCESS(2),
    FAILED(3);

    companion object {
        fun fromValue(value: Int) = values().find { it.value == value } ?: IDLE
    }
}

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

    private val _wifiValidationStatus = MutableStateFlow(WifiValidationStatus.IDLE)
    val wifiValidationStatus: StateFlow<WifiValidationStatus> = _wifiValidationStatus.asStateFlow()

    // Notification
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

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

        // UUIDs matching the Pico
        val ODOMETER_SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        val ODOMETER_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        val SESSIONS_LIST_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2")
        val MARK_REPORTED_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef3")
        val TIME_SYNC_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef4")
        val USER_SETTINGS_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef5")
        val WIFI_VALIDATION_STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef6")
        val SET_LIFETIME_TOTALS_CHARACTERISTIC_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef7")
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
        sessionCacheManager = SessionCacheManager(this)

        createNotificationChannel()

        // Register Bluetooth state change receiver
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)
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

                    mainHandler.postDelayed({
                        sendTimeSync()
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
                        mainHandler.postDelayed({
                            readUserSettings()
                            // Sessions list will be read after user settings completes
                        }, 500)
                    }
                    MARK_REPORTED_CHARACTERISTIC_UUID -> {
                        Log.i(TAG, "Session marked as reported successfully")
                    }
                }
            } else {
                Log.w(TAG, "Characteristic write failed: ${characteristic.uuid}, status=$status")
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
                    }
                    USER_SETTINGS_CHARACTERISTIC_UUID -> {
                        parseUserSettings(value)
                        // Chain sessions list read after user settings completes
                        mainHandler.postDelayed({
                            readSessionsList()
                        }, 50)
                    }
                    WIFI_VALIDATION_STATUS_CHARACTERISTIC_UUID -> parseWifiValidationStatus(value)
                }
            } else {
                Log.w(TAG, "Characteristic read failed: ${characteristic.uuid}, status=$status")
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
                        }
                        USER_SETTINGS_CHARACTERISTIC_UUID -> {
                            parseUserSettings(value)
                            // Chain sessions list read after user settings completes
                            mainHandler.postDelayed({
                                readSessionsList()
                            }, 50)
                        }
                        WIFI_VALIDATION_STATUS_CHARACTERISTIC_UUID -> parseWifiValidationStatus(value)
                    }
                } ?: Log.w(TAG, "Characteristic value is null")
            } else {
                Log.w(TAG, "Characteristic read failed: ${characteristic.uuid}, status=$status")
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

    private fun readSessionsList() {
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "readSessionsList: No Bluetooth connect permission")
            return
        }

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        if (service == null) {
            Log.w(TAG, "readSessionsList: Service not found")
            return
        }

        val characteristic = service.getCharacteristic(SESSIONS_LIST_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            Log.w(TAG, "readSessionsList: Characteristic not found")
            return
        }

        Log.d(TAG, "Reading sessions list characteristic... UUID=${characteristic.uuid}")
        val result = bluetoothGatt?.readCharacteristic(characteristic)
        Log.d(TAG, "readCharacteristic returned: $result")
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

        if (data.size != 193) {
            Log.e(TAG, "Invalid user settings size: ${data.size} bytes, expected 193")
            return
        }

        val metric = data[0].toInt() != 0

        // Parse SSID (64 bytes)
        val ssidBytes = ByteArray(64)
        System.arraycopy(data, 1, ssidBytes, 0, 64)
        val ssid = String(ssidBytes, Charsets.UTF_8).trim('\u0000')

        // Parse WiFi password (128 bytes)
        val passwordBytes = ByteArray(128)
        System.arraycopy(data, 65, passwordBytes, 0, 128)
        val wifiPassword = String(passwordBytes, Charsets.UTF_8).trim('\u0000')

        _userSettings.value = UserSettings(metric, ssid, wifiPassword)
        Log.i(TAG, "Loaded user settings: metric=$metric, ssid=$ssid")
    }

    private fun parseWifiValidationStatus(data: ByteArray) {
        if (data.size < 1) {
            Log.e(TAG, "Invalid WiFi validation status size: ${data.size} bytes")
            return
        }

        val status = WifiValidationStatus.fromValue(data[0].toInt())
        _wifiValidationStatus.value = status
        Log.i(TAG, "WiFi validation status: $status")
    }

    fun markSessionReported(sessionId: Int) {
        if (!hasBluetoothConnectPermission()) return

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

            mainHandler.postDelayed({
                readSessionsList()
            }, 500)
        }
    }

    private fun sendTimeSync() {
        if (!hasBluetoothConnectPermission()) return

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(TIME_SYNC_CHARACTERISTIC_UUID)
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

            Log.d(TAG, "Sending time sync: timestamp=$unixTimestamp, timezone_offset=${timezoneOffsetSeconds}s (${timezoneOffsetSeconds/3600.0f}h)")
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
            mainHandler.postDelayed({
                readSessionsList()
            }, 500)
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

    // Read user settings from Pico
    fun readUserSettings() {
        if (!hasBluetoothConnectPermission()) return

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(USER_SETTINGS_CHARACTERISTIC_UUID)
        if (characteristic != null) {
            Log.d(TAG, "Reading user settings characteristic...")
            bluetoothGatt?.readCharacteristic(characteristic)
        }
    }

    // Write user settings to Pico
    fun writeUserSettings(metric: Boolean, ssid: String, wifiPassword: String) {
        if (!hasBluetoothConnectPermission()) return

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(USER_SETTINGS_CHARACTERISTIC_UUID)
        if (characteristic != null) {
            // Create 193-byte packet: 1 byte metric + 64 bytes SSID + 128 bytes password
            val buffer = ByteBuffer.allocate(193).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(if (metric) 1.toByte() else 0.toByte())

            // SSID (64 bytes, null-padded)
            val ssidBytes = ByteArray(64)
            val ssidData = ssid.toByteArray(Charsets.UTF_8).take(63).toByteArray()
            System.arraycopy(ssidData, 0, ssidBytes, 0, ssidData.size)
            buffer.put(ssidBytes)

            // WiFi password (128 bytes, null-padded)
            val passwordBytes = ByteArray(128)
            val passwordData = wifiPassword.toByteArray(Charsets.UTF_8).take(127).toByteArray()
            System.arraycopy(passwordData, 0, passwordBytes, 0, passwordData.size)
            buffer.put(passwordBytes)

            val data = buffer.array()

            Log.d(TAG, "Writing user settings: metric=$metric, ssid=$ssid")
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

            // Only mark validation status as testing if WiFi credentials were provided
            // If SSID is blank, set to IDLE instead (no validation needed)
            if (ssid.isNotBlank()) {
                _wifiValidationStatus.value = WifiValidationStatus.TESTING
            } else {
                _wifiValidationStatus.value = WifiValidationStatus.IDLE
            }
        }
    }

    // Read WiFi validation status
    fun readWifiValidationStatus() {
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Cannot read WiFi validation status - missing permission")
            return
        }

        if (bluetoothGatt == null) {
            Log.w(TAG, "Cannot read WiFi validation status - not connected")
            return
        }

        val service = bluetoothGatt?.getService(ODOMETER_SERVICE_UUID)
        if (service == null) {
            Log.w(TAG, "Cannot read WiFi validation status - service not found")
            return
        }

        val characteristic = service.getCharacteristic(WIFI_VALIDATION_STATUS_CHARACTERISTIC_UUID)
        if (characteristic != null) {
            Log.d(TAG, "Reading WiFi validation status characteristic...")
            try {
                val success = bluetoothGatt?.readCharacteristic(characteristic)
                Log.d(TAG, "Read WiFi validation status request: ${if (success == true) "success" else "failed"}")
            } catch (e: Exception) {
                Log.e(TAG, "Exception reading WiFi validation status: ${e.message}")
            }
        } else {
            Log.w(TAG, "WiFi validation status characteristic not found")
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
}
