package io.velocraft.android.controls

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.engine.AccountManager
import io.velocraft.android.engine.ModrinthSyncManager
import io.velocraft.android.engine.PerformanceBooster
import io.velocraft.android.util.SystemImeHelper
import io.velocraft.android.util.VeloLogger
import io.velocraft.android.util.VeloVectorIcons
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-Touch Pro PvP HUD Overlay, Maximized Top Telemetry Bar, Direct + Precision Virtual Mouse Cursor,
 * Slide-Out In-Game Sidebar Menu (with Custom Vector Icons, Scrollable HUD/Control Settings,
 * Shareable Control `.json`, Live Modrinth Sync, and System IME Keyboard Trigger) & Visual HUD Studio.
 *
 * Strictly zero stock Android widgets, zero neon colors, and zero emojis.
 */
class ProHudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class SidebarSection(val shortTitle: String, val headerTitle: String) {
        CONTROLS_HUD("OPASITAS", "ATUR OPASITAS, UKURAN & KONTROL 60/120HZ"),
        TELEMETRY_LOG("TURBO C++", "TURBO FPS REDMI A2 (C++ .SO) & LOG BOOT"),
        SHARE_JSON("BAGI HUD", "BAGIKAN, EKSPOR & IMPOR KONTROL (.JSON)"),
        MODRINTH_SYNC("PASANG MOD", "UNDUH & KELOLA MOD MINECRAFT 1.20.4"),
        IME_SHORTCUTS("KEYBOARD", "KEYBOARD ANDROID & PINTASAN CEPAT")
    }

    private data class SidebarActionHit(
        val rect: RectF,
        val action: () -> Unit
    )

    private data class SidebarSliderHit(
        val rect: RectF,
        val minVal: Float,
        val maxVal: Float,
        val onValue: (Float) -> Unit
    )

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

    var touchLookSensitivity: Float = prefs.getFloat("touch_look_sens", 0.90f)
        set(value) {
            field = value.coerceIn(0.15f, 3.0f)
            prefs.edit().putFloat("touch_look_sens", field).apply()
        }

    var globalHudOpacity: Float = prefs.getFloat(
        "global_hud_opacity",
        buttons.firstOrNull()?.opacity ?: 0.78f
    ).coerceIn(0.08f, 1.0f)
        set(value) {
            field = value.coerceIn(0.08f, 1.0f)
            prefs.edit().putFloat("global_hud_opacity", field).apply()
        }

    var cursorSpeedMultiplier: Float = prefs.getFloat("cursor_speed_mult", 1.35f)
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

    // Slide-Out Left Sidebar Drawer State
    var isSidebarDrawerOpen: Boolean = false
    private var activeSidebarSection: SidebarSection = SidebarSection.CONTROLS_HUD
    private var sidebarScrollY: Float = 0f
    private var sidebarMaxScrollY: Float = 0f
    private var sidebarTouchDownX: Float = 0f
    private var sidebarTouchDownY: Float = 0f
    private var sidebarTouchLastY: Float = 0f
    private var sidebarIsDraggingScroll: Boolean = false
    private var activeDraggingSidebarSlider: SidebarSliderHit? = null
    private val sidebarPanelRect = RectF()
    private val sidebarCloseRect = RectF()
    private val sidebarNavRects = Array(SidebarSection.values().size) { RectF() }
    private val sidebarActionHits = mutableListOf<SidebarActionHit>()
    private val sidebarSliderHits = mutableListOf<SidebarSliderHit>()
    private var customJsonProfileName: String = prefs.getString("custom_json_name", "kontrol-xyverse-pro") ?: "kontrol-xyverse-pro"

    // Maximized Top Telemetry Bar Hit Rects
    private val topBarSidebarBtnRect = RectF()
    private val topBarLogBtnRect = RectF()
    private val topBarBoostBtnRect = RectF()
    private val topBarImeBtnRect = RectF()
    private val topBarJsonBtnRect = RectF()
    private val topBarMainRect = RectF()

    // In-HUD Boost & Status Toast Banner
    private var hudStatusToastMessage: String? = null
    private var hudStatusToastExpireMs: Long = 0L

    fun showHudToast(message: String, durationMs: Long = 3500L) {
        hudStatusToastMessage = message
        hudStatusToastExpireMs = System.currentTimeMillis() + durationMs
        postInvalidateOnAnimation()
    }

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
    private val vectorIconPath = Path()

    val isCursorEffective: Boolean
        get() = isVirtualCursorEnabled || !NativeEngineBridge.isCursorGrabbed()

    // HUD Editor state
    var isEditMode: Boolean = false
        private set
    private var draggingButton: HudButtonConfig? = null
    private var selectedEditButton: HudButtonConfig? = null

    fun exitVisualEditMode() {
        isEditMode = false
        draggingButton = null
        selectedEditButton = null
        ControlProfileManager.saveLayout(context, buttons)
        onExitHudEditor?.invoke()
        postInvalidateOnAnimation()
    }

    // Editor 3-Row Studio Dock Hit Rects (including interactive Opacity & Size Sliders)
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
    private val editOpacitySliderRect = RectF()
    private val editSizeSliderRect = RectF()
    private val editApplyAllOpacityRect = RectF()
    private var editorDraggingOpacitySlider = false
    private var editorDraggingSizeSlider = false

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
    var onReopenBootLogRequested: (() -> Unit)? = null

    // Maximized Telemetry State
    var currentFps: Int = 120
    var activeRendererLabel: String = "AUTO"
    private val fpsHistory = IntArray(24) { 60 }
    private var fpsHistoryIdx = 0
    private var lastTelemetrySampleMs = 0L
    private var cachedJvmUsedMb = 320
    private var cachedJvmMaxMb = 768
    private var cachedSysRamPct = 62
    private var cachedOnePercentLow = 54
    private var cachedFrameTimeMs = 16.6f

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
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.7f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFF4F1EA.toInt()
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(11.5f)
        isFakeBoldText = true
    }
    private val leftTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textAlign = Paint.Align.LEFT
        textSize = dp(10.5f)
        isFakeBoldText = true
    }
    private val subTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8B2A6.toInt()
        textAlign = Paint.Align.LEFT
        textSize = dp(9.0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x44A09B90
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }
    private val hudBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6181715.toInt()
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

    fun enterVisualEditMode() {
        isSidebarDrawerOpen = false
        isEditMode = true
        toggledButtons.add("btn_edit_hud")
        if (selectedEditButton == null) {
            selectedEditButton = buttons.firstOrNull()
        }
        postInvalidateOnAnimation()
    }

    fun toggleSidebarDrawer(section: SidebarSection? = null) {
        if (section != null) {
            if (isSidebarDrawerOpen && activeSidebarSection == section) {
                isSidebarDrawerOpen = false
            } else {
                activeSidebarSection = section
                sidebarScrollY = 0f
                isSidebarDrawerOpen = true
            }
        } else {
            isSidebarDrawerOpen = !isSidebarDrawerOpen
            if (isSidebarDrawerOpen) sidebarScrollY = 0f
        }
        postInvalidateOnAnimation()
    }

    fun getButtonsList(): MutableList<HudButtonConfig> = buttons

    fun reloadLayoutFromDisk() {
        val loaded = ControlProfileManager.loadLayout(context)
        buttons.clear()
        buttons.addAll(loaded)
        selectedEditButton = buttons.firstOrNull()
        postInvalidateOnAnimation()
    }

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

    fun scaleAllButtons(deltaDp: Float) {
        buttons.forEach { btn ->
            btn.radiusDp = (btn.radiusDp + deltaDp).coerceIn(14f, 60f)
        }
        ControlProfileManager.saveLayout(context, buttons)
        postInvalidateOnAnimation()
    }

    fun setAllButtonsRadiusDp(targetRadiusDp: Float) {
        val clamped = targetRadiusDp.coerceIn(14f, 60f)
        buttons.forEach { btn ->
            btn.radiusDp = clamped
        }
        ControlProfileManager.saveLayout(context, buttons)
        postInvalidateOnAnimation()
    }

    fun getAverageButtonRadiusDp(): Float {
        if (buttons.isEmpty()) return 24f
        return buttons.map { it.radiusDp }.average().toFloat().coerceIn(14f, 60f)
    }

    fun adjustAllButtonsOpacity(delta: Float) {
        val next = (globalHudOpacity + delta).coerceIn(0.08f, 1.0f)
        setAllButtonsOpacity(next)
    }

    fun setAllButtonsOpacity(targetOpacity: Float) {
        val clamped = targetOpacity.coerceIn(0.08f, 1.0f)
        globalHudOpacity = clamped
        buttons.forEach { btn ->
            btn.opacity = clamped
        }
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

    private fun refreshLiveTelemetrySamples() {
        val now = System.currentTimeMillis()
        if (now - lastTelemetrySampleMs < 750L) return
        lastTelemetrySampleMs = now

        val safeFps = currentFps.coerceIn(1, 360)
        fpsHistory[fpsHistoryIdx] = safeFps
        fpsHistoryIdx = (fpsHistoryIdx + 1) % fpsHistory.size

        var minFps = safeFps
        for (v in fpsHistory) {
            if (v in 1 until minFps) minFps = v
        }
        cachedOnePercentLow = minFps
        cachedFrameTimeMs = (1000f / safeFps.toFloat()).coerceIn(2.5f, 99.9f)

        val snap = NativeEngineBridge.getBootTelemetry()
        if (snap != null && snap.jvmMaxRamMb > 0) {
            cachedJvmUsedMb = snap.jvmUsedRamMb.coerceAtLeast(96)
            cachedJvmMaxMb = snap.jvmMaxRamMb
        }

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                if (mi.totalMem > 0) {
                    val usedPct = (((mi.totalMem - mi.availMem) * 100L) / mi.totalMem).toInt()
                    cachedSysRamPct = usedPct.coerceIn(15, 98)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        refreshLiveTelemetrySamples()

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
        }

        // Always draw Maximized Top Statistics & Quick Action Bar
        drawMaximizedTopTelemetryBar(canvas, w)

        for (btn in buttons) {
            if (!btn.isVisible && !isEditMode) continue
            if (isHudCleanModeHidden && !isEditMode &&
                btn.id != "btn_settings" &&
                btn.actionType != ActionType.TOGGLE_HUD_CLEAN &&
                btn.actionType != ActionType.SIDEBAR_DRAWER_TOGGLE
            ) {
                continue
            }

            val cx = btn.normX * w
            val cy = btn.normY * h
            val r = dp(btn.radiusDp)
            val isPressed = activeButtons.contains(btn.id) ||
                toggledButtons.contains(btn.id) ||
                (btn.actionType == ActionType.CURSOR_TOGGLE && isCursorEffective) ||
                (btn.actionType == ActionType.SIDEBAR_DRAWER_TOGGLE && isSidebarDrawerOpen)
            val isSelectedInEditor = isEditMode && selectedEditButton?.id == btn.id
            val effectiveOpacity = (if (!btn.isVisible && isEditMode) 0.28f else btn.opacity).coerceIn(0.08f, 1.0f)

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

            // 1. Outer shadow/backing frame (scales with effectiveOpacity)
            bgFillPaint.color = modulateAlpha(0xFF0A0C0B.toInt(), (effectiveOpacity * 0.56f).coerceIn(0.04f, 0.72f))
            tmpBtnRect.set(cx - halfW - dp(2.0f), cy - halfH - dp(1.5f), cx + halfW + dp(2.0f), cy + halfH + dp(2.8f))
            if (btn.shape == ButtonShape.CIRCLE) {
                canvas.drawCircle(cx, cy + dp(1.0f), r + dp(2.0f), bgFillPaint)
            } else {
                canvas.drawRoundRect(tmpBtnRect, cornerRad + dp(1.5f), cornerRad + dp(1.5f), bgFillPaint)
            }

            // 2. Sculpted 3D-beveled button surface with vertical gradient (scales with effectiveOpacity)
            val topSurfCol = if (isPressed) {
                modulateAlpha(btn.accentHex, (effectiveOpacity + 0.25f).coerceIn(0.22f, 0.96f))
            } else {
                modulateAlpha(0xFF282D29.toInt(), (effectiveOpacity * 0.88f).coerceIn(0.06f, 0.92f))
            }
            val botSurfCol = if (isPressed) {
                modulateAlpha(0xFF1A241D.toInt(), (effectiveOpacity + 0.22f).coerceIn(0.20f, 0.95f))
            } else {
                modulateAlpha(0xFF151816.toInt(), (effectiveOpacity * 0.90f).coerceIn(0.06f, 0.94f))
            }
            tmpBtnRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            bgFillPaint.shader = android.graphics.LinearGradient(
                cx, cy - halfH, cx, cy + halfH,
                topSurfCol, botSurfCol, android.graphics.Shader.TileMode.CLAMP
            )
            if (btn.shape == ButtonShape.CIRCLE) {
                canvas.drawCircle(cx, cy, r, bgFillPaint)
            } else {
                canvas.drawRoundRect(tmpBtnRect, cornerRad, cornerRad, bgFillPaint)
            }
            bgFillPaint.shader = null

            // 3. Subtle inner bevel ring + top specular glass highlight (scales with effectiveOpacity)
            strokePaint.color = if (isPressed) {
                modulateAlpha(0xFFFFFFFF.toInt(), (effectiveOpacity * 0.55f).coerceIn(0.12f, 0.65f))
            } else {
                modulateAlpha(btn.accentHex, (effectiveOpacity * 0.42f).coerceIn(0.05f, 0.52f))
            }
            strokePaint.strokeWidth = dp(1.1f)
            val inset = dp(3.0f)
            if (btn.shape == ButtonShape.CIRCLE) {
                canvas.drawCircle(cx, cy, (r - inset).coerceAtLeast(dp(4f)), strokePaint)
            } else {
                tmpBtnRect.set(cx - halfW + inset, cy - halfH + inset, cx + halfW - inset, cy + halfH - inset)
                canvas.drawRoundRect(tmpBtnRect, (cornerRad - dp(2f)).coerceAtLeast(dp(3f)), (cornerRad - dp(2f)).coerceAtLeast(dp(3f)), strokePaint)
            }

            // Top specular highlight line
            strokePaint.color = modulateAlpha(0xFFFFFFFF.toInt(), (effectiveOpacity * 0.34f).coerceIn(0.04f, 0.44f))
            if (btn.shape != ButtonShape.CIRCLE && halfW > dp(10f)) {
                canvas.drawLine(cx - halfW * 0.55f, cy - halfH + dp(1.4f), cx + halfW * 0.55f, cy - halfH + dp(1.4f), strokePaint)
            }

            // 4. Outer crisp stroke ring (scales with effectiveOpacity unless selected in editor)
            strokePaint.color = when {
                isSelectedInEditor -> 0xFFF4F1EA.toInt()
                isPressed -> modulateAlpha(0xFFF4F1EA.toInt(), (effectiveOpacity + 0.25f).coerceIn(0.25f, 1.0f))
                else -> modulateAlpha(btn.accentHex, (effectiveOpacity * 0.94f).coerceIn(0.08f, 0.96f))
            }
            strokePaint.strokeWidth = if (isSelectedInEditor || isPressed) dp(2.2f) else dp(1.5f)
            tmpBtnRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
            if (btn.shape == ButtonShape.CIRCLE) {
                canvas.drawCircle(cx, cy, r, strokePaint)
            } else {
                canvas.drawRoundRect(tmpBtnRect, cornerRad, cornerRad, strokePaint)
            }

            // 5. Custom Tactical Vector Icon + Crisp Micro-Label (both scale with effectiveOpacity)
            val iconAlpha = (effectiveOpacity + 0.14f).coerceIn(0.16f, 1.0f)
            val iconPrimaryCol = if (isSelectedInEditor || isPressed) 0xFFF4F1EA.toInt() else 0xFFEAE5DC.toInt()
            val iconAccentCol = btn.accentHex
            val isWidePill = btn.shape == ButtonShape.PILL || halfW > halfH * 1.24f

            if (isWidePill) {
                val iconR = (halfH * 0.50f).coerceIn(dp(6.5f), dp(14f))
                val iconCx = cx - halfW + iconR + dp(6.0f)
                VeloVectorIcons.drawControlActionIcon(
                    canvas = canvas,
                    action = btn.actionType,
                    keyCodeOrButton = btn.keyCodeOrButton,
                    cx = iconCx,
                    cy = cy,
                    r = iconR,
                    primaryColor = iconPrimaryCol,
                    accentColor = iconAccentCol,
                    alphaScale = iconAlpha
                )

                val textStartLeft = iconCx + iconR + dp(3.0f)
                val textRight = cx + halfW - dp(4.0f)
                val availW = (textRight - textStartLeft).coerceAtLeast(dp(18f))
                val textCenterX = (textStartLeft + textRight) * 0.5f

                textPaint.textSize = dp(8.8f)
                val labelStr = if (!btn.isVisible && isEditMode) "${btn.label}*" else btn.label
                while (textPaint.measureText(labelStr) > availW && textPaint.textSize > dp(6.4f)) {
                    textPaint.textSize -= dp(0.4f)
                }
                textPaint.color = if (isSelectedInEditor) {
                    0xFFF4F1EA.toInt()
                } else {
                    modulateAlpha(0xFFF4F1EA.toInt(), (effectiveOpacity + 0.10f).coerceIn(0.14f, 1.0f))
                }
                val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(labelStr, textCenterX, textY, textPaint)
            } else {
                // Circle or Square/RoundRect Button: Sculpted Vector Icon on upper center + Label Badge below
                val minHalf = min(halfW, halfH)
                val iconR = (minHalf * 0.44f).coerceIn(dp(7.5f), dp(22f))
                val iconCy = cy - halfH * 0.17f
                VeloVectorIcons.drawControlActionIcon(
                    canvas = canvas,
                    action = btn.actionType,
                    keyCodeOrButton = btn.keyCodeOrButton,
                    cx = cx,
                    cy = iconCy,
                    r = iconR,
                    primaryColor = iconPrimaryCol,
                    accentColor = iconAccentCol,
                    alphaScale = iconAlpha
                )

                textPaint.textSize = when {
                    btn.label.length >= 7 -> dp(7.2f)
                    btn.label.length >= 5 -> dp(7.8f)
                    else -> dp(8.6f)
                }
                val maxLabelW = halfW * 1.68f
                val labelStr = if (!btn.isVisible && isEditMode) "${btn.label}*" else btn.label
                while (textPaint.measureText(labelStr) > maxLabelW && textPaint.textSize > dp(6.0f)) {
                    textPaint.textSize -= dp(0.4f)
                }
                textPaint.color = if (isSelectedInEditor) {
                    0xFFF4F1EA.toInt()
                } else {
                    modulateAlpha(0xFFDCD7CC.toInt(), (effectiveOpacity + 0.08f).coerceIn(0.12f, 1.0f))
                }
                val labelCy = cy + halfH * 0.56f
                val textY = labelCy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(labelStr, cx, textY, textPaint)
            }
            textPaint.color = 0xFFF4F1EA.toInt()
        }

        if (isEditMode) {
            drawEditorSelectionGuides(canvas, w, h)
            drawEditorStudioDock(canvas, w, h)
        }

        if (isSidebarDrawerOpen && !isEditMode) {
            drawSlideOutSidebarDrawer(canvas, w, h)
        }

        if (isCursorEffective && !isEditMode && !isSidebarDrawerOpen) {
            drawVirtualMouseCursor(canvas, virtualCursorX, virtualCursorY)
        }
    }

    /**
     * Draws the Maximized Top Statistics & Telemetry Bar with Custom Vector Icons,
     * FPS + 1% Low + Frame Time ms + Sparkline, CPS L/R, JVM/Device RAM, Renderer,
     * and Quick Action Pills (`[SIDEBAR]`, `[IME KEYB]`, `[JSON]`).
     */
    private fun drawMaximizedTopTelemetryBar(canvas: Canvas, w: Float) {
        val barH = dp(25f)
        val top = dp(3.5f)
        val sideMargin = dp(8f)
        val totalW = (w - sideMargin * 2f).coerceAtMost(dp(840f))
        val startX = (w - totalW) * 0.5f

        val sidebarBtnW = dp(76f)
        val logBtnW = dp(54f)
        val boostBtnW = dp(60f)
        val rightBtnW = dp(62f)
        val gap = dp(4.5f)

        // 1A. Left Quick-Trigger Pill: Slide-Out Sidebar Button with Custom Command Deck Icon
        topBarSidebarBtnRect.set(startX, top, startX + sidebarBtnW, top + barH)
        bgFillPaint.color = if (isSidebarDrawerOpen) 0xFF4E8C57.toInt() else 0xEB1A1917.toInt()
        canvas.drawRoundRect(topBarSidebarBtnRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = if (isSidebarDrawerOpen) 0xFFF4F1EA.toInt() else 0xFF4E8C57.toInt()
        strokePaint.strokeWidth = dp(1.3f)
        canvas.drawRoundRect(topBarSidebarBtnRect, dp(7f), dp(7f), strokePaint)

        VeloVectorIcons.drawControlActionIcon(
            canvas = canvas,
            action = ActionType.SIDEBAR_DRAWER_TOGGLE,
            keyCodeOrButton = 0,
            cx = topBarSidebarBtnRect.left + dp(13f),
            cy = topBarSidebarBtnRect.centerY(),
            r = dp(6.8f),
            primaryColor = 0xFFF4F1EA.toInt(),
            accentColor = 0xFF5FA869.toInt()
        )

        textPaint.textSize = dp(8.2f)
        textPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText(
            "SIDEBAR",
            topBarSidebarBtnRect.left + dp(47f),
            topBarSidebarBtnRect.centerY() + dp(3.0f),
            textPaint
        )

        // 1B. Left-2 Quick-Trigger Pill: [LOG] Button to Reopen Boot Log & Hadits Overlay Anytime!
        topBarLogBtnRect.set(topBarSidebarBtnRect.right + gap, top, topBarSidebarBtnRect.right + gap + logBtnW, top + barH)
        bgFillPaint.color = 0xEB1D2B22.toInt()
        canvas.drawRoundRect(topBarLogBtnRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFF5FA869.toInt()
        canvas.drawRoundRect(topBarLogBtnRect, dp(7f), dp(7f), strokePaint)
        VeloVectorIcons.drawUiIcon(canvas, 5, topBarLogBtnRect.left + dp(12f), topBarLogBtnRect.centerY(), dp(6.2f), 0xFFF4F1EA.toInt(), 0xFF5FA869.toInt())
        canvas.drawText(
            "LOG",
            topBarLogBtnRect.left + dp(35f),
            topBarLogBtnRect.centerY() + dp(3.0f),
            textPaint
        )

        // 2. Right Quick-Action Pills: [BOOST], [JSON] & [IME KEY]
        val endX = startX + totalW
        topBarImeBtnRect.set(endX - rightBtnW, top, endX, top + barH)
        bgFillPaint.color = 0xEB222D38.toInt()
        canvas.drawRoundRect(topBarImeBtnRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFF5A7D9A.toInt()
        canvas.drawRoundRect(topBarImeBtnRect, dp(7f), dp(7f), strokePaint)
        VeloVectorIcons.drawControlActionIcon(
            canvas = canvas,
            action = ActionType.IME_KEYBOARD_TOGGLE,
            keyCodeOrButton = 0,
            cx = topBarImeBtnRect.left + dp(12f),
            cy = topBarImeBtnRect.centerY(),
            r = dp(6.4f),
            primaryColor = 0xFFF4F1EA.toInt(),
            accentColor = 0xFF5A7D9A.toInt()
        )
        textPaint.textSize = dp(8.0f)
        canvas.drawText("IME KEY", topBarImeBtnRect.left + dp(39f), topBarImeBtnRect.centerY() + dp(2.9f), textPaint)

        topBarJsonBtnRect.set(topBarImeBtnRect.left - gap - dp(52f), top, topBarImeBtnRect.left - gap, top + barH)
        bgFillPaint.color = 0xEB26211C.toInt()
        canvas.drawRoundRect(topBarJsonBtnRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFFC87D55.toInt()
        canvas.drawRoundRect(topBarJsonBtnRect, dp(7f), dp(7f), strokePaint)
        VeloVectorIcons.drawUiIcon(canvas, 7, topBarJsonBtnRect.left + dp(11f), topBarJsonBtnRect.centerY(), dp(5.8f), 0xFFF4F1EA.toInt(), 0xFFC87D55.toInt())
        canvas.drawText("JSON", topBarJsonBtnRect.left + dp(34f), topBarJsonBtnRect.centerY() + dp(2.9f), textPaint)

        topBarBoostBtnRect.set(topBarJsonBtnRect.left - gap - boostBtnW, top, topBarJsonBtnRect.left - gap, top + barH)
        bgFillPaint.color = 0xEB2B2118.toInt()
        canvas.drawRoundRect(topBarBoostBtnRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFFE6B86A.toInt()
        canvas.drawRoundRect(topBarBoostBtnRect, dp(7f), dp(7f), strokePaint)
        VeloVectorIcons.drawUiIcon(canvas, 6, topBarBoostBtnRect.left + dp(12f), topBarBoostBtnRect.centerY(), dp(6.2f), 0xFFE6B86A.toInt(), 0xFFE6B86A.toInt())
        canvas.drawText("BOOST", topBarBoostBtnRect.left + dp(38f), topBarBoostBtnRect.centerY() + dp(2.9f), textPaint)

        // 3. Center Maximized Telemetry Strip
        val mainLeft = topBarLogBtnRect.right + gap
        val mainRight = topBarBoostBtnRect.left - gap
        topBarMainRect.set(mainLeft, top, mainRight, top + barH)
        canvas.drawRoundRect(topBarMainRect, dp(7f), dp(7f), hudBannerPaint)
        strokePaint.color = 0x8858544C.toInt()
        strokePaint.strokeWidth = dp(1.1f)
        canvas.drawRoundRect(topBarMainRect, dp(7f), dp(7f), strokePaint)

        // Status Indicator Dot + Mini FPS Sparkline Bars on the left of the strip
        val cursorOn = isCursorEffective
        bgFillPaint.color = if (cursorOn) 0xFFC87D55.toInt() else 0xFF5FA869.toInt()
        canvas.drawCircle(mainLeft + dp(10f), topBarMainRect.centerY(), dp(3.5f), bgFillPaint)

        val sparkStartX = mainLeft + dp(18f)
        val sparkBottom = top + barH - dp(6f)
        val sparkMaxH = dp(16f)
        for (i in 0 until 8) {
            val sample = fpsHistory[(fpsHistoryIdx + fpsHistory.size - 8 + i) % fpsHistory.size]
            val ratio = (sample / 120f).coerceIn(0.18f, 1.0f)
            val bh = sparkMaxH * ratio
            val bx = sparkStartX + i * dp(3.2f)
            bgFillPaint.color = if (sample >= 45) 0xFF4E8C57.toInt() else 0xFFC87D55.toInt()
            canvas.drawRect(bx, sparkBottom - bh, bx + dp(2.1f), sparkBottom, bgFillPaint)
        }

        // Compose Maximized Telemetry Text
        val cpsL = macroEngine.getLeftCps()
        val cpsR = macroEngine.getRightCps()
        val frameMsStr = String.format("%.1fms", cachedFrameTimeMs)
        val modeStr = if (cursorOn) "KURSOR" else "3D"
        val resPct = PerformanceBooster.getLiveResolutionScalePct(context)

        val fullStats = "$currentFps FPS (1% $cachedOnePercentLow | $frameMsStr)  •  " +
            "CPS $cpsL:$cpsR  •  " +
            "JVM $cachedJvmUsedMb/${cachedJvmMaxMb}MB  •  " +
            "SKALA ${resPct}%  •  " +
            "$activeRendererLabel • $modeStr"

        val textAreaLeft = sparkStartX + dp(29f)
        val textAreaW = (mainRight - textAreaLeft - dp(8f)).coerceAtLeast(dp(60f))
        textPaint.textSize = dp(9.2f)
        textPaint.color = 0xFFF4F1EA.toInt()
        while (textPaint.measureText(fullStats) > textAreaW && textPaint.textSize > dp(7.0f)) {
            textPaint.textSize -= dp(0.4f)
        }
        val clipped = TextUtils.ellipsize(fullStats, textPaint, textAreaW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(
            clipped,
            textAreaLeft + textAreaW * 0.5f,
            topBarMainRect.centerY() + dp(3.2f),
            textPaint
        )

        // 4. Optional In-HUD Boost / Action Toast Banner directly below the Top Telemetry Bar
        val toastMsg = hudStatusToastMessage
        if (toastMsg != null && System.currentTimeMillis() < hudStatusToastExpireMs) {
            val toastW = (w * 0.72f).coerceAtMost(dp(560f))
            val toastLeft = (w - toastW) * 0.5f
            val toastTop = top + barH + dp(5f)
            val toastRect = RectF(toastLeft, toastTop, toastLeft + toastW, toastTop + dp(25f))
            bgFillPaint.color = 0xF0254D2B.toInt()
            canvas.drawRoundRect(toastRect, dp(6f), dp(6f), bgFillPaint)
            strokePaint.color = 0xFF8CD996.toInt()
            canvas.drawRoundRect(toastRect, dp(6f), dp(6f), strokePaint)
            textPaint.textSize = dp(8.8f)
            textPaint.color = 0xFFF4F1EA.toInt()
            val clippedToast = TextUtils.ellipsize(toastMsg, textPaint, toastW - dp(20f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(clippedToast, toastRect.centerX(), toastRect.centerY() + dp(3.1f), textPaint)
        }
    }

    /**
     * Draws custom geometric vector icons (`Canvas` `Path` & lines — zero emojis).
     * `iconType`:
     * 0 = Controls/Sliders
     * 1 = Share/JSON Nodes
     * 2 = Modrinth Cloud/Cube
     * 3 = Keyboard IME
     * 4 = Telemetry/Terminal Pulse
     */
    private fun drawVectorIcon(canvas: Canvas, iconType: Int, cx: Float, cy: Float, r: Float, color: Int) {
        when (iconType) {
            0 -> VeloVectorIcons.drawUiIcon(canvas, 3, cx, cy, r * 1.12f, color, 0xFF5FA869.toInt())
            1 -> VeloVectorIcons.drawUiIcon(canvas, 7, cx, cy, r * 1.12f, color, 0xFFC87D55.toInt())
            2 -> VeloVectorIcons.drawUiIcon(canvas, 2, cx, cy, r * 1.12f, color, 0xFF5A7D9A.toInt())
            3 -> VeloVectorIcons.drawControlActionIcon(canvas, ActionType.IME_KEYBOARD_TOGGLE, 0, cx, cy, r * 1.12f, color, 0xFF5A7D9A.toInt())
            else -> VeloVectorIcons.drawUiIcon(canvas, 6, cx, cy, r * 1.12f, color, 0xFFE6B86A.toInt())
        }
    }

    /**
     * Draws the Slide-Out In-Game Left Sidebar Drawer (`isSidebarDrawerOpen == true`)
     * with a Left Vector Icon Navigation Rail and a Scrollable Content Area.
     */
    private fun drawSlideOutSidebarDrawer(canvas: Canvas, w: Float, h: Float) {
        sidebarActionHits.clear()
        sidebarSliderHits.clear()

        // Subtle right-side scrim so the game world stays visible
        bgFillPaint.color = 0x880C0C0B.toInt()
        canvas.drawRect(0f, 0f, w, h, bgFillPaint)

        val drawerW = dp(390f).coerceAtMost(w * 0.78f)
        val railW = dp(64f)
        sidebarPanelRect.set(0f, 0f, drawerW, h)

        // Drawer Background
        bgFillPaint.color = 0xF5181715.toInt()
        canvas.drawRect(sidebarPanelRect, bgFillPaint)

        // Left Icon Navigation Rail Background
        bgFillPaint.color = 0xFF121110.toInt()
        canvas.drawRect(0f, 0f, railW, h, bgFillPaint)

        // Right Border of Drawer
        strokePaint.color = 0xFF4E8C57.toInt()
        strokePaint.strokeWidth = dp(1.6f)
        canvas.drawLine(drawerW, 0f, drawerW, h, strokePaint)
        strokePaint.color = 0xFF2E2C28.toInt()
        strokePaint.strokeWidth = dp(1.0f)
        canvas.drawLine(railW, 0f, railW, h, strokePaint)

        // Navigation Rail Items (5 Sections with Vector Icons)
        val navTopStart = dp(10f)
        val navItemH = dp(52f)
        val navGap = dp(6f)
        val sections = SidebarSection.values()
        for ((idx, sec) in sections.withIndex()) {
            val itemTop = navTopStart + idx * (navItemH + navGap)
            val rect = sidebarNavRects[idx]
            rect.set(dp(6f), itemTop, railW - dp(6f), itemTop + navItemH)
            val selected = (sec == activeSidebarSection)

            bgFillPaint.color = if (selected) 0xFF28382B.toInt() else 0xFF1C1B18.toInt()
            canvas.drawRoundRect(rect, dp(8f), dp(8f), bgFillPaint)
            strokePaint.color = if (selected) 0xFF4E8C57.toInt() else 0xFF33312C.toInt()
            strokePaint.strokeWidth = if (selected) dp(1.6f) else dp(1f)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), strokePaint)

            val iconColor = if (selected) 0xFFF4F1EA.toInt() else 0xFFB8B2A6.toInt()
            drawVectorIcon(canvas, idx, rect.centerX(), rect.top + dp(19f), dp(7.5f), iconColor)

            textPaint.textSize = dp(7.6f)
            textPaint.color = iconColor
            canvas.drawText(sec.shortTitle, rect.centerX(), rect.bottom - dp(7.5f), textPaint)
        }

        // Drawer Header + Close Button
        val contentLeft = railW + dp(12f)
        val contentRight = drawerW - dp(12f)
        val headerH = dp(42f)

        leftTextPaint.textSize = dp(11.2f)
        leftTextPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText(activeSidebarSection.headerTitle, contentLeft, dp(25f), leftTextPaint)

        sidebarCloseRect.set(contentRight - dp(68f), dp(8f), contentRight, dp(34f))
        bgFillPaint.color = 0xFFC87D55.toInt()
        canvas.drawRoundRect(sidebarCloseRect, dp(6f), dp(6f), bgFillPaint)
        textPaint.textSize = dp(8.8f)
        textPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText("TUTUP [X]", sidebarCloseRect.centerX(), sidebarCloseRect.centerY() + dp(3.1f), textPaint)

        strokePaint.color = 0xFF2E2C28.toInt()
        canvas.drawLine(contentLeft, headerH, contentRight, headerH, strokePaint)

        // Scrollable Content Viewport
        val viewTop = headerH + dp(6f)
        val viewBottom = h - dp(8f)
        val viewH = (viewBottom - viewTop).coerceAtLeast(dp(80f))

        canvas.save()
        canvas.clipRect(contentLeft - dp(2f), viewTop, contentRight + dp(2f), viewBottom)

        var curY = viewTop - sidebarScrollY
        val cardW = contentRight - contentLeft

        when (activeSidebarSection) {
            SidebarSection.CONTROLS_HUD -> {
                curY = drawSidebarControlsSection(canvas, contentLeft, curY, cardW, viewTop, viewBottom)
            }
            SidebarSection.SHARE_JSON -> {
                curY = drawSidebarShareJsonSection(canvas, contentLeft, curY, cardW, viewTop, viewBottom)
            }
            SidebarSection.MODRINTH_SYNC -> {
                curY = drawSidebarModrinthSection(canvas, contentLeft, curY, cardW, viewTop, viewBottom)
            }
            SidebarSection.IME_SHORTCUTS -> {
                curY = drawSidebarImeAndShortcutsSection(canvas, contentLeft, curY, cardW, viewTop, viewBottom)
            }
            SidebarSection.TELEMETRY_LOG -> {
                curY = drawSidebarTelemetrySection(canvas, contentLeft, curY, cardW, viewTop, viewBottom)
            }
        }

        val totalContentH = (curY + sidebarScrollY) - viewTop + dp(16f)
        sidebarMaxScrollY = max(0f, totalContentH - viewH)
        sidebarScrollY = sidebarScrollY.coerceIn(0f, sidebarMaxScrollY)

        canvas.restore()

        // Scrollbar indicator on the right edge of the drawer
        if (sidebarMaxScrollY > dp(8f)) {
            val trackTop = viewTop + dp(4f)
            val trackBottom = viewBottom - dp(4f)
            val trackH = trackBottom - trackTop
            val thumbH = max(dp(24f), trackH * (viewH / (totalContentH.coerceAtLeast(1f))))
            val thumbTop = trackTop + (trackH - thumbH) * (sidebarScrollY / sidebarMaxScrollY)
            bgFillPaint.color = 0x884E8C57.toInt()
            tmpBtnRect.set(drawerW - dp(5f), thumbTop, drawerW - dp(2f), thumbTop + thumbH)
            canvas.drawRoundRect(tmpBtnRect, dp(2f), dp(2f), bgFillPaint)
        }
    }

    private fun drawSidebarControlsSection(
        canvas: Canvas,
        left: Float,
        startY: Float,
        width: Float,
        clipTop: Float,
        clipBottom: Float
    ): Float {
        var y = startY
        val halfW = (width - dp(8f)) * 0.5f

        // 1. Interactive Draggable Slider: OPASITAS / TRANSPARANSI TOMBOL HUD (8% - 100%)
        val curOpPct = (globalHudOpacity * 100f).roundToInt().coerceIn(8, 100)
        y = drawSidebarSliderCard(
            canvas = canvas,
            left = left,
            y = y,
            width = width,
            title = "OPASITAS / TRANSPARANSI TOMBOL HUD",
            valueBadge = "$curOpPct%",
            subtitle = "Geser bar ke kiri agar tombol transparan, atau ke kanan agar tegas",
            currentVal = globalHudOpacity,
            minVal = 0.08f,
            maxVal = 1.00f,
            accentColor = 0xFF4E8C57.toInt(),
            onStepMinus = { adjustAllButtonsOpacity(-0.08f) },
            onStepPlus = { adjustAllButtonsOpacity(+0.08f) },
            onSlideValue = { newOp -> setAllButtonsOpacity(newOp) },
            clipTop = clipTop,
            clipBottom = clipBottom
        )

        // 2. Interactive Draggable Slider: UKURAN SELURUH TOMBOL HUD (14dp - 56dp)
        val avgRad = getAverageButtonRadiusDp()
        y = drawSidebarSliderCard(
            canvas = canvas,
            left = left,
            y = y,
            width = width,
            title = "UKURAN TOMBOL KONTROL HUD",
            valueBadge = "${avgRad.roundToInt()} dp",
            subtitle = "Geser untuk mengecilkan atau membesarkan seluruh tombol layar",
            currentVal = avgRad,
            minVal = 14f,
            maxVal = 56f,
            accentColor = 0xFF5A7D9A.toInt(),
            onStepMinus = { scaleAllButtons(-2.5f) },
            onStepPlus = { scaleAllButtons(+2.5f) },
            onSlideValue = { newRad -> setAllButtonsRadiusDp(newRad) },
            clipTop = clipTop,
            clipBottom = clipBottom
        )

        // 3. Interactive Draggable Slider: SENSITIVITAS GESER KAMERA 60/120Hz (15% - 300%)
        val sensPct = (touchLookSensitivity * 100f).roundToInt()
        y = drawSidebarSliderCard(
            canvas = canvas,
            left = left,
            y = y,
            width = width,
            title = "SENSITIVITAS GESER KAMERA (60/120Hz)",
            valueBadge = "$sensPct%",
            subtitle = "Respons sentuhan unbuffered 60/120Hz dengan akselerasi geser halus",
            currentVal = touchLookSensitivity,
            minVal = 0.15f,
            maxVal = 3.00f,
            accentColor = 0xFFC87D55.toInt(),
            onStepMinus = { touchLookSensitivity -= 0.12f; postInvalidateOnAnimation() },
            onStepPlus = { touchLookSensitivity += 0.12f; postInvalidateOnAnimation() },
            onSlideValue = { newSens -> touchLookSensitivity = newSens; postInvalidateOnAnimation() },
            clipTop = clipTop,
            clipBottom = clipBottom
        )

        // 4. Interactive Draggable Slider: SKALA RESOLUSI RENDER FPS (C++ Hardware Scaler 50% - 100%)
        val resScale = PerformanceBooster.getResolutionScale(context)
        val resPct = (resScale * 100f).roundToInt()
        y = drawSidebarSliderCard(
            canvas = canvas,
            left = left,
            y = y,
            width = width,
            title = "SKALA RESOLUSI RENDER FPS (TURBO C++)",
            valueBadge = "$resPct%",
            subtitle = "65%-75% mendongkrak FPS Redmi A2 hingga 2x lipat tanpa ubah ukuran UI",
            currentVal = resScale,
            minVal = 0.50f,
            maxVal = 1.00f,
            accentColor = 0xFFE6B86A.toInt(),
            onStepMinus = {
                PerformanceBooster.setResolutionScale(context, resScale - 0.05f)
                postInvalidateOnAnimation()
            },
            onStepPlus = {
                PerformanceBooster.setResolutionScale(context, resScale + 0.05f)
                postInvalidateOnAnimation()
            },
            onSlideValue = { newScale ->
                PerformanceBooster.setResolutionScale(context, newScale)
                postInvalidateOnAnimation()
            },
            clipTop = clipTop,
            clipBottom = clipBottom
        )

        // Row 5: Edit HUD Position & Studio Pengaturan Lengkap
        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            " PINDAHKAN POSISI TOMBOL", 0xFFC87D55.toInt(),
            { enterVisualEditMode() },
            "PENGATURAN LENGKAP", 0xFF5A7D9A.toInt(),
            {
                isSidebarDrawerOpen = false
                onQuickSettingsRequested?.invoke()
            },
            clipTop, clipBottom
        )

        // Row 6: Virtual Cursor Toggle & Clean HUD Toggle
        val cursorLbl = if (isVirtualCursorEnabled) "KURSOR MOUSE: ON" else "KURSOR MOUSE: AUTO"
        val cleanLbl = if (isHudCleanModeHidden) "SEMBUNYIKAN HUD: ON" else "SEMBUNYIKAN HUD: OFF"
        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            cursorLbl, if (isVirtualCursorEnabled) 0xFF4E8C57.toInt() else 0xFF282623.toInt(),
            { toggleVirtualCursor() },
            cleanLbl, if (isHudCleanModeHidden) 0xFFC87D55.toInt() else 0xFF282623.toInt(),
            {
                isHudCleanModeHidden = !isHudCleanModeHidden
                postInvalidateOnAnimation()
            },
            clipTop, clipBottom
        )

        // Row 7: Direct Touch Menu vs Relative Trackpad & Cursor Style
        val touchModeLbl = if (directTouchMenuClickEnabled) "MENU: SENTUH LANGSUNG" else "MENU: TRACKPAD"
        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            touchModeLbl, 0xFF282623.toInt(),
            { directTouchMenuClickEnabled = !directTouchMenuClickEnabled },
            "GAYA: ${getCursorStyleName().take(12)}", 0xFF282623.toInt(),
            { cycleCursorStyle() },
            clipTop, clipBottom
        )

        // Row 8: Gyroscope & Auto-Click Combat
        val gyroOn = toggledButtons.contains("btn_gyro")
        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            if (gyroOn) "GYRO AIM: ON" else "GYRO AIM: OFF",
            if (gyroOn) 0xFF4E8C57.toInt() else 0xFF282623.toInt(),
            {
                val next = !gyroOn
                if (next) toggledButtons.add("btn_gyro") else toggledButtons.remove("btn_gyro")
                onGyroToggleRequested?.invoke(next)
                postInvalidateOnAnimation()
            },
            if (holdAutoClickEnabled) "AUTO-CLICK: ON (14 CPS)" else "AUTO-CLICK: OFF",
            if (holdAutoClickEnabled) 0xFF4E8C57.toInt() else 0xFF282623.toInt(),
            {
                holdAutoClickEnabled = !holdAutoClickEnabled
                postInvalidateOnAnimation()
            },
            clipTop, clipBottom
        )

        // Row 9: Quick Presets Header & Buttons
        leftTextPaint.textSize = dp(9.5f)
        leftTextPaint.color = 0xFFB8B2A6.toInt()
        canvas.drawText("Preset Tata Letak Kontrol Cepat:", left, y + dp(13f), leftTextPaint)
        y += dp(18f)

        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            "PRESET STANDAR", 0xFF282623.toInt(),
            { applyLayoutPreset(0) },
            "PRESET PVP CLAW", 0xFF282623.toInt(),
            { applyLayoutPreset(1) },
            clipTop, clipBottom
        )
        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            "PRESET SURVIVAL", 0xFF282623.toInt(),
            { applyLayoutPreset(2) },
            "RESET DEFAULT", 0xFFB85448.toInt(),
            { resetToDefaultLayout() },
            clipTop, clipBottom
        )

        return y
    }

    private fun drawSidebarShareJsonSection(
        canvas: Canvas,
        left: Float,
        startY: Float,
        width: Float,
        clipTop: Float,
        clipBottom: Float
    ): Float {
        var y = startY
        val halfW = (width - dp(8f)) * 0.5f

        // Status Card
        val statusCardH = dp(42f)
        tmpBtnRect.set(left, y, left + width, y + statusCardH)
        bgFillPaint.color = 0xFF22211E.toInt()
        canvas.drawRoundRect(tmpBtnRect, dp(8f), dp(8f), bgFillPaint)
        strokePaint.color = 0xFF3A3732.toInt()
        canvas.drawRoundRect(tmpBtnRect, dp(8f), dp(8f), strokePaint)

        leftTextPaint.textSize = dp(9.2f)
        leftTextPaint.color = 0xFF4E8C57.toInt()
        canvas.drawText("FOLDER JSON: Android/media/id.xyverse.minecraft/controls/", left + dp(8f), y + dp(15f), leftTextPaint)

        subTextPaint.textSize = dp(8.5f)
        val msg = TextUtils.ellipsize(ControlProfileManager.lastShareStatusMessage, subTextPaint, width - dp(16f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(msg, left + dp(8f), y + dp(31f), subTextPaint)
        y += statusCardH + dp(8f)

        // Profile Name Input Field (uses Android System IME Keyboard!)
        val nameRect = RectF(left, y, left + width, y + dp(34f))
        bgFillPaint.color = 0xFF262420.toInt()
        canvas.drawRoundRect(nameRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFF5A7D9A.toInt()
        canvas.drawRoundRect(nameRect, dp(7f), dp(7f), strokePaint)
        leftTextPaint.textSize = dp(9.5f)
        leftTextPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText("Nama File: $customJsonProfileName.json  [KETUK UBAH IME]", left + dp(10f), nameRect.centerY() + dp(3.3f), leftTextPaint)
        registerSidebarHit(nameRect, clipTop, clipBottom) {
            SystemImeHelper.requestTextInput(
                label = "Nama Profil Kontrol JSON",
                initialText = customJsonProfileName,
                maxLength = 32,
                onChanged = { updated ->
                    customJsonProfileName = updated.replace(Regex("[^a-zA-Z0-9_-]"), "")
                    prefs.edit().putString("custom_json_name", customJsonProfileName).apply()
                    postInvalidateOnAnimation()
                }
            )
        }
        y += dp(40f)

        // Export & Share / Copy JSON Buttons
        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            "BAGIKAN FILE .JSON", 0xFF4E8C57.toInt(),
            {
                ControlProfileManager.exportAndShareLayoutJson(context, buttons, customJsonProfileName, openShareSheet = true)
                postInvalidateOnAnimation()
            },
            "EKSPOR & SALIN JSON", 0xFF5A7D9A.toInt(),
            {
                ControlProfileManager.exportAndShareLayoutJson(context, buttons, customJsonProfileName, openShareSheet = false)
                postInvalidateOnAnimation()
            },
            clipTop, clipBottom
        )

        // Import from Clipboard Button
        val importClipRect = RectF(left, y, left + width, y + dp(32f))
        bgFillPaint.color = 0xFFC87D55.toInt()
        canvas.drawRoundRect(importClipRect, dp(7f), dp(7f), bgFillPaint)
        textPaint.textSize = dp(9.2f)
        textPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText("IMPOR KONTROL .JSON DARI CLIPBOARD", importClipRect.centerX(), importClipRect.centerY() + dp(3.2f), textPaint)
        registerSidebarHit(importClipRect, clipTop, clipBottom) {
            val (ok, _) = ControlProfileManager.importLayoutFromClipboard(context)
            if (ok) reloadLayoutFromDisk() else postInvalidateOnAnimation()
        }
        y += dp(38f)

        // Saved External .json Profiles List
        val savedFiles = ControlProfileManager.listSavedExternalJsonProfiles(context)
        leftTextPaint.textSize = dp(9.5f)
        leftTextPaint.color = 0xFFB8B2A6.toInt()
        canvas.drawText("Profil .JSON Tersimpan (${savedFiles.size} berkas):", left, y + dp(12f), leftTextPaint)
        y += dp(18f)

        if (savedFiles.isEmpty()) {
            subTextPaint.textSize = dp(8.8f)
            canvas.drawText("Belum ada file .json diekspor. Ketuk 'BAGIKAN FILE .JSON' di atas.", left, y + dp(14f), subTextPaint)
            y += dp(24f)
        } else {
            for (file in savedFiles) {
                val rowRect = RectF(left, y, left + width, y + dp(32f))
                bgFillPaint.color = 0xFF242320.toInt()
                canvas.drawRoundRect(rowRect, dp(6f), dp(6f), bgFillPaint)
                strokePaint.color = 0xFF38352F.toInt()
                canvas.drawRoundRect(rowRect, dp(6f), dp(6f), strokePaint)

                leftTextPaint.textSize = dp(9.2f)
                leftTextPaint.color = 0xFFF4F1EA.toInt()
                canvas.drawText(file.name, left + dp(10f), rowRect.centerY() + dp(3.2f), leftTextPaint)

                val loadBadge = RectF(rowRect.right - dp(74f), y + dp(4f), rowRect.right - dp(6f), y + dp(28f))
                bgFillPaint.color = 0xFF4E8C57.toInt()
                canvas.drawRoundRect(loadBadge, dp(5f), dp(5f), bgFillPaint)
                textPaint.textSize = dp(8.2f)
                canvas.drawText("MUAT JSON", loadBadge.centerX(), loadBadge.centerY() + dp(2.8f), textPaint)

                registerSidebarHit(rowRect, clipTop, clipBottom) {
                    val (ok, _) = ControlProfileManager.importLayoutFromFile(context, file)
                    if (ok) reloadLayoutFromDisk() else postInvalidateOnAnimation()
                }
                y += dp(36f)
            }
        }

        return y
    }

    private fun drawSidebarModrinthSection(
        canvas: Canvas,
        left: Float,
        startY: Float,
        width: Float,
        clipTop: Float,
        clipBottom: Float
    ): Float {
        var y = startY

        // 1. 4-Category Switcher Bar (MODS, SHADERS, TEKSTUR/RP, MAP & DUNIA)
        val cats = ModrinthSyncManager.ContentCategory.values()
        val catGap = dp(4f)
        val catBtnW = (width - catGap * 3f) / 4f
        val catBtnH = dp(28f)
        val activeCat = ModrinthSyncManager.activeCategory
        for ((idx, cat) in cats.withIndex()) {
            val cl = left + idx * (catBtnW + catGap)
            val cRect = RectF(cl, y, cl + catBtnW, y + catBtnH)
            val isSel = (cat == activeCat)
            bgFillPaint.color = if (isSel) 0xFF4E8C57.toInt() else 0xFF242320.toInt()
            canvas.drawRoundRect(cRect, dp(6f), dp(6f), bgFillPaint)
            strokePaint.color = if (isSel) 0xFF8CD996.toInt() else 0xFF3E3B35.toInt()
            canvas.drawRoundRect(cRect, dp(6f), dp(6f), strokePaint)
            textPaint.textSize = dp(7.6f)
            textPaint.color = 0xFFF4F1EA.toInt()
            canvas.drawText(cat.shortTab, cRect.centerX(), cRect.centerY() + dp(2.8f), textPaint)
            registerSidebarHit(cRect, clipTop, clipBottom) {
                ModrinthSyncManager.setCategory(context, cat) {
                    sidebarScrollY = 0f
                    postInvalidateOnAnimation()
                }
            }
        }
        y += catBtnH + dp(7f)

        // 2. Search Bar (uses Android System IME Keyboard!) + [CARI] + [+ MUAT 30 LAGI]
        val searchBoxW = width - dp(138f)
        val searchRect = RectF(left, y, left + searchBoxW, y + dp(31f))
        bgFillPaint.color = 0xFF242320.toInt()
        canvas.drawRoundRect(searchRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFF4E8C57.toInt()
        canvas.drawRoundRect(searchRect, dp(7f), dp(7f), strokePaint)

        val qText = if (ModrinthSyncManager.currentSearchQuery.isBlank()) {
            "Cari ${activeCat.shortTab} (Ketuk IME)..."
        } else {
            "Cari: ${ModrinthSyncManager.currentSearchQuery}"
        }
        leftTextPaint.textSize = dp(8.6f)
        leftTextPaint.color = if (ModrinthSyncManager.currentSearchQuery.isBlank()) 0xFF9C978C.toInt() else 0xFFF4F1EA.toInt()
        val qClip = TextUtils.ellipsize(qText, leftTextPaint, searchBoxW - dp(14f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(qClip, left + dp(8f), searchRect.centerY() + dp(3.0f), leftTextPaint)

        registerSidebarHit(searchRect, clipTop, clipBottom) {
            SystemImeHelper.requestTextInput(
                label = "Cari ${activeCat.fullTitle}",
                initialText = ModrinthSyncManager.currentSearchQuery,
                maxLength = 40,
                onChanged = { q ->
                    ModrinthSyncManager.currentSearchQuery = q
                    postInvalidateOnAnimation()
                },
                onCommitted = { q ->
                    ModrinthSyncManager.searchModsOnModrinth(context, q) {
                        postInvalidateOnAnimation()
                    }
                }
            )
        }

        val searchBtnRect = RectF(searchRect.right + dp(5f), y, searchRect.right + dp(56f), y + dp(31f))
        bgFillPaint.color = 0xFF4E8C57.toInt()
        canvas.drawRoundRect(searchBtnRect, dp(7f), dp(7f), bgFillPaint)
        textPaint.textSize = dp(8.0f)
        textPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText("CARI", searchBtnRect.centerX(), searchBtnRect.centerY() + dp(2.9f), textPaint)
        registerSidebarHit(searchBtnRect, clipTop, clipBottom) {
            ModrinthSyncManager.searchModsOnModrinth(context, ModrinthSyncManager.currentSearchQuery) {
                postInvalidateOnAnimation()
            }
        }

        val loadMoreBtnRect = RectF(searchBtnRect.right + dp(5f), y, left + width, y + dp(31f))
        bgFillPaint.color = 0xFF5A7D9A.toInt()
        canvas.drawRoundRect(loadMoreBtnRect, dp(7f), dp(7f), bgFillPaint)
        textPaint.textSize = dp(7.6f)
        canvas.drawText("+MUAT SEMUA", loadMoreBtnRect.centerX(), loadMoreBtnRect.centerY() + dp(2.8f), textPaint)
        registerSidebarHit(loadMoreBtnRect, clipTop, clipBottom) {
            ModrinthSyncManager.loadMoreFromModrinth(context) {
                postInvalidateOnAnimation()
            }
        }
        y += dp(35f)

        // Status line
        subTextPaint.textSize = dp(8.2f)
        val stLine = TextUtils.ellipsize(ModrinthSyncManager.syncStatusMessage, subTextPaint, width, TextUtils.TruncateAt.END).toString()
        canvas.drawText(stLine, left, y + dp(10f), subTextPaint)
        y += dp(15f)

        val mods = ModrinthSyncManager.getCatalogWithDiskState(context)
        for (mod in mods) {
            val cardH = dp(44f)
            val cardRect = RectF(left, y, left + width, y + cardH)
            bgFillPaint.color = 0xFF22211E.toInt()
            canvas.drawRoundRect(cardRect, dp(7f), dp(7f), bgFillPaint)
            strokePaint.color = if (mod.isInstalled && mod.isEnabled) 0xFF4E8C57.toInt() else 0xFF36342F.toInt()
            canvas.drawRoundRect(cardRect, dp(7f), dp(7f), strokePaint)

            leftTextPaint.textSize = dp(9.2f)
            leftTextPaint.color = 0xFFF4F1EA.toInt()
            val titleClip = TextUtils.ellipsize("${mod.title}  [${mod.categoryTag}]", leftTextPaint, width - dp(92f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(titleClip, left + dp(8f), y + dp(16f), leftTextPaint)

            subTextPaint.textSize = dp(8.0f)
            val descClip = TextUtils.ellipsize("${mod.downloadsText} • ${mod.summary}", subTextPaint, width - dp(92f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(descClip, left + dp(8f), y + dp(32f), subTextPaint)

            val actionRect = RectF(cardRect.right - dp(78f), y + dp(8f), cardRect.right - dp(6f), y + cardH - dp(8f))
            val isDownloading = ModrinthSyncManager.activeDownloadSlug == mod.slug
            bgFillPaint.color = when {
                isDownloading -> 0xFFB89248.toInt()
                mod.isInstalled && mod.isEnabled -> 0xFF4E8C57.toInt()
                mod.isInstalled -> 0xFF78736A.toInt()
                else -> 0xFF5A7D9A.toInt()
            }
            canvas.drawRoundRect(actionRect, dp(6f), dp(6f), bgFillPaint)
            textPaint.textSize = dp(7.8f)
            val btnTxt = when {
                isDownloading -> "UNDUH..."
                mod.isInstalled && mod.isEnabled -> "AKTIF [ON]"
                mod.isInstalled -> "MATI [OFF]"
                else -> "+ PASANG"
            }
            canvas.drawText(btnTxt, actionRect.centerX(), actionRect.centerY() + dp(2.8f), textPaint)

            registerSidebarHit(cardRect, clipTop, clipBottom) {
                ModrinthSyncManager.installOrToggleMod(context, mod) {
                    postInvalidateOnAnimation()
                }
            }
            y += cardH + dp(6f)
        }

        // Bottom Button: Load More (+30 Items) from Modrinth Server
        val moreFooterRect = RectF(left, y + dp(2f), left + width, y + dp(34f))
        bgFillPaint.color = 0xFF28382B.toInt()
        canvas.drawRoundRect(moreFooterRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFF5FA869.toInt()
        canvas.drawRoundRect(moreFooterRect, dp(7f), dp(7f), strokePaint)
        textPaint.textSize = dp(8.5f)
        textPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText("+ MUAT LEBIH BANYAK ${activeCat.fullTitle} DARI SERVER (+30 ITEM)", moreFooterRect.centerX(), moreFooterRect.centerY() + dp(3.0f), textPaint)
        registerSidebarHit(moreFooterRect, clipTop, clipBottom) {
            ModrinthSyncManager.loadMoreFromModrinth(context) {
                postInvalidateOnAnimation()
            }
        }
        y += dp(40f)

        return y
    }

    private fun drawSidebarImeAndShortcutsSection(
        canvas: Canvas,
        left: Float,
        startY: Float,
        width: Float,
        clipTop: Float,
        clipBottom: Float
    ): Float {
        var y = startY
        val halfW = (width - dp(8f)) * 0.5f

        // System IME Keyboard Trigger Buttons
        val imeChatRect = RectF(left, y, left + width, y + dp(36f))
        bgFillPaint.color = 0xFF4E8C57.toInt()
        canvas.drawRoundRect(imeChatRect, dp(7f), dp(7f), bgFillPaint)
        textPaint.textSize = dp(9.4f)
        canvas.drawText("BUKA KEYBOARD IME SISTEM (CHAT & COMMAND)", imeChatRect.centerX(), imeChatRect.centerY() + dp(3.3f), textPaint)
        registerSidebarHit(imeChatRect, clipTop, clipBottom) {
            isSidebarDrawerOpen = false
            postInvalidateOnAnimation()
            SystemImeHelper.openInGameDirectKeyboard(openChatFirst = true)
        }
        y += dp(42f)

        val imeDirectRect = RectF(left, y, left + width, y + dp(34f))
        bgFillPaint.color = 0xFF5A7D9A.toInt()
        canvas.drawRoundRect(imeDirectRect, dp(7f), dp(7f), bgFillPaint)
        textPaint.textSize = dp(9.0f)
        canvas.drawText("KETIK LANGSUNG IME (SEARCH / SIGN / ANVIL)", imeDirectRect.centerX(), imeDirectRect.centerY() + dp(3.2f), textPaint)
        registerSidebarHit(imeDirectRect, clipTop, clipBottom) {
            isSidebarDrawerOpen = false
            postInvalidateOnAnimation()
            SystemImeHelper.openInGameDirectKeyboard(openChatFirst = false)
        }
        y += dp(42f)

        // Active Account Summary Card
        val acc = AccountManager.getActiveAccount(context)
        val accRect = RectF(left, y, left + width, y + dp(46f))
        bgFillPaint.color = 0xFF22211E.toInt()
        canvas.drawRoundRect(accRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFF3A3732.toInt()
        canvas.drawRoundRect(accRect, dp(7f), dp(7f), strokePaint)
        leftTextPaint.textSize = dp(9.4f)
        leftTextPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText("Akun Aktif: ${acc.username} (${acc.type.label})", left + dp(10f), y + dp(18f), leftTextPaint)
        subTextPaint.textSize = dp(8.4f)
        canvas.drawText("UUID: ${acc.uuid}", left + dp(10f), y + dp(34f), subTextPaint)
        y += dp(54f)

        // Quick In-Game Key Shortcuts
        leftTextPaint.textSize = dp(9.4f)
        leftTextPaint.color = 0xFFB8B2A6.toInt()
        canvas.drawText("Pintasan Cepat In-Game:", left, y + dp(11f), leftTextPaint)
        y += dp(16f)

        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            "PAUSE / MOD MENU (ESC)", 0xFF282623.toInt(),
            {
                isSidebarDrawerOpen = false
                tapKey(NativeEngineBridge.KEY_ESCAPE)
            },
            "DEBUG OVERLAY (F3)", 0xFF282623.toInt(),
            {
                isSidebarDrawerOpen = false
                tapKey(NativeEngineBridge.KEY_F3)
            },
            clipTop, clipBottom
        )
        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            "PERSPEKTIF (F5)", 0xFF282623.toInt(),
            {
                isSidebarDrawerOpen = false
                tapKey(NativeEngineBridge.KEY_F5)
            },
            "HITBOX ENTITAS (F3+B)", 0xFF282623.toInt(),
            {
                isSidebarDrawerOpen = false
                macroEngine.executeF3Shortcut(66)
            },
            clipTop, clipBottom
        )
        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            "BATAS CHUNK (F3+G)", 0xFF282623.toInt(),
            {
                isSidebarDrawerOpen = false
                macroEngine.executeF3Shortcut(71)
            },
            "RELOAD CHUNK (F3+A)", 0xFF282623.toInt(),
            {
                isSidebarDrawerOpen = false
                macroEngine.executeF3Shortcut(65)
            },
            clipTop, clipBottom
        )

        return y
    }

    private fun drawSidebarTelemetrySection(
        canvas: Canvas,
        left: Float,
        startY: Float,
        width: Float,
        clipTop: Float,
        clipBottom: Float
    ): Float {
        var y = startY
        val halfW = (width - dp(8f)) * 0.5f

        // 1. Prominent Button to Reopen Boot Log & Hadits Overlay Anytime!
        val openLogBtnRect = RectF(left, y, left + width, y + dp(36f))
        bgFillPaint.color = 0xFF4E8C57.toInt()
        canvas.drawRoundRect(openLogBtnRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFF8CD996.toInt()
        canvas.drawRoundRect(openLogBtnRect, dp(7f), dp(7f), strokePaint)
        textPaint.textSize = dp(9.2f)
        textPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText("BUKA KONSOL LOG BOOT, ANIMASI & HADITS", openLogBtnRect.centerX(), openLogBtnRect.centerY() + dp(3.2f), textPaint)
        registerSidebarHit(openLogBtnRect, clipTop, clipBottom) {
            isSidebarDrawerOpen = false
            onReopenBootLogRequested?.invoke()
            postInvalidateOnAnimation()
        }
        y += dp(42f)

        // 2. One-Tap Instant RAM, GC & Cache Boost Button
        val boostNowRect = RectF(left, y, left + width, y + dp(36f))
        bgFillPaint.color = 0xFFC87D55.toInt()
        canvas.drawRoundRect(boostNowRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFFE6B86A.toInt()
        canvas.drawRoundRect(boostNowRect, dp(7f), dp(7f), strokePaint)
        textPaint.textSize = dp(9.2f)
        canvas.drawText("JALANKAN INSTANT RAM, GC & CACHE BOOST SEKARANG", boostNowRect.centerX(), boostNowRect.centerY() + dp(3.2f), textPaint)
        registerSidebarHit(boostNowRect, clipTop, clipBottom) {
            val res = PerformanceBooster.runInstantBoost(context)
            showHudToast(res.summaryMessage)
            postInvalidateOnAnimation()
        }
        y += dp(42f)

        // 3. Boost Status & Real-Time Native C++ Turbo Telemetry Card
        val infoCardH = dp(104f)
        val cardRect = RectF(left, y, left + width, y + infoCardH)
        bgFillPaint.color = 0xFF22211E.toInt()
        canvas.drawRoundRect(cardRect, dp(8f), dp(8f), bgFillPaint)
        strokePaint.color = 0xFF3A3732.toInt()
        canvas.drawRoundRect(cardRect, dp(8f), dp(8f), strokePaint)

        leftTextPaint.textSize = dp(9.2f)
        leftTextPaint.color = 0xFF8CD996.toInt()
        val activeBoostProf = PerformanceBooster.getActiveProfile(context)
        val resScale = PerformanceBooster.getResolutionScale(context)
        canvas.drawText("PROFIL AKTIF: ${activeBoostProf.title}  |  SKALA C++: ${(resScale * 100f).roundToInt()}%", left + dp(10f), y + dp(16f), leftTextPaint)

        subTextPaint.textSize = dp(8.4f)
        canvas.drawText("FPS: $currentFps  |  1% Low: $cachedOnePercentLow  |  Frame Time: ${String.format("%.1f", cachedFrameTimeMs)} ms", left + dp(10f), y + dp(32f), subTextPaint)
        canvas.drawText("JVM Heap: $cachedJvmUsedMb / $cachedJvmMaxMb MB  |  RAM Perangkat: $cachedSysRamPct%", left + dp(10f), y + dp(47f), subTextPaint)
        canvas.drawText("Mod Aktif: Sodium 0.5.8 + ImmediatelyFast + Lithium + Ferrite + EntityCulling", left + dp(10f), y + dp(62f), subTextPaint)
        canvas.drawText("Mesin Native: libvelocraft_turbo.so (MultiDraw Fix + GL4ES FastMath + VBO)", left + dp(10f), y + dp(77f), subTextPaint)
        val boostSum = TextUtils.ellipsize(PerformanceBooster.getLastBoostSummary(context), subTextPaint, width - dp(20f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(boostSum, left + dp(10f), y + dp(93f), subTextPaint)
        y += infoCardH + dp(8f)

        // 3B. Interactive Draggable Slider for C++ Hardware Resolution Scaling (50% - 100%)
        y = drawSidebarSliderCard(
            canvas = canvas,
            left = left,
            y = y,
            width = width,
            title = "SKALA RESOLUSI HARDWARE (C++ ANATIVEWINDOW)",
            valueBadge = "${(resScale * 100f).roundToInt()}%",
            subtitle = "Turunkan ke 65% di Redmi A2 untuk naikkan FPS dari 8 FPS ke 25-35+ FPS",
            currentVal = resScale,
            minVal = 0.50f,
            maxVal = 1.00f,
            accentColor = 0xFFE6B86A.toInt(),
            onStepMinus = {
                PerformanceBooster.setResolutionScale(context, resScale - 0.05f)
                postInvalidateOnAnimation()
            },
            onStepPlus = {
                PerformanceBooster.setResolutionScale(context, resScale + 0.05f)
                postInvalidateOnAnimation()
            },
            onSlideValue = { newScale ->
                PerformanceBooster.setResolutionScale(context, newScale)
                postInvalidateOnAnimation()
            },
            clipTop = clipTop,
            clipBottom = clipBottom
        )

        // 4. 4 Performance Boost Profile Presets
        leftTextPaint.textSize = dp(9.2f)
        leftTextPaint.color = 0xFFB8B2A6.toInt()
        canvas.drawText("Pilih Preset Boost Performa (Otomatis Atur options.txt & Resolusi):", left, y + dp(11f), leftTextPaint)
        y += dp(16f)

        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            "TURBO FPS (4 CHUNK)",
            if (activeBoostProf == PerformanceBooster.BoostProfile.TURBO_FPS) 0xFF4E8C57.toInt() else 0xFF282623.toInt(),
            {
                val msg = PerformanceBooster.applyBoostProfile(context, PerformanceBooster.BoostProfile.TURBO_FPS)
                showHudToast(msg)
                postInvalidateOnAnimation()
            },
            "PVP 120FPS (6 CHUNK)",
            if (activeBoostProf == PerformanceBooster.BoostProfile.PVP_COMPETITIVE) 0xFF4E8C57.toInt() else 0xFF282623.toInt(),
            {
                val msg = PerformanceBooster.applyBoostProfile(context, PerformanceBooster.BoostProfile.PVP_COMPETITIVE)
                showHudToast(msg)
                postInvalidateOnAnimation()
            },
            clipTop, clipBottom
        )

        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            "SEIMBANG (8 CHUNK)",
            if (activeBoostProf == PerformanceBooster.BoostProfile.BALANCED_STUDIO) 0xFF4E8C57.toInt() else 0xFF282623.toInt(),
            {
                val msg = PerformanceBooster.applyBoostProfile(context, PerformanceBooster.BoostProfile.BALANCED_STUDIO)
                showHudToast(msg)
                postInvalidateOnAnimation()
            },
            "VISUAL (12 CHUNK)",
            if (activeBoostProf == PerformanceBooster.BoostProfile.HIGH_VISUAL) 0xFF4E8C57.toInt() else 0xFF282623.toInt(),
            {
                val msg = PerformanceBooster.applyBoostProfile(context, PerformanceBooster.BoostProfile.HIGH_VISUAL)
                showHudToast(msg)
                postInvalidateOnAnimation()
            },
            clipTop, clipBottom
        )

        // 5. Granular Boost Toggles
        val curChunks = PerformanceBooster.getRenderDistanceChunks(context)
        val noPartClouds = PerformanceBooster.isNoParticlesAndCloudsEnabled(context)
        val noShadows = PerformanceBooster.isNoEntityShadowsEnabled(context)

        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            "JARAK RENDER: $curChunks CHUNK [GANTI]", 0xFF3A5268.toInt(),
            {
                val nextC = PerformanceBooster.cycleRenderDistance(context)
                showHudToast("Jarak Pandang diatur ke $nextC Chunk.")
                postInvalidateOnAnimation()
            },
            if (noPartClouds) "AWAN & PARTIKEL: MINIMAL" else "AWAN & PARTIKEL: NORMAL",
            if (noPartClouds) 0xFF4E8C57.toInt() else 0xFF282623.toInt(),
            {
                val nowMin = PerformanceBooster.toggleNoParticlesAndClouds(context)
                showHudToast(if (nowMin) "Boost Aktif: Awan OFF & Partikel Minimal." else "Awan & Partikel dikembalikan Normal.")
                postInvalidateOnAnimation()
            },
            clipTop, clipBottom
        )

        y = drawSidebarDualButtons(
            canvas, left, y, halfW,
            if (noShadows) "BAYANGAN ENTITAS: OFF" else "BAYANGAN ENTITAS: ON",
            if (noShadows) 0xFF4E8C57.toInt() else 0xFF282623.toInt(),
            {
                val nowOff = PerformanceBooster.toggleNoEntityShadows(context)
                showHudToast(if (nowOff) "Boost Aktif: Bayangan Entitas dimatikan." else "Bayangan Entitas diaktifkan.")
                postInvalidateOnAnimation()
            },
            "STUDIO PENGATURAN LENGKAP", 0xFF5A7D9A.toInt(),
            {
                isSidebarDrawerOpen = false
                onQuickSettingsRequested?.invoke()
            },
            clipTop, clipBottom
        )

        return y
    }

    private fun tapKey(keyCode: Int) {
        NativeEngineBridge.sendKey(keyCode, NativeEngineBridge.GLFW_PRESS, 0)
        postDelayed({
            NativeEngineBridge.sendKey(keyCode, NativeEngineBridge.GLFW_RELEASE, 0)
        }, 35L)
    }

    private fun drawSidebarDualButtons(
        canvas: Canvas,
        left: Float,
        y: Float,
        halfW: Float,
        label1: String,
        color1: Int,
        action1: () -> Unit,
        label2: String,
        color2: Int,
        action2: () -> Unit,
        clipTop: Float,
        clipBottom: Float
    ): Float {
        val h = dp(31f)
        val r1 = RectF(left, y, left + halfW, y + h)
        val r2 = RectF(left + halfW + dp(8f), y, left + halfW * 2f + dp(8f), y + h)

        for ((r, col, lbl) in listOf(Triple(r1, color1, label1), Triple(r2, color2, label2))) {
            bgFillPaint.shader = android.graphics.LinearGradient(
                r.left, r.top, r.left, r.bottom,
                col, 0xFF171A18.toInt(), android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(r, dp(6.5f), dp(6.5f), bgFillPaint)
            bgFillPaint.shader = null

            strokePaint.color = 0x2AFFFFFF
            canvas.drawLine(r.left + dp(6f), r.top + dp(1.2f), r.right - dp(6f), r.top + dp(1.2f), strokePaint)
            strokePaint.color = 0xFF424A44.toInt()
            canvas.drawRoundRect(r, dp(6.5f), dp(6.5f), strokePaint)

            textPaint.textSize = dp(8.4f)
            textPaint.color = 0xFFF4F1EA.toInt()
            canvas.drawText(lbl, r.centerX(), r.centerY() + dp(3.0f), textPaint)
        }

        registerSidebarHit(r1, clipTop, clipBottom, action1)
        registerSidebarHit(r2, clipTop, clipBottom, action2)
        return y + h + dp(6f)
    }

    private fun drawSidebarSliderCard(
        canvas: Canvas,
        left: Float,
        y: Float,
        width: Float,
        title: String,
        valueBadge: String,
        subtitle: String,
        currentVal: Float,
        minVal: Float,
        maxVal: Float,
        accentColor: Int,
        onStepMinus: () -> Unit,
        onStepPlus: () -> Unit,
        onSlideValue: (Float) -> Unit,
        clipTop: Float,
        clipBottom: Float
    ): Float {
        val h = dp(58f)
        val cardRect = RectF(left, y, left + width, y + h)
        bgFillPaint.shader = android.graphics.LinearGradient(
            cardRect.left, cardRect.top, cardRect.left, cardRect.bottom,
            0xFF232824.toInt(), 0xFF171A18.toInt(), android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(cardRect, dp(8f), dp(8f), bgFillPaint)
        bgFillPaint.shader = null

        strokePaint.color = 0x26FFFFFF
        canvas.drawLine(cardRect.left + dp(8f), cardRect.top + dp(1.2f), cardRect.right - dp(8f), cardRect.top + dp(1.2f), strokePaint)
        strokePaint.color = 0xFF3A443D.toInt()
        strokePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(cardRect, dp(8f), dp(8f), strokePaint)

        // Title on top-left
        leftTextPaint.textSize = dp(9.0f)
        leftTextPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText(title, left + dp(9f), y + dp(15f), leftTextPaint)

        // Value Pill Badge on top-right
        val badgeW = dp(54f)
        val badgeRect = RectF(cardRect.right - dp(8f) - badgeW, y + dp(4f), cardRect.right - dp(8f), y + dp(20f))
        bgFillPaint.color = accentColor
        canvas.drawRoundRect(badgeRect, dp(5f), dp(5f), bgFillPaint)
        textPaint.textSize = dp(8.4f)
        textPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText(valueBadge, badgeRect.centerX(), badgeRect.centerY() + dp(2.8f), textPaint)

        // Subtitle helper line
        subTextPaint.textSize = dp(7.6f)
        subTextPaint.color = 0xFFB8B2A6.toInt()
        canvas.drawText(subtitle, left + dp(9f), y + dp(26f), subTextPaint)

        // Bottom row: [-] Button + Draggable Slider Bar + [+] Button
        val stepBtnW = dp(34f)
        val rowTop = y + dp(31f)
        val rowBottom = y + h - dp(5f)
        val minusRect = RectF(left + dp(8f), rowTop, left + dp(8f) + stepBtnW, rowBottom)
        val plusRect = RectF(cardRect.right - dp(8f) - stepBtnW, rowTop, cardRect.right - dp(8f), rowBottom)

        bgFillPaint.color = 0xFF2E2C28.toInt()
        canvas.drawRoundRect(minusRect, dp(5.5f), dp(5.5f), bgFillPaint)
        strokePaint.color = 0xFF4A4740.toInt()
        canvas.drawRoundRect(minusRect, dp(5.5f), dp(5.5f), strokePaint)

        bgFillPaint.color = accentColor
        canvas.drawRoundRect(plusRect, dp(5.5f), dp(5.5f), bgFillPaint)

        textPaint.textSize = dp(10.0f)
        textPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText("-", minusRect.centerX(), minusRect.centerY() + dp(3.4f), textPaint)
        canvas.drawText("+", plusRect.centerX(), plusRect.centerY() + dp(3.4f), textPaint)

        // Interactive Slider Track in between [-] and [+]
        val trackLeft = minusRect.right + dp(10f)
        val trackRight = plusRect.left - dp(10f)
        val trackCy = (rowTop + rowBottom) * 0.5f
        val trackH = dp(7f)
        val trackVisualRect = RectF(trackLeft, trackCy - trackH * 0.5f, trackRight, trackCy + trackH * 0.5f)
        bgFillPaint.color = 0xFF141311.toInt()
        canvas.drawRoundRect(trackVisualRect, dp(3.5f), dp(3.5f), bgFillPaint)
        strokePaint.color = 0xFF3A3732.toInt()
        canvas.drawRoundRect(trackVisualRect, dp(3.5f), dp(3.5f), strokePaint)

        val ratio = ((currentVal - minVal) / (maxVal - minVal).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        val fillRight = trackLeft + (trackRight - trackLeft) * ratio
        val fillRect = RectF(trackLeft, trackCy - trackH * 0.5f, fillRight.coerceAtLeast(trackLeft + dp(4f)), trackCy + trackH * 0.5f)
        bgFillPaint.color = accentColor
        canvas.drawRoundRect(fillRect, dp(3.5f), dp(3.5f), bgFillPaint)

        // Slider Thumb Knob
        val thumbR = dp(7.5f)
        bgFillPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawCircle(fillRight, trackCy, thumbR, bgFillPaint)
        strokePaint.color = accentColor
        strokePaint.strokeWidth = dp(2.0f)
        canvas.drawCircle(fillRight, trackCy, thumbR, strokePaint)
        strokePaint.strokeWidth = dp(1.2f)

        // Register touch hits
        registerSidebarHit(minusRect, clipTop, clipBottom, onStepMinus)
        registerSidebarHit(plusRect, clipTop, clipBottom, onStepPlus)
        val touchHitRect = RectF(trackLeft - dp(6f), rowTop - dp(3f), trackRight + dp(6f), rowBottom + dp(3f))
        if (touchHitRect.bottom >= clipTop && touchHitRect.top <= clipBottom) {
            sidebarSliderHits.add(SidebarSliderHit(touchHitRect, minVal, maxVal, onSlideValue))
        }

        return y + h + dp(7f)
    }

    private fun drawSidebarStepperCard(
        canvas: Canvas,
        left: Float,
        y: Float,
        width: Float,
        title: String,
        minusLabel: String,
        onMinus: () -> Unit,
        plusLabel: String,
        onPlus: () -> Unit,
        clipTop: Float,
        clipBottom: Float
    ): Float {
        val h = dp(34f)
        val cardRect = RectF(left, y, left + width, y + h)
        bgFillPaint.color = 0xFF22211E.toInt()
        canvas.drawRoundRect(cardRect, dp(7f), dp(7f), bgFillPaint)
        strokePaint.color = 0xFF36342F.toInt()
        canvas.drawRoundRect(cardRect, dp(7f), dp(7f), strokePaint)

        leftTextPaint.textSize = dp(8.8f)
        leftTextPaint.color = 0xFFD6D2C8.toInt()
        canvas.drawText(title, left + dp(8f), cardRect.centerY() + dp(3.0f), leftTextPaint)

        val btnW = dp(62f)
        val plusRect = RectF(cardRect.right - dp(5f) - btnW, y + dp(4f), cardRect.right - dp(5f), y + h - dp(4f))
        val minusRect = RectF(plusRect.left - dp(5f) - btnW, y + dp(4f), plusRect.left - dp(5f), y + h - dp(4f))

        bgFillPaint.color = 0xFF2E2C28.toInt()
        canvas.drawRoundRect(minusRect, dp(5f), dp(5f), bgFillPaint)
        bgFillPaint.color = 0xFF4E8C57.toInt()
        canvas.drawRoundRect(plusRect, dp(5f), dp(5f), bgFillPaint)

        textPaint.textSize = dp(8.0f)
        textPaint.color = 0xFFF4F1EA.toInt()
        canvas.drawText(minusLabel, minusRect.centerX(), minusRect.centerY() + dp(2.8f), textPaint)
        canvas.drawText(plusLabel, plusRect.centerX(), plusRect.centerY() + dp(2.8f), textPaint)

        registerSidebarHit(minusRect, clipTop, clipBottom, onMinus)
        registerSidebarHit(plusRect, clipTop, clipBottom, onPlus)
        return y + h + dp(6f)
    }

    private fun registerSidebarHit(rect: RectF, clipTop: Float, clipBottom: Float, action: () -> Unit) {
        if (rect.bottom >= clipTop && rect.top <= clipBottom) {
            sidebarActionHits.add(SidebarActionHit(RectF(rect), action))
        }
    }

    private fun drawVirtualMouseCursor(canvas: Canvas, x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val clickAge = now - lastCursorClickMs
        val isClicking = isHoldingLeftAttack || isHoldingRightPlace || isCursorClickAnimating || clickAge < 220L

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
                val ringR = dp(9.5f)
                val gap = dp(3.8f)
                val arm = dp(14.5f)

                cursorStrokePaint.strokeWidth = dp(3.0f)
                canvas.drawCircle(x, y, ringR, cursorStrokePaint)
                canvas.drawLine(x - arm, y, x - gap, y, cursorStrokePaint)
                canvas.drawLine(x + gap, y, x + arm, y, cursorStrokePaint)
                canvas.drawLine(x, y - arm, x, y - gap, cursorStrokePaint)
                canvas.drawLine(x, y + gap, x, y + arm, cursorStrokePaint)

                strokePaint.color = 0xFFF4F1EA.toInt()
                strokePaint.strokeWidth = dp(1.5f)
                canvas.drawCircle(x, y, ringR, strokePaint)
                canvas.drawLine(x - arm, y, x - gap, y, strokePaint)
                canvas.drawLine(x + gap, y, x + arm, y, strokePaint)
                canvas.drawLine(x, y - arm, x, y - gap, strokePaint)
                canvas.drawLine(x, y + gap, x, y + arm, strokePaint)

                cursorAccentPaint.color = coreColor
                canvas.drawCircle(x, y, dp(2.4f), cursorAccentPaint)
            }

            2 -> {
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

                canvas.drawPath(cursorPath, cursorFillPaint)

                cursorInnerPath.reset()
                cursorInnerPath.moveTo(x + s * 0.11f, y + s * 0.22f)
                cursorInnerPath.lineTo(x + s * 0.11f, y + s * 0.68f)
                cursorInnerPath.lineTo(x + s * 0.30f, y + s * 0.54f)
                cursorInnerPath.lineTo(x + s * 0.52f, y + s * 0.52f)
                cursorInnerPath.close()
                cursorAccentPaint.color = coreColor
                canvas.drawPath(cursorInnerPath, cursorAccentPaint)

                cursorStrokePaint.strokeWidth = dp(2.1f)
                canvas.drawPath(cursorPath, cursorStrokePaint)
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
        val barW = dp(610f).coerceAtMost(w * 0.96f)
        val barH = dp(122f)
        val left = (w - barW) * 0.5f
        val top = dp(38f)
        val barRect = RectF(left, top, left + barW, top + barH)
        canvas.drawRoundRect(barRect, dp(10f), dp(10f), hudBannerPaint)
        strokePaint.color = 0xFFC87D55.toInt()
        strokePaint.strokeWidth = dp(1.4f)
        canvas.drawRoundRect(barRect, dp(10f), dp(10f), strokePaint)

        val sel = selectedEditButton
        val infoText = if (sel != null) {
            val pctX = (sel.normX * 100).roundToInt()
            val pctY = (sel.normY * 100).roundToInt()
            val opPct = (sel.opacity * 100).roundToInt()
            "Terpilih: [${sel.label}]  |  Pos: $pctX%, $pctY%  |  Ukuran: ${sel.radiusDp.toInt()}dp  |  Opasitas: $opPct%  |  Bentuk: ${sel.shape.displayName}"
        } else {
            "Ketuk tombol mana saja di layar untuk memilih, geser posisi, atau atur Opasitas di bawah"
        }
        textPaint.textSize = dp(9.0f)
        textPaint.color = 0xFFD6D2C8.toInt()
        canvas.drawText(infoText, barRect.centerX(), top + dp(14f), textPaint)
        textPaint.color = 0xFFF4F1EA.toInt()

        val gap = dp(4.5f)
        val cols = 5
        val itemW = (barW - gap * (cols + 1)) / cols
        val rowH = dp(26f)

        val row1Top = top + dp(20f)
        val row2Top = row1Top + rowH + dp(4.5f)
        val row3Top = row2Top + rowH + dp(5.5f)

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
            textPaint.textSize = dp(8.8f)
            canvas.drawText(item.second, item.first.centerX(), item.first.centerY() + dp(3.1f), textPaint)
        }

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
            textPaint.textSize = dp(8.6f)
            canvas.drawText(lbl, rect.centerX(), rect.centerY() + dp(3.1f), textPaint)
        }

        // Row 3: Interactive Draggable Opacity Slider + Size Slider + [SAMAKAN OPASITAS SEMUA]
        val sliderW = (barW - gap * 4f) * 0.36f
        val applyAllW = barW - gap * 4f - sliderW * 2f

        editOpacitySliderRect.set(left + gap, row3Top, left + gap + sliderW, row3Top + rowH + dp(2f))
        editSizeSliderRect.set(editOpacitySliderRect.right + gap, row3Top, editOpacitySliderRect.right + gap + sliderW, row3Top + rowH + dp(2f))
        editApplyAllOpacityRect.set(editSizeSliderRect.right + gap, row3Top, editSizeSliderRect.right + gap + applyAllW, row3Top + rowH + dp(2f))

        // Draw Opacity Slider Box
        val curOp = (sel?.opacity ?: globalHudOpacity).coerceIn(0.08f, 1.0f)
        val opRatio = ((curOp - 0.08f) / 0.92f).coerceIn(0f, 1f)
        bgFillPaint.color = 0xFF1F1E1B.toInt()
        canvas.drawRoundRect(editOpacitySliderRect, dp(6f), dp(6f), bgFillPaint)
        val opFillRect = RectF(
            editOpacitySliderRect.left,
            editOpacitySliderRect.top,
            editOpacitySliderRect.left + editOpacitySliderRect.width() * opRatio,
            editOpacitySliderRect.bottom
        )
        bgFillPaint.color = 0xAA4E8C57.toInt()
        canvas.drawRoundRect(opFillRect, dp(6f), dp(6f), bgFillPaint)
        strokePaint.color = 0xFF8CD996.toInt()
        canvas.drawRoundRect(editOpacitySliderRect, dp(6f), dp(6f), strokePaint)
        textPaint.textSize = dp(8.2f)
        canvas.drawText(
            "GESER OPASITAS: ${(curOp * 100f).roundToInt()}%",
            editOpacitySliderRect.centerX(),
            editOpacitySliderRect.centerY() + dp(3.0f),
            textPaint
        )

        // Draw Size Slider Box
        val curRad = (sel?.radiusDp ?: 24f).coerceIn(14f, 60f)
        val radRatio = ((curRad - 14f) / 46f).coerceIn(0f, 1f)
        bgFillPaint.color = 0xFF1F1E1B.toInt()
        canvas.drawRoundRect(editSizeSliderRect, dp(6f), dp(6f), bgFillPaint)
        val radFillRect = RectF(
            editSizeSliderRect.left,
            editSizeSliderRect.top,
            editSizeSliderRect.left + editSizeSliderRect.width() * radRatio,
            editSizeSliderRect.bottom
        )
        bgFillPaint.color = 0xAA5A7D9A.toInt()
        canvas.drawRoundRect(radFillRect, dp(6f), dp(6f), bgFillPaint)
        strokePaint.color = 0xFF7CA5C7.toInt()
        canvas.drawRoundRect(editSizeSliderRect, dp(6f), dp(6f), strokePaint)
        canvas.drawText(
            "GESER UKURAN: ${curRad.roundToInt()}dp",
            editSizeSliderRect.centerX(),
            editSizeSliderRect.centerY() + dp(3.0f),
            textPaint
        )

        // Draw [SAMAKAN OPASITAS SEMUA] Button
        bgFillPaint.color = 0xFFC87D55.toInt()
        canvas.drawRoundRect(editApplyAllOpacityRect, dp(6f), dp(6f), bgFillPaint)
        textPaint.textSize = dp(8.0f)
        canvas.drawText(
            "TERAPKAN OPASITAS KE SEMUA",
            editApplyAllOpacityRect.centerX(),
            editApplyAllOpacityRect.centerY() + dp(3.0f),
            textPaint
        )
    }

    private fun drawHotbarBar(canvas: Canvas, w: Float, h: Float) {
        val barWidth = (w * 0.40f).coerceIn(dp(260f), dp(420f))
        val barHeight = dp(34f)
        val left = (w - barWidth) / 2f
        val top = h - barHeight - dp(8f)
        hotbarRect.set(left, top, left + barWidth, top + barHeight)

        val hbOpacity = globalHudOpacity.coerceIn(0.12f, 1.0f)
        bgFillPaint.color = modulateAlpha(0xE6181715.toInt(), hbOpacity)
        canvas.drawRoundRect(hotbarRect, dp(8f), dp(8f), bgFillPaint)
        strokePaint.color = modulateAlpha(0x8858544C.toInt(), hbOpacity)
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
                bgFillPaint.color = modulateAlpha(0x554E8C57, (hbOpacity + 0.15f).coerceAtMost(1.0f))
                canvas.drawRoundRect(slotRect, dp(5.5f), dp(5.5f), bgFillPaint)
            }
            strokePaint.color = if (isSelected) {
                modulateAlpha(0xFF5FA869.toInt(), (hbOpacity + 0.20f).coerceAtMost(1.0f))
            } else {
                modulateAlpha(0x6678736A, hbOpacity)
            }
            strokePaint.strokeWidth = if (isSelected) dp(1.8f) else dp(1f)
            canvas.drawRoundRect(slotRect, dp(5.5f), dp(5.5f), strokePaint)

            textPaint.textSize = if (isSelected) dp(10.5f) else dp(9.5f)
            textPaint.color = if (isSelected) {
                modulateAlpha(0xFFF4F1EA.toInt(), (hbOpacity + 0.20f).coerceAtMost(1.0f))
            } else {
                modulateAlpha(0xFFB8B2A6.toInt(), (hbOpacity + 0.10f).coerceAtMost(1.0f))
            }
            canvas.drawText("${i + 1}", slotRect.centerX(), slotRect.centerY() + dp(3.6f), textPaint)
        }
        textPaint.color = 0xFFF4F1EA.toInt()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            try {
                requestUnbufferedDispatch(event)
            } catch (_: Throwable) {
            }
        }
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)

        if (isEditMode) {
            return handleEditorTouch(event, action, x, y)
        }

        if (isSidebarDrawerOpen) {
            return handleSidebarDrawerTouch(event, action, x, y)
        }

        var needsUiRedraw = true

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (topBarSidebarBtnRect.contains(x, y)) {
                    toggleSidebarDrawer()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
                if (topBarLogBtnRect.contains(x, y)) {
                    onReopenBootLogRequested?.invoke()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
                if (topBarBoostBtnRect.contains(x, y)) {
                    val res = PerformanceBooster.runInstantBoost(context)
                    showHudToast(res.summaryMessage)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
                if (topBarImeBtnRect.contains(x, y)) {
                    SystemImeHelper.openInGameDirectKeyboard(openChatFirst = true)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
                if (topBarJsonBtnRect.contains(x, y)) {
                    toggleSidebarDrawer(SidebarSection.SHARE_JSON)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }
                if (topBarMainRect.contains(x, y)) {
                    toggleSidebarDrawer(SidebarSection.TELEMETRY_LOG)
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    return true
                }

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
                    } else {
                        needsUiRedraw = false
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                needsUiRedraw = false
                val histSize = event.historySize
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    if (pid == lookPointerId) {
                        if (isCursorEffective) {
                            val curX = event.getX(i)
                            val curY = event.getY(i)
                            val rawDx = curX - lastLookX
                            val rawDy = curY - lastLookY
                            lastLookX = curX
                            lastLookY = curY
                            if (directTouchMenuClickEnabled) {
                                setVirtualCursorPosition(curX, curY)
                            } else {
                                moveVirtualCursorByDelta(
                                    rawDx * touchLookSensitivity * cursorSpeedMultiplier * 1.6f,
                                    rawDy * touchLookSensitivity * cursorSpeedMultiplier * 1.6f
                                )
                            }
                            needsUiRedraw = true
                        } else {
                            // High-Refresh 60Hz / 120Hz Unbuffered Camera Swipe Pipeline
                            var accumDx = 0f
                            var accumDy = 0f
                            for (hIdx in 0 until histSize) {
                                val hx = event.getHistoricalX(i, hIdx)
                                val hy = event.getHistoricalY(i, hIdx)
                                accumDx += (hx - lastLookX)
                                accumDy += (hy - lastLookY)
                                lastLookX = hx
                                lastLookY = hy
                            }
                            val curX = event.getX(i)
                            val curY = event.getY(i)
                            accumDx += (curX - lastLookX)
                            accumDy += (curY - lastLookY)
                            lastLookX = curX
                            lastLookY = curY

                            if (accumDx != 0f || accumDy != 0f) {
                                val stepMag = hypot(accumDx.toDouble(), accumDy.toDouble()).toFloat()
                                val velocityBoost = (1.0f + (stepMag / dp(34f)).coerceIn(0f, 0.95f))
                                val gain = touchLookSensitivity * 2.15f * velocityBoost
                                NativeEngineBridge.sendMouseDelta(
                                    accumDx * gain,
                                    accumDy * gain
                                )
                            }
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

        if (needsUiRedraw) {
            postInvalidateOnAnimation()
        }
        return true
    }

    private fun handleSidebarDrawerTouch(event: MotionEvent, action: Int, x: Float, y: Float): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (!sidebarPanelRect.contains(x, y)) {
                    isSidebarDrawerOpen = false
                    postInvalidateOnAnimation()
                    return true
                }
                sidebarTouchDownX = x
                sidebarTouchDownY = y
                sidebarTouchLastY = y
                sidebarIsDraggingScroll = false
                activeDraggingSidebarSlider = null

                for (slider in sidebarSliderHits) {
                    if (slider.rect.contains(x, y)) {
                        activeDraggingSidebarSlider = slider
                        val ratio = ((x - slider.rect.left) / slider.rect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        slider.onValue.invoke(slider.minVal + ratio * (slider.maxVal - slider.minVal))
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        postInvalidateOnAnimation()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val activeSlider = activeDraggingSidebarSlider
                if (activeSlider != null) {
                    val ratio = ((x - activeSlider.rect.left) / activeSlider.rect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    activeSlider.onValue.invoke(activeSlider.minVal + ratio * (activeSlider.maxVal - activeSlider.minVal))
                    postInvalidateOnAnimation()
                    return true
                }

                val dy = sidebarTouchLastY - y
                if (abs(y - sidebarTouchDownY) > dp(8f) && abs(y - sidebarTouchDownY) > abs(x - sidebarTouchDownX)) {
                    sidebarIsDraggingScroll = true
                }
                if (sidebarIsDraggingScroll) {
                    sidebarScrollY = (sidebarScrollY + dy).coerceIn(0f, sidebarMaxScrollY)
                    sidebarTouchLastY = y
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activeDraggingSidebarSlider != null) {
                    activeDraggingSidebarSlider = null
                    postInvalidateOnAnimation()
                    return true
                }
                if (!sidebarIsDraggingScroll) {
                    if (sidebarCloseRect.contains(x, y)) {
                        isSidebarDrawerOpen = false
                        postInvalidateOnAnimation()
                        return true
                    }
                    for ((idx, r) in sidebarNavRects.withIndex()) {
                        if (r.contains(x, y)) {
                            activeSidebarSection = SidebarSection.values()[idx]
                            sidebarScrollY = 0f
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            postInvalidateOnAnimation()
                            return true
                        }
                    }
                    for (hit in sidebarActionHits) {
                        if (hit.rect.contains(x, y)) {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            hit.action.invoke()
                            postInvalidateOnAnimation()
                            return true
                        }
                    }
                }
                sidebarIsDraggingScroll = false
            }
            MotionEvent.ACTION_CANCEL -> {
                activeDraggingSidebarSlider = null
                sidebarIsDraggingScroll = false
            }
        }
        return true
    }

    private fun handleEditorTouch(event: MotionEvent, action: Int, x: Float, y: Float): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                editorDraggingOpacitySlider = false
                editorDraggingSizeSlider = false
                when {
                    editOpacitySliderRect.contains(x, y) -> {
                        editorDraggingOpacitySlider = true
                        val ratio = ((x - editOpacitySliderRect.left) / editOpacitySliderRect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val newOp = (0.08f + ratio * 0.92f).coerceIn(0.08f, 1.0f)
                        val sel = selectedEditButton
                        if (sel != null) {
                            sel.opacity = newOp
                            ControlProfileManager.saveLayout(context, buttons)
                        } else {
                            setAllButtonsOpacity(newOp)
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editSizeSliderRect.contains(x, y) -> {
                        editorDraggingSizeSlider = true
                        val ratio = ((x - editSizeSliderRect.left) / editSizeSliderRect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val newRad = (14f + ratio * 46f).coerceIn(14f, 60f)
                        val sel = selectedEditButton
                        if (sel != null) {
                            sel.radiusDp = newRad
                            ControlProfileManager.saveLayout(context, buttons)
                        } else {
                            setAllButtonsRadiusDp(newRad)
                        }
                        postInvalidateOnAnimation()
                        return true
                    }
                    editApplyAllOpacityRect.contains(x, y) -> {
                        val targetOp = (selectedEditButton?.opacity ?: globalHudOpacity).coerceIn(0.08f, 1.0f)
                        setAllButtonsOpacity(targetOp)
                        showHudToast("Opasitas ${(targetOp * 100f).roundToInt()}% diterapkan ke seluruh tombol HUD!")
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        return true
                    }
                    editAddKeyRect.contains(x, y) -> {
                        val newBtn = HudButtonConfig(
                            id = "custom_${System.currentTimeMillis()}",
                            label = "KEY G",
                            normX = 0.50f,
                            normY = 0.54f,
                            radiusDp = 24f,
                            opacity = globalHudOpacity,
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
                            it.opacity = (it.opacity - 0.08f).coerceAtLeast(0.08f)
                            ControlProfileManager.saveLayout(context, buttons)
                        } ?: adjustAllButtonsOpacity(-0.08f)
                        postInvalidateOnAnimation()
                        return true
                    }
                    editOpacityPlusRect.contains(x, y) -> {
                        selectedEditButton?.let {
                            it.opacity = (it.opacity + 0.08f).coerceAtMost(1.0f)
                            ControlProfileManager.saveLayout(context, buttons)
                        } ?: adjustAllButtonsOpacity(+0.08f)
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
                if (editorDraggingOpacitySlider) {
                    val ratio = ((x - editOpacitySliderRect.left) / editOpacitySliderRect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val newOp = (0.08f + ratio * 0.92f).coerceIn(0.08f, 1.0f)
                    val sel = selectedEditButton
                    if (sel != null) {
                        sel.opacity = newOp
                    } else {
                        setAllButtonsOpacity(newOp)
                    }
                    postInvalidateOnAnimation()
                    return true
                }
                if (editorDraggingSizeSlider) {
                    val ratio = ((x - editSizeSliderRect.left) / editSizeSliderRect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val newRad = (14f + ratio * 46f).coerceIn(14f, 60f)
                    val sel = selectedEditButton
                    if (sel != null) {
                        sel.radiusDp = newRad
                    } else {
                        setAllButtonsRadiusDp(newRad)
                    }
                    postInvalidateOnAnimation()
                    return true
                }
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
                editorDraggingOpacitySlider = false
                editorDraggingSizeSlider = false
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
            ActionType.SIDEBAR_DRAWER_TOGGLE -> toggleSidebarDrawer()
            ActionType.IME_KEYBOARD_TOGGLE -> SystemImeHelper.openInGameDirectKeyboard(openChatFirst = true)
            ActionType.PVP_COMBO_MACRO -> {
                macroEngine.executeSprintResetCombo { postInvalidateOnAnimation() }
            }
            ActionType.MACRO_DROP_STACK -> {
                macroEngine.executeDropStack()
            }
            ActionType.MACRO_HITBOXES -> {
                macroEngine.executeF3Shortcut(66)
            }
            ActionType.MACRO_CHUNK_BORDERS -> {
                macroEngine.executeF3Shortcut(71)
            }
            ActionType.MACRO_RELOAD_CHUNKS -> {
                macroEngine.executeF3Shortcut(65)
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
                btn.id != "btn_settings" &&
                btn.actionType != ActionType.TOGGLE_HUD_CLEAN &&
                btn.actionType != ActionType.SIDEBAR_DRAWER_TOGGLE
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
