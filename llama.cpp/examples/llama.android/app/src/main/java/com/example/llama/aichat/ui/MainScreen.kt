package com.example.llama.aichat.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llama.aichat.ai.K2InferenceManager
import com.example.llama.aichat.data.NotificationRecord
import com.example.llama.aichat.data.NotificationRule
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val importantNotifications by viewModel.importantNotifications.collectAsState(initial = emptyList())
    val unimportantNotifications by viewModel.unimportantNotifications.collectAsState(initial = emptyList())
    val rules by viewModel.rules.collectAsState(initial = emptyList())
    val isAccessEnabled by viewModel.isNotificationAccessEnabled.collectAsState()
    val isPermissionGranted by viewModel.isNotificationPermissionGranted.collectAsState()
    val isEnabled by viewModel.isEnabled.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val importantContext by viewModel.importantContext.collectAsState()
    val isAiAlertSoundEnabled by viewModel.isAiAlertSoundEnabled.collectAsState()
    val selectedNotification by viewModel.selectedNotification.collectAsState()

    var showAddRuleDialog by remember { mutableStateOf(false) }
    var ruleToEdit by remember { mutableStateOf<NotificationRule?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NOTIFICATION ANALYZER", fontWeight = FontWeight.Bold) },
                actions = {
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { viewModel.toggleEnabled() },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                StatusSection(
                    isAccessEnabled = isAccessEnabled,
                    isPermissionGranted = isPermissionGranted,
                    modelState = modelState,
                    onEnableClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onRequestPermissionClick = onRequestNotificationPermission,
                    onRetryClick = {
                        viewModel.retryModelLoad()
                    },
                    onPickFileClick = {
                        filePickerLauncher.launch("*/*")
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))

                ImportantContextSection(
                    contextText = importantContext,
                    onContextChange = { viewModel.updateImportantContext(it) }
                )
                Spacer(modifier = Modifier.height(16.dp))

                RulesSection(
                    rules = rules,
                    onAddClick = { showAddRuleDialog = true },
                    onToggle = { viewModel.toggleRule(it) },
                    onEdit = { ruleToEdit = it },
                    onDelete = { viewModel.deleteRule(it) }
                )
                Spacer(modifier = Modifier.height(24.dp))

                Text("TODAY", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (importantNotifications.isEmpty() && unimportantNotifications.isEmpty()) {
                item {
                    Text(
                        "No notifications analyzed yet.",
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                if (importantNotifications.isNotEmpty()) {
                    item {
                        Text(
                            "IMPORTANT",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        )
                    }
                    items(importantNotifications) { record ->
                        NotificationItem(
                            record = record,
                            isImportant = true,
                            onClick = { viewModel.selectNotification(record) }
                        )
                    }
                }

                if (unimportantNotifications.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "NOT IMPORTANT",
                            color = Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        )
                    }
                    items(unimportantNotifications) { record ->
                        NotificationItem(
                            record = record,
                            isImportant = false,
                            onClick = { viewModel.selectNotification(record) }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("AI ALERT SOUND", fontWeight = FontWeight.Bold)
                            Text(
                                if (isAiAlertSoundEnabled) "Enabled" else "Disabled",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = isAiAlertSoundEnabled,
                            onCheckedChange = { viewModel.toggleAiAlertSound() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { viewModel.clearHistory() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear History")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        "Your notifications are analyzed locally on this device using the K2 AI model. Notification content is not sent to a cloud AI service.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showAddRuleDialog) {
        AddRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onConfirm = { text ->
                viewModel.addRule(text)
                showAddRuleDialog = false
            }
        )
    }

    ruleToEdit?.let { rule ->
        EditRuleDialog(
            rule = rule,
            onDismiss = { ruleToEdit = null },
            onConfirm = { newText ->
                viewModel.updateRule(rule, newText)
                ruleToEdit = null
            }
        )
    }

    selectedNotification?.let { record ->
        NotificationDetailDialog(
            record = record,
            onDismiss = { viewModel.selectNotification(null) }
        )
    }
}

@Composable
fun StatusSection(
    isAccessEnabled: Boolean,
    isPermissionGranted: Boolean,
    modelState: K2InferenceManager.State,
    onEnableClick: () -> Unit,
    onRequestPermissionClick: () -> Unit,
    onRetryClick: () -> Unit,
    onPickFileClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Notification Access: ", fontWeight = FontWeight.Medium)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (isAccessEnabled) Color(0xFF4CAF50) else Color.Red, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (isAccessEnabled) "Connected" else "Not connected",
                    color = if (isAccessEnabled) Color(0xFF4CAF50) else Color.Red,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!isAccessEnabled) {
                Button(onClick = onEnableClick, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Enable Notification Access")
                }
            }

            if (!isPermissionGranted) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alert Notifications: ", fontWeight = FontWeight.Medium)
                    Text("Permission required", color = Color.Red, fontSize = 12.sp)
                }
                Button(
                    onClick = onRequestPermissionClick,
                    modifier = Modifier.padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Grant Notification Permission")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("AI Model:", fontWeight = FontWeight.Medium)
                    Text("K2 Horizon 0.9B Q4", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Local / On-device", fontSize = 12.sp, color = Color.Gray)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (modelState == K2InferenceManager.State.ERROR) {
                        Button(
                            onClick = onRetryClick,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("RETRY")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onPickFileClick,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("IMPORT MODEL")
                    }
                }
            }
            if (modelState == K2InferenceManager.State.ERROR) {
                Text(
                    "Error: The model file might be corrupted. Please ensure you have the full 600MB .gguf file and try importing again.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            val (stateText, stateColor) = when (modelState) {
                K2InferenceManager.State.READY -> "Ready (Model Loaded)" to Color(0xFF4CAF50)
                K2InferenceManager.State.LOADING -> "Loading / Copying..." to Color(0xFFFFA000)
                K2InferenceManager.State.UNAVAILABLE -> "Unavailable" to Color.Red
                K2InferenceManager.State.ERROR -> "Error" to Color.Red
                K2InferenceManager.State.UNINITIALIZED -> "Uninitialized" to Color.Gray
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(stateColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stateText, fontSize = 12.sp, color = stateColor, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ImportantContextSection(contextText: String, onContextChange: (String) -> Unit) {
    Column {
        Text("IMPORTANT CONTEXT", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = contextText,
            onValueChange = onContextChange,
            placeholder = { Text("e.g. I am currently waiting for the result of a job interview. Anything related to the recruiter or interview is important.") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
    }
}

@Composable
fun RulesSection(
    rules: List<NotificationRule>,
    onAddClick: () -> Unit,
    onToggle: (NotificationRule) -> Unit,
    onEdit: (NotificationRule) -> Unit,
    onDelete: (NotificationRule) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("RULES", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            TextButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("+ Add Rule")
            }
        }
        if (rules.isEmpty()) {
            Text("No rules added yet. Tap '+ Add Rule' to define custom rules.", fontSize = 13.sp, color = Color.Gray)
        } else {
            rules.forEach { rule ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = rule.enabled, onCheckedChange = { onToggle(rule) })
                    Text(
                        rule.text,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onEdit(rule) },
                        fontSize = 14.sp
                    )
                    IconButton(onClick = { onEdit(rule) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Rule", tint = Color.Gray, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onDelete(rule) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Rule", tint = Color.Gray, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationItem(
    record: NotificationRecord,
    isImportant: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = record.summary,
            fontWeight = if (isImportant) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
            val appInfo = if (!record.sender.isNullOrBlank() && record.sender != record.appName) {
                "${record.appName} · ${record.sender}"
            } else {
                record.appName
            }
            Text(appInfo, fontSize = 12.sp, color = Color.Gray)
            Spacer(modifier = Modifier.weight(1f))
            Text(formatTime(record.timestamp), fontSize = 12.sp, color = Color.Gray)
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp), thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))
    }
}

@Composable
fun NotificationDetailDialog(
    record: NotificationRecord,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = record.summary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "${record.appName}${if (!record.sender.isNullOrBlank()) " · " + record.sender else ""}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!record.title.isNullOrBlank() && record.title != record.summary) {
                    Text("Original Title:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.Gray)
                    Text(record.title, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                }

                if (!record.text.isNullOrBlank()) {
                    Text("Original Message:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.Gray)
                    Text(record.text, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                Text("AI Decision Reason:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.Gray)
                Text(record.reason, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Category: ${record.aiCategory}", fontSize = 12.sp, color = Color.Gray)
                    Text(formatTime(record.timestamp), fontSize = 12.sp, color = Color.Gray)
                }

                if (record.important && record.alert) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("AI Chime Alert Triggered: YES", fontSize = 12.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun AddRuleDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Rule") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("e.g. Messages from my parents are important.") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun EditRuleDialog(rule: NotificationRule, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(rule.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Rule") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

