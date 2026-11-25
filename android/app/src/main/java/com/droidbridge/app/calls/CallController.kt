package com.droidbridge.app.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.droidbridge.app.net.BridgeEventBus
import com.droidbridge.app.net.BridgeLog
import com.droidbridge.app.net.CallResultPayload
import com.droidbridge.app.net.CallStatePayload
import com.droidbridge.app.net.IncomingControlEvent
import com.droidbridge.app.net.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Bridges Android telephony state to the paired Mac using only public, non-privileged APIs:
 *
 *  - `READ_PHONE_STATE`    → ringing / active / idle transitions (TelephonyCallback)
 *  - `ANSWER_PHONE_CALLS`  → `TelecomManager.acceptRingingCall()` / `endCall()`
 *  - `CALL_PHONE`          → `TelecomManager.placeCall()` (falls back to `Intent.ACTION_CALL`)
 *
 * Caller identity comes from the dialer's own call notification (see NotifyBridgeService), so no
 * `READ_CALL_LOG` or default-dialer role is required. Call audio is intentionally out of scope:
 * Android does not allow a third-party app to route voice-call audio.
 */
object CallController {
    private const val TAG = "Calls"

    @Volatile private var phase: String = "idle"
    @Volatile private var callerName: String? = null
    @Volatile private var callerNumber: String? = null
    @Volatile private var callKey: String? = null
    @Volatile private var startedAt: Long = 0L

    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var scope: CoroutineScope? = null

    /** Registers phone-state observation (idempotent) and flushes state on every reconnect. */
    fun start(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        registerPhoneState(app)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            s.launch {
                BridgeEventBus.onConnectedSignal.collect { emit() }
            }
        }
    }

    private fun registerPhoneState(context: Context) {
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) {
            BridgeLog.i(TAG, "READ_PHONE_STATE not granted; using notification-driven call state only")
            return
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val executor = Executor { r -> main.post(r) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleTelephonyState(state)
            }
            runCatching { tm.registerTelephonyCallback(executor, callback) }
                .onFailure { BridgeLog.w(TAG, "TelephonyCallback registration failed: ${it.message}") }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    if (!phoneNumber.isNullOrBlank()) callerNumber = phoneNumber
                    handleTelephonyState(state)
                }
            }
            @Suppress("DEPRECATION")
            runCatching { tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
                .onFailure { BridgeLog.w(TAG, "PhoneStateListener registration failed: ${it.message}") }
        }
    }

    private fun handleTelephonyState(state: Int) {
        val mapped = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "active"
            else -> "idle"
        }
        if (mapped == phase) return
        phase = mapped
        if (mapped == "active" && startedAt == 0L) startedAt = System.currentTimeMillis()
        if (mapped == "idle") {
            startedAt = 0L
            callKey = null
        }
        emit()
    }

    /** Called by the notification listener when the dialer posts a CATEGORY_CALL notification. */
    fun onCallNotification(title: String?, text: String?, key: String) {
        callKey = key
        val (name, number) = classifyCaller(title, text)
        if (name != null) callerName = name
        if (number != null) callerNumber = number
        if (phase == "idle") {
            phase = "ringing"
            startedAt = 0L
        }
        emit()
    }

    fun onCallNotificationRemoved(key: String) {
        if (callKey != null && callKey != key) return
        callKey = null
        // Only fall back to idle while still ringing: an answered call replaces the ringing
        // notification with an in-call one, and phone state has already reported "active".
        if (phase == "ringing") {
            phase = "idle"
            startedAt = 0L
            callerName = null
            callerNumber = null
            emit()
        }
    }

    fun answer(context: Context) {
        val tm = telecom(context)
        val canAccept = granted(context, Manifest.permission.ANSWER_PHONE_CALLS)
        if (tm != null && canAccept) {
            val err = runCatching { tm.acceptRingingCall() }.exceptionOrNull()
            if (err == null) {
                emitResult("answer", true, null)
                return
            }
            BridgeLog.w(TAG, "acceptRingingCall failed: ${err.message}")
        }
        if (callKey != null) {
            // Fallback: fire the dialer's own "Answer" notification action.
            BridgeEventBus.emitIncoming(IncomingControlEvent.CallNotificationAction(true))
            emitResult("answer", true, "fallback")
            return
        }
        emitResult(
            "answer",
            false,
            if (!canAccept) "ANSWER_PHONE_CALLS is not granted on the phone" else "No ringing call"
        )
    }

    fun end(context: Context) {
        val tm = telecom(context)
        val canAccept = granted(context, Manifest.permission.ANSWER_PHONE_CALLS)
        if (tm != null && canAccept) {
            val err = runCatching { tm.endCall() }.exceptionOrNull()
            if (err == null) {
                phase = "idle"
                startedAt = 0L
                emit()
                emitResult("end", true, null)
                return
            }
            BridgeLog.w(TAG, "endCall failed: ${err.message}")
        }
        if (callKey != null) {
            BridgeEventBus.emitIncoming(IncomingControlEvent.CallNotificationAction(false))
            emitResult("end", true, "fallback")
            return
        }
        emitResult(
            "end",
            false,
            if (!canAccept) "ANSWER_PHONE_CALLS is not granted on the phone" else "No active call"
        )
    }

    fun dial(context: Context, rawNumber: String) {
        val number = rawNumber.trim().filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (number.count { it.isDigit() } < 3) {
            emitResult("dial", false, "Enter a valid phone number")
            return
        }
        if (!granted(context, Manifest.permission.CALL_PHONE)) {
            emitResult("dial", false, "CALL_PHONE is not granted on the phone")
            return
        }
        val uri = Uri.fromParts("tel", number, null)
        // Prefer ACTION_CALL: it works for any app holding CALL_PHONE, whereas TelecomManager.placeCall
        // is gated to the default-dialer role on many OEM builds. Fall back to placeCall if needed.
        val actionErr = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.exceptionOrNull()
        if (actionErr == null) {
            emitResult("dial", true, null)
            return
        }
        BridgeLog.w(TAG, "ACTION_CALL failed: ${actionErr.message}; trying placeCall")
        val tm = telecom(context)
        if (tm != null) {
            val err = runCatching { tm.placeCall(uri, Bundle()) }.exceptionOrNull()
            if (err == null) {
                emitResult("dial", true, null)
                return
            }
            BridgeLog.w(TAG, "placeCall failed: ${err.message}")
            emitResult("dial", false, err.message ?: actionErr.message ?: "Could not place the call")
            return
        }
        emitResult("dial", false, actionErr.message ?: "Could not place the call")
    }

    private fun emit() {
        val idle = phase == "idle"
        val payload = CallStatePayload(
            state = phase,
            name = callerName,
            number = callerNumber,
            key = callKey,
            startTimeMs = startedAt
        )
        runCatching { BridgeEventBus.sendControl(Protocol.MSG_CALL_STATE, Protocol.encodeJson(payload)) }
        if (idle) {
            callerName = null
            callerNumber = null
        }
    }

    private fun classifyCaller(title: String?, text: String?): Pair<String?, String?> {
        val t = title?.trim().orEmpty()
        val body = text?.trim().orEmpty()
        val fromBody = Regex("""\+?\d[\d\s\-()]{4,}\d""").find(body)?.value?.trim()
        return when {
            t.isEmpty() -> null to fromBody
            looksLikeNumber(t) -> null to t
            else -> t to fromBody
        }
    }

    private fun looksLikeNumber(value: String): Boolean =
        value.isNotEmpty() && value.all {
            it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')'
        }

    private fun telecom(context: Context): TelecomManager? =
        runCatching { context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager }.getOrNull()

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun emitResult(action: String, ok: Boolean, message: String?) {
        BridgeLog.i(TAG, "Call action '$action' ok=$ok ${message ?: ""}")
        runCatching {
            BridgeEventBus.sendControl(
                Protocol.MSG_CALL_RESULT,
                Protocol.encodeJson(CallResultPayload(action, ok, message))
            )
        }
    }
}
