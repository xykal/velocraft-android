package io.velocraft.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.velocraft.android.auth.PlayerSessionManager
import io.velocraft.android.bridge.FabricStateBridge
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.controls.CustomCanvasModalView
import io.velocraft.android.controls.GamepadInputRouter
import io.velocraft.android.controls.GyroAimController
import io.velocraft.android.controls.ProHudOverlayView
import io.velocraft.android.engine.AccountManager
import io.velocraft.android.engine.BootLogOverlayView
import io.velocraft.android.engine.DirectBootProvisioner
import io.velocraft.android.engine.EngineDownloadService
import io.velocraft.android.engine.FloatingDownloadOverlay
import io.velocraft.android.engine.JvmOptimizer
import io.velocraft.android.engine.MainMenuView
import io.velocraft.android.engine.PerformanceBooster
import io.velocraft.android.engine.RendererProfileManager
import io.velocraft.android.engine.ResumableDownloadEngine
import io.velocraft.android.util.SystemImeHelper
import io.velocraft.android.util.VeloLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Minecraft (Java Porting Edition) Main Console & Direct Game Activity.
 *
 * v0.7.8-release Upgrades:
 * - Zero-Relog Background Persistence: Static process-level `jvmRunningInProcess` state + EGL Pbuffer
 *   fallback + Back-gesture interception so briefly leaving the app or swiping Back never destroys
 *   the Activity or restarts the OpenJDK JVM session.
 * - GL4ES 1.1.5 World-Join Safe VBO Guard (`enforceRendererModCompatibility`).
 * - Slide-Out In-Game Sidebar Menu & Maximized Top Telemetry Bar (`ProHudOverlayView`).
 * - Android System IME Keyboard (`SystemImeHelper`) + Microsoft OAuth2 / Offline Account (`AccountManager`).
 * - Live Modrinth v2 Mod Sync & Shareable Control `.JSON` Export/Import.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        @Volatile
        var jvmRunningInProcess: Boolean = false
    }

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var gameSurfaceView: SurfaceView
    private lateinit var mainMenuView: MainMenuView
    private lateinit var bootLogOverlay: BootLogOverlayView
    private lateinit var proHudOverlay: ProHudOverlayView
    private lateinit var customModalView: CustomCanvasModalView
    private lateinit var gyroController: GyroAimController
    private lateinit var gamepadRouter: GamepadInputRouter
    private lateinit var fabricStateBridge: FabricStateBridge
    private lateinit var provisioner: DirectBootProvisioner

    private val downloadListener: (ResumableDownloadEngine.DownloadProgressState) -> Unit = { state ->
        mainMenuView.refreshState(state)
        if (!state.isRunning && state.percent >= 100) {
            EngineDownloadService.dismissAllNotifications(this)
            mainMenuView.showBannerMessage("Seluruh berkas Engine & OpenJDK selesai diunduh. Ketuk MAIN SEKARANG.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VeloLogger.section(
            "BOOT",
            "GameActivity.onCreate() started (v0.7.8-release World-Join VBO Guard + Zero-Relog + Sidebar + Modrinth + IME + JSON)"
        )

        val hw = JvmOptimizer.detectHardware(this)
        val effectiveRenderer = RendererProfileManager.resolveEffectiveBackend(this)
        VeloLogger.section(
            "RENDERER",
            "ABI=${hw.abi}, 64Bit=${hw.is64Bit}, SoC=${hw.socModel}, RAM=${hw.totalRamMb}MB " +
                "(Heap=${hw.allocatedHeapMb}MB), Selected=${RendererProfileManager.getSelectedBackend(this).id}, " +
                "EffectiveRenderer=${effectiveRenderer.displayName} (${effectiveRenderer.nativeLibName})"
        )

        provisioner = DirectBootProvisioner(this)
        if (!jvmRunningInProcess) {
            provisioner.prepareDirectoriesAndConfigs()
        }

        val rootContainer = FrameLayout(this)

        // 1. Hardware SurfaceView bound to ANativeWindow for OpenGL ES / Vulkan rendering
        gameSurfaceView = SurfaceView(this).apply {
            holder.addCallback(this@GameActivity)
        }

        // 2. High-precision 200Hz Gyroscope Aim Controller
        gyroController = GyroAimController(this)

        // 3. Multi-Touch Pro PvP HUD + Slide-Out Sidebar + Maximized Top Stats Bar
        proHudOverlay = ProHudOverlayView(this).apply {
            activeRendererLabel = effectiveRenderer.id.uppercase()
            onGyroToggleRequested = { enabled ->
                gyroController.isEnabled = enabled
            }
            onQuickSettingsRequested = {
                customModalView.openOnTab(CustomCanvasModalView.Tab.RENDERER_ACCOUNT)
            }
            onOpenKeybindEditorRequested = { selectedBtn ->
                customModalView.openOnTab(CustomCanvasModalView.Tab.KEYBIND_CREATOR, selectedBtn)
            }
            onReopenBootLogRequested = {
                bootLogOverlay.toggleLogOverlay()
            }
            onExitHudEditor = {
                if (!jvmRunningInProcess) {
                    visibility = View.GONE
                    mainMenuView.visibility = View.VISIBLE
                    mainMenuView.showBannerMessage("Tata letak HUD disimpan.")
                }
            }
        }

        // 4. Live Boot Console Overlay (prevents blank black screen during JVM/Fabric startup)
        bootLogOverlay = BootLogOverlayView(
            context = this,
            onReturnToLauncher = {
                mainMenuView.visibility = View.VISIBLE
                mainMenuView.refreshState()
            }
        ).apply {
            visibility = View.GONE
        }

        // 5. Physical Gamepad & External Keyboard Router
        gamepadRouter = GamepadInputRouter(proHudOverlay)

        // 6. Clean Matte Non-Neon 6-Page Studio Launcher Console with Vector Icons
        mainMenuView = MainMenuView(this).apply {
            onStartOrResumeDownload = { skipMusicLite ->
                requestPostNotificationsPermissionIfNeeded()
                EngineDownloadService.startOrResume(this@GameActivity, skipMusicLite)
                showBannerMessage("Unduhan berjalan di latar belakang. Notifikasi otomatis hilang saat selesai.")
            }
            onLaunchGameRequested = {
                tryLaunchJvmWhenReady()
            }
            onRequestOverlayPermission = {
                if (!FloatingDownloadOverlay.canDrawOverlays(this@GameActivity)) {
                    FloatingDownloadOverlay.requestOverlayPermission(this@GameActivity)
                } else {
                    showBannerMessage("Izin Tampil di Atas Aplikasi Lain sudah aktif.")
                }
            }
            onOpenVisualHudEditor = {
                mainMenuView.visibility = View.GONE
                proHudOverlay.visibility = View.VISIBLE
                proHudOverlay.enterVisualEditMode()
            }
            onOpenQuickSettings = {
                customModalView.openOnTab(CustomCanvasModalView.Tab.CONTROLS_CURSOR)
            }
            onOpenRendererAndKeybindModal = {
                customModalView.openOnTab(CustomCanvasModalView.Tab.KEYBIND_CREATOR)
            }
            onControlJsonReloadRequested = {
                proHudOverlay.reloadLayoutFromDisk()
            }
        }

        // 7. 100% Custom Canvas Settings, Renderer Picker, Keybind Creator & Log Viewer Modal
        customModalView = CustomCanvasModalView(
            context = this,
            hudView = proHudOverlay,
            gyroController = gyroController,
            onCloseModal = {
                val eff = RendererProfileManager.resolveEffectiveBackend(this)
                proHudOverlay.activeRendererLabel = eff.id.uppercase()
                mainMenuView.refreshState()
                proHudOverlay.postInvalidateOnAnimation()
            },
            onReopenBootLogOverlay = {
                bootLogOverlay.reopenLogOverlay()
            }
        ).apply {
            visibility = View.GONE
        }

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
            bootLogOverlay,
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
        rootContainer.addView(
            customModalView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Attach Android System IME Keyboard Bridge View to Root Container
        SystemImeHelper.attachToRoot(this, rootContainer)

        setContentView(rootContainer)

        // If JVM is already running in this process (e.g. Activity resumed/recreated after app switch),
        // immediately restore the in-game view without relogging!
        val nativeTelemetry = NativeEngineBridge.getBootTelemetry()
        val nativeStage = nativeTelemetry?.stageIndex ?: 1
        if (jvmRunningInProcess || nativeStage >= 3) {
            jvmRunningInProcess = true
            mainMenuView.visibility = View.GONE
            proHudOverlay.visibility = View.VISIBLE
            if (nativeStage < 10) {
                bootLogOverlay.startBootMonitoring()
            } else {
                bootLogOverlay.visibility = View.GONE
            }
            VeloLogger.section("BOOT", "Resumed existing active JVM session without relog (stage=$nativeStage).")
        }

        // Prevent Android Back Gesture / Button from finishing GameActivity while in-game
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        SystemImeHelper.isImeActive() -> {
                            SystemImeHelper.hideKeyboard()
                        }
                        customModalView.visibility == View.VISIBLE -> {
                            customModalView.visibility = View.GONE
                        }
                        proHudOverlay.isEditMode -> {
                            proHudOverlay.exitVisualEditMode()
                        }
                        proHudOverlay.isSidebarDrawerOpen -> {
                            proHudOverlay.isSidebarDrawerOpen = false
                            proHudOverlay.postInvalidateOnAnimation()
                        }
                        jvmRunningInProcess -> {
                            // Send ESC key to Minecraft (Pause Menu / Close Inventory) instead of killing Activity
                            NativeEngineBridge.sendKeyEvent(NativeEngineBridge.KEY_ESCAPE, true)
                            NativeEngineBridge.sendKeyEvent(NativeEngineBridge.KEY_ESCAPE, false)
                        }
                        else -> {
                            moveTaskToBack(true)
                        }
                    }
                }
            }
        )

        window.decorView.post {
            enableImmersiveHighRefreshDisplaySafely()
            requestPostNotificationsPermissionIfNeeded()
        }

        fabricStateBridge = FabricStateBridge(activityScope) { state ->
            runOnUiThread {
                val eff = RendererProfileManager.resolveEffectiveBackend(this@GameActivity)
                proHudOverlay.activeRendererLabel = eff.id.uppercase()
                if (state.clientFps > 0) {
                    proHudOverlay.currentFps = state.clientFps
                }
                if (mainMenuView.visibility != View.VISIBLE) {
                    proHudOverlay.visibility = View.VISIBLE
                }
                proHudOverlay.postInvalidateOnAnimation()
            }
        }
        fabricStateBridge.startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Ensure bringing the app back from Recents or Launcher icon never resets to mainMenuView when JVM is running
        if (jvmRunningInProcess) {
            mainMenuView.visibility = View.GONE
            proHudOverlay.visibility = View.VISIBLE
            proHudOverlay.postInvalidateOnAnimation()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (SystemImeHelper.isImeActive()) {
            return super.dispatchKeyEvent(event)
        }
        if (gamepadRouter.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadRouter.onGenericMotionEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun requestPostNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    2041
                )
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        VeloLogger.section("RENDERER", "SurfaceView surfaceCreated (${frame.width()}x${frame.height()}), jvmRunning=$jvmRunningInProcess.")
        NativeEngineBridge.setSurface(holder.surface, frame.width(), frame.height())
        if (!jvmRunningInProcess) {
            mainMenuView.refreshState()
        } else {
            mainMenuView.visibility = View.GONE
            proHudOverlay.visibility = View.VISIBLE
            proHudOverlay.postInvalidateOnAnimation()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        NativeEngineBridge.setSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Detach ANativeWindow cleanly; native render thread automatically switches to preserved pbufferSurface
        // so leaving the app briefly never crashes or relogs the game session!
        VeloLogger.section("RENDERER", "SurfaceView surfaceDestroyed (preserving JVM & EGL context in background).")
        NativeEngineBridge.setSurface(null, 0, 0)
    }

    private fun tryLaunchJvmWhenReady() {
        val readiness = ResumableDownloadEngine.inspectEngineReadiness(this)
        if (!readiness.isReadyToLaunch) {
            VeloLogger.section(
                "RUNTIME",
                "Engine not yet ready: OpenJDK=${readiness.isOpenJdkRuntimeReady}, " +
                    "ClientJar=${readiness.isClientJarReady}, Mods=${readiness.installedModsCount}, " +
                    "Assets=${readiness.isAssetIndexReady}"
            )
            mainMenuView.showBannerMessage("Berkas Engine/OpenJDK belum lengkap. Ketuk UNDUH / LANJUTKAN terlebih dahulu.")
            return
        }

        if (jvmRunningInProcess) {
            mainMenuView.visibility = View.GONE
            proHudOverlay.visibility = View.VISIBLE
            val nativeTel = NativeEngineBridge.getBootTelemetry()
            if ((nativeTel?.stageIndex ?: 10) < 10) {
                bootLogOverlay.startBootMonitoring()
            }
            return
        }

        val jliPrimary = File(provisioner.jreDir, "lib/libjli.so")
        val jliAlt = File(provisioner.jreDir, "lib/jli/libjli.so")
        val jliLib = when {
            jliPrimary.exists() && jliPrimary.length() > 10_000L -> jliPrimary
            jliAlt.exists() && jliAlt.length() > 10_000L -> jliAlt
            else -> null
        }

        if (jliLib == null) {
            VeloLogger.section("RUNTIME", "libjli.so not found inside ${provisioner.jreDir.absolutePath}")
            mainMenuView.showBannerMessage("OpenJDK Runtime belum terpasang lengkap. Ketuk tombol UNDUH / LANJUTKAN.")
            return
        }

        val hw = JvmOptimizer.detectHardware(this)
        val effectiveRenderer = RendererProfileManager.resolveEffectiveBackend(this)
        val resScale = JvmOptimizer.getResolutionScalePct(this)
        val summaryLine =
            "RAM Heap: ${hw.allocatedHeapMb} MB | Renderer: ${effectiveRenderer.displayName} | Skala: $resScale% | ABI: ${hw.abi}"

        jvmRunningInProcess = true
        runOnUiThread {
            mainMenuView.visibility = View.GONE
            proHudOverlay.visibility = View.VISIBLE
            proHudOverlay.activeRendererLabel = effectiveRenderer.id.uppercase()
            bootLogOverlay.startBootMonitoring()
            bootLogOverlay.updateBootStage(
                title = "TAHAP 1/10: Menyiapkan Direktori Game, Mod Stack & Kompatibilitas Renderer...",
                progress = 8,
                summary = summaryLine
            )
            proHudOverlay.postInvalidateOnAnimation()
        }

        Thread({
            try {
                provisioner.prepareDirectoriesAndConfigs(
                    includeFullMusicAssets = !ResumableDownloadEngine.isSkipMusicLiteEnabled(this)
                )
                PerformanceBooster.syncBoostSettingsToOptionsTxt(this@GameActivity)
                val syncedShaders = RendererProfileManager.syncExternalShaderpacks(this, provisioner.gameDir)
                VeloLogger.section(
                    "STAGE-1",
                    "Direktori & Shaderpack siap (Shader=$syncedShaders, ModAktif=${readiness.installedModsCount})."
                )

                bootLogOverlay.updateBootStage(
                    title = "TAHAP 1/10: Mengonfigurasi Lingkungan OpenJDK 17 & Renderer (${effectiveRenderer.id})...",
                    progress = 12
                )
                NativeEngineBridge.configureRendererEnv(
                    jreHomePath = provisioner.jreDir.absolutePath,
                    rendererLibName = effectiveRenderer.nativeLibName,
                    cacheDirPath = cacheDir.absolutePath
                )

                bootLogOverlay.updateBootStage(
                    title = "TAHAP 2/10: Merakit Classpath JAR Minecraft 1.20.4 & Fabric 0.16.9...",
                    progress = 15
                )
                val classpath = buildClasspathString(provisioner.librariesDir)
                val jarCount = classpath.split(File.pathSeparator).count { it.isNotBlank() }
                VeloLogger.section("STAGE-2", "Berhasil merakit Classpath dengan $jarCount pustaka JAR.")

                val activeAccount = AccountManager.getActiveAccount(this@GameActivity)
                PlayerSessionManager(this@GameActivity).saveOfflineSession(activeAccount.username)
                val jvmArgs = JvmOptimizer.buildDirectBootJvmArgs(
                    context = this@GameActivity,
                    gameDir = provisioner.gameDir,
                    assetsDir = provisioner.assetsDir,
                    nativesDir = provisioner.nativesDir,
                    classpath = classpath,
                    playerUsername = activeAccount.username,
                    playerUuid = activeAccount.uuid,
                    accessToken = activeAccount.accessToken,
                    userType = activeAccount.userType
                )

                VeloLogger.section(
                    "STAGE-2",
                    "Memulai peluncuran langsung OpenJDK 17 HotSpot untuk '${activeAccount.username}' (Tipe=${activeAccount.type.label}, ${jvmArgs.size} argumen, Renderer=${effectiveRenderer.id})"
                )

                val exitCode = NativeEngineBridge.launchDirectJvm(jliLib.absolutePath, jvmArgs)
                VeloLogger.section("RUNTIME", "JVM selesai dengan kode keluar: $exitCode")
                jvmRunningInProcess = false
                bootLogOverlay.notifyJvmFinished(exitCode)
            } catch (t: Throwable) {
                jvmRunningInProcess = false
                VeloLogger.error("Error in VeloCraft-JVM-Main thread", t)
                bootLogOverlay.notifyJvmFinished(-99)
            }
        }, "VeloCraft-JVM-Main").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun buildClasspathString(librariesDir: File): String {
        val entries = ArrayList<String>()
        if (provisioner.lwjglGlfwJar.exists() && provisioner.lwjglGlfwJar.length() > 100_000L) {
            entries.add(provisioner.lwjglGlfwJar.canonicalPath)
        }
        if (librariesDir.exists()) {
            librariesDir.walkTopDown()
                .filter { f ->
                    f.isFile &&
                        f.extension.equals("jar", ignoreCase = true) &&
                        !f.invariantSeparatorsPath.contains("/org/lwjgl/") &&
                        !f.name.equals("fabric-loader-0.16.7.jar", ignoreCase = true)
                }
                .forEach { entries.add(it.canonicalPath) }
        }
        return entries.joinToString(File.pathSeparator)
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
        EngineDownloadService.addListener(downloadListener)
        if (!jvmRunningInProcess) {
            mainMenuView.refreshState()
        } else {
            mainMenuView.visibility = View.GONE
            proHudOverlay.visibility = View.VISIBLE
            proHudOverlay.postInvalidateOnAnimation()
        }
        gyroController.start()
        window.decorView.post {
            enableImmersiveHighRefreshDisplaySafely()
        }
    }

    override fun onPause() {
        EngineDownloadService.removeListener(downloadListener)
        gyroController.stop()
        super.onPause()
    }

    override fun onDestroy() {
        EngineDownloadService.removeListener(downloadListener)
        fabricStateBridge.stopListening()
        gyroController.stop()
        activityScope.cancel()
        super.onDestroy()
    }
}
