package com.mypeople.walkolutionodometer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    private var isConnected = mutableStateOf(false)

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
                        isConnected = isConnected.value,
                        onSaveSettings = { metric ->
                            bleService?.writeUserSettings(metric)
                            Toast.makeText(this@SettingsActivity, "Settings saved!", Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        onBack = { finish() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        // Bind to BLE service
        val intent = Intent(this, BleService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userSettings: UserSettings?,
    isConnected: Boolean,
    onSaveSettings: (metric: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var metric by remember { mutableStateOf(userSettings?.metric ?: false) }
    var hasChanges by remember { mutableStateOf(false) }

    // Update local state when userSettings changes
    LaunchedEffect(userSettings) {
        userSettings?.let {
            metric = it.metric
            hasChanges = false
        }
    }

    // Track changes
    LaunchedEffect(metric, userSettings) {
        hasChanges = userSettings?.let {
            metric != it.metric
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
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Text(
                text = "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
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
                        enabled = isConnected
                    )
                }

                Text(
                    text = if (metric) "Distances will be shown in kilometers" else "Distances will be shown in miles",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Save button
        Button(
            onClick = { onSaveSettings(metric) },
            enabled = isConnected && hasChanges,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = if (hasChanges) "Save Settings" else "No Changes",
                fontSize = 16.sp
            )
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
    }
}
