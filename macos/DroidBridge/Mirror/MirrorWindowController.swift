import Foundation
import AppKit
import SwiftUI

public enum MirrorUIState: Equatable {
    case waitingForPhone
    case awaitingConsent
    case standby
    case streaming
    case error(String)
}

public final class MirrorViewModel: ObservableObject {
    @Published public var uiState: MirrorUIState = .waitingForPhone
    @Published public var sizePreset: MirrorSizePreset = .medium
    @Published public var qualityPreset: StreamQualityPreset = .balanced
    @Published public var phoneName: String = "Android Phone"
    @Published public var showStatsHud: Bool = false

    public let decoder: VideoDecoder
    public let telemetry: TelemetryRecorder

    public var onRequestMirror: (() -> Void)?
    public var onPauseMirror: (() -> Void)?
    public var onStopMirror: (() -> Void)?
    public var onQualityChanged: ((StreamQualityPreset) -> Void)?
    public var onSendTouch: ((InputTouchPayload) -> Void)?
    public var onSendScroll: ((InputScrollPayload) -> Void)?
    public var onSendText: ((String) -> Void)?
    public var onSendKey: ((String) -> Void)?
    public var onSendGlobal: ((String) -> Void)?
    public var onPasteClipboard: (() -> Void)?
    public var onMakeCall: (() -> Void)?
    public var onResizePresetChanged: ((MirrorSizePreset) -> Void)?

    public init(decoder: VideoDecoder, telemetry: TelemetryRecorder = TelemetryRecorder()) {
        self.decoder = decoder
        self.telemetry = telemetry
    }

    public func updateFromMirrorState(_ payload: MirrorStatePayload) {
        DispatchQueue.main.async {
            switch payload.state {
            case "awaiting_consent":
                self.uiState = .awaitingConsent
            case "standby":
                self.uiState = .standby
            case "streaming":
                self.uiState = .streaming
            case "error":
                self.uiState = .error(payload.reason ?? "Mirroring failed")
            default:
                if let reason = payload.reason, !reason.isEmpty {
                    self.uiState = .error(reason)
                } else {
                    self.uiState = .waitingForPhone
                }
            }
        }
    }
}

/// Manages the phone-styled aspect-ratio-locked macOS mirror window.
/// Closing the window sends `mirror.pause` (keeping Android in zero-CPU standby)
/// so reopening "Open Phone" resumes streaming in <100ms with zero prompt.
public final class MirrorWindowController: NSWindowController, NSWindowDelegate {
    private let viewModel: MirrorViewModel
    private let toolbarHeight: CGFloat = 46

    public init(viewModel: MirrorViewModel, inputViewProvider: @escaping () -> AnyView) {
        self.viewModel = viewModel

        let initialVideoSize = AnnexBConverter.windowSize(videoWidth: 720, videoHeight: 1600, preset: viewModel.sizePreset)
        let contentRect = NSRect(
            x: 0,
            y: 0,
            width: initialVideoSize.width,
            height: initialVideoSize.height + toolbarHeight
        )

        let window = NSWindow(
            contentRect: contentRect,
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = viewModel.phoneName
        window.titlebarAppearsTransparent = true
        window.isMovableByWindowBackground = true
        window.backgroundColor = NSColor(calibratedWhite: 0.08, alpha: 1.0)
        window.minSize = NSSize(width: 260, height: 420)
        window.center()

        super.init(window: window)
        window.delegate = self

        let rootView = MirrorWindowContentView(
            viewModel: viewModel,
            decoder: viewModel.decoder,
            telemetry: viewModel.telemetry,
            streamOverlay: inputViewProvider
        )
        let hosting = NSHostingView(rootView: rootView)
        hosting.wantsLayer = true
        hosting.layer?.cornerRadius = 18
        hosting.layer?.masksToBounds = true
        window.contentView = hosting

        viewModel.decoder.onVideoSizeChanged = { [weak self] videoSize in
            self?.applyAspectRatio(videoSize: videoSize)
        }
        viewModel.onResizePresetChanged = { [weak self] preset in
            guard let self else { return }
            self.applySizePreset(preset)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) not supported")
    }

    public func openAndRequestMirror() {
        guard let window else { return }
        window.title = viewModel.phoneName
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        viewModel.onRequestMirror?()
    }

    public func windowWillClose(_ notification: Notification) {
        // Pause into standby instead of tearing down MediaProjection so next open is instant!
        viewModel.onPauseMirror?()
        viewModel.decoder.reset()
        viewModel.uiState = .waitingForPhone
    }

    private func applyAspectRatio(videoSize: CGSize) {
        guard let window, videoSize.width > 0, videoSize.height > 0 else { return }
        let target = AnnexBConverter.windowSize(
            videoWidth: Int(videoSize.width),
            videoHeight: Int(videoSize.height),
            preset: viewModel.sizePreset
        )
        let contentHeight = target.height + toolbarHeight
        window.contentAspectRatio = NSSize(width: target.width, height: contentHeight)
        var frame = window.frameRect(forContentRect: NSRect(x: 0, y: 0, width: target.width, height: contentHeight))
        frame.origin = NSPoint(
            x: window.frame.midX - frame.width / 2,
            y: window.frame.midY - frame.height / 2
        )
        window.setFrame(frame, display: true, animate: true)
    }

    private func applySizePreset(_ preset: MirrorSizePreset) {
        viewModel.sizePreset = preset
        applyAspectRatio(videoSize: viewModel.decoder.videoSize)
    }
}

struct MirrorWindowContentView: View {
    @ObservedObject var viewModel: MirrorViewModel
    @ObservedObject var decoder: VideoDecoder
    @ObservedObject var telemetry: TelemetryRecorder
    let streamOverlay: () -> AnyView

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topLeading) {
                Color.black
                streamOverlay()

                if viewModel.showStatsHud && viewModel.uiState == .streaming {
                    telemetryHudOverlay
                        .padding(10)
                        .allowsHitTesting(false)
                }

                if viewModel.uiState != .streaming {
                    stateOverlay
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .padding([.horizontal, .top], 6)

            // Bottom navigation, live FPS, HUD toggle, quality & size preset bar
            HStack(spacing: 10) {
                Button { viewModel.onSendGlobal?("back") } label: {
                    Image(systemName: "chevron.backward")
                }
                .help("Back (⌘[ or Esc)")

                Button { viewModel.onSendGlobal?("home") } label: {
                    Image(systemName: "circle")
                }
                .help("Home (⌘1)")

                Button { viewModel.onSendGlobal?("recents") } label: {
                    Image(systemName: "square")
                }
                .help("Recents (⌘2)")

                Button { viewModel.onSendGlobal?("notifications") } label: {
                    Image(systemName: "bell")
                }
                .help("Notifications (⌘N)")

                Button { viewModel.onMakeCall?() } label: {
                    Image(systemName: "phone")
                }
                .help("Call a Number (dials on the phone)")
                .disabled(viewModel.uiState != .streaming)

                Spacer()

                if viewModel.uiState == .streaming && decoder.currentFps > 0 {
                    Text("\(decoder.currentFps) fps")
                        .font(.caption2.monospacedDigit())
                        .foregroundColor(.green.opacity(0.9))
                }

                Button {
                    viewModel.showStatsHud.toggle()
                } label: {
                    Image(systemName: "waveform.path.ecg")
                        .foregroundColor(viewModel.showStatsHud ? .green : .white.opacity(0.85))
                }
                .help("Toggle Live Hardware Telemetry HUD")

                Menu {
                    ForEach(StreamQualityPreset.allCases) { q in
                        Button {
                            viewModel.qualityPreset = q
                            viewModel.onQualityChanged?(q)
                        } label: {
                            if viewModel.qualityPreset == q {
                                Label(q.rawValue, systemImage: "checkmark")
                            } else {
                                Text(q.rawValue)
                            }
                        }
                    }
                } label: {
                    Image(systemName: "slider.horizontal.3")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .help("Stream Quality (\(viewModel.qualityPreset.rawValue))")

                ForEach(MirrorSizePreset.allCases) { preset in
                    Button(preset.rawValue) {
                        viewModel.onResizePresetChanged?(preset)
                    }
                    .buttonStyle(.bordered)
                    .tint(viewModel.sizePreset == preset ? .accentColor : .secondary)
                    .controlSize(.mini)
                }
            }
            .buttonStyle(.borderless)
            .foregroundColor(.white.opacity(0.85))
            .padding(.horizontal, 12)
            .frame(height: 46)
        }
        .background(Color(NSColor(calibratedWhite: 0.08, alpha: 1.0)))
    }

    @ViewBuilder
    private var telemetryHudOverlay: some View {
        let mac = telemetry.latestMac
        let droid = telemetry.latestAndroid
        VStack(alignment: .leading, spacing: 3) {
            Text(String(
                format: "MAC  CPU %.1f%% • %.0f MB • Dec %.2f ms • %.2f Mbps",
                mac.cpuPercent, mac.physFootprintMb, mac.avgDecodeMs, mac.videoMbps
            ))
            if let rtt = mac.rttMs {
                Text(String(format: "NET  RTT %.1f ms • %d fps • %@", rtt, mac.decodedFps, mac.qualityPreset))
            } else {
                Text(String(format: "NET  %d fps • %@", mac.decodedFps, mac.qualityPreset))
            }
            if let d = droid {
                let tempStr = d.batteryTempC.map { String(format: "%.1f°C", $0) } ?? "--"
                let powerStr = d.batteryPowerW.map { String(format: "%.2fW", $0) } ?? "--"
                Text(String(
                    format: "PHONE  CPU %.1f%% • %.0f MB • %@ • %@",
                    d.appCpuPercent, d.appPssMb, tempStr, powerStr
                ))
                Text(String(
                    format: "ENC  %dx%d • Enc %.0f µs • Drop %lld • Therm %@",
                    d.videoWidth, d.videoHeight, d.avgEncryptUs, d.droppedFrames, d.thermalStatus
                ))
            }
        }
        .font(.system(size: 10, weight: .medium, design: .monospaced))
        .foregroundColor(.green)
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.black.opacity(0.72))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private var stateOverlay: some View {
        VStack(spacing: 14) {
            switch viewModel.uiState {
            case .waitingForPhone, .standby:
                ProgressView()
                    .controlSize(.large)
                    .colorScheme(.dark)
                Text(viewModel.uiState == .standby ? "Resuming stream…" : "Connecting to phone…")
                    .font(.headline)
                    .foregroundColor(.white)
                Text("Phone screen will appear automatically.")
                    .font(.caption)
                    .foregroundColor(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            case .awaitingConsent:
                Image(systemName: "iphone.and.arrow.forward")
                    .font(.system(size: 36))
                    .foregroundColor(.accentColor)
                Text("Unlock your phone and tap Start once")
                    .font(.headline)
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                Text("Locked to Entire Screen. After approving once, reopening stays instant with no repeat prompts.")
                    .font(.caption)
                    .foregroundColor(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            case let .error(reason):
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 32))
                    .foregroundColor(.orange)
                Text(reason)
                    .font(.headline)
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 20)
                Button("Retry") {
                    viewModel.uiState = .waitingForPhone
                    viewModel.onRequestMirror?()
                }
                .buttonStyle(.borderedProminent)
            case .streaming:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.opacity(0.85))
    }
}
