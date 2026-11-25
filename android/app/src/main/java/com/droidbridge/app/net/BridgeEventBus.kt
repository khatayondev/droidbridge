package com.droidbridge.app.net

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BridgeConnectionState {
    UNPAIRED,
    SEARCHING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

sealed class OutgoingControlMessage {
    data class Raw(val msgType: Byte, val payload: ByteArray) : OutgoingControlMessage()
}

sealed class IncomingControlEvent {
    data class NotifDismiss(val key: String) : IncomingControlEvent()
    data class NotifReply(val key: String, val text: String) : IncomingControlEvent()
    data class NotifAction(val key: String, val actionIndex: Int) : IncomingControlEvent()
    data class AppIconRequest(val pkg: String) : IncomingControlEvent()
    data object MirrorRequest : IncomingControlEvent()
    data object MirrorPause : IncomingControlEvent()
    data object MirrorStop : IncomingControlEvent()
    data object VideoKeyframeRequest : IncomingControlEvent()
    data class MirrorQuality(val payload: MirrorQualityPayload) : IncomingControlEvent()
    data class AppLaunch(val pkg: String) : IncomingControlEvent()
    data class InputTouch(val payload: InputTouchPayload) : IncomingControlEvent()
    data class InputScroll(val payload: InputScrollPayload) : IncomingControlEvent()
    data class InputText(val payload: InputTextPayload) : IncomingControlEvent()
    data class InputKey(val payload: InputKeyPayload) : IncomingControlEvent()
    data class InputGlobal(val payload: InputGlobalPayload) : IncomingControlEvent()
    data class Clipboard(val payload: ClipboardPayload) : IncomingControlEvent()
    data class CallNotificationAction(val answer: Boolean) : IncomingControlEvent()
}

/**
 * In-process reactive bus decoupling BridgeConnectionService from NotificationListenerService,
 * MirrorService, ControlAccessibilityService, BridgeKeyboard IME, and the Compose UI.
 */
object BridgeEventBus {
    private val _connectionState = MutableStateFlow(BridgeConnectionState.UNPAIRED)
    val connectionState: StateFlow<BridgeConnectionState> = _connectionState.asStateFlow()

    private val _connectedMacName = MutableStateFlow<String?>(null)
    val connectedMacName: StateFlow<String?> = _connectedMacName.asStateFlow()

    private val _mirrorState = MutableStateFlow("idle")
    val mirrorState: StateFlow<String> = _mirrorState.asStateFlow()

    private val _outgoingControl = MutableSharedFlow<OutgoingControlMessage>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val outgoingControl: SharedFlow<OutgoingControlMessage> = _outgoingControl.asSharedFlow()

    private val _incomingControl = MutableSharedFlow<IncomingControlEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingControl: SharedFlow<IncomingControlEvent> = _incomingControl.asSharedFlow()

    private val _onConnectedSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val onConnectedSignal: SharedFlow<Unit> = _onConnectedSignal.asSharedFlow()

    fun setConnectionState(state: BridgeConnectionState, macName: String? = null) {
        _connectionState.value = state
        if (macName != null || state == BridgeConnectionState.UNPAIRED) {
            _connectedMacName.value = macName
        }
        if (state == BridgeConnectionState.CONNECTED) {
            _onConnectedSignal.tryEmit(Unit)
        }
    }

    fun setMirrorState(state: String, reason: String? = null) {
        _mirrorState.value = state
        sendControl(Protocol.MSG_MIRROR_STATE, Protocol.encodeJson(MirrorStatePayload(state, reason)))
    }

    fun sendControl(msgType: Byte, payload: ByteArray) {
        _outgoingControl.tryEmit(OutgoingControlMessage.Raw(msgType, payload))
    }

    fun emitIncoming(event: IncomingControlEvent) {
        _incomingControl.tryEmit(event)
    }
}
