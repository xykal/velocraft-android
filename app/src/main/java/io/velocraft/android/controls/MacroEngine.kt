package io.velocraft.android.controls

import android.os.Handler
import android.os.Looper
import io.velocraft.android.bridge.NativeEngineBridge

/**
 * High-Speed PvP Macro & Bridging Assist Engine for VeloCraft Direct.
 *
 * Supports:
 * 1. FastPlace / Bridging Right-Click Ticker (15 CPS right-click rhythm for Speed-Bridging / Breezily).
 * 2. Quick Utility Swap Macro (e.g., Rod/Snowball/EnderPearl on secondary slot -> Right Click -> Return to Sword Slot 1).
 * 3. Custom GLFW Keybind lookup table so players can bind any HUD button to any keyboard key.
 */
class MacroEngine {

    private val handler = Handler(Looper.getMainLooper())
    var fastPlaceEnabled: Boolean = false
    var utilityMacroSlotZeroBased: Int = 1 // Default Slot 2 (Rod / Pearl / Cobweb)
    var primaryWeaponSlotZeroBased: Int = 0 // Default Slot 1 (Sword)

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
                handler.postDelayed(this, 65L) // ~15.3 CPS right-click bridging rhythm
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

    /**
     * Executes a 1-tap PvP Rod / EnderPearl / Healing combo macro:
     * 1. Switches to utility slot (e.g. Slot 2).
     * 2. Sends Right-Click press & release after 25ms.
     * 3. Switches back to primary Sword slot (Slot 1) after 55ms.
     */
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
         * Complete map of bindable GLFW keys for the In-Game Custom Keybind Picker.
         */
        val BINDABLE_KEYS: LinkedHashMap<String, Int> = linkedMapOf(
            "W (Forward)" to NativeEngineBridge.KEY_W,
            "S (Backward)" to NativeEngineBridge.KEY_S,
            "A (Strafe Left)" to NativeEngineBridge.KEY_A,
            "D (Strafe Right)" to NativeEngineBridge.KEY_D,
            "SPACE (Jump)" to NativeEngineBridge.KEY_SPACE,
            "LEFT SHIFT (Sneak)" to NativeEngineBridge.KEY_LEFT_SHIFT,
            "LEFT CTRL (Sprint)" to NativeEngineBridge.KEY_LEFT_CONTROL,
            "E (Inventory)" to NativeEngineBridge.KEY_E,
            "F (Swap Offhand)" to NativeEngineBridge.KEY_F,
            "Q (Drop Item)" to NativeEngineBridge.KEY_Q,
            "T (Chat)" to NativeEngineBridge.KEY_T,
            "TAB (Player List)" to NativeEngineBridge.KEY_TAB,
            "F1 (Toggle HUD)" to NativeEngineBridge.KEY_F1,
            "F3 (Debug Info)" to NativeEngineBridge.KEY_F3,
            "F5 (Perspective)" to NativeEngineBridge.KEY_F5,
            "ESC (Pause / Close)" to NativeEngineBridge.KEY_ESCAPE,
            "Hotbar Slot 1" to NativeEngineBridge.KEY_1,
            "Hotbar Slot 2" to NativeEngineBridge.KEY_2,
            "Hotbar Slot 3" to NativeEngineBridge.KEY_3,
            "Hotbar Slot 4" to NativeEngineBridge.KEY_4,
            "Hotbar Slot 5" to NativeEngineBridge.KEY_5,
            "Hotbar Slot 6" to NativeEngineBridge.KEY_6,
            "Hotbar Slot 7" to NativeEngineBridge.KEY_7,
            "Hotbar Slot 8" to NativeEngineBridge.KEY_8,
            "Hotbar Slot 9" to NativeEngineBridge.KEY_9
        )
    }
}
