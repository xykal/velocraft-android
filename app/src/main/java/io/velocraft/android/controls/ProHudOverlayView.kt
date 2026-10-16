package io.velocraft.android.controls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.bridge.NativeEngineBridge
import kotlin.math.hypot

/**
 * Multi-Touch Pro PvP HUD Overlay & Virtual Mouse Cursor View.
 *
 * Designed with a clean, matte, non-neon aesthetic and strictly zero emojis:
 * - Independent multi-pointer state machine (Move + Camera/Virtual Cursor + Attack + Jump simultaneously).
 * - Toggleable On-Screen Virtual Mouse Cursor ("CURSOR") for GUI navigation and inventory management.
 * - Full HUD Editor toolbar (+ ADD KEY, BIND KEY, SIZE -/+, DELETE, SAVE HUD).
 */
class ProHudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val buttons: MutableList<HudButtonConfig> = ControlProfileManager.loadLayout(context)

    // Pointer tracking
    private val pointerToButtonMap = mutableMapOf<Int, HudButtonConfig>()
    private val activeButtons = mutableSetOf<String>()
    private val toggledButtons = mutableSetOf<String>()

    // Camera / Virtual Cursor pointer tracking
    private var lookPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var lastLookX: Float = 0f
    private var lastLookY: Float = 0f
    private var downLookX: Float = 0f
    private var downLookY: Float = 0f
    private var downLookTimestamp: Long = 0L
    var touchLookSensitivity: Float = 0.65f

    // Virtual Mouse Cursor State ("bisa show cursor")
    var isVirtualCursorEnabled: Boolean = false
        private set
    var virtualCursorX: Float = -1f
        private set
    var virtualCursorY: Float = -1f
        private set
    private val cursorPath = Path()

    // HUD Editor state
    var isEditMode: Boolean = false
        private set
    private var draggingButton: HudButtonConfig? = null
    private var selectedEditButton: HudButtonConfig? = null

    // Editor Top Toolbar Hit Rects
    private val editAddKeyRect = RectF()
    private val editBindKeyRect = RectF()
    private val editSizeMinusRect = RectF()
    private val editSizePlusRect = RectF()
    private val editDeleteKeyRect = RectF()
    private val editSaveHudRect = RectF()

    // PvP Combat Automation & CPS Counter
    val macroEngine = MacroEngine()
    var holdAutoClickEnabled: Boolean = true
    var useProCircleCrosshair: Boolean = true
    private var isHoldingLeftAttack = false
    private var isHoldingRightPlace = false

    // Callbacks
    var onGyroToggleRequested: ((Boolean) -> Unit)? = null
    var onQuickSettingsRequested: (() -> Unit)? = null
    var onOpenKeybindEditorRequested: ((HudButtonConfig?) -> Unit)? = null

    // Telemetry
    var currentFps: Int = 120
    var activeRendererLabel: String = "AUTO"
    private val hotbarRect = RectF()
    private var activeHotbarSlot: Int = 0

    // Matte Non-Neon Paint Palette
    private val bgFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(11.5f)
        isFakeBoldText = true
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44A09B90
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }
    private val hudBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD91E1D1B.toInt()
        style = Paint.Style.FILL
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9F4F1EA.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        style = Paint.Style.FILL
    }
    private val cursorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141413.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    fun getButtonsList(): MutableList<HudButtonConfig> = buttons

    fun saveCurrentLayout() {
        ControlProfileManager.saveLayout(context, buttons)
        postInvalidateOnAnimation()
    }

    fun resetToDefaultLayout() {
        buttons.clear()
        buttons.addAll(ControlProfileManager.createDefaultProPvpLayout())
        ControlProfileManager.saveLayout(context, buttons)
        postInvalidateOnAnimation()
    }

    fun toggleVirtualCursor(): Boolean {
        isVirtualCursorEnabled = !isVirtualCursorEnabled
        if (isVirtualCursorEnabled) {
            toggledButtons.add("btn_cursor")
            if (virtualCursorX < 0f || virtualCursorY < 0f) {
                virtualCursorX = (width.takeIf { it > 0 } ?: 1280) * 0.5f
                virtualCursorY = (height.takeIf { it > 0 } ?: 720) * 0.5f
            }
        } else {
            toggledButtons.remove("btn_cursor")
        }
        postInvalidateOnAnimation()
        return isVirtualCursorEnabled
    }

    fun moveVirtualCursorByDelta(dx: Float, dy: Float) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        if (virtualCursorX < 0f) virtualCursorX = w * 0.5f
        if (virtualCursorY < 0f) virtualCursorY = h * 0.5f
        virtualCursorX = (virtualCursorX + dx).coerceIn(0f, w)
        virtualCursorY = (virtualCursorY + dy).coerceIn(0f, h)
        NativeEngineBridge.sendMouseDelta(dx, dy)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (virtualCursorX < 0f || virtualCursorY < 0f) {
            virtualCursorX = w * 0.5f
            virtualCursorY = h * 0.5f
        }

        if (isEditMode) {
            drawEditorGrid(canvas, w, h)
        } else {
            drawCenterCrosshair(canvas, w / 2f, h / 2f)
        }

        drawHotbarBar(canvas, w, h)
        drawTopTelemetryPill(canvas, w)

        for (btn in buttons) {
            val cx = btn.normX * w
            val cy = btn.normY * h
            val r = dp(btn.radiusDp)
            val isPressed = activeButtons.contains(btn.id) || toggledButtons.contains(btn.id)
            val isSelectedInEditor = isEditMode && selectedEditButton?.id == btn.id

            bgFillPaint.color = if (isPressed) {
                 modulateAlpha(btn.accentHex, 0.72f)
            } else {
                modulateAlpha(0xFF1E1D1B.toInt(), btn.opacity * 0.78f)
            }
            canvas.drawCircle(cx, cy, r, bgFillPaint)

            strokePaint.color = when {
                isSelectedInEditor -> 0xFFF4F1EA.toInt()
                isPressed -> 0xFFF4F1EA.toInt()
                else -> modulateAlpha(btn.accentHex, 0.85f)
            }
            strokePaint.strokeWidth = if (isSelectedInEditor || isPressed) dp(2.4f) else dp(1.4f)
            canvas.drawCircle(cx, cy, r, strokePaint)

            textPaint.textSize = if (btn.label.length > 6) dp(9.5f) else dp(11.5f)
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(btn.label, cx, textY, textPaint)
        }

        if (isEditMode) {
            drawEditorTopToolbar(canvas, w)
        }

        if (isVirtualCursorEnabled && !isEditMode) {
            drawVirtualMouseCursor(canvas, virtualCursorX, virtualCursorY)
        }
    }

    private fun drawCenterCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        if (useProCircleCrosshair) {
            val radius = dp(10f)
            crosshairPaint.color = if (isHoldingLeftAttack) 0xFFD96B52.toInt() else 0xB8F4F1EA.toInt()
            canvas.drawCircle(cx, cy, radius, crosshairPaint)
            bgFillPaint.color = 0xFFF4F1EA.toInt()
            canvas.drawCircle(cx, cy, dp(1.8f), bgFillPaint)
        } else {
            val arm = dp(7f)
            crosshairPaint.color = 0xD9F4F1EA.toInt()
            canvas.drawLine(cx - arm, cy, cx + arm, cy, crosshairPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, crosshairPaint)
        }
    }

    private fun drawVirtualMouseCursor(canvas: Canvas, x: Float, y: Float) {
        val s = dp(16f)
        cursorPath.reset()
        cursorPath.moveTo(x, y)
        cursorPath.lineTo(x, y + s)
        cursorPath.lineTo(x + s * 0.32f, y + s * 0.74f)
        cursorPath.lineTo(x + s * 0.56f, y + s * 1.12f)
        cursorPath.lineTo(x + s * 0.74f, y + s * 1.02f)
        cursorPath.lineTo(x + s * 0.48f, y + s * 0.64f)
        cursorPath.lineTo(x + s * 0.82f, y + s * 0.64f)
        cursorPath.close()

        canvas.drawPath(cursorPath, cursorFillPaint)
        canvas.drawPath(cursorPath, cursorStrokePaint)
    }

    private fun drawEditorGrid(canvas: Canvas, w: Float, h: Float) {
        val step = dp(32f)
        var x = step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }
    }

    private fun drawEditorTopToolbar(canvas: Canvas, w: Float) {
        val barW = dp(520f).coerceAtMost(w * 0.94f)
        val barH = dp(38f)
        val left = (w - barW) * 0.5f
        val top = dp(44f)
        val barRect = RectF(left, top, left + barW, top + barH)
        canvas.drawRoundRect(barRect, dp(8f), dp(8f), hudBannerPaint)
        strokePaint.color = 0xFFC87D55.toInt()
        canvas.drawRoundRect(barRect, dp(8f), dp(8f), strokePaint)

        val gap = dp(5f)
        val itemW = (barW - gap * 7f) / 6f
        val itemTop = top + dp(4f)
        val itemBottom = top + barH - dp(4f)

        val rects = listOf(
            editAddKeyRect to "+ ADD KEY",
            editBindKeyRect to "BIND KEY",
            editSizeMinusRect to "SIZE -",
            editSizePlusRect to "SIZE +",
            editDeleteKeyRect to "DELETE",
            editSaveHudRect to "SAVE HUD"
        )
        for ((idx, pair) in rects.withIndex()) {
            val rLeft = left + gap + idx * (itemW + gap)
            pair.first.set(rLeft, itemTop, rLeft + itemW, itemBottom)
            bgFillPaint.color = when (idx) {
                0, 5 -> 0xFF4E8C57.toInt()
                4 -> 0xFFB85448.toInt()
                else -> 0xFF2D2B28.toInt()
            }
            canvas.drawRoundRect(pair.first, dp(6f), dp(6f), bgFillPaint)
            textPaint.textSize = dp(9.8f)
            canvas.drawText(pair.second, pair.first.centerX(), pair.first.centerY() + dp(3.5f), textPaint)
        }
    }

    private fun drawHotbarBar(canvas: Canvas, w: Float, h: Float) {
        val barWidth = w * 0.42f
        val barHeight = dp(38f)
        val left = (w - barWidth) / 2f
        val top = h - barHeight - dp(10f)
        hotbarRect.set(left, top, left + barWidth, top + barHeight)

        canvas.drawRoundRect(hotbarRect, dp(8f), dp(8f), hudBannerPaint)
        val slotWidth = barWidth / 9f
        for (i in 0 until 9) {
            val sLeft = left + i * slotWidth
            val slotRect = RectF(sLeft + dp(2f), top + dp(2f), sLeft + slotWidth - dp(2f), top + barHeight - dp(2f))
            strokePaint.color = if (i == activeHotbarSlot) 0xFF4E8C57.toInt() else 0x5578736A
            strokePaint.strokeWidth = if (i == activeHotbarSlot) dp(2f) else dp(1f)
            canvas.drawRoundRect(slotRect, dp(5f), dp(5f), strokePaint)

            textPaint.textSize = dp(10f)
            canvas.drawText("${i + 1}", slotRect.centerX(), slotRect.centerY() + dp(4f), textPaint)
        }
    }

    private fun drawTopTelemetryPill(canvas: Canvas, w: Float) {
        val pillW = dp(380f).coerceAtMost(w * 0.72f)
        val pillH = dp(26f)
        val left = (w - pillW) / 2f
        val top = dp(10f)
        val rect = RectF(left, top, left + pillW, top + pillH)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), hudBannerPaint)
        strokePaint.color = 0x6678736A
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), strokePaint)

        textPaint.textSize = dp(10f)
        val gyroState = if (toggledButtons.contains("btn_gyro")) "GYRO:ON" else "GYRO:OFF"
        val curState = if (isVirtualCursorEnabled) "CURSOR:ON" else "CURSOR:OFF"
        val cpsL = macroEngine.getLeftCps()
        val cpsR = macroEngine.getRightCps()
        canvas.drawText(
            "Minecraft 1.20.4 ($activeRendererLabel) | $currentFps FPS | $cpsL/$cpsR CPS | $curState | $gyroState",
            rect.centerX(),
            rect.centerY() + dp(3.8f),
            textPaint
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)

        if (isEditMode) {
            return handleEditorTouch(event, action, x, y)
        }

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (hotbarRect.contains(x, y)) {
                    val slot = ((x - hotbarRect.left) / (hotbarRect.width() / 9f)).toInt().coerceIn(0, 8)
                    selectHotbarSlot(slot)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    postInvalidateOnAnimation()
                    return true
                }

                val hitBtn = findHitButton(x, y)
                if (hitBtn != null) {
                    pointerToButtonMap[pointerId] = hitBtn
                    onHudButtonDown(hitBtn)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                } else if (lookPointerId == MotionEvent.INVALID_POINTER_ID) {
                    lookPointerId = pointerId
                    lastLookX = x
                    lastLookY = y
                    downLookX = x
                    downLookY = y
                    downLookTimestamp = System.currentTimeMillis()
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

                        if (isVirtualCursorEnabled) {
                            moveVirtualCursorByDelta(dx * 1.35f, dy * 1.35f)
                        } else {
                            NativeEngineBridge.sendMouseDelta(dx, dy)
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val mapped = pointerToButtonMap.remove(pointerId)
                if (mapped != null) {
                    onHudButtonUp(mapped)
                }
                if (pointerId == lookPointerId) {
                    // Tap-to-click when Virtual Cursor is enabled
                    val dt = System.currentTimeMillis() - downLookTimestamp
                    val dist = hypot((x - downLookX).toDouble(), (y - downLookY).toDouble())
                    if (isVirtualCursorEnabled && dt < 220L && dist < dp(12f)) {
                        macroEngine.registerManualLeftClick()
                        NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, true)
                        NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, false)
                    }
                    lookPointerId = MotionEvent.INVALID_POINTER_ID
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerToButtonMap.values.forEach { onHudButtonUp(it) }
                pointerToButtonMap.clear()
                lookPointerId = MotionEvent.INVALID_POINTER_ID
            }
        }

        postInvalidateOnAnimation()
        return true
    }

    private fun handleEditorTouch(event: MotionEvent, action: Int, x: Float, y: Float): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // Check Top Editor Toolbar first
                when {
                    editAddKeyRect.contains(x, y) -> {
                        val newBtn = HudButtonConfig(
                            id = "custom_${System.currentTimeMillis()}",
                            label = "KEY G",
                            normX = 0.50f,
                            normY = 0.50f,
                            radiusDp = 24f,
                            opacity = 0.80f,
                            actionType = ActionType.KEY_HOLD,
                            keyCodeOrButton = 71,
                            isToggle = false,
                            accentHex = 0xFF4E8C57.toInt()
                        )
                        buttons.add(newBtn)
                        selectedEditButton = newBtn
                        ControlProfileManager.saveLayout(context, buttons)
                        postInvalidateOnAnimation()
                        return true
                    }
                    editBindKeyRect.contains(x, y) -> {
                        onOpenKeybindEditorRequested?.invoke(selectedEditButton)
                        return true
                    }
                    editSizeMinusRect.contains(x, y) -> {
                        selectedEditButton?.let {
                            it.radiusDp = (it.radiusDp - 3f).coerceAtLeast(14f)
                            ControlProfileManager.saveLayout(context, buttons)
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editSizePlusRect.contains(x, y) -> {
                        selectedEditButton?.let {
                            it.radiusDp = (it.radiusDp + 3f).coerceAtMost(60f)
                            ControlProfileManager.saveLayout(context, buttons)
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editDeleteKeyRect.contains(x, y) -> {
                        selectedEditButton?.let { sel ->
                            if (sel.id != "btn_edit_hud" && sel.id != "btn_settings" && buttons.size > 4) {
                                buttons.remove(sel)
                                selectedEditButton = buttons.firstOrNull()
                                ControlProfileManager.saveLayout(context, buttons)
                            }
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editSaveHudRect.contains(x, y) -> {
                        isEditMode = false
                        toggledButtons.remove("btn_edit_hud")
                        ControlProfileManager.saveLayout(context, buttons)
                        postInvalidateOnAnimation()
                        return true
                    }
                }

                val hit = findHitButton(x, y)
                if (hit?.actionType == ActionType.HUD_EDITOR_TOGGLE) {
                    isEditMode = false
                    toggledButtons.remove(hit.id)
                    ControlProfileManager.saveLayout(context, buttons)
                } else if (hit != null) {
                    draggingButton = hit
                    selectedEditButton = hit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                draggingButton?.let { btn ->
                    btn.normX = (x / width.coerceAtLeast(1)).coerceIn(0.04f, 0.96f)
                    btn.normY = (y / height.coerceAtLeast(1)).coerceIn(0.06f, 0.94f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingButton = null
                ControlProfileManager.saveLayout(context, buttons)
            }
        }
        postInvalidateOnAnimation()
        return true
    }

    private fun onHudButtonDown(btn: HudButtonConfig) {
        if (btn.isToggle) {
            val nowActive = if (toggledButtons.contains(btn.id)) {
                toggledButtons.remove(btn.id)
                false
            } else {
                toggledButtons.add(btn.id)
                true
            }
            when (btn.actionType) {
                ActionType.KEY_TOGGLE -> NativeEngineBridge.sendKeyEvent(btn.keyCodeOrButton, nowActive)
                ActionType.GYRO_TOGGLE -> onGyroToggleRequested?.invoke(nowActive)
                ActionType.CURSOR_TOGGLE -> {
                    isVirtualCursorEnabled = nowActive
                }
                ActionType.HUD_EDITOR_TOGGLE -> {
                    isEditMode = nowActive
                    if (isEditMode && selectedEditButton == null) {
                        selectedEditButton = buttons.firstOrNull()
                    }
                }
                else -> {}
            }
            return
        }

        activeButtons.add(btn.id)
        when (btn.actionType) {
            ActionType.KEY_HOLD -> NativeEngineBridge.sendKeyEvent(btn.keyCodeOrButton, true)
            ActionType.MOUSE_LEFT_ATTACK -> {
                isHoldingLeftAttack = true
                macroEngine.registerManualLeftClick()
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, true)
                if (holdAutoClickEnabled) {
                    macroEngine.setLeftAutoClickActive(true) { postInvalidateOnAnimation() }
                }
            }
            ActionType.MOUSE_RIGHT_PLACE -> {
                isHoldingRightPlace = true
                macroEngine.registerManualRightClick()
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_RIGHT, true)
                macroEngine.setRightFastPlaceActive(true) { postInvalidateOnAnimation() }
            }
            ActionType.QUICK_SETTINGS -> onQuickSettingsRequested?.invoke()
            ActionType.PVP_COMBO_MACRO -> {
                macroEngine.executeSprintResetCombo { postInvalidateOnAnimation() }
            }
            else -> {}
        }
    }

    private fun onHudButtonUp(btn: HudButtonConfig) {
        if (btn.isToggle) return
        activeButtons.remove(btn.id)
        when (btn.actionType) {
            ActionType.KEY_HOLD -> NativeEngineBridge.sendKeyEvent(btn.keyCodeOrButton, false)
            ActionType.MOUSE_LEFT_ATTACK -> {
                isHoldingLeftAttack = false
                macroEngine.setLeftAutoClickActive(false)
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, false)
            }
            ActionType.MOUSE_RIGHT_PLACE -> {
                isHoldingRightPlace = false
                macroEngine.setRightFastPlaceActive(false)
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_RIGHT, false)
            }
            else -> {}
        }
    }

    private fun selectHotbarSlot(slotIndex: Int) {
        activeHotbarSlot = slotIndex
        val keyCode = NativeEngineBridge.KEY_1 + slotIndex
        NativeEngineBridge.sendKeyEvent(keyCode, true)
        NativeEngineBridge.sendKeyEvent(keyCode, false)
    }

    private fun findHitButton(x: Float, y: Float): HudButtonConfig? {
        val w = width.toFloat()
        val h = height.toFloat()
        return buttons.firstOrNull { btn ->
            val cx = btn.normX * w
            val cy = btn.normY * h
            val r = dp(btn.radiusDp) * 1.15f
            hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r
        }
    }

    private fun modulateAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
