package com.mypeople.walkolutionodometer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mypeople.walkolutionodometer.ui.theme.WalkolutionOdometerTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    // Service binding
    private var bleService: BleService? = null
    private var serviceBound = false

    // State from service
    private var userSettings = mutableStateOf<UserSettings?>(null)
    private var wifiValidationStatus = mutableStateOf(WifiValidationStatus.IDLE)
    private var isConnected = mutableStateOf(false)
    private var isSaving = mutableStateOf(false)

    // WiFi scanning state
    private var availableNetworks = mutableStateOf<List<String>>(emptyList())

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            serviceBound = true
            Log.i(TAG, "BleService connected")

            // Collect state from service
            lifecycleScope.launch {
                bleService?.userSettings?.collect { settings ->
                    userSettings.value = settings
                }
            }
            lifecycleScope.launch {
                bleService?.wifiValidationStatus?.collect { status ->
                    wifiValidationStatus.value = status

                    // If we're saving and validation completed
                    if (isSaving.value && status != WifiValidationStatus.TESTING && status != WifiValidationStatus.IDLE) {
                        when (status) {
                            WifiValidationStatus.SUCCESS -> {
                                Toast.makeText(this@SettingsActivity, "Settings saved! WiFi connected successfully.", Toast.LENGTH_LONG).show()
                                // Delay slightly so user sees the final status before closing
                                kotlinx.coroutines.delay(500)
                                finish()
                            }
                            WifiValidationStatus.FAILED -> {
                                Toast.makeText(this@SettingsActivity, "WiFi connection failed. Please check credentials and try again.", Toast.LENGTH_LONG).show()
                                // Stay on settings screen, allow user to fix credentials
                                isSaving.value = false
                            }
                            else -> {}
                        }
                    }
                }
            }
            lifecycleScope.launch {
                bleService?.isConnected?.collect { connected ->
                    isConnected.value = connected
                }
            }

            // Read current settings
            bleService?.readUserSettings()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            serviceBound = false
            Log.i(TAG, "BleService disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WalkolutionOdometerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SettingsScreen(
                        userSettings = userSettings.value,
                        wifiValidationStatus = wifiValidationStatus.value,
                        isConnected = isConnected.value,
                        isSaving = isSaving.value,
                        availableNetworks = availableNetworks.value,
                        onRefreshNetworks = { scanWifiNetworks() },
                        onSaveSettings = { metric, ssid, password ->
                            bleService?.writeUserSettings(metric, ssid, password)
                            // If no WiFi credentials provided, close immediately
                            if (ssid.isBlank()) {
                                Toast.makeText(this@SettingsActivity, "Settings saved!", Toast.LENGTH_SHORT).show()
                                finish()
                            } else {
                                // Mark as saving and wait for WiFi validation
                                isSaving.value = true

                                // Poll for WiFi validation status
                                startPollingWifiStatus()
                            }
                        },
                        onBack = {
                            if (!isSaving.value) {
                                finish()
                            }
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        // Bind to BLE service
        val intent = Intent(this, BleService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Scan for WiFi networks
        scanWifiNetworks()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun startPollingWifiStatus() {
        // Poll every 500ms for up to 15 seconds (WiFi validation should complete within ~10s)
        var pollCount = 0
        val maxPolls = 30 // 15 seconds

        lifecycleScope.launch {
            while (pollCount < maxPolls && isSaving.value) {
                bleService?.readWifiValidationStatus()
                kotlinx.coroutines.delay(500)
                pollCount++
            }

            // If we timed out, show error
            if (isSaving.value) {
                isSaving.value = false
                Toast.makeText(this@SettingsActivity, "WiFi validation timed out", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun scanWifiNetworks() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        try {
            val scanResults = wifiManager.scanResults
            val ssids = scanResults
                .mapNotNull { it.SSID }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            availableNetworks.value = ssids
            Log.i(TAG, "Found ${ssids.size} WiFi networks")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for WiFi scan: ${e.message}")
            Toast.makeText(this, "Location permission required for WiFi scan", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning WiFi: ${e.message}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userSettings: UserSettings?,
    wifiValidationStatus: WifiValidationStatus,
    isConnected: Boolean,
    isSaving: Boolean,
    availableNetworks: List<String>,
    onRefreshNetworks: () -> Unit,
    onSaveSettings: (metric: Boolean, ssid: String, password: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var metric by remember { mutableStateOf(userSettings?.metric ?: false) }
    var ssid by remember { mutableStateOf(userSettings?.ssid ?: "") }
    var wifiPassword by remember { mutableStateOf(userSettings?.wifiPassword ?: "") }
    var hasChanges by remember { mutableStateOf(false) }

    // Update local state when userSettings changes
    LaunchedEffect(userSettings) {
        userSettings?.let {
            metric = it.metric
            ssid = it.ssid
            wifiPassword = it.wifiPassword
            hasChanges = false
        }
    }

    // Track changes
    LaunchedEffect(metric, ssid, wifiPassword, userSettings) {
        hasChanges = userSettings?.let {
            metric != it.metric || ssid != it.ssid || wifiPassword != it.wifiPassword
        } ?: false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Top bar with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                enabled = !isSaving
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Text(
                text = if (isSaving) "Saving Settings..." else "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        // Connection status
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isConnected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = if (isConnected) "Connected to Pico" else "Disconnected - Connect to view/change settings",
                modifier = Modifier.padding(16.dp),
                color = if (isConnected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer
            )
        }

        // Settings form
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Units",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Use Metric (km/h)",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = metric,
                        onCheckedChange = { metric = it },
                        enabled = isConnected && !isSaving
                    )
                }

                Text(
                    text = if (metric) "Distances will be shown in kilometers" else "Distances will be shown in miles",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // WiFi Settings
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "WiFi Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Configure WiFi for NTP time sync",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // WiFi SSID Dropdown
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = {
                        if (isConnected && !isSaving) {
                            expanded = !expanded
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it },
                        label = { Text("WiFi SSID") },
                        enabled = isConnected && !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                if (availableNetworks.isNotEmpty()) {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                }
                                IconButton(
                                    onClick = { onRefreshNetworks() },
                                    enabled = isConnected && !isSaving
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                                        contentDescription = "Refresh networks"
                                    )
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Next
                        )
                    )

                    if (availableNetworks.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            availableNetworks.forEach { network ->
                                DropdownMenuItem(
                                    text = { Text(network) },
                                    onClick = {
                                        ssid = network
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = wifiPassword,
                    onValueChange = { wifiPassword = it },
                    label = { Text("WiFi Password") },
                    enabled = isConnected && !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )

                // WiFi validation status
                if (wifiValidationStatus != WifiValidationStatus.IDLE) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (wifiValidationStatus) {
                                WifiValidationStatus.TESTING -> MaterialTheme.colorScheme.tertiaryContainer
                                WifiValidationStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                                WifiValidationStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (wifiValidationStatus) {
                                WifiValidationStatus.TESTING -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "Testing WiFi connection...",
                                        modifier = Modifier.padding(start = 12.dp),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }
                                WifiValidationStatus.SUCCESS -> {
                                    Text(
                                        text = "✓ WiFi connected and NTP sync successful!",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                WifiValidationStatus.FAILED -> {
                                    Text(
                                        text = "✗ WiFi connection failed. Check credentials.",
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }

        // Save button
        Button(
            onClick = { onSaveSettings(metric, ssid, wifiPassword) },
            enabled = isConnected && hasChanges && !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Saving...",
                    fontSize = 16.sp
                )
            } else {
                Text(
                    text = if (hasChanges) "Save Settings" else "No Changes",
                    fontSize = 16.sp
                )
            }
        }

        if (!isConnected) {
            Text(
                text = "Connect to the Pico to change settings",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // Help text
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "About WiFi Settings",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "The Pico uses WiFi to sync time from NTP servers. This ensures accurate timestamps for your walking sessions. Leave WiFi settings blank if you don't want to use WiFi (time will be synced from your phone via Bluetooth).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
