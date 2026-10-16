package io.velocraft.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.velocraft.android.auth.PlayerSessionManager
import io.velocraft.android.bridge.FabricStateBridge
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.controls.GyroAimController
import io.velocraft.android.controls.ProHudOverlayView
import io.velocraft.android.controls.QuickSettingsSheet
import io.velocraft.android.engine.DirectBootProvisioner
import io.velocraft.android.engine.EngineDownloadService
import io.velocraft.android.engine.FloatingDownloadOverlay
import io.velocraft.android.engine.JvmOptimizer
import io.velocraft.android.engine.MainMenuView
import io.velocraft.android.util.VeloLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Minecraft (Java Porting Edition) Main Console & Direct Game Activity.
 *
 * Flow:
 * 1. Opens into a clean, non-neon Main Menu (`MainMenuView`) that validates all required files
 *    (Client 1.20.4, Libraries, Fabric Loader, 7 Optimization Mods, Assets, Native Bridge).
 * 2. Downloads run inside `EngineDownloadService` (Foreground Push Notification + HTTP Range `.part`
 *    auto-resume + optional Floating Overlay over other apps).
 * 3. Only when 100% of files are verified does the "MAIN SEKARANG" button unlock to launch the JVM.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var gameSurfaceView: SurfaceView
    private lateinit var proHudOverlay: ProHudOverlayView
    private lateinit var mainMenuView: MainMenuView
    private lateinit var gyroController: GyroAimController
    private lateinit var fabricStateBridge: FabricStateBridge
    private lateinit var provisioner: DirectBootProvisioner

    @Volatile
    private var jvmStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VeloLogger.info("GameActivity.onCreate() started (v0.5.0 Clean UI + Resumable Foreground Downloader).")

        val hw = JvmOptimizer.detectHardware(this)
        VeloLogger.info(
            "Hardware Detected: ABI=${hw.abi}, 64Bit=${hw.is64Bit}, SoC=${hw.socModel}, " +
                "TotalRAM=${hw.totalRamMb}MB, AllocatedHeap=${hw.allocatedHeapMb}MB, " +
                "Cores=${hw.totalCores}, Renderer=${hw.recommendedRendererLib}"
        )

        provisioner = DirectBootProvisioner(this)
        provisioner.prepareDirectoriesAndConfigs()
        gyroController = GyroAimController(this)

        val rootContainer = FrameLayout(this)

        // 1. Hardware SurfaceView bound to ANativeWindow for Native OpenGL ES / Vulkan JVM rendering
        gameSurfaceView = SurfaceView(this).apply {
            holder.addCallback(this@GameActivity)
        }

        // 2. Pro PvP Multi-Touch HUD Overlay View (Hidden while in Main Menu unless editing HUD layout)
        proHudOverlay = ProHudOverlayView(this, gyroController).apply {
            visibility = View.GONE
            onExitHudEditor = {
                visibility = View.GONE
                mainMenuView.visibility = View.VISIBLE
            }
        }

        // 3. Modern Clean Main Menu Console (Strictly validates readiness before allowing launch)
        mainMenuView = MainMenuView(
            context = this,
            provisioner = provisioner,
            onStartOrResumeDownload = { includeFullMusic ->
                requestPostNotificationPermissionIfNeeded()
                EngineDownloadService.startOrResumeDownload(this, includeFullMusic)
            },
            onPauseDownload = {
                EngineDownloadService.pauseDownload(this)
            },
            onLaunchVerifiedGame = {
                launchVerifiedMinecraftJvm()
            },
            onOpenHudEditor = {
                mainMenuView.visibility = View.GONE
                proHudOverlay.isEditMode = true
                proHudOverlay.visibility = View.VISIBLE
                Toast.makeText(
                    this,
                    "Geser tombol untuk mengatur posisi. Ketuk 'SELESAI / HUD' di kanan atas untuk menyimpan.",
                    Toast.LENGTH_LONG
                ).show()
            },
            onOpenSettingsAndLogs = {
                QuickSettingsSheet.show(this, proHudOverlay, gyroController) {
                    proHudOverlay.resetToDefaultLayout()
                }
            },
            onRequestOverlayAndNotifPermissions = {
                requestPostNotificationPermissionIfNeeded()
                if (!FloatingDownloadOverlay.canDrawOverlays(this)) {
                    FloatingDownloadOverlay.requestOverlayPermission(this)
                } else {
                    Toast.makeText(
                        this,
                        "Izin Tampil di Atas Aplikasi Lain & Push Notifikasi sudah aktif!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        rootContainer.addView(
            gameSurfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootContainer.addView(
            proHudOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootContainer.addView(
            mainMenuView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootContainer)

        window.decorView.post {
            enableImmersiveHighRefreshDisplaySafely()
            requestPostNotificationPermissionIfNeeded()
        }

        // Subscribe to Foreground Download Service updates
        EngineDownloadService.snapshotListener = { snapshot ->
            runOnUiThread {
                mainMenuView.refreshState(snapshot)
                FloatingDownloadOverlay.showOrUpdate(this@GameActivity, snapshot)
            }
        }

        fabricStateBridge = FabricStateBridge(activityScope) { telemetryState ->
            runOnUiThread {
                proHudOverlay.updateTelemetry(telemetryState)
            }
        }
        fabricStateBridge.startListening()
    }

    private fun requestPostNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        VeloLogger.info("SurfaceView surfaceCreated (${frame.width()}x${frame.height()}).")
        NativeEngineBridge.setSurface(holder.surface, frame.width(), frame.height())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeEngineBridge.setSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeEngineBridge.setSurface(null, 0, 0)
    }

    private fun launchVerifiedMinecraftJvm() {
        if (jvmStarted) return
        val jliLib = File(provisioner.jreDir, "lib/libjli.so")
        if (!jliLib.exists()) {
            VeloLogger.warn("Launch blocked: OpenJDK runtime (${provisioner.jreDir.name}/lib/libjli.so) belum terpasang.")
            Toast.makeText(
                this,
                "File game & mod sudah siap! Menunggu pemasangan paket OpenJDK Runtime (${provisioner.jreDir.name}). Cek Log untuk detail.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        jvmStarted = true
        mainMenuView.visibility = View.GONE
        proHudOverlay.isEditMode = false
        proHudOverlay.visibility = View.VISIBLE

        Thread({
            try {
                val hw = JvmOptimizer.detectHardware(this@GameActivity)
                NativeEngineBridge.configureRendererEnv(
                    jreHomePath = provisioner.jreDir.absolutePath,
                    rendererLibName = hw.recommendedRendererLib,
                    cacheDirPath = cacheDir.absolutePath
                )

                val session = PlayerSessionManager(this@GameActivity).getActiveSession()
                val jvmArgs = JvmOptimizer.buildDirectBootJvmArgs(
                    context = this@GameActivity,
                    gameDir = provisioner.gameDir,
                    assetsDir = provisioner.assetsDir,
                    nativesDir = provisioner.nativesDir,
                    classpath = buildClasspathString(provisioner.librariesDir),
                    playerUsername = session.username,
                    playerUuid = session.uuid,
                    accessToken = session.accessToken
                )

                VeloLogger.info("Booting KnotClient with ${jvmArgs.size} JVM args for '${session.username}'...")
                val exitCode = NativeEngineBridge.launchDirectJvm(jliLib.absolutePath, jvmArgs)
                VeloLogger.info("JVM exited with code: $exitCode")
            } catch (t: Throwable) {
                VeloLogger.error("Error in VeloCraft-JVM-Main thread", t)
            }
        }, "VeloCraft-JVM-Main").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun buildClasspathString(librariesDir: File): String {
        if (!librariesDir.exists()) return ""
        return librariesDir.walkTopDown()
            .filter { it.isFile && it.extension == "jar" }
            .joinToString(File.pathSeparator) { it.absolutePath }
    }

    private fun enableImmersiveHighRefreshDisplaySafely() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val highestMode = display?.supportedModes?.maxByOrNull { it.refreshRate }
                if (highestMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = highestMode.modeId
                    window.attributes = params
                }
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
        } catch (t: Throwable) {
            VeloLogger.warn("Non-fatal error applying immersive display flags: ${t.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        mainMenuView.refreshState()
        gyroController.start()
    }

    override fun onPause() {
        gyroController.stop()
        super.onPause()
    }

    override fun onDestroy() {
        if (EngineDownloadService.snapshotListener != null) {
            EngineDownloadService.snapshotListener = null
        }
        fabricStateBridge.stopListening()
        gyroController.stop()
        activityScope.cancel()
        super.onDestroy()
    }
}
