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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Multi-Touch Pro PvP HUD Overlay, Direct + Precision Virtual Mouse Cursor & Visual HUD Studio.
 *
 * Designed with a clean, matte, non-neon aesthetic and strictly zero emojis:
 * - Independent multi-pointer state machine (Move + Camera/Virtual Cursor + Attack + Jump simultaneously).
 * - Automatic & Manual Virtual Mouse Cursor with 3 Sleek Studio Styles (Panah Taktis, Retikel Presisi, Intan Lancip),
 *   supporting both Direct Touch-to-Click on menus and Smooth Trackpad Dragging.
 * - Full 10-Tool Visual HUD Studio Dock (+ TAMBAH, DUPLIKAT, BENTUK, SNAP GRID, KEY & LABEL,
 *   UKURAN -/+, OPASITAS -/+, HAPUS, SIMPAN HUD).
 */
class ProHudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val prefs = context.getSharedPreferences("velocraft_hud_studio_prefs", Context.MODE_PRIVATE)
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

    var touchLookSensitivity: Float = prefs.getFloat("touch_look_sens", 0.65f)
        set(value) {
            field = value.coerceIn(0.15f, 2.0f)
            prefs.edit().putFloat("touch_look_sens", field).apply()
        }

    var cursorSpeedMultiplier: Float = prefs.getFloat("cursor_speed_mult", 1.25f)
        set(value) {
            field = value.coerceIn(0.5f, 2.5f)
            prefs.edit().putFloat("cursor_speed_mult", field).apply()
        }

    /**
     * Cursor Visual Style:
     * 0 = PANAH TAKTIS STUDIO (Sculpted Dual-Tone Tactical Arrow)
     * 1 = RETIKEL PRESISI (Precision Crosshair Ring + Micro-Blades)
     * 2 = INTAN LANCIP (Geometric Diamond Spearhead)
     */
    var cursorStyle: Int = prefs.getInt("cursor_style", 0)
        set(value) {
            field = ((value % 3) + 3) % 3
            prefs.edit().putInt("cursor_style", field).apply()
            postInvalidateOnAnimation()
        }

    /**
     * When true (default), tapping directly on any menu button in GUI mode moves the cursor
     * to the finger position and clicks immediately, while dragging moves the cursor smoothly.
     * When false, the screen acts as a relative trackpad only.
     */
    var directTouchMenuClickEnabled: Boolean = prefs.getBoolean("direct_touch_menu", true)
        set(value) {
            field = value
            prefs.edit().putBoolean("direct_touch_menu", value).apply()
            postInvalidateOnAnimation()
        }

    var editorGridSnapEnabled: Boolean = prefs.getBoolean("editor_grid_snap", true)
        set(value) {
            field = value
            prefs.edit().putBoolean("editor_grid_snap", value).apply()
            postInvalidateOnAnimation()
        }

    var isHudCleanModeHidden: Boolean = false
        private set

    // Virtual Mouse Cursor State
    var isVirtualCursorEnabled: Boolean = false
        private set
    var virtualCursorX: Float = -1f
        private set
    var virtualCursorY: Float = -1f
        private set
    private var lastCursorClickMs: Long = 0L
    private var isCursorClickAnimating: Boolean = false
    private val cursorPath = Path()
    private val cursorInnerPath = Path()

    /**
     * The cursor is active whenever the user manually toggled `KURSOR` on OR whenever
     * Minecraft is in a Menu / Inventory / Mod Menu screen (`!NativeEngineBridge.isCursorGrabbed()`).
     */
    val isCursorEffective: Boolean
        get() = isVirtualCursorEnabled || !NativeEngineBridge.isCursorGrabbed()

    // HUD Editor state
    var isEditMode: Boolean = false
        private set
    private var draggingButton: HudButtonConfig? = null
    private var selectedEditButton: HudButtonConfig? = null

    // Editor 2-Row Studio Dock Hit Rects
    private val editAddKeyRect = RectF()
    private val editDuplicateRect = RectF()
    private val editShapeCycleRect = RectF()
    private val editGridSnapRect = RectF()
    private val editBindKeyRect = RectF()
    private val editSizeMinusRect = RectF()
    private val editSizePlusRect = RectF()
    private val editOpacityMinusRect = RectF()
    private val editOpacityPlusRect = RectF()
    private val editDeleteKeyRect = RectF()
    private val editSaveHudRect = RectF()

    // PvP Combat Automation & CPS Counter
    val macroEngine = MacroEngine()
    var holdAutoClickEnabled: Boolean = true
    var useProCircleCrosshair: Boolean = false
    private var isHoldingLeftAttack = false
    private var isHoldingRightPlace = false

    // Callbacks
    var onGyroToggleRequested: ((Boolean) -> Unit)? = null
    var onQuickSettingsRequested: (() -> Unit)? = null
    var onOpenKeybindEditorRequested: ((HudButtonConfig?) -> Unit)? = null
    var onExitHudEditor: (() -> Unit)? = null

    fun enterVisualEditMode() {
        isEditMode = true
        toggledButtons.add("btn_edit_hud")
        if (selectedEditButton == null) {
            selectedEditButton = buttons.firstOrNull()
        }
        postInvalidateOnAnimation()
    }

    // Telemetry
    var currentFps: Int = 120
    var activeRendererLabel: String = "AUTO"
    private val hotbarRect = RectF()
    private val tmpBtnRect = RectF()
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
    private val cursorAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4E8C57.toInt()
        style = Paint.Style.FILL
    }
    private val cursorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF10100E.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2.0f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
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
        selectedEditButton = buttons.firstOrNull()
        ControlProfileManager.saveLayout(context, buttons)
        postInvalidateOnAnimation()
    }

    fun applyLayoutPreset(presetIndex: Int) {
        buttons.clear()
        val preset = when (presetIndex) {
            1 -> ControlProfileManager.createCompetitivePvpLayout()
            2 -> ControlProfileManager.createBuilderMinimalLayout()
            else -> ControlProfileManager.createDefaultProPvpLayout()
        }
        buttons.addAll(preset)
        selectedEditButton = buttons.firstOrNull()
        ControlProfileManager.saveLayout(context, buttons)
        postInvalidateOnAnimation()
    }

    fun getCursorStyleName(): String = when (cursorStyle) {
        1 -> "RETIKEL PRESISI"
        2 -> "INTAN LANCIP"
        else -> "PANAH TAKTIS STUDIO"
    }

    fun cycleCursorStyle(): String {
        cursorStyle = (cursorStyle + 1) % 3
        return getCursorStyleName()
    }

    fun toggleVirtualCursor(): Boolean {
        isVirtualCursorEnabled = !isVirtualCursorEnabled
        if (isVirtualCursorEnabled) {
            toggledButtons.add("btn_cursor")
            if (virtualCursorX < 0f || virtualCursorY < 0f) {
                virtualCursorX = (width.takeIf { it > 0 } ?: 1280) * 0.5f
                virtualCursorY = (height.takeIf { it > 0 } ?: 720) * 0.5f
            }
            NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
        } else {
            toggledButtons.remove("btn_cursor")
        }
        postInvalidateOnAnimation()
        return isVirtualCursorEnabled
    }

    fun setVirtualCursorPosition(x: Float, y: Float) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        virtualCursorX = x.coerceIn(0f, w)
        virtualCursorY = y.coerceIn(0f, h)
        NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
        postInvalidateOnAnimation()
    }

    fun moveVirtualCursorByDelta(dx: Float, dy: Float) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        if (virtualCursorX < 0f) virtualCursorX = w * 0.5f
        if (virtualCursorY < 0f) virtualCursorY = h * 0.5f
        virtualCursorX = (virtualCursorX + dx).coerceIn(0f, w)
        virtualCursorY = (virtualCursorY + dy).coerceIn(0f, h)
        NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
        postInvalidateOnAnimation()
    }

    private fun performCursorClick(button: Int) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        if (virtualCursorX < 0f) virtualCursorX = w * 0.5f
        if (virtualCursorY < 0f) virtualCursorY = h * 0.5f

        if (button == NativeEngineBridge.MOUSE_LEFT) {
            macroEngine.registerManualLeftClick()
        } else if (button == NativeEngineBridge.MOUSE_RIGHT) {
            macroEngine.registerManualRightClick()
        }

        lastCursorClickMs = System.currentTimeMillis()
        isCursorClickAnimating = true
        NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
        NativeEngineBridge.sendMouseButton(button, true)
        postInvalidateOnAnimation()

        postDelayed({
            NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
            NativeEngineBridge.sendMouseButton(button, false)
            isCursorClickAnimating = false
            postInvalidateOnAnimation()
        }, 45L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (virtualCursorX < 0f || virtualCursorY < 0f) {
            virtualCursorX = w * 0.5f
            virtualCursorY = h * 0.5f
            NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
        }

        if (isEditMode) {
            drawEditorGrid(canvas, w, h)
        }

        if (!isHudCleanModeHidden || isEditMode) {
            drawHotbarBar(canvas, w, h)
            drawTopTelemetryPill(canvas, w)
        }

        for (btn in buttons) {
            if (!btn.isVisible && !isEditMode) continue
            if (isHudCleanModeHidden && !isEditMode &&
                btn.id != "btn_settings" && btn.actionType != ActionType.TOGGLE_HUD_CLEAN
            ) {
                continue
            }

            val cx = btn.normX * w
            val cy = btn.normY * h
            val r = dp(btn.radiusDp)
            val isPressed = activeButtons.contains(btn.id) ||
                toggledButtons.contains(btn.id) ||
                (btn.actionType == ActionType.CURSOR_TOGGLE && isCursorEffective)
            val isSelectedInEditor = isEditMode && selectedEditButton?.id == btn.id
            val effectiveOpacity = if (!btn.isVisible && isEditMode) 0.30f else btn.opacity

            val halfW = when (btn.shape) {
                ButtonShape.PILL -> r * 1.38f
                else -> r
            }
            val halfH = when (btn.shape) {
                ButtonShape.PILL -> r * 0.78f
                else -> r
            }
            val cornerRad = when (btn.shape) {
                ButtonShape.CIRCLE -> r
                ButtonShape.ROUND_RECT -> dp(9f)
                ButtonShape.PILL -> halfH
            }

            // 1. Outer shadow/backing frame
            bgFillPaint.color = modulateAlpha(0xFF10100E.toInt(), (effectiveOpacity * 0.48f).coerceAtMost(0.68f))
            tmpBtnRect.set(cx - halfW - dp(1.8f), cy - halfH - dp(1.8f), cx + halfW + dp(1.8f), cy + halfH + dp(1.8f))
            if (btn.shape == ButtonShape.CIRCLE) {
                canvas.drawCircle(cx, cy, r + dp(1.8f), bgFillPaint)
            } else {
                canvas.drawRoundRect(tmpBtnRect, cornerRad + dp(1.5f), cornerRad + dp(1.5f), bgFillPaint)
            }

            // 2. Main matte button surface
            bgFillPaint.color = if (isPressed) {
                modulateAlpha(btn.accentHex, 0.80f)
            } else {
                modulateAlpha(0xFF1B1A18.toInt(), effectiveOpacity * 0.84f)
            }
            tmpBtnRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            if (btn.shape == ButtonShape.CIRCLE) {
                canvas.drawCircle(cx, cy, r, bgFillPaint)
            } else {
                canvas.drawRoundRect(tmpBtnRect, cornerRad, cornerRad, bgFillPaint)
            }

            // 3. Subtle inner bevel ring
            strokePaint.color = if (isPressed) {
                0x55FFFFFF
            } else {
                modulateAlpha(btn.accentHex, 0.32f)
            }
            strokePaint.strokeWidth = dp(1f)
            val inset = dp(3.2f)
            if (btn.shape == ButtonShape.CIRCLE) {
                canvas.drawCircle(cx, cy, (r - inset).coerceAtLeast(dp(4f)), strokePaint)
            } else {
                tmpBtnRect.set(cx - halfW + inset, cy - halfH + inset, cx + halfW - inset, cy + halfH - inset)
                canvas.drawRoundRect(tmpBtnRect, (cornerRad - dp(2f)).coerceAtLeast(dp(3f)), (cornerRad - dp(2f)).coerceAtLeast(dp(3f)), strokePaint)
            }

            // 4. Outer crisp stroke ring
            strokePaint.color = when {
                isSelectedInEditor -> 0xFFF4F1EA.toInt()
                isPressed -> 0xFFF4F1EA.toInt()
                else -> modulateAlpha(btn.accentHex, 0.90f)
            }
            strokePaint.strokeWidth = if (isSelectedInEditor || isPressed) dp(2.2f) else dp(1.4f)
            tmpBtnRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            if (btn.shape == ButtonShape.CIRCLE) {
                canvas.drawCircle(cx, cy, r, strokePaint)
            } else {
                canvas.drawRoundRect(tmpBtnRect, cornerRad, cornerRad, strokePaint)
            }

            // 5. Auto-fitted button label
            textPaint.textSize = when {
                btn.label.length >= 8 -> dp(8.8f)
                btn.label.length >= 6 -> dp(9.8f)
                else -> dp(11.4f)
            }
            val maxLabelW = halfW * 1.74f
            val labelStr = if (!btn.isVisible && isEditMode) "${btn.label} [OFF]" else btn.label
            while (textPaint.measureText(labelStr) > maxLabelW && textPaint.textSize > dp(7.2f)) {
                textPaint.textSize -= dp(0.5f)
            }
            val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(labelStr, cx, textY, textPaint)
        }

        if (isEditMode) {
            drawEditorSelectionGuides(canvas, w, h)
            drawEditorStudioDock(canvas, w, h)
        }

        if (isCursorEffective && !isEditMode) {
            drawVirtualMouseCursor(canvas, virtualCursorX, virtualCursorY)
        }
    }

    /**
     * Renders the 3 Studio Custom Cursor Styles with click-ripple feedback at the exact hotspot `(x, y)`.
     */
    private fun drawVirtualMouseCursor(canvas: Canvas, x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val clickAge = now - lastCursorClickMs
        val isClicking = isHoldingLeftAttack || isHoldingRightPlace || isCursorClickAnimating || clickAge < 220L

        // Animated click ripple ring around the exact hotspot (x, y)
        if (clickAge in 0L..220L) {
            val progress = (clickAge / 220f).coerceIn(0f, 1f)
            val rippleRad = dp(5f + 16f * progress)
            strokePaint.color = modulateAlpha(0xFF4E8C57.toInt(), (1f - progress) * 0.85f)
            strokePaint.strokeWidth = dp(2.0f * (1f - progress * 0.5f))
            canvas.drawCircle(x, y, rippleRad, strokePaint)
            postInvalidateOnAnimation()
        }

        val coreColor = if (isClicking) 0xFFC87D55.toInt() else 0xFF4E8C57.toInt()

        when (cursorStyle) {
            1 -> {
                // STYLE 1: RETIKEL PRESISI (Tactical Crosshair Reticle)
                val ringR = dp(9.5f)
                val gap = dp(3.8f)
                val arm = dp(14.5f)

                // Outer dark halo for contrast
                cursorStrokePaint.strokeWidth = dp(3.0f)
                canvas.drawCircle(x, y, ringR, cursorStrokePaint)
                canvas.drawLine(x - arm, y, x - gap, y, cursorStrokePaint)
                canvas.drawLine(x + gap, y, x + arm, y, cursorStrokePaint)
                canvas.drawLine(x, y - arm, x, y - gap, cursorStrokePaint)
                canvas.drawLine(x, y + gap, x, y + arm, cursorStrokePaint)

                // Crisp warm ivory inner reticle
                strokePaint.color = 0xFFF4F1EA.toInt()
                strokePaint.strokeWidth = dp(1.5f)
                canvas.drawCircle(x, y, ringR, strokePaint)
                canvas.drawLine(x - arm, y, x - gap, y, strokePaint)
                canvas.drawLine(x + gap, y, x + arm, y, strokePaint)
                canvas.drawLine(x, y - arm, x, y - gap, strokePaint)
                canvas.drawLine(x, y + gap, x, y + arm, strokePaint)

                // Center pinpoint hotspot dot
                cursorAccentPaint.color = coreColor
                canvas.drawCircle(x, y, dp(2.4f), cursorAccentPaint)
            }

            2 -> {
                // STYLE 2: INTAN LANCIP (Geometric Diamond Spearhead)
                val s = dp(20f)
                cursorPath.reset()
                cursorPath.moveTo(x, y)
                cursorPath.lineTo(x + s * 0.22f, y + s * 0.92f)
                cursorPath.lineTo(x + s * 0.44f, y + s * 0.64f)
                cursorPath.lineTo(x + s * 0.64f, y + s * 0.44f)
                cursorPath.lineTo(x + s * 0.92f, y + s * 0.22f)
                cursorPath.close()

                canvas.drawPath(cursorPath, cursorFillPaint)

                cursorInnerPath.reset()
                cursorInnerPath.moveTo(x + s * 0.10f, y + s * 0.10f)
                cursorInnerPath.lineTo(x + s * 0.24f, y + s * 0.68f)
                cursorInnerPath.lineTo(x + s * 0.42f, y + s * 0.42f)
                cursorInnerPath.lineTo(x + s * 0.68f, y + s * 0.24f)
                cursorInnerPath.close()
                cursorAccentPaint.color = coreColor
                canvas.drawPath(cursorInnerPath, cursorAccentPaint)

                cursorStrokePaint.strokeWidth = dp(2.0f)
                canvas.drawPath(cursorPath, cursorStrokePaint)
                canvas.drawCircle(x, y, dp(1.8f), cursorAccentPaint)
            }

            else -> {
                // STYLE 0: PANAH TAKTIS STUDIO (Sculpted Dual-Tone Tactical Pointer)
                val s = dp(21f)
                cursorPath.reset()
                cursorPath.moveTo(x, y)
                cursorPath.lineTo(x, y + s * 0.96f)
                cursorPath.lineTo(x + s * 0.28f, y + s * 0.72f)
                cursorPath.lineTo(x + s * 0.48f, y + s * 1.06f)
                cursorPath.lineTo(x + s * 0.66f, y + s * 0.96f)
                cursorPath.lineTo(x + s * 0.45f, y + s * 0.62f)
                cursorPath.lineTo(x + s * 0.78f, y + s * 0.60f)
                cursorPath.close()

                // Main warm ivory blade
                canvas.drawPath(cursorPath, cursorFillPaint)

                // Inner emerald/terracotta tactical core chevron
                cursorInnerPath.reset()
                cursorInnerPath.moveTo(x + s * 0.11f, y + s * 0.22f)
                cursorInnerPath.lineTo(x + s * 0.11f, y + s * 0.68f)
                cursorInnerPath.lineTo(x + s * 0.30f, y + s * 0.54f)
                cursorInnerPath.lineTo(x + s * 0.52f, y + s * 0.52f)
                cursorInnerPath.close()
                cursorAccentPaint.color = coreColor
                canvas.drawPath(cursorInnerPath, cursorAccentPaint)

                // Dark obsidian armor contour
                cursorStrokePaint.strokeWidth = dp(2.1f)
                canvas.drawPath(cursorPath, cursorStrokePaint)

                // Hotspot pinpoint tip dot
                canvas.drawCircle(x, y, dp(2.0f), cursorAccentPaint)
            }
        }
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
        crosshairPaint.color = 0x55C87D55
        canvas.drawLine(w * 0.5f, 0f, w * 0.5f, h, crosshairPaint)
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, crosshairPaint)
    }

    private fun drawEditorSelectionGuides(canvas: Canvas, w: Float, h: Float) {
        val sel = selectedEditButton ?: return
        val cx = sel.normX * w
        val cy = sel.normY * h
        strokePaint.color = 0x884E8C57.toInt()
        strokePaint.strokeWidth = dp(1.1f)
        canvas.drawLine(cx, 0f, cx, h, strokePaint)
        canvas.drawLine(0f, cy, w, cy, strokePaint)
    }

    private fun drawEditorStudioDock(canvas: Canvas, w: Float, h: Float) {
        val barW = dp(590f).coerceAtMost(w * 0.95f)
        val barH = dp(88f)
        val left = (w - barW) * 0.5f
        val top = dp(38f)
        val barRect = RectF(left, top, left + barW, top + barH)
        canvas.drawRoundRect(barRect, dp(10f), dp(10f), hudBannerPaint)
        strokePaint.color = 0xFFC87D55.toInt()
        strokePaint.strokeWidth = dp(1.4f)
        canvas.drawRoundRect(barRect, dp(10f), dp(10f), strokePaint)

        // Selected Button Info Header inside Dock
        val sel = selectedEditButton
        val infoText = if (sel != null) {
            val pctX = (sel.normX * 100).roundToInt()
            val pctY = (sel.normY * 100).roundToInt()
            val opPct = (sel.opacity * 100).roundToInt()
            "Terpilih: [${sel.label}]  |  Pos: $pctX%, $pctY%  |  Ukuran: ${sel.radiusDp.toInt()}dp  |  Opasitas: $opPct%  |  Bentuk: ${sel.shape.displayName}"
        } else {
            "Ketuk tombol mana saja di layar untuk memilih & menggeser posisinya"
        }
        textPaint.textSize = dp(9.2f)
        textPaint.color = 0xFFD6D2C8.toInt()
        canvas.drawText(infoText, barRect.centerX(), top + dp(14f), textPaint)
        textPaint.color = 0xFFF4F1EA.toInt()

        val gap = dp(4.5f)
        val cols = 5
        val itemW = (barW - gap * (cols + 1)) / cols
        val rowH = dp(28f)

        val row1Top = top + dp(21f)
        val row2Top = row1Top + rowH + dp(5f)

        val row1Items = listOf(
            Triple(editAddKeyRect, "+ TAMBAH", 0xFF4E8C57.toInt()),
            Triple(editDuplicateRect, "DUPLIKAT", 0xFF2D2B28.toInt()),
            Triple(editShapeCycleRect, "BENTUK: ${sel?.shape?.displayName ?: "BULAT"}", 0xFF2D2B28.toInt()),
            Triple(editGridSnapRect, if (editorGridSnapEnabled) "SNAP: ON" else "SNAP: OFF", if (editorGridSnapEnabled) 0xFF4E8C57.toInt() else 0xFF2D2B28.toInt()),
            Triple(editBindKeyRect, "KEY & LABEL", 0xFF5A7D9A.toInt())
        )

        for ((idx, item) in row1Items.withIndex()) {
            val rLeft = left + gap + idx * (itemW + gap)
            item.first.set(rLeft, row1Top, rLeft + itemW, row1Top + rowH)
            bgFillPaint.color = item.third
            canvas.drawRoundRect(item.first, dp(6f), dp(6f), bgFillPaint)
            textPaint.textSize = dp(9.0f)
            canvas.drawText(item.second, item.first.centerX(), item.first.centerY() + dp(3.2f), textPaint)
        }

        val row2Items = listOf(
            Triple(editSizeMinusRect, "UKURAN -", 0xFF2D2B28.toInt()),
            Triple(editSizePlusRect, "UKURAN +", 0xFF2D2B28.toInt()),
            Triple(editOpacityMinusRect, "OPASITAS -", 0xFF2D2B28.toInt()),
            Triple(editDeleteKeyRect, "HAPUS BTN", 0xFFB85448.toInt()),
            Triple(editSaveHudRect, "SIMPAN HUD", 0xFF4E8C57.toInt())
        )
        // Split Opasitas-/+ into compact pair in slot 2
        val r2Col0Left = left + gap
        editSizeMinusRect.set(r2Col0Left, row2Top, r2Col0Left + itemW, row2Top + rowH)
        val r2Col1Left = r2Col0Left + itemW + gap
        editSizePlusRect.set(r2Col1Left, row2Top, r2Col1Left + itemW, row2Top + rowH)

        val r2Col2Left = r2Col1Left + itemW + gap
        val halfItemW = (itemW - gap) * 0.5f
        editOpacityMinusRect.set(r2Col2Left, row2Top, r2Col2Left + halfItemW, row2Top + rowH)
        editOpacityPlusRect.set(r2Col2Left + halfItemW + gap, row2Top, r2Col2Left + itemW, row2Top + rowH)

        val r2Col3Left = r2Col2Left + itemW + gap
        editDeleteKeyRect.set(r2Col3Left, row2Top, r2Col3Left + itemW, row2Top + rowH)
        val r2Col4Left = r2Col3Left + itemW + gap
        editSaveHudRect.set(r2Col4Left, row2Top, r2Col4Left + itemW, row2Top + rowH)

        for ((rect, lbl, col) in listOf(
            Triple(editSizeMinusRect, "UKURAN -", 0xFF2D2B28.toInt()),
            Triple(editSizePlusRect, "UKURAN +", 0xFF2D2B28.toInt()),
            Triple(editOpacityMinusRect, "OPAS -", 0xFF2D2B28.toInt()),
            Triple(editOpacityPlusRect, "OPAS +", 0xFF2D2B28.toInt()),
            Triple(editDeleteKeyRect, "HAPUS", 0xFFB85448.toInt()),
            Triple(editSaveHudRect, "SIMPAN HUD", 0xFF4E8C57.toInt())
        )) {
            bgFillPaint.color = col
            canvas.drawRoundRect(rect, dp(6f), dp(6f), bgFillPaint)
            textPaint.textSize = dp(8.8f)
            canvas.drawText(lbl, rect.centerX(), rect.centerY() + dp(3.2f), textPaint)
        }
    }

    private fun drawHotbarBar(canvas: Canvas, w: Float, h: Float) {
        val barWidth = (w * 0.40f).coerceIn(dp(260f), dp(420f))
        val barHeight = dp(34f)
        val left = (w - barWidth) / 2f
        val top = h - barHeight - dp(8f)
        hotbarRect.set(left, top, left + barWidth, top + barHeight)

        canvas.drawRoundRect(hotbarRect, dp(8f), dp(8f), hudBannerPaint)
        strokePaint.color = 0x6658544C
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(hotbarRect, dp(8f), dp(8f), strokePaint)

        val slotWidth = barWidth / 9f
        for (i in 0 until 9) {
            val sLeft = left + i * slotWidth
            val isSelected = (i == activeHotbarSlot)
            val slotRect = RectF(
                sLeft + dp(2.2f),
                top + dp(2.2f),
                sLeft + slotWidth - dp(2.2f),
                top + barHeight - dp(2.2f)
            )
            if (isSelected) {
                bgFillPaint.color = 0x384E8C57
                canvas.drawRoundRect(slotRect, dp(5.5f), dp(5.5f), bgFillPaint)
            }
            strokePaint.color = if (isSelected) 0xFF5FA869.toInt() else 0x4478736A
            strokePaint.strokeWidth = if (isSelected) dp(1.8f) else dp(1f)
            canvas.drawRoundRect(slotRect, dp(5.5f), dp(5.5f), strokePaint)

            textPaint.textSize = if (isSelected) dp(10.5f) else dp(9.5f)
            textPaint.color = if (isSelected) 0xFFF4F1EA.toInt() else 0xFFB8B2A6.toInt()
            canvas.drawText("${i + 1}", slotRect.centerX(), slotRect.centerY() + dp(3.6f), textPaint)
        }
        textPaint.color = 0xFFF4F1EA.toInt()
    }

    private fun drawTopTelemetryPill(canvas: Canvas, w: Float) {
        val pillW = dp(430f).coerceAtMost(w * 0.76f)
        val pillH = dp(26f)
        val left = (w - pillW) / 2f
        val top = dp(8f)
        val rect = RectF(left, top, left + pillW, top + pillH)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), hudBannerPaint)
        strokePaint.color = 0x7758544C
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), strokePaint)

        val cursorOn = isCursorEffective
        bgFillPaint.color = if (cursorOn) 0xFFC87D55.toInt() else 0xFF5FA869.toInt()
        canvas.drawCircle(left + dp(12f), rect.centerY(), dp(3.5f), bgFillPaint)

        textPaint.textSize = dp(9.5f)
        val gyroState = if (toggledButtons.contains("btn_gyro")) "GYRO:ON" else "GYRO:OFF"
        val curState = if (cursorOn) "KURSOR:AKTIF" else "KAMERA:3D"
        val cpsL = macroEngine.getLeftCps()
        val cpsR = macroEngine.getRightCps()
        canvas.drawText(
            "MC 1.20.4 ($activeRendererLabel)  |  $currentFps FPS  |  $cpsL/$cpsR CPS  |  $curState  |  $gyroState",
            rect.centerX() + dp(5f),
            rect.centerY() + dp(3.5f),
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
                if (!isHudCleanModeHidden && hotbarRect.contains(x, y)) {
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
                    if (isCursorEffective && directTouchMenuClickEnabled) {
                        setVirtualCursorPosition(x, y)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    if (pid == lookPointerId) {
                        val curX = event.getX(i)
                        val curY = event.getY(i)
                        val rawDx = curX - lastLookX
                        val rawDy = curY - lastLookY
                        lastLookX = curX
                        lastLookY = curY

                        if (isCursorEffective) {
                            if (directTouchMenuClickEnabled) {
                                setVirtualCursorPosition(curX, curY)
                            } else {
                                moveVirtualCursorByDelta(
                                    rawDx * touchLookSensitivity * cursorSpeedMultiplier * 1.4f,
                                    rawDy * touchLookSensitivity * cursorSpeedMultiplier * 1.4f
                                )
                            }
                        } else {
                            NativeEngineBridge.sendMouseDelta(
                                rawDx * touchLookSensitivity,
                                rawDy * touchLookSensitivity
                            )
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
                    val dt = System.currentTimeMillis() - downLookTimestamp
                    val dist = hypot((x - downLookX).toDouble(), (y - downLookY).toDouble())
                    if (isCursorEffective && dt < 300L && dist < dp(18f)) {
                        if (directTouchMenuClickEnabled) {
                            setVirtualCursorPosition(x, y)
                        }
                        performCursorClick(NativeEngineBridge.MOUSE_LEFT)
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
                when {
                    editAddKeyRect.contains(x, y) -> {
                        val newBtn = HudButtonConfig(
                            id = "custom_${System.currentTimeMillis()}",
                            label = "KEY G",
                            normX = 0.50f,
                            normY = 0.54f,
                            radiusDp = 24f,
                            opacity = 0.82f,
                            actionType = ActionType.KEY_HOLD,
                            keyCodeOrButton = 71,
                            isToggle = false,
                            accentHex = 0xFF4E8C57.toInt(),
                            shape = ButtonShape.ROUND_RECT
                        )
                        buttons.add(newBtn)
                        selectedEditButton = newBtn
                        ControlProfileManager.saveLayout(context, buttons)
                        postInvalidateOnAnimation()
                        return true
                    }
                    editDuplicateRect.contains(x, y) -> {
                        selectedEditButton?.let { src ->
                            val copyBtn = src.copy(
                                id = "custom_${System.currentTimeMillis()}",
                                normX = (src.normX + 0.06f).coerceIn(0.06f, 0.94f),
                                normY = (src.normY + 0.06f).coerceIn(0.08f, 0.92f)
                            )
                            buttons.add(copyBtn)
                            selectedEditButton = copyBtn
                            ControlProfileManager.saveLayout(context, buttons)
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editShapeCycleRect.contains(x, y) -> {
                        selectedEditButton?.let { sel ->
                            val shapes = ButtonShape.values()
                            sel.shape = shapes[(sel.shape.ordinal + 1) % shapes.size]
                            ControlProfileManager.saveLayout(context, buttons)
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editGridSnapRect.contains(x, y) -> {
                        editorGridSnapEnabled = !editorGridSnapEnabled
                        return true
                    }
                    editBindKeyRect.contains(x, y) -> {
                        onOpenKeybindEditorRequested?.invoke(selectedEditButton)
                        return true
                    }
                    editSizeMinusRect.contains(x, y) -> {
                        selectedEditButton?.let {
                            it.radiusDp = (it.radiusDp - 2.5f).coerceAtLeast(14f)
                            ControlProfileManager.saveLayout(context, buttons)
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editSizePlusRect.contains(x, y) -> {
                        selectedEditButton?.let {
                            it.radiusDp = (it.radiusDp + 2.5f).coerceAtMost(62f)
                            ControlProfileManager.saveLayout(context, buttons)
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editOpacityMinusRect.contains(x, y) -> {
                        selectedEditButton?.let {
                            it.opacity = (it.opacity - 0.08f).coerceAtLeast(0.20f)
                            ControlProfileManager.saveLayout(context, buttons)
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editOpacityPlusRect.contains(x, y) -> {
                        selectedEditButton?.let {
                            it.opacity = (it.opacity + 0.08f).coerceAtMost(1.0f)
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
                        onExitHudEditor?.invoke()
                        postInvalidateOnAnimation()
                        return true
                    }
                }

                val hit = findHitButton(x, y, includeHidden = true)
                if (hit?.actionType == ActionType.HUD_EDITOR_TOGGLE) {
                    isEditMode = false
                    toggledButtons.remove(hit.id)
                    ControlProfileManager.saveLayout(context, buttons)
                    onExitHudEditor?.invoke()
                } else if (hit != null) {
                    draggingButton = hit
                    selectedEditButton = hit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                draggingButton?.let { btn ->
                    var nx = (x / width.coerceAtLeast(1)).coerceIn(0.04f, 0.96f)
                    var ny = (y / height.coerceAtLeast(1)).coerceIn(0.06f, 0.94f)
                    if (editorGridSnapEnabled) {
                        nx = ((nx * 40f).roundToInt() / 40f).coerceIn(0.04f, 0.96f)
                        ny = ((ny * 40f).roundToInt() / 40f).coerceIn(0.06f, 0.94f)
                    }
                    btn.normX = nx
                    btn.normY = ny
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
                    if (nowActive) {
                        NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
                    }
                }
                ActionType.HUD_EDITOR_TOGGLE -> {
                    isEditMode = nowActive
                    if (isEditMode && selectedEditButton == null) {
                        selectedEditButton = buttons.firstOrNull()
                    }
                }
                ActionType.TOGGLE_HUD_CLEAN -> {
                    isHudCleanModeHidden = nowActive
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
                lastCursorClickMs = System.currentTimeMillis()
                macroEngine.registerManualLeftClick()
                if (isCursorEffective) {
                    NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
                }
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, true)
                if (holdAutoClickEnabled && !isCursorEffective) {
                    macroEngine.setLeftAutoClickActive(true) { postInvalidateOnAnimation() }
                }
            }
            ActionType.MOUSE_RIGHT_PLACE -> {
                isHoldingRightPlace = true
                lastCursorClickMs = System.currentTimeMillis()
                macroEngine.registerManualRightClick()
                if (isCursorEffective) {
                    NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
                }
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_RIGHT, true)
                if (!isCursorEffective) {
                    macroEngine.setRightFastPlaceActive(true) { postInvalidateOnAnimation() }
                }
            }
            ActionType.MOUSE_MIDDLE_PICK -> {
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_MIDDLE, true)
            }
            ActionType.MOUSE_SCROLL_UP -> {
                NativeEngineBridge.sendScroll(0f, 1.0f)
                activeHotbarSlot = (activeHotbarSlot + 8) % 9
            }
            ActionType.MOUSE_SCROLL_DOWN -> {
                NativeEngineBridge.sendScroll(0f, -1.0f)
                activeHotbarSlot = (activeHotbarSlot + 1) % 9
            }
            ActionType.HOTBAR_SLOT -> {
                selectHotbarSlot(btn.keyCodeOrButton.coerceIn(0, 8))
            }
            ActionType.QUICK_SETTINGS -> onQuickSettingsRequested?.invoke()
            ActionType.PVP_COMBO_MACRO -> {
                macroEngine.executeSprintResetCombo { postInvalidateOnAnimation() }
            }
            ActionType.MACRO_DROP_STACK -> {
                macroEngine.executeDropStack()
            }
            ActionType.MACRO_HITBOXES -> {
                macroEngine.executeF3Shortcut(66) // F3 + B
            }
            ActionType.MACRO_CHUNK_BORDERS -> {
                macroEngine.executeF3Shortcut(71) // F3 + G
            }
            ActionType.MACRO_RELOAD_CHUNKS -> {
                macroEngine.executeF3Shortcut(65) // F3 + A
            }
            ActionType.TOGGLE_HUD_CLEAN -> {
                isHudCleanModeHidden = !isHudCleanModeHidden
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
                if (isCursorEffective) {
                    NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
                }
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_LEFT, false)
            }
            ActionType.MOUSE_RIGHT_PLACE -> {
                isHoldingRightPlace = false
                macroEngine.setRightFastPlaceActive(false)
                if (isCursorEffective) {
                    NativeEngineBridge.sendCursorPos(virtualCursorX, virtualCursorY)
                }
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_RIGHT, false)
            }
            ActionType.MOUSE_MIDDLE_PICK -> {
                NativeEngineBridge.sendMouseButton(NativeEngineBridge.MOUSE_MIDDLE, false)
            }
            else -> {}
        }
    }

    private fun selectHotbarSlot(slotIndex: Int) {
        activeHotbarSlot = slotIndex.coerceIn(0, 8)
        val keyCode = NativeEngineBridge.KEY_1 + activeHotbarSlot
        NativeEngineBridge.sendKeyEvent(keyCode, true)
        postDelayed({
            NativeEngineBridge.sendKeyEvent(keyCode, false)
        }, 25L)
    }

    private fun findHitButton(x: Float, y: Float, includeHidden: Boolean = false): HudButtonConfig? {
        val w = width.toFloat()
        val h = height.toFloat()
        return buttons.firstOrNull { btn ->
            if (!btn.isVisible && !includeHidden) return@firstOrNull false
            if (isHudCleanModeHidden && !includeHidden &&
                btn.id != "btn_settings" && btn.actionType != ActionType.TOGGLE_HUD_CLEAN
            ) {
                return@firstOrNull false
            }
            val cx = btn.normX * w
            val cy = btn.normY * h
            val r = dp(btn.radiusDp) * 1.15f
            when (btn.shape) {
                ButtonShape.PILL -> abs(x - cx) <= r * 1.38f && abs(y - cy) <= r * 0.85f
                ButtonShape.ROUND_RECT -> abs(x - cx) <= r && abs(y - cy) <= r
                ButtonShape.CIRCLE -> hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r
            }
        }
    }

    private fun modulateAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
