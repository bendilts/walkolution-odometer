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
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.mypeople.walkolutionodometer.ui.theme.WalkolutionOdometerTheme
import kotlinx.coroutines.launch

class SetLifetimeTotalsActivity : ComponentActivity() {

    private var bleService: BleService? = null
    private var serviceBound = false

    private var isConnected = mutableStateOf(false)
    private var metric = mutableStateOf(false)

    companion object {
        private const val TAG = "SetLifetimeTotalsActivity"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleService.LocalBinder
            bleService = binder.getService()
            serviceBound = true
            Log.i(TAG, "BleService connected")

            // Collect state from service
            lifecycleScope.launch {
                bleService?.isConnected?.collect { connected ->
                    isConnected.value = connected
                }
            }
            lifecycleScope.launch {
                bleService?.odometerData?.collect { data ->
                    metric.value = data.metric
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            serviceBound = false
            Log.w(TAG, "BleService disconnected")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WalkolutionOdometerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Set Lifetime Totals") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    SetLifetimeTotalsScreen(
                        isConnected = isConnected.value,
                        metric = metric.value,
                        onSetTotals = { hours, distance -> setLifetimeTotals(hours, distance) },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
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

    private fun setLifetimeTotals(hours: Float, distance: Float) {
        bleService?.setLifetimeTotals(hours, distance)
    }
}

@Composable
fun SetLifetimeTotalsScreen(
    isConnected: Boolean,
    metric: Boolean,
    onSetTotals: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var hoursText by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf("") }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val distanceUnit = if (metric) "km" else "mi"

    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = { Text("Success") },
            text = { Text("Lifetime totals have been set successfully!") },
            confirmButton = {
                TextButton(onClick = { showSuccessDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text("Error") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Transfer Your Progress",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "If you're replacing your Pi Pico, enter your previous lifetime totals here to carry them forward.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        if (!isConnected) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Not connected to Walkolution device. Please ensure Bluetooth is enabled and the device is nearby.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        OutlinedTextField(
            value = hoursText,
            onValueChange = { hoursText = it },
            label = { Text("Total Hours") },
            placeholder = { Text("0") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            enabled = isConnected
        )

        OutlinedTextField(
            value = distanceText,
            onValueChange = { distanceText = it },
            label = { Text("Total Distance ($distanceUnit)") },
            placeholder = { Text("0.00") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            enabled = isConnected
        )

        Button(
            onClick = {
                val hours = hoursText.toFloatOrNull()
                val distance = distanceText.toFloatOrNull()

                when {
                    hours == null || hours < 0 -> {
                        errorMessage = "Please enter a valid number of hours (0 or greater)"
                        showErrorDialog = true
                    }
                    distance == null || distance < 0 -> {
                        errorMessage = "Please enter a valid distance (0 or greater)"
                        showErrorDialog = true
                    }
                    else -> {
                        onSetTotals(hours, distance)
                        showSuccessDialog = true
                        hoursText = ""
                        distanceText = ""
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = isConnected && hoursText.isNotBlank() && distanceText.isNotBlank()
        ) {
            Text(
                text = "Set Lifetime Totals",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Note: This will replace the current lifetime totals on your device. The current session will not be affected.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
