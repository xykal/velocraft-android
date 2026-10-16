package io.velocraft.android.controls

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.velocraft.android.bridge.NativeEngineBridge
import kotlin.math.abs

/**
 * Full Physical Gamepad (Xbox, PlayStation, GameSir, Razer Kishi, Generic HID) &
 * External Keyboard Router for Minecraft (Java Porting Edition).
 */
class GamepadInputRouter(
    private val onHotbarSlotChanged: (Int) -> Unit,
    private val onToggleVirtualCursor: () -> Unit,
    private val onMoveVirtualCursorDelta: (dx: Float, dy: Float) -> Unit,
    private val isVirtualCursorActive: () -> Boolean
) {
    constructor(hudView: ProHudOverlayView) : this(
        onHotbarSlotChanged = { _ -> hudView.postInvalidateOnAnimation() },
        onToggleVirtualCursor = { hudView.toggleVirtualCursor() },
        onMoveVirtualCursorDelta = { dx, dy -> hudView.moveVirtualCursorByDelta(dx, dy) },
        isVirtualCursorActive = { hudView.isVirtualCursorEnabled }
    )

    fun onKeyEvent(event: KeyEvent): Boolean = handleKeyEvent(event)
    fun onGenericMotionEvent(event: MotionEvent): Boolean = handleMotionEvent(event)
    private var currentHotbarSlot = 0
    private var stickW = false
    private var stickS = false
    private var stickA = false
    private var stickD = false
    private var stickSprint = false
    private var leftTriggerPressed = false
    private var rightTriggerPressed = false
    private var sneakToggled = false

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        val isJoystick = (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isJoystick) return false

        // 1. Left Analog Stick -> WASD Movement + Auto-Sprint
        val lx = getCenteredAxis(event, MotionEvent.AXIS_X)
        val ly = getCenteredAxis(event, MotionEvent.AXIS_Y)

        val nextW = ly < -0.32f
        val nextS = ly > 0.32f
        val nextA = lx < -0.32f
        val nextD = lx > 0.32f
        val nextSprint = ly < -0.82f

        updateStickKey(NativeEngineBridge.KEY_W, stickW, nextW) { stickW = it }
        updateStickKey(NativeEngineBridge.KEY_S, stickS, nextS) { stickS = it }
        updateStickKey(NativeEngineBridge.KEY_A, stickA, nextA) { stickA = it }
        updateStickKey(NativeEngineBridge.KEY_D, stickD, nextD) { stickD = it }
        updateStickKey(NativeEngineBridge.KEY_LEFT_CONTROL, stickSprint, nextSprint) { stickSprint = it }

        // 2. Right Analog Stick -> Camera Look OR Virtual Mouse Cursor Movement
        val rx = getCenteredAxis(event, MotionEvent.AXIS_Z)
        val ry = getCenteredAxis(event, MotionEvent.AXIS_RZ)
        if (abs(rx) > 0.08f || abs(ry) > 0.08f) {
            val dx = rx * 18f
            val dy = ry * 14f
            if (isVirtualCursorActive()) {
                onMoveVirtualCursorDelta(dx, dy)
            } else {
                NativeEngineBridge.sendCameraDelta(dx, dy)
            }
        }

        // 3. L2 / R2 Analog Triggers -> Place (Right Click) & Attack (Left Click)
        val lTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE)
        )
        val rTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS)
        )

        val nextLT = lTrigger > 0.45f
        if (nextLT != leftTriggerPressed) {
            leftTriggerPressed = nextLT
            NativeEngineBridge.sendMouseButton(
                NativeEngineBridge.MOUSE_RIGHT,
                if (nextLT) NativeEngineBridge.GLFW_PRESS else NativeEngineBridge.GLFW_RELEASE
            )
        }

        val nextRT = rTrigger > 0.45f
        if (nextRT != rightTriggerPressed) {
            rightTriggerPressed = nextRT
            NativeEngineBridge.sendMouseButton(
                NativeEngineBridge.MOUSE_LEFT,
                if (nextRT) NativeEngineBridge.GLFW_PRESS else NativeEngineBridge.GLFW_RELEASE
            )
        }

        return true
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> NativeEngineBridge.GLFW_PRESS
            KeyEvent.ACTION_UP -> NativeEngineBridge.GLFW_RELEASE
            else -> return false
        }

        if (event.repeatCount > 0 && action == NativeEngineBridge.GLFW_PRESS) {
            return true
        }

        when (event.keyCode) {
            // Gamepad Face Buttons
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (isVirtualCursorActive()) {
                    NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, action)
                } else {
                    NativeEngineBridge.sendKey(NativeEngineBridge.KEY_SPACE, action)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (isVirtualCursorActive()) {
                    if (action == NativeEngineBridge.GLFW_PRESS) {
                        NativeEngineBridge.sendKey(NativeEngineBridge.KEY_ESCAPE, NativeEngineBridge.GLFW_PRESS)
                        NativeEngineBridge.sendKey(NativeEngineBridge.KEY_ESCAPE, NativeEngineBridge.GLFW_RELEASE)
                    }
                } else if (action == NativeEngineBridge.GLFW_PRESS) {
                    sneakToggled = !sneakToggled
                    NativeEngineBridge.sendKey(
                        NativeEngineBridge.KEY_LEFT_SHIFT,
                        if (sneakToggled) NativeEngineBridge.GLFW_PRESS else NativeEngineBridge.GLFW_RELEASE
                    )
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                NativeEngineBridge.sendKey(NativeEngineBridge.KEY_F, action)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                NativeEngineBridge.sendKey(NativeEngineBridge.KEY_E, action)
                return true
            }

            // Bumpers L1 / R1 -> Cycle Hotbar Slots 1..9
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (action == NativeEngineBridge.GLFW_PRESS) {
                    currentHotbarSlot = (currentHotbarSlot + 8) % 9
                    NativeEngineBridge.tapHotbarSlot(currentHotbarSlot)
                    onHotbarSlotChanged(currentHotbarSlot)
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (action == NativeEngineBridge.GLFW_PRESS) {
                    currentHotbarSlot = (currentHotbarSlot + 1) % 9
                    NativeEngineBridge.tapHotbarSlot(currentHotbarSlot)
                    onHotbarSlotChanged(currentHotbarSlot)
                }
                return true
            }

            // Digital Triggers L2 / R2
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_RIGHT, action)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, action)
                return true
            }

            // Start / Select / Thumbs
            KeyEvent.KEYCODE_BUTTON_START -> {
                NativeEngineBridge.sendKey(NativeEngineBridge.KEY_ESCAPE, action)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (action == NativeEngineBridge.GLFW_PRESS) {
                    onToggleVirtualCursor()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                NativeEngineBridge.sendKey(NativeEngineBridge.KEY_LEFT_CONTROL, action)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                NativeEngineBridge.sendKey(NativeEngineBridge.KEY_F5, action)
                return true
            }

            // D-Pad
            KeyEvent.KEYCODE_DPAD_UP -> {
                NativeEngineBridge.sendKey(NativeEngineBridge.KEY_F5, action)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                NativeEngineBridge.sendKey(NativeEngineBridge.KEY_Q, action)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                NativeEngineBridge.sendKey(NativeEngineBridge.KEY_F3, action)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                NativeEngineBridge.sendKey(NativeEngineBridge.KEY_T, action)
                return true
            }
        }

        // Physical Keyboard Passthrough (A-Z, 0-9, F1-F12, Space, Shift, Ctrl, Esc, Tab)
        val mappedGlfw = mapAndroidKeyCodeToGlfw(event.keyCode)
        if (mappedGlfw != null) {
            NativeEngineBridge.sendKey(mappedGlfw, action)
            return true
        }

        return false
    }

    private fun mapAndroidKeyCodeToGlfw(androidKeyCode: Int): Int? {
        return when (androidKeyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                65 + (androidKeyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                48 + (androidKeyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                290 + (androidKeyCode - KeyEvent.KEYCODE_F1)
            KeyEvent.KEYCODE_SPACE -> NativeEngineBridge.KEY_SPACE
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> NativeEngineBridge.KEY_LEFT_SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> NativeEngineBridge.KEY_LEFT_CONTROL
            KeyEvent.KEYCODE_TAB -> NativeEngineBridge.KEY_TAB
            KeyEvent.KEYCODE_ESCAPE -> NativeEngineBridge.KEY_ESCAPE
            KeyEvent.KEYCODE_ENTER -> 257
            KeyEvent.KEYCODE_DEL -> 259
            else -> null
        }
    }

    private fun updateStickKey(glfwKey: Int, current: Boolean, next: Boolean, updateState: (Boolean) -> Unit) {
        if (current != next) {
            updateState(next)
            NativeEngineBridge.sendKey(
                glfwKey,
                if (next) NativeEngineBridge.GLFW_PRESS else NativeEngineBridge.GLFW_RELEASE
            )
        }
    }

    private fun getCenteredAxis(event: MotionEvent, axis: Int): Float {
        val range = event.device?.getMotionRange(axis, event.source)
        val value = event.getAxisValue(axis)
        val flat = range?.flat ?: 0.1f
        return if (abs(value) > flat) value else 0f
    }
}
