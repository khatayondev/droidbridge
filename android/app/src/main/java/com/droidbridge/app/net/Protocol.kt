package com.droidbridge.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    val MAGIC = byteArrayOf(0x44, 0x42, 0x52, 0x31) // "DBR1"
    const val PROTOCOL_VERSION = 1
    const val SERVICE_TYPE = "_droidbridge._tcp."
    const val DEFAULT_PORT = 47321
    const val UDP_DISCOVERY_PORT = 47322

    const val MODE_PAIRING: Byte = 0x01
    const val MODE_CONTROL: Byte = 0x02
    const val MODE_VIDEO: Byte = 0x03

    const val HANDSHAKE_STATUS_OK: Byte = 0x00
    const val HANDSHAKE_STATUS_UNKNOWN_KEY: Byte = 0x01

    const val CHANNEL_CONTROL = "control"
    const val CHANNEL_VIDEO = "video"

    const val MAX_PAIRING_FRAME_BYTES = 65_536
    const val MAX_CONTROL_FRAME_BYTES = 4 * 1024 * 1024
    const val MAX_VIDEO_FRAME_BYTES = 16 * 1024 * 1024

    const val HEARTBEAT_INTERVAL_MS = 5_000L
    const val HEARTBEAT_TIMEOUT_MS = 15_000L

    // Control message types
    const val MSG_HELLO: Byte = 0x01
    const val MSG_PING: Byte = 0x02
    const val MSG_PONG: Byte = 0x03
    const val MSG_ERROR: Byte = 0x04

    const val MSG_NOTIF_POSTED: Byte = 0x10
    const val MSG_NOTIF_REMOVED: Byte = 0x11
    const val MSG_NOTIF_DISMISS: Byte = 0x12
    const val MSG_NOTIF_REPLY: Byte = 0x13
    const val MSG_NOTIF_ACTION: Byte = 0x14
    const val MSG_APP_ICON: Byte = 0x15
    const val MSG_APP_ICON_REQUEST: Byte = 0x16

    const val MSG_MIRROR_REQUEST: Byte = 0x20
    const val MSG_MIRROR_STATE: Byte = 0x21
    const val MSG_MIRROR_STOP: Byte = 0x22
    const val MSG_VIDEO_KEYFRAME_REQUEST: Byte = 0x23
    const val MSG_APP_LAUNCH: Byte = 0x24
    const val MSG_MIRROR_PAUSE: Byte = 0x25
    const val MSG_MIRROR_QUALITY: Byte = 0x26

    const val MSG_INPUT_TOUCH: Byte = 0x30
    const val MSG_INPUT_SCROLL: Byte = 0x31
    const val MSG_INPUT_TEXT: Byte = 0x32
    const val MSG_INPUT_KEY: Byte = 0x33
    const val MSG_INPUT_GLOBAL: Byte = 0x34

    const val MSG_CLIPBOARD: Byte = 0x40
    const val MSG_DEVICE_STATUS: Byte = 0x41
    const val MSG_DEVICE_TELEMETRY: Byte = 0x42

    // Call control (phone <-> Mac), public APIs only
    const val MSG_CALL_STATE: Byte = 0x50
    const val MSG_CALL_ANSWER: Byte = 0x51
    const val MSG_CALL_END: Byte = 0x52
    const val MSG_CALL_DIAL: Byte = 0x53
    const val MSG_CALL_RESULT: Byte = 0x54

    // Video/Audio channel binary message types (unsigned byte values)
    const val MSG_VIDEO_CONFIG: Byte = 0x80.toByte()
    const val MSG_VIDEO_FRAME: Byte = 0x81.toByte()
    const val MSG_AUDIO_CONFIG: Byte = 0x82.toByte()
    const val MSG_AUDIO_FRAME: Byte = 0x83.toByte()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    inline fun <reified T> encodeJson(value: T): ByteArray =
        json.encodeToString(value).toByteArray(Charsets.UTF_8)

    inline fun <reified T> decodeJson(bytes: ByteArray): T =
        json.decodeFromString(bytes.toString(Charsets.UTF_8))
}

// --- Pairing DTOs ---

@Serializable
data class PairRequestPayload(
    val type: String = "pair.request",
    val deviceId: String,
    val deviceName: String,
    val phoneStaticPub: String,
    val mac: String
)

@Serializable
data class PairResponsePayload(
    val type: String,
    val macDeviceId: String? = null,
    val macName: String? = null,
    val reason: String? = null
)

// --- Control DTOs ---

@Serializable
data class HelloPayload(
    val deviceId: String,
    val deviceName: String,
    val appVersion: String,
    val protocolVersion: Int = Protocol.PROTOCOL_VERSION,
    val capabilities: List<String> = emptyList()
)

@Serializable
data class PingPongPayload(
    val t: Long
)

@Serializable
data class ErrorPayload(
    val code: String,
    val message: String
)

@Serializable
data class NotifMessage(
    val sender: String,
    val text: String,
    val t: Long
)

@Serializable
data class NotifAction(
    val index: Int,
    val title: String,
    val hasRemoteInput: Boolean
)

@Serializable
data class NotifPostedPayload(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val subText: String? = null,
    val conversationTitle: String? = null,
    val messages: List<NotifMessage> = emptyList(),
    val postTime: Long,
    val category: String? = null,
    val isGroupConversation: Boolean = false,
    val actions: List<NotifAction> = emptyList(),
    val largeIconPngBase64: String? = null
)

@Serializable
data class NotifKeyPayload(
    val key: String
)

@Serializable
data class NotifReplyPayload(
    val key: String,
    val text: String
)

@Serializable
data class NotifActionTriggerPayload(
    val key: String,
    val actionIndex: Int
)

@Serializable
data class AppIconPayload(
    val pkg: String,
    val appName: String,
    val pngBase64: String
)

@Serializable
data class AppIconRequestPayload(
    val pkg: String
)

@Serializable
data class MirrorStatePayload(
    val state: String, // "idle" | "awaiting_consent" | "streaming" | "error"
    val reason: String? = null
)

@Serializable
data class AppLaunchPayload(
    val pkg: String
)

@Serializable
data class MirrorQualityPayload(
    val maxLongSide: Int,
    val bitrateBps: Int,
    val fps: Int
)

@Serializable
data class InputTouchPayload(
    val phase: String, // "down" | "move" | "up"
    val x: Double,
    val y: Double,
    val t: Long
)

@Serializable
data class InputScrollPayload(
    val dx: Double,
    val dy: Double,
    val x: Double,
    val y: Double
)

@Serializable
data class InputTextPayload(
    val text: String
)

@Serializable
data class InputKeyPayload(
    val key: String // "enter"|"backspace"|"tab"|"escape"|"left"|"right"|"up"|"down"
)

@Serializable
data class InputGlobalPayload(
    val action: String // "back"|"home"|"recents"|"notifications"|"quick_settings"|"lock"
)

@Serializable
data class ClipboardPayload(
    val text: String
)

@Serializable
data class DeviceStatusPayload(
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null
)

@Serializable
data class DeviceTelemetryPayload(
    val timestampMs: Long,
    val deviceModel: String,
    val socModel: String,
    val androidVersion: String,
    val cpuCores: Int,
    val totalRamMb: Int,
    val appCpuPercent: Double,
    val appCpuCorePercent: Double,
    val appPssMb: Double,
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val batteryTempC: Double? = null,
    val batteryCurrentMa: Double? = null,
    val batteryPowerW: Double? = null,
    val thermalStatus: String = "none",
    val mirrorState: String = "idle",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val encodedFps: Double = 0.0,
    val videoBitrateKbps: Double = 0.0,
    val avgEncryptUs: Double = 0.0,
    val avgSocketWriteMs: Double = 0.0,
    val droppedFrames: Long = 0L
)

@Serializable
data class CallStatePayload(
    val state: String, // "idle" | "ringing" | "active"
    val name: String? = null,
    val number: String? = null,
    val key: String? = null,
    val startTimeMs: Long = 0L
)

@Serializable
data class CallDialPayload(
    val number: String
)

@Serializable
data class CallResultPayload(
    val action: String, // "answer" | "end" | "dial"
    val ok: Boolean,
    val message: String? = null
)

// --- Binary Video & Audio Codecs ---

data class VideoConfigPayload(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val sps: ByteArray,
    val pps: ByteArray
) {
    fun encode(): ByteArray {
        val totalSize = 2 + 2 + 2 + 4 + sps.size + 4 + pps.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        buf.putShort(rotation.toShort())
        buf.putInt(sps.size)
        buf.put(sps)
        buf.putInt(pps.size)
        buf.put(pps)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): VideoConfigPayload {
            require(bytes.size >= 14) { "VideoConfig too short: ${bytes.size}" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val width = buf.short.toInt() and 0xFFFF
            val height = buf.short.toInt() and 0xFFFF
            val rotation = buf.short.toInt() and 0xFFFF
            val spsLen = buf.int
            require(spsLen >= 0 && spsLen <= buf.remaining() - 4) { "Invalid SPS length: $spsLen" }
            val sps = ByteArray(spsLen)
            buf.get(sps)
            val ppsLen = buf.int
            require(ppsLen >= 0 && ppsLen == buf.remaining()) { "Invalid PPS length: $ppsLen" }
            val pps = ByteArray(ppsLen)
            buf.get(pps)
            return VideoConfigPayload(width, height, rotation, sps, pps)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoConfigPayload) return false
        return width == other.width && height == other.height && rotation == other.rotation &&
            sps.contentEquals(other.sps) && pps.contentEquals(other.pps)
    }

    override fun hashCode(): Int {
        var r = width
        r = 31 * r + height
        r = 31 * r + rotation
        r = 31 * r + sps.contentHashCode()
        r = 31 * r + pps.contentHashCode()
        return r
    }
}

data class VideoFramePayload(
    val ptsMicros: Long,
    val flags: Int,
    val nalData: ByteArray
) {
    val isKeyframe: Boolean get() = (flags and FLAG_KEYFRAME) != 0

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(8 + 1 + nalData.size).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(ptsMicros)
        buf.put(flags.toByte())
        buf.put(nalData)
        return buf.array()
    }

    companion object {
        const val FLAG_KEYFRAME = 0x01

        fun decode(bytes: ByteArray): VideoFramePayload {
            require(bytes.size >= 9) { "VideoFrame too short: ${bytes.size}" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val pts = buf.long
            val flags = buf.get().toInt() and 0xFF
            val nalData = ByteArray(buf.remaining())
            buf.get(nalData)
            return VideoFramePayload(pts, flags, nalData)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoFramePayload) return false
        return ptsMicros == other.ptsMicros && flags == other.flags && nalData.contentEquals(other.nalData)
    }

    override fun hashCode(): Int {
        var r = ptsMicros.hashCode()
        r = 31 * r + flags
        r = 31 * r + nalData.contentHashCode()
        return r
    }
}

data class AudioConfigPayload(
    val sampleRate: Int,
    val channels: Int,
    val audioSpecificConfig: ByteArray
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(4 + 1 + 2 + audioSpecificConfig.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(sampleRate)
        buf.put(channels.toByte())
        buf.putShort(audioSpecificConfig.size.toShort())
        buf.put(audioSpecificConfig)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): AudioConfigPayload {
            require(bytes.size >= 7) { "AudioConfig too short" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val sampleRate = buf.int
            val channels = buf.get().toInt() and 0xFF
            val ascLen = buf.short.toInt() and 0xFFFF
            require(ascLen == buf.remaining()) { "Invalid ASC length" }
            val asc = ByteArray(ascLen)
            buf.get(asc)
            return AudioConfigPayload(sampleRate, channels, asc)
        }
    }
}

data class AudioFramePayload(
    val ptsMicros: Long,
    val aacData: ByteArray
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(8 + aacData.size).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(ptsMicros)
        buf.put(aacData)
        return buf.array()
    }

    companion object {
        fun decode(bytes: ByteArray): AudioFramePayload {
            require(bytes.size >= 8) { "AudioFrame too short" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val pts = buf.long
            val data = ByteArray(buf.remaining())
            buf.get(data)
            return AudioFramePayload(pts, data)
        }
    }
}
