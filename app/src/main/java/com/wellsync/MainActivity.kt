package com.wellsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.wellsync.ui.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.jeziellago.compose.markdowntext.MarkdownText
import java.text.SimpleDateFormat
import java.util.*

import androidx.activity.enableEdgeToEdge

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WellSyncScreen()
                }
            }
        }
    }
}

@Composable
fun WellSyncScreen(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
        onResult = {
            viewModel.checkHealthConnectAndPermissions()
        }
    )

    // Automatically request permissions on first launch if they are missing
    LaunchedEffect(uiState.isHealthConnectAvailable, uiState.hasPermissions) {
        if (uiState.isHealthConnectAvailable && !uiState.hasPermissions) {
            permissionLauncher.launch(viewModel.requiredPermissions)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "WellSync",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Health Connect: ${if (uiState.isHealthConnectAvailable) "Available" else "Not Available"}",
            style = MaterialTheme.typography.bodyMedium
        )

        val locale = LocalLocale.current.platformLocale
        val dateStr = if (uiState.lastSyncTime > 0) {
            SimpleDateFormat("yyyy/MM/dd HH:mm", locale).format(Date(uiState.lastSyncTime))
        } else {
            "Never"
        }
        Text(
            text = "Last Synced: $dateStr",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (uiState.isHealthConnectAvailable && !uiState.hasPermissions) {
            Button(
                onClick = { permissionLauncher.launch(viewModel.requiredPermissions) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Health Connect Permissions")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Please allow permissions to analyze your data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Button(
            onClick = { viewModel.refreshAndAnalyze() },
            enabled = !uiState.isLoading && uiState.isHealthConnectAvailable && uiState.hasPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Refresh & Analyze")
            }
        }

        uiState.error?.let {
            Text(
                text = "Error: $it",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "AI Insights",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (uiState.analysis != null) {
                    MarkdownText(
                        markdown = uiState.analysis!!,
                        style = MaterialTheme.typography.bodyLarge,
                        isTextSelectable = true
                    )
                } else if (uiState.error != null) {
                    Text(
                        text = "Analysis failed. Please resolve the error and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "No analysis yet. Click refresh to sync your data.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
