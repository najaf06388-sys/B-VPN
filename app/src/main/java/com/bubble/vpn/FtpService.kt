package com.bubble.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class FtpService : Service() {

    companion object {
        const val PORT = 2222
        private const val CHANNEL = "bubble_channel"
        private const val NOTIF_ID = 1

        /** 0 = off, 1 = starting, 2 = running */
        @Volatile var state: Int = 0
        @Volatile var lastError: String? = null
    }

    private var server: FtpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        startServer()
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (server != null) return
        lastError = null
        state = 1
        acquireLocks()
        val s = FtpServer(Environment.getExternalStorageDirectory(), PORT)
        server = s
        s.start { err ->
            if (err == null) {
                state = 2
            } else {
                lastError = err
                state = 0
                handler.post { stopSelf() }
            }
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        releaseLocks()
        state = 0
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BubbleVPN:ftp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BubbleVPN:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "BubbleVPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        return b.setContentTitle("BubbleVPN is running")
            .setContentText("Sharing your files on port $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
