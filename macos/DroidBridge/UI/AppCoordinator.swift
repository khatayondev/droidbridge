import Foundation
import AppKit
import SwiftUI
import ServiceManagement

/// A call currently ringing or in progress on the paired phone.
public struct ActiveCall: Equatable {
    public enum Phase: String {
        case ringing
        case active
    }

    public var phase: Phase
    public var name: String?
    public var number: String?
    public var startedAt: Date?

    public var displayName: String {
        if let n = name, !n.isEmpty { return n }
        if let num = number, !num.isEmpty { return num }
        return "Unknown caller"
    }
}

/// Central coordinator owning networking, pairing, notifications, mirroring, input forwarding,
/// clipboard sync, and launch-at-login management on macOS.
public final class AppCoordinator: ObservableObject, BridgeServerDelegate {
    @Published public var clipboardSyncEnabled: Bool {
        didSet {
            UserDefaults.standard.set(clipboardSyncEnabled, forKey: Self.keyClipboardSync)
        }
    }
    @Published public var launchAtLoginEnabled: Bool = false
    @Published public private(set) var activeCall: ActiveCall?

    public let store: KeychainStore
    public let pairingManager: PairingManager
    public let server: BridgeServer
    public let notificationManager: NotificationBridgeManager
    public let videoDecoder: VideoDecoder
    public let telemetryRecorder: TelemetryRecorder
    public let mirrorViewModel: MirrorViewModel

    private var mirrorWindowController: MirrorWindowController?
    private var pairingWindow: NSWindow?
    private var settingsWindow: NSWindow?
    private var callWindow: NSWindow?

    private var clipboardTimer: Timer?
    private var lastPasteboardChangeCount: Int = NSPasteboard.general.changeCount
    private var lastClipboardFromPhone: String?

    private static let keyClipboardSync = "droidbridge.clipboardSyncEnabled"

    public init() {
        self.store = .shared
        self.pairingManager = PairingManager(store: store)
        self.server = BridgeServer(store: store, pairingManager: pairingManager)
        self.notificationManager = NotificationBridgeManager()
        self.videoDecoder = VideoDecoder()
        self.telemetryRecorder = TelemetryRecorder()
        self.mirrorViewModel = MirrorViewModel(decoder: videoDecoder, telemetry: telemetryRecorder)

        if UserDefaults.standard.object(forKey: Self.keyClipboardSync) != nil {
            self.clipboardSyncEnabled = UserDefaults.standard.bool(forKey: Self.keyClipboardSync)
        } else {
            self.clipboardSyncEnabled = true
        }

        self.launchAtLoginEnabled = (SMAppService.mainApp.status == .enabled)

        wireCallbacks()
    }

    public func start() {
        server.delegate = self
        server.start()
        notificationManager.start()
        telemetryRecorder.start()
        startClipboardMonitor()
    }

    public func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
            launchAtLoginEnabled = (SMAppService.mainApp.status == .enabled)
        } catch {
            launchAtLoginEnabled = (SMAppService.mainApp.status == .enabled)
        }
    }

    // MARK: - Window Presentation

    public func openMirrorWindow(launchPackage: String? = nil) {
        DispatchQueue.main.async {
            if self.mirrorWindowController == nil {
                self.mirrorWindowController = MirrorWindowController(
                    viewModel: self.mirrorViewModel,
                    inputViewProvider: { AnyView(MirrorInputView(viewModel: self.mirrorViewModel)) }
                )
            }
            if let phone = self.server.activePhone {
                self.mirrorViewModel.phoneName = phone.deviceName
            }
            self.mirrorWindowController?.openAndRequestMirror()
            if let pkg = launchPackage, !pkg.isEmpty {
                self.server.sendControl(type: ProtocolConstants.msgAppLaunch, payload: AppLaunchPayload(pkg: pkg))
            }
        }
    }

    public func openPairingWindow() {
        DispatchQueue.main.async {
            if self.pairingWindow == nil {
                let view = PairingWindowView(
                    pairingManager: self.pairingManager,
                    server: self.server,
                    onForgetPhone: { [weak self] deviceId in
                        guard let self else { return }
                        if self.server.activePhone?.deviceId == deviceId {
                            self.server.disconnectActivePhone()
                        }
                        self.pairingManager.forgetPhone(deviceId: deviceId)
                    }
                )
                let win = NSWindow(
                    contentRect: NSRect(x: 0, y: 0, width: 420, height: 580),
                    styleMask: [.titled, .closable],
                    backing: .buffered,
                    defer: false
                )
                win.title = "Pair New Phone — DroidBridge"
                win.contentView = NSHostingView(rootView: view)
                win.center()
                win.isReleasedWhenClosed = false
                self.pairingWindow = win
            }
            self.pairingManager.startPairingSession(port: self.server.listeningPort)
            self.pairingWindow?.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    public func openSettingsWindow() {
        DispatchQueue.main.async {
            if self.settingsWindow == nil {
                let view = SettingsView(
                    coordinator: self,
                    notificationManager: self.notificationManager,
                    server: self.server
                )
                let win = NSWindow(
                    contentRect: NSRect(x: 0, y: 0, width: 480, height: 540),
                    styleMask: [.titled, .closable, .resizable],
                    backing: .buffered,
                    defer: false
                )
                win.title = "DroidBridge Settings"
                win.contentView = NSHostingView(rootView: view)
                win.center()
                win.isReleasedWhenClosed = false
                self.settingsWindow = win
            }
            self.settingsWindow?.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    // MARK: - Calls

    public func openCallWindow() {
        DispatchQueue.main.async {
            if self.callWindow == nil {
                let view = CallDialView { [weak self] number in
                    self?.placeCall(number: number)
                }
                let win = NSWindow(
                    contentRect: NSRect(x: 0, y: 0, width: 340, height: 170),
                    styleMask: [.titled, .closable],
                    backing: .buffered,
                    defer: false
                )
                win.title = "Call a Number — DroidBridge"
                win.contentView = NSHostingView(rootView: view)
                win.center()
                win.isReleasedWhenClosed = false
                self.callWindow = win
            }
            self.callWindow?.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    public func placeCall(number: String) {
        let trimmed = number.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        server.sendControl(type: ProtocolConstants.msgCallDial, payload: CallDialPayload(number: trimmed))
    }

    public func answerCall() {
        server.sendControl(type: ProtocolConstants.msgCallAnswer, payload: EmptyPayload())
    }

    /// Declines a ringing call or hangs up an active one — the phone uses `endCall()` for both.
    public func declineCall() {
        server.sendControl(type: ProtocolConstants.msgCallEnd, payload: EmptyPayload())
    }

    private func handleCallState(_ payload: CallStatePayload) {
        DispatchQueue.main.async {
            switch payload.state {
            case "ringing":
                self.activeCall = ActiveCall(
                    phase: .ringing,
                    name: payload.name,
                    number: payload.number,
                    startedAt: nil
                )
                self.notificationManager.handleIncomingCall(name: payload.name, number: payload.number)
            case "active":
                self.notificationManager.handleCallEnded()
                if var call = self.activeCall {
                    call.phase = .active
                    if call.startedAt == nil { call.startedAt = Date() }
                    self.activeCall = call
                } else {
                    self.activeCall = ActiveCall(
                        phase: .active,
                        name: payload.name,
                        number: payload.number,
                        startedAt: Date()
                    )
                }
            default:
                self.activeCall = nil
                self.notificationManager.handleCallEnded()
            }
        }
    }

    // MARK: - Wiring

    private func wireCallbacks() {
        notificationManager.onSendReply = { [weak self] key, text in
            self?.server.sendControl(
                type: ProtocolConstants.msgNotifReply,
                payload: NotifReplyPayload(key: key, text: text)
            )
        }
        notificationManager.onSendAction = { [weak self] key, index in
            self?.server.sendControl(
                type: ProtocolConstants.msgNotifAction,
                payload: NotifActionTriggerPayload(key: key, actionIndex: index)
            )
        }
        notificationManager.onSendDismiss = { [weak self] key in
            self?.server.sendControl(
                type: ProtocolConstants.msgNotifDismiss,
                payload: NotifKeyPayload(key: key)
            )
        }
        notificationManager.onRequestAppIcon = { [weak self] pkg in
            self?.server.sendControl(
                type: ProtocolConstants.msgAppIconRequest,
                payload: AppIconRequestPayload(pkg: pkg)
            )
        }
        notificationManager.onOpenMirrorForApp = { [weak self] pkg in
            self?.openMirrorWindow(launchPackage: pkg)
        }
        notificationManager.onAnswerCall = { [weak self] in
            self?.answerCall()
        }
        notificationManager.onDeclineCall = { [weak self] in
            self?.declineCall()
        }

        videoDecoder.onRequestKeyframe = { [weak self] in
            self?.server.sendControl(type: ProtocolConstants.msgVideoKeyframeRequest, payload: EmptyPayload())
        }
        videoDecoder.onFrameDecodedMetrics = { [weak self] bytes, decodeMs, fps in
            self?.telemetryRecorder.recordVideoFrameDecoded(wireBytes: bytes, decodeMs: decodeMs, fps: fps)
        }
        server.onRttMeasured = { [weak self] rttMs in
            self?.telemetryRecorder.recordRtt(ms: rttMs)
        }

        mirrorViewModel.onRequestMirror = { [weak self] in
            guard let self else { return }
            self.telemetryRecorder.setQualityPreset(self.mirrorViewModel.qualityPreset.rawValue)
            self.server.sendControl(type: ProtocolConstants.msgMirrorQuality, payload: self.mirrorViewModel.qualityPreset.payload)
            self.server.sendControl(type: ProtocolConstants.msgMirrorRequest, payload: EmptyPayload())
        }
        mirrorViewModel.onPauseMirror = { [weak self] in
            self?.server.sendControl(type: ProtocolConstants.msgMirrorPause, payload: EmptyPayload())
        }
        mirrorViewModel.onStopMirror = { [weak self] in
            self?.server.sendControl(type: ProtocolConstants.msgMirrorStop, payload: EmptyPayload())
        }
        mirrorViewModel.onQualityChanged = { [weak self] preset in
            self?.telemetryRecorder.setQualityPreset(preset.rawValue)
            self?.server.sendControl(type: ProtocolConstants.msgMirrorQuality, payload: preset.payload)
        }
        mirrorViewModel.onSendTouch = { [weak self] touch in
            self?.server.sendControl(type: ProtocolConstants.msgInputTouch, payload: touch)
        }
        mirrorViewModel.onSendScroll = { [weak self] scroll in
            self?.server.sendControl(type: ProtocolConstants.msgInputScroll, payload: scroll)
        }
        mirrorViewModel.onSendText = { [weak self] text in
            self?.server.sendControl(type: ProtocolConstants.msgInputText, payload: InputTextPayload(text: text))
        }
        mirrorViewModel.onSendKey = { [weak self] key in
            self?.server.sendControl(type: ProtocolConstants.msgInputKey, payload: InputKeyPayload(key: key))
        }
        mirrorViewModel.onSendGlobal = { [weak self] action in
            self?.server.sendControl(type: ProtocolConstants.msgInputGlobal, payload: InputGlobalPayload(action: action))
        }
        mirrorViewModel.onPasteClipboard = { [weak self] in
            guard let self, let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
                return
            }
            self.server.sendControl(type: ProtocolConstants.msgClipboard, payload: ClipboardPayload(text: text))
            self.server.sendControl(type: ProtocolConstants.msgInputText, payload: InputTextPayload(text: text))
        }
        mirrorViewModel.onMakeCall = { [weak self] in
            self?.openCallWindow()
        }
    }

    // MARK: - BridgeServerDelegate

    public func bridgeServer(_ server: BridgeServer, didConnectControl phone: PairedPhone, hello: HelloPayload) {
        DispatchQueue.main.async {
            self.mirrorViewModel.phoneName = phone.deviceName
        }
    }

    public func bridgeServer(_ server: BridgeServer, didDisconnectControl phone: PairedPhone?) {
        videoDecoder.reset()
        telemetryRecorder.clearAndroidTelemetry()
        DispatchQueue.main.async {
            self.activeCall = nil
            self.notificationManager.handleCallEnded()
        }
        DispatchQueue.main.async {
            self.mirrorViewModel.uiState = .waitingForPhone
        }
    }

    public func bridgeServer(_ server: BridgeServer, didReceiveControlMessage type: UInt8, payload: Data) {
        switch type {
        case ProtocolConstants.msgNotifPosted:
            if let notif = try? JSONDecoder().decode(NotifPostedPayload.self, from: payload) {
                notificationManager.handleNotificationPosted(notif)
            }
        case ProtocolConstants.msgNotifRemoved:
            if let rem = try? JSONDecoder().decode(NotifKeyPayload.self, from: payload) {
                notificationManager.handleNotificationRemoved(key: rem.key)
            }
        case ProtocolConstants.msgAppIcon:
            if let icon = try? JSONDecoder().decode(AppIconPayload.self, from: payload) {
                notificationManager.handleAppIcon(icon)
            }
        case ProtocolConstants.msgMirrorState:
            if let state = try? JSONDecoder().decode(MirrorStatePayload.self, from: payload) {
                mirrorViewModel.updateFromMirrorState(state)
            }
        case ProtocolConstants.msgDeviceTelemetry:
            if let t = try? JSONDecoder().decode(DeviceTelemetryPayload.self, from: payload) {
                telemetryRecorder.handleAndroidTelemetry(t)
            }
        case ProtocolConstants.msgCallState:
            if let call = try? JSONDecoder().decode(CallStatePayload.self, from: payload) {
                handleCallState(call)
            }
        case ProtocolConstants.msgCallResult:
            if let result = try? JSONDecoder().decode(CallResultPayload.self, from: payload),
               !result.ok {
                notificationManager.notifyCallError(
                    result.message ?? "The phone rejected the \(result.action) request."
                )
            }
        case ProtocolConstants.msgClipboard:
            if let clip = try? JSONDecoder().decode(ClipboardPayload.self, from: payload),
               clipboardSyncEnabled, !clip.text.isEmpty {
                DispatchQueue.main.async {
                    self.lastClipboardFromPhone = clip.text
                    let pb = NSPasteboard.general
                    pb.clearContents()
                    pb.setString(clip.text, forType: .string)
                    self.lastPasteboardChangeCount = pb.changeCount
                }
            }
        default:
            break
        }
    }

    public func bridgeServer(_ server: BridgeServer, didReceiveVideoMessage type: UInt8, payload: Data) {
        switch type {
        case ProtocolConstants.msgVideoConfig:
            if let cfg = try? VideoConfigPayload.decode(payload) {
                videoDecoder.handleVideoConfig(cfg)
            }
        case ProtocolConstants.msgVideoFrame:
            if let frame = try? VideoFramePayload.decode(payload) {
                videoDecoder.handleVideoFrame(frame)
            }
        default:
            break
        }
    }

    public func bridgeServerDidDisconnectVideo(_ server: BridgeServer) {
        videoDecoder.reset()
        DispatchQueue.main.async {
            if self.mirrorViewModel.uiState == .streaming {
                self.mirrorViewModel.uiState = .waitingForPhone
            }
        }
    }

    // MARK: - Mac -> Phone Clipboard Polling

    private func startClipboardMonitor() {
        clipboardTimer?.invalidate()
        clipboardTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self, self.clipboardSyncEnabled, self.server.activePhone != nil else { return }
            let pb = NSPasteboard.general
            if pb.changeCount != self.lastPasteboardChangeCount {
                self.lastPasteboardChangeCount = pb.changeCount
                if let str = pb.string(forType: .string), !str.isEmpty, str != self.lastClipboardFromPhone {
                    self.lastClipboardFromPhone = nil
                    self.server.sendControl(
                        type: ProtocolConstants.msgClipboard,
                        payload: ClipboardPayload(text: str)
                    )
                }
            }
        }
    }
}
