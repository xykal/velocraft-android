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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.velocraft.android.GameActivity
import io.velocraft.android.util.VeloLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Foreground Service for downloading Minecraft 1.20.4 Engine, Libraries, Mods & Assets.
 *
 * Ensures the download continues running even when the user switches to other apps,
 * locks the screen, or minimizes the app, displaying real-time progress (MB / Total MB, Speed MB/s)
 * and Pause/Resume controls directly in the Android Push Notification bar!
 */
class EngineDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "minecraft_port_download_channel"
        const val NOTIFICATION_ID = 4201

        const val ACTION_START_OR_RESUME = "io.velocraft.android.action.START_OR_RESUME"
        const val ACTION_PAUSE           = "io.velocraft.android.action.PAUSE"
        const val EXTRA_FULL_ASSETS      = "extra_full_assets"

        @Volatile
        var latestSnapshot: ResumableDownloadEngine.DownloadSnapshot? = null
            private set

        @Volatile
        var snapshotListener: ((ResumableDownloadEngine.DownloadSnapshot) -> Unit)? = null

        fun startOrResumeDownload(context: Context, includeFullMusicAssets: Boolean) {
            val intent = Intent(context, EngineDownloadService::class.java).apply {
                action = ACTION_START_OR_RESUME
                putExtra(EXTRA_FULL_ASSETS, includeFullMusicAssets)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseDownload(context: Context) {
            val intent = Intent(context, EngineDownloadService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private val pauseFlag = AtomicBoolean(false)
    private var lastNotificationUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                VeloLogger.info("EngineDownloadService received ACTION_PAUSE.")
                pauseFlag.set(true)
                latestSnapshot?.let { snap ->
                    val pausedSnap = snap.copy(
                        isRunning = false,
                        isPaused = true,
                        speedBytesPerSec = 0L,
                        currentFileName = "Unduhan dijeda — Tekan Lanjutkan kapan saja"
                    )
                    latestSnapshot = pausedSnap
                    snapshotListener?.invoke(pausedSnap)
                    updateNotification(pausedSnap)
                }
                return START_NOT_STICKY
            }

            ACTION_START_OR_RESUME, null -> {
                val fullAssets = intent?.getBooleanExtra(EXTRA_FULL_ASSETS, true) ?: true
                if (downloadJob?.isActive == true) {
                    return START_STICKY
                }
                pauseFlag.set(false)
                val initialSnap = ResumableDownloadEngine.DownloadSnapshot(
                    isRunning = true,
                    isPaused = false,
                    isCompleted = false,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    completedFiles = 0,
                    totalFiles = 1,
                    speedBytesPerSec = 0L,
                    currentFileName = "Menyiapkan antrean unduhan..."
                )
                startForegroundSafely(buildNotification(initialSnap))

                val provisioner = DirectBootProvisioner(applicationContext)
                val engine = ResumableDownloadEngine(provisioner)

                downloadJob = serviceScope.launch {
                    try {
                        engine.executeResumableDownload(
                            includeFullMusicAssets = fullAssets,
                            pauseFlag = pauseFlag
                        ) { snapshot ->
                            latestSnapshot = snapshot
                            snapshotListener?.invoke(snapshot)
                            val now = System.currentTimeMillis()
                            if (now - lastNotificationUpdateMs >= 500L || !snapshot.isRunning || snapshot.isCompleted) {
                                lastNotificationUpdateMs = now
                                updateNotification(snapshot)
                            }
                        }
                    } catch (t: Throwable) {
                        VeloLogger.error("EngineDownloadService error", t)
                        val errSnap = ResumableDownloadEngine.DownloadSnapshot(
                            isRunning = false,
                            isPaused = true,
                            isCompleted = false,
                            downloadedBytes = latestSnapshot?.downloadedBytes ?: 0L,
                            totalBytes = latestSnapshot?.totalBytes ?: 0L,
                            completedFiles = latestSnapshot?.completedFiles ?: 0,
                            totalFiles = latestSnapshot?.totalFiles ?: 1,
                            speedBytesPerSec = 0L,
                            currentFileName = "Koneksi terputus — Tekan Lanjutkan untuk meneruskan",
                            errorMessage = t.message
                        )
                        latestSnapshot = errSnap
                        snapshotListener?.invoke(errSnap)
                        updateNotification(errSnap)
                    } finally {
                        if (latestSnapshot?.isCompleted == true) {
                            stopForeground(STOP_FOREGROUND_DETACH)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundSafely(notification: Notification) {
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

    private fun updateNotification(snapshot: ResumableDownloadEngine.DownloadSnapshot) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(snapshot))
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(snapshot: ResumableDownloadEngine.DownloadSnapshot): Notification {
        val openAppIntent = Intent(this, GameActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dlMb = snapshot.downloadedBytes / (1024L * 1024L)
        val totalMb = (snapshot.totalBytes / (1024L * 1024L)).coerceAtLeast(1L)
        val speedMbps = "%.2f".format(snapshot.speedBytesPerSec / (1024f * 1024f))

        val title = when {
            snapshot.isCompleted -> "Minecraft 1.20.4 Siap Dimainkan!"
            snapshot.isPaused -> "Unduhan Dijeda (${snapshot.percent}% • $dlMb / $totalMb MB)"
            else -> "Mengunduh Minecraft 1.20.4 (${snapshot.percent}% • $speedMbps MB/s)"
        }

        val subtitle = "$dlMb MB / $totalMb MB (${snapshot.completedFiles}/${snapshot.totalFiles} file) • ${snapshot.currentFileName}"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(pendingOpen)
            .setOngoing(snapshot.isRunning)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (!snapshot.isCompleted) {
            builder.setProgress(100, snapshot.percent, snapshot.totalBytes == 0L && snapshot.isRunning)

            if (snapshot.isRunning) {
                val pauseIntent = Intent(this, EngineDownloadService::class.java).apply {
                    action = ACTION_PAUSE
                }
                val pendingPause = PendingIntent.getService(
                    this,
                    1,
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_pause, "Jeda", pendingPause)
            } else {
                val resumeIntent = Intent(this, EngineDownloadService::class.java).apply {
                    action = ACTION_START_OR_RESUME
                }
                val pendingResume = PendingIntent.getService(
                    this,
                    2,
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_play, "Lanjutkan", pendingResume)
            }
        } else {
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Unduhan Data Game Minecraft",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menampilkan progres unduhan engine & asset Minecraft di latar belakang"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
