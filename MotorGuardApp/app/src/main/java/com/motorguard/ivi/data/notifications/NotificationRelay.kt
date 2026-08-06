package com.motorguard.ivi.data.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One posted notification, reduced to what a head unit should show at a glance. */
data class AppNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** True for things like the media transport row — present, but not worth interrupting for. */
    val ongoing: Boolean,
)

/**
 * Notifications, surfaced inside the app because nothing else can show them.
 *
 * Motor Guard is the launcher and runs with the system bars hidden ([com.motorguard.ivi.MainActivity]
 * calls `hide(systemBars())` for the kiosk look). On AAOS both the heads-up notification and the
 * Notification Center are drawn *by CarSystemUI in those bars* — so with them hidden, a posted
 * notification has nowhere to appear. It is not that notifications are missing; they are
 * underneath. This is the app taking responsibility for showing them itself.
 *
 * A [NotificationListenerService] is the only supported way to read them. It has to be enabled
 * explicitly — being a platform app is not enough:
 *
 *     adb shell cmd notification allow_listener \
 *         com.motorguard.ivi/com.motorguard.ivi.data.notifications.NotificationRelayService
 */
object NotificationRelay {

    private val _active = MutableStateFlow<List<AppNotification>>(emptyList())
    val active: StateFlow<List<AppNotification>> = _active.asStateFlow()

    /** The newest arrival, for the heads-up banner. Cleared once shown. */
    private val _latest = MutableStateFlow<AppNotification?>(null)
    val latest: StateFlow<AppNotification?> = _latest.asStateFlow()

    fun consumeLatest() {
        _latest.value = null
    }

    internal fun replaceAll(items: List<AppNotification>) {
        _active.value = items
    }

    internal fun onPosted(item: AppNotification) {
        _active.value = (_active.value.filterNot { it.key == item.key } + item)
            .sortedByDescending { it.postedAt }
        // Ongoing notifications are status, not news — a transport row or a running foreground
        // service should not throw a banner over the driver's map every time it updates.
        if (!item.ongoing) _latest.value = item
    }

    internal fun onRemoved(key: String) {
        _active.value = _active.value.filterNot { it.key == key }
        if (_latest.value?.key == key) _latest.value = null
    }
}

/**
 * The listener itself. Deliberately thin: it converts and forwards, and holds no state of its
 * own, because the system binds and unbinds it on its own schedule.
 */
class NotificationRelayService : NotificationListenerService() {

    override fun onListenerConnected() {
        // The binding can come long after the notifications did, so the current set is read once
        // on connect rather than waiting for the next post.
        runCatching {
            NotificationRelay.replaceAll(
                activeNotifications.orEmpty().mapNotNull { it.toAppNotification() }
                    .sortedByDescending { it.postedAt },
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.toAppNotification()?.let { NotificationRelay.onPosted(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { NotificationRelay.onRemoved(it) }
    }
}

/**
 * Skips anything with no text to show. A notification carrying only an icon and a content view
 * is real, but there is nothing meaningful to render for it on a dashboard.
 */
private fun StatusBarNotification.toAppNotification(): AppNotification? {
    val extras = notification?.extras ?: return null
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    if (title.isBlank() && text.isBlank()) return null

    return AppNotification(
        key = key,
        packageName = packageName,
        title = title.ifBlank { packageName },
        text = text,
        postedAt = postTime,
        ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
    )
}
