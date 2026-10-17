package io.velocraft.android.bridge

import android.view.Surface
import io.velocraft.android.util.VeloLogger

/**
 * Bulletproof Kotlin wrapper around VeloCraft's lock-free C++20 JNI bridge (`libvelocraft_bridge.so`).
 * Logs every native initialization step to `/storage/emulated/0/Android/media/id.xyverse.minecraft/log/`
 * and guards all JNI calls so native library errors can never crash the UI thread.
 */
object NativeEngineBridge {

    data class BootTelemetrySnapshot(
        val stageIndex: Int,
        val totalStages: Int,
        val percent: Int,
        val loadedClasses: Int,
        val targetSplashClasses: Int,
        val classesPerSec: Int,
        val jvmUsedRamMb: Int,
        val jvmMaxRamMb: Int,
        val elapsedSec: Int,
        val etaMojangSplashSec: Int,
        val etaMinecraftMenuSec: Int,
        val mojangSplashReached: Boolean,
        val titleScreenReached: Boolean,
        val swapBuffersCount: Int,
        val stageTitle: String,
        val subTaskDetail: String,
        val activeClassName: String
    )

    @Volatile
    var isNativeLoaded: Boolean = false
        private set

    interface InteractiveInputListener {
        fun onKeyAction(keyCode: Int, action: Int)
        fun onMouseAction(button: Int, action: Int)
        fun onCameraDelta(deltaX: Float, deltaY: Float)
    }

    @Volatile
    var inputListener: InteractiveInputListener? = null

    init {
        try {
            try {
                System.loadLibrary("velocraft_turbo")
            } catch (_: Throwable) {
            }
            System.loadLibrary("velocraft_bridge")
            isNativeLoaded = true
            VeloLogger.info("Native libraries libvelocraft_turbo.so + libvelocraft_bridge.so loaded (ABI: ${getArchitectureAbi()}).")
        } catch (t: Throwable) {
            isNativeLoaded = false
            VeloLogger.error("Failed to load libvelocraft_bridge.so", t)
        }
    }

    // GLFW Action Constants
    const val GLFW_RELEASE = 0
    const val GLFW_PRESS   = 1

    // GLFW Mouse Buttons
    const val MOUSE_LEFT   = 0
    const val MOUSE_RIGHT  = 1
    const val MOUSE_MIDDLE = 2

    // GLFW Keycodes for Pro PvP HUD
    const val KEY_SPACE        = 32
    const val KEY_SLASH        = 47
    const val KEY_1            = 49
    const val KEY_2            = 50
    const val KEY_3            = 51
    const val KEY_4            = 52
    const val KEY_5            = 53
    const val KEY_6            = 54
    const val KEY_7            = 55
    const val KEY_8            = 56
    const val KEY_9            = 57
    const val KEY_A            = 65
    const val KEY_D            = 68
    const val KEY_E            = 69 // Inventory
    const val KEY_F            = 70 // Swap Offhand
    const val KEY_Q            = 81 // Drop Item
    const val KEY_S            = 83
    const val KEY_T            = 84 // Chat
    const val KEY_W            = 87
    const val KEY_ESCAPE       = 256
    const val KEY_TAB          = 258
    const val KEY_F1           = 290
    const val KEY_F3           = 292 // Debug Info
    const val KEY_F5           = 294 // Third-person toggle
    const val KEY_LEFT_SHIFT   = 340 // Sneak
    const val KEY_LEFT_CONTROL = 341 // Sprint

    external fun nativeSetSurface(surface: Surface?, width: Int, height: Int)
    external fun nativeSendKey(glfwKeyCode: Int, action: Int, mods: Int)
    external fun nativeSendChar(codepoint: Int, mods: Int)
    external fun nativeSendMouseButton(button: Int, action: Int, mods: Int)
    external fun nativeSendCameraDelta(deltaX: Float, deltaY: Float)
    external fun nativeSendCursorPos(x: Float, y: Float)
    external fun nativeSetCursorGrabbed(grabbed: Boolean)
    external fun nativeIsCursorGrabbed(): Boolean
    external fun nativeSendScroll(xOffset: Float, yOffset: Float)
    external fun nativeConfigureRendererEnv(jreHomePath: String, rendererLibName: String, cacheDirPath: String)
    external fun nativeLaunchDirectJvm(jliLibPath: String, jvmArgsArray: Array<String>): Int
    external fun nativeGetArchitectureAbi(): String
    external fun nativeGetBootTelemetrySnapshot(): String
    external fun nativeSetResolutionScale(scale: Float)
    external fun nativeGetResolutionScale(): Float
    external fun nativeTriggerTurboBoost(): String

    fun setResolutionScale(scale: Float) {
        if (!isNativeLoaded) return
        try {
            nativeSetResolutionScale(scale.coerceIn(0.50f, 1.00f))
        } catch (t: Throwable) {
            VeloLogger.error("nativeSetResolutionScale error", t)
        }
    }

    fun getResolutionScale(): Float {
        if (!isNativeLoaded) return 0.75f
        return try {
            nativeGetResolutionScale().coerceIn(0.50f, 1.00f)
        } catch (_: Throwable) {
            0.75f
        }
    }

    fun triggerTurboBoost(): String {
        if (!isNativeLoaded) return "Turbo C++ siap"
        return try {
            nativeTriggerTurboBoost()
        } catch (_: Throwable) {
            "Turbo C++ aktif"
        }
    }

    fun getBootTelemetry(): BootTelemetrySnapshot? {
        if (!isNativeLoaded) return null
        return try {
            val raw = nativeGetBootTelemetrySnapshot()
            val parts = raw.split("|", limit = 17)
            if (parts.size < 17) return null
            BootTelemetrySnapshot(
                stageIndex = parts[0].toIntOrNull() ?: 1,
                totalStages = parts[1].toIntOrNull() ?: 10,
                percent = parts[2].toIntOrNull() ?: 10,
                loadedClasses = parts[3].toIntOrNull() ?: 0,
                targetSplashClasses = parts[4].toIntOrNull() ?: 5400,
                classesPerSec = parts[5].toIntOrNull() ?: 0,
                jvmUsedRamMb = parts[6].toIntOrNull() ?: 0,
                jvmMaxRamMb = parts[7].toIntOrNull() ?: 768,
                elapsedSec = parts[8].toIntOrNull() ?: 0,
                etaMojangSplashSec = parts[9].toIntOrNull() ?: 35,
                etaMinecraftMenuSec = parts[10].toIntOrNull() ?: 60,
                mojangSplashReached = parts[11] == "1",
                titleScreenReached = parts[12] == "1",
                swapBuffersCount = parts[13].toIntOrNull() ?: 0,
                stageTitle = parts[14],
                subTaskDetail = parts[15],
                activeClassName = parts[16]
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun setSurface(surface: Surface?, width: Int, height: Int) {
        if (!isNativeLoaded) return
        try {
            nativeSetSurface(surface, width, height)
        } catch (t: Throwable) {
            VeloLogger.error("nativeSetSurface error", t)
        }
    }

    fun sendKey(glfwKeyCode: Int, action: Int, mods: Int = 0) {
        inputListener?.onKeyAction(glfwKeyCode, action)
        if (!isNativeLoaded) return
        try {
            nativeSendKey(glfwKeyCode, action, mods)
        } catch (t: Throwable) {
            VeloLogger.error("nativeSendKey error", t)
        }
    }

    fun sendKeyEvent(glfwKeyCode: Int, pressed: Boolean) {
        sendKey(glfwKeyCode, if (pressed) GLFW_PRESS else GLFW_RELEASE, 0)
    }

    fun sendChar(codepoint: Int, mods: Int = 0) {
        if (!isNativeLoaded) return
        try {
            nativeSendChar(codepoint, mods)
        } catch (t: Throwable) {
            VeloLogger.error("nativeSendChar error", t)
        }
    }

    fun sendMouseButton(button: Int, action: Int, mods: Int = 0) {
        inputListener?.onMouseAction(button, action)
        if (!isNativeLoaded) return
        try {
            nativeSendMouseButton(button, action, mods)
        } catch (t: Throwable) {
            VeloLogger.error("nativeSendMouseButton error", t)
        }
    }

    fun sendMouseButton(button: Int, pressed: Boolean) {
        sendMouseButton(button, if (pressed) GLFW_PRESS else GLFW_RELEASE, 0)
    }

    fun sendCameraDelta(deltaX: Float, deltaY: Float) {
        inputListener?.onCameraDelta(deltaX, deltaY)
        if (!isNativeLoaded) return
        try {
            nativeSendCameraDelta(deltaX, deltaY)
        } catch (t: Throwable) {
            VeloLogger.error("nativeSendCameraDelta error", t)
        }
    }

    fun sendMouseDelta(deltaX: Float, deltaY: Float) {
        sendCameraDelta(deltaX, deltaY)
    }

    fun sendCursorPos(x: Float, y: Float) {
        if (!isNativeLoaded) return
        try {
            nativeSendCursorPos(x, y)
        } catch (t: Throwable) {
            VeloLogger.error("nativeSendCursorPos error", t)
        }
    }

    fun setCursorGrabbed(grabbed: Boolean) {
        if (!isNativeLoaded) return
        try {
            nativeSetCursorGrabbed(grabbed)
        } catch (t: Throwable) {
            VeloLogger.error("nativeSetCursorGrabbed error", t)
        }
    }

    fun isCursorGrabbed(): Boolean {
        if (!isNativeLoaded) return false
        return try {
            nativeIsCursorGrabbed()
        } catch (_: Throwable) {
            false
        }
    }

    fun sendScroll(xOffset: Float, yOffset: Float) {
        if (!isNativeLoaded) return
        try {
            nativeSendScroll(xOffset, yOffset)
        } catch (t: Throwable) {
            VeloLogger.error("nativeSendScroll error", t)
        }
    }

    fun configureRendererEnv(jreHomePath: String, rendererLibName: String, cacheDirPath: String) {
        if (!isNativeLoaded) return
        try {
            nativeConfigureRendererEnv(jreHomePath, rendererLibName, cacheDirPath)
        } catch (t: Throwable) {
            VeloLogger.error("nativeConfigureRendererEnv error", t)
        }
    }

    fun launchDirectJvm(jliLibPath: String, jvmArgsArray: Array<String>): Int {
        if (!isNativeLoaded) return -1
        return try {
            nativeLaunchDirectJvm(jliLibPath, jvmArgsArray)
        } catch (t: Throwable) {
            VeloLogger.error("nativeLaunchDirectJvm error", t)
            -2
        }
    }

    fun getArchitectureAbi(): String {
        if (!isNativeLoaded) return "unloaded"
        return try {
            nativeGetArchitectureAbi()
        } catch (_: Throwable) {
            "unknown"
        }
    }

    fun tapHotbarSlot(slotIndexZeroBased: Int) {
        val key = KEY_1 + slotIndexZeroBased.coerceIn(0, 8)
        sendKey(key, GLFW_PRESS, 0)
        sendKey(key, GLFW_RELEASE, 0)
    }
}
