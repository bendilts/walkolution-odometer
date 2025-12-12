package com.mypeople.walkolutionodometer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
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

class StravaSettingsActivity : ComponentActivity() {

    private lateinit var stravaRepository: StravaRepository
    private var stravaAuthenticated = mutableStateOf(false)
    private var stravaAthleteName = mutableStateOf<String?>(null)
    private var stravaStatus = mutableStateOf<String?>(null)

    companion object {
        private const val TAG = "StravaSettingsActivity"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Strava repository
        stravaRepository = StravaRepository(this)

        // Check if returning from Strava OAuth
        handleStravaCallback(intent)

        // Check existing auth status
        lifecycleScope.launch {
            stravaAuthenticated.value = stravaRepository.isAuthenticated()
            stravaAthleteName.value = stravaRepository.getAthleteName()
        }

        setContent {
            WalkolutionOdometerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Strava Settings") },
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
                    StravaSettingsScreen(
                        stravaAuthenticated = stravaAuthenticated.value,
                        stravaAthleteName = stravaAthleteName.value,
                        stravaStatus = stravaStatus.value,
                        onConnectStrava = { connectToStrava() },
                        onDisconnectStrava = { disconnectStrava() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStravaCallback(intent)
    }

    private fun handleStravaCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "walkolution" && uri.host == "walkolution") {
            val code = uri.getQueryParameter("code")
            val error = uri.getQueryParameter("error")

            if (code != null) {
                lifecycleScope.launch {
                    stravaStatus.value = "Connecting to Strava..."
                    val result = stravaRepository.exchangeCodeForTokens(code)
                    if (result.isSuccess) {
                        stravaAuthenticated.value = true
                        stravaAthleteName.value = stravaRepository.getAthleteName()
                        stravaStatus.value = "Successfully connected to Strava!"
                    } else {
                        stravaStatus.value = "Failed: ${result.exceptionOrNull()?.message}"
                    }
                }
            } else if (error != null) {
                stravaStatus.value = "Authorization denied"
            }
        }
    }

    private fun connectToStrava() {
        val authUrl = stravaRepository.getAuthorizationUrl()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
        startActivity(intent)
    }

    private fun disconnectStrava() {
        lifecycleScope.launch {
            stravaRepository.logout()
            stravaAuthenticated.value = false
            stravaAthleteName.value = null
            stravaStatus.value = "Disconnected from Strava"
        }
    }
}

@Composable
fun StravaSettingsScreen(
    stravaAuthenticated: Boolean,
    stravaAthleteName: String?,
    stravaStatus: String?,
    onConnectStrava: () -> Unit,
    onDisconnectStrava: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Strava Integration",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "Connect your Strava account to automatically upload your walking sessions.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        if (stravaAuthenticated) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "✓ Connected",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Account: ${stravaAthleteName ?: "Unknown"}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            OutlinedButton(
                onClick = onDisconnectStrava,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Disconnect from Strava")
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Not Connected",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Connect to enable session uploads",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = onConnectStrava,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect to Strava")
            }
        }

        stravaStatus?.let { status ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = status,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}
