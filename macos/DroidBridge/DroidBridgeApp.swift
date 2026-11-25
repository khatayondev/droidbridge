import SwiftUI

@main
struct DroidBridgeApp: App {
    @StateObject private var coordinator = AppCoordinator()

    init() {
        // Defer starting network server until coordinator is initialized in onAppear/init hook
    }

    var body: some Scene {
        MenuBarExtra {
            MenuBarContentView(
                coordinator: coordinator,
                server: coordinator.server,
                notificationManager: coordinator.notificationManager
            )
        } label: {
            MenuBarIconLabel(coordinator: coordinator, server: coordinator.server)
        }
    }
}

private struct MenuBarIconLabel: View {
    @ObservedObject var coordinator: AppCoordinator
    @ObservedObject var server: BridgeServer
    @State private var started = false

    var body: some View {
        Image(systemName: server.activePhone != nil ? "iphone.radiowaves.left.and.right" : "iphone")
            .onAppear {
                if !started {
                    started = true
                    coordinator.start()
                }
            }
    }
}

private struct MenuBarContentView: View {
    @ObservedObject var coordinator: AppCoordinator
    @ObservedObject var server: BridgeServer
    @ObservedObject var notificationManager: NotificationBridgeManager

    var body: some View {
        Group {
            if let phone = server.activePhone {
                Text(statusLine(phone: phone))
            } else {
                Text("Waiting for phone (port \(server.listeningPort))")
            }

            if let call = coordinator.activeCall {
                Text(call.phase == .ringing
                     ? "📞 \(call.displayName) — Incoming call"
                     : "📞 \(call.displayName) — In call")
                if call.phase == .ringing {
                    Button("Answer Call") { coordinator.answerCall() }
                    Button("Decline Call") { coordinator.declineCall() }
                } else {
                    Button("End Call") { coordinator.declineCall() }
                }
                Divider()
            }

            Divider()

            Button("Open Phone") {
                coordinator.openMirrorWindow()
            }
            .disabled(server.activePhone == nil)
            .keyboardShortcut("o")

            Button("Call a Number…") {
                coordinator.openCallWindow()
            }
            .disabled(server.activePhone == nil)

            if server.activePhone != nil {
                Button("End Phone Capture Session") {
                    coordinator.server.sendControl(type: ProtocolConstants.msgMirrorStop, payload: EmptyPayload())
                }
            }

            Toggle("Pause Notifications", isOn: $notificationManager.notificationsPaused)

            Divider()

            Button("Pair New Phone…") {
                coordinator.openPairingWindow()
            }

            Button("Settings…") {
                coordinator.openSettingsWindow()
            }
            .keyboardShortcut(",")

            Divider()

            Button("Quit DroidBridge") {
                coordinator.server.stop()
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q")
        }
    }

    private func statusLine(phone: PairedPhone) -> String {
        var line = "Connected: \(phone.deviceName)"
        if let pct = server.batteryLevel {
            line += " • \(pct)%"
            if server.isCharging == true {
                line += " ⚡"
            }
        }
        return line
    }
}
