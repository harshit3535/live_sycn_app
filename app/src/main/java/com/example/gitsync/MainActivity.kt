package com.example.gitsync

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.gitsync.ui.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* UI re-checks permission state on its own each recomposition */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, app still works, just no visible notification if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncStatusRegistry.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            GitSyncTheme {
                Surface(color = BgColor) {
                    GitSyncApp(
                        onRequestAllFilesAccess = { requestAllFilesAccess() },
                        onOpenAccessibilitySettings = { openAccessibilitySettings() },
                        hasAllFilesAccess = { hasAllFilesAccess() },
                        isAccessibilityEnabled = { isAccessibilityServiceEnabled() }
                    )
                }
            }
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            manageStorageLauncher.launch(intent)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

private sealed class Screen {
    object Home : Screen()
    data class Detail(val profileId: String?) : Screen()
    object Connections : Screen()
}

@Composable
private fun GitSyncApp(
    onRequestAllFilesAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    hasAllFilesAccess: () -> Boolean,
    isAccessibilityEnabled: () -> Boolean
) {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    val connectionsStore = remember { ConnectionsStore(context) }

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val profiles = remember { mutableStateListOf<SyncProfile>() }
    var runningIds by remember { mutableStateOf(store.runningProfileIds()) }

    fun reloadProfiles() {
        profiles.clear()
        profiles.addAll(store.getAll())
        runningIds = store.runningProfileIds()
    }

    LaunchedEffect(Unit) { reloadProfiles() }
    // Keep running-state fresh while on the home screen (service may finish an
    // initial sync, or a profile could be stopped from its own notification).
    LaunchedEffect(screen) {
        while (screen is Screen.Home) {
            runningIds = store.runningProfileIds()
            delay(1000)
        }
    }

    when (val s = screen) {
        is Screen.Home -> HomeScreen(
            profiles = profiles,
            runningIds = runningIds,
            onNew = { screen = Screen.Detail(null) },
            onOpen = { screen = Screen.Detail(it.id) },
            onToggleStart = { profile, start ->
                if (start) SyncService.start(context, profile.id) else SyncService.stop(context, profile.id)
                runningIds = if (start) runningIds + profile.id else runningIds - profile.id
            },
            onDelete = { profile ->
                store.delete(profile.id)
                SyncStatusRegistry.deleteProfileData(profile.id)
                java.io.File(context.filesDir, "sync_manifest_${profile.id}.json").let { if (it.exists()) it.delete() }
                reloadProfiles()
            },
            onOpenConnections = { screen = Screen.Connections }
        )
        is Screen.Detail -> ProfileDetailScreen(
            context = context,
            initialProfileId = s.profileId,
            store = store,
            connectionsStore = connectionsStore,
            hasAllFilesAccess = hasAllFilesAccess,
            isAccessibilityEnabled = isAccessibilityEnabled,
            onRequestAllFilesAccess = onRequestAllFilesAccess,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onBack = {
                reloadProfiles()
                screen = Screen.Home
            }
        )
        is Screen.Connections -> ConnectionsScreen(
            connectionsStore = connectionsStore,
            onBack = { screen = Screen.Home }
        )
    }
}

// ============================== HOME ==============================

@Composable
private fun HomeScreen(
    profiles: List<SyncProfile>,
    runningIds: Set<String>,
    onNew: () -> Unit,
    onOpen: (SyncProfile) -> Unit,
    onToggleStart: (SyncProfile, Boolean) -> Unit,
    onDelete: (SyncProfile) -> Unit,
    onOpenConnections: () -> Unit
) {
    var deleteTarget by remember { mutableStateOf<SyncProfile?>(null) }
    var blockedMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("GitSync", color = TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${runningIds.size}/${ProfileStore.MAX_RUNNING} running",
                    color = if (runningIds.isNotEmpty()) AccentGreen else TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onOpenConnections) {
                Icon(Icons.Default.Link, contentDescription = "Connections", tint = AccentBlue)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (profiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                Text("No templates yet — tap + to create one", color = TextSecondary)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(profiles, key = { it.id }) { profile ->
                    val isRunning = profile.id in runningIds
                    ProfileRow(
                        profile = profile,
                        isRunning = isRunning,
                        onOpen = { onOpen(profile) },
                        onToggleStart = {
                            if (!isRunning && runningIds.size >= ProfileStore.MAX_RUNNING) {
                                blockedMessage = "Max ${ProfileStore.MAX_RUNNING} syncs can run at once — stop one first"
                            } else {
                                onToggleStart(profile, !isRunning)
                            }
                        },
                        onDelete = {
                            if (isRunning) {
                                blockedMessage = "Stop sync before deleting this template"
                            } else {
                                deleteTarget = profile
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onNew,
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New template", fontWeight = FontWeight.Bold)
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = SurfaceColor,
            title = { Text("Delete \"${target.name}\"?", color = TextPrimary) },
            text = { Text("This only removes the saved template. Files already pushed to GitHub or on your phone are not touched.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { onDelete(target); deleteTarget = null }) {
                    Text("Delete", color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }

    blockedMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { blockedMessage = null },
            containerColor = SurfaceColor,
            title = { Text("Can't do that", color = TextPrimary) },
            text = { Text(msg, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { blockedMessage = null }) { Text("OK", color = AccentGreen) }
            }
        )
    }
}

@Composable
private fun ProfileRow(
    profile: SyncProfile,
    isRunning: Boolean,
    onOpen: () -> Unit,
    onToggleStart: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(9.dp)
                        .background(if (isRunning) AccentGreen else TextSecondary, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(profile.name, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            val repoLabel = profile.ownerRepo()?.let { "${it.first}/${it.second}" } ?: "Invalid repo URL"
            Text(repoLabel, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(profile.interval.label, color = TextSecondary, style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onToggleStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) AccentRed else AccentGreen,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (isRunning) "Stop" else "Start")
                }
                OutlinedButton(onClick = onOpen) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AccentRed)
                }
            }
        }
    }
}

// ============================== DETAIL ==============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDetailScreen(
    context: Context,
    initialProfileId: String?,
    store: ProfileStore,
    connectionsStore: ConnectionsStore,
    hasAllFilesAccess: () -> Boolean,
    isAccessibilityEnabled: () -> Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onBack: () -> Unit
) {
    val existing = remember(initialProfileId) { initialProfileId?.let { store.get(it) } }
    val profileId = remember { initialProfileId ?: UUID.randomUUID().toString() }
    var isSaved by remember { mutableStateOf(existing != null) }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var repoUrl by remember { mutableStateOf(existing?.repoUrl ?: "") }
    var token by remember { mutableStateOf(existing?.token ?: "") }
    var folderPath by remember { mutableStateOf(existing?.folderPath ?: "") }
    var interval by remember { mutableStateOf(existing?.interval ?: SyncInterval.ON_CHANGE) }
    var triggerApps by remember { mutableStateOf(existing?.triggerApps ?: emptySet()) }
    val branch = remember { existing?.branch ?: "" }

    var showFolderDialog by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showIntervalMenu by remember { mutableStateOf(false) }
    var showFirstSyncDialog by remember { mutableStateOf(false) }
    var alertMessage by remember { mutableStateOf<String?>(null) }

    var runningIdsLocal by remember { mutableStateOf(store.runningProfileIds()) }
    val isRunning = profileId in runningIdsLocal

    val status = remember(profileId) { SyncStatusRegistry.forProfile(profileId) }
    val statusRunning by status.isRunning.collectAsState()
    val phase by status.phase.collectAsState()
    val progress by status.progressPercent.collectAsState()
    val statusText by status.statusText.collectAsState()
    val lastSync by status.lastSyncTimeMillis.collectAsState()
    val nextCheck by status.nextCheckTimeMillis.collectAsState()
    val logs by status.logs.collectAsState()

    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { tick = System.currentTimeMillis(); delay(1000) }
    }

    fun buildProfile() = SyncProfile(profileId, name.ifBlank { "Untitled" }, repoUrl, token, folderPath, branch, interval, triggerApps)

    fun persist(): SyncProfile {
        val p = buildProfile()
        store.save(p)
        isSaved = true
        return p
    }

    fun beginSync(p: SyncProfile) {
        SyncService.start(context, p.id)
        runningIdsLocal = store.runningProfileIds()
    }

    fun handleStart() {
        val p = persist()
        if (!p.isConfigured()) {
            alertMessage = "Fill in repo URL, token and folder before starting"
            return
        }
        if (SyncStatusRegistry.runningCount() >= ProfileStore.MAX_RUNNING) {
            alertMessage = "Max ${ProfileStore.MAX_RUNNING} syncs can run at once — stop one first"
            return
        }
        val key = p.connectionKey()
        if (connectionsStore.isInitialized(key)) {
            beginSync(p)
        } else if (FolderPreflight.isEmpty(p.folderPath)) {
            val label = p.ownerRepo()?.let { "${it.first}/${it.second}" } ?: p.repoUrl
            connectionsStore.markInitialized(key, label, p.folderPath)
            beginSync(p)
        } else {
            showFirstSyncDialog = true
        }
    }

    fun handleStop() {
        SyncService.stop(context, profileId)
        runningIdsLocal = store.runningProfileIds()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text(if (existing == null) "New template" else "Edit template", color = TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Template name")
        SectionCard {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("e.g. College Notes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = gitSyncTextFieldColors()
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Repository")
        SectionCard {
            OutlinedTextField(
                value = repoUrl,
                onValueChange = { repoUrl = it },
                label = { Text("GitHub repo URL") },
                placeholder = { Text("https://github.com/owner/repo.git") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = gitSyncTextFieldColors()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("GitHub token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = gitSyncTextFieldColors()
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Folder to sync")
        SectionCard {
            OutlinedTextField(
                value = folderPath,
                onValueChange = { folderPath = it },
                label = { Text("Folder path") },
                placeholder = { Text("/storage/emulated/0/MyNotes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = gitSyncTextFieldColors()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { showFolderDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = AccentBlue)
                Spacer(Modifier.width(8.dp))
                Text("Browse folders")
            }
            if (!hasAllFilesAccess()) {
                Spacer(Modifier.height(10.dp))
                PermissionWarning("Needs \"All files access\" to read/write your folder", "Grant") { onRequestAllFilesAccess() }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Sync interval")
        SectionCard {
            Box {
                OutlinedButton(onClick = { showIntervalMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(interval.label)
                }
                DropdownMenu(expanded = showIntervalMenu, onDismissRequest = { showIntervalMenu = false }) {
                    SyncInterval.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { interval = option; showIntervalMenu = false })
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Trigger sync on app open/close")
        SectionCard {
            Text("${triggerApps.size} app(s) selected", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Apps, contentDescription = null, tint = AccentBlue)
                Spacer(Modifier.width(8.dp))
                Text("Choose apps")
            }
            if (triggerApps.isNotEmpty() && !isAccessibilityEnabled()) {
                Spacer(Modifier.height(10.dp))
                PermissionWarning("Enable Accessibility Service for app-trigger detection", "Enable") { onOpenAccessibilitySettings() }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { persist(); onBack() },
            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Text("Save template", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Live status")
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(statusRunning, phase)
                Spacer(Modifier.width(8.dp))
                Text(statusText, color = TextPrimary, fontWeight = FontWeight.Medium)
            }
            if (phase == SyncPhase.PUSHING || phase == SyncPhase.PULLING) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentGreen,
                    trackColor = SurfaceColorAlt
                )
                Spacer(Modifier.height(4.dp))
                Text("$progress%", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            val secsSinceSync = if (lastSync > 0) ((tick - lastSync) / 1000).coerceAtLeast(0) else null
            val secsToNext = if (statusRunning && nextCheck > 0) ((nextCheck - tick) / 1000).coerceAtLeast(0) else null
            InfoRow("Last sync", secsSinceSync?.let { "${it}s ago (${SyncStatusRegistry.formatTime(lastSync)})" } ?: "Never yet")
            if (secsToNext != null) InfoRow("Next check", "in ${secsToNext}s")

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { if (isRunning) handleStop() else handleStart() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) AccentRed else AccentGreen,
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Icon(if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isRunning) "Stop sync" else "Start sync", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Activity log (this template only)")
        SectionCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${logs.size} entries", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { status.clearLogs() }) { Text("Clear", color = AccentRed) }
            }
            Spacer(Modifier.height(6.dp))
            if (logs.isEmpty()) {
                Text("No activity yet", color = TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Column {
                    logs.reversed().take(200).forEach { entry -> LogRow(entry) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showFolderDialog) {
        FolderBrowserDialog(
            startPath = folderPath,
            onDismiss = { showFolderDialog = false },
            onSelect = { folderPath = it; showFolderDialog = false }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            context = context,
            initiallySelected = triggerApps,
            onDismiss = { showAppPicker = false },
            onConfirm = { triggerApps = it; showAppPicker = false }
        )
    }

    if (showFirstSyncDialog) {
        AlertDialog(
            onDismissRequest = { showFirstSyncDialog = false },
            containerColor = SurfaceColor,
            title = { Text("Folder already has files", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This is the first time syncing this folder with this repo, and the folder isn't empty. " +
                        "Is this your existing project folder, or do you want a fresh start from GitHub?",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = buildProfile()
                    connectionsStore.markInitialized(
                        p.connectionKey(),
                        p.ownerRepo()?.let { "${it.first}/${it.second}" } ?: p.repoUrl,
                        p.folderPath
                    )
                    showFirstSyncDialog = false
                    beginSync(p)
                }) { Text("This is my folder — sync normally", color = AccentGreen) }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        val p = buildProfile()
                        val backupPath = FolderPreflight.backupExistingContents(p.folderPath)
                        connectionsStore.markInitialized(
                            p.connectionKey(),
                            p.ownerRepo()?.let { "${it.first}/${it.second}" } ?: p.repoUrl,
                            p.folderPath
                        )
                        SyncStatusRegistry.forProfile(p.id).addLog(LogType.INFO, "Existing files moved to $backupPath before fresh pull")
                        showFirstSyncDialog = false
                        beginSync(p)
                    }) { Text("Fresh start (backs up old files first)", color = AccentAmber) }
                    TextButton(onClick = { showFirstSyncDialog = false }) { Text("Cancel", color = TextSecondary) }
                }
            }
        )
    }

    alertMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { alertMessage = null },
            containerColor = SurfaceColor,
            title = { Text("Can't start sync", color = TextPrimary) },
            text = { Text(msg, color = TextSecondary) },
            confirmButton = { TextButton(onClick = { alertMessage = null }) { Text("OK", color = AccentGreen) } }
        )
    }
}

// ============================== CONNECTIONS ==============================

@Composable
private fun ConnectionsScreen(connectionsStore: ConnectionsStore, onBack: () -> Unit) {
    var records by remember { mutableStateOf(connectionsStore.getAll()) }
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text("Connections", color = TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Repo + folder pairs that have already been safely connected once.",
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        if (records.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text("No connections yet", color = TextSecondary)
            }
        } else {
            LazyColumn {
                items(records, key = { it.key }) { record ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(record.repoLabel, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(record.folderPath, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Connected: ${formatter.format(record.initializedAtMillis)}",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = {
                                connectionsStore.remove(record.key)
                                records = connectionsStore.getAll()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = AccentRed)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================== SHARED WIDGETS ==============================

@Composable
fun SectionTitle(text: String) {
    Text(text, color = TextSecondary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(value, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun StatusDot(isRunning: Boolean, phase: SyncPhase) {
    val color = when {
        !isRunning -> TextSecondary
        phase == SyncPhase.ERROR -> AccentRed
        phase == SyncPhase.PUSHING || phase == SyncPhase.PULLING -> AccentAmber
        else -> AccentGreen
    }
    Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
fun PermissionWarning(text: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(AccentAmber.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = AccentAmber)
        Spacer(Modifier.width(8.dp))
        Text(text, color = TextPrimary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(actionLabel, color = AccentAmber) }
    }
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun LogRow(entry: LogEntry) {
    val color = when (entry.type) {
        LogType.PUSH -> AccentGreen
        LogType.PULL -> AccentBlue
        LogType.DELETE -> AccentRed
        LogType.ERROR -> AccentRed
        LogType.TRIGGER -> AccentAmber
        LogType.INFO -> TextSecondary
    }
    val prefix = when (entry.type) {
        LogType.PUSH -> "PUSH"
        LogType.PULL -> "PULL"
        LogType.DELETE -> "DEL"
        LogType.ERROR -> "ERR"
        LogType.TRIGGER -> "TRIG"
        LogType.INFO -> "INFO"
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(timeFormatter.format(entry.timestampMillis), color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(70.dp))
        Text(prefix, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(42.dp))
        Text(entry.message, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}
