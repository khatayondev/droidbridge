import Foundation
import Network
import os

public protocol BridgeServerDelegate: AnyObject {
    func bridgeServer(_ server: BridgeServer, didConnectControl phone: PairedPhone, hello: HelloPayload)
    func bridgeServer(_ server: BridgeServer, didDisconnectControl phone: PairedPhone?)
    func bridgeServer(_ server: BridgeServer, didReceiveControlMessage type: UInt8, payload: Data)
    func bridgeServer(_ server: BridgeServer, didReceiveVideoMessage type: UInt8, payload: Data)
    func bridgeServerDidDisconnectVideo(_ server: BridgeServer)
}

public enum ConnectionState: Equatable {
    case stopped
    case listening(port: UInt16)
    case connected(phoneName: String)
}

/// Manages the Mac-side TCP server (`NWListener`), Bonjour `_droidbridge._tcp` advertisement,
/// UDP LAN discovery responder (port 47322), pairing handshake, E2EE X25519+HKDF+ChaCha20-Poly1305
/// session setup, streaming TCP frame reassembly, heartbeats, and channel routing.
public final class BridgeServer: ObservableObject {
    @Published public private(set) var state: ConnectionState = .stopped
    @Published public private(set) var listeningPort: UInt16 = ProtocolConstants.defaultPort
    @Published public private(set) var activePhone: PairedPhone?
    @Published public private(set) var batteryLevel: Int?
    @Published public private(set) var isCharging: Bool?

    public weak var delegate: BridgeServerDelegate?
    public var onRttMeasured: ((Double) -> Void)?

    private let logger = Logger(subsystem: "com.droidbridge.macos", category: "BridgeServer")
    private let store: KeychainStore
    private let pairingManager: PairingManager
    private let queue = DispatchQueue(label: "com.droidbridge.server", qos: .userInteractive)
    private var listener: NWListener?
    private var udpResponder: DiscoveryResponder?

    private var activeControlSession: EncryptedSession?
    private var activeVideoSession: EncryptedSession?
    private var heartbeatTimer: DispatchSourceTimer?

    public init(store: KeychainStore = .shared, pairingManager: PairingManager) {
        self.store = store
        self.pairingManager = pairingManager
    }

    public func start(preferredPort: UInt16 = ProtocolConstants.defaultPort, advertiseBonjour: Bool = true) {
        queue.async { [weak self] in
            self?.startOnQueue(preferredPort: preferredPort, advertiseBonjour: advertiseBonjour)
        }
    }

    public func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.stopHeartbeatTimer()
            self.udpResponder?.stop()
            self.udpResponder = nil
            self.activeControlSession?.close()
            self.activeControlSession = nil
            self.activeVideoSession?.close()
            self.activeVideoSession = nil
            self.listener?.cancel()
            self.listener = nil
            DispatchQueue.main.async {
                self.activePhone = nil
                self.state = .stopped
            }
        }
    }

    public func disconnectActivePhone() {
        queue.async { [weak self] in
            guard let self else { return }
            self.activeControlSession?.close()
            self.activeVideoSession?.close()
        }
    }

    public func sendControl<T: Encodable>(type: UInt8, payload: T) {
        guard let data = try? JSONEncoder().encode(payload) else { return }
        sendControlRaw(type: type, payload: data)
    }

    public func sendControlRaw(type: UInt8, payload: Data) {
        queue.async { [weak self] in
            self?.activeControlSession?.send(msgType: type, payload: payload)
        }
    }

    // MARK: - Listener Setup

    private func startOnQueue(preferredPort: UInt16, advertiseBonjour: Bool) {
        listener?.cancel()

        let params = NWParameters.tcp
        params.serviceClass = .interactiveVideo
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.noDelay = true
            tcp.enableKeepalive = true
            tcp.keepaliveIdle = 5
            tcp.keepaliveInterval = 2
            tcp.keepaliveCount = 3
        }

        let createdListener: NWListener
        if preferredPort > 0, let nwPort = NWEndpoint.Port(rawValue: preferredPort),
           let l = try? NWListener(using: params, on: nwPort) {
            createdListener = l
        } else if let l = try? NWListener(using: params) {
            createdListener = l
        } else {
            return
        }

        if advertiseBonjour {
            let txtDict: [String: String] = [
                "v": String(ProtocolConstants.protocolVersion),
                "id": store.getOrCreateDeviceId()
            ]
            let txtRecord = NWTXTRecord(txtDict)
            createdListener.service = NWListener.Service(
                name: pairingManager.macName,
                type: ProtocolConstants.bonjourType,
                domain: nil,
                txtRecord: txtRecord
            )
        }

        createdListener.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            switch newState {
            case .ready:
                let port = createdListener.port?.rawValue ?? preferredPort
                self.logger.info("Server listening on TCP port \(port)")
                if advertiseBonjour {
                    if self.udpResponder == nil {
                        let resp = DiscoveryResponder(macDeviceId: self.store.getOrCreateDeviceId(), tcpPort: port)
                        resp.start()
                        self.udpResponder = resp
                    } else {
                        self.udpResponder?.updateTcpPort(port)
                    }
                }
                DispatchQueue.main.async {
                    self.listeningPort = port
                    if self.activePhone == nil {
                        self.state = .listening(port: port)
                    }
                }
            case let .failed(error):
                self.logger.error("NWListener failed: \(error.localizedDescription)")
                if preferredPort != 0 {
                    self.startOnQueue(preferredPort: 0, advertiseBonjour: advertiseBonjour)
                }
            default:
                break
            }
        }

        createdListener.newConnectionHandler = { [weak self] conn in
            self?.handleIncomingConnection(conn)
        }

        self.listener = createdListener
        createdListener.start(queue: queue)
    }

    // MARK: - Connection Dispatch (Buffered Stream Reader — Immune to TCP Fragmentation)

    private func handleIncomingConnection(_ conn: NWConnection) {
        conn.start(queue: queue)
        let reader = BufferedConnectionReader(connection: conn)
        reader.readExact(5) { [weak self] data in
            guard let self, let data, data.count == 5,
                  data.prefix(4) == ProtocolConstants.magic else {
                conn.cancel()
                return
            }
            let mode = data[data.startIndex + 4]
            switch mode {
            case ProtocolConstants.modePairing:
                self.handlePairingConnection(conn, reader: reader)
            case ProtocolConstants.modeControl:
                self.handleEncryptedHandshake(conn, reader: reader, isVideo: false)
            case ProtocolConstants.modeVideo:
                self.handleEncryptedHandshake(conn, reader: reader, isVideo: true)
            default:
                conn.cancel()
            }
        }
    }

    private func handlePairingConnection(_ conn: NWConnection, reader: BufferedConnectionReader) {
        reader.readExact(4) { [weak self] lenData in
            guard let self, let lenData else { conn.cancel(); return }
            let len = lenData.withUnsafeBytes { UInt32(bigEndian: $0.loadUnaligned(as: UInt32.self)) }
            guard len > 0, Int(len) <= ProtocolConstants.maxPairingFrameBytes else {
                conn.cancel()
                return
            }
            reader.readExact(Int(len)) { payloadData in
                guard let payloadData,
                      let req = try? JSONDecoder().decode(PairRequestPayload.self, from: payloadData) else {
                    conn.cancel()
                    return
                }
                let resp = self.pairingManager.handlePairRequest(req)
                guard let respBytes = try? JSONEncoder().encode(resp) else {
                    conn.cancel()
                    return
                }
                var frame = Data(capacity: 4 + respBytes.count)
                frame.appendBigEndian(UInt32(respBytes.count))
                frame.append(respBytes)
                self.logger.info("Pairing request from '\(req.deviceName)' -> \(resp.type) (\(resp.reason ?? "ok"))")
                // Send clean TCP FIN (.finalMessage) and wait briefly before cancelling so
                // Android reliably reads pair.ok over Wi-Fi instead of getting a TCP RST!
                conn.send(content: frame, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
                    self?.queue.asyncAfter(deadline: .now() + 1.5) {
                        conn.cancel()
                    }
                })
            }
        }
    }

    private func handleEncryptedHandshake(_ conn: NWConnection, reader: BufferedConnectionReader, isVideo: Bool) {
        // Read remaining 64 bytes of client handshake: phoneStaticPub(32) + phoneEphPub(32)
        reader.readExact(64) { [weak self] data in
            guard let self, let data, data.count == 64 else {
                conn.cancel()
                return
            }
            let phoneStaticPub = Data(data.prefix(32))
            let phoneEphPub = Data(data.suffix(32))

            // Reject unknown / unpaired phones immediately (with explicit status byte + clean FIN)
            guard let trustedPhone = self.store.findPairedPhone(publicKey: phoneStaticPub) else {
                self.logger.warning("Rejected connection from unknown public key")
                var rejectHeader = Data(capacity: 5)
                rejectHeader.append(ProtocolConstants.magic)
                rejectHeader.append(ProtocolConstants.handshakeStatusUnknownKey)
                conn.send(content: rejectHeader, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
                    self?.queue.asyncAfter(deadline: .now() + 0.5) {
                        conn.cancel()
                    }
                })
                return
            }

            // Enforce single active phone policy (but allow the SAME phone to cleanly replace a stale socket!)
            if let existing = self.activeControlSession, existing.phone.deviceId != trustedPhone.deviceId {
                self.logger.warning("Rejected second phone while another phone is active")
                conn.cancel()
                return
            }

            let macStatic = self.store.getOrCreateStaticKeyPair()
            let macEph = BridgeCrypto.generateX25519KeyPair()
            let channelName = isVideo ? ProtocolConstants.channelVideo : ProtocolConstants.channelControl
            let maxBytes = isVideo ? ProtocolConstants.maxVideoFrameBytes : ProtocolConstants.maxControlFrameBytes

            guard let keys = try? BridgeCrypto.deriveSessionKeys(
                role: .macResponder,
                ownStaticPriv: macStatic.privateKey,
                ownEphPriv: macEph.privateKey,
                peerStaticPub: phoneStaticPub,
                peerEphPub: phoneEphPub,
                channelName: channelName
            ) else {
                conn.cancel()
                return
            }

            let cipher = FrameCipher(
                sendKey: keys.keyMacToPhone,
                recvKey: keys.keyPhoneToMac,
                maxFrameBytes: maxBytes
            )

            // Send 37-byte server handshake: "DBR1" + 0x00 + macEphPub(32)
            var serverHelloHeader = Data(capacity: 37)
            serverHelloHeader.append(ProtocolConstants.magic)
            serverHelloHeader.append(ProtocolConstants.handshakeStatusOk)
            serverHelloHeader.append(macEph.publicKey)

            conn.send(content: serverHelloHeader, completion: .contentProcessed { [weak self] err in
                guard let self, err == nil else {
                    conn.cancel()
                    return
                }
                let session = EncryptedSession(
                    connection: conn,
                    reader: reader,
                    cipher: cipher,
                    phone: trustedPhone,
                    isVideo: isVideo,
                    maxFrameBytes: maxBytes
                )
                self.completeEncryptedHello(session)
            })
        }
    }

    private func completeEncryptedHello(_ session: EncryptedSession) {
        // First encrypted frame from phone MUST be hello (0x01)
        session.readOneFrame { [weak self] result in
            guard let self else { session.close(); return }
            guard case let .success((msgType, payload)) = result,
                  msgType == ProtocolConstants.msgHello,
                  let peerHello = try? JSONDecoder().decode(HelloPayload.self, from: payload),
                  peerHello.protocolVersion == ProtocolConstants.protocolVersion else {
                self.logger.warning("Encrypted hello failed; closing connection")
                session.close()
                return
            }

            let localHello = HelloPayload(
                deviceId: self.store.getOrCreateDeviceId(),
                deviceName: self.pairingManager.macName,
                appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0",
                protocolVersion: ProtocolConstants.protocolVersion,
                capabilities: ["notifications", "mirror", "mirror_standby", "input", "clipboard", "calls"]
            )
            if let localHelloData = try? JSONEncoder().encode(localHello) {
                session.send(msgType: ProtocolConstants.msgHello, payload: localHelloData)
            }

            if session.isVideo {
                let prev = self.activeVideoSession
                self.activeVideoSession = session
                prev?.close()
                self.logger.info("Video channel connected from \(session.phone.deviceName)")
                session.startContinuousReadLoop(
                    onFrame: { [weak self] type, data in
                        guard let self else { return }
                        self.delegate?.bridgeServer(self, didReceiveVideoMessage: type, payload: data)
                    },
                    onClose: { [weak self] in
                        guard let self else { return }
                        if self.activeVideoSession === session {
                            self.activeVideoSession = nil
                            self.logger.info("Video channel closed")
                            self.delegate?.bridgeServerDidDisconnectVideo(self)
                        }
                    }
                )
            } else {
                let prev = self.activeControlSession
                self.activeControlSession = session
                prev?.close()
                self.startHeartbeatTimer()
                self.logger.info("Control channel connected from \(session.phone.deviceName)")
                DispatchQueue.main.async {
                    self.activePhone = session.phone
                    self.state = .connected(phoneName: session.phone.deviceName)
                }
                self.delegate?.bridgeServer(self, didConnectControl: session.phone, hello: peerHello)

                session.startContinuousReadLoop(
                    onFrame: { [weak self] type, data in
                        guard let self else { return }
                        self.handleControlFrame(session: session, type: type, payload: data)
                    },
                    onClose: { [weak self] in
                        guard let self else { return }
                        if self.activeControlSession === session {
                            let phone = session.phone
                            self.activeControlSession = nil
                            self.activeVideoSession?.close()
                            self.activeVideoSession = nil
                            self.stopHeartbeatTimer()
                            self.logger.info("Control channel closed")
                            DispatchQueue.main.async {
                                self.activePhone = nil
                                self.batteryLevel = nil
                                self.isCharging = nil
                                self.state = .listening(port: self.listeningPort)
                            }
                            self.delegate?.bridgeServer(self, didDisconnectControl: phone)
                        }
                    }
                )
            }
        }
    }

    private func handleControlFrame(session: EncryptedSession, type: UInt8, payload: Data) {
        switch type {
        case ProtocolConstants.msgPing:
            session.send(msgType: ProtocolConstants.msgPong, payload: payload)
        case ProtocolConstants.msgPong:
            if let pong = try? JSONDecoder().decode(PingPongPayload.self, from: payload) {
                let nowMs = Double(Date().timeIntervalSince1970 * 1000.0)
                let rtt = nowMs - Double(pong.t)
                if rtt >= 0 && rtt < 10_000 {
                    onRttMeasured?(rtt)
                }
            }
        case ProtocolConstants.msgDeviceStatus:
            if let status = try? JSONDecoder().decode(DeviceStatusPayload.self, from: payload) {
                DispatchQueue.main.async {
                    self.batteryLevel = status.batteryLevel
                    self.isCharging = status.isCharging
                }
            }
        default:
            delegate?.bridgeServer(self, didReceiveControlMessage: type, payload: payload)
        }
    }

    // MARK: - Heartbeats (5s ping, 15s silence timeout)

    private func startHeartbeatTimer() {
        stopHeartbeatTimer()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + ProtocolConstants.heartbeatIntervalSec,
                       repeating: ProtocolConstants.heartbeatIntervalSec)
        timer.setEventHandler { [weak self] in
            guard let self, let session = self.activeControlSession else { return }
            if Date().timeIntervalSince(session.lastActivity) > ProtocolConstants.heartbeatTimeoutSec {
                self.logger.warning("Control channel heartbeat timed out after 15s; closing")
                session.close()
                return
            }
            let ping = PingPongPayload(t: Int64(Date().timeIntervalSince1970 * 1000))
            if let data = try? JSONEncoder().encode(ping) {
                session.send(msgType: ProtocolConstants.msgPing, payload: data)
            }
        }
        timer.resume()
        heartbeatTimer = timer
    }

    private func stopHeartbeatTimer() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
    }
}

/// Streaming TCP byte buffer over `NWConnection`.
/// Accumulates arbitrary TCP chunks (including large H.264 keyframes spanning dozens of packets)
/// without ever failing on partial `NWConnection.receive` deliveries.
final class BufferedConnectionReader {
    private let connection: NWConnection
    private var buffer = Data()
    private var readOffset: Int = 0
    private var closed = false

    init(connection: NWConnection) {
        self.connection = connection
        self.buffer.reserveCapacity(256 * 1024)
    }

    private var availableCount: Int { buffer.count - readOffset }

    func readExact(_ count: Int, completion: @escaping (Data?) -> Void) {
        guard !closed else { completion(nil); return }
        if availableCount >= count {
            let chunk = Data(buffer[readOffset..<(readOffset + count)])
            consume(count)
            completion(chunk)
            return
        }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 131_072) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { completion(nil); return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
            }
            if self.availableCount >= count {
                let chunk = Data(self.buffer[self.readOffset..<(self.readOffset + count)])
                self.consume(count)
                completion(chunk)
                return
            }
            if error != nil || isComplete {
                self.closed = true
                completion(nil)
                return
            }
            self.readExact(count, completion: completion)
        }
    }

    /// Streams encrypted `[u32 cipherLen][cipherAndTag]` frames continuously with minimal syscalls.
    func startFrameStream(
        maxFrameBytes: Int,
        onCipherFrame: @escaping (Data) -> Bool,
        onClose: @escaping () -> Void
    ) {
        drainOrReceive(maxFrameBytes: maxFrameBytes, onCipherFrame: onCipherFrame, onClose: onClose)
    }

    private func drainOrReceive(
        maxFrameBytes: Int,
        onCipherFrame: @escaping (Data) -> Bool,
        onClose: @escaping () -> Void
    ) {
        while !closed {
            guard availableCount >= 4 else { break }
            let cipherLen = buffer.withUnsafeBytes { raw -> Int in
                let ptr = raw.baseAddress!.advanced(by: readOffset)
                return Int(UInt32(bigEndian: ptr.loadUnaligned(as: UInt32.self)))
            }
            guard cipherLen >= 1 + BridgeCrypto.tagSize, cipherLen <= maxFrameBytes else {
                closed = true
                onClose()
                return
            }
            guard availableCount >= 4 + cipherLen else { break }
            let frameStart = readOffset + 4
            let frameEnd = frameStart + cipherLen
            let cipherData = Data(buffer[frameStart..<frameEnd])
            consume(4 + cipherLen)
            if !onCipherFrame(cipherData) {
                closed = true
                onClose()
                return
            }
        }

        guard !closed else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 131_072) { [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
            }
            if error != nil {
                self.closed = true
                onClose()
                return
            }
            if isComplete && self.availableCount < 4 {
                self.closed = true
                onClose()
                return
            }
            self.drainOrReceive(maxFrameBytes: maxFrameBytes, onCipherFrame: onCipherFrame, onClose: onClose)
        }
    }

    func markClosed() {
        closed = true
    }

    private func consume(_ count: Int) {
        readOffset += count
        if readOffset == buffer.count {
            buffer.removeAll(keepingCapacity: true)
            readOffset = 0
        } else if readOffset > 131_072 {
            buffer.removeSubrange(0..<readOffset)
            readOffset = 0
        }
    }
}

/// Manages encrypted framing over a single `NWConnection` using `BufferedConnectionReader`.
private final class EncryptedSession {
    let connection: NWConnection
    let reader: BufferedConnectionReader
    let cipher: FrameCipher
    let phone: PairedPhone
    let isVideo: Bool
    let maxFrameBytes: Int
    var lastActivity: Date = Date()
    private var closed = false
    private var onCloseCallback: (() -> Void)?

    init(
        connection: NWConnection,
        reader: BufferedConnectionReader,
        cipher: FrameCipher,
        phone: PairedPhone,
        isVideo: Bool,
        maxFrameBytes: Int
    ) {
        self.connection = connection
        self.reader = reader
        self.cipher = cipher
        self.phone = phone
        self.isVideo = isVideo
        self.maxFrameBytes = maxFrameBytes
    }

    func send(msgType: UInt8, payload: Data) {
        guard !closed else { return }
        do {
            let wire = try cipher.sealFrame(msgType: msgType, payload: payload)
            connection.send(content: wire, completion: .contentProcessed { [weak self] err in
                if err != nil {
                    self?.close()
                }
            })
        } catch {
            close()
        }
    }

    func readOneFrame(completion: @escaping (Result<(UInt8, Data), Error>) -> Void) {
        reader.readExact(4) { [weak self] lenData in
            guard let self, let lenData else {
                completion(.failure(BridgeCryptoError.invalidWireFrame))
                return
            }
            let cipherLen = Int(lenData.withUnsafeBytes { UInt32(bigEndian: $0.loadUnaligned(as: UInt32.self)) })
            guard cipherLen >= 1 + BridgeCrypto.tagSize, cipherLen <= self.maxFrameBytes else {
                completion(.failure(BridgeCryptoError.invalidCiphertextLength))
                return
            }
            self.reader.readExact(cipherLen) { body in
                guard let body else {
                    completion(.failure(BridgeCryptoError.invalidWireFrame))
                    return
                }
                do {
                    let frame = try self.cipher.openCiphertext(body)
                    self.lastActivity = Date()
                    completion(.success(frame))
                } catch {
                    completion(.failure(error))
                }
            }
        }
    }

    func startContinuousReadLoop(
        onFrame: @escaping (UInt8, Data) -> Void,
        onClose: @escaping () -> Void
    ) {
        self.onCloseCallback = onClose
        reader.startFrameStream(
            maxFrameBytes: maxFrameBytes,
            onCipherFrame: { [weak self] cipherData in
                guard let self, !self.closed else { return false }
                do {
                    let (type, payload) = try self.cipher.openCiphertext(cipherData)
                    self.lastActivity = Date()
                    onFrame(type, payload)
                    return true
                } catch {
                    return false
                }
            },
            onClose: { [weak self] in
                self?.close()
            }
        )
    }

    func close() {
        guard !closed else { return }
        closed = true
        reader.markClosed()
        connection.cancel()
        let cb = onCloseCallback
        onCloseCallback = nil
        cb?()
    }
}
