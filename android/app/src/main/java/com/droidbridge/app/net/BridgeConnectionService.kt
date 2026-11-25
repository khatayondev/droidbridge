package com.droidbridge.app.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.droidbridge.app.BuildConfig
import com.droidbridge.app.mirror.MirrorService
import com.droidbridge.app.pairing.KeyStoreManager
import com.droidbridge.app.pairing.PairedMacInfo
import com.droidbridge.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Persistent foreground service (foregroundServiceType="connectedDevice") that owns:
 * - Parallel fast discovery (UDP LAN probe + mDNS + last-known IP:port) across any Wi-Fi network
 * - Encrypted control TCP socket with instant network-switch detection & smart backoff (500ms -> 15s)
 * - Heartbeat (5s ping, 15s timeout)
 * - Control message routing and instant zero-prompt standby mirror resume
 */
class BridgeConnectionService : LifecycleService() {

    private lateinit var keyStore: KeyStoreManager
    private lateinit var discovery: DiscoveryManager
    private lateinit var telemetryCollector: TelemetryCollector
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var notificationManager: NotificationManager

    private val activeChannel = AtomicReference<EncryptedChannel?>(null)
    private val activeEndpoint = AtomicReference<Pair<String, Int>?>(null)
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)

    private var connectionLoopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var batteryReceiver: BroadcastReceiver? = null
    @Volatile
    private var lastLinkSignature: String? = null

    override fun onCreate() {
        super.onCreate()
        keyStore = KeyStoreManager(this)
        discovery = DiscoveryManager(this)
        telemetryCollector = TelemetryCollector(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannels()
        com.droidbridge.app.calls.CallController.start(this)
        startAsForeground("Searching for paired Mac…")
        registerNetworkCallback()
        registerBatteryReceiver()
        observeOutgoingMessages()

        instance = this
        startConnectionLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                activeChannel.getAndSet(null)?.close()
                reconnectSignal.trySend(Unit)
            }
            ACTION_START -> {
                if (activeChannel.get() == null) {
                    reconnectSignal.trySend(Unit)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        connectionLoopJob?.cancel()
        activeChannel.getAndSet(null)?.close()
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        BridgeEventBus.setConnectionState(BridgeConnectionState.DISCONNECTED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * Opens a separate encrypted TCP socket for the video channel using the currently active Mac endpoint.
     */
    suspend fun openVideoChannel(): EncryptedChannel? = withContext(Dispatchers.IO) {
        val paired = keyStore.getPairedMac() ?: return@withContext null
        val (host, port) = activeEndpoint.get() ?: (paired.lastHost to paired.lastPort)
        val staticKp = keyStore.getOrCreateStaticKeyPair()
        val hello = buildHelloPayload()
        runCatching {
            EncryptedChannel.connectAsClient(
                host = host,
                port = port,
                mode = Protocol.MODE_VIDEO,
                channelName = Protocol.CHANNEL_VIDEO,
                phoneStaticKeyPair = staticKp,
                macStaticPub = paired.macStaticPub,
                localHello = hello,
                readTimeoutMs = 30_000,
                maxFrameBytes = Protocol.MAX_VIDEO_FRAME_BYTES
            )
        }.onFailure {
            BridgeLog.e("VideoNet", "Failed to open video channel", it)
        }.getOrNull()
    }

    private fun startConnectionLoop() {
        connectionLoopJob?.cancel()
        connectionLoopJob = lifecycleScope.launch(Dispatchers.IO) {
            var backoffMs = 500L
            while (isActive) {
                val paired = keyStore.getPairedMac()
                if (paired == null) {
                    BridgeEventBus.setConnectionState(BridgeConnectionState.UNPAIRED, null)
                    updateForegroundNotification("Not paired — open DroidBridge to pair")
                    delay(3_000L)
                    continue
                }

                BridgeEventBus.setConnectionState(BridgeConnectionState.SEARCHING, paired.macName)
                updateForegroundNotification("Searching for ${paired.macName}…")

                val channel = tryConnectFast(paired)
                if (channel == null) {
                    BridgeEventBus.setConnectionState(BridgeConnectionState.DISCONNECTED, paired.macName)
                    val waitMs = backoffMs
                    backoffMs = min(backoffMs * 2, 15_000L)
                    val waitJob = launch { delay(waitMs) }
                    val signalJob = launch { reconnectSignal.receive() }
                    while (waitJob.isActive && signalJob.isActive) {
                        delay(80)
                    }
                    waitJob.cancel()
                    signalJob.cancel()
                    continue
                }

                // Connected!
                backoffMs = 500L
                activeChannel.set(channel)
                BridgeEventBus.setConnectionState(BridgeConnectionState.CONNECTED, paired.macName)
                updateForegroundNotification("Connected to ${paired.macName}")
                BridgeLog.i("Net", "Connected to ${paired.macName} at ${activeEndpoint.get()}")
                sendCurrentBatteryStatus()
                // Notify Mac if mirror is already armed in standby so it knows resume will be instant
                if (MirrorService.isReadyInStandby) {
                    BridgeEventBus.setMirrorState("standby")
                }

                try {
                    runSessionLoops(channel)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BridgeLog.w("Net", "Connection lost: ${e.message}")
                } finally {
                    activeChannel.compareAndSet(channel, null)
                    channel.close()
                    BridgeEventBus.setConnectionState(BridgeConnectionState.DISCONNECTED, paired.macName)
                    // Pause (NOT stop!) MirrorService on transient disconnect so MediaProjection survives
                    // network blips/switches with ZERO re-prompt!
                    BridgeEventBus.emitIncoming(IncomingControlEvent.MirrorPause)
                }
            }
        }
    }

    /**
     * Fast multi-network connect:
     * 1. Immediately tries last-known IP:port while simultaneously probing UDP LAN + mDNS.
     * 2. If last-known IP succeeds (same network), connects in <50ms.
     * 3. If on a brand-new Wi-Fi network, UDP LAN probe finds the Mac's new IP in <50ms and connects!
     */
    private suspend fun tryConnectFast(paired: PairedMacInfo): EncryptedChannel? = coroutineScope {
        val staticKp = keyStore.getOrCreateStaticKeyPair()
        val hello = buildHelloPayload()

        // Launch parallel discovery (UDP broadcast + mDNS) immediately
        val discoverDeferred = async(Dispatchers.IO) {
            discovery.discoverMac(paired.macDeviceId, timeoutMs = 2_500L)
        }

        // Simultaneously try last-known IP:port for instant reconnect on same Wi-Fi
        val lastKnownChannel = runCatching {
            BridgeEventBus.setConnectionState(BridgeConnectionState.CONNECTING, paired.macName)
            EncryptedChannel.connectAsClient(
                host = paired.lastHost,
                port = paired.lastPort,
                mode = Protocol.MODE_CONTROL,
                channelName = Protocol.CHANNEL_CONTROL,
                phoneStaticKeyPair = staticKp,
                macStaticPub = paired.macStaticPub,
                localHello = hello
            )
        }.getOrNull()

        if (lastKnownChannel != null) {
            discoverDeferred.cancel()
            activeEndpoint.set(paired.lastHost to paired.lastPort)
            return@coroutineScope lastKnownChannel
        }

        // Last-known IP didn't answer (likely moved to a different Wi-Fi network or IP changed);
        // use discovered endpoint from UDP probe / mDNS
        val discovered = discoverDeferred.await() ?: return@coroutineScope null
        if (discovered.host == paired.lastHost && discovered.port == paired.lastPort) {
            return@coroutineScope null
        }

        BridgeEventBus.setConnectionState(BridgeConnectionState.CONNECTING, paired.macName)
        runCatching {
            val ch = EncryptedChannel.connectAsClient(
                host = discovered.host,
                port = discovered.port,
                mode = Protocol.MODE_CONTROL,
                channelName = Protocol.CHANNEL_CONTROL,
                phoneStaticKeyPair = staticKp,
                macStaticPub = paired.macStaticPub,
                localHello = hello
            )
            activeEndpoint.set(discovered.host to discovered.port)
            keyStore.updateLastEndpoint(discovered.host, discovered.port)
            ch
        }.onFailure {
            BridgeLog.w("Net", "Connect to discovered ${discovered.host}:${discovered.port} failed: ${it.message}")
        }.getOrNull()
    }

    private suspend fun runSessionLoops(channel: EncryptedChannel) = withContext(Dispatchers.IO) {
        val pingJob = launch {
            while (isActive && !channel.isClosed) {
                delay(Protocol.HEARTBEAT_INTERVAL_MS)
                try {
                    channel.send(Protocol.MSG_PING, Protocol.encodeJson(PingPongPayload(System.currentTimeMillis())))
                } catch (_: Exception) {
                    channel.close()
                    break
                }
            }
        }

        // Send lightweight hardware/stream telemetry 1x/sec for live HUD & benchmark reports
        val telemetryJob = launch {
            while (isActive && !channel.isClosed) {
                delay(1_000L)
                try {
                    val sample = telemetryCollector.sample()
                    channel.send(Protocol.MSG_DEVICE_TELEMETRY, Protocol.encodeJson(sample))
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            while (isActive && !channel.isClosed) {
                val (msgType, payload) = channel.readNext()
                handleIncomingControlFrame(channel, msgType, payload)
            }
        } finally {
            pingJob.cancel()
            telemetryJob.cancel()
        }
    }

    private fun handleIncomingControlFrame(channel: EncryptedChannel, msgType: Byte, payload: ByteArray) {
        when (msgType) {
            Protocol.MSG_PING -> {
                runCatching { channel.send(Protocol.MSG_PONG, payload) }
            }
            Protocol.MSG_PONG -> Unit
            Protocol.MSG_NOTIF_DISMISS -> {
                val msg = Protocol.decodeJson<NotifKeyPayload>(payload)
                BridgeEventBus.emitIncoming(IncomingControlEvent.NotifDismiss(msg.key))
            }
            Protocol.MSG_NOTIF_REPLY -> {
                val msg = Protocol.decodeJson<NotifReplyPayload>(payload)
                BridgeEventBus.emitIncoming(IncomingControlEvent.NotifReply(msg.key, msg.text))
            }
            Protocol.MSG_NOTIF_ACTION -> {
                val msg = Protocol.decodeJson<NotifActionTriggerPayload>(payload)
                BridgeEventBus.emitIncoming(IncomingControlEvent.NotifAction(msg.key, msg.actionIndex))
            }
            Protocol.MSG_APP_ICON_REQUEST -> {
                val msg = Protocol.decodeJson<AppIconRequestPayload>(payload)
                BridgeEventBus.emitIncoming(IncomingControlEvent.AppIconRequest(msg.pkg))
            }
            Protocol.MSG_CALL_ANSWER -> com.droidbridge.app.calls.CallController.answer(this)
            Protocol.MSG_CALL_END -> com.droidbridge.app.calls.CallController.end(this)
            Protocol.MSG_CALL_DIAL -> {
                val msg = Protocol.decodeJson<CallDialPayload>(payload)
                com.droidbridge.app.calls.CallController.dial(this, msg.number)
            }
            Protocol.MSG_MIRROR_REQUEST -> {
                onMirrorRequestedFromMac()
            }
            Protocol.MSG_MIRROR_PAUSE -> {
                BridgeEventBus.emitIncoming(IncomingControlEvent.MirrorPause)
            }
            Protocol.MSG_MIRROR_STOP -> {
                BridgeEventBus.emitIncoming(IncomingControlEvent.MirrorStop)
            }
            Protocol.MSG_VIDEO_KEYFRAME_REQUEST -> {
                BridgeEventBus.emitIncoming(IncomingControlEvent.VideoKeyframeRequest)
            }
            Protocol.MSG_MIRROR_QUALITY -> {
                val q = Protocol.decodeJson<MirrorQualityPayload>(payload)
                BridgeEventBus.emitIncoming(IncomingControlEvent.MirrorQuality(q))
            }
            Protocol.MSG_APP_LAUNCH -> {
                val msg = Protocol.decodeJson<AppLaunchPayload>(payload)
                launchAppByPackage(msg.pkg)
                BridgeEventBus.emitIncoming(IncomingControlEvent.AppLaunch(msg.pkg))
            }
            Protocol.MSG_INPUT_TOUCH -> {
                BridgeEventBus.emitIncoming(IncomingControlEvent.InputTouch(Protocol.decodeJson(payload)))
            }
            Protocol.MSG_INPUT_SCROLL -> {
                BridgeEventBus.emitIncoming(IncomingControlEvent.InputScroll(Protocol.decodeJson(payload)))
            }
            Protocol.MSG_INPUT_TEXT -> {
                BridgeEventBus.emitIncoming(IncomingControlEvent.InputText(Protocol.decodeJson(payload)))
            }
            Protocol.MSG_INPUT_KEY -> {
                BridgeEventBus.emitIncoming(IncomingControlEvent.InputKey(Protocol.decodeJson(payload)))
            }
            Protocol.MSG_INPUT_GLOBAL -> {
                BridgeEventBus.emitIncoming(IncomingControlEvent.InputGlobal(Protocol.decodeJson(payload)))
            }
            Protocol.MSG_CLIPBOARD -> {
                val clip = Protocol.decodeJson<ClipboardPayload>(payload)
                applyClipboardFromMac(clip.text)
                BridgeEventBus.emitIncoming(IncomingControlEvent.Clipboard(clip))
            }
            else -> {
                BridgeLog.w("Net", "Unhandled control message type: 0x${"%02x".format(msgType)}")
            }
        }
    }

    private fun observeOutgoingMessages() {
        lifecycleScope.launch(Dispatchers.IO) {
            BridgeEventBus.outgoingControl.collect { msg ->
                val ch = activeChannel.get() ?: return@collect
                if (msg is OutgoingControlMessage.Raw) {
                    try {
                        ch.send(msg.msgType, msg.payload)
                    } catch (e: IOException) {
                        BridgeLog.w("Net", "Send failed: ${e.message}")
                        ch.close()
                    }
                }
            }
        }
    }

    private fun onMirrorRequestedFromMac() {
        // Seamless zero-prompt resume if MediaProjection is already alive in standby!
        if (MirrorService.isReadyInStandby || MirrorService.isStreaming) {
            BridgeLog.i("Mirror", "Resuming standby MediaProjection instantly (no prompt required)")
            BridgeEventBus.emitIncoming(IncomingControlEvent.MirrorRequest)
            return
        }

        val macName = keyStore.getPairedMac()?.macName ?: "your Mac"
        BridgeEventBus.setMirrorState("awaiting_consent")
        val consentIntent = Intent().apply {
            setClassName(packageName, "com.droidbridge.app.mirror.MirrorConsentActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this,
            REQ_MIRROR_CONSENT,
            consentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_MIRROR_PROMPT)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Start mirroring to $macName")
            .setContentText("Tap once to enable entire-screen mirroring (stays ready for instant reconnect).")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        notificationManager.notify(NOTIF_ID_MIRROR_PROMPT, notif)
    }

    private fun launchAppByPackage(pkg: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launchIntent) }
    }

    private fun applyClipboardFromMac(text: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("DroidBridge", text))
            }
        }
    }

    private fun registerNetworkCallback() {
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                BridgeLog.i("Net", "Wi-Fi available; triggering fast discovery")
                reconnectSignal.trySend(Unit)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val sig = linkProperties.linkAddresses.joinToString(",") { it.address.hostAddress ?: "" }
                if (sig != lastLinkSignature && lastLinkSignature != null) {
                    BridgeLog.i("Net", "Wi-Fi IP/network changed; re-discovering Mac")
                    activeChannel.getAndSet(null)?.close()
                    reconnectSignal.trySend(Unit)
                }
                lastLinkSignature = sig
            }

            override fun onLost(network: Network) {
                BridgeLog.w("Net", "Wi-Fi lost; closing active socket")
                activeChannel.getAndSet(null)?.close()
            }
        }
        networkCallback = cb
        runCatching { connectivityManager.registerNetworkCallback(req, cb) }
    }

    private fun registerBatteryReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                sendCurrentBatteryStatus(intent)
            }
        }
        batteryReceiver = receiver
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun sendCurrentBatteryStatus(batteryIntent: Intent? = null) {
        val intent = batteryIntent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else null
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            BridgeEventBus.sendControl(
                Protocol.MSG_DEVICE_STATUS,
                Protocol.encodeJson(DeviceStatusPayload(pct, charging))
            )
        }
    }

    private fun buildHelloPayload(): HelloPayload {
        val name = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifBlank { "Android" }
        return HelloPayload(
            deviceId = keyStore.getOrCreateDeviceId(),
            deviceName = name,
            appVersion = BuildConfig.VERSION_NAME,
            protocolVersion = Protocol.PROTOCOL_VERSION,
            capabilities = listOf("notifications", "mirror", "mirror_standby", "input", "clipboard", "calls")
        )
    }

    private fun createNotificationChannels() {
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            "DroidBridge Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent status for the encrypted connection to your Mac."
            setShowBadge(false)
        }
        val mirrorPromptChannel = NotificationChannel(
            CHANNEL_MIRROR_PROMPT,
            "Mirror Requests",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Prompts to approve screen mirroring requested by your Mac."
        }
        notificationManager.createNotificationChannel(statusChannel)
        notificationManager.createNotificationChannel(mirrorPromptChannel)
    }

    private fun buildStatusNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DroidBridge")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startAsForeground(text: String) {
        val notif = buildStatusNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID_STATUS,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID_STATUS, notif)
        }
    }

    private fun updateForegroundNotification(text: String) {
        notificationManager.notify(NOTIF_ID_STATUS, buildStatusNotification(text))
    }

    companion object {
        const val ACTION_START = "com.droidbridge.app.ACTION_START"
        const val ACTION_STOP = "com.droidbridge.app.ACTION_STOP"
        const val ACTION_RECONNECT = "com.droidbridge.app.ACTION_RECONNECT"

        private const val CHANNEL_STATUS = "droidbridge_status"
        private const val CHANNEL_MIRROR_PROMPT = "droidbridge_mirror_prompt"
        private const val NOTIF_ID_STATUS = 1001
        const val NOTIF_ID_MIRROR_PROMPT = 1002
        private const val REQ_MIRROR_CONSENT = 2001

        @Volatile
        var instance: BridgeConnectionService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, BridgeConnectionService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun reconnect(context: Context) {
            val intent = Intent(context, BridgeConnectionService::class.java).apply {
                action = ACTION_RECONNECT
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeConnectionService::class.java))
        }
    }
}
