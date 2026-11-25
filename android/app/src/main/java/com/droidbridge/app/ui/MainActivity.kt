package com.droidbridge.app.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.droidbridge.app.calls.CallController
import com.droidbridge.app.mirror.MirrorConsentActivity
import com.droidbridge.app.net.BridgeConnectionService
import com.droidbridge.app.net.BridgeConnectionState
import com.droidbridge.app.net.BridgeEventBus
import com.droidbridge.app.net.BridgeLog
import com.droidbridge.app.net.ClipboardPayload
import com.droidbridge.app.net.Protocol
import com.droidbridge.app.pairing.KeyStoreManager
import com.droidbridge.app.pairing.PairedMacInfo
import com.droidbridge.app.pairing.PairingClient
import com.droidbridge.app.pairing.PairingUri
import com.droidbridge.app.pairing.QrScannerScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val keyStore = KeyStoreManager(this)
        CallController.start(this)
        if (keyStore.isPaired()) {
            runCatching { BridgeConnectionService.start(this) }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DroidBridgeRoot(keyStore)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && BridgeEventBus.connectionState.value == BridgeConnectionState.CONNECTED) {
            // Android 10+: reading clipboard is permitted while our activity has window focus
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) {
                BridgeEventBus.sendControl(
                    Protocol.MSG_CLIPBOARD,
                    Protocol.encodeJson(ClipboardPayload(text))
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DroidBridgeRoot(keyStore: KeyStoreManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var pairedMac by remember { mutableStateOf<PairedMacInfo?>(keyStore.getPairedMac()) }
    var perms by remember { mutableStateOf(PermissionChecker.checkAll(context)) }
    var selectedTab by remember { mutableIntStateOf(if (pairedMac != null) 0 else 1) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showManualUriDialog by remember { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var showUnpairConfirm by remember { mutableStateOf(false) }
    var pairingFeedback by remember { mutableStateOf<String?>(null) }
    var pairingErrorDialog by remember { mutableStateOf<String?>(null) }
    var isPairing by remember { mutableStateOf(false) }

    // Refresh permissions every time user returns from Android Settings
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                perms = PermissionChecker.checkAll(context)
                pairedMac = keyStore.getPairedMac()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        perms = PermissionChecker.checkAll(context)
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        perms = PermissionChecker.checkAll(context)
        if (granted) {
            showQrScanner = true
        } else {
            pairingFeedback = "Camera permission is needed to scan the QR code (or use Manual URI)."
        }
    }

    val callPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        perms = PermissionChecker.checkAll(context)
    }

    fun performPairing(rawUri: String) {
        scope.launch {
            isPairing = true
            pairingFeedback = "Pairing with Mac…"
            pairingErrorDialog = null
            try {
                val parsed = PairingUri.parse(rawUri)
                val info = PairingClient.pair(parsed, keyStore)
                pairedMac = info
                pairingFeedback = "Paired with ${info.macName}!"
                // Always force reconnect so an already-running service drops stale sockets & wakes immediately
                BridgeConnectionService.reconnect(context)
                selectedTab = 0
            } catch (e: Exception) {
                BridgeLog.e("Pairing", "Pairing failed", e)
                val msg = e.message ?: "Unknown pairing error"
                pairingFeedback = "Pairing failed: $msg"
                pairingErrorDialog = msg
            } finally {
                isPairing = false
            }
        }
    }

    if (showQrScanner) {
        QrScannerScreen(
            onQrScanned = { raw ->
                showQrScanner = false
                performPairing(raw)
            },
            onCancel = { showQrScanner = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("DroidBridge") })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("●") },
                    label = { Text("Status") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("✓") },
                    label = { Text("Setup") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("≡") },
                    label = { Text("Logs") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
                0 -> StatusScreen(
                    pairedMac = pairedMac,
                    onStartMirror = {
                        context.startActivity(Intent(context, MirrorConsentActivity::class.java))
                    },
                    onReconnect = { BridgeConnectionService.reconnect(context) },
                    onUnpair = { showUnpairConfirm = true },
                    onGoToSetup = { selectedTab = 1 },
                    onSyncClipboard = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrEmpty()) {
                            BridgeEventBus.sendControl(
                                Protocol.MSG_CLIPBOARD,
                                Protocol.encodeJson(ClipboardPayload(text))
                            )
                            pairingFeedback = "Clipboard sent to Mac."
                        } else {
                            pairingFeedback = "Phone clipboard is empty."
                        }
                    }
                )
                1 -> OnboardingChecklistScreen(
                    perms = perms,
                    pairedMac = pairedMac,
                    isPairing = isPairing,
                    feedback = pairingFeedback,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenAppInfo = { PermissionChecker.openAppInfo(context) },
                    onOpenNotifListener = { PermissionChecker.openNotificationListenerSettings(context) },
                    onRequestAccessibility = { showAccessibilityDisclosure = true },
                    onOpenImeSettings = { PermissionChecker.openInputMethodSettings(context) },
                    onRequestBattery = { PermissionChecker.requestBatteryOptimizationExemption(context) },
                    onOpenDontKillMyApp = { PermissionChecker.openUrl(context, "https://dontkillmyapp.com") },
                    onRequestCalls = {
                        callPermLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.CALL_PHONE,
                                Manifest.permission.ANSWER_PHONE_CALLS
                            )
                        )
                    },
                    onScanQr = {
                        if (PermissionChecker.hasCameraPermission(context)) {
                            showQrScanner = true
                        } else {
                            cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onManualUri = { showManualUriDialog = true }
                )
                2 -> LogViewerScreen()
            }
        }
    }

    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text("Accessibility Service Disclosure") },
            text = {
                Text(
                    "DroidBridge uses Android's Accessibility Service solely to inject taps, swipes, scrolling, and system navigation (Back, Home, Recents, Notifications) that you trigger from your own paired Mac over an end-to-end encrypted Wi-Fi connection.\n\n" +
                        "DroidBridge never reads passwords, never sends data to external servers, and never records your screen unless you explicitly approve a mirroring session."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showAccessibilityDisclosure = false
                    PermissionChecker.openAccessibilitySettings(context)
                }) {
                    Text("Open Accessibility Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showManualUriDialog) {
        var uriInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualUriDialog = false },
            title = { Text("Enter Pairing URI") },
            text = {
                Column {
                    Text("Paste the droidbridge://pair?... URI copied from your Mac's pairing window.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uriInput,
                        onValueChange = { uriInput = it },
                        singleLine = true,
                        placeholder = { Text("droidbridge://pair?...") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showManualUriDialog = false
                    if (uriInput.isNotBlank()) performPairing(uriInput)
                }) {
                    Text("Pair")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualUriDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (pairingErrorDialog != null) {
        AlertDialog(
            onDismissRequest = { pairingErrorDialog = null },
            title = { Text("Pairing Failed") },
            text = {
                Text(
                    "${pairingErrorDialog}\n\n" +
                        "Check that:\n" +
                        "• Both devices are on the same Wi-Fi (no VPN blocking LAN)\n" +
                        "• The QR code on your Mac has not expired (2 min)\n" +
                        "• macOS Firewall / Local Network allows DroidBridge"
                )
            },
            confirmButton = {
                Button(onClick = { pairingErrorDialog = null }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pairingErrorDialog = null
                    selectedTab = 2
                }) { Text("View Logs") }
            }
        )
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text("Unpair Mac?") },
            text = { Text("This removes your paired Mac's cryptographic key and stops the background bridge.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showUnpairConfirm = false
                        BridgeConnectionService.stop(context)
                        keyStore.clearPairing()
                        pairedMac = null
                        selectedTab = 1
                    }
                ) { Text("Unpair") }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatusScreen(
    pairedMac: PairedMacInfo?,
    onStartMirror: () -> Unit,
    onReconnect: () -> Unit,
    onUnpair: () -> Unit,
    onGoToSetup: () -> Unit,
    onSyncClipboard: () -> Unit
) {
    val connState by BridgeEventBus.connectionState.collectAsState()
    val mirrorState by BridgeEventBus.mirrorState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (pairedMac == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Not Paired Yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Complete the Setup checklist and scan the QR code shown in DroidBridge on your Mac.")
                    Button(onClick = onGoToSetup) {
                        Text("Open Setup Checklist")
                    }
                }
            }
            return
        }

        val (dotColor, statusText) = when (connState) {
            BridgeConnectionState.CONNECTED -> Color(0xFF2E7D32) to "Connected to ${pairedMac.macName}"
            BridgeConnectionState.CONNECTING -> Color(0xFFEF6C00) to "Connecting to ${pairedMac.macName}…"
            BridgeConnectionState.SEARCHING -> Color(0xFF1565C0) to "Searching for ${pairedMac.macName}…"
            else -> Color(0xFFC62828) to "Disconnected from ${pairedMac.macName}"
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(dotColor))
                    Spacer(Modifier.width(10.dp))
                    Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Last endpoint: ${pairedMac.lastHost}:${pairedMac.lastPort} • End-to-end encrypted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Mirror state: $mirrorState",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = onStartMirror,
            enabled = connState == BridgeConnectionState.CONNECTED,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when (mirrorState) {
                    "streaming" -> "Mirroring Active (Entire Screen)"
                    "standby" -> "Resume Mirroring Instantly"
                    else -> "Start Mirroring to ${pairedMac.macName}"
                }
            )
        }

        if (mirrorState == "streaming" || mirrorState == "standby") {
            val ctx = LocalContext.current
            OutlinedButton(
                onClick = { com.droidbridge.app.mirror.MirrorService.stop(ctx) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("End Screen Capture Session")
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onSyncClipboard,
                enabled = connState == BridgeConnectionState.CONNECTED,
                modifier = Modifier.weight(1f)
            ) {
                Text("Send Clipboard")
            }
            OutlinedButton(onClick = onReconnect, modifier = Modifier.weight(1f)) {
                Text("Reconnect")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Known Android Platform Limits", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("• Your phone screen must stay unlocked while mirroring.", style = MaterialTheme.typography.bodySmall)
                Text("• Apps using FLAG_SECURE (banking, DRM video) appear black by Android security policy.", style = MaterialTheme.typography.bodySmall)
                Text("• Android 14+ requires tapping 'Start now' on each mirroring session.", style = MaterialTheme.typography.bodySmall)
                Text("• Android 15+ may redact one-time passcodes in notifications.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onUnpair,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Unpair ${pairedMac.macName}")
        }
    }
}

@Composable
private fun OnboardingChecklistScreen(
    perms: PermissionChecklistState,
    pairedMac: PairedMacInfo?,
    isPairing: Boolean,
    feedback: String?,
    onRequestNotifications: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenNotifListener: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenDontKillMyApp: () -> Unit,
    onRequestCalls: () -> Unit,
    onScanQr: () -> Unit,
    onManualUri: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Setup Checklist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Grant these permissions so DroidBridge can sync notifications, mirror your screen, and accept Mac input without ADB or root.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (feedback != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(feedback, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        ChecklistCard(
            step = "1. Allow Notifications",
            description = "Needed to show connection status and mirror approval prompts (Android 13+).",
            granted = perms.notificationsGranted,
            buttonLabel = "Grant",
            onAction = onRequestNotifications
        )

        // Step 2: Android 13+ sideloaded Restricted Settings explanation
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "2. Allow Restricted Settings (Android 13+ Sideloaded Apps)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Because DroidBridge is sideloaded outside Google Play, Android greys out Notification Access and Accessibility until you unlock restricted settings:\n" +
                        "  ① Tap 'Open App Info' below.\n" +
                        "  ② Tap the ⋮ menu in the top-right corner.\n" +
                        "  ③ Tap 'Allow restricted settings' and authenticate.\n" +
                        "  ④ Return here to enable steps 3 and 4.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onOpenAppInfo) {
                    Text("Open App Info")
                }
            }
        }

        ChecklistCard(
            step = "3. Notification Access",
            description = "Forwards phone & SMS notifications to your Mac and lets you reply or dismiss from macOS.",
            granted = perms.notificationListenerGranted,
            buttonLabel = "Open Settings",
            onAction = onOpenNotifListener
        )

        ChecklistCard(
            step = "4. Accessibility Service (Remote Control)",
            description = "Injects taps, swipes, scrolling, and Back/Home/Recents from your paired Mac only.",
            granted = perms.accessibilityGranted,
            buttonLabel = "Review & Enable",
            onAction = onRequestAccessibility
        )

        ChecklistCard(
            step = "5. DroidBridge Keyboard (Optional)",
            description = "Recommended for smooth typing from your Mac keyboard without replacing full text fields.",
            granted = perms.keyboardEnabled,
            buttonLabel = "Enable Keyboard",
            onAction = onOpenImeSettings
        )

        ChecklistCard(
            step = "6. Call Control (Optional)",
            description = "Shows incoming calls on your Mac with Answer/Decline and lets you dial from your Mac. Call audio still stays on the phone.",
            granted = perms.callsGranted,
            buttonLabel = "Grant",
            onAction = onRequestCalls
        )

        // Step 7: Battery exemption + OEM tips
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(perms.batteryExemptionGranted)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "7. Battery & Background Survival",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Exempt DroidBridge from Doze/battery optimization so notifications stay instant when the screen is off.\n\n" +
                        "OEM tips:\n" +
                        "• Tecno / Infinix (HiOS / XOS): Enable Auto-start in Phone Master & lock DroidBridge in Recents.\n" +
                        "• Xiaomi (MIUI / HyperOS): Enable Autostart & set Battery Saver to 'No restrictions'.\n" +
                        "• Samsung (One UI): Add DroidBridge to 'Never sleeping apps'.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRequestBattery) {
                        Text(if (perms.batteryExemptionGranted) "Exempted" else "Disable Optimization")
                    }
                    OutlinedButton(onClick = onOpenDontKillMyApp) {
                        Text("dontkillmyapp.com")
                    }
                }
            }
        }

        // Step 8: Pair with Mac
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(pairedMac != null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "8. Pair with Your Mac",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    if (pairedMac != null) "Paired with ${pairedMac.macName}. You can scan again to pair a different Mac."
                    else "Open DroidBridge on your Mac, choose 'Pair New Phone…', and scan the QR code.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (feedback != null) {
                    Text(
                        feedback,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (feedback.startsWith("Pairing failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onScanQr, enabled = !isPairing) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPairing) "Pairing…" else "Scan Pairing QR")
                    }
                    OutlinedButton(onClick = onManualUri, enabled = !isPairing) {
                        Text("Paste URI")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistCard(
    step: String,
    description: String,
    granted: Boolean,
    buttonLabel: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIcon(granted)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(step, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Text("Granted", style = MaterialTheme.typography.labelMedium, color = Color(0xFF2E7D32))
            } else {
                Button(onClick = onAction) { Text(buttonLabel) }
            }
        }
    }
}

@Composable
private fun StatusIcon(granted: Boolean) {
    if (granted) {
        Icon(Icons.Default.CheckCircle, contentDescription = "Granted", tint = Color(0xFF2E7D32))
    } else {
        Icon(Icons.Default.ErrorOutline, contentDescription = "Pending", tint = Color(0xFFEF6C00))
    }
}

@Composable
private fun LogViewerScreen() {
    val entries by BridgeLog.entries.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Diagnostic Log (${entries.size})", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { BridgeLog.clear() }) { Text("Clear") }
        }
        Text(
            "Release builds never log notification text or keystrokes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries.reversed()) { entry ->
                Text(
                    text = "${entry.timestamp} [${entry.level}/${entry.tag}] ${entry.message}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
