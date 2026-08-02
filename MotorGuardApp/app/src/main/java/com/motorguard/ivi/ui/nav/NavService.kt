package com.motorguard.ivi.ui.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.motorguard.ivi.MainActivity
import com.motorguard.ivi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the [NavSession] (and therefore the whole process) alive while a
 * route is being guided.
 *
 * [NavSession] already survives a tab switch on its own — its coroutines run on an app scope, not
 * a `viewModelScope`, so destroying the Nav fragment doesn't clear it. This service covers the
 * *other* case: when MotorGuard is backgrounded (another app in front), an ongoing "Navigating"
 * notification is what stops Android from freezing/killing the process and stalling the drive.
 *
 * It owns no navigation state — that all lives in [NavSession]. The service is purely the lifetime
 * anchor + the notification, mirroring how the voice services (owner D) are thin platform shells
 * over the real logic. It is started by [NavSession.startGuidance] and stopped by
 * [NavSession.endGuidance] (or arrival), via the [start]/[stop] helpers below.
 */
class NavService : Service() {

    // Scoped to the service; drives the ongoing notification off NavSession.state. Cancelled in
    // onDestroy so it never outlives the foreground.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // If the OS spun the service up before any fragment touched the session, wire it now.
        NavSession.ensureStarted(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter the foreground immediately with the type declared in the manifest ("location").
        // On Android 14+ that type additionally requires a granted location runtime permission;
        // at a permission-less desk demo (simulated location) startForeground throws instead of
        // starting. We swallow that: the in-process session still survives tab switches — only the
        // backgrounded-process guarantee is lost. See TODO(on-device) in the PR notes.
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(NavSession.state.value)) }
            .onFailure { Log.w(TAG, "startForeground denied (location permission?) — session still runs in-process", it) }

        // Keep the notification honest: refresh it when the destination changes. Guidance-ending
        // is handled by NavSession calling stop(), so we don't self-stop here.
        observeState()
        return START_NOT_STICKY
    }

    private fun observeState() {
        scope.launch {
            NavSession.state
                .map { (it.phase as? NavPhase.Guiding)?.route?.destination?.name }
                .distinctUntilChanged()
                .collect { destination ->
                    if (destination != null) {
                        notificationManager()
                            .notify(NOTIFICATION_ID, buildNotification(NavSession.state.value))
                    }
                }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(state: NavUiState): Notification {
        val destination = (state.phase as? NavPhase.Guiding)?.route?.destination?.name
        // Tapping the notification brings the Nav tab forward (singleTask + EXTRA_TAB), the same
        // route the voice overlay uses to surface a tab.
        val open = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.NAV.name)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val content = PendingIntent.getActivity(this, 0, open, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.nav_notification_title))
            .setContentText(
                destination?.let { getString(R.string.nav_notification_to, it) }
                    ?: getString(R.string.nav_notification_active),
            )
            .setContentIntent(content)
            .setOngoing(true)          // not swipe-dismissable while guiding
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.nav_channel_name),
            NotificationManager.IMPORTANCE_LOW,   // silent: an ongoing status, not an alert
        ).apply { description = getString(R.string.nav_channel_desc) }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "MotorGuardNav"
        private const val CHANNEL_ID = "nav_guidance"
        private const val NOTIFICATION_ID = 42

        /**
         * Raise the foreground service. Called from [NavSession.startGuidance] while the Nav tab is
         * in front (a user tap), so the background-start restriction does not apply.
         */
        fun start(context: Context) {
            val intent = Intent(context, NavService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Drop the notification and stop the service. Called when guidance ends or the car arrives. */
        fun stop(context: Context) {
            context.stopService(Intent(context, NavService::class.java))
        }
    }
}
