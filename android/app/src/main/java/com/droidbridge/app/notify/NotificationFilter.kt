package com.droidbridge.app.notify

import com.droidbridge.app.net.NotifPostedPayload

data class NotificationFingerprint(
    val key: String,
    val title: String?,
    val text: String?,
    val lastMessageTimestamp: Long
)

/**
 * Pure Kotlin filtering, deduplication, and offline queueing logic for Android notifications.
 */
class NotificationFilter(
    private val ownPackageName: String,
    private val maxQueueSize: Int = 100,
    private val maxQueueAgeMs: Long = 3_600_000L // 1 hour
) {
    private val lock = Any()
    private val lastFingerprints = HashMap<String, NotificationFingerprint>()
    private val offlineQueue = ArrayDeque<QueuedNotification>()

    private data class QueuedNotification(
        val enqueuedAtMs: Long,
        val payload: NotifPostedPayload
    )

    fun shouldSkip(
        pkg: String,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        title: String?,
        text: String?,
        hasMessages: Boolean,
        isCall: Boolean = false
    ): Boolean {
        if (pkg == ownPackageName) return true
        // Incoming/ongoing call notifications are inherently ongoing; they are routed through the
        // dedicated call channel instead of being dropped here.
        if (isOngoing && !isCall) return true
        if (isGroupSummary) return true
        if (title.isNullOrBlank() && text.isNullOrBlank() && !hasMessages) return true
        return false
    }

    /**
     * Returns true if this notification's content is new or changed since the last emission.
     */
    fun acceptIfChanged(payload: NotifPostedPayload): Boolean = synchronized(lock) {
        val lastMsgTime = payload.messages.maxOfOrNull { it.t } ?: payload.postTime
        val fp = NotificationFingerprint(
            key = payload.key,
            title = payload.title,
            text = payload.text,
            lastMessageTimestamp = lastMsgTime
        )
        val prev = lastFingerprints[payload.key]
        if (prev == fp) return false
        lastFingerprints[payload.key] = fp
        true
    }

    fun onRemoved(key: String) = synchronized(lock) {
        lastFingerprints.remove(key)
        offlineQueue.removeAll { it.payload.key == key }
    }

    fun enqueueOffline(payload: NotifPostedPayload, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        // Replace existing queued entry with same key so latest update wins
        offlineQueue.removeAll { it.payload.key == payload.key }
        while (offlineQueue.size >= maxQueueSize) {
            offlineQueue.removeFirst()
        }
        offlineQueue.addLast(QueuedNotification(nowMs, payload))
    }

    fun drainOfflineQueue(nowMs: Long = System.currentTimeMillis()): List<NotifPostedPayload> = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        val items = offlineQueue.map { it.payload }
        offlineQueue.clear()
        items
    }

    private fun pruneExpiredLocked(nowMs: Long) {
        while (offlineQueue.isNotEmpty() && nowMs - offlineQueue.first().enqueuedAtMs > maxQueueAgeMs) {
            offlineQueue.removeFirst()
        }
    }
}
