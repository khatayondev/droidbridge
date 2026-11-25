import Foundation
import UserNotifications

public struct FormattedNotification: Equatable {
    public let identifier: String
    public let title: String
    public let subtitle: String
    public let body: String
    public let threadIdentifier: String
    public let categoryIdentifier: String
    public let pkg: String
}

/// Manages macOS UserNotifications display, icon caching, per-app mute list, privacy settings,
/// and two-way reply/action/dismiss/click routing back to the Android phone.
public final class NotificationBridgeManager: NSObject, ObservableObject, UNUserNotificationCenterDelegate {

    @Published public var mutedPackages: Set<String> {
        didSet { saveSettings() }
    }
    @Published public var seenApps: [String: String] { // pkg -> appName
        didSet { saveSettings() }
    }
    @Published public var showMessageContent: Bool {
        didSet { saveSettings() }
    }
    @Published public var notificationsPaused: Bool {
        didSet { saveSettings() }
    }

    public var onSendReply: ((_ key: String, _ text: String) -> Void)?
    public var onSendAction: ((_ key: String, _ actionIndex: Int) -> Void)?
    public var onSendDismiss: ((_ key: String) -> Void)?
    public var onRequestAppIcon: ((_ pkg: String) -> Void)?
    public var onOpenMirrorForApp: ((_ pkg: String?) -> Void)?
    public var onAnswerCall: (() -> Void)?
    public var onDeclineCall: (() -> Void)?

    private let defaults: UserDefaults
    private let iconCacheDir: URL
    private var registeredCategories: [String: UNNotificationCategory] = [:]
    private let categoryLock = NSLock()
    private var centerAvailable: Bool = false

    private static let keyMuted = "droidbridge.notif.mutedPackages"
    private static let keySeen = "droidbridge.notif.seenApps"
    private static let keyShowContent = "droidbridge.notif.showMessageContent"
    private static let keyPaused = "droidbridge.notif.paused"

    public static let categoryDefault = "DROIDBRIDGE_DEFAULT"
    public static let categoryReplyable = "DROIDBRIDGE_REPLYABLE"
    public static let actionReplyId = "REPLY"
    public static let actionCustomPrefix = "ACTION_"

    public static let categoryCall = "DROIDBRIDGE_CALL"
    public static let actionCallAnswer = "CALL_ANSWER"
    public static let actionCallDecline = "CALL_DECLINE"
    public static let callNotificationId = "droidbridge.call"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        self.iconCacheDir = caches.appendingPathComponent("DroidBridge/AppIcons", isDirectory: true)
        try? FileManager.default.createDirectory(at: iconCacheDir, withIntermediateDirectories: true)

        let mutedArr = defaults.stringArray(forKey: Self.keyMuted) ?? []
        self.mutedPackages = Set(mutedArr)
        self.seenApps = defaults.dictionary(forKey: Self.keySeen) as? [String: String] ?? [:]
        if defaults.object(forKey: Self.keyShowContent) != nil {
            self.showMessageContent = defaults.bool(forKey: Self.keyShowContent)
        } else {
            self.showMessageContent = true
        }
        self.notificationsPaused = defaults.bool(forKey: Self.keyPaused)

        super.init()
    }

    public func start() {
        guard Bundle.main.bundleIdentifier != nil else { return }
        centerAvailable = true
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        registerBaseCategories(on: center)
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    public func isMuted(pkg: String) -> Bool {
        mutedPackages.contains(pkg)
    }

    public func setMuted(_ muted: Bool, pkg: String) {
        if muted {
            mutedPackages.insert(pkg)
        } else {
            mutedPackages.remove(pkg)
        }
    }

    // MARK: - Formatting & Filtering (Pure / Unit-Testable)

    public func formatNotification(_ payload: NotifPostedPayload) -> FormattedNotification? {
        recordSeenApp(pkg: payload.pkg, appName: payload.appName)

        if notificationsPaused || mutedPackages.contains(payload.pkg) {
            return nil
        }

        let title: String
        if let conv = payload.conversationTitle, !conv.isEmpty {
            title = conv
        } else if let t = payload.title, !t.isEmpty {
            title = t
        } else {
            title = payload.appName
        }

        let body: String
        if !showMessageContent {
            body = "New notification"
        } else if let latest = payload.messages.last {
            if payload.isGroupConversation && !latest.sender.isEmpty {
                body = "\(latest.sender): \(latest.text)"
            } else {
                body = latest.text
            }
        } else if let t = payload.text, !t.isEmpty {
            body = t
        } else if let sub = payload.subText, !sub.isEmpty {
            body = sub
        } else {
            body = ""
        }

        let threadId: String
        if let conv = payload.conversationTitle, !conv.isEmpty {
            threadId = "\(payload.pkg):\(conv)"
        } else {
            threadId = payload.pkg
        }

        let categoryId = categoryIdentifier(for: payload)

        return FormattedNotification(
            identifier: payload.key,
            title: title,
            subtitle: payload.appName,
            body: body,
            threadIdentifier: threadId,
            categoryIdentifier: categoryId,
            pkg: payload.pkg
        )
    }

    public func categoryIdentifier(for payload: NotifPostedPayload) -> String {
        let hasReply = payload.actions.contains { $0.hasRemoteInput }
        let nonReply = Array(payload.actions.filter { !$0.hasRemoteInput }.prefix(3))
        if nonReply.isEmpty {
            return hasReply ? Self.categoryReplyable : Self.categoryDefault
        }
        let sig = (hasReply ? "R" : "N") + "_" + nonReply.map { "\($0.index):\($0.title)" }.joined(separator: "|")
        let hash = abs(sig.hashValue)
        return "DROIDBRIDGE_DYN_\(hash)"
    }

    // MARK: - Incoming Messages from Android

    public func handleNotificationPosted(_ payload: NotifPostedPayload) {
        if cachedAppIconURL(for: payload.pkg) == nil {
            onRequestAppIcon?(payload.pkg)
        }
        guard let formatted = formatNotification(payload) else { return }
        guard centerAvailable else { return }

        let center = UNUserNotificationCenter.current()
        ensureDynamicCategory(for: payload, categoryId: formatted.categoryIdentifier, on: center)

        let content = UNMutableNotificationContent()
        content.title = formatted.title
        content.subtitle = formatted.subtitle
        content.body = formatted.body
        content.threadIdentifier = formatted.threadIdentifier
        content.categoryIdentifier = formatted.categoryIdentifier
        content.userInfo = ["pkg": payload.pkg, "key": payload.key]
        // Use standard .default sound so macOS Focus mode naturally suppresses alerts when active.
        content.sound = .default

        if let attachment = buildAttachment(largeIconBase64: payload.largeIconPngBase64, pkg: payload.pkg) {
            content.attachments = [attachment]
        }

        let request = UNNotificationRequest(
            identifier: formatted.identifier,
            content: content,
            trigger: nil
        )
        center.add(request) { _ in }
    }

    public func handleNotificationRemoved(key: String) {
        guard centerAvailable else { return }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [key])
    }

    public func handleAppIcon(_ icon: AppIconPayload) {
        recordSeenApp(pkg: icon.pkg, appName: icon.appName)
        guard let pngData = Data(base64Encoded: icon.pngBase64) else { return }
        let url = iconCacheURL(for: icon.pkg)
        try? pngData.write(to: url, options: .atomic)
    }

    // MARK: - Calls

    /// Shows (or refreshes) a ringing-call banner with Answer / Decline actions.
    public func handleIncomingCall(name: String?, number: String?) {
        guard centerAvailable else { return }
        let caller: String
        if let n = name, !n.isEmpty {
            caller = n
        } else if let num = number, !num.isEmpty {
            caller = num
        } else {
            caller = "Unknown caller"
        }

        let content = UNMutableNotificationContent()
        content.title = "Incoming call"
        content.subtitle = caller
        content.body = (name?.isEmpty == false ? (number ?? "") : "")
        content.categoryIdentifier = Self.categoryCall
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        content.userInfo = ["call": true]

        let request = UNNotificationRequest(
            identifier: Self.callNotificationId,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request) { _ in }
    }

    public func handleCallEnded() {
        guard centerAvailable else { return }
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [Self.callNotificationId])
        center.removePendingNotificationRequests(withIdentifiers: [Self.callNotificationId])
    }

    public func notifyCallError(_ message: String) {
        guard centerAvailable else { return }
        let content = UNMutableNotificationContent()
        content.title = "Call action failed"
        content.body = message
        let request = UNNotificationRequest(
            identifier: "droidbridge.call.error.\(UUID().uuidString)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request) { _ in }
    }

    // MARK: - UNUserNotificationCenterDelegate

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let key = response.notification.request.identifier
        let pkg = response.notification.request.content.userInfo["pkg"] as? String

        switch response.actionIdentifier {
        case Self.actionCallAnswer:
            onAnswerCall?()
        case Self.actionCallDecline:
            onDeclineCall?()
        case Self.actionReplyId:
            if let textResp = response as? UNTextInputNotificationResponse {
                onSendReply?(key, textResp.userText)
            }
        case UNNotificationDismissActionIdentifier:
            onSendDismiss?(key)
        case UNNotificationDefaultActionIdentifier:
            onOpenMirrorForApp?(pkg)
        default:
            if response.actionIdentifier.hasPrefix(Self.actionCustomPrefix),
               let idx = Int(response.actionIdentifier.dropFirst(Self.actionCustomPrefix.count)) {
                onSendAction?(key, idx)
            }
        }
        completionHandler()
    }

    // MARK: - Categories & Attachments

    private func registerBaseCategories(on center: UNUserNotificationCenter) {
        let replyAction = UNTextInputNotificationAction(
            identifier: Self.actionReplyId,
            title: "Reply",
            options: [],
            textInputButtonTitle: "Send",
            textInputPlaceholder: "Reply…"
        )
        let defaultCat = UNNotificationCategory(
            identifier: Self.categoryDefault,
            actions: [],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let replyCat = UNNotificationCategory(
            identifier: Self.categoryReplyable,
            actions: [replyAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let answerAction = UNNotificationAction(
            identifier: Self.actionCallAnswer,
            title: "Answer",
            options: []
        )
        let declineAction = UNNotificationAction(
            identifier: Self.actionCallDecline,
            title: "Decline",
            options: [.destructive]
        )
        let callCat = UNNotificationCategory(
            identifier: Self.categoryCall,
            actions: [answerAction, declineAction],
            intentIdentifiers: [],
            options: []
        )
        categoryLock.lock()
        registeredCategories[Self.categoryDefault] = defaultCat
        registeredCategories[Self.categoryReplyable] = replyCat
        registeredCategories[Self.categoryCall] = callCat
        let all = Set(registeredCategories.values)
        categoryLock.unlock()
        center.setNotificationCategories(all)
    }

    private func ensureDynamicCategory(
        for payload: NotifPostedPayload,
        categoryId: String,
        on center: UNUserNotificationCenter
    ) {
        categoryLock.lock()
        if registeredCategories[categoryId] != nil {
            categoryLock.unlock()
            return
        }
        var actions: [UNNotificationAction] = []
        if payload.actions.contains(where: { $0.hasRemoteInput }) {
            actions.append(UNTextInputNotificationAction(
                identifier: Self.actionReplyId,
                title: "Reply",
                options: [],
                textInputButtonTitle: "Send",
                textInputPlaceholder: "Reply…"
            ))
        }
        let nonReply = payload.actions.filter { !$0.hasRemoteInput }.prefix(3 - actions.count)
        for act in nonReply {
            actions.append(UNNotificationAction(
                identifier: "\(Self.actionCustomPrefix)\(act.index)",
                title: act.title,
                options: []
            ))
        }
        let cat = UNNotificationCategory(
            identifier: categoryId,
            actions: actions,
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        registeredCategories[categoryId] = cat
        let all = Set(registeredCategories.values)
        categoryLock.unlock()
        center.setNotificationCategories(all)
    }

    private func buildAttachment(largeIconBase64: String?, pkg: String) -> UNNotificationAttachment? {
        let tempURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("db_notif_\(UUID().uuidString).png")

        if let b64 = largeIconBase64, let data = Data(base64Encoded: b64) {
            do {
                try data.write(to: tempURL, options: .atomic)
                return try UNNotificationAttachment(identifier: "avatar", url: tempURL, options: nil)
            } catch {
                try? FileManager.default.removeItem(at: tempURL)
            }
        }

        if let cached = cachedAppIconURL(for: pkg) {
            do {
                try FileManager.default.copyItem(at: cached, to: tempURL)
                return try UNNotificationAttachment(identifier: "appicon", url: tempURL, options: nil)
            } catch {
                try? FileManager.default.removeItem(at: tempURL)
            }
        }
        return nil
    }

    private func iconCacheURL(for pkg: String) -> URL {
        let safe = pkg.replacingOccurrences(of: "/", with: "_")
        return iconCacheDir.appendingPathComponent("\(safe).png")
    }

    private func cachedAppIconURL(for pkg: String) -> URL? {
        let url = iconCacheURL(for: pkg)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    private func recordSeenApp(pkg: String, appName: String) {
        if seenApps[pkg] != appName {
            if Thread.isMainThread {
                seenApps[pkg] = appName
            } else {
                DispatchQueue.main.async {
                    self.seenApps[pkg] = appName
                }
            }
        }
    }

    private func saveSettings() {
        defaults.set(Array(mutedPackages), forKey: Self.keyMuted)
        defaults.set(seenApps, forKey: Self.keySeen)
        defaults.set(showMessageContent, forKey: Self.keyShowContent)
        defaults.set(notificationsPaused, forKey: Self.keyPaused)
    }
}
