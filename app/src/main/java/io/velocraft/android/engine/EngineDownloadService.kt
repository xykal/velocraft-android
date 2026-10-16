package io.velocraft.android.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.velocraft.android.GameActivity
import io.velocraft.android.util.VeloLogger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Android Foreground Service for downloading OpenJDK 21 Runtime + Minecraft 1.20.4 Engine, Libraries, Mods & Assets.
 *
 * Ensures the download continues running even when the user switches to other apps,
 * and AUTOMATICALLY removes the Push Notification (`STOP_FOREGROUND_REMOVE` + `nm.cancel`)
 * as soon as the download completes (`percent >= 100`) or stops.
 * Strictly zero emojis.
 */
class EngineDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "xyverse_minecraft_download_channel"
        private const val NOTIFICATION_ID = 2041
        private const val EXTRA_SKIP_MUSIC_LITE = "extra_skip_music_lite"

        @Volatile
        var latestState: ResumableDownloadEngine.DownloadProgressState =
            ResumableDownloadEngine.DownloadProgressState(
                isRunning = false,
                percent = 0,
                phaseTitle = "Siap Mengunduh Engine",
                detailText = "Ketuk 'UNDUH / LANJUTKAN ENGINE & OPENJDK 21' untuk memulai"
            )
            private set

        private val listeners = CopyOnWriteArrayList<(ResumableDownloadEngine.DownloadProgressState) -> Unit>()

        fun addListener(listener: (ResumableDownloadEngine.DownloadProgressState) -> Unit) {
            listeners.add(listener)
            listener(latestState)
        }

        fun removeListener(listener: (ResumableDownloadEngine.DownloadProgressState) -> Unit) {
            listeners.remove(listener)
        }

        fun dismissAllNotifications(context: Context) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
                nm.cancelAll()
            }
        }

        fun startOrResume(context: Context, skipMusicLite: Boolean) {
            ResumableDownloadEngine.setSkipMusicLiteEnabled(context, skipMusicLite)
            val intent = Intent(context, EngineDownloadService::class.java).apply {
                putExtra(EXTRA_SKIP_MUSIC_LITE, skipMusicLite)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                VeloLogger.error("Failed to start EngineDownloadService", t)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var floatingOverlay: FloatingDownloadOverlay? = null
    private var isWorkerActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        floatingOverlay = FloatingDownloadOverlay(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If engine is already 100% ready, immediately remove any lingering notification and exit
        if (ResumableDownloadEngine.inspectEngineReadiness(this).isReadyToLaunch) {
            dismissForegroundAndNotification()
            return START_NOT_STICKY
        }

        val skipMusic = intent?.getBooleanExtra(
            EXTRA_SKIP_MUSIC_LITE,
            ResumableDownloadEngine.isSkipMusicLiteEnabled(this)
        ) ?: ResumableDownloadEngine.isSkipMusicLiteEnabled(this)

        val initialNotif = buildNotification(
            percent = latestState.percent,
            title = "Menyiapkan Unduhan Minecraft 1.20.4 + OpenJDK 21...",
            detail = "Memeriksa berkas yang sudah ada di penyimpanan..."
        )
        startForegroundCompat(initialNotif)
        floatingOverlay?.showOrUpdate(
            percent = latestState.percent,
            phaseTitle = "Menyiapkan Unduhan Minecraft 1.20.4",
            detailText = "Memeriksa berkas yang sudah terunduh..."
        )

        if (!isWorkerActive) {
            isWorkerActive = true
            worker.execute {
                try {
                    ResumableDownloadEngine.runResumableProvisioning(
                        context = applicationContext,
                        skipMusicLite = skipMusic
                    ) { state ->
                        publishState(state)
                    }
                } finally {
                    isWorkerActive = false
                    mainHandler.post {
                        dismissForegroundAndNotification()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun publishState(state: ResumableDownloadEngine.DownloadProgressState) {
        latestState = state
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!state.isRunning || state.percent >= 100) {
            // Download finished or paused -> immediately remove push notification & floating overlay!
            nm.cancel(NOTIFICATION_ID)
            mainHandler.post {
                floatingOverlay?.dismiss()
                for (l in listeners) {
                    l(state)
                }
                dismissForegroundAndNotification()
            }
            return
        }

        val notif = buildNotification(
            percent = state.percent,
            title = state.phaseTitle,
            detail = state.detailText,
            ongoing = true
        )
        nm.notify(NOTIFICATION_ID, notif)

        mainHandler.post {
            floatingOverlay?.showOrUpdate(
                percent = state.percent,
                phaseTitle = state.phaseTitle,
                detailText = state.detailText
            )
            for (l in listeners) {
                l(state)
            }
        }
    }

    private fun dismissForegroundAndNotification() {
        runCatching {
            floatingOverlay?.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            nm.cancelAll()
            stopSelf()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            VeloLogger.warn("startForeground fallback: ${t.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Xyverse Minecraft Engine Downloader",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menampilkan progres unduhan data Minecraft Java 1.20.4 + OpenJDK 21 di latar belakang"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        percent: Int,
        title: String,
        detail: String,
        ongoing: Boolean = true
    ): Notification {
        val openIntent = Intent(this, GameActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Minecraft 1.20.4 - $title ($percent%)")
            .setContentText(detail)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        floatingOverlay?.dismiss()
        dismissAllNotifications(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
