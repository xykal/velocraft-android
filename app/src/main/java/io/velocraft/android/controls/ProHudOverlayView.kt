package io.velocraft.android.controls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.bridge.FabricStateBridge
import io.velocraft.android.bridge.NativeEngineBridge
import kotlin.math.hypot

/**
 * VeloCraft Pro PvP HUD Overlay View.
 *
 * Features:
 * - True 10-finger independent multi-touch routing (Claw & Thumb PvP friendly).
 * - Dedicated right-half free-look camera touch zone with adjustable sensitivity & acceleration.
 * - Direct 9-Slot Hotbar at the bottom-center for instant 1-tap weapon/block switching.
 * - Real-time Left/Right CPS (Clicks Per Second) counter + FPS/Ping telemetry pill.
 * - Smart GUI Mode: Automatically switches from Crosshair Mode to Direct Touch Cursor when
 *   `FabricStateBridge` detects an open Inventory, Chest, or Pause Menu inside Minecraft.
 * - In-Game Layout Editor: Drag buttons anywhere on screen and save instantly.
 */
class ProHudOverlayView(
    context: Context,
    private val gyroController: GyroAimController
) : View(context) {

    private val buttons: MutableList<HudButtonConfig> = ControlProfileManager.loadLayout(context)
    private val activeToggles = mutableSetOf<String>()
    private val pointerToButtonMap = mutableMapOf<Int, HudButtonConfig>()

    // Camera look pointer tracking
    private var lookPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var lastLookX: Float = 0f
    private var lastLookY: Float = 0f
    var touchLookSensitivity: Float = 0.65f

    // HUD & Telemetry State
    var currentScreenMode: FabricStateBridge.ScreenMode = FabricStateBridge.ScreenMode.IN_GAME_CROSSHAIR
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }
    var clientFps: Int = 120
    var clientPingMs: Int = 0
    var activeHotbarSlot: Int = 0
    var isEditMode: Boolean = false
    var holdAutoClickEnabled: Boolean = false
    var useProCircleCrosshair: Boolean = false
    val macroEngine = MacroEngine()
    private var isHoldingAttackButton: Boolean = false
    private var editingButton: HudButtonConfig? = null

    private val autoClickRunnable = object : Runnable {
        override fun run() {
            if (isHoldingAttackButton && holdAutoClickEnabled && !isEditMode) {
                leftClickTimestamps.addLast(System.currentTimeMillis())
                NativeEngineBridge.nativeSendMouseButton(NativeEngineBridge.MOUSE_LEFT, NativeEngineBridge.GLFW_PRESS)
                NativeEngineBridge.nativeSendMouseButton(NativeEngineBridge.MOUSE_LEFT, NativeEngineBridge.GLFW_RELEASE)
                postInvalidateOnAnimation()
                postDelayed(this, 70L) // ~14.2 CPS PvP rhythm
            }
        }
    }

    fun resetToDefaultLayout() {
        buttons.clear()
        buttons.addAll(ControlProfileManager.createDefaultProPvpLayout())
        ControlProfileManager.saveLayout(context, buttons)
        postInvalidateOnAnimation()
    }

    // CPS Counter Ring Buffers (timestamps in ms of clicks in the last 1000ms)
    private val leftClickTimestamps = ArrayDeque<Long>()
    private val rightClickTimestamps = ArrayDeque<Long>()

    // Paints
    private val buttonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val buttonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dpToPx(11f)
        isFakeBoldText = true
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC0F172A.toInt()
        style = Paint.Style.FILL
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        strokeWidth = dpToPx(1.8f)
    }
    private val hotbarRect = RectF()
    private val pillRect = RectF()

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    fun updateTelemetry(state: FabricStateBridge.TelemetryState) {
        currentScreenMode = state.screenMode
        clientFps = state.clientFps
        clientPingMs = state.pingMs
        activeHotbarSlot = state.activeHotbarSlot
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Top-Center Pro Telemetry Pill (FPS | CPS | Mode | Gyro)
        drawTelemetryPill(canvas, w)

        // 2. If in GUI_CURSOR mode (Inventory / Chest open), draw subtle cursor indicator & Esc button
        if (currentScreenMode == FabricStateBridge.ScreenMode.GUI_CURSOR && !isEditMode) {
            drawGuiModeOverlay(canvas, w, h)
            return
        }

        // 3. Center Precision Crosshair
        val cx = w * 0.5f
        val cy = h * 0.5f
        val arm = dpToPx(7f)
        if (useProCircleCrosshair) {
            crosshairPaint.style = Paint.Style.STROKE
            canvas.drawCircle(cx, cy, dpToPx(9f), crosshairPaint)
            crosshairPaint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, dpToPx(2.2f), crosshairPaint)
        } else {
            canvas.drawLine(cx - arm, cy, cx + arm, cy, crosshairPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, crosshairPaint)
        }

        // 4. Direct 9-Slot Hotbar Touch Bar (Bottom Center)
        drawDirectHotbar(canvas, w, h)

        // 5. All Pro PvP HUD Buttons
        for (btn in buttons) {
            val bx = btn.normX * w
            val by = btn.normY * h
            val rad = dpToPx(btn.radiusDp)
            val isPressedOrActive = activeToggles.contains(btn.id) || pointerToButtonMap.containsValue(btn)

            buttonFillPaint.color = if (isPressedOrActive) btn.accentHex else 0xFF1E293B.toInt()
            buttonFillPaint.alpha = ((if (isPressedOrActive) 0.92f else btn.opacity) * 255).toInt()
            canvas.drawCircle(bx, by, rad, buttonFillPaint)

            buttonStrokePaint.color = if (isEditMode) 0xFFF97316.toInt() else btn.accentHex
            buttonStrokePaint.alpha = if (isPressedOrActive) 255 else 180
            canvas.drawCircle(bx, by, rad, buttonStrokePaint)

            canvas.drawText(btn.label, bx, by + (textPaint.textSize * 0.35f), textPaint)
        }
    }

    private fun drawTelemetryPill(canvas: Canvas, screenW: Float) {
        val now = System.currentTimeMillis()
        while (leftClickTimestamps.isNotEmpty() && now - leftClickTimestamps.first() > 1000L) {
            leftClickTimestamps.removeFirst()
        }
        while (rightClickTimestamps.isNotEmpty() && now - rightClickTimestamps.first() > 1000L) {
            rightClickTimestamps.removeFirst()
        }

        val leftCps = leftClickTimestamps.size
        val rightCps = rightClickTimestamps.size
        val modeLabel = if (isEditMode) "HUD EDITOR (DRAG TOMBOL)" else if (currentScreenMode == FabricStateBridge.ScreenMode.GUI_CURSOR) "GUI TOUCH MODE" else "CROSSHAIR LOCK"
        val gyroLabel = if (gyroController.isEnabled) "GYRO: ON" else "GYRO: OFF"
        val banner = "FPS: $clientFps  |  CPS: $leftCps / $rightCps  |  $gyroLabel  |  $modeLabel"

        val pillW = dpToPx(340f)
        val pillH = dpToPx(26f)
        val left = (screenW - pillW) * 0.5f
        val top = dpToPx(8f)
        pillRect.set(left, top, left + pillW, top + pillH)

        canvas.drawRoundRect(pillRect, dpToPx(13f), dpToPx(13f), pillPaint)
        canvas.drawText(banner, screenW * 0.5f, top + dpToPx(17f), textPaint)
    }

    private fun drawDirectHotbar(canvas: Canvas, screenW: Float, screenH: Float) {
        val slotSize = dpToPx(38f)
        val totalW = slotSize * 9f
        val startX = (screenW - totalW) * 0.5f
        val topY = screenH - slotSize - dpToPx(8f)

        for (slot in 0 until 9) {
            val sx = startX + slot * slotSize
            hotbarRect.set(sx + 2f, topY, sx + slotSize - 2f, topY + slotSize)
            val isSelected = (slot == activeHotbarSlot)

            buttonFillPaint.color = if (isSelected) 0xCC38BDF8.toInt() else 0x990F172A.toInt()
            canvas.drawRoundRect(hotbarRect, dpToPx(6f), dpToPx(6f), buttonFillPaint)

            buttonStrokePaint.color = if (isSelected) Color.WHITE else 0x8064748B.toInt()
            canvas.drawRoundRect(hotbarRect, dpToPx(6f), dpToPx(6f), buttonStrokePaint)

            canvas.drawText("${slot + 1}", hotbarRect.centerX(), hotbarRect.centerY() + dpToPx(4f), textPaint)
        }
    }

    private fun drawGuiModeOverlay(canvas: Canvas, screenW: Float, screenH: Float) {
        // Draw only a clean "CLOSE GUI (ESC)" button in the top-right when Inventory/Chest is open
        val bx = screenW * 0.90f
        val by = screenH * 0.12f
        val rad = dpToPx(26f)
        buttonFillPaint.color = 0xCCEF4444.toInt()
        canvas.drawCircle(bx, by, rad, buttonFillPaint)
        canvas.drawText("ESC", bx, by + dpToPx(4f), textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val x = event.getX(actionIndex)
        val y = event.getY(actionIndex)

        // 1. Handle GUI_CURSOR Mode (Direct Touch inside Inventory / Chest / Title Screen)
        if (currentScreenMode == FabricStateBridge.ScreenMode.GUI_CURSOR && !isEditMode) {
            return handleGuiCursorTouch(event, actionMasked, x, y)
        }

        // 2. Handle In-Game Layout Editor Mode (Drag & Drop buttons)
        if (isEditMode) {
            return handleHudEditorTouch(event, actionMasked, x, y)
        }

        // 3. Standard In-Game Multi-Touch PvP Routing
        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Check if user tapped the 9-slot direct hotbar first
                if (tryHandleHotbarTap(x, y)) {
                    postInvalidateOnAnimation()
                    return true
                }

                // Check if pointer hit a HUD button
                val hitBtn = findHitButton(x, y)
                if (hitBtn != null) {
                    pointerToButtonMap[pointerId] = hitBtn
                    onButtonPress(hitBtn)
                } else if (lookPointerId == MotionEvent.INVALID_POINTER_ID) {
                    // Assign this finger to smooth camera look
                    lookPointerId = pointerId
                    lastLookX = x
                    lastLookY = y
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    if (pid == lookPointerId) {
                        val curX = event.getX(i)
                        val curY = event.getY(i)
                        val dx = (curX - lastLookX) * touchLookSensitivity
                        val dy = (curY - lastLookY) * touchLookSensitivity
                        lastLookX = curX
                        lastLookY = curY
                        NativeEngineBridge.nativeSendCameraDelta(dx, dy)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val releasedBtn = pointerToButtonMap.remove(pointerId)
                if (releasedBtn != null) {
                    onButtonRelease(releasedBtn)
                }
                if (pointerId == lookPointerId) {
                    lookPointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
        }

        postInvalidateOnAnimation()
        return true
    }

    private fun handleGuiCursorTouch(
        event: MotionEvent,
        actionMasked: Int,
        x: Float,
        y: Float
    ): Boolean {
        // Check ESC button at (0.90w, 0.12h)
        if (actionMasked == MotionEvent.ACTION_DOWN &&
            hypot(x - width * 0.90f, y - height * 0.12f) <= dpToPx(30f)
        ) {
            NativeEngineBridge.nativeSendKey(NativeEngineBridge.KEY_ESCAPE, NativeEngineBridge.GLFW_PRESS)
            NativeEngineBridge.nativeSendKey(NativeEngineBridge.KEY_ESCAPE, NativeEngineBridge.GLFW_RELEASE)
            return true
        }

        NativeEngineBridge.nativeSendCursorPos(x, y)
        when (actionMasked) {
            MotionEvent.ACTION_DOWN ->
                NativeEngineBridge.nativeSendMouseButton(NativeEngineBridge.MOUSE_LEFT, NativeEngineBridge.GLFW_PRESS)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                NativeEngineBridge.nativeSendMouseButton(NativeEngineBridge.MOUSE_LEFT, NativeEngineBridge.GLFW_RELEASE)
        }
        return true
    }

    private fun handleHudEditorTouch(
        event: MotionEvent,
        actionMasked: Int,
        x: Float,
        y: Float
    ): Boolean {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findHitButton(x, y)
                if (hit?.actionType == ActionType.HUD_EDITOR_TOGGLE) {
                    isEditMode = false
                    ControlProfileManager.saveLayout(context, buttons)
                } else {
                    editingButton = hit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                editingButton?.let { btn ->
                    btn.normX = (x / width.toFloat()).coerceIn(0.04f, 0.96f)
                    btn.normY = (y / height.toFloat()).coerceIn(0.06f, 0.94f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                editingButton = null
                ControlProfileManager.saveLayout(context, buttons)
            }
        }
        postInvalidateOnAnimation()
        return true
    }

    private fun tryHandleHotbarTap(x: Float, y: Float): Boolean {
        val slotSize = dpToPx(38f)
        val totalW = slotSize * 9f
        val startX = (width - totalW) * 0.5f
        val topY = height - slotSize - dpToPx(8f)

        if (y in topY..(topY + slotSize) && x in startX..(startX + totalW)) {
            val slot = ((x - startX) / slotSize).toInt().coerceIn(0, 8)
            activeHotbarSlot = slot
            NativeEngineBridge.tapHotbarSlot(slot)
            return true
        }
        return false
    }

    private fun findHitButton(x: Float, y: Float): HudButtonConfig? {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in buttons.indices.reversed()) {
            val b = buttons[i]
            val bx = b.normX * w
            val by = b.normY * h
            if (hypot(x - bx, y - by) <= dpToPx(b.radiusDp)) {
                return b
            }
        }
        return null
    }

    private fun onButtonPress(btn: HudButtonConfig) {
        when (btn.actionType) {
            ActionType.KEY_HOLD -> {
                NativeEngineBridge.nativeSendKey(btn.keyCodeOrButton, NativeEngineBridge.GLFW_PRESS)
            }
            ActionType.KEY_TOGGLE -> {
                if (activeToggles.contains(btn.id)) {
                    activeToggles.remove(btn.id)
                    NativeEngineBridge.nativeSendKey(btn.keyCodeOrButton, NativeEngineBridge.GLFW_RELEASE)
                } else {
                    activeToggles.add(btn.id)
                    NativeEngineBridge.nativeSendKey(btn.keyCodeOrButton, NativeEngineBridge.GLFW_PRESS)
                }
            }
            ActionType.MOUSE_LEFT_ATTACK -> {
                isHoldingAttackButton = true
                leftClickTimestamps.addLast(System.currentTimeMillis())
                NativeEngineBridge.nativeSendMouseButton(NativeEngineBridge.MOUSE_LEFT, NativeEngineBridge.GLFW_PRESS)
                if (holdAutoClickEnabled) {
                    removeCallbacks(autoClickRunnable)
                    postDelayed(autoClickRunnable, 70L)
                }
            }
            ActionType.MOUSE_RIGHT_PLACE -> {
                rightClickTimestamps.addLast(System.currentTimeMillis())
                NativeEngineBridge.nativeSendMouseButton(NativeEngineBridge.MOUSE_RIGHT, NativeEngineBridge.GLFW_PRESS)
                macroEngine.onRightClickDown()
            }
            ActionType.HOTBAR_SLOT -> {
                NativeEngineBridge.tapHotbarSlot(btn.keyCodeOrButton)
            }
            ActionType.GYRO_TOGGLE -> {
                gyroController.isEnabled = !gyroController.isEnabled
                if (gyroController.isEnabled) activeToggles.add(btn.id) else activeToggles.remove(btn.id)
            }
            ActionType.HUD_EDITOR_TOGGLE -> {
                isEditMode = !isEditMode
            }
            ActionType.QUICK_SETTINGS -> {
                QuickSettingsSheet.show(context, this, gyroController) {
                    resetToDefaultLayout()
                }
            }
            ActionType.PVP_COMBO_MACRO -> {
                rightClickTimestamps.addLast(System.currentTimeMillis())
                macroEngine.triggerQuickUtilityMacro { updatedSlot ->
                    activeHotbarSlot = updatedSlot
                    postInvalidateOnAnimation()
                }
            }
        }
    }

    private fun onButtonRelease(btn: HudButtonConfig) {
        when (btn.actionType) {
            ActionType.KEY_HOLD -> {
                NativeEngineBridge.nativeSendKey(btn.keyCodeOrButton, NativeEngineBridge.GLFW_RELEASE)
            }
            ActionType.MOUSE_LEFT_ATTACK -> {
                isHoldingAttackButton = false
                removeCallbacks(autoClickRunnable)
                NativeEngineBridge.nativeSendMouseButton(NativeEngineBridge.MOUSE_LEFT, NativeEngineBridge.GLFW_RELEASE)
            }
            ActionType.MOUSE_RIGHT_PLACE -> {
                macroEngine.onRightClickUp()
                NativeEngineBridge.nativeSendMouseButton(NativeEngineBridge.MOUSE_RIGHT, NativeEngineBridge.GLFW_RELEASE)
            }
            else -> Unit
        }
    }
}
