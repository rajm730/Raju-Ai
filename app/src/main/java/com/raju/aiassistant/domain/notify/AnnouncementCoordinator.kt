package com.raju.aiassistant.domain.notify

import com.raju.aiassistant.domain.assistant.Speaker
import com.raju.aiassistant.domain.assistant.announcementText
import com.raju.aiassistant.domain.model.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A message waiting for the user's Read / Skip decision. */
data class PendingAnnouncement(val id: Long, val item: IncomingItem)

/**
 * Announces incoming messages one at a time. With "ask before reading" on (the default) it only says
 * *who* wrote, then waits for an explicit Read (tap) — Skip, Stop, the timeout or the notification
 * being dismissed mean the content is never read. Message text is kept only in memory until then.
 */
class AnnouncementCoordinator(
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    private val settings: StateFlow<UserSettings>,
    private val consentTimeoutMs: Long = 30_000,
    private val maxQueue: Int = 10,
) {
    private val _pending = MutableStateFlow<PendingAnnouncement?>(null)
    val pending: StateFlow<PendingAnnouncement?> = _pending.asStateFlow()

    private val queue = ArrayDeque<IncomingItem>()
    private var worker: Job? = null
    private var timeout: Job? = null
    private var nextId = 1L

    /** Called for every parsed message notification. */
    fun onMessage(item: IncomingItem) {
        val current = _pending.value
        if (current != null && current.item.threadId == item.threadId) {
            // Another message in the same chat while we wait: update the preview, don't ask twice.
            _pending.value = current.copy(item = item.copy(count = current.item.count + 1))
            return
        }
        val idx = queue.indexOfFirst { it.threadId == item.threadId }
        if (idx >= 0) {
            queue[idx] = item.copy(count = queue[idx].count + 1)
        } else {
            if (queue.size >= maxQueue) queue.removeFirst()
            queue.addLast(item)
        }
        pump()
    }

    /** The notification was dismissed or read elsewhere: forget it. */
    fun onRemoved(key: String) {
        queue.removeAll { it.key == key }
        if (_pending.value?.item?.key == key) clearPending()
    }

    /** [onDone] is always called once: right away if there is nothing to read, otherwise after speech ends or is stopped. */
    fun read(id: Long, onDone: () -> Unit = {}) {
        val p = _pending.value
        if (p == null || p.id != id) { onDone(); return }
        clearPending(resume = false)
        worker = scope.launch {
            try {
                speaker.speak(readPreviewText(p.item))
            } finally {
                onDone()
            }
            worker = null
            pump()
        }
    }

    fun skip(id: Long) {
        if (_pending.value?.id == id) clearPending()
    }

    /** Stop button / Pause / Safety Mode: nothing queued or pending is read. */
    fun stopAll() {
        queue.clear()
        worker?.cancel()
        worker = null
        clearPending(resume = false)
    }

    private fun clearPending(resume: Boolean = true) {
        timeout?.cancel()
        timeout = null
        _pending.value = null
        if (resume) pump()
    }

    private fun pump() {
        if (worker != null || _pending.value != null) return
        val item = queue.removeFirstOrNull() ?: return
        val s = settings.value
        if (!s.announcementsActive) {
            queue.clear()
            return
        }
        worker = scope.launch {
            // Don't talk over the assistant; give up waiting after a while.
            withTimeoutOrNull(20_000) { while (speaker.speaking.value) delay(300) }
            when {
                item.preview == null -> speaker.speak(hiddenPreviewText(item))
                !s.askBeforeReading -> speaker.speak(announcementText(item.appLabel, whoText(item), item.preview))
                else -> {
                    val p = PendingAnnouncement(nextId++, item)
                    _pending.value = p
                    speaker.speak(askToReadText(item))
                    timeout = scope.launch {
                        delay(consentTimeoutMs)
                        if (_pending.value?.id == p.id) clearPending()
                    }
                }
            }
            worker = null
            pump()
        }
    }
}
