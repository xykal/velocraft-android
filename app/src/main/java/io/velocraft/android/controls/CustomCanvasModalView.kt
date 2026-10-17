package io.velocraft.android.controls

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.auth.PlayerSessionManager
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.engine.AccountManager
import io.velocraft.android.engine.JvmOptimizer
import io.velocraft.android.engine.PerformanceBooster
import io.velocraft.android.engine.RendererProfileManager
import io.velocraft.android.util.SystemImeHelper
import io.velocraft.android.util.VeloLogger
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 100% Custom Canvas Settings, JVM RAM Allocation Editor, Multi-Renderer Picker,
 * Complete Custom HUD & Keybind Studio, Cursor Style Selector & Log Viewer Modal.
 *
 * Strictly zero stock Android widgets and zero emojis.
 */
class CustomCanvasModalView(
    context: Context,
    private val hudView: ProHudOverlayView,
    private val gyroController: GyroAimController,
    private val onCloseModal: () -> Unit,
    private val onReopenBootLogOverlay: (() -> Unit)? = null
) : View(context) {

    enum class Tab(val title: String) {
        RENDERER_ACCOUNT("RAM, RENDERER & AKUN"),
        CONTROLS_CURSOR("KONTROL & KURSOR"),
        KEYBIND_CREATOR("STUDIO CUSTOM HUD"),
        DIAGNOSTIC_LOG("LOG DIAGNOSTIK")
    }

    enum class BindingCategory(val title: String) {
        ALPHA_NUM("1. KEYBOARD UTAMA"),
        FN_AND_NAV("2. F1-F12 & NAVIGASI"),
        MOUSE_HOTBAR("3. MOUSE, SCROLL & HOTBAR"),
        MACRO_SYSTEM("4. MAKRO PVP & F3 DEBUG")
    }

    data class SpecialBindingSpec(
        val displayTitle: String,
        val buttonLabel: String,
        val actionType: ActionType,
        val keyCodeOrButton: Int = 0,
        val isToggle: Boolean = false
    )

    var activeTab: Tab = Tab.RENDERER_ACCOUNT
        set(value) {
            field = value
            if (value == Tab.DIAGNOSTIC_LOG) {
                logLines = sanitizeLines(VeloLogger.readLatestLogsTail(14000).lines())
                logScrollY = 0f
            }
            postInvalidateOnAnimation()
        }

    private var activeBindCategory: BindingCategory = BindingCategory.ALPHA_NUM

    private val sessionManager = PlayerSessionManager(context)
    private var editingUsername: String = sessionManager.getActiveSession().username
    private var bannerStatusText: String =
        "Pengaturan Studio siap. Ketuk tab di atas untuk mengatur RAM, Kursor, atau Custom HUD."

    // Tab 3: Selected HUD button for keybinding
    private var selectedButtonIndex: Int = 0

    // Tab 4: Log viewer state
    private var logLines: List<String> = emptyList()
    private var logScrollY: Float = 0f
    private var lastTouchY: Float = 0f

    // Hit Rectangles
    private val tabRects = Array(Tab.values().size) { RectF() }
    private val closeBtnRect = RectF()
    private val rendererOptionRects = Array(RendererProfileManager.RendererBackend.values().size) { RectF() }
    private val shaderToggleRect = RectF()
    private val ramSliderRect = RectF()
    private val ramDefaultBtnRect = RectF()
    private val resScale50Rect = RectF()
    private val resScale75Rect = RectF()
    private val resScale100Rect = RectF()
    private val gcModeToggleRect = RectF()
    private val charKeyRects = mutableListOf<Pair<String, RectF>>()

    // Tab 2 Hit Rects
    private val touchSensSliderRect = RectF()
    private val gyroSensSliderRect = RectF()
    private val hudOpacitySliderRect = RectF()
    private val hudSizeSliderRect = RectF()
    private val cursorStyleBtnRect = RectF()
    private val cursorTouchModeBtnRect = RectF()
    private val cursorToggleRect = RectF()
    private val autoClickToggleRect = RectF()
    private val fastPlaceToggleRect = RectF()
    private val openVisualEditorRect = RectF()
    private val presetStandardRect = RectF()
    private val presetPvpRect = RectF()
    private val presetMinimalRect = RectF()

    // Tab 3 Hit Rects
    private val prevBtnSelectRect = RectF()
    private val nextBtnSelectRect = RectF()
    private val addCustomBtnRect = RectF()
    private val duplicateCustomBtnRect = RectF()
    private val visibilityToggleBtnRect = RectF()
    private val deleteCustomBtnRect = RectF()

    private val sizeMinusRect = RectF()
    private val sizePlusRect = RectF()
    private val opacityMinusRect = RectF()
    private val opacityPlusRect = RectF()
    private val shapeCycleRect = RectF()
    private val modeHoldToggleRect = RectF()
    private val colorCycleRect = RectF()

    private val categoryTabRects = Array(BindingCategory.values().size) { RectF() }
    private val keyGridRects = mutableListOf<Pair<Pair<String, Int>, RectF>>()
    private val specialGridRects = mutableListOf<Pair<SpecialBindingSpec, RectF>>()

    // Tab 4 Hit Rects
    private val reopenLiveOverlayRect = RectF()
    private val copyLogBtnRect = RectF()
    private val refreshLogBtnRect = RectF()

    // Matte Non-Neon Studio Paints
    private val scrimPaint = Paint().apply { color = 0xE60E0E0D.toInt() }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1917.toInt() }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF242320.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF36342F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val activeGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4E8C57.toInt() }
    private val slateBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5A7D9A.toInt() }
    private val terraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC87D55.toInt() }
    private val dangerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB85448.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF33312C.toInt() }
    private val dynamicSwatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(12.5f)
        isFakeBoldText = true
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD6D2C8.toInt()
        textSize = dp(10.5f)
    }
    private val mutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9C978C.toInt()
        textSize = dp(9.5f)
    }
    private val btnTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(9.8f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6E2D8.toInt()
        textSize = dp(9.8f)
        typeface = Typeface.MONOSPACE
    }

    private val tmpRect = RectF()

    private val mouseAndHotbarSpecs = listOf(
        SpecialBindingSpec("KLIK KIRI (SERANG / ATK)", "ATK (L)", ActionType.MOUSE_LEFT_ATTACK, NativeEngineBridge.MOUSE_LEFT),
        SpecialBindingSpec("KLIK KANAN (PASANG / PLACE)", "PLACE (R)", ActionType.MOUSE_RIGHT_PLACE, NativeEngineBridge.MOUSE_RIGHT),
        SpecialBindingSpec("KLIK TENGAH (PICK BLOCK)", "PICK (M)", ActionType.MOUSE_MIDDLE_PICK, NativeEngineBridge.MOUSE_MIDDLE),
        SpecialBindingSpec("SCROLL ATAS (ITEM SEBELUMNYA)", "SCRL -", ActionType.MOUSE_SCROLL_UP, 0),
        SpecialBindingSpec("SCROLL BAWAH (ITEM BERIKUTNYA)", "SCRL +", ActionType.MOUSE_SCROLL_DOWN, 0),
        SpecialBindingSpec("HOTBAR SLOT 1", "SLOT 1", ActionType.HOTBAR_SLOT, 0),
        SpecialBindingSpec("HOTBAR SLOT 2", "SLOT 2", ActionType.HOTBAR_SLOT, 1),
        SpecialBindingSpec("HOTBAR SLOT 3", "SLOT 3", ActionType.HOTBAR_SLOT, 2),
        SpecialBindingSpec("HOTBAR SLOT 4", "SLOT 4", ActionType.HOTBAR_SLOT, 3),
        SpecialBindingSpec("HOTBAR SLOT 5", "SLOT 5", ActionType.HOTBAR_SLOT, 4),
        SpecialBindingSpec("HOTBAR SLOT 6", "SLOT 6", ActionType.HOTBAR_SLOT, 5),
        SpecialBindingSpec("HOTBAR SLOT 7", "SLOT 7", ActionType.HOTBAR_SLOT, 6),
        SpecialBindingSpec("HOTBAR SLOT 8", "SLOT 8", ActionType.HOTBAR_SLOT, 7),
        SpecialBindingSpec("HOTBAR SLOT 9", "SLOT 9", ActionType.HOTBAR_SLOT, 8),
        SpecialBindingSpec("OFFHAND SWAP (F)", "OFF (F)", ActionType.KEY_HOLD, NativeEngineBridge.KEY_F)
    )

    private val macroAndDebugSpecs = listOf(
        SpecialBindingSpec("MAKRO COMBO PVP (ROD + SWORD)", "COMBO", ActionType.PVP_COMBO_MACRO, 0),
        SpecialBindingSpec("BUANG 1 STACK PENUH (CTRL + Q)", "STACK Q", ActionType.MACRO_DROP_STACK, 0),
        SpecialBindingSpec("F3 + B (TAMPILKAN HITBOX ENTITAS)", "HITBOX", ActionType.MACRO_HITBOXES, 0),
        SpecialBindingSpec("F3 + G (TAMPILKAN BATAS CHUNK)", "CHUNK G", ActionType.MACRO_CHUNK_BORDERS, 0),
        SpecialBindingSpec("F3 + A (MUAT ULANG CHUNK RENDER)", "RELOAD A", ActionType.MACRO_RELOAD_CHUNKS, 0),
        SpecialBindingSpec("KAMERA PERSPEKTIF (F5)", "CAM F5", ActionType.KEY_HOLD, NativeEngineBridge.KEY_F5),
        SpecialBindingSpec("LAYAR DEBUG (F3)", "DEBUG F3", ActionType.KEY_HOLD, NativeEngineBridge.KEY_F3),
        SpecialBindingSpec("SCREENSHOT (F2)", "SHOT F2", ActionType.KEY_HOLD, 291),
        SpecialBindingSpec("MODE LAYAR BERSIH (SEMBUNYI HUD)", "CLEAN HUD", ActionType.TOGGLE_HUD_CLEAN, 0, isToggle = true),
        SpecialBindingSpec("TOGGLE KURSOR VIRTUAL", "KURSOR", ActionType.CURSOR_TOGGLE, 0, isToggle = true),
        SpecialBindingSpec("TOGGLE GYROSCOPE 200HZ", "GYRO", ActionType.GYRO_TOGGLE, 0, isToggle = true),
        SpecialBindingSpec("DAFTAR PEMAIN MULTIPLAYER (TAB)", "TAB LIST", ActionType.KEY_HOLD, NativeEngineBridge.KEY_TAB),
        SpecialBindingSpec("MENU PAUSE / MOD MENU (ESC)", "ESC", ActionType.KEY_HOLD, NativeEngineBridge.KEY_ESCAPE),
        SpecialBindingSpec("BUKA KEYBOARD IME SISTEM", "KEYB", ActionType.IME_KEYBOARD_TOGGLE, 0),
        SpecialBindingSpec("BUKA SIDEBAR MENU", "MENU", ActionType.SIDEBAR_DRAWER_TOGGLE, 0),
        SpecialBindingSpec("INVENTORI PEMAIN (E)", "INV (E)", ActionType.KEY_HOLD, NativeEngineBridge.KEY_E)
    )

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sanitizeLines(lines: List<String>): List<String> {
        return lines.map { raw ->
            raw.replace("libpojavexec.so", "libvelocraft_bridge.so", ignoreCase = true)
                .replace("pojavexec", "velocraft_bridge", ignoreCase = true)
                .replace("pojavInitOpenGL", "initOpenGLContext", ignoreCase = true)
                .replace("pojavInit", "initBridge", ignoreCase = true)
                .replace("Pojav", "VeloCraft", ignoreCase = true)
        }
    }

    fun openOnTab(tab: Tab, preselectButton: HudButtonConfig? = null) {
        editingUsername = sessionManager.getActiveSession().username
        if (preselectButton != null) {
            val idx = hudView.getButtonsList().indexOfFirst { it.id == preselectButton.id }
            if (idx >= 0) selectedButtonIndex = idx
        }
        activeTab = tab
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        val margin = dp(14f)
        val panelLeft = margin
        val panelTop = margin * 0.65f
        val panelRight = w - margin
        val panelBottom = h - margin * 0.65f

        tmpRect.set(panelLeft, panelTop, panelRight, panelBottom)
        canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), panelPaint)
        canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), borderPaint)

        // Top Tab Bar + Close Button
        val tabBarTop = panelTop + dp(8f)
        val tabH = dp(31f)
        val closeW = dp(86f)
        closeBtnRect.set(panelRight - dp(12f) - closeW, tabBarTop, panelRight - dp(12f), tabBarTop + tabH)
        canvas.drawRoundRect(closeBtnRect, dp(8f), dp(8f), terraPaint)
        drawCenteredButtonText(canvas, "TUTUP [X]", closeBtnRect)

        val tabsAreaW = (closeBtnRect.left - panelLeft - dp(22f))
        val singleTabW = (tabsAreaW - dp(18f)) / Tab.values().size
        for ((i, tab) in Tab.values().withIndex()) {
            val tl = panelLeft + dp(12f) + i * (singleTabW + dp(6f))
            tabRects[i].set(tl, tabBarTop, tl + singleTabW, tabBarTop + tabH)
            canvas.drawRoundRect(
                tabRects[i],
                dp(8f),
                dp(8f),
                if (activeTab == tab) activeGreenPaint else cardPaint
            )
            drawCenteredButtonText(canvas, tab.title, tabRects[i])
        }

        // Status Sub-banner
        drawEllipsizedText(
            canvas,
            bannerStatusText,
            panelLeft + dp(14f),
            tabBarTop + tabH + dp(13f),
            panelRight - panelLeft - dp(28f),
            mutedPaint
        )

        val contentTop = tabBarTop + tabH + dp(20f)
        val contentLeft = panelLeft + dp(14f)
        val contentRight = panelRight - dp(14f)
        val contentBottom = panelBottom - dp(10f)

        when (activeTab) {
            Tab.RENDERER_ACCOUNT -> drawRendererAndAccountTab(canvas, contentLeft, contentTop, contentRight, contentBottom)
            Tab.CONTROLS_CURSOR -> drawControlsAndCursorTab(canvas, contentLeft, contentTop, contentRight, contentBottom)
            Tab.KEYBIND_CREATOR -> drawKeybindCreatorTab(canvas, contentLeft, contentTop, contentRight, contentBottom)
            Tab.DIAGNOSTIC_LOG -> drawDiagnosticLogTab(canvas, contentLeft, contentTop, contentRight, contentBottom)
        }
    }

    private fun drawRendererAndAccountTab(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val colGap = dp(14f)
        val leftW = (right - left - colGap) * 0.52f
        val rightLeft = left + leftW + colGap
        val rightW = right - rightLeft

        canvas.drawText("1. Pilih Engine Renderer (Otomatis / Manual):", left, top + dp(11f), titlePaint)
        val selectedBackend = RendererProfileManager.getSelectedBackend(context)

        var rY = top + dp(17f)
        val itemH = dp(31f)
        for ((idx, backend) in RendererProfileManager.RendererBackend.values().withIndex()) {
            rendererOptionRects[idx].set(left, rY, left + leftW, rY + itemH)
            val isChosen = (selectedBackend == backend)
            canvas.drawRoundRect(
                rendererOptionRects[idx],
                dp(7f),
                dp(7f),
                if (isChosen) activeGreenPaint else cardPaint
            )
            canvas.drawRoundRect(rendererOptionRects[idx], dp(7f), dp(7f), borderPaint)

            val prefix = if (isChosen) "[AKTIF] " else "[PILIH] "
            bodyPaint.isFakeBoldText = isChosen
            drawEllipsizedText(canvas, prefix + backend.displayName, left + dp(8f), rY + dp(13f), leftW - dp(16f), bodyPaint)
            bodyPaint.isFakeBoldText = false
            drawEllipsizedText(canvas, backend.shortDesc, left + dp(8f), rY + dp(25f), leftW - dp(16f), mutedPaint)
            rY += itemH + dp(4f)
        }

        val shadersOn = RendererProfileManager.isShaderSupportEnabled(context)
        shaderToggleRect.set(left, rY + dp(2f), left + leftW, rY + dp(31f))
        canvas.drawRoundRect(shaderToggleRect, dp(7f), dp(7f), if (shadersOn) activeGreenPaint else cardPaint)
        val shLabel = if (shadersOn) "SHADER SUPPORT (IRIS + SHADERPACKS): [ON]" else "SHADER SUPPORT (IRIS + SHADERPACKS): [OFF]"
        drawCenteredButtonText(canvas, shLabel, shaderToggleRect)

        val hw = JvmOptimizer.detectHardware(context)
        val modeTag = if (hw.isCustomHeap) "[CUSTOM]" else "[SARAN DEFAULT]"
        drawEllipsizedText(
            canvas,
            "2. Alokasi Memori JVM: ${hw.allocatedHeapMb} MB $modeTag",
            rightLeft,
            top + dp(11f),
            rightW,
            titlePaint
        )
        drawEllipsizedText(
            canvas,
            "Saran Default HP ini: ${hw.recommendedHeapMb} MB dari Total ${hw.totalRamMb} MB RAM",
            rightLeft,
            top + dp(24f),
            rightW,
            mutedPaint
        )

        val sliderW = rightW * 0.62f
        ramSliderRect.set(rightLeft, top + dp(30f), rightLeft + sliderW, top + dp(52f))
        canvas.drawRoundRect(ramSliderRect, dp(6f), dp(6f), trackPaint)
        val ramRatio = ((hw.allocatedHeapMb - hw.minSafeHeapMb).toFloat() /
            (hw.maxSafeHeapMb - hw.minSafeHeapMb).coerceAtLeast(1)).coerceIn(0f, 1f)
        tmpRect.set(ramSliderRect.left, ramSliderRect.top, ramSliderRect.left + sliderW * ramRatio, ramSliderRect.bottom)
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), activeGreenPaint)
        drawCenteredButtonText(canvas, "Geser RAM (${hw.minSafeHeapMb}-${hw.maxSafeHeapMb} MB)", ramSliderRect)

        ramDefaultBtnRect.set(ramSliderRect.right + dp(6f), top + dp(30f), right, top + dp(52f))
        canvas.drawRoundRect(
            ramDefaultBtnRect,
            dp(6f),
            dp(6f),
            if (!hw.isCustomHeap) activeGreenPaint else terraPaint
        )
        drawCenteredButtonText(canvas, "DEFAULT (${hw.recommendedHeapMb}M)", ramDefaultBtnRect)

        val rowY = top + dp(58f)
        val rowH = dp(24f)
        val btnW4 = (rightW - dp(15f)) / 4f
        resScale50Rect.set(rightLeft, rowY, rightLeft + btnW4, rowY + rowH)
        resScale75Rect.set(resScale50Rect.right + dp(5f), rowY, resScale50Rect.right + dp(5f) + btnW4, rowY + rowH)
        resScale100Rect.set(resScale75Rect.right + dp(5f), rowY, resScale75Rect.right + dp(5f) + btnW4, rowY + rowH)
        gcModeToggleRect.set(resScale100Rect.right + dp(5f), rowY, right, rowY + rowH)

        for ((r, pct) in listOf(resScale50Rect to 50, resScale75Rect to 75, resScale100Rect to 100)) {
            val active = (hw.resolutionScalePct == pct)
            canvas.drawRoundRect(r, dp(5f), dp(5f), if (active) activeGreenPaint else cardPaint)
            canvas.drawRoundRect(r, dp(5f), dp(5f), borderPaint)
            drawCenteredButtonText(canvas, "RES ${pct}%", r)
        }
        canvas.drawRoundRect(gcModeToggleRect, dp(5f), dp(5f), if (hw.useGenerationalZgc) activeGreenPaint else cardPaint)
        canvas.drawRoundRect(gcModeToggleRect, dp(5f), dp(5f), borderPaint)
        drawCenteredButtonText(canvas, if (hw.useGenerationalZgc) "GC: ZGC" else "GC: G1GC", gcModeToggleRect)

        var kY = rowY + rowH + dp(14f)
        val activeAcc = AccountManager.getActiveAccount(context)
        drawEllipsizedText(
            canvas,
            "3. Akun Pemain (${activeAcc.type.label}): ${activeAcc.username}",
            rightLeft,
            kY,
            rightW,
            titlePaint
        )
        kY += dp(6f)

        charKeyRects.clear()
        val halfW = (rightW - dp(6f)) * 0.5f

        // Row A: Mode Offline vs Mode Microsoft Resmi
        val offlineModeRect = RectF(rightLeft, kY, rightLeft + halfW, kY + dp(28f))
        val msaModeRect = RectF(rightLeft + halfW + dp(6f), kY, right, kY + dp(28f))
        val isMsa = AccountManager.getSelectedMode(context) == AccountManager.AccountType.MICROSOFT
        canvas.drawRoundRect(offlineModeRect, dp(6f), dp(6f), if (!isMsa) activeGreenPaint else cardPaint)
        canvas.drawRoundRect(offlineModeRect, dp(6f), dp(6f), borderPaint)
        drawCenteredButtonText(canvas, "MODE OFFLINE (LOKAL)", offlineModeRect)

        canvas.drawRoundRect(msaModeRect, dp(6f), dp(6f), if (isMsa) activeGreenPaint else slateBluePaint)
        canvas.drawRoundRect(msaModeRect, dp(6f), dp(6f), borderPaint)
        drawCenteredButtonText(canvas, "LOGIN MICROSOFT (MSA)", msaModeRect)
        charKeyRects.add("MODE_OFFLINE" to offlineModeRect)
        charKeyRects.add("MODE_MICROSOFT" to msaModeRect)
        kY += dp(33f)

        // Row B: Tap to Edit Username with Native Android System IME Keyboard
        val imeEditUserRect = RectF(rightLeft, kY, right, kY + dp(30f))
        canvas.drawRoundRect(imeEditUserRect, dp(6f), dp(6f), cardPaint)
        canvas.drawRoundRect(imeEditUserRect, dp(6f), dp(6f), borderPaint)
        drawCenteredButtonText(canvas, "USERNAME: $editingUsername  [KETUK KEYBOARD IME SISTEM]", imeEditUserRect)
        charKeyRects.add("OPEN_IME_USERNAME" to imeEditUserRect)
        kY += dp(35f)

        // Row C: Microsoft OAuth2 Status & Browser Action
        drawEllipsizedText(canvas, AccountManager.msaAuthStatusText, rightLeft, kY + dp(9f), rightW, mutedPaint)
        kY += dp(14f)

        // Row D: Share / Export Control .JSON & Import Control .JSON
        val shareJsonRect = RectF(rightLeft, kY, rightLeft + halfW, kY + dp(28f))
        val importJsonRect = RectF(rightLeft + halfW + dp(6f), kY, right, kY + dp(28f))
        canvas.drawRoundRect(shareJsonRect, dp(6f), dp(6f), activeGreenPaint)
        canvas.drawRoundRect(importJsonRect, dp(6f), dp(6f), terraPaint)
        drawCenteredButtonText(canvas, "BAGIKAN KONTROL .JSON", shareJsonRect)
        drawCenteredButtonText(canvas, "IMPOR .JSON CLIPBOARD", importJsonRect)
        charKeyRects.add("SHARE_JSON" to shareJsonRect)
        charKeyRects.add("IMPORT_JSON" to importJsonRect)
    }

    private fun drawControlsAndCursorTab(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val totalW = right - left
        val halfW = (totalW - dp(12f)) * 0.5f
        var y = top + dp(8f)

        // Row 1: Two Sliders Side-by-Side (Global HUD Opacity & Global HUD Button Size)
        val opPct = (hudView.globalHudOpacity * 100f).roundToInt().coerceIn(8, 100)
        val avgSize = hudView.getAverageButtonRadiusDp().roundToInt()
        drawEllipsizedText(canvas, "Opasitas / Transparansi Tombol HUD: $opPct%", left, y, halfW, titlePaint)
        drawEllipsizedText(canvas, "Ukuran Seluruh Tombol HUD: $avgSize dp", left + halfW + dp(12f), y, halfW, titlePaint)

        y += dp(5f)
        val sliderH = dp(22f)
        hudOpacitySliderRect.set(left, y, left + halfW, y + sliderH)
        canvas.drawRoundRect(hudOpacitySliderRect, dp(7f), dp(7f), trackPaint)
        val opFillW = halfW * ((hudView.globalHudOpacity - 0.08f) / 0.92f).coerceIn(0f, 1f)
        tmpRect.set(left, y, left + opFillW, y + sliderH)
        canvas.drawRoundRect(tmpRect, dp(7f), dp(7f), activeGreenPaint)
        drawCenteredButtonText(canvas, "Geser Opasitas HUD ($opPct%)", hudOpacitySliderRect)

        hudSizeSliderRect.set(left + halfW + dp(12f), y, right, y + sliderH)
        canvas.drawRoundRect(hudSizeSliderRect, dp(7f), dp(7f), trackPaint)
        val sizeFillW = halfW * ((avgSize - 14f) / 42f).coerceIn(0f, 1f)
        tmpRect.set(hudSizeSliderRect.left, y, hudSizeSliderRect.left + sizeFillW, y + sliderH)
        canvas.drawRoundRect(tmpRect, dp(7f), dp(7f), slateBluePaint)
        drawCenteredButtonText(canvas, "Geser Ukuran Tombol ($avgSize dp)", hudSizeSliderRect)

        // Row 2: Two Sliders Side-by-Side (Touch Camera Sensitivity 60/120Hz & Gyroscope Sensitivity)
        y += sliderH + dp(10f)
        val touchPct = (hudView.touchLookSensitivity * 100).toInt()
        drawEllipsizedText(canvas, "Sensitivitas Geser Kamera (60/120Hz): $touchPct%", left, y, halfW, titlePaint)
        val gyroVal = gyroController.sensitivityX.toInt()
        drawEllipsizedText(canvas, "Sensitivitas Gyroscope (200Hz): ${gyroVal}x", left + halfW + dp(12f), y, halfW, titlePaint)

        y += dp(5f)
        touchSensSliderRect.set(left, y, left + halfW, y + sliderH)
        canvas.drawRoundRect(touchSensSliderRect, dp(7f), dp(7f), trackPaint)
        val touchFillW = halfW * ((touchPct - 15f) / 285f).coerceIn(0f, 1f)
        tmpRect.set(left, y, left + touchFillW, y + sliderH)
        canvas.drawRoundRect(tmpRect, dp(7f), dp(7f), terraPaint)
        drawCenteredButtonText(canvas, "Geser Sensitivitas Kamera ($touchPct%)", touchSensSliderRect)

        gyroSensSliderRect.set(left + halfW + dp(12f), y, right, y + sliderH)
        canvas.drawRoundRect(gyroSensSliderRect, dp(7f), dp(7f), trackPaint)
        val gyroFillW = halfW * (gyroVal / 30f).coerceIn(0f, 1f)
        tmpRect.set(gyroSensSliderRect.left, y, gyroSensSliderRect.left + gyroFillW, y + sliderH)
        canvas.drawRoundRect(tmpRect, dp(7f), dp(7f), activeGreenPaint)
        drawCenteredButtonText(canvas, "Geser Sensitivitas Gyro (${gyroVal}x)", gyroSensSliderRect)

        // Row 3: Cursor Style Picker + Menu Touch Mode Picker
        y += sliderH + dp(9f)
        val btnH = dp(29f)
        cursorStyleBtnRect.set(left, y, left + halfW, y + btnH)
        cursorTouchModeBtnRect.set(left + halfW + dp(12f), y, right, y + btnH)

        canvas.drawRoundRect(cursorStyleBtnRect, dp(7f), dp(7f), slateBluePaint)
        canvas.drawRoundRect(cursorStyleBtnRect, dp(7f), dp(7f), borderPaint)
        drawCenteredButtonText(canvas, "GAYA KURSOR: ${hudView.getCursorStyleName()} [GANTI]", cursorStyleBtnRect)

        val touchModeName = if (hudView.directTouchMenuClickEnabled) {
            "MODE MENU: SENTUH LANGSUNG + GESER"
        } else {
            "MODE MENU: TRACKPAD KURSOR SAJA"
        }
        canvas.drawRoundRect(cursorTouchModeBtnRect, dp(7f), dp(7f), if (hudView.directTouchMenuClickEnabled) activeGreenPaint else cardPaint)
        canvas.drawRoundRect(cursorTouchModeBtnRect, dp(7f), dp(7f), borderPaint)
        drawCenteredButtonText(canvas, touchModeName, cursorTouchModeBtnRect)

        // Row 4 & Row 5: 2x2 Feature Toggles + Visual HUD Editor Launcher
        y += btnH + dp(6f)
        cursorToggleRect.set(left, y, left + halfW, y + btnH)
        autoClickToggleRect.set(left + halfW + dp(12f), y, right, y + btnH)

        y += btnH + dp(6f)
        fastPlaceToggleRect.set(left, y, left + halfW, y + btnH)
        openVisualEditorRect.set(left + halfW + dp(12f), y, right, y + btnH)

        drawToggleBox(canvas, cursorToggleRect, "PAKSA TAMPILKAN KURSOR", hudView.isVirtualCursorEnabled)
        drawToggleBox(canvas, autoClickToggleRect, "TAHAN SERANG AUTO-CLICK (14 CPS)", hudView.holdAutoClickEnabled)
        drawToggleBox(canvas, fastPlaceToggleRect, "FASTPLACE / BRIDGING (15 CPS)", hudView.macroEngine.fastPlaceEnabled)

        canvas.drawRoundRect(openVisualEditorRect, dp(7f), dp(7f), terraPaint)
        canvas.drawRoundRect(openVisualEditorRect, dp(7f), dp(7f), borderPaint)
        drawCenteredButtonText(canvas, "BUKA VISUAL DRAG & DROP HUD EDITOR", openVisualEditorRect)

        // Row 6: 3 HUD Layout Presets (Standar Studio / PvP Kompetitif / Builder Minimalis)
        y += btnH + dp(10f)
        drawEllipsizedText(canvas, "Preset Tata Letak HUD Cepat (Ketuk untuk menerapkan):", left, y, totalW, mutedPaint)
        y += dp(5f)
        val presetW = (totalW - dp(16f)) / 3f
        val presetH = dp(28f)
        presetStandardRect.set(left, y, left + presetW, y + presetH)
        presetPvpRect.set(presetStandardRect.right + dp(8f), y, presetStandardRect.right + dp(8f) + presetW, y + presetH)
        presetMinimalRect.set(presetPvpRect.right + dp(8f), y, right, y + presetH)

        canvas.drawRoundRect(presetStandardRect, dp(7f), dp(7f), activeGreenPaint)
        drawCenteredButtonText(canvas, "PRESET 1: STANDAR STUDIO", presetStandardRect)

        canvas.drawRoundRect(presetPvpRect, dp(7f), dp(7f), dangerPaint)
        drawCenteredButtonText(canvas, "PRESET 2: PVP KOMPETITIF", presetPvpRect)

        canvas.drawRoundRect(presetMinimalRect, dp(7f), dp(7f), slateBluePaint)
        drawCenteredButtonText(canvas, "PRESET 3: BUILDER MINIMAL", presetMinimalRect)
    }

    private fun drawToggleBox(canvas: Canvas, rect: RectF, label: String, active: Boolean) {
        canvas.drawRoundRect(rect, dp(7f), dp(7f), if (active) activeGreenPaint else cardPaint)
        canvas.drawRoundRect(rect, dp(7f), dp(7f), borderPaint)
        val state = if (active) "[ON]" else "[OFF]"
        drawCenteredButtonText(canvas, "$label: $state", rect)
    }

    private fun drawKeybindCreatorTab(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val buttons = hudView.getButtonsList()
        if (buttons.isEmpty()) return
        selectedButtonIndex = selectedButtonIndex.coerceIn(0, buttons.lastIndex)
        val currentBtn = buttons[selectedButtonIndex]
        val totalW = right - left
        val gap = dp(5f)

        // =========================================================================
        // ROW 1: Button Selector (< PREV, NEXT >, Selected Info, + TAMBAH, DUPLIKAT, TAMPIL, HAPUS)
        // =========================================================================
        val r1H = dp(28f)
        var xCur = left
        prevBtnSelectRect.set(xCur, top, xCur + dp(42f), top + r1H)
        xCur += dp(42f) + gap
        nextBtnSelectRect.set(xCur, top, xCur + dp(42f), top + r1H)
        xCur += dp(42f) + gap

        val rightToolsW = dp(68f) * 4f + gap * 3f
        val infoW = (right - rightToolsW - gap - xCur).coerceAtLeast(dp(130f))
        tmpRect.set(xCur, top, xCur + infoW, top + r1H)
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), borderPaint)
        drawCenteredButtonText(
            canvas,
            "Tombol [${selectedButtonIndex + 1}/${buttons.size}]: ${currentBtn.label} (${currentBtn.actionType.name})",
            tmpRect
        )
        xCur += infoW + gap

        val toolW = (right - xCur - gap * 3f) / 4f
        addCustomBtnRect.set(xCur, top, xCur + toolW, top + r1H)
        xCur += toolW + gap
        duplicateCustomBtnRect.set(xCur, top, xCur + toolW, top + r1H)
        xCur += toolW + gap
        visibilityToggleBtnRect.set(xCur, top, xCur + toolW, top + r1H)
        xCur += toolW + gap
        deleteCustomBtnRect.set(xCur, top, right, top + r1H)

        for ((r, lbl) in listOf(prevBtnSelectRect to "< PREV", nextBtnSelectRect to "NEXT >")) {
            canvas.drawRoundRect(r, dp(6f), dp(6f), cardPaint)
            canvas.drawRoundRect(r, dp(6f), dp(6f), borderPaint)
            drawCenteredButtonText(canvas, lbl, r)
        }

        canvas.drawRoundRect(addCustomBtnRect, dp(6f), dp(6f), activeGreenPaint)
        drawCenteredButtonText(canvas, "+ TAMBAH", addCustomBtnRect)

        canvas.drawRoundRect(duplicateCustomBtnRect, dp(6f), dp(6f), slateBluePaint)
        drawCenteredButtonText(canvas, "DUPLIKAT", duplicateCustomBtnRect)

        canvas.drawRoundRect(visibilityToggleBtnRect, dp(6f), dp(6f), if (currentBtn.isVisible) activeGreenPaint else cardPaint)
        canvas.drawRoundRect(visibilityToggleBtnRect, dp(6f), dp(6f), borderPaint)
        drawCenteredButtonText(canvas, if (currentBtn.isVisible) "TAMPIL: ON" else "TAMPIL: OFF", visibilityToggleBtnRect)

        canvas.drawRoundRect(deleteCustomBtnRect, dp(6f), dp(6f), dangerPaint)
        drawCenteredButtonText(canvas, "HAPUS", deleteCustomBtnRect)

        // =========================================================================
        // ROW 2: Button Properties (Size -/+, Opacity -/+, Shape, Hold/Toggle, Color Swatch)
        // =========================================================================
        val r2Top = top + r1H + dp(5f)
        val r2H = dp(27f)
        val propCols = 7
        val propW = (totalW - gap * (propCols - 1)) / propCols

        val propRects = listOf(
            sizeMinusRect,
            sizePlusRect,
            opacityMinusRect,
            opacityPlusRect,
            shapeCycleRect,
            modeHoldToggleRect,
            colorCycleRect
        )
        for (i in 0 until propCols) {
            val pl = left + i * (propW + gap)
            propRects[i].set(pl, r2Top, pl + propW, r2Top + r2H)
        }

        val opPct = (currentBtn.opacity * 100).roundToInt()
        val propLabels = listOf(
            "UKURAN - (${currentBtn.radiusDp.toInt()})",
            "UKURAN +",
            "OPAS - ($opPct%)",
            "OPAS +",
            "BENTUK: ${currentBtn.shape.displayName}",
            if (currentBtn.isToggle) "MODE: TOGGLE" else "MODE: TAHAN",
            "WARNA AKSEN"
        )
        for (i in 0 until propCols) {
            val r = propRects[i]
            if (i == 6) {
                dynamicSwatchPaint.color = currentBtn.accentHex
                canvas.drawRoundRect(r, dp(6f), dp(6f), dynamicSwatchPaint)
            } else {
                canvas.drawRoundRect(r, dp(6f), dp(6f), cardPaint)
            }
            canvas.drawRoundRect(r, dp(6f), dp(6f), borderPaint)
            drawCenteredButtonText(canvas, propLabels[i], r)
        }

        // =========================================================================
        // ROW 3: 4 Category Sub-Tabs for Binding Actions
        // =========================================================================
        val r3Top = r2Top + r2H + dp(6f)
        val r3H = dp(26f)
        val catCount = BindingCategory.values().size
        val catW = (totalW - gap * (catCount - 1)) / catCount
        for ((idx, cat) in BindingCategory.values().withIndex()) {
            val cl = left + idx * (catW + gap)
            categoryTabRects[idx].set(cl, r3Top, cl + catW, r3Top + r3H)
            canvas.drawRoundRect(
                categoryTabRects[idx],
                dp(6f),
                dp(6f),
                if (activeBindCategory == cat) slateBluePaint else cardPaint
            )
            canvas.drawRoundRect(categoryTabRects[idx], dp(6f), dp(6f), borderPaint)
            drawCenteredButtonText(canvas, cat.title, categoryTabRects[idx])
        }

        // =========================================================================
        // ROW 4: Binding Grid for Active Category
        // =========================================================================
        val gridTop = r3Top + r3H + dp(6f)
        val availH = (bottom - gridTop).coerceAtLeast(dp(60f))
        keyGridRects.clear()
        specialGridRects.clear()

        when (activeBindCategory) {
            BindingCategory.ALPHA_NUM -> {
                drawKeyMapGrid(canvas, left, gridTop, totalW, availH, MacroEngine.KEYBOARD_ALPHA_NUM, currentBtn, cols = 11)
            }
            BindingCategory.FN_AND_NAV -> {
                drawKeyMapGrid(canvas, left, gridTop, totalW, availH, MacroEngine.KEYBOARD_FN_NAV, currentBtn, cols = 9)
            }
            BindingCategory.MOUSE_HOTBAR -> {
                drawSpecialSpecGrid(canvas, left, gridTop, totalW, availH, mouseAndHotbarSpecs, currentBtn, cols = 3)
            }
            BindingCategory.MACRO_SYSTEM -> {
                drawSpecialSpecGrid(canvas, left, gridTop, totalW, availH, macroAndDebugSpecs, currentBtn, cols = 3)
            }
        }
    }

    private fun drawKeyMapGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        totalW: Float,
        availH: Float,
        keyMap: Map<String, Int>,
        currentBtn: HudButtonConfig,
        cols: Int
    ) {
        val entries = keyMap.entries.toList()
        val rows = ((entries.size + cols - 1) / cols).coerceAtLeast(1)
        val gap = dp(4f)
        val cellW = (totalW - gap * (cols - 1)) / cols
        val cellH = ((availH - gap * (rows - 1)) / rows).coerceIn(dp(20f), dp(32f))

        for ((idx, entry) in entries.withIndex()) {
            val col = idx % cols
            val row = idx / cols
            val cx = left + col * (cellW + gap)
            val cy = top + row * (cellH + gap)
            val r = RectF(cx, cy, cx + cellW, cy + cellH)
            val isBound = (currentBtn.actionType == ActionType.KEY_HOLD || currentBtn.actionType == ActionType.KEY_TOGGLE) &&
                currentBtn.keyCodeOrButton == entry.value

            canvas.drawRoundRect(r, dp(5f), dp(5f), if (isBound) activeGreenPaint else cardPaint)
            canvas.drawRoundRect(r, dp(5f), dp(5f), borderPaint)
            drawCenteredButtonText(canvas, entry.key, r)
            keyGridRects.add((entry.key to entry.value) to r)
        }
    }

    private fun drawSpecialSpecGrid(
        canvas: Canvas,
        left: Float,
        top: Float,
        totalW: Float,
        availH: Float,
        specs: List<SpecialBindingSpec>,
        currentBtn: HudButtonConfig,
        cols: Int
    ) {
        val rows = ((specs.size + cols - 1) / cols).coerceAtLeast(1)
        val gap = dp(5f)
        val cellW = (totalW - gap * (cols - 1)) / cols
        val cellH = ((availH - gap * (rows - 1)) / rows).coerceIn(dp(22f), dp(34f))

        for ((idx, spec) in specs.withIndex()) {
            val col = idx % cols
            val row = idx / cols
            val cx = left + col * (cellW + gap)
            val cy = top + row * (cellH + gap)
            val r = RectF(cx, cy, cx + cellW, cy + cellH)
            val isBound = currentBtn.actionType == spec.actionType &&
                currentBtn.keyCodeOrButton == spec.keyCodeOrButton

            canvas.drawRoundRect(r, dp(6f), dp(6f), if (isBound) activeGreenPaint else cardPaint)
            canvas.drawRoundRect(r, dp(6f), dp(6f), borderPaint)
            drawCenteredButtonText(canvas, spec.displayTitle, r)
            specialGridRects.add(spec to r)
        }
    }

    private fun drawDiagnosticLogTab(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val logBarTop = top
        val btnW = dp(118f)
        val btnH = dp(28f)
        copyLogBtnRect.set(right - btnW, logBarTop, right, logBarTop + btnH)
        refreshLogBtnRect.set(copyLogBtnRect.left - dp(6f) - btnW, logBarTop, copyLogBtnRect.left - dp(6f), logBarTop + btnH)
        reopenLiveOverlayRect.set(refreshLogBtnRect.left - dp(6f) - dp(152f), logBarTop, refreshLogBtnRect.left - dp(6f), logBarTop + btnH)

        canvas.drawRoundRect(reopenLiveOverlayRect, dp(7f), dp(7f), slateBluePaint)
        canvas.drawRoundRect(reopenLiveOverlayRect, dp(7f), dp(7f), borderPaint)
        drawCenteredButtonText(canvas, "BUKA KONSOL LOG LIVE", reopenLiveOverlayRect)

        canvas.drawRoundRect(refreshLogBtnRect, dp(7f), dp(7f), cardPaint)
        canvas.drawRoundRect(refreshLogBtnRect, dp(7f), dp(7f), borderPaint)
        drawCenteredButtonText(canvas, "SEGARKAN LOG", refreshLogBtnRect)

        canvas.drawRoundRect(copyLogBtnRect, dp(7f), dp(7f), activeGreenPaint)
        drawCenteredButtonText(canvas, "SALIN LOG", copyLogBtnRect)

        drawEllipsizedText(
            canvas,
            "File: ${VeloLogger.latestLogFile?.absolutePath ?: "latest.log"} (Geser untuk scroll)",
            left,
            logBarTop + dp(18f),
            (reopenLiveOverlayRect.left - left - dp(8f)).coerceAtLeast(dp(120f)),
            bodyPaint
        )

        val boxTop = logBarTop + btnH + dp(6f)
        tmpRect.set(left, boxTop, right, bottom)
        canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), borderPaint)

        canvas.save()
        canvas.clipRect(tmpRect.left + dp(8f), tmpRect.top + dp(6f), tmpRect.right - dp(8f), tmpRect.bottom - dp(6f))
        val lineH = dp(13.5f)
        var y = tmpRect.top + dp(18f) - logScrollY
        for (line in logLines) {
            if (y >= tmpRect.top && y <= tmpRect.bottom + lineH) {
                canvas.drawText(line, tmpRect.left + dp(8f), y, monoPaint)
            }
            y += lineH
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchY = y
                if (activeTab == Tab.RENDERER_ACCOUNT) {
                    handleRamSliderDrag(x, y)
                } else if (activeTab == Tab.CONTROLS_CURSOR) {
                    handleControlsSliderDrag(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeTab == Tab.RENDERER_ACCOUNT) {
                    handleRamSliderDrag(x, y)
                } else if (activeTab == Tab.CONTROLS_CURSOR) {
                    handleControlsSliderDrag(x, y)
                } else if (activeTab == Tab.DIAGNOSTIC_LOG) {
                    val dy = lastTouchY - y
                    lastTouchY = y
                    val maxScroll = max(0f, logLines.size * dp(13.5f) - (height * 0.45f))
                    logScrollY = (logScrollY + dy).coerceIn(0f, maxScroll)
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (closeBtnRect.contains(x, y)) {
                    visibility = GONE
                    onCloseModal()
                    return true
                }

                for ((i, rect) in tabRects.withIndex()) {
                    if (rect.contains(x, y)) {
                        activeTab = Tab.values()[i]
                        return true
                    }
                }

                when (activeTab) {
                    Tab.RENDERER_ACCOUNT -> handleRendererTabTap(x, y)
                    Tab.CONTROLS_CURSOR -> handleControlsTabTap(x, y)
                    Tab.KEYBIND_CREATOR -> handleKeybindTabTap(x, y)
                    Tab.DIAGNOSTIC_LOG -> handleLogTabTap(x, y)
                }
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun handleRamSliderDrag(x: Float, y: Float) {
        if (ramSliderRect.contains(x, y)) {
            val hw = JvmOptimizer.detectHardware(context)
            val ratio = ((x - ramSliderRect.left) / ramSliderRect.width()).coerceIn(0f, 1f)
            val rawMb = hw.minSafeHeapMb + (ratio * (hw.maxSafeHeapMb - hw.minSafeHeapMb)).toInt()
            val steppedMb = (rawMb / 64) * 64
            JvmOptimizer.setCustomHeapMb(context, steppedMb)
            bannerStatusText = "Alokasi Memori JVM diatur ke ${steppedMb} MB (Saran Default: ${hw.recommendedHeapMb} MB)"
            postInvalidateOnAnimation()
        }
    }

    private fun handleControlsSliderDrag(x: Float, y: Float) {
        if (hudOpacitySliderRect.contains(x, y)) {
            val ratio = ((x - hudOpacitySliderRect.left) / hudOpacitySliderRect.width()).coerceIn(0f, 1f)
            val newOp = (0.08f + ratio * 0.92f).coerceIn(0.08f, 1.0f)
            hudView.setAllButtonsOpacity(newOp)
            bannerStatusText = "Opasitas seluruh tombol HUD diatur ke ${(newOp * 100f).roundToInt()}%"
            postInvalidateOnAnimation()
        } else if (hudSizeSliderRect.contains(x, y)) {
            val ratio = ((x - hudSizeSliderRect.left) / hudSizeSliderRect.width()).coerceIn(0f, 1f)
            val newRad = (14f + ratio * 42f).coerceIn(14f, 56f)
            hudView.setAllButtonsRadiusDp(newRad)
            bannerStatusText = "Ukuran seluruh tombol HUD diatur ke ${newRad.roundToInt()} dp"
            postInvalidateOnAnimation()
        } else if (touchSensSliderRect.contains(x, y)) {
            val ratio = ((x - touchSensSliderRect.left) / touchSensSliderRect.width()).coerceIn(0f, 1f)
            hudView.touchLookSensitivity = 0.15f + ratio * 2.85f
            bannerStatusText = "Sensitivitas geser kamera 60/120Hz diatur ke ${(hudView.touchLookSensitivity * 100f).roundToInt()}%"
            postInvalidateOnAnimation()
        } else if (gyroSensSliderRect.contains(x, y)) {
            val ratio = ((x - gyroSensSliderRect.left) / gyroSensSliderRect.width()).coerceIn(0.04f, 1f)
            val v = (ratio * 30f).coerceAtLeast(1f)
            gyroController.sensitivityX = v
            gyroController.sensitivityY = v * 0.78f
            postInvalidateOnAnimation()
        }
    }

    private fun handleRendererTabTap(x: Float, y: Float) {
        for ((idx, rect) in rendererOptionRects.withIndex()) {
            if (rect.contains(x, y)) {
                val chosen = RendererProfileManager.RendererBackend.values()[idx]
                RendererProfileManager.setSelectedBackend(context, chosen)
                bannerStatusText = "Renderer dipilih: ${chosen.displayName}"
                return
            }
        }
        if (shaderToggleRect.contains(x, y)) {
            val next = !RendererProfileManager.isShaderSupportEnabled(context)
            RendererProfileManager.setShaderSupportEnabled(context, next)
            bannerStatusText = "Shader Support (Iris): ${if (next) "[ON]" else "[OFF]"}"
            return
        }
        if (ramDefaultBtnRect.contains(x, y)) {
            JvmOptimizer.resetToRecommendedHeap(context)
            val hw = JvmOptimizer.detectHardware(context)
            bannerStatusText = "Alokasi RAM dikembalikan ke Saran Default Optimal: ${hw.recommendedHeapMb} MB"
            return
        }
        if (resScale50Rect.contains(x, y)) {
            JvmOptimizer.setResolutionScalePct(context, 50)
            PerformanceBooster.setResolutionScale(context, 0.50f)
            bannerStatusText = "Resolusi Render Hardware C++ diatur ke 50% (Ultra High FPS Redmi A2)."
            return
        }
        if (resScale75Rect.contains(x, y)) {
            JvmOptimizer.setResolutionScalePct(context, 75)
            PerformanceBooster.setResolutionScale(context, 0.75f)
            bannerStatusText = "Resolusi Render Hardware C++ diatur ke 75% (Balanced)."
            return
        }
        if (resScale100Rect.contains(x, y)) {
            JvmOptimizer.setResolutionScalePct(context, 100)
            PerformanceBooster.setResolutionScale(context, 1.00f)
            bannerStatusText = "Resolusi Render Hardware C++ diatur ke 100% (Native Tajam)."
            return
        }
        if (gcModeToggleRect.contains(x, y)) {
            val next = !JvmOptimizer.isZgcModeEnabled(context)
            JvmOptimizer.setZgcModeEnabled(context, next)
            bannerStatusText = "Garbage Collector diatur ke: ${if (JvmOptimizer.isZgcModeEnabled(context)) "Generational ZGC" else "G1GC Low-Pause"}"
            return
        }
        for ((key, rect) in charKeyRects) {
            if (rect.contains(x, y)) {
                when (key) {
                    "MODE_OFFLINE" -> {
                        val acc = AccountManager.setOfflineAccount(context, editingUsername)
                        sessionManager.saveOfflineSession(acc.username)
                        bannerStatusText = "Mode Akun Offline aktif: ${acc.username} (UUID: ${acc.uuid.take(8)}...)"
                    }
                    "MODE_MICROSOFT" -> {
                        AccountManager.setAccountMode(context, AccountManager.AccountType.MICROSOFT)
                        AccountManager.startMicrosoftLoginFlow(context, openBrowserImmediately = true) {
                            bannerStatusText = AccountManager.msaAuthStatusText
                            postInvalidateOnAnimation()
                        }
                    }
                    "OPEN_IME_USERNAME" -> {
                        SystemImeHelper.requestTextInput(
                            label = "Username Pemain Offline",
                            initialText = editingUsername,
                            maxLength = 16,
                            onChanged = { updated ->
                                editingUsername = updated.replace(Regex("[^a-zA-Z0-9_]"), "")
                                val acc = AccountManager.setOfflineAccount(context, editingUsername)
                                sessionManager.saveOfflineSession(acc.username)
                                bannerStatusText = "Username diperbarui via IME: ${acc.username}"
                                postInvalidateOnAnimation()
                            }
                        )
                    }
                    "SHARE_JSON" -> {
                        val outPath = ControlProfileManager.exportAndShareLayoutJson(
                            context,
                            hudView.getButtonsList(),
                            "kontrol-xyverse-pro",
                            openShareSheet = true
                        )
                        bannerStatusText = "Kontrol .json diekspor & dibagikan: $outPath"
                    }
                    "IMPORT_JSON" -> {
                        val (ok, msg) = ControlProfileManager.importLayoutFromClipboard(context)
                        if (ok) hudView.reloadLayoutFromDisk()
                        bannerStatusText = msg
                    }
                }
                return
            }
        }
    }

    private fun handleControlsTabTap(x: Float, y: Float) {
        when {
            cursorStyleBtnRect.contains(x, y) -> {
                val styleName = hudView.cycleCursorStyle()
                bannerStatusText = "Gaya Kursor diubah menjadi: $styleName"
            }
            cursorTouchModeBtnRect.contains(x, y) -> {
                hudView.directTouchMenuClickEnabled = !hudView.directTouchMenuClickEnabled
                bannerStatusText = if (hudView.directTouchMenuClickEnabled) {
                    "Mode Menu: Sentuh Langsung (ketuk tombol menu langsung klik) + Geser Kursor aktif."
                } else {
                    "Mode Menu: Trackpad Kursor (geser untuk menggerakkan kursor, ketuk untuk klik)."
                }
            }
            cursorToggleRect.contains(x, y) -> {
                hudView.toggleVirtualCursor()
            }
            autoClickToggleRect.contains(x, y) -> {
                hudView.holdAutoClickEnabled = !hudView.holdAutoClickEnabled
            }
            fastPlaceToggleRect.contains(x, y) -> {
                hudView.macroEngine.fastPlaceEnabled = !hudView.macroEngine.fastPlaceEnabled
            }
            openVisualEditorRect.contains(x, y) -> {
                visibility = GONE
                onCloseModal()
                hudView.visibility = VISIBLE
                hudView.enterVisualEditMode()
            }
            presetStandardRect.contains(x, y) -> {
                hudView.applyLayoutPreset(0)
                bannerStatusText = "Preset 1 (Standar Studio Lengkap) berhasil diterapkan."
            }
            presetPvpRect.contains(x, y) -> {
                hudView.applyLayoutPreset(1)
                bannerStatusText = "Preset 2 (PvP Kompetitif Ergonomis + Hitbox) berhasil diterapkan."
            }
            presetMinimalRect.contains(x, y) -> {
                hudView.applyLayoutPreset(2)
                bannerStatusText = "Preset 3 (Builder & Survival Minimalis + Scroll) berhasil diterapkan."
            }
        }
    }

    private fun handleKeybindTabTap(x: Float, y: Float) {
        val buttons = hudView.getButtonsList()
        if (buttons.isEmpty()) return
        selectedButtonIndex = selectedButtonIndex.coerceIn(0, buttons.lastIndex)
        val currentBtn = buttons[selectedButtonIndex]

        for ((idx, rect) in categoryTabRects.withIndex()) {
            if (rect.contains(x, y)) {
                activeBindCategory = BindingCategory.values()[idx]
                return
            }
        }

        when {
            prevBtnSelectRect.contains(x, y) -> {
                selectedButtonIndex = (selectedButtonIndex + buttons.size - 1) % buttons.size
            }
            nextBtnSelectRect.contains(x, y) -> {
                selectedButtonIndex = (selectedButtonIndex + 1) % buttons.size
            }
            addCustomBtnRect.contains(x, y) -> {
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
                selectedButtonIndex = buttons.lastIndex
                hudView.saveCurrentLayout()
                bannerStatusText = "Tombol baru ditambahkan. Pilih aksi di bawah lalu atur posisinya di EDIT HUD."
            }
            duplicateCustomBtnRect.contains(x, y) -> {
                val copyBtn = currentBtn.copy(
                    id = "custom_${System.currentTimeMillis()}",
                    normX = (currentBtn.normX + 0.06f).coerceIn(0.06f, 0.94f),
                    normY = (currentBtn.normY + 0.06f).coerceIn(0.08f, 0.92f)
                )
                buttons.add(copyBtn)
                selectedButtonIndex = buttons.lastIndex
                hudView.saveCurrentLayout()
                bannerStatusText = "Tombol '${currentBtn.label}' berhasil diduplikat."
            }
            visibilityToggleBtnRect.contains(x, y) -> {
                if (currentBtn.id == "btn_settings" || currentBtn.id == "btn_edit_hud") {
                    bannerStatusText = "Tombol sistem utama (CFG / EDIT HUD) harus tetap tampil."
                } else {
                    currentBtn.isVisible = !currentBtn.isVisible
                    hudView.saveCurrentLayout()
                    bannerStatusText = "Visibilitas tombol '${currentBtn.label}': ${if (currentBtn.isVisible) "TAMPIL" else "SEMBUNYI"}"
                }
            }
            deleteCustomBtnRect.contains(x, y) -> {
                if (buttons.size > 4 && currentBtn.id != "btn_settings" && currentBtn.id != "btn_edit_hud") {
                    val removedLabel = currentBtn.label
                    buttons.removeAt(selectedButtonIndex)
                    selectedButtonIndex = selectedButtonIndex.coerceIn(0, buttons.lastIndex)
                    hudView.saveCurrentLayout()
                    bannerStatusText = "Tombol '$removedLabel' dihapus."
                } else {
                    bannerStatusText = "Tombol sistem utama (CFG / EDIT HUD) tidak dapat dihapus."
                }
            }
            sizeMinusRect.contains(x, y) -> {
                currentBtn.radiusDp = (currentBtn.radiusDp - 2.5f).coerceAtLeast(14f)
                hudView.saveCurrentLayout()
            }
            sizePlusRect.contains(x, y) -> {
                currentBtn.radiusDp = (currentBtn.radiusDp + 2.5f).coerceAtMost(62f)
                hudView.saveCurrentLayout()
            }
            opacityMinusRect.contains(x, y) -> {
                currentBtn.opacity = (currentBtn.opacity - 0.08f).coerceAtLeast(0.08f)
                hudView.saveCurrentLayout()
            }
            opacityPlusRect.contains(x, y) -> {
                currentBtn.opacity = (currentBtn.opacity + 0.08f).coerceAtMost(1.0f)
                hudView.saveCurrentLayout()
            }
            shapeCycleRect.contains(x, y) -> {
                val shapes = ButtonShape.values()
                currentBtn.shape = shapes[(currentBtn.shape.ordinal + 1) % shapes.size]
                hudView.saveCurrentLayout()
                bannerStatusText = "Bentuk tombol '${currentBtn.label}' diubah menjadi: ${currentBtn.shape.displayName}"
            }
            modeHoldToggleRect.contains(x, y) -> {
                currentBtn.isToggle = !currentBtn.isToggle
                if (currentBtn.actionType == ActionType.KEY_HOLD || currentBtn.actionType == ActionType.KEY_TOGGLE) {
                    currentBtn.actionType = if (currentBtn.isToggle) ActionType.KEY_TOGGLE else ActionType.KEY_HOLD
                }
                hudView.saveCurrentLayout()
                bannerStatusText = "Mode tombol '${currentBtn.label}': ${if (currentBtn.isToggle) "TOGGLE (Tekan Sekali)" else "TAHAN (Hold)"}"
            }
            colorCycleRect.contains(x, y) -> {
                val palette = ControlProfileManager.STUDIO_ACCENT_PALETTE
                val curIdx = palette.indexOfFirst { it.second == currentBtn.accentHex }
                val nextPair = palette[(curIdx + 1) % palette.size]
                currentBtn.accentHex = nextPair.second
                hudView.saveCurrentLayout()
                bannerStatusText = "Warna aksen tombol '${currentBtn.label}' diatur ke: ${nextPair.first}"
            }
            else -> {
                for ((entry, rect) in keyGridRects) {
                    if (rect.contains(x, y)) {
                        currentBtn.keyCodeOrButton = entry.second
                        currentBtn.label = entry.first
                        currentBtn.actionType = if (currentBtn.isToggle) ActionType.KEY_TOGGLE else ActionType.KEY_HOLD
                        hudView.saveCurrentLayout()
                        bannerStatusText = "Tombol di-bind ke Keyboard '${entry.first}' (GLFW code ${entry.second})."
                        return
                    }
                }
                for ((spec, rect) in specialGridRects) {
                    if (rect.contains(x, y)) {
                        currentBtn.actionType = spec.actionType
                        currentBtn.keyCodeOrButton = spec.keyCodeOrButton
                        currentBtn.label = spec.buttonLabel
                        currentBtn.isToggle = spec.isToggle
                        hudView.saveCurrentLayout()
                        bannerStatusText = "Tombol di-bind ke '${spec.displayTitle}' (Label: ${spec.buttonLabel})."
                        return
                    }
                }
            }
        }
    }

    private fun handleLogTabTap(x: Float, y: Float) {
        when {
            reopenLiveOverlayRect.contains(x, y) -> {
                visibility = GONE
                onCloseModal()
                onReopenBootLogOverlay?.invoke()
            }
            refreshLogBtnRect.contains(x, y) -> {
                logLines = sanitizeLines(VeloLogger.readLatestLogsTail(14000).lines())
                bannerStatusText = "Log diperbarui (${logLines.size} baris)."
            }
            copyLogBtnRect.contains(x, y) -> {
                val fullText = sanitizeLines(VeloLogger.readLatestLogsTail(25000).lines()).joinToString("\n")
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("XyverseMinecraftLog", fullText))
                bannerStatusText = "[DISALIN] Isi latest.log berhasil disalin ke Clipboard!"
            }
        }
    }

    private fun drawCenteredButtonText(canvas: Canvas, text: String, rect: RectF) {
        val maxW = (rect.width() - dp(8f)).coerceAtLeast(dp(12f))
        btnTextPaint.textSize = dp(9.6f)
        while (btnTextPaint.measureText(text) > maxW && btnTextPaint.textSize > dp(7.2f)) {
            btnTextPaint.textSize -= dp(0.4f)
        }
        val clipped = TextUtils.ellipsize(text, btnTextPaint, maxW, TextUtils.TruncateAt.END).toString()
        val textY = rect.centerY() - (btnTextPaint.descent() + btnTextPaint.ascent()) * 0.5f
        canvas.drawText(clipped, rect.centerX(), textY, btnTextPaint)
    }

    private fun drawEllipsizedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: TextPaint
    ) {
        if (maxWidth <= 4f) return
        val clipped = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clipped, x, y, paint)
    }
}
