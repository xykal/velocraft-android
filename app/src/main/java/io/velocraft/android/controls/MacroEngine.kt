package io.velocraft.android.controls

import android.os.Handler
import android.os.Looper
import io.velocraft.android.bridge.NativeEngineBridge
import kotlin.random.Random

/**
 * High-Speed PvP Macro, CPS Counter, Bridging Assist & Complete PC 104-Key GLFW Lookup Table.
 */
class MacroEngine {

    private val handler = Handler(Looper.getMainLooper())
    var fastPlaceEnabled: Boolean = false
    var utilityMacroSlotZeroBased: Int = 1 // Slot 2 (Rod / Pearl / Cobweb)
    var primaryWeaponSlotZeroBased: Int = 0 // Slot 1 (Sword)

    private val leftClickTimestamps = ArrayList<Long>(32)
    private val rightClickTimestamps = ArrayList<Long>(32)

    private var isLeftAutoClicking = false
    private var isHoldingRightClick = false
    private var leftTickCallback: (() -> Unit)? = null
    private var rightTickCallback: (() -> Unit)? = null

    private val leftAutoClickRunnable = object : Runnable {
        override fun run() {
            if (!isLeftAutoClicking) return
            registerManualLeftClick()
            NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, true)
            NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, false)
            leftTickCallback?.invoke()
            val delayMs = 68L + Random.nextLong(-6L, 7L) // ~14 CPS randomized
            handler.postDelayed(this, delayMs)
        }
    }

    private val fastPlaceRunnable = object : Runnable {
        override fun run() {
            if (isHoldingRightClick && fastPlaceEnabled) {
                registerManualRightClick()
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_RIGHT, true)
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_RIGHT, false)
                rightTickCallback?.invoke()
                handler.postDelayed(this, 65L)
            }
        }
    }

    fun registerManualLeftClick() {
        val now = System.currentTimeMillis()
        leftClickTimestamps.add(now)
        pruneTimestamps(leftClickTimestamps, now)
    }

    fun registerManualRightClick() {
        val now = System.currentTimeMillis()
        rightClickTimestamps.add(now)
        pruneTimestamps(rightClickTimestamps, now)
    }

    fun getLeftCps(): Int {
        val now = System.currentTimeMillis()
        pruneTimestamps(leftClickTimestamps, now)
        return leftClickTimestamps.size
    }

    fun getRightCps(): Int {
        val now = System.currentTimeMillis()
        pruneTimestamps(rightClickTimestamps, now)
        return rightClickTimestamps.size
    }

    private fun pruneTimestamps(list: ArrayList<Long>, now: Long) {
        while (list.isNotEmpty() && now - list[0] > 1000L) {
            list.removeAt(0)
        }
    }

    fun setLeftAutoClickActive(active: Boolean, onTick: (() -> Unit)? = null) {
        isLeftAutoClicking = active
        leftTickCallback = onTick
        handler.removeCallbacks(leftAutoClickRunnable)
        if (active) {
            handler.postDelayed(leftAutoClickRunnable, 70L)
        }
    }

    fun setRightFastPlaceActive(active: Boolean, onTick: (() -> Unit)? = null) {
        isHoldingRightClick = active
        rightTickCallback = onTick
        handler.removeCallbacks(fastPlaceRunnable)
        if (active && fastPlaceEnabled) {
            handler.postDelayed(fastPlaceRunnable, 65L)
        }
    }

    fun executeSprintResetCombo(onComplete: (() -> Unit)? = null) {
        NativeEngineBridge.tapHotbarSlot(utilityMacroSlotZeroBased)
        handler.postDelayed({
            registerManualRightClick()
            NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_RIGHT, true)
            NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_RIGHT, false)
        }, 25L)
        handler.postDelayed({
            NativeEngineBridge.tapHotbarSlot(primaryWeaponSlotZeroBased)
            registerManualLeftClick()
            NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, true)
            NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, false)
            onComplete?.invoke()
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
