package io.velocraft.android.bridge

import android.view.Surface

/**
 * Kotlin wrapper around VeloCraft's lock-free C++20 JNI bridge (libvelocraft_bridge.so).
 * Provides sub-millisecond input dispatch to LWJGL3/GLFW and direct OpenJDK 21 ARM64 boot.
 */
object NativeEngineBridge {

    init {
        try {
            System.loadLibrary("velocraft_bridge")
        } catch (e: UnsatisfiedLinkError) {
            // Ignored in unit test / preview environments where NDK shared lib isn't loaded
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
    external fun nativeSendKey(glfwKeyCode: Int, action: Int, mods: Int = 0)
    external fun nativeSendMouseButton(button: Int, action: Int, mods: Int = 0)
    external fun nativeSendCameraDelta(deltaX: Float, deltaY: Float)
    external fun nativeSendCursorPos(x: Float, y: Float)
    external fun nativeSetCursorGrabbed(grabbed: Boolean)
    external fun nativeConfigureRendererEnv(jreHomePath: String, rendererLibName: String, cacheDirPath: String)
    external fun nativeLaunchDirectJvm(jliLibPath: String, jvmArgsArray: Array<String>): Int
    external fun nativeGetArchitectureAbi(): String

    /**
     * Helper to select a hotbar slot (1-9) with a rapid press+release pulse.
     */
    fun tapHotbarSlot(slotIndexZeroBased: Int) {
        val key = KEY_1 + slotIndexZeroBased.coerceIn(0, 8)
        nativeSendKey(key, GLFW_PRESS, 0)
        nativeSendKey(key, GLFW_RELEASE, 0)
    }
}
