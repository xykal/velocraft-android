package io.velocraft.android

import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import io.velocraft.android.auth.PlayerSessionManager
import io.velocraft.android.bridge.FabricStateBridge
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.controls.GyroAimController
import io.velocraft.android.controls.ProHudOverlayView
import io.velocraft.android.engine.DirectBootProvisioner
import io.velocraft.android.engine.FirstBootSplashView
import io.velocraft.android.engine.JvmOptimizer
import io.velocraft.android.engine.VoxelWorldView
import io.velocraft.android.util.VeloLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Minecraft (Java Porting Edition) Direct-to-Game Activity ("Tanpa Launcher").
 *
 * Writes detailed boot & runtime telemetry to `/storage/emulated/0/Android/media/io.velocraft.android/log/latest.log`.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var gameSurfaceView: SurfaceView
    private lateinit var voxelWorldView: VoxelWorldView
    private lateinit var proHudOverlay: ProHudOverlayView
    private var firstBootSplash: FirstBootSplashView? = null
    private lateinit var gyroController: GyroAimController
    private lateinit var fabricStateBridge: FabricStateBridge
    private lateinit var provisioner: DirectBootProvisioner

    @Volatile
    private var jvmStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VeloLogger.info("GameActivity.onCreate() started.")

        val hw = JvmOptimizer.detectHardware(this)
        VeloLogger.info(
            "Hardware Detected: ABI=${hw.abi}, 64Bit=${hw.is64Bit}, SoC=${hw.socModel}, " +
                "TotalRAM=${hw.totalRamMb}MB, AllocatedHeap=${hw.allocatedHeapMb}MB, " +
                "Cores=${hw.totalCores}, Renderer=${hw.recommendedRendererLib}"
        )

        provisioner = DirectBootProvisioner(this)
        gyroController = GyroAimController(this)

        val rootContainer = FrameLayout(this)

        // 1. Hardware SurfaceView bound to ANativeWindow for Native OpenGL ES / Vulkan JVM rendering
        gameSurfaceView = SurfaceView(this).apply {
            holder.addCallback(this@GameActivity)
        }

        // 2. Built-in 120 FPS Interactive 3D Voxel World View (active until external libjli.so takes over)
        voxelWorldView = VoxelWorldView(this)

        // 3. Pro PvP Multi-Touch HUD Overlay View
        proHudOverlay = ProHudOverlayView(this, gyroController)

        rootContainer.addView(
            gameSurfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootContainer.addView(
            voxelWorldView,
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

        // 4. Show sleek 1x setup overlay only if engine is not yet provisioned
        if (!provisioner.isEngineProvisioned()) {
            VeloLogger.info("First boot detected; attaching FirstBootSplashView.")
            firstBootSplash = FirstBootSplashView(this).also { splash ->
                rootContainer.addView(
                    splash,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }

        // CRITICAL FIX: Always call setContentView(rootContainer) BEFORE touching window.decorView or insetsController!
        setContentView(rootContainer)
        VeloLogger.info("setContentView(rootContainer) completed.")

        // Post immersive fullscreen & high refresh rate request after DecorView is attached
        window.decorView.post {
            enableImmersiveHighRefreshDisplaySafely()
        }

        // 5. Start listening for real-time GUI/In-Game state packets from Companion Fabric Mod
        fabricStateBridge = FabricStateBridge(activityScope) { telemetryState ->
            runOnUiThread {
                proHudOverlay.updateTelemetry(telemetryState)
            }
        }
        fabricStateBridge.startListening()
        VeloLogger.info("FabricStateBridge UDP listener started on 127.0.0.1:28465.")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        VeloLogger.info("SurfaceView surfaceCreated (${frame.width()}x${frame.height()}).")
        NativeEngineBridge.setSurface(holder.surface, frame.width(), frame.height())

        if (!jvmStarted) {
            jvmStarted = true
            bootEngineDirectly()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        VeloLogger.info("SurfaceView surfaceChanged (${width}x${height}, format=$format).")
        NativeEngineBridge.setSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        VeloLogger.info("SurfaceView surfaceDestroyed.")
        NativeEngineBridge.setSurface(null, 0, 0)
    }

    private fun bootEngineDirectly() {
        activityScope.launch {
            try {
                VeloLogger.info("Starting DirectBootProvisioner.ensureProvisioned()...")
                provisioner.ensureProvisioned { progress ->
                    VeloLogger.info("Provisioner [${progress.stepIndex}/${progress.totalSteps}] (${progress.percent}%): ${progress.statusText}")
                    runOnUiThread {
                        firstBootSplash?.updateProgress(progress)
                    }
                }
            } catch (t: Throwable) {
                VeloLogger.error("Provisioner encountered non-fatal error", t)
            } finally {
                firstBootSplash?.let { splash ->
                    (splash.parent as? FrameLayout)?.removeView(splash)
                    firstBootSplash = null
                    VeloLogger.info("Removed FirstBootSplashView; entering interactive viewport.")
                }
            }

            // Launch Embedded OpenJDK (arm64-v8a or armeabi-v7a) on dedicated Native Render Thread
            Thread({
                try {
                    val hw = JvmOptimizer.detectHardware(this@GameActivity)
                    val jliLib = File(provisioner.jreDir, "lib/libjli.so")
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

                    VeloLogger.info("Prepared JVM args (${jvmArgs.size} arguments) for player '${session.username}'.")

                    if (jliLib.exists()) {
                        VeloLogger.info("Found libjli.so at ${jliLib.absolutePath}; launching KnotClient...")
                        runOnUiThread {
                            voxelWorldView.visibility = View.GONE
                        }
                        val exitCode = NativeEngineBridge.launchDirectJvm(jliLib.absolutePath, jvmArgs)
                        VeloLogger.info("JVM exited with code: $exitCode")
                    } else {
                        val msg = "OpenJDK (${hw.abi}) belum ditemukan di ${jliLib.absolutePath} — Menjalankan Mode Sandbox 3D Voxel & Pro HUD."
                        VeloLogger.warn(msg)
                        runOnUiThread {
                            voxelWorldView.engineStatusLine = msg
                            voxelWorldView.postInvalidateOnAnimation()
                        }
                    }
                } catch (t: Throwable) {
                    VeloLogger.error("Error in VeloCraft-JVM-Main thread", t)
                }
            }, "VeloCraft-JVM-Main").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
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
                    VeloLogger.info("Display refresh rate set to ${highestMode.refreshRate}Hz (${highestMode.physicalWidth}x${highestMode.physicalHeight}).")
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
        gyroController.start()
    }

    override fun onPause() {
        gyroController.stop()
        super.onPause()
    }

    override fun onDestroy() {
        VeloLogger.info("GameActivity.onDestroy() called.")
        fabricStateBridge.stopListening()
        gyroController.stop()
        activityScope.cancel()
        super.onDestroy()
    }
}
