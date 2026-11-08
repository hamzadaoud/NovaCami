package com.novacami.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service (type "camera"): keeps the camera, the encoder and the network stream running when the screen
 * is off or Novacami is in the background. It shows an ongoing notification with "Be right back" and "Stop".
 * The engine itself lives in [EngineHost]; this service only keeps the process alive and awake.
 */
class StreamService : Service() {
    private var engine: StreamEngine? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = EngineHost.get(this)
        createChannel()
        engine?.serviceListener = { handler.post { onEngineChanged() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = engine
        if (e == null) { stopSelf(); return START_NOT_STICKY }
        // Android requires startForeground() right after startForegroundService(), whatever the command is.
        val inForeground = try { enterForeground(); true } catch (t: Throwable) {
            Log.w(TAG, "could not start as foreground service", t); false
        }
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_BRB -> { e.showBrb(!e.brb); updateNotification() }
            else -> {
                if (inForeground) acquireCpu()
                e.start()
                if (inForeground) updateNotification() else stopSelf()   // without the notification we can't keep running: stream while the app is open
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(NOTIF_ID, n)
    }

    /** Streaming stopped (from the app, the PC or the notification) -> end the service; restarts are ignored by the engine. */
    private fun onEngineChanged() {
        val e = engine ?: return
        if (e.streaming) { updateNotification(); return }
        handler.postDelayed({ if (engine?.streaming == false) stopEverything() }, 400)
    }

    private fun stopEverything() {
        engine?.stop()
        releaseCpu()
        removeNotification()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun removeNotification() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine?.serviceListener = null
        releaseCpu()
        super.onDestroy()
    }

    /** Keeps the CPU running while the screen is off (otherwise the phone can doze and the stream stops). */
    private fun acquireCpu() {
        try {
            if (cpuLock == null) {
                cpuLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novacami:stream").also { it.setReferenceCounted(false) }
            }
            if (cpuLock?.isHeld == false) cpuLock?.acquire()
        } catch (e: Exception) { Log.w(TAG, "cpu lock", e) }
    }

    private fun releaseCpu() {
        try { cpuLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
    }

    // ---------- notification ----------
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Novacami is streaming your camera"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun updateNotification() {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) { }
    }

    private fun buildNotification(): Notification {
        val brb = engine?.brb == true
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            flags
        )
        fun action(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, StreamService::class.java).setAction(a), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_novacam)
            .setContentTitle(if (brb) "Novacami: Be right back is on" else "Novacami is streaming")
            .setContentText(if (brb) "Your PC shows the Be right back screen" else "Your phone is working as a webcam")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (brb) "I'm back" else "Be right back", action(ACTION_BRB, 1))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
    }

    companion object {
        const val ACTION_START = "com.novacami.app.START"
        const val ACTION_STOP = "com.novacami.app.STOP"
        const val ACTION_BRB = "com.novacami.app.BRB"
        private const val CHANNEL = "novacami_stream"
        private const val NOTIF_ID = 17
        private const val TAG = "NovacamiService"
    }
}
