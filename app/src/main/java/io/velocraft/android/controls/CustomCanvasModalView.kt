package io.velocraft.android.controls

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.auth.PlayerSessionManager
import io.velocraft.android.engine.JvmOptimizer
import io.velocraft.android.engine.RendererProfileManager
import io.velocraft.android.util.VeloLogger
import kotlin.math.max

/**
 * 100% Custom Canvas Settings, JVM RAM Allocation Editor, Multi-Renderer Picker,
 * Custom Keybind Creator, Log Viewer & Xyverse Publisher Identity Modal.
 *
 * Published by Xyverse Technology Global (Founder / Director: Haekal Saputra).
 * Strictly zero stock Android widgets and zero emojis.
 */
class CustomCanvasModalView(
    context: Context,
    private val hudView: ProHudOverlayView,
    private val gyroController: GyroAimController,
    private val onCloseModal: () -> Unit
) : View(context) {

    enum class Tab(val title: String) {
        RENDERER_ACCOUNT("RAM, RENDERER & AKUN"),
        CONTROLS_CURSOR("KONTROL & KURSOR"),
        KEYBIND_CREATOR("CUSTOM KEYBIND & HUD"),
        DIAGNOSTIC_LOG("LOG & XYVERSE INFO")
    }

    var activeTab: Tab = Tab.RENDERER_ACCOUNT
        set(value) {
            field = value
            if (value == Tab.DIAGNOSTIC_LOG) {
                logLines = VeloLogger.readLatestLogsTail(14000).lines()
                logScrollY = 0f
            }
            postInvalidateOnAnimation()
        }

    private val sessionManager = PlayerSessionManager(context)
    private var editingUsername: String = sessionManager.getActiveSession().username
    private var bannerStatusText: String =
        "Publisher: Xyverse Technology Global (Founder/Direktur: Haekal Saputra) | Pkg: ${context.packageName}"

    private val xyverseLogoBmp: Bitmap? = try {
        context.assets.open("branding/xyverse_logo_horizontal.png").use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) {
        null
    }

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
    private val cursorToggleRect = RectF()
    private val autoClickToggleRect = RectF()
    private val fastPlaceToggleRect = RectF()
    private val circleCrosshairToggleRect = RectF()
    private val resetHudBtnRect = RectF()

    // Tab 3 Hit Rects
    private val prevBtnSelectRect = RectF()
    private val nextBtnSelectRect = RectF()
    private val addCustomBtnRect = RectF()
    private val deleteCustomBtnRect = RectF()
    private val sizeMinusRect = RectF()
    private val sizePlusRect = RectF()
    private val modeHoldToggleRect = RectF()
    private val keyGridRects = mutableListOf<Pair<Pair<String, Int>, RectF>>()

    // Tab 4 Hit Rects
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
    private val terraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC87D55.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF33312C.toInt() }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(13f)
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD6D2C8.toInt()
        textSize = dp(10.8f)
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9C978C.toInt()
        textSize = dp(9.8f)
    }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(10.2f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6E2D8.toInt()
        textSize = dp(9.8f)
        typeface = Typeface.MONOSPACE
    }

    private val tmpRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

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

        val margin = dp(16f)
        val panelLeft = margin
        val panelTop = margin * 0.7f
        val panelRight = w - margin
        val panelBottom = h - margin * 0.7f

        tmpRect.set(panelLeft, panelTop, panelRight, panelBottom)
        canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), panelPaint)
        canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), borderPaint)

        // Top Tab Bar + Close Button
        val tabBarTop = panelTop + dp(9f)
        val tabH = dp(32f)
        val closeW = dp(82f)
        closeBtnRect.set(panelRight - dp(12f) - closeW, tabBarTop, panelRight - dp(12f), tabBarTop + tabH)
        canvas.drawRoundRect(closeBtnRect, dp(8f), dp(8f), terraPaint)
        canvas.drawText("CLOSE [X]", closeBtnRect.centerX(), closeBtnRect.centerY() + dp(3.8f), btnTextPaint)

        val tabsAreaW = (closeBtnRect.left - panelLeft - dp(24f))
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
            canvas.drawText(tab.title, tabRects[i].centerX(), tabRects[i].centerY() + dp(3.8f), btnTextPaint)
        }

        // Status Sub-banner
        canvas.drawText(bannerStatusText, panelLeft + dp(14f), tabBarTop + tabH + dp(14f), mutedPaint)

        val contentTop = tabBarTop + tabH + dp(22f)
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

        // =========================================================================
        // LEFT COLUMN: Multi-Renderer Selector + Shader Toggle
        // =========================================================================
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

            val prefix = if (isChosen) "[ACTIVE] " else "[SELECT] "
            bodyPaint.isFakeBoldText = isChosen
            canvas.drawText(prefix + backend.displayName, left + dp(8f), rY + dp(13f), bodyPaint)
            bodyPaint.isFakeBoldText = false
            canvas.drawText(backend.shortDesc.take(56), left + dp(8f), rY + dp(25f), mutedPaint)
            rY += itemH + dp(4f)
        }

        // Shader Support Toggle
        val shadersOn = RendererProfileManager.isShaderSupportEnabled(context)
        shaderToggleRect.set(left, rY + dp(2f), left + leftW, rY + dp(31f))
        canvas.drawRoundRect(shaderToggleRect, dp(7f), dp(7f), if (shadersOn) activeGreenPaint else cardPaint)
        val shLabel = if (shadersOn) "SHADER SUPPORT (IRIS + SHADERPACKS): [ON]" else "SHADER SUPPORT (IRIS + SHADERPACKS): [OFF]"
        canvas.drawText(shLabel, shaderToggleRect.centerX(), shaderToggleRect.centerY() + dp(3.8f), btnTextPaint)

        // =========================================================================
        // RIGHT COLUMN: JVM Memory Allocation Slider + Resolution Scale + Username
        // =========================================================================
        val hw = JvmOptimizer.detectHardware(context)
        val modeTag = if (hw.isCustomHeap) "[CUSTOM]" else "[SARAN DEFAULT]"
        canvas.drawText(
            "2. Alokasi Memori JVM: ${hw.allocatedHeapMb} MB $modeTag",
            rightLeft,
            top + dp(11f),
            titlePaint
        )
        canvas.drawText(
            "Saran Default HP ini: ${hw.recommendedHeapMb} MB dari Total ${hw.totalRamMb} MB RAM",
            rightLeft,
            top + dp(24f),
            mutedPaint
        )

        // Interactive RAM Slider + [SARAN DEFAULT] Button
        val sliderW = rightW * 0.62f
        ramSliderRect.set(rightLeft, top + dp(30f), rightLeft + sliderW, top + dp(52f))
        canvas.drawRoundRect(ramSliderRect, dp(6f), dp(6f), trackPaint)
        val ramRatio = ((hw.allocatedHeapMb - hw.minSafeHeapMb).toFloat() /
            (hw.maxSafeHeapMb - hw.minSafeHeapMb).coerceAtLeast(1)).coerceIn(0f, 1f)
        tmpRect.set(ramSliderRect.left, ramSliderRect.top, ramSliderRect.left + sliderW * ramRatio, ramSliderRect.bottom)
        canvas.drawRoundRect(tmpRect, dp(6f), dp(6f), activeGreenPaint)
        canvas.drawText(
            "Geser RAM (${hw.minSafeHeapMb}-${hw.maxSafeHeapMb} MB)",
            ramSliderRect.centerX(),
            ramSliderRect.centerY() + dp(3.5f),
            btnTextPaint
        )

        ramDefaultBtnRect.set(ramSliderRect.right + dp(6f), top + dp(30f), right, top + dp(52f))
        canvas.drawRoundRect(
            ramDefaultBtnRect,
            dp(6f),
            dp(6f),
            if (!hw.isCustomHeap) activeGreenPaint else terraPaint
        )
        canvas.drawText(
            "DEFAULT (${hw.recommendedHeapMb}M)",
            ramDefaultBtnRect.centerX(),
            ramDefaultBtnRect.centerY() + dp(3.5f),
            btnTextPaint
        )

        // Resolution Scale (50% / 75% / 100%) + GC Mode Row
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
            canvas.drawText("RES ${pct}%", r.centerX(), r.centerY() + dp(3.5f), btnTextPaint)
        }
        canvas.drawRoundRect(gcModeToggleRect, dp(5f), dp(5f), if (hw.useGenerationalZgc) activeGreenPaint else cardPaint)
        canvas.drawRoundRect(gcModeToggleRect, dp(5f), dp(5f), borderPaint)
        canvas.drawText(
            if (hw.useGenerationalZgc) "GC: ZGC" else "GC: G1GC",
            gcModeToggleRect.centerX(),
            gcModeToggleRect.centerY() + dp(3.5f),
            btnTextPaint
        )

        // Custom On-Canvas Username Editor
        var kY = rowY + rowH + dp(16f)
        canvas.drawText("3. Akun Pemain (Offline Username): $editingUsername", rightLeft, kY, titlePaint)
        kY += dp(6f)

        charKeyRects.clear()
        val rows = listOf(
            "1234567890_",
            "QWERTYUIOP",
            "ASDFGHJKL",
            "ZXCVBNM"
        )
        val keyH = dp(24f)

        for (row in rows) {
            val keyW = (rightW - dp(3.5f) * (row.length - 1)) / row.length
            for ((cIdx, ch) in row.withIndex()) {
                val kx = rightLeft + cIdx * (keyW + dp(3.5f))
                val r = RectF(kx, kY, kx + keyW, kY + keyH)
                canvas.drawRoundRect(r, dp(5f), dp(5f), cardPaint)
                canvas.drawRoundRect(r, dp(5f), dp(5f), borderPaint)
                canvas.drawText(ch.toString(), r.centerX(), r.centerY() + dp(3.5f), btnTextPaint)
                charKeyRects.add(ch.toString() to r)
            }
            kY += keyH + dp(3.5f)
        }

        // Backspace & Save Username row
        val halfW = (rightW - dp(6f)) * 0.5f
        val bkRect = RectF(rightLeft, kY, rightLeft + halfW, kY + dp(26f))
        val saveUserRect = RectF(rightLeft + halfW + dp(6f), kY, right, kY + dp(26f))
        canvas.drawRoundRect(bkRect, dp(6f), dp(6f), terraPaint)
        canvas.drawRoundRect(saveUserRect, dp(6f), dp(6f), activeGreenPaint)
        canvas.drawText("BACKSPACE [DEL]", bkRect.centerX(), bkRect.centerY() + dp(3.5f), btnTextPaint)
        canvas.drawText("SAVE USERNAME", saveUserRect.centerX(), saveUserRect.centerY() + dp(3.5f), btnTextPaint)
        charKeyRects.add("BACKSPACE" to bkRect)
        charKeyRects.add("SAVE_USER" to saveUserRect)
    }

    private fun drawControlsAndCursorTab(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        val totalW = right - left
        var y = top + dp(14f)

        // 1. Touch Camera Sensitivity Slider
        val touchPct = (hudView.touchLookSensitivity * 100).toInt()
        canvas.drawText("Touch Camera Sensitivity: $touchPct% (Geser bar di bawah)", left, y, titlePaint)
        y += dp(8f)
        touchSensSliderRect.set(left, y, left + totalW, y + dp(24f))
        canvas.drawRoundRect(touchSensSliderRect, dp(8f), dp(8f), trackPaint)
        val touchFillW = totalW * ((touchPct - 15f) / 185f).coerceIn(0f, 1f)
        tmpRect.set(left, y, left + touchFillW, y + dp(24f))
        canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), activeGreenPaint)

        // 2. Gyroscope Sensitivity Slider
        y += dp(40f)
        val gyroVal = gyroController.sensitivityX.toInt()
        canvas.drawText("Gyroscope Sensitivity (200Hz): ${gyroVal}x", left, y, titlePaint)
        y += dp(8f)
        gyroSensSliderRect.set(left, y, left + totalW, y + dp(24f))
        canvas.drawRoundRect(gyroSensSliderRect, dp(8f), dp(8f), trackPaint)
        val gyroFillW = totalW * (gyroVal / 30f).coerceIn(0f, 1f)
        tmpRect.set(left, y, left + gyroFillW, y + dp(24f))
        canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), activeGreenPaint)

        // 3. 2x2 Grid of Toggle Buttons
        y += dp(38f)
        val halfW = (totalW - dp(12f)) * 0.5f
        val btnH = dp(36f)

        cursorToggleRect.set(left, y, left + halfW, y + btnH)
        autoClickToggleRect.set(left + halfW + dp(12f), y, right, y + btnH)

        val r2Y = y + btnH + dp(10f)
        fastPlaceToggleRect.set(left, r2Y, left + halfW, r2Y + btnH)
        circleCrosshairToggleRect.set(left + halfW + dp(12f), r2Y, right, r2Y + btnH)

        drawToggleBox(canvas, cursorToggleRect, "SHOW VIRTUAL CURSOR", hudView.isVirtualCursorEnabled)
        drawToggleBox(canvas, autoClickToggleRect, "HOLD ATTACK AUTO-CLICK (14 CPS)", hudView.holdAutoClickEnabled)
        drawToggleBox(canvas, fastPlaceToggleRect, "FASTPLACE / BRIDGING (15 CPS)", hudView.macroEngine.fastPlaceEnabled)
        drawToggleBox(canvas, circleCrosshairToggleRect, "CIRCLE + DOT PRO CROSSHAIR", hudView.useProCircleCrosshair)

        // 4. Reset HUD Layout Button
        val r3Y = r2Y + btnH + dp(12f)
        resetHudBtnRect.set(left, r3Y, right, r3Y + dp(36f))
        canvas.drawRoundRect(resetHudBtnRect, dp(8f), dp(8f), terraPaint)
        canvas.drawText("RESET DEFAULT HUD LAYOUT", resetHudBtnRect.centerX(), resetHudBtnRect.centerY() + dp(4f), btnTextPaint)
    }

    private fun drawToggleBox(canvas: Canvas, rect: RectF, label: String, active: Boolean) {
        canvas.drawRoundRect(rect, dp(8f), dp(8f), if (active) activeGreenPaint else cardPaint)
        canvas.drawRoundRect(rect, dp(8f), dp(8f), borderPaint)
        val state = if (active) "[ON]" else "[OFF]"
        canvas.drawText("$label: $state", rect.centerX(), rect.centerY() + dp(4f), btnTextPaint)
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

        val barH = dp(32f)
        var xCur = left
        prevBtnSelectRect.set(xCur, top, xCur + dp(46f), top + barH)
        xCur += dp(52f)
        nextBtnSelectRect.set(xCur, top, xCur + dp(46f), top + barH)
        xCur += dp(54f)

        val infoW = dp(185f)
        tmpRect.set(xCur, top, xCur + infoW, top + barH)
        canvas.drawRoundRect(tmpRect, dp(7f), dp(7f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText(
            "Btn [${selectedButtonIndex + 1}/${buttons.size}]: ${currentBtn.label}",
            tmpRect.centerX(),
            tmpRect.centerY() + dp(3.8f),
            btnTextPaint
        )
        xCur += infoW + dp(8f)

        sizeMinusRect.set(xCur, top, xCur + dp(48f), top + barH)
        xCur += dp(52f)
        sizePlusRect.set(xCur, top, xCur + dp(48f), top + barH)
        xCur += dp(54f)

        modeHoldToggleRect.set(xCur, top, xCur + dp(94f), top + barH)
        xCur += dp(100f)

        addCustomBtnRect.set(xCur, top, xCur + dp(76f), top + barH)
        xCur += dp(82f)
        deleteCustomBtnRect.set(xCur, top, right.coerceAtMost(xCur + dp(72f)), top + barH)

        for ((r, lbl) in listOf(
            prevBtnSelectRect to "< PREV",
            nextBtnSelectRect to "NEXT >",
            sizeMinusRect to "SIZE -",
            sizePlusRect to "SIZE +",
            modeHoldToggleRect to if (currentBtn.isToggle) "MODE: TOGGLE" else "MODE: HOLD"
        )) {
            canvas.drawRoundRect(r, dp(7f), dp(7f), cardPaint)
            canvas.drawRoundRect(r, dp(7f), dp(7f), borderPaint)
            canvas.drawText(lbl, r.centerX(), r.centerY() + dp(3.8f), btnTextPaint)
        }

        canvas.drawRoundRect(addCustomBtnRect, dp(7f), dp(7f), activeGreenPaint)
        canvas.drawText("+ ADD KEY", addCustomBtnRect.centerX(), addCustomBtnRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(deleteCustomBtnRect, dp(7f), dp(7f), terraPaint)
        canvas.drawText("DELETE", deleteCustomBtnRect.centerX(), deleteCustomBtnRect.centerY() + dp(3.8f), btnTextPaint)

        val gridTop = top + barH + dp(8f)
        canvas.drawText(
            "Ketuk tombol Keyboard PC di bawah untuk di-bind ke tombol '${currentBtn.label}' (Ukuran: ${currentBtn.radiusDp.toInt()}dp):",
            left,
            gridTop + dp(10f),
            mutedPaint
        )

        keyGridRects.clear()
        val allKeys = MacroEngine.BINDABLE_KEYS.entries.toList()
        val cols = 12
        val gap = dp(4f)
        val cellW = ((right - left) - gap * (cols - 1)) / cols
        val cellH = dp(26f)
        val startY = gridTop + dp(16f)

        for ((idx, entry) in allKeys.withIndex()) {
            val col = idx % cols
            val row = idx / cols
            val cx = left + col * (cellW + gap)
            val cy = startY + row * (cellH + gap)
            if (cy + cellH > bottom) break

            val r = RectF(cx, cy, cx + cellW, cy + cellH)
            val isBound = (currentBtn.keyCodeOrButton == entry.value)
            canvas.drawRoundRect(r, dp(5f), dp(5f), if (isBound) activeGreenPaint else cardPaint)
            canvas.drawRoundRect(r, dp(5f), dp(5f), borderPaint)
            canvas.drawText(entry.key, r.centerX(), r.centerY() + dp(3.5f), btnTextPaint)
            keyGridRects.add((entry.key to entry.value) to r)
        }
    }

    private fun drawDiagnosticLogTab(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) {
        // Top Official Publisher Banner Card
        val pubCardH = dp(48f)
        tmpRect.set(left, top, right, top + pubCardH)
        canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), borderPaint)

        var textStartX = left + dp(12f)
        xyverseLogoBmp?.let { logo ->
            val lH = dp(32f)
            val lW = lH * (logo.width.toFloat() / logo.height.coerceAtLeast(1))
            srcRect.set(0, 0, logo.width, logo.height)
            dstRect.set(textStartX, top + dp(8f), textStartX + lW, top + dp(8f) + lH)
            canvas.drawBitmap(logo, srcRect, dstRect, bitmapPaint)
            textStartX += lW + dp(14f)
        }

        canvas.drawText(
            "XYVERSE TECHNOLOGY GLOBAL - Founder / Direktur: Haekal Saputra",
            textStartX,
            top + dp(19f),
            titlePaint
        )
        canvas.drawText(
            "Official Signed Package: ${context.packageName} | Cert Subject: CN=Haekal Saputra, O=Xyverse Technology Global, C=ID",
            textStartX,
            top + dp(36f),
            mutedPaint
        )

        val logBarTop = top + pubCardH + dp(8f)
        val btnW = dp(120f)
        val btnH = dp(28f)
        copyLogBtnRect.set(right - btnW, logBarTop, right, logBarTop + btnH)
        refreshLogBtnRect.set(right - btnW * 2f - dp(8f), logBarTop, right - btnW - dp(8f), logBarTop + btnH)

        canvas.drawRoundRect(refreshLogBtnRect, dp(7f), dp(7f), cardPaint)
        canvas.drawRoundRect(refreshLogBtnRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText("REFRESH LOG", refreshLogBtnRect.centerX(), refreshLogBtnRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(copyLogBtnRect, dp(7f), dp(7f), activeGreenPaint)
        canvas.drawText("COPY LOG", copyLogBtnRect.centerX(), copyLogBtnRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawText(
            "File Log: ${VeloLogger.latestLogFile?.absolutePath ?: "latest.log"} (Geser untuk scroll)",
            left,
            logBarTop + dp(18f),
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
        if (touchSensSliderRect.contains(x, y)) {
            val ratio = ((x - touchSensSliderRect.left) / touchSensSliderRect.width()).coerceIn(0.08f, 1f)
            hudView.touchLookSensitivity = 0.15f + ratio * 1.85f
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
            bannerStatusText = "Resolusi Render diatur ke 50% (Ultra High FPS untuk HP Kentang)."
            return
        }
        if (resScale75Rect.contains(x, y)) {
            JvmOptimizer.setResolutionScalePct(context, 75)
            bannerStatusText = "Resolusi Render diatur ke 75% (Balanced)."
            return
        }
        if (resScale100Rect.contains(x, y)) {
            JvmOptimizer.setResolutionScalePct(context, 100)
            bannerStatusText = "Resolusi Render diatur ke 100% (Native Tajam)."
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
                    "BACKSPACE" -> {
                        if (editingUsername.isNotEmpty()) {
                            editingUsername = editingUsername.dropLast(1)
                        }
                    }
                    "SAVE_USER" -> {
                        val saved = sessionManager.saveOfflineSession(editingUsername)
                        editingUsername = saved.username
                        bannerStatusText = "Username disimpan: ${saved.username} (UUID: ${saved.uuid.take(8)}...)"
                        VeloLogger.section("AUTH", "Saved Offline Username: ${saved.username} (UUID=${saved.uuid})")
                    }
                    else -> {
                        if (editingUsername.length < 16) {
                            editingUsername += key
                        }
                    }
                }
                return
            }
        }
    }

    private fun handleControlsTabTap(x: Float, y: Float) {
        when {
            cursorToggleRect.contains(x, y) -> {
                hudView.toggleVirtualCursor()
            }
            autoClickToggleRect.contains(x, y) -> {
                hudView.holdAutoClickEnabled = !hudView.holdAutoClickEnabled
            }
            fastPlaceToggleRect.contains(x, y) -> {
                hudView.macroEngine.fastPlaceEnabled = !hudView.macroEngine.fastPlaceEnabled
            }
            circleCrosshairToggleRect.contains(x, y) -> {
                hudView.useProCircleCrosshair = !hudView.useProCircleCrosshair
                hudView.postInvalidateOnAnimation()
            }
            resetHudBtnRect.contains(x, y) -> {
                hudView.resetToDefaultLayout()
                bannerStatusText = "Layout tombol HUD dikembalikan ke posisi default."
            }
        }
    }

    private fun handleKeybindTabTap(x: Float, y: Float) {
        val buttons = hudView.getButtonsList()
        if (buttons.isEmpty()) return
        selectedButtonIndex = selectedButtonIndex.coerceIn(0, buttons.lastIndex)
        val currentBtn = buttons[selectedButtonIndex]

        when {
            prevBtnSelectRect.contains(x, y) -> {
                selectedButtonIndex = (selectedButtonIndex + buttons.size - 1) % buttons.size
            }
            nextBtnSelectRect.contains(x, y) -> {
                selectedButtonIndex = (selectedButtonIndex + 1) % buttons.size
            }
            sizeMinusRect.contains(x, y) -> {
                currentBtn.radiusDp = (currentBtn.radiusDp - 3f).coerceAtLeast(14f)
                hudView.saveCurrentLayout()
            }
            sizePlusRect.contains(x, y) -> {
                currentBtn.radiusDp = (currentBtn.radiusDp + 3f).coerceAtMost(60f)
                hudView.saveCurrentLayout()
            }
            modeHoldToggleRect.contains(x, y) -> {
                currentBtn.isToggle = !currentBtn.isToggle
                currentBtn.actionType = if (currentBtn.isToggle) ActionType.KEY_TOGGLE else ActionType.KEY_HOLD
                hudView.saveCurrentLayout()
            }
            addCustomBtnRect.contains(x, y) -> {
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
                selectedButtonIndex = buttons.lastIndex
                hudView.saveCurrentLayout()
                bannerStatusText = "Tombol baru dibuat. Pilih key di bawah lalu atur posisinya di EDIT HUD."
            }
            deleteCustomBtnRect.contains(x, y) -> {
                if (buttons.size > 4 && currentBtn.id != "btn_settings" && currentBtn.id != "btn_edit_hud") {
                    buttons.removeAt(selectedButtonIndex)
                    selectedButtonIndex = selectedButtonIndex.coerceIn(0, buttons.lastIndex)
                    hudView.saveCurrentLayout()
                    bannerStatusText = "Tombol dihapus."
                } else {
                    bannerStatusText = "Tombol sistem utama (CFG / EDIT HUD) tidak dapat dihapus."
                }
            }
            else -> {
                for ((entry, rect) in keyGridRects) {
                    if (rect.contains(x, y)) {
                        currentBtn.keyCodeOrButton = entry.second
                        currentBtn.label = entry.first
                        if (currentBtn.actionType != ActionType.KEY_TOGGLE) {
                            currentBtn.actionType = ActionType.KEY_HOLD
                        }
                        hudView.saveCurrentLayout()
                        bannerStatusText = "Tombol di-bind ke key '${entry.first}' (GLFW code ${entry.second})."
                        return
                    }
                }
            }
        }
    }

    private fun handleLogTabTap(x: Float, y: Float) {
        if (refreshLogBtnRect.contains(x, y)) {
            logLines = VeloLogger.readLatestLogsTail(14000).lines()
            bannerStatusText = "Log diperbarui (${logLines.size} baris)."
        } else if (copyLogBtnRect.contains(x, y)) {
            val fullText = VeloLogger.readLatestLogsTail(25000)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("XyverseMinecraftLog", fullText))
            bannerStatusText = "[COPIED] Isi latest.log berhasil disalin ke Clipboard!"
        }
    }
}
