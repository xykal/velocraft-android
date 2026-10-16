package io.velocraft.android.controls

import android.os.Handler
import android.os.Looper
import io.velocraft.android.bridge.NativeEngineBridge

/**
 * High-Speed PvP Macro, Bridging Assist & Complete PC 104-Key GLFW Lookup Table.
 */
class MacroEngine {

    private val handler = Handler(Looper.getMainLooper())
    var fastPlaceEnabled: Boolean = false
    var utilityMacroSlotZeroBased: Int = 1 // Slot 2 (Rod / Pearl / Cobweb)
    var primaryWeaponSlotZeroBased: Int = 0 // Slot 1 (Sword)

    private var isHoldingRightClick = false

    private val fastPlaceRunnable = object : Runnable {
        override fun run() {
            if (isHoldingRightClick && fastPlaceEnabled) {
                NativeEngineBridge.sendMouseButton(
                    NativeEngineBridge.MOUSE_RIGHT,
                    NativeEngineBridge.GLFW_PRESS
                )
                NativeEngineBridge.sendMouseButton(
                    NativeEngineBridge.MOUSE_RIGHT,
                    NativeEngineBridge.GLFW_RELEASE
                )
                handler.postDelayed(this, 65L)
            }
        }
    }

    fun onRightClickDown() {
        isHoldingRightClick = true
        if (fastPlaceEnabled) {
            handler.removeCallbacks(fastPlaceRunnable)
            handler.postDelayed(fastPlaceRunnable, 65L)
        }
    }

    fun onRightClickUp() {
        isHoldingRightClick = false
        handler.removeCallbacks(fastPlaceRunnable)
    }

    fun triggerQuickUtilityMacro(onHotbarVisualUpdate: (Int) -> Unit) {
        NativeEngineBridge.tapHotbarSlot(utilityMacroSlotZeroBased)
        onHotbarVisualUpdate(utilityMacroSlotZeroBased)

        handler.postDelayed({
            NativeEngineBridge.sendMouseButton(
                NativeEngineBridge.MOUSE_RIGHT,
                NativeEngineBridge.GLFW_PRESS
            )
            NativeEngineBridge.sendMouseButton(
                NativeEngineBridge.MOUSE_RIGHT,
                NativeEngineBridge.GLFW_RELEASE
            )
        }, 25L)

        handler.postDelayed({
            NativeEngineBridge.tapHotbarSlot(primaryWeaponSlotZeroBased)
            onHotbarVisualUpdate(primaryWeaponSlotZeroBased)
        }, 65L)
    }

    companion object {
        /**
         * Complete PC Keyboard & Mouse GLFW Keycode Table for Custom HUD Button Binding.
         */
        val BINDABLE_KEYS: LinkedHashMap<String, Int> = linkedMapOf<String, Int>().apply {
            // Core Action Keys
            put("SPACE", 32)
            put("L-SHIFT", 340)
            put("L-CTRL", 341)
            put("L-ALT", 342)
            put("TAB", 258)
            put("ESC", 256)
            put("ENTER", 257)
            put("BACKSPC", 259)
            put("CAPS", 280)

            // Letters A - Z
            for (c in 'A'..'Z') {
                put(c.toString(), c.code)
            }

            // Numbers 0 - 9
            for (n in '0'..'9') {
                put(n.toString(), n.code)
            }

            // Function Keys F1 - F12
            for (f in 1..12) {
                put("F$f", 289 + f)
            }

            // Arrow & Navigation Keys
            put("UP", 265)
            put("DOWN", 264)
            put("LEFT", 263)
            put("RIGHT", 262)
            put("INS", 260)
            put("DEL", 261)
            put("HOME", 268)
            put("END", 269)
            put("PGUP", 266)
            put("PGDN", 267)
        }
    }
}
