package org.aero.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat

/*
 * The terminal lock screen. Android does not let an app replace its own lock screen, so the launcher
 * puts a screen of its own ON TOP of it (an activity allowed to show while locked), the way lock-screen
 * apps do. Android's lock screen stays underneath and still does the real unlocking: PIN, fingerprint,
 * Extend Unlock. Pieces:
 *   LockService      - stays running (with its notification, Android requires it) and, each time the
 *                      screen goes off, opens LockActivity, so it is already there when the screen comes on;
 *   LockBoot         - starts the service again after a reboot or an update of the launcher;
 *   MediaListener    - the "notification access" Android asks for before telling an app what is playing.
 * Opening an activity from the background needs "display over other apps" (Settings.canDrawOverlays).
 */
class LockService : Service() {
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // not over a call (the in-call screen owns the display), and only with the permission
            val audio = getSystemService(AudioManager::class.java)
            if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return
            if (!Settings.canDrawOverlays(c)) return
            runCatching {
                startActivity(Intent(c, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lock screen", NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false) })
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock_status)
            .setContentTitle(if (Prefs(this).lang.value == "pt") "tela de bloqueio aero ligada" else "aero lock screen is on")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { runCatching { unregisterReceiver(screenOff) }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "lock"

        /** Starts or stops the service to match the setting. */
        fun sync(ctx: Context) {
            val i = Intent(ctx, LockService::class.java)
            if (Prefs(ctx).lockScreen.value) runCatching { ContextCompat.startForegroundService(ctx, i) }
            else ctx.stopService(i)
        }

        fun canShow(ctx: Context) = Settings.canDrawOverlays(ctx)
        fun hasMedia(ctx: Context) = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(ctx, MediaListener::class.java).flattenToString()) == true
    }
}

/** After a reboot (or an update of the launcher) the lock screen comes back by itself. */
class LockBoot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = LockService.sync(c)
}

/**
 * Here so Android lets the lock screen read what is playing (MediaSessionManager wants a notification
 * listener of ours). The notifications themselves are only counted, per app: nothing of their text.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() = count()
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) = count()
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) = count()
    override fun onListenerDisconnected() { counts.value = emptyMap() }

    private fun count() {
        counts.value = runCatching { activeNotifications.orEmpty() }.getOrDefault(emptyArray())
            .filter { it.isClearable && !it.isOngoing }
            .groupingBy { it.packageName }.eachCount()
    }

    companion object {
        /** Notifications on the phone right now: package -> how many. */
        val counts = androidx.compose.runtime.mutableStateOf<Map<String, Int>>(emptyMap())
    }
}
