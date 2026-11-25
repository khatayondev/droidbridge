import Foundation

public enum ProtocolConstants {
    public static let magic = Data([0x44, 0x42, 0x52, 0x31]) // "DBR1"
    public static let protocolVersion = 1
    public static let bonjourType = "_droidbridge._tcp"
    public static let defaultPort: UInt16 = 47321
    public static let udpDiscoveryPort: UInt16 = 47322

    public static let modePairing: UInt8 = 0x01
    public static let modeControl: UInt8 = 0x02
    public static let modeVideo: UInt8 = 0x03

    public static let handshakeStatusOk: UInt8 = 0x00
    public static let handshakeStatusUnknownKey: UInt8 = 0x01

    public static let channelControl = "control"
    public static let channelVideo = "video"

    public static let maxPairingFrameBytes = 65_536
    public static let maxControlFrameBytes = 4 * 1024 * 1024
    public static let maxVideoFrameBytes = 16 * 1024 * 1024

    public static let heartbeatIntervalSec: TimeInterval = 5.0
    public static let heartbeatTimeoutSec: TimeInterval = 15.0

    // Control message types
    public static let msgHello: UInt8 = 0x01
    public static let msgPing: UInt8 = 0x02
    public static let msgPong: UInt8 = 0x03
    public static let msgError: UInt8 = 0x04

    public static let msgNotifPosted: UInt8 = 0x10
    public static let msgNotifRemoved: UInt8 = 0x11
    public static let msgNotifDismiss: UInt8 = 0x12
    public static let msgNotifReply: UInt8 = 0x13
    public static let msgNotifAction: UInt8 = 0x14
    public static let msgAppIcon: UInt8 = 0x15
    public static let msgAppIconRequest: UInt8 = 0x16

    public static let msgMirrorRequest: UInt8 = 0x20
    public static let msgMirrorState: UInt8 = 0x21
    public static let msgMirrorStop: UInt8 = 0x22
    public static let msgVideoKeyframeRequest: UInt8 = 0x23
    public static let msgAppLaunch: UInt8 = 0x24
    public static let msgMirrorPause: UInt8 = 0x25
    public static let msgMirrorQuality: UInt8 = 0x26

    public static let msgInputTouch: UInt8 = 0x30
    public static let msgInputScroll: UInt8 = 0x31
    public static let msgInputText: UInt8 = 0x32
    public static let msgInputKey: UInt8 = 0x33
    public static let msgInputGlobal: UInt8 = 0x34

    public static let msgClipboard: UInt8 = 0x40
    public static let msgDeviceStatus: UInt8 = 0x41
    public static let msgDeviceTelemetry: UInt8 = 0x42

    // Call control (phone <-> Mac)
    public static let msgCallState: UInt8 = 0x50
    public static let msgCallAnswer: UInt8 = 0x51
    public static let msgCallEnd: UInt8 = 0x52
    public static let msgCallDial: UInt8 = 0x53
    public static let msgCallResult: UInt8 = 0x54

    // Video/Audio channel binary message types
    public static let msgVideoConfig: UInt8 = 0x80
    public static let msgVideoFrame: UInt8 = 0x81
    public static let msgAudioConfig: UInt8 = 0x82
    public static let msgAudioFrame: UInt8 = 0x83
}

// MARK: - Pairing DTOs

public struct PairRequestPayload: Codable {
    public let type: String
    public let deviceId: String
    public let deviceName: String
    public let phoneStaticPub: String
    public let mac: String
}

public struct PairResponsePayload: Codable {
    public let type: String
    public let macDeviceId: String?
    public let macName: String?
    public let reason: String?

    public init(type: String, macDeviceId: String? = nil, macName: String? = nil, reason: String? = nil) {
        self.type = type
        self.macDeviceId = macDeviceId
        self.macName = macName
        self.reason = reason
    }
}

// MARK: - Control DTOs

public struct HelloPayload: Codable, Equatable {
    public let deviceId: String
    public let deviceName: String
    public let appVersion: String
    public let protocolVersion: Int
    public let capabilities: [String]
}

public struct PingPongPayload: Codable, Equatable {
    public let t: Int64
}

public struct ErrorPayload: Codable, Equatable {
    public let code: String
    public let message: String
}

public struct NotifMessage: Codable, Equatable {
    public let sender: String
    public let text: String
    public let t: Int64
}

public struct NotifAction: Codable, Equatable {
    public let index: Int
    public let title: String
    public let hasRemoteInput: Bool
}

public struct NotifPostedPayload: Codable, Equatable {
    public let key: String
    public let pkg: String
    public let appName: String
    public let title: String?
    public let text: String?
    public let subText: String?
    public let conversationTitle: String?
    public let messages: [NotifMessage]
    public let postTime: Int64
    public let category: String?
    public let isGroupConversation: Bool
    public let actions: [NotifAction]
    public let largeIconPngBase64: String?
}

public struct NotifKeyPayload: Codable, Equatable {
    public let key: String
}

public struct NotifReplyPayload: Codable, Equatable {
    public let key: String
    public let text: String
}

public struct NotifActionTriggerPayload: Codable, Equatable {
    public let key: String
    public let actionIndex: Int
}

public struct AppIconPayload: Codable, Equatable {
    public let pkg: String
    public let appName: String
    public let pngBase64: String
}

public struct AppIconRequestPayload: Codable, Equatable {
    public let pkg: String
}

public struct EmptyPayload: Codable, Equatable {
    public init() {}
}

public struct MirrorStatePayload: Codable, Equatable {
    public let state: String
    public let reason: String?
}

public struct AppLaunchPayload: Codable, Equatable {
    public let pkg: String
}

public struct MirrorQualityPayload: Codable, Equatable {
    public let maxLongSide: Int
    public let bitrateBps: Int
    public let fps: Int

    public init(maxLongSide: Int, bitrateBps: Int, fps: Int) {
        self.maxLongSide = maxLongSide
        self.bitrateBps = bitrateBps
        self.fps = fps
    }
}

public struct InputTouchPayload: Codable, Equatable {
    public let phase: String
    public let x: Double
    public let y: Double
    public let t: Int64
}

public struct InputScrollPayload: Codable, Equatable {
    public let dx: Double
    public let dy: Double
    public let x: Double
    public let y: Double
}

public struct InputTextPayload: Codable, Equatable {
    public let text: String
}

public struct InputKeyPayload: Codable, Equatable {
    public let key: String
}

public struct InputGlobalPayload: Codable, Equatable {
    public let action: String
}

public struct ClipboardPayload: Codable, Equatable {
    public let text: String
}

public struct DeviceStatusPayload: Codable, Equatable {
    public let batteryLevel: Int?
    public let isCharging: Bool?
}

public struct DeviceTelemetryPayload: Codable, Equatable {
    public let timestampMs: Int64
    public let deviceModel: String
    public let socModel: String
    public let androidVersion: String
    public let cpuCores: Int
    public let totalRamMb: Int
    public let appCpuPercent: Double
    public let appCpuCorePercent: Double
    public let appPssMb: Double
    public let batteryPercent: Int?
    public let isCharging: Bool?
    public let batteryTempC: Double?
    public let batteryCurrentMa: Double?
    public let batteryPowerW: Double?
    public let thermalStatus: String
    public let mirrorState: String
    public let videoWidth: Int
    public let videoHeight: Int
    public let encodedFps: Double
    public let videoBitrateKbps: Double
    public let avgEncryptUs: Double
    public let avgSocketWriteMs: Double
    public let droppedFrames: Int64
}

public struct CallStatePayload: Codable, Equatable {
    public let state: String // "idle" | "ringing" | "active"
    public let name: String?
    public let number: String?
    public let key: String?
    public let startTimeMs: Int64?

    public init(state: String, name: String?, number: String?, key: String?, startTimeMs: Int64?) {
        self.state = state
        self.name = name
        self.number = number
        self.key = key
        self.startTimeMs = startTimeMs
    }
}

public struct CallDialPayload: Codable, Equatable {
    public let number: String
}

public struct CallResultPayload: Codable, Equatable {
    public let action: String
    public let ok: Bool
    public let message: String?
}

// MARK: - Binary Video / Audio Codecs

public enum BinaryPayloadError: Error {
    case truncated
    case invalidLength
}

public struct VideoConfigPayload: Equatable {
    public let width: UInt16
    public let height: UInt16
    public let rotation: UInt16
    public let sps: Data
    public let pps: Data

    public init(width: UInt16, height: UInt16, rotation: UInt16, sps: Data, pps: Data) {
        self.width = width
        self.height = height
        self.rotation = rotation
        self.sps = sps
        self.pps = pps
    }

    public func encode() -> Data {
        var out = Data(capacity: 14 + sps.count + pps.count)
        out.appendBigEndian(width)
        out.appendBigEndian(height)
        out.appendBigEndian(rotation)
        out.appendBigEndian(UInt32(sps.count))
        out.append(sps)
        out.appendBigEndian(UInt32(pps.count))
        out.append(pps)
        return out
    }

    public static func decode(_ data: Data) throws -> VideoConfigPayload {
        guard data.count >= 14 else { throw BinaryPayloadError.truncated }
        var offset = 0
        let width: UInt16 = try data.readBigEndian(at: &offset)
        let height: UInt16 = try data.readBigEndian(at: &offset)
        let rotation: UInt16 = try data.readBigEndian(at: &offset)
        let spsLen: UInt32 = try data.readBigEndian(at: &offset)
        guard Int(spsLen) <= data.count - offset - 4 else { throw BinaryPayloadError.invalidLength }
        let sps = data.subdata(in: offset..<(offset + Int(spsLen)))
        offset += Int(spsLen)
        let ppsLen: UInt32 = try data.readBigEndian(at: &offset)
        guard Int(ppsLen) == data.count - offset else { throw BinaryPayloadError.invalidLength }
        let pps = data.subdata(in: offset..<(offset + Int(ppsLen)))
        return VideoConfigPayload(width: width, height: height, rotation: rotation, sps: sps, pps: pps)
    }
}

public struct VideoFramePayload: Equatable {
    public static let flagKeyframe: UInt8 = 0x01

    public let ptsMicros: UInt64
    public let flags: UInt8
    public let nalData: Data

    public var isKeyframe: Bool { (flags & Self.flagKeyframe) != 0 }

    public init(ptsMicros: UInt64, flags: UInt8, nalData: Data) {
        self.ptsMicros = ptsMicros
        self.flags = flags
        self.nalData = nalData
    }

    public func encode() -> Data {
        var out = Data(capacity: 9 + nalData.count)
        out.appendBigEndian(ptsMicros)
        out.append(flags)
        out.append(nalData)
        return out
    }

    public static func decode(_ data: Data) throws -> VideoFramePayload {
        guard data.count >= 9 else { throw BinaryPayloadError.truncated }
        var offset = 0
        let pts: UInt64 = try data.readBigEndian(at: &offset)
        let flags = data[data.startIndex + offset]
        offset += 1
        let nalData = data.subdata(in: offset..<data.count)
        return VideoFramePayload(ptsMicros: pts, flags: flags, nalData: nalData)
    }
}

public struct AudioConfigPayload: Equatable {
    public let sampleRate: UInt32
    public let channels: UInt8
    public let audioSpecificConfig: Data

    public init(sampleRate: UInt32, channels: UInt8, audioSpecificConfig: Data) {
        self.sampleRate = sampleRate
        self.channels = channels
        self.audioSpecificConfig = audioSpecificConfig
    }

    public func encode() -> Data {
        var out = Data(capacity: 7 + audioSpecificConfig.count)
        out.appendBigEndian(sampleRate)
        out.append(channels)
        out.appendBigEndian(UInt16(audioSpecificConfig.count))
        out.append(audioSpecificConfig)
        return out
    }

    public static func decode(_ data: Data) throws -> AudioConfigPayload {
        guard data.count >= 7 else { throw BinaryPayloadError.truncated }
        var offset = 0
        let sr: UInt32 = try data.readBigEndian(at: &offset)
        let ch = data[data.startIndex + offset]
        offset += 1
        let ascLen: UInt16 = try data.readBigEndian(at: &offset)
        guard Int(ascLen) == data.count - offset else { throw BinaryPayloadError.invalidLength }
        let asc = data.subdata(in: offset..<data.count)
        return AudioConfigPayload(sampleRate: sr, channels: ch, audioSpecificConfig: asc)
    }
}

public struct AudioFramePayload: Equatable {
    public let ptsMicros: UInt64
    public let aacData: Data

    public init(ptsMicros: UInt64, aacData: Data) {
        self.ptsMicros = ptsMicros
        self.aacData = aacData
    }

    public func encode() -> Data {
        var out = Data(capacity: 8 + aacData.count)
        out.appendBigEndian(ptsMicros)
        out.append(aacData)
        return out
    }

    public static func decode(_ data: Data) throws -> AudioFramePayload {
        guard data.count >= 8 else { throw BinaryPayloadError.truncated }
        var offset = 0
        let pts: UInt64 = try data.readBigEndian(at: &offset)
        let aac = data.subdata(in: offset..<data.count)
        return AudioFramePayload(ptsMicros: pts, aacData: aac)
    }
}

// MARK: - Big-Endian Data Helpers

public extension Data {
    mutating func appendBigEndian<T: FixedWidthInteger>(_ value: T) {
        var be = value.bigEndian
        Swift.withUnsafeBytes(of: &be) { append(contentsOf: $0) }
    }

    func readBigEndian<T: FixedWidthInteger>(at offset: inout Int) throws -> T {
        let size = MemoryLayout<T>.size
        guard offset + size <= count else { throw BinaryPayloadError.truncated }
        let slice = subdata(in: offset..<(offset + size))
        offset += size
        return slice.withUnsafeBytes { ptr in
            T(bigEndian: ptr.loadUnaligned(as: T.self))
        }
    }
}
