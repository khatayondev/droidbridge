package com.droidbridge.app.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.droidbridge.app.calls.CallController
import com.droidbridge.app.net.AppIconPayload
import com.droidbridge.app.net.BridgeConnectionState
import com.droidbridge.app.net.BridgeEventBus
import com.droidbridge.app.net.BridgeLog
import com.droidbridge.app.net.ErrorPayload
import com.droidbridge.app.net.IncomingControlEvent
import com.droidbridge.app.net.NotifAction
import com.droidbridge.app.net.NotifKeyPayload
import com.droidbridge.app.net.NotifMessage
import com.droidbridge.app.net.NotifPostedPayload
import com.droidbridge.app.net.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * Captures posted/removed Android notifications and executes Mac-triggered reply, action,
 * and dismiss operations via public NotificationListenerService APIs.
 *
 * SMS is handled automatically via the default SMS app's notifications (no READ_SMS/SEND_SMS).
 * On Android 15+, OTP and sensitive content redacted by the OS is forwarded as received.
 */
class NotifyBridgeService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var filter: NotificationFilter
    private val sentAppIcons = Collections.synchronizedSet(HashSet<String>())

    override fun onCreate() {
        super.onCreate()
        filter = NotificationFilter(ownPackageName = packageName)
        observeIncomingCommands()
        observeReconnectFlush()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        BridgeLog.i("Notify", "NotificationListenerService connected")
        syncActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        scope.launch {
            processPostedNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        CallController.onCallNotificationRemoved(sbn.key)
        filter.onRemoved(sbn.key)
        BridgeEventBus.sendControl(
            Protocol.MSG_NOTIF_REMOVED,
            Protocol.encodeJson(NotifKeyPayload(sbn.key))
        )
    }

    private fun syncActiveNotifications() {
        scope.launch {
            val active = runCatching { activeNotifications }.getOrNull() ?: return@launch
            BridgeLog.i("Notify", "Syncing ${active.size} active notifications")
            for (sbn in active) {
                processPostedNotification(sbn)
            }
        }
    }

    private fun processPostedNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: Bundle.EMPTY
        val isGroupSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val isCall = notif.category == Notification.CATEGORY_CALL

        val messagingStyle = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif)
        }.getOrNull()

        val messages = messagingStyle?.messages?.mapNotNull { m ->
            val msgText = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sender = m.person?.name?.toString()?.takeIf { it.isNotBlank() } ?: ""
            NotifMessage(sender = sender, text = msgText, t = m.timestamp)
        }.orEmpty()

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: messagingStyle?.conversationTitle?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val plainText = bigText
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: messages.lastOrNull()?.text
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val conversationTitle = messagingStyle?.conversationTitle?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        if (filter.shouldSkip(
                pkg = sbn.packageName,
                isOngoing = sbn.isOngoing,
                isGroupSummary = isGroupSummary,
                title = title,
                text = plainText,
                hasMessages = messages.isNotEmpty(),
                isCall = isCall
            )
        ) {
            return
        }

        if (isCall) {
            // Call notifications are routed through the dedicated call channel so the Mac can
            // render Answer/Decline instead of a generic message banner.
            CallController.onCallNotification(title, plainText, sbn.key)
            return
        }

        val appName = resolveAppName(sbn.packageName)
        val actions = notif.actions?.mapIndexed { idx, act ->
            NotifAction(
                index = idx,
                title = act.title?.toString() ?: "Action",
                hasRemoteInput = !act.remoteInputs.isNullOrEmpty()
            )
        }.orEmpty()

        val largeIconBase64 = extractLargeIconBase64(notif)

        val payload = NotifPostedPayload(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appName,
            title = title,
            text = plainText,
            subText = subText,
            conversationTitle = conversationTitle,
            messages = messages,
            postTime = sbn.postTime,
            category = notif.category,
            isGroupConversation = messagingStyle?.isGroupConversation == true,
            actions = actions,
            largeIconPngBase64 = largeIconBase64
        )

        if (!filter.acceptIfChanged(payload)) return

        if (BridgeEventBus.connectionState.value == BridgeConnectionState.CONNECTED) {
            sendAppIconIfNeeded(sbn.packageName, appName)
            BridgeEventBus.sendControl(Protocol.MSG_NOTIF_POSTED, Protocol.encodeJson(payload))
            BridgeLog.dSensitive("Notify", "Forwarded notification from ${sbn.packageName}")
        } else {
            filter.enqueueOffline(payload)
        }
    }

    private fun observeReconnectFlush() {
        scope.launch {
            BridgeEventBus.onConnectedSignal.collect {
                // Re-send app icons on fresh connection (Mac caches by pkg, and can also request)
                sentAppIcons.clear()
                val queued = filter.drainOfflineQueue()
                if (queued.isNotEmpty()) {
                    BridgeLog.i("Notify", "Flushing ${queued.size} queued notifications")
                    for (item in queued) {
                        sendAppIconIfNeeded(item.pkg, item.appName)
                        BridgeEventBus.sendControl(Protocol.MSG_NOTIF_POSTED, Protocol.encodeJson(item))
                    }
                }
                syncActiveNotifications()
            }
        }
    }

    private fun observeIncomingCommands() {
        scope.launch {
            BridgeEventBus.incomingControl.collect { event ->
                when (event) {
                    is IncomingControlEvent.NotifDismiss -> handleDismiss(event.key)
                    is IncomingControlEvent.NotifReply -> handleReply(event.key, event.text)
                    is IncomingControlEvent.NotifAction -> handleAction(event.key, event.actionIndex)
                    is IncomingControlEvent.CallNotificationAction -> handleCallNotificationAction(event.answer)
                    is IncomingControlEvent.AppIconRequest -> {
                        sentAppIcons.remove(event.pkg)
                        sendAppIconIfNeeded(event.pkg, resolveAppName(event.pkg))
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Fallback path when ANSWER_PHONE_CALLS is unavailable: tap the dialer's own call action. */
    private fun handleCallNotificationAction(answer: Boolean) {
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        val sbn = active.firstOrNull { it.notification?.category == Notification.CATEGORY_CALL } ?: return
        val actions = sbn.notification.actions ?: return
        val keywords = if (answer) listOf("answer", "accept", "接听")
        else listOf("decline", "reject", "dismiss", "挂断")
        val action = actions.firstOrNull { act ->
            val title = act.title?.toString()?.lowercase().orEmpty()
            keywords.any { title.contains(it) }
        } ?: actions.getOrNull(if (answer) 0 else 1) ?: return
        runCatching { action.actionIntent?.send() }
            .onSuccess { BridgeLog.i("Calls", "Triggered call action '${action.title}'") }
            .onFailure { BridgeLog.e("Calls", "Call notification action failed", it) }
    }

    private fun handleDismiss(key: String) {
        val target = findActiveNotification(key)
        if (target == null) {
            sendNotifError("notif_not_found", "Cannot dismiss notification: already removed")
            return
        }
        runCatching { cancelNotification(key) }
            .onFailure { sendNotifError("dismiss_failed", it.message ?: "Dismiss failed") }
    }

    private fun handleReply(key: String, text: String) {
        val sbn = findActiveNotification(key)
        if (sbn == null) {
            sendNotifError("notif_not_found", "Cannot reply: notification is no longer active")
            return
        }
        val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (action == null || action.actionIntent == null) {
            sendNotifError("no_reply_action", "Notification does not support inline reply")
            return
        }
        try {
            val remoteInputs = action.remoteInputs
            val fillInIntent = Intent()
            val results = Bundle()
            for (ri in remoteInputs) {
                results.putCharSequence(ri.resultKey, text)
            }
            RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, results)
            action.actionIntent.send(this, 0, fillInIntent)
            BridgeLog.i("Notify", "Sent inline reply for ${sbn.packageName}")
        } catch (e: Exception) {
            BridgeLog.e("Notify", "Inline reply failed", e)
            sendNotifError("reply_failed", e.message ?: "Reply failed")
        }
    }

    private fun handleAction(key: String, actionIndex: Int) {
        val sbn = findActiveNotification(key)
        if (sbn == null) {
            sendNotifError("notif_not_found", "Cannot invoke action: notification is no longer active")
            return
        }
        val actions = sbn.notification.actions
        val action = actions?.getOrNull(actionIndex)
        if (action == null || action.actionIntent == null) {
            sendNotifError("action_not_found", "Action index $actionIndex not found")
            return
        }
        try {
            action.actionIntent.send()
            BridgeLog.i("Notify", "Triggered action #$actionIndex for ${sbn.packageName}")
        } catch (e: Exception) {
            BridgeLog.e("Notify", "Action trigger failed", e)
            sendNotifError("action_failed", e.message ?: "Action failed")
        }
    }

    private fun findActiveNotification(key: String): StatusBarNotification? =
        runCatching { activeNotifications?.firstOrNull { it.key == key } }.getOrNull()

    private fun sendNotifError(code: String, message: String) {
        BridgeLog.w("Notify", "$code: $message")
        BridgeEventBus.sendControl(
            Protocol.MSG_ERROR,
            Protocol.encodeJson(ErrorPayload(code, message))
        )
    }

    private fun resolveAppName(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    private fun sendAppIconIfNeeded(pkg: String, appName: String) {
        if (!sentAppIcons.add(pkg)) return
        val pngBase64 = runCatching {
            val drawable = packageManager.getApplicationIcon(pkg)
            drawableToPngBase64(drawable, maxSizePx = 128)
        }.getOrNull() ?: return

        BridgeEventBus.sendControl(
            Protocol.MSG_APP_ICON,
            Protocol.encodeJson(AppIconPayload(pkg = pkg, appName = appName, pngBase64 = pngBase64))
        )
    }

    private fun extractLargeIconBase64(notif: Notification): String? = runCatching {
        val icon = notif.getLargeIcon() ?: return null
        val drawable = icon.loadDrawable(this) ?: return null
        drawableToPngBase64(drawable, maxSizePx = 96)
    }.getOrNull()

    private fun drawableToPngBase64(drawable: Drawable, maxSizePx: Int): String {
        val rawBitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxSizePx
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxSizePx
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }

        val scaled = if (rawBitmap.width > maxSizePx || rawBitmap.height > maxSizePx) {
            val scale = maxSizePx.toFloat() / maxOf(rawBitmap.width, rawBitmap.height)
            val nw = (rawBitmap.width * scale).toInt().coerceAtLeast(1)
            val nh = (rawBitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rawBitmap, nw, nh, true)
        } else {
            rawBitmap
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
        if (scaled !== rawBitmap) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
