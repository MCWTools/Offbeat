package com.auralis.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.auralis.player.Graph
import com.auralis.player.data.Downloader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the process alive (with a progress notification) while links are being downloaded. */
class DownloadService : Service() {
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(text: String, progress: Int): Notification =
        NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Offbeat · downloading")
            .setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true)
            .setProgress(100, progress, progress <= 0)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, 77, notif("Starting…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running) {
            running = true
            Graph.scope.launch {
                while (Graph.downloader.busy) {
                    val j = Graph.downloader.jobs.firstOrNull { it.state == Downloader.State.WORKING }
                    if (j != null) nm.notify(77, notif(j.message, (j.progress * 100).toInt()))
                    delay(800)
                }
                running = false
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object { const val CH = "auralis_downloads" }
}
