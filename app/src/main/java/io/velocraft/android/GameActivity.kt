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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * VeloCraft Direct-to-Game Activity ("Tanpa Launcher").
 *
 * Opening the APK immediately initializes the OpenGL ES 3.2 / Vulkan SurfaceView, overlays the
 * Pro PvP HUD, starts the local Fabric State Bridge, and boots Minecraft Java 1.20.4 (Fabric + Sodium)
 * directly on the embedded ARM64 JVM.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var gameSurfaceView: SurfaceView
    private lateinit var proHudOverlay: ProHudOverlayView
    private var firstBootSplash: FirstBootSplashView? = null
    private lateinit var gyroController: GyroAimController
    private lateinit var fabricStateBridge: FabricStateBridge
    private lateinit var provisioner: DirectBootProvisioner

    @Volatile
    private var jvmStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveHighRefreshDisplay()

        provisioner = DirectBootProvisioner(this)
        gyroController = GyroAimController(this)

        val rootContainer = FrameLayout(this)

        // 1. Hardware SurfaceView bound to ANativeWindow for MobileGlues / GL4ES / Vulkan
        gameSurfaceView = SurfaceView(this).apply {
            holder.addCallback(this@GameActivity)
        }

        // 2. Pro PvP Multi-Touch HUD Overlay View
        proHudOverlay = ProHudOverlayView(this, gyroController)

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

        // Show sleek 1x setup overlay only if engine is not yet provisioned
        if (!provisioner.isEngineProvisioned()) {
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

        setContentView(rootContainer)

        // 3. Start listening for real-time GUI/In-Game state packets from VeloCraft Fabric Mod
        fabricStateBridge = FabricStateBridge(activityScope) { telemetryState ->
            runOnUiThread {
                proHudOverlay.updateTelemetry(telemetryState)
            }
        }
        fabricStateBridge.startListening()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        NativeEngineBridge.nativeSetSurface(holder.surface, frame.width(), frame.height())

        if (!jvmStarted) {
            jvmStarted = true
            bootEngineDirectly()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeEngineBridge.nativeSetSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeEngineBridge.nativeSetSurface(null, 0, 0)
    }

    private fun bootEngineDirectly() {
        activityScope.launch {
            // Silent 1x provisioning on first boot; instant pass-through on subsequent boots
            provisioner.ensureProvisioned { progress ->
                runOnUiThread {
                    firstBootSplash?.updateProgress(progress)
                }
            }
            firstBootSplash?.let { splash ->
                (splash.parent as? FrameLayout)?.removeView(splash)
                firstBootSplash = null
            }

            // Launch Embedded OpenJDK (arm64-v8a or armeabi-v7a) on dedicated Native Render Thread
            Thread({
                val hw = JvmOptimizer.detectHardware(this@GameActivity)
                val jliLib = File(provisioner.jreDir, "lib/libjli.so")
                NativeEngineBridge.nativeConfigureRendererEnv(
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

                if (jliLib.exists()) {
                    NativeEngineBridge.nativeLaunchDirectJvm(jliLib.absolutePath, jvmArgs)
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

    private fun enableImmersiveHighRefreshDisplay() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Request highest supported display refresh rate (90Hz / 120Hz / 144Hz)
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
        fabricStateBridge.stopListening()
        gyroController.stop()
        activityScope.cancel()
        super.onDestroy()
    }
}
