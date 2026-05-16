package com.wellsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WellSyncScreen(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    var showPromptMenu by remember { mutableStateOf(false) }
    var showCustomPromptDialog by remember { mutableStateOf(false) }
    var customPromptInput by remember { mutableStateOf("") }

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

    if (showCustomPromptDialog) {
        AlertDialog(
            onDismissRequest = { showCustomPromptDialog = false },
            title = { Text("Custom Prompt") },
            text = {
                OutlinedTextField(
                    value = customPromptInput,
                    onValueChange = { customPromptInput = it },
                    label = { Text("Enter your prompt instructions") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.savePromptSettings(3, customPromptInput)
                    showCustomPromptDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomPromptDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WellSync") },
                actions = {
                    Box {
                        IconButton(onClick = { showPromptMenu = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "AI Settings")
                        }
                        DropdownMenu(
                            expanded = showPromptMenu,
                            onDismissRequest = { showPromptMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Default") },
                                onClick = {
                                    viewModel.savePromptSettings(0, uiState.customPrompt)
                                    showPromptMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sweet") },
                                onClick = {
                                    viewModel.savePromptSettings(1, uiState.customPrompt)
                                    showPromptMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Spicy") },
                                onClick = {
                                    viewModel.savePromptSettings(2, uiState.customPrompt)
                                    showPromptMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Custom") },
                                onClick = {
                                    customPromptInput = uiState.customPrompt ?: ""
                                    showCustomPromptDialog = true
                                    showPromptMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

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
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
