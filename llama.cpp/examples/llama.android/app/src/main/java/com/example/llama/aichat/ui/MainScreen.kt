package com.example.llama.aichat.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val importantNotifications by viewModel.importantNotifications.collectAsState()
    val unimportantNotifications by viewModel.unimportantNotifications.collectAsState()
    val allNotifications by viewModel.allNotifications.collectAsState()
    val retentionPeriod by viewModel.retentionPeriod.collectAsState()
    val rules by viewModel.rules.collectAsState(initial = emptyList())
    val isAccessEnabled by viewModel.isNotificationAccessEnabled.collectAsState()
    val isPermissionGranted by viewModel.isNotificationPermissionGranted.collectAsState()
    val isEnabled by viewModel.isEnabled.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val isAiAlertSoundEnabled by viewModel.isAiAlertSoundEnabled.collectAsState()
    val selectedNotification by viewModel.selectedNotification.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeFilter by viewModel.activeFilter.collectAsState()

    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var initialRuleSuggestion by remember { mutableStateOf("") }
    var ruleToEdit by remember { mutableStateOf<NotificationRule?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { viewModel.importModel(it) }
    }

    // Filter notifications based on activeFilter and searchQuery
    val filteredNotifications = remember(allNotifications, importantNotifications, unimportantNotifications, activeFilter, searchQuery) {
        val baseList = when (activeFilter) {
            "IMPORTANT" -> importantNotifications
            "NOT_IMPORTANT" -> unimportantNotifications
            else -> allNotifications
        }
        if (searchQuery.isBlank()) {
            baseList
        } else {
            val q = searchQuery.trim().lowercase()
            baseList.filter {
                (it.appName.lowercase().contains(q)) ||
                (it.sender?.lowercase()?.contains(q) == true) ||
                (it.title?.lowercase()?.contains(q) == true) ||
                (it.summary.lowercase().contains(q)) ||
                (it.text?.lowercase()?.contains(q) == true) ||
                (it.aiCategory.lowercase().contains(q))
            }
        }
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

                RulesSection(
                    rules = rules,
                    onAddClick = {
                        initialRuleSuggestion = ""
                        showAddRuleDialog = true
                    },
                    onToggle = { viewModel.toggleRule(it) },
                    onEdit = { ruleToEdit = it },
                    onDelete = { viewModel.deleteRule(it) }
                )
                Spacer(modifier = Modifier.height(16.dp))

                RetentionSection(
                    selectedPeriod = retentionPeriod,
                    onClick = { showRetentionDialog = true }
                )
                Spacer(modifier = Modifier.height(20.dp))

                // Search & Filter Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("NOTIFICATIONS (${retentionPeriod.label})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "${filteredNotifications.size} item${if (filteredNotifications.size != 1) "s" else ""}",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search sender, text, app...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear search", tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = activeFilter == "ALL",
                        onClick = { viewModel.setActiveFilter("ALL") },
                        label = { Text("All (${allNotifications.size})") }
                    )
                    FilterChip(
                        selected = activeFilter == "IMPORTANT",
                        onClick = { viewModel.setActiveFilter("IMPORTANT") },
                        label = { Text("⚡ Important (${importantNotifications.size})") }
                    )
                    FilterChip(
                        selected = activeFilter == "NOT_IMPORTANT",
                        onClick = { viewModel.setActiveFilter("NOT_IMPORTANT") },
                        label = { Text("Other (${unimportantNotifications.size})") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            if (filteredNotifications.isEmpty()) {
                item {
                    Text(
                        if (searchQuery.isNotBlank()) "No notifications match '$searchQuery'" else "No notifications analyzed yet.",
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(filteredNotifications, key = { it.id }) { record ->
                    NotificationItem(
                        record = record,
                        isImportant = record.important,
                        onClick = { viewModel.selectNotification(record) }
                    )
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
                    Text("Clear All History")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        "Your notifications are analyzed locally on this device using the K2 AI model. Notification content is never sent to a cloud AI service.",
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
            initialText = initialRuleSuggestion,
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

    if (showRetentionDialog) {
        RetentionDialog(
            currentPeriod = retentionPeriod,
            onDismiss = { showRetentionDialog = false },
            onSelect = { period ->
                viewModel.setRetentionPeriod(period)
                showRetentionDialog = false
            }
        )
    }

    selectedNotification?.let { record ->
        NotificationDetailDialog(
            record = record,
            onDismiss = { viewModel.selectNotification(null) },
            onDelete = { viewModel.deleteNotification(record) },
            onAddRuleForSender = { sender ->
                initialRuleSuggestion = "Urgent messages from $sender are important."
                showAddRuleDialog = true
            }
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
                K2InferenceManager.State.INFERENCE -> "Analyzing..." to Color(0xFF00E5FF)
                K2InferenceManager.State.LOADING -> "Loading / Copying..." to Color(0xFFFFA000)
                K2InferenceManager.State.UNLOADING -> "Unloading..." to Color(0xFFFFA000)
                K2InferenceManager.State.UNAVAILABLE -> "Unavailable" to Color.Red
                K2InferenceManager.State.ERROR -> "Error" to Color.Red
                K2InferenceManager.State.UNINITIALIZED -> "Standby (Unloaded)" to Color.Gray
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isImportant) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Badge for App & Category
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val appInfo = if (!record.sender.isNullOrBlank() && record.sender != record.appName) {
                        "${record.appName} · ${record.sender}"
                    } else {
                        record.appName
                    }
                    Text(
                        text = appInfo,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isImportant) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }

                // Urgency tag / timestamp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (record.important && record.alert) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                "⚡ ALERT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else if (record.important) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                "⭐ IMPORTANT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(formatTime(record.timestamp), fontSize = 11.sp, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = record.summary,
                fontWeight = if (isImportant) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            if (!record.reason.isBlank()) {
                Text(
                    text = "AI: ${record.reason}",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun NotificationDetailDialog(
    record: NotificationRecord,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onAddRuleForSender: (String) -> Unit
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
                    Text("AI Chime Alert Triggered: YES (⚡)", fontSize = 12.sp, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (!record.sender.isNullOrBlank() && record.sender != record.appName) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onAddRuleForSender(record.sender)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add Rule for '${record.sender}'")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddRuleDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    val suggestions = listOf(
        "Urgent messages from Mom are important",
        "OTP and verification codes are important",
        "Delivery and courier updates are important",
        "Bank transaction alerts are important"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Rule") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("e.g. Urgent messages from Rahul are important.") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Suggestions:", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    suggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = { text = suggestion },
                            label = { Text(suggestion, fontSize = 11.sp) }
                        )
                    }
                }
            }
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

@Composable
fun RetentionSection(
    selectedPeriod: RetentionPeriod,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "HISTORY RETENTION",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    "Auto-expires after ${selectedPeriod.label}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selectedPeriod.label} ▼",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun RetentionDialog(
    currentPeriod: RetentionPeriod,
    onDismiss: () -> Unit,
    onSelect: (RetentionPeriod) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification Retention Period", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Select how long notifications are kept in history before expiring automatically:",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(12.dp))
                RetentionPeriod.entries.forEach { period ->
                    val isSelected = period == currentPeriod
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(period) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelect(period) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (period == RetentionPeriod.HOURS_24) "${period.label} (Default)" else period.label,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}


