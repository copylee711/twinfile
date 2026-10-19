package app.twinfile.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import app.twinfile.ui.MainActivity

/** Keeps the process alive and shows progress while [Ops] has running jobs. */
class TransferService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val running = Ops.running
            if (running.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            getSystemService(NotificationManager::class.java).notify(ID, build(running))
            handler.postDelayed(this, 700)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "文件操作", NotificationManager.IMPORTANCE_LOW))
        val n = build(Ops.running)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun build(running: List<OpJob>): Notification {
        val job = running.firstOrNull()
        val s = job?.stats
        val pct = if (s != null && s.totalBytes > 0) (s.doneBytes * 100 / s.totalBytes).toInt().coerceIn(0, 100) else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job?.title ?: "文件操作")
            .setContentText(if (running.size > 1) "${s?.current ?: ""}（共 ${running.size} 个任务）" else s?.current ?: "")
            .setProgress(100, pct, s == null || s.totalBytes == 0L)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL = "ops"
        const val ID = 1
    }
}
