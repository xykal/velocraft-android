package io.velocraft.android.engine

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.auth.PlayerSessionManager
import io.velocraft.android.controls.ControlProfileManager
import io.velocraft.android.util.SystemImeHelper
import io.velocraft.android.util.VeloLogger
import kotlin.math.max

/**
 * Clean, Icon-Enhanced 6-Page Studio Launcher Console for Minecraft: Java Edition (1.20.4 Fabric).
 *
 * Features:
 * - Left Navigation Sidebar with custom geometric vector icons (Canvas Path, zero emojis)
 * - Scrollable content support across Modrinth Sync, Controls/JSON, and Logs
 * - Page 3: Built-in Mod/Shader Manager + Live Modrinth v2 Online Mod Search & Sync
 * - Page 4: Visual HUD Editor, Keybind PC/Gamepad, Sensitivity/Cursor, & Share/Import Control .JSON
 * - Page 5: Microsoft Official Account (OAuth2 Device Flow) + Offline Account via Android System IME Keyboard
 */
class MainMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class NavPage(val label: String, val iconType: Int) {
        HOME("BERANDA", 0),
        MEMORY_ENGINE("MEMORI & ENGINE", 1),
        MODS_SHADERS("MODS & MODRINTH", 2),
        CONTROLS_HUD("KONTROL & JSON", 3),
        ACCOUNT("AKUN PEMAIN", 4),
        LOGS("LOG DIAGNOSTIK", 5)
    }

    // Callbacks wired by GameActivity
    var onStartOrResumeDownload: ((skipMusicLite: Boolean) -> Unit)? = null
    var onLaunchGameRequested: (() -> Unit)? = null
    var onRequestOverlayPermission: (() -> Unit)? = null
    var onOpenQuickSettings: (() -> Unit)? = null
    var onOpenRendererAndKeybindModal: (() -> Unit)? = null
    var onOpenVisualHudEditor: (() -> Unit)? = null
    var onControlJsonReloadRequested: (() -> Unit)? = null

    private fun sanitizeLogLines(rawLines: List<String>): List<String> {
        return rawLines.map { raw ->
            raw.replace("libpojavexec.so", "libvelocraft_bridge.so", ignoreCase = true)
                .replace("pojavexec", "velocraft_bridge", ignoreCase = true)
                .replace("pojavInitOpenGL", "initOpenGLContext", ignoreCase = true)
                .replace("pojavInit", "initBridge", ignoreCase = true)
                .replace("Pojav", "VeloCraft", ignoreCase = true)
        }
    }

    private var activePage: NavPage = NavPage.HOME
        set(value) {
            field = value
            pageScrollY = 0f
            if (value == NavPage.LOGS) {
                logLines = sanitizeLogLines(VeloLogger.readLatestLogsTail(15000).lines())
                logScrollY = 0f
            } else if (value == NavPage.MODS_SHADERS && modrinthCatalog.isEmpty() && ModrinthSyncManager.activeDownloadSlug == null) {
                triggerModrinthFetch(modrinthQuery)
            }
            postInvalidateOnAnimation()
        }

    private val sessionManager = PlayerSessionManager(context)
    private var editingUsername: String = AccountManager.getActiveAccount(context).username
    private var skipMusicLite: Boolean = ResumableDownloadEngine.isSkipMusicLiteEnabled(context)
    private var readiness = ResumableDownloadEngine.inspectEngineReadiness(context)
    private var downloadState = EngineDownloadService.latestState

    // Scroll states
    private var pageScrollY: Float = 0f
    private var pageContentHeight: Float = 0f
    private var logLines: List<String> = emptyList()
    private var logScrollY: Float = 0f
    private var lastTouchY: Float = 0f
    private var touchDownY: Float = 0f
    private var isDraggingScroll: Boolean = false

    // Page 3 Sub-Mode: false = Built-in Mods & Shaders, true = Live Modrinth Sync & Search
    private var isModrinthOnlineTab: Boolean = false
    private var modrinthQuery: String = ""
    private var modrinthCatalog: List<ModrinthSyncManager.ModrinthProjectItem> = emptyList()

    // Assets
    private val markBmp: Bitmap? = loadAssetBitmap("branding/xyverse_mark.png")
    private val mascotBmp: Bitmap? = loadAssetBitmap("branding/xyverse_mascot.png")
    private val worldBannerBmp: Bitmap? = loadAssetBitmap("branding/xyverse_world_banner.jpg")

    // In-Canvas Notification Toast Banner
    private var bannerMessage: String? = null
    private var bannerExpireAtMs: Long = 0L

    // Navigation Sidebar Hit Rects
    private val navRects = Array(NavPage.values().size) { RectF() }

    // Page 1 (HOME) Hit Rects
    private val homeLiteModeRect = RectF()
    private val homeFullModeRect = RectF()
    private val homePrimaryActionRect = RectF()
    private val homeQuickAccountRect = RectF()
    private val homeQuickRamRect = RectF()

    // Page 2 (MEMORY & ENGINE) Hit Rects
    private val ramSliderRect = RectF()
    private val ramMinusBtnRect = RectF()
    private val ramDefaultBtnRect = RectF()
    private val ramPlusBtnRect = RectF()
    private val ramPresetRects = mutableListOf<Pair<Int, RectF>>()
    private val rendererItemRects = Array(RendererProfileManager.RendererBackend.values().size) { RectF() }
    private val res50Rect = RectF()
    private val res75Rect = RectF()
    private val res100Rect = RectF()
    private val gcG1Rect = RectF()
    private val gcZgcRect = RectF()

    // Page 3 (MODS, SHADERS & MODRINTH) Hit Rects
    private val modsSubTabLocalRect = RectF()
    private val modsSubTabModrinthRect = RectF()
    private val modCardRects = mutableListOf<Pair<String, RectF>>()
    private val shaderToggleRect = RectF()
    private val shaderCycleRect = RectF()
    private val shaderSyncRect = RectF()
    private val modrinthSearchInputRect = RectF()
    private val modrinthSearchBtnRect = RectF()
    private val modrinthSyncAllBtnRect = RectF()
    private val modrinthItemRects = mutableListOf<Pair<ModrinthSyncManager.ModrinthProjectItem, RectF>>()

    // Page 4 (CONTROLS, HUD & JSON) Hit Rects
    private val ctrlVisualHudBtnRect = RectF()
    private val ctrlKeybindModalBtnRect = RectF()
    private val ctrlSettingsModalBtnRect = RectF()
    private val ctrlOverlayPermBtnRect = RectF()
    private val ctrlExportShareJsonRect = RectF()
    private val ctrlCopyJsonClipRect = RectF()
    private val ctrlImportJsonClipRect = RectF()

    // Page 5 (ACCOUNT: MICROSOFT & OFFLINE + SYSTEM IME) Hit Rects
    private val accountActionRects = mutableListOf<Pair<String, RectF>>()

    // Page 6 (LOGS) Hit Rects
    private val logRefreshRect = RectF()
    private val logCopyRect = RectF()

    // Clean Matte Non-Neon Studio Palette
    private val bgPaint = Paint().apply { color = 0xFF121211.toInt() }
    private val sidebarPaint = Paint().apply { color = 0xFF181715.toInt() }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1D1A.toInt() }
    private val cardElevatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262420.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF33312B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4E8C57.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
    }
    private val accentGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4E8C57.toInt() }
    private val accentTerraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC87D55.toInt() }
    private val accentSlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A5268.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2B26.toInt() }
    private val scrimPaint = Paint().apply { color = 0xB8121211.toInt() }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val vectorIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val vectorPath = Path()

    // Typography
    private val h1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(16f)
        isFakeBoldText = true
    }
    private val h2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEAE6DC.toInt()
        textSize = dp(12.2f)
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD4CFC4.toInt()
        textSize = dp(10.8f)
    }
    private val bodyBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(10.8f)
        isFakeBoldText = true
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF969186.toInt()
        textSize = dp(9.6f)
    }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(10.4f)
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
    private val bannerRect = RectF()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun loadAssetBitmap(assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fitText(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(text) <= maxWidth) return text
        val ellipsis = "..."
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText(ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    private fun drawFittedText(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint) {
        canvas.drawText(fitText(text, paint, maxWidth), x, y, paint)
    }

    fun showBannerMessage(message: String, durationMs: Long = 4000L) {
        bannerMessage = message
        bannerExpireAtMs = System.currentTimeMillis() + durationMs
        postInvalidateOnAnimation()
    }

    private fun triggerModrinthFetch(query: String) {
        ModrinthSyncManager.searchModsOnModrinth(context, query) {
            modrinthCatalog = ModrinthSyncManager.getCatalogWithDiskState(context)
            postInvalidateOnAnimation()
        }
    }

    fun refreshState(newState: ResumableDownloadEngine.DownloadProgressState? = null) {
        if (newState != null) {
            downloadState = newState
        } else {
            downloadState = EngineDownloadService.latestState
        }
        readiness = ResumableDownloadEngine.inspectEngineReadiness(context)
        if (readiness.isReadyToLaunch && !downloadState.isRunning) {
            EngineDownloadService.dismissAllNotifications(context)
        }
        skipMusicLite = ResumableDownloadEngine.isSkipMusicLiteEnabled(context)
        postInvalidateOnAnimation()
    }

    /**
     * Draws clean geometric vector icons for navigation sidebar items & cards (zero emojis).
     */
    private fun drawNavVectorIcon(canvas: Canvas, iconType: Int, cx: Float, cy: Float, r: Float, color: Int) {
        vectorIconPaint.color = color
        vectorIconPaint.style = Paint.Style.STROKE
        vectorIconPaint.strokeWidth = dp(1.5f)
        vectorPath.reset()
        when (iconType) {
            0 -> {
                // HOME: House roof + walls
                vectorPath.moveTo(cx - r, cy - r * 0.1f)
                vectorPath.lineTo(cx, cy - r * 0.95f)
                vectorPath.lineTo(cx + r, cy - r * 0.1f)
                vectorPath.moveTo(cx - r * 0.72f, cy - r * 0.2f)
                vectorPath.lineTo(cx - r * 0.72f, cy + r * 0.85f)
                vectorPath.lineTo(cx + r * 0.72f, cy + r * 0.85f)
                vectorPath.lineTo(cx + r * 0.72f, cy - r * 0.2f)
                canvas.drawPath(vectorPath, vectorIconPaint)
            }
            1 -> {
                // MEMORY & ENGINE: CPU Chip + pins
                tmpRect.set(cx - r * 0.65f, cy - r * 0.65f, cx + r * 0.65f, cy + r * 0.65f)
                canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), vectorIconPaint)
                canvas.drawLine(cx - r, cy - r * 0.3f, cx - r * 0.65f, cy - r * 0.3f, vectorIconPaint)
                canvas.drawLine(cx - r, cy + r * 0.3f, cx - r * 0.65f, cy + r * 0.3f, vectorIconPaint)
                canvas.drawLine(cx + r * 0.65f, cy - r * 0.3f, cx + r, cy - r * 0.3f, vectorIconPaint)
                canvas.drawLine(cx + r * 0.65f, cy + r * 0.3f, cx + r, cy + r * 0.3f, vectorIconPaint)
            }
            2 -> {
                // MODS & MODRINTH: Isometric Cube / Puzzle
                vectorPath.moveTo(cx, cy - r * 0.95f)
                vectorPath.lineTo(cx + r * 0.85f, cy - r * 0.45f)
                vectorPath.lineTo(cx + r * 0.85f, cy + r * 0.45f)
                vectorPath.lineTo(cx, cy + r * 0.95f)
                vectorPath.lineTo(cx - r * 0.85f, cy + r * 0.45f)
                vectorPath.lineTo(cx - r * 0.85f, cy - r * 0.45f)
                vectorPath.close()
                vectorPath.moveTo(cx, cy - r * 0.95f)
                vectorPath.lineTo(cx, cy + r * 0.95f)
                canvas.drawPath(vectorPath, vectorIconPaint)
            }
            3 -> {
                // CONTROLS & JSON: Sliders / Cross D-Pad
                canvas.drawLine(cx - r * 0.85f, cy - r * 0.45f, cx + r * 0.85f, cy - r * 0.45f, vectorIconPaint)
                canvas.drawCircle(cx - r * 0.25f, cy - r * 0.45f, r * 0.24f, vectorIconPaint)
                canvas.drawLine(cx - r * 0.85f, cy + r * 0.45f, cx + r * 0.85f, cy + r * 0.45f, vectorIconPaint)
                canvas.drawCircle(cx + r * 0.3f, cy + r * 0.45f, r * 0.24f, vectorIconPaint)
            }
            4 -> {
                // ACCOUNT: User Head + Shoulders
                canvas.drawCircle(cx, cy - r * 0.35f, r * 0.38f, vectorIconPaint)
                tmpRect.set(cx - r * 0.8f, cy + r * 0.15f, cx + r * 0.8f, cy + r * 1.1f)
                canvas.drawArc(tmpRect, 180f, 180f, false, vectorIconPaint)
            }
            5 -> {
                // LOGS: Terminal Prompt (> _)
                vectorPath.moveTo(cx - r * 0.75f, cy - r * 0.45f)
                vectorPath.lineTo(cx - r * 0.2f, cy)
                vectorPath.lineTo(cx - r * 0.75f, cy + r * 0.45f)
                vectorPath.moveTo(cx + r * 0.05f, cy + r * 0.45f)
                vectorPath.lineTo(cx + r * 0.75f, cy + r * 0.45f)
                canvas.drawPath(vectorPath, vectorIconPaint)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // =========================================================================
        // 1. LEFT NAVIGATION SIDEBAR WITH VECTOR ICONS
        // =========================================================================
        val sidebarW = dp(172f).coerceAtMost(w * 0.25f)
        canvas.drawRect(0f, 0f, sidebarW, h, sidebarPaint)
        canvas.drawLine(sidebarW, 0f, sidebarW, h, borderPaint)

        val sbPad = dp(11f)
        var sbY = dp(14f)

        // Clean Brand Header inside Sidebar
        markBmp?.let { bmp ->
            val iconSz = dp(28f)
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(sbPad, sbY, sbPad + iconSz, sbY + iconSz)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            drawFittedText(canvas, "MINECRAFT", sbPad + iconSz + dp(8f), sbY + dp(13f), sidebarW - iconSz - dp(22f), bodyBoldPaint)
            drawFittedText(canvas, "1.20.4 Fabric", sbPad + iconSz + dp(8f), sbY + dp(26f), sidebarW - iconSz - dp(22f), mutedPaint)
        } ?: run {
            drawFittedText(canvas, "MINECRAFT 1.20.4", sbPad, sbY + dp(16f), sidebarW - sbPad * 2f, bodyBoldPaint)
        }

        sbY += dp(38f)
        canvas.drawLine(sbPad, sbY, sidebarW - sbPad, sbY, borderPaint)
        sbY += dp(9f)

        val navBtnH = ((h - sbY - dp(36f)) / NavPage.values().size).coerceIn(dp(30f), dp(40f))
        for ((idx, page) in NavPage.values().withIndex()) {
            val top = sbY + idx * (navBtnH + dp(4f))
            navRects[idx].set(sbPad, top, sidebarW - sbPad, top + navBtnH - dp(2f))
            val isSelected = (activePage == page)
            canvas.drawRoundRect(
                navRects[idx],
                dp(7f),
                dp(7f),
                if (isSelected) accentGreenPaint else cardPaint
            )
            if (isSelected) {
                dstRect.set(
                    navRects[idx].left + dp(3f),
                    navRects[idx].top + dp(5f),
                    navRects[idx].left + dp(6f),
                    navRects[idx].bottom - dp(5f)
                )
                bodyBoldPaint.color = 0xFFF4F1EA.toInt()
                canvas.drawRoundRect(dstRect, dp(2f), dp(2f), bodyBoldPaint)
            } else {
                canvas.drawRoundRect(navRects[idx], dp(7f), dp(7f), borderPaint)
            }

            // Custom Vector Icon on Left of Sidebar Button
            val iconCx = navRects[idx].left + dp(17f)
            val iconCy = navRects[idx].centerY()
            drawNavVectorIcon(
                canvas,
                page.iconType,
                iconCx,
                iconCy,
                dp(6.5f),
                if (isSelected) 0xFFF4F1EA.toInt() else 0xFFB0ABA0.toInt()
            )

            btnTextPaint.textAlign = Paint.Align.LEFT
            drawFittedText(
                canvas,
                page.label,
                navRects[idx].left + dp(30f),
                navRects[idx].centerY() + dp(3.6f),
                navRects[idx].width() - dp(36f),
                btnTextPaint
            )
            btnTextPaint.textAlign = Paint.Align.CENTER
        }

        // Subtle version footer at bottom of sidebar
        drawFittedText(
            canvas,
            "v0.7.8-release | ${if (android.os.Process.is64Bit()) "ARM64" else "ARM32"}",
            sbPad,
            h - dp(11f),
            sidebarW - sbPad * 2f,
            mutedPaint
        )

        // =========================================================================
        // 2. MAIN WORKSPACE CONTENT AREA
        // =========================================================================
        val contentLeft = sidebarW + dp(15f)
        val contentRight = w - dp(15f)
        val contentTop = dp(13f)
        val contentBottom = h - dp(13f)

        when (activePage) {
            NavPage.HOME -> drawHomePage(canvas, contentLeft, contentTop, contentRight, contentBottom)
            NavPage.MEMORY_ENGINE -> drawMemoryAndEnginePage(canvas, contentLeft, contentTop, contentRight, contentBottom)
            NavPage.MODS_SHADERS -> drawModsAndShadersPage(canvas, contentLeft, contentTop, contentRight, contentBottom)
            NavPage.CONTROLS_HUD -> drawControlsAndHudPage(canvas, contentLeft, contentTop, contentRight, contentBottom)
            NavPage.ACCOUNT -> drawAccountPage(canvas, contentLeft, contentTop, contentRight, contentBottom)
            NavPage.LOGS -> drawLogsPage(canvas, contentLeft, contentTop, contentRight, contentBottom)
        }

        // Top In-Canvas Notification Toast Banner
        val msg = bannerMessage
        if (msg != null && System.currentTimeMillis() < bannerExpireAtMs) {
            val bw = (contentRight - contentLeft).coerceAtMost(dp(580f))
            val bLeft = contentLeft + ((contentRight - contentLeft) - bw) * 0.5f
            bannerRect.set(bLeft, dp(10f), bLeft + bw, dp(42f))
            canvas.drawRoundRect(bannerRect, dp(8f), dp(8f), accentGreenPaint)
            canvas.drawRoundRect(bannerRect, dp(8f), dp(8f), borderPaint)
            drawFittedText(canvas, msg, bannerRect.left + dp(14f), bannerRect.centerY() + dp(4f), bw - dp(28f), bodyBoldPaint)
        }
    }

    // =========================================================================
    // PAGE 1: BERANDA (HERO BANNER + STATUS GRID + LAUNCH / DOWNLOAD CONSOLE)
    // =========================================================================
    private fun drawHomePage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val totalH = bottom - top
        val hw = JvmOptimizer.detectHardware(context)
        val effectiveRenderer = RendererProfileManager.resolveEffectiveBackend(context)
        val activeAcc = AccountManager.getActiveAccount(context)

        // 1. Top Hero Banner Card (36% of height)
        val heroH = (totalH * 0.37f).coerceIn(dp(96f), dp(142f))
        tmpRect.set(left, top, right, top + heroH)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)

        worldBannerBmp?.let { bBmp ->
            srcRect.set(0, (bBmp.height * 0.22f).toInt(), bBmp.width, (bBmp.height * 0.78f).toInt())
            canvas.save()
            canvas.clipRect(tmpRect)
            canvas.drawBitmap(bBmp, srcRect, tmpRect, bitmapPaint)
            canvas.drawRect(tmpRect, scrimPaint)
            canvas.restore()
        }
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), borderPaint)

        var heroTextLeft = left + dp(16f)
        mascotBmp?.let { mBmp ->
            val mSz = (heroH - dp(22f)).coerceIn(dp(64f), dp(104f))
            srcRect.set(0, 0, mBmp.width, mBmp.height)
            dstRect.set(left + dp(12f), top + (heroH - mSz) * 0.5f, left + dp(12f) + mSz, top + (heroH + mSz) * 0.5f)
            canvas.drawBitmap(mBmp, srcRect, dstRect, bitmapPaint)
            canvas.drawRoundRect(dstRect, dp(8f), dp(8f), borderPaint)
            heroTextLeft = dstRect.right + dp(14f)
        }

        val maxHeroTextW = (right - dp(16f)) - heroTextLeft
        drawFittedText(
            canvas,
            "Minecraft: Java Edition 1.20.4 (Fabric)",
            heroTextLeft,
            top + dp(26f),
            maxHeroTextW,
            h1Paint
        )
        drawFittedText(
            canvas,
            "Renderer: ${effectiveRenderer.displayName}  |  Resolusi: ${hw.resolutionScalePct}%  |  Akun: ${activeAcc.type.label}",
            heroTextLeft,
            top + dp(45f),
            maxHeroTextW,
            bodyPaint
        )

        val pillY = top + heroH - dp(36f)
        val pillH = dp(26f)
        val pillW = ((maxHeroTextW - dp(10f)) * 0.5f).coerceAtMost(dp(235f))

        homeQuickAccountRect.set(heroTextLeft, pillY, heroTextLeft + pillW, pillY + pillH)
        canvas.drawRoundRect(homeQuickAccountRect, dp(6f), dp(6f), cardElevatedPaint)
        canvas.drawRoundRect(homeQuickAccountRect, dp(6f), dp(6f), borderPaint)
        drawNavVectorIcon(canvas, 4, homeQuickAccountRect.left + dp(12f), homeQuickAccountRect.centerY(), dp(5.5f), 0xFFF4F1EA.toInt())
        drawFittedText(
            canvas,
            "${activeAcc.username} (${if (activeAcc.type == AccountManager.AccountType.MICROSOFT) "MSA" else "Offline"}) [Ubah]",
            homeQuickAccountRect.left + dp(23f),
            homeQuickAccountRect.centerY() + dp(3.8f),
            pillW - dp(30f),
            bodyBoldPaint
        )

        homeQuickRamRect.set(homeQuickAccountRect.right + dp(8f), pillY, homeQuickAccountRect.right + dp(8f) + pillW, pillY + pillH)
        canvas.drawRoundRect(homeQuickRamRect, dp(6f), dp(6f), cardElevatedPaint)
        canvas.drawRoundRect(homeQuickRamRect, dp(6f), dp(6f), borderPaint)
        drawNavVectorIcon(canvas, 1, homeQuickRamRect.left + dp(12f), homeQuickRamRect.centerY(), dp(5.5f), 0xFFF4F1EA.toInt())
        val ramTag = if (hw.isCustomHeap) "Custom" else "Saran"
        drawFittedText(
            canvas,
            "RAM: ${hw.allocatedHeapMb} MB ($ramTag) [Atur]",
            homeQuickRamRect.left + dp(23f),
            homeQuickRamRect.centerY() + dp(3.8f),
            pillW - dp(30f),
            bodyBoldPaint
        )

        // 2. Middle 4-Card Engine Readiness Grid
        val gridTop = top + heroH + dp(10f)
        val gridH = dp(58f)
        val gap = dp(8f)
        val cardW = (totalW - gap * 3f) / 4f

        val statusItems = listOf(
            Triple("OpenJDK 17 Runtime", readiness.isOpenJdkRuntimeReady, if (readiness.isOpenJdkRuntimeReady) "Siap (${hw.abi})" else "Belum Terpasang"),
            Triple("Client 1.20.4 & Libs", readiness.isClientJarReady, if (readiness.isClientJarReady) "Terverifikasi" else "Perlu Diunduh"),
            Triple("ModMenu, Optimasi & Modrinth", readiness.installedModsCount >= 6, "${readiness.installedModsCount}/9 Mod Inti Siap"),
            Triple("Asset Suara & Tekstur", readiness.isAssetIndexReady, if (readiness.isAssetIndexReady) "Index 12.json Siap" else "Belum Lengkap")
        )

        for ((i, item) in statusItems.withIndex()) {
            val cLeft = left + i * (cardW + gap)
            tmpRect.set(cLeft, gridTop, cLeft + cardW, gridTop + gridH)
            canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), cardPaint)
            canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), if (item.second) activeBorderPaint else borderPaint)

            val badgeText = if (item.second) "SIAP" else "BELUM"
            val badgeW = dp(48f)
            val badgeH = dp(15f)
            dstRect.set(cLeft + dp(10f), gridTop + dp(7f), cLeft + dp(10f) + badgeW, gridTop + dp(7f) + badgeH)
            canvas.drawRoundRect(dstRect, dp(4f), dp(4f), if (item.second) accentGreenPaint else accentTerraPaint)
            btnTextPaint.textSize = dp(8.8f)
            canvas.drawText(badgeText, dstRect.centerX(), dstRect.centerY() + dp(3.1f), btnTextPaint)
            btnTextPaint.textSize = dp(10.4f)

            drawFittedText(canvas, item.first, cLeft + dp(10f), gridTop + dp(36f), cardW - dp(20f), bodyBoldPaint)
            drawFittedText(canvas, item.third, cLeft + dp(10f), gridTop + dp(50f), cardW - dp(20f), mutedPaint)
        }

        // 3. Bottom Action & Download Card
        val botTop = gridTop + gridH + dp(10f)
        tmpRect.set(left, botTop, right, bottom)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), borderPaint)

        val innerL = left + dp(14f)
        val innerR = right - dp(14f)
        val innerW = innerR - innerL

        val leftZoneW = innerW * 0.58f
        val modeBtnW = (leftZoneW - dp(8f)) * 0.5f
        val modeBtnH = dp(30f)
        val modeY = botTop + dp(12f)

        homeLiteModeRect.set(innerL, modeY, innerL + modeBtnW, modeY + modeBtnH)
        homeFullModeRect.set(homeLiteModeRect.right + dp(8f), modeY, innerL + leftZoneW, modeY + modeBtnH)

        canvas.drawRoundRect(homeLiteModeRect, dp(6f), dp(6f), if (skipMusicLite) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(homeLiteModeRect, dp(6f), dp(6f), borderPaint)
        canvas.drawText("MODE HEMAT (~245 MB)", homeLiteModeRect.centerX(), homeLiteModeRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(homeFullModeRect, dp(6f), dp(6f), if (!skipMusicLite) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(homeFullModeRect, dp(6f), dp(6f), borderPaint)
        canvas.drawText("MODE PENUH (~695 MB)", homeFullModeRect.centerX(), homeFullModeRect.centerY() + dp(3.8f), btnTextPaint)

        val pct = downloadState.percent.coerceIn(0, 100)
        val progY = modeY + modeBtnH + dp(16f)
        val statusStr = when {
            downloadState.isRunning -> "Mengunduh ($pct%): ${downloadState.phaseTitle} - ${downloadState.detailText}"
            readiness.isReadyToLaunch -> "Seluruh file Engine & OpenJDK 17 siap. Ketuk tombol di kanan untuk mulai bermain."
            else -> "Mode Hemat membuat stub .ogg hening 108-byte yang 100% aman tanpa risiko crash suara."
        }
        drawFittedText(canvas, statusStr, innerL, progY, leftZoneW, bodyPaint)

        val barTop = (progY + dp(8f)).coerceAtMost(bottom - dp(18f))
        val barH = dp(10f)
        dstRect.set(innerL, barTop, innerL + leftZoneW, barTop + barH)
        canvas.drawRoundRect(dstRect, dp(5f), dp(5f), trackPaint)
        val fillRatio = if (readiness.isReadyToLaunch && !downloadState.isRunning) 1f else (pct / 100f)
        if (fillRatio > 0f) {
            dstRect.set(innerL, barTop, innerL + leftZoneW * fillRatio, barTop + barH)
            canvas.drawRoundRect(dstRect, dp(5f), dp(5f), accentGreenPaint)
        }

        val actionLeft = innerL + leftZoneW + dp(14f)
        homePrimaryActionRect.set(actionLeft, botTop + dp(12f), innerR, bottom - dp(12f))
        val actionPaint = when {
            downloadState.isRunning -> accentTerraPaint
            else -> accentGreenPaint
        }
        canvas.drawRoundRect(homePrimaryActionRect, dp(9f), dp(9f), actionPaint)
        canvas.drawRoundRect(homePrimaryActionRect, dp(9f), dp(9f), activeBorderPaint)

        val mainBtnTitle = when {
            downloadState.isRunning -> "UNDUHAN BERJALAN ($pct%)"
            readiness.isReadyToLaunch -> "MAIN SEKARANG"
            else -> "UNDUH / LANJUTKAN DATA"
        }
        val mainBtnSub = when {
            downloadState.isRunning -> "Otomatis lanjut di latar belakang"
            readiness.isReadyToLaunch -> "Luncurkan Minecraft 1.20.4 (${hw.allocatedHeapMb} MB)"
            else -> if (skipMusicLite) "Paket Hemat ~245 MB (Resume Aktif)" else "Paket Penuh ~695 MB (Resume Aktif)"
        }
        btnTextPaint.textSize = dp(13.2f)
        canvas.drawText(mainBtnTitle, homePrimaryActionRect.centerX(), homePrimaryActionRect.centerY() - dp(3f), btnTextPaint)
        btnTextPaint.textSize = dp(9.6f)
        canvas.drawText(
            fitText(mainBtnSub, btnTextPaint, homePrimaryActionRect.width() - dp(20f)),
            homePrimaryActionRect.centerX(),
            homePrimaryActionRect.centerY() + dp(13f),
            btnTextPaint
        )
        btnTextPaint.textSize = dp(10.4f)
    }

    // =========================================================================
    // PAGE 2: MEMORI & ENGINE (RAM SLIDER, PRESETS, RENDERER, RESOLUTION, GC)
    // =========================================================================
    private fun drawMemoryAndEnginePage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val colGap = dp(12f)
        val leftColW = (totalW - colGap) * 0.50f
        val rightColLeft = left + leftColW + colGap
        val rightColW = right - rightColLeft
        val hw = JvmOptimizer.detectHardware(context)

        tmpRect.set(left, top, left + leftColW, bottom)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), borderPaint)

        val pad = dp(14f)
        val inL = left + pad
        val inW = leftColW - pad * 2f
        var y = top + dp(24f)

        val modeLabel = if (hw.isCustomHeap) "Mode Custom" else "Saran Default"
        drawFittedText(canvas, "Alokasi Memori JVM (-Xmx): ${hw.allocatedHeapMb} MB ($modeLabel)", inL, y, inW, h2Paint)
        y += dp(16f)
        drawFittedText(
            canvas,
            "Saran Default untuk HP ini: ${hw.recommendedHeapMb} MB  |  Total RAM HP: ${hw.totalRamMb} MB",
            inL,
            y,
            inW,
            mutedPaint
        )

        y += dp(10f)
        ramSliderRect.set(inL, y, inL + inW, y + dp(26f))
        canvas.drawRoundRect(ramSliderRect, dp(7f), dp(7f), trackPaint)
        val ratio = ((hw.allocatedHeapMb - hw.minSafeHeapMb).toFloat() /
            (hw.maxSafeHeapMb - hw.minSafeHeapMb).coerceAtLeast(1)).coerceIn(0f, 1f)
        dstRect.set(inL, y, inL + inW * ratio, y + dp(26f))
        canvas.drawRoundRect(dstRect, dp(7f), dp(7f), accentGreenPaint)
        canvas.drawText(
            "Geser Slider RAM (${hw.minSafeHeapMb} MB - ${hw.maxSafeHeapMb} MB)",
            ramSliderRect.centerX(),
            ramSliderRect.centerY() + dp(3.8f),
            btnTextPaint
        )

        y += dp(34f)
        val stepW = dp(72f)
        val defW = inW - stepW * 2f - dp(12f)
        val stepH = dp(28f)
        ramMinusBtnRect.set(inL, y, inL + stepW, y + stepH)
        ramDefaultBtnRect.set(ramMinusBtnRect.right + dp(6f), y, ramMinusBtnRect.right + dp(6f) + defW, y + stepH)
        ramPlusBtnRect.set(ramDefaultBtnRect.right + dp(6f), y, inL + inW, y + stepH)

        canvas.drawRoundRect(ramMinusBtnRect, dp(6f), dp(6f), cardElevatedPaint)
        canvas.drawRoundRect(ramMinusBtnRect, dp(6f), dp(6f), borderPaint)
        canvas.drawText("- 128 MB", ramMinusBtnRect.centerX(), ramMinusBtnRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(ramDefaultBtnRect, dp(6f), dp(6f), if (!hw.isCustomHeap) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(ramDefaultBtnRect, dp(6f), dp(6f), borderPaint)
        canvas.drawText("SARAN DEFAULT (${hw.recommendedHeapMb} MB)", ramDefaultBtnRect.centerX(), ramDefaultBtnRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(ramPlusBtnRect, dp(6f), dp(6f), cardElevatedPaint)
        canvas.drawRoundRect(ramPlusBtnRect, dp(6f), dp(6f), borderPaint)
        canvas.drawText("+ 128 MB", ramPlusBtnRect.centerX(), ramPlusBtnRect.centerY() + dp(3.8f), btnTextPaint)

        y += dp(36f)
        ramPresetRects.clear()
        val presets = listOf(1024, 1536, 2048, 3072, 4096)
        val pGap = dp(5f)
        val pW = (inW - pGap * (presets.size - 1)) / presets.size
        for ((idx, mb) in presets.withIndex()) {
            val px = inL + idx * (pW + pGap)
            val r = RectF(px, y, px + pW, y + dp(25f))
            val isCur = (hw.allocatedHeapMb == mb)
            canvas.drawRoundRect(r, dp(5f), dp(5f), if (isCur) accentGreenPaint else cardElevatedPaint)
            canvas.drawRoundRect(r, dp(5f), dp(5f), borderPaint)
            canvas.drawText("${mb}M", r.centerX(), r.centerY() + dp(3.5f), btnTextPaint)
            ramPresetRects.add(mb to r)
        }

        y += dp(42f)
        drawFittedText(canvas, "Skala Resolusi Render (Boost FPS vs Ketajaman):", inL, y, inW, h2Paint)
        y += dp(8f)
        val resW = (inW - dp(12f)) / 3f
        val resH = dp(30f)
        res50Rect.set(inL, y, inL + resW, y + resH)
        res75Rect.set(res50Rect.right + dp(6f), y, res50Rect.right + dp(6f) + resW, y + resH)
        res100Rect.set(res75Rect.right + dp(6f), y, inL + inW, y + resH)

        for ((r, pair) in listOf(res50Rect to (50 to "50% (FPS Tinggi)"), res75Rect to (75 to "75% (Seimbang)"), res100Rect to (100 to "100% (Native)"))) {
            val active = (hw.resolutionScalePct == pair.first)
            canvas.drawRoundRect(r, dp(6f), dp(6f), if (active) accentGreenPaint else cardElevatedPaint)
            canvas.drawRoundRect(r, dp(6f), dp(6f), borderPaint)
            canvas.drawText(pair.second, r.centerX(), r.centerY() + dp(3.8f), btnTextPaint)
        }

        y += dp(46f)
        drawFittedText(canvas, "Mode JVM Garbage Collector:", inL, y, inW, h2Paint)
        y += dp(8f)
        val gcW = (inW - dp(8f)) * 0.5f
        gcG1Rect.set(inL, y, inL + gcW, y + resH)
        gcZgcRect.set(gcG1Rect.right + dp(8f), y, inL + inW, y + resH)

        canvas.drawRoundRect(gcG1Rect, dp(6f), dp(6f), if (!hw.useGenerationalZgc) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(gcG1Rect, dp(6f), dp(6f), borderPaint)
        canvas.drawText("G1GC (Stabil Semua HP)", gcG1Rect.centerX(), gcG1Rect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(gcZgcRect, dp(6f), dp(6f), if (hw.useGenerationalZgc) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(gcZgcRect, dp(6f), dp(6f), borderPaint)
        canvas.drawText("Generational ZGC (64-bit)", gcZgcRect.centerX(), gcZgcRect.centerY() + dp(3.8f), btnTextPaint)

        // RIGHT COLUMN CARD: Multi-Renderer Selector
        tmpRect.set(rightColLeft, top, right, bottom)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), borderPaint)

        val rInL = rightColLeft + pad
        val rInW = rightColW - pad * 2f
        var ry = top + dp(24f)

        val selectedBackend = RendererProfileManager.getSelectedBackend(context)
        val effectiveBackend = RendererProfileManager.resolveEffectiveBackend(context)
        drawFittedText(canvas, "Pilih Renderer GPU (Aktif: ${effectiveBackend.id}):", rInL, ry, rInW, h2Paint)
        ry += dp(10f)

        val availH = bottom - ry - dp(12f)
        val itemH = ((availH - dp(24f)) / RendererProfileManager.RendererBackend.values().size).coerceIn(dp(34f), dp(48f))
        for ((idx, backend) in RendererProfileManager.RendererBackend.values().withIndex()) {
            rendererItemRects[idx].set(rInL, ry, rInL + rInW, ry + itemH)
            val isChosen = (selectedBackend == backend)
            canvas.drawRoundRect(rendererItemRects[idx], dp(7f), dp(7f), if (isChosen) accentGreenPaint else cardElevatedPaint)
            canvas.drawRoundRect(rendererItemRects[idx], dp(7f), dp(7f), borderPaint)

            val tag = if (isChosen) "[DIPILIH] " else ""
            drawFittedText(canvas, tag + backend.displayName, rInL + dp(10f), ry + dp(16f), rInW - dp(20f), bodyBoldPaint)
            drawFittedText(canvas, backend.shortDesc, rInL + dp(10f), ry + dp(31f), rInW - dp(20f), mutedPaint)
            ry += itemH + dp(6f)
        }
    }

    // =========================================================================
    // PAGE 3: MODS, SHADER & LIVE MODRINTH SYNC (SCROLLABLE)
    // =========================================================================
    private fun drawModsAndShadersPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val halfW = (totalW - dp(10f)) * 0.5f

        // Top Sub-Tab Bar: [MOD BAWAAN & SHADERPACK] vs [SINKRONISASI MODRINTH ONLINE]
        val subTabH = dp(32f)
        modsSubTabLocalRect.set(left, top, left + halfW, top + subTabH)
        modsSubTabModrinthRect.set(left + halfW + dp(10f), top, right, top + subTabH)

        canvas.drawRoundRect(modsSubTabLocalRect, dp(7f), dp(7f), if (!isModrinthOnlineTab) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(modsSubTabLocalRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText("MOD BAWAAN & SHADERPACK (9 MOD)", modsSubTabLocalRect.centerX(), modsSubTabLocalRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(modsSubTabModrinthRect, dp(7f), dp(7f), if (isModrinthOnlineTab) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(modsSubTabModrinthRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText("KATALOG & SINKRONISASI MODRINTH (1.20.4)", modsSubTabModrinthRect.centerX(), modsSubTabModrinthRect.centerY() + dp(3.8f), btnTextPaint)

        val bodyTop = top + subTabH + dp(8f)
        if (!isModrinthOnlineTab) {
            drawLocalModsAndShaderContent(canvas, left, bodyTop, right, bottom)
        } else {
            drawModrinthOnlineContent(canvas, left, bodyTop, right, bottom)
        }
    }

    private fun drawLocalModsAndShaderContent(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val mods = RendererProfileManager.getModStackStatusCompat(context)

        // Top Shader Pipeline Bar Card
        val shaderCardH = dp(56f)
        tmpRect.set(left, top, right, top + shaderCardH)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), borderPaint)

        val shadersOn = RendererProfileManager.isShaderSupportEnabled(context)
        val activePack = RendererProfileManager.getActiveShaderpackName(context)
        drawFittedText(
            canvas,
            "Manajer Shaderpack (Iris Shaders)  |  Paket Aktif: $activePack",
            left + dp(14f),
            top + dp(19f),
            totalW * 0.54f,
            h2Paint
        )
        drawFittedText(
            canvas,
            "Catatan: Pada GL4ES 1.1.5, Sodium/Iris otomatis dinonaktifkan saat masuk dunia agar bebas crash VBO.",
            left + dp(14f),
            top + dp(37f),
            totalW * 0.54f,
            mutedPaint
        )

        val btnW = dp(104f)
        val btnH = dp(32f)
        val btnY = top + (shaderCardH - btnH) * 0.5f
        shaderSyncRect.set(right - dp(12f) - btnW, btnY, right - dp(12f), btnY + btnH)
        shaderCycleRect.set(shaderSyncRect.left - dp(7f) - btnW, btnY, shaderSyncRect.left - dp(7f), btnY + btnH)
        shaderToggleRect.set(shaderCycleRect.left - dp(7f) - btnW, btnY, shaderCycleRect.left - dp(7f), btnY + btnH)

        canvas.drawRoundRect(shaderToggleRect, dp(7f), dp(7f), if (shadersOn) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(shaderToggleRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText(if (shadersOn) "SHADER: ON" else "SHADER: OFF", shaderToggleRect.centerX(), shaderToggleRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(shaderCycleRect, dp(7f), dp(7f), cardElevatedPaint)
        canvas.drawRoundRect(shaderCycleRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText("GANTI SHADER", shaderCycleRect.centerX(), shaderCycleRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(shaderSyncRect, dp(7f), dp(7f), accentTerraPaint)
        canvas.drawText("SINKRONKAN", shaderSyncRect.centerX(), shaderSyncRect.centerY() + dp(3.8f), btnTextPaint)

        // Scrollable 2-Column Mod Grid
        val gridTop = top + shaderCardH + dp(8f)
        canvas.save()
        canvas.clipRect(left, gridTop, right, bottom)

        val cols = 2
        val gapX = dp(10f)
        val gapY = dp(8f)
        val cellW = (totalW - gapX) / cols
        val cellH = dp(48f)

        modCardRects.clear()
        var maxBottom = gridTop
        for ((idx, mod) in mods.withIndex()) {
            val col = idx % cols
            val row = idx / cols
            val cx = left + col * (cellW + gapX)
            val cy = gridTop + row * (cellH + gapY) - pageScrollY
            val r = RectF(cx, cy, cx + cellW, cy + cellH)
            maxBottom = max(maxBottom, gridTop + (row + 1) * (cellH + gapY))

            if (r.bottom >= gridTop && r.top <= bottom) {
                canvas.drawRoundRect(r, dp(8f), dp(8f), cardPaint)
                canvas.drawRoundRect(r, dp(8f), dp(8f), if (mod.isEnabled) activeBorderPaint else borderPaint)

                val pillW = dp(76f)
                val pillH = dp(24f)
                val pillRect = RectF(r.right - dp(10f) - pillW, r.centerY() - pillH * 0.5f, r.right - dp(10f), r.centerY() + pillH * 0.5f)
                canvas.drawRoundRect(pillRect, dp(6f), dp(6f), if (mod.isEnabled) accentGreenPaint else cardElevatedPaint)
                canvas.drawText(if (mod.isEnabled) "AKTIF" else "NONAKTIF", pillRect.centerX(), pillRect.centerY() + dp(3.5f), btnTextPaint)

                val textMaxW = cellW - pillW - dp(26f)
                drawFittedText(canvas, "${mod.title} [${mod.category}]", cx + dp(12f), cy + dp(19f), textMaxW, bodyBoldPaint)
                drawFittedText(canvas, mod.summary, cx + dp(12f), cy + dp(35f), textMaxW, mutedPaint)
            }
            modCardRects.add(mod.slug to r)
        }
        pageContentHeight = maxBottom - gridTop
        canvas.restore()
    }

    private fun drawModrinthOnlineContent(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left

        // Search & Sync Bar
        val barH = dp(34f)
        val syncW = dp(138f)
        val cariW = dp(92f)
        val inputW = totalW - syncW - cariW - dp(16f)

        modrinthSearchInputRect.set(left, top, left + inputW, top + barH)
        modrinthSearchBtnRect.set(modrinthSearchInputRect.right + dp(8f), top, modrinthSearchInputRect.right + dp(8f) + cariW, top + barH)
        modrinthSyncAllBtnRect.set(modrinthSearchBtnRect.right + dp(8f), top, right, top + barH)

        canvas.drawRoundRect(modrinthSearchInputRect, dp(7f), dp(7f), cardPaint)
        canvas.drawRoundRect(modrinthSearchInputRect, dp(7f), dp(7f), borderPaint)
        val qDisplay = if (modrinthQuery.isBlank()) "Ketuk untuk cari Mod Fabric 1.20.4 via Keyboard IME..." else "Pencarian: $modrinthQuery"
        drawFittedText(canvas, qDisplay, modrinthSearchInputRect.left + dp(12f), modrinthSearchInputRect.centerY() + dp(3.8f), inputW - dp(24f), bodyBoldPaint)

        canvas.drawRoundRect(modrinthSearchBtnRect, dp(7f), dp(7f), accentGreenPaint)
        canvas.drawText("CARI MOD", modrinthSearchBtnRect.centerX(), modrinthSearchBtnRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(modrinthSyncAllBtnRect, dp(7f), dp(7f), accentTerraPaint)
        canvas.drawText("SEGARKAN MODRINTH", modrinthSyncAllBtnRect.centerX(), modrinthSyncAllBtnRect.centerY() + dp(3.8f), btnTextPaint)

        val statusY = top + barH + dp(14f)
        drawFittedText(canvas, ModrinthSyncManager.syncStatusMessage, left + dp(4f), statusY, totalW, mutedPaint)

        val listTop = statusY + dp(8f)
        canvas.save()
        canvas.clipRect(left, listTop, right, bottom)

        modrinthItemRects.clear()
        val itemH = dp(50f)
        val gapY = dp(7f)
        var cy = listTop - pageScrollY
        for (item in modrinthCatalog) {
            val r = RectF(left, cy, right, cy + itemH)
            if (r.bottom >= listTop && r.top <= bottom) {
                canvas.drawRoundRect(r, dp(8f), dp(8f), cardPaint)
                canvas.drawRoundRect(r, dp(8f), dp(8f), if (item.isInstalled) activeBorderPaint else borderPaint)

                val btnW = dp(132f)
                val actRect = RectF(r.right - dp(10f) - btnW, r.top + dp(9f), r.right - dp(10f), r.bottom - dp(9f))
                val actPaint = when {
                    item.isEnabled -> accentGreenPaint
                    item.isInstalled -> accentTerraPaint
                    else -> accentSlatePaint
                }
                canvas.drawRoundRect(actRect, dp(6f), dp(6f), actPaint)
                val actLabel = when {
                    item.isEnabled -> "TERPASANG [AKTIF]"
                    item.isInstalled -> "TERPASANG [OFF]"
                    else -> "UNDUH & PASANG"
                }
                canvas.drawText(actLabel, actRect.centerX(), actRect.centerY() + dp(3.6f), btnTextPaint)

                val textW = totalW - btnW - dp(32f)
                drawFittedText(
                    canvas,
                    "${item.title} [${item.categoryTag}]  •  ${item.downloadsText}",
                    r.left + dp(12f),
                    r.top + dp(20f),
                    textW,
                    bodyBoldPaint
                )
                drawFittedText(
                    canvas,
                    item.summary,
                    r.left + dp(12f),
                    r.top + dp(37f),
                    textW,
                    mutedPaint
                )
                modrinthItemRects.add(item to actRect)
            }
            cy += itemH + gapY
        }
        pageContentHeight = modrinthCatalog.size * (itemH + gapY)
        canvas.restore()
    }

    // =========================================================================
    // PAGE 4: KONTROL, HUD & SHARE/IMPORT .JSON (SCROLLABLE 3x2 GRID)
    // =========================================================================
    private fun drawControlsAndHudPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val gap = dp(10f)
        val cardW = (totalW - gap) * 0.5f
        val cardH = ((bottom - top - gap * 2f) / 3f).coerceAtLeast(dp(74f))

        val row1Y = top
        val row2Y = row1Y + cardH + gap
        val row3Y = row2Y + cardH + gap

        val cards = listOf(
            Triple(
                ctrlVisualHudBtnRect.apply { set(left, row1Y, left + cardW, row1Y + cardH) },
                "1. Visual Drag & Drop HUD Editor",
                "Geser, ubah ukuran, opasitas, atau tambah tombol langsung di layar."
            ) to ("BUKA VISUAL HUD EDITOR" to accentGreenPaint),
            Triple(
                ctrlKeybindModalBtnRect.apply { set(left + cardW + gap, row1Y, right, row1Y + cardH) },
                "2. Custom Keybind (104 Tombol PC & Gamepad)",
                "Bind tombol bebas ke A-Z, F1-F12, Mouse, Macro, Keyboard IME, atau Sidebar."
            ) to ("BUKA KEYBIND KEYBOARD PC" to accentGreenPaint),
            Triple(
                ctrlSettingsModalBtnRect.apply { set(left, row2Y, left + cardW, row2Y + cardH) },
                "3. Sensitivitas, Gyro 200Hz & Kursor",
                "Atur sensitivitas kamera, Gyroscope, Auto-Click 14 CPS & 3 Gaya Kursor."
            ) to ("ATUR SENSITIVITAS & KURSOR" to accentGreenPaint),
            Triple(
                ctrlExportShareJsonRect.apply { set(left + cardW + gap, row2Y, right, row2Y + cardH) },
                "4. Bagikan / Ekspor Kontrol .JSON",
                "Ekspor tata letak HUD ke file .json dan bagikan ke teman atau komunitas."
            ) to ("EKSPOR & BAGIKAN .JSON" to accentTerraPaint),
            Triple(
                ctrlImportJsonClipRect.apply { set(left, row3Y, left + cardW, row3Y + cardH) },
                "5. Impor Kontrol .JSON dari Clipboard",
                "Tempel teks JSON kontrol yang disalin dari teman untuk langsung dipakai."
            ) to ("IMPOR .JSON DARI CLIPBOARD" to accentSlatePaint),
            Triple(
                ctrlOverlayPermBtnRect.apply { set(left + cardW + gap, row3Y, right, row3Y + cardH) },
                "6. Panel Kambang Unduhan (Overlay)",
                "Izinkan panel progres melayang saat berpindah aplikasi selama mengunduh."
            ) to ((if (FloatingDownloadOverlay.canDrawOverlays(context)) "IZIN OVERLAY: SUDAH AKTIF" else "AKTIFKAN IZIN OVERLAY") to accentGreenPaint)
        )

        for ((info, pair) in cards) {
            val r = info.first
            canvas.drawRoundRect(r, dp(9f), dp(9f), cardPaint)
            canvas.drawRoundRect(r, dp(9f), dp(9f), borderPaint)

            drawFittedText(canvas, info.second, r.left + dp(12f), r.top + dp(19f), r.width() - dp(24f), h2Paint)
            drawFittedText(canvas, info.third, r.left + dp(12f), r.top + dp(35f), r.width() - dp(24f), mutedPaint)

            val bRect = RectF(r.left + dp(12f), r.bottom - dp(34f), r.right - dp(12f), r.bottom - dp(8f))
            canvas.drawRoundRect(bRect, dp(6f), dp(6f), pair.second)
            canvas.drawText(pair.first, bRect.centerX(), bRect.centerY() + dp(3.6f), btnTextPaint)
        }
    }

    // =========================================================================
    // PAGE 5: AKUN PEMAIN (MICROSOFT OAUTH2 + OFFLINE + SYSTEM IME KEYBOARD)
    // =========================================================================
    private fun drawAccountPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val activeAcc = AccountManager.getActiveAccount(context)
        val isMsaActive = (activeAcc.type == AccountManager.AccountType.MICROSOFT)
        accountActionRects.clear()

        // 1. Top Active Profile Card
        val profH = dp(76f)
        tmpRect.set(left, top, right, top + profH)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), activeBorderPaint)

        var textX = left + dp(14f)
        mascotBmp?.let { mBmp ->
            val sz = dp(52f)
            srcRect.set(0, 0, mBmp.width, mBmp.height)
            dstRect.set(left + dp(12f), top + dp(12f), left + dp(12f) + sz, top + dp(12f) + sz)
            canvas.drawBitmap(mBmp, srcRect, dstRect, bitmapPaint)
            canvas.drawRoundRect(dstRect, dp(8f), dp(8f), borderPaint)
            textX = dstRect.right + dp(14f)
        }

        drawFittedText(
            canvas,
            "Akun Aktif: ${activeAcc.username}   |   Tipe: ${activeAcc.type.label}",
            textX,
            top + dp(27f),
            right - textX - dp(16f),
            h2Paint
        )
        drawFittedText(
            canvas,
            "UUID Pemain: ${activeAcc.uuid}   |   Input menggunakan Keyboard IME Sistem Android (Tanpa Keyboard Custom)",
            textX,
            top + dp(48f),
            right - textX - dp(16f),
            mutedPaint
        )

        // 2. Two Side-by-Side Cards: LEFT = Offline Account (IME Keyboard), RIGHT = Microsoft Official OAuth2
        val bodyTop = top + profH + dp(10f)
        val colGap = dp(12f)
        val colW = (totalW - colGap) * 0.5f
        val rightColL = left + colW + colGap

        // LEFT CARD: OFFLINE ACCOUNT + SYSTEM IME KEYBOARD
        tmpRect.set(left, bodyTop, left + colW, bottom)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), if (!isMsaActive) activeBorderPaint else borderPaint)

        val inL = left + dp(14f)
        val inW = colW - dp(28f)
        var ly = bodyTop + dp(24f)

        drawFittedText(canvas, "Opsi A: Akun Offline (Lokal / Server Non-Premium)", inL, ly, inW, h2Paint)
        ly += dp(16f)
        drawFittedText(canvas, "Ketuk kotak di bawah untuk mengetik Username dengan Keyboard IME Android:", inL, ly, inW, mutedPaint)
        ly += dp(10f)

        // System IME Input Field Box
        val imeFieldRect = RectF(inL, ly, inL + inW, ly + dp(40f))
        canvas.drawRoundRect(imeFieldRect, dp(8f), dp(8f), cardElevatedPaint)
        canvas.drawRoundRect(imeFieldRect, dp(8f), dp(8f), activeBorderPaint)
        drawFittedText(
            canvas,
            "Username: $editingUsername",
            imeFieldRect.left + dp(12f),
            imeFieldRect.centerY() + dp(4f),
            inW - dp(120f),
            h2Paint
        )
        val kbBadgeRect = RectF(imeFieldRect.right - dp(104f), imeFieldRect.top + dp(6f), imeFieldRect.right - dp(6f), imeFieldRect.bottom - dp(6f))
        canvas.drawRoundRect(kbBadgeRect, dp(5f), dp(5f), accentSlatePaint)
        canvas.drawText("KETIK (IME)", kbBadgeRect.centerX(), kbBadgeRect.centerY() + dp(3.5f), btnTextPaint)
        accountActionRects.add("OPEN_IME_USERNAME" to imeFieldRect)
        ly += dp(50f)

        val saveOfflineRect = RectF(inL, ly, inL + inW, ly + dp(36f))
        canvas.drawRoundRect(saveOfflineRect, dp(7f), dp(7f), if (!isMsaActive) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(saveOfflineRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText("SIMPAN & GUNAKAN AKUN OFFLINE", saveOfflineRect.centerX(), saveOfflineRect.centerY() + dp(3.8f), btnTextPaint)
        accountActionRects.add("SAVE_OFFLINE" to saveOfflineRect)

        // RIGHT CARD: MICROSOFT OFFICIAL OAUTH2 LOGIN
        tmpRect.set(rightColL, bodyTop, right, bottom)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), if (isMsaActive) activeBorderPaint else borderPaint)

        val rInL = rightColL + dp(14f)
        val rInW = colW - dp(28f)
        var ry = bodyTop + dp(24f)

        drawFittedText(canvas, "Opsi B: Akun Microsoft Resmi (Xbox Live / Mojang)", rInL, ry, rInW, h2Paint)
        ry += dp(16f)
        drawFittedText(canvas, AccountManager.msaAuthStatusText, rInL, ry, rInW, bodyPaint)
        ry += dp(10f)

        val code = AccountManager.msaUserCode
        if (code.isNotBlank()) {
            val codeBox = RectF(rInL, ry, rInL + rInW, ry + dp(34f))
            canvas.drawRoundRect(codeBox, dp(7f), dp(7f), cardElevatedPaint)
            canvas.drawRoundRect(codeBox, dp(7f), dp(7f), activeBorderPaint)
            canvas.drawText("KODE LOGIN MICROSOFT: $code (Tersalin ke Clipboard)", codeBox.centerX(), codeBox.centerY() + dp(4f), btnTextPaint)
            accountActionRects.add("COPY_OPEN_MSA" to codeBox)
            ry += dp(42f)
        }

        val startMsaRect = RectF(rInL, ry, rInL + rInW, ry + dp(36f))
        canvas.drawRoundRect(startMsaRect, dp(7f), dp(7f), accentGreenPaint)
        canvas.drawText(
            if (AccountManager.isMsaPolling) "BUKA BROWSER MICROSOFT.COM/LINK" else "LOGIN AKUN MICROSOFT RESMI (OAUTH2)",
            startMsaRect.centerX(),
            startMsaRect.centerY() + dp(3.8f),
            btnTextPaint
        )
        accountActionRects.add("START_MSA_LOGIN" to startMsaRect)
        ry += dp(44f)

        if (AccountManager.hasSavedMicrosoftAccount(context) || AccountManager.isMsaPolling) {
            val secRect = RectF(rInL, ry, rInL + rInW, ry + dp(32f))
            canvas.drawRoundRect(secRect, dp(7f), dp(7f), accentTerraPaint)
            val secLabel = if (AccountManager.isMsaPolling) "BATALKAN PROSES LOGIN" else "KELUAR (LOGOUT) AKUN MICROSOFT"
            canvas.drawText(secLabel, secRect.centerX(), secRect.centerY() + dp(3.8f), btnTextPaint)
            accountActionRects.add("MSA_SECONDARY" to secRect)
        }
    }

    // =========================================================================
    // PAGE 6: LOG DIAGNOSTIK (STRUCTURED MONOSPACE LOG CONSOLE)
    // =========================================================================
    private fun drawLogsPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val btnW = dp(124f)
        val btnH = dp(30f)
        logCopyRect.set(right - btnW, top, right, top + btnH)
        logRefreshRect.set(logCopyRect.left - dp(8f) - btnW, top, logCopyRect.left - dp(8f), top + btnH)

        canvas.drawRoundRect(logRefreshRect, dp(7f), dp(7f), cardElevatedPaint)
        canvas.drawRoundRect(logRefreshRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText("SEGARKAN LOG", logRefreshRect.centerX(), logRefreshRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(logCopyRect, dp(7f), dp(7f), accentGreenPaint)
        canvas.drawText("SALIN LOG", logCopyRect.centerX(), logCopyRect.centerY() + dp(3.8f), btnTextPaint)

        drawFittedText(
            canvas,
            "File: ${VeloLogger.latestLogFile?.absolutePath ?: "latest.log"} (Geser ke atas/bawah untuk scroll)",
            left,
            top + dp(19f),
            logRefreshRect.left - left - dp(12f),
            bodyBoldPaint
        )

        val boxTop = top + btnH + dp(8f)
        tmpRect.set(left, boxTop, right, bottom)
        canvas.drawRoundRect(tmpRect, dp(9f), dp(9f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(9f), dp(9f), borderPaint)

        canvas.save()
        canvas.clipRect(tmpRect.left + dp(10f), tmpRect.top + dp(8f), tmpRect.right - dp(10f), tmpRect.bottom - dp(8f))
        val lineH = dp(14f)
        var y = tmpRect.top + dp(20f) - logScrollY
        for (line in logLines) {
            if (y >= tmpRect.top && y <= tmpRect.bottom + lineH) {
                canvas.drawText(line, tmpRect.left + dp(10f), y, monoPaint)
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
                touchDownY = y
                isDraggingScroll = false
                if (activePage == NavPage.MEMORY_ENGINE && ramSliderRect.contains(x, y)) {
                    handleRamSliderDrag(x)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastTouchY - y
                if (kotlin.math.abs(y - touchDownY) > dp(8f)) {
                    isDraggingScroll = true
                }
                if (activePage == NavPage.MEMORY_ENGINE && ramSliderRect.contains(x, y)) {
                    handleRamSliderDrag(x)
                } else if (activePage == NavPage.LOGS) {
                    lastTouchY = y
                    val maxScroll = max(0f, logLines.size * dp(14f) - (height * 0.6f))
                    logScrollY = (logScrollY + dy).coerceIn(0f, maxScroll)
                    postInvalidateOnAnimation()
                } else if (activePage == NavPage.MODS_SHADERS) {
                    lastTouchY = y
                    val maxScroll = max(0f, pageContentHeight - (height * 0.55f))
                    pageScrollY = (pageScrollY + dy).coerceIn(0f, maxScroll)
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDraggingScroll && (activePage == NavPage.LOGS || activePage == NavPage.MODS_SHADERS)) {
                    isDraggingScroll = false
                    return true
                }
                isDraggingScroll = false

                // Check Left Navigation Sidebar first
                for ((idx, r) in navRects.withIndex()) {
                    if (r.contains(x, y)) {
                        activePage = NavPage.values()[idx]
                        return true
                    }
                }

                when (activePage) {
                    NavPage.HOME -> handleHomeTap(x, y)
                    NavPage.MEMORY_ENGINE -> handleMemoryEngineTap(x, y)
                    NavPage.MODS_SHADERS -> handleModsShadersTap(x, y)
                    NavPage.CONTROLS_HUD -> handleControlsHudTap(x, y)
                    NavPage.ACCOUNT -> handleAccountTap(x, y)
                    NavPage.LOGS -> handleLogsTap(x, y)
                }
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun handleRamSliderDrag(x: Float) {
        val hw = JvmOptimizer.detectHardware(context)
        val ratio = ((x - ramSliderRect.left) / ramSliderRect.width()).coerceIn(0f, 1f)
        val rawMb = hw.minSafeHeapMb + (ratio * (hw.maxSafeHeapMb - hw.minSafeHeapMb)).toInt()
        val stepped = (rawMb / 64) * 64
        JvmOptimizer.setCustomHeapMb(context, stepped)
        postInvalidateOnAnimation()
    }

    private fun handleHomeTap(x: Float, y: Float) {
        when {
            homeQuickAccountRect.contains(x, y) -> activePage = NavPage.ACCOUNT
            homeQuickRamRect.contains(x, y) -> activePage = NavPage.MEMORY_ENGINE
            homeLiteModeRect.contains(x, y) -> {
                if (!downloadState.isRunning) {
                    skipMusicLite = true
                    ResumableDownloadEngine.setSkipMusicLiteEnabled(context, true)
                    showBannerMessage("Mode Hemat (~245 MB - Silent OGG Aman) diaktifkan.")
                }
            }
            homeFullModeRect.contains(x, y) -> {
                if (!downloadState.isRunning) {
                    skipMusicLite = false
                    ResumableDownloadEngine.setSkipMusicLiteEnabled(context, false)
                    showBannerMessage("Mode Penuh (~695 MB - Termasuk Musik Latar) diaktifkan.")
                }
            }
            homePrimaryActionRect.contains(x, y) -> {
                if (readiness.isReadyToLaunch && !downloadState.isRunning) {
                    onLaunchGameRequested?.invoke()
                } else {
                    onStartOrResumeDownload?.invoke(skipMusicLite)
                }
            }
        }
    }

    private fun handleMemoryEngineTap(x: Float, y: Float) {
        val hw = JvmOptimizer.detectHardware(context)
        when {
            ramMinusBtnRect.contains(x, y) -> {
                JvmOptimizer.setCustomHeapMb(context, hw.allocatedHeapMb - 128)
            }
            ramDefaultBtnRect.contains(x, y) -> {
                JvmOptimizer.resetToRecommendedHeap(context)
                showBannerMessage("Alokasi RAM dikembalikan ke Saran Default (${hw.recommendedHeapMb} MB).")
            }
            ramPlusBtnRect.contains(x, y) -> {
                JvmOptimizer.setCustomHeapMb(context, hw.allocatedHeapMb + 128)
            }
            res50Rect.contains(x, y) -> JvmOptimizer.setResolutionScalePct(context, 50)
            res75Rect.contains(x, y) -> JvmOptimizer.setResolutionScalePct(context, 75)
            res100Rect.contains(x, y) -> JvmOptimizer.setResolutionScalePct(context, 100)
            gcG1Rect.contains(x, y) -> JvmOptimizer.setZgcModeEnabled(context, false)
            gcZgcRect.contains(x, y) -> JvmOptimizer.setZgcModeEnabled(context, true)
            else -> {
                for ((mb, r) in ramPresetRects) {
                    if (r.contains(x, y)) {
                        JvmOptimizer.setCustomHeapMb(context, mb)
                        return
                    }
                }
                for ((idx, r) in rendererItemRects.withIndex()) {
                    if (r.contains(x, y)) {
                        val chosen = RendererProfileManager.RendererBackend.values()[idx]
                        RendererProfileManager.setSelectedBackend(context, chosen)
                        showBannerMessage("Renderer diatur ke: ${chosen.displayName}")
                        return
                    }
                }
            }
        }
    }

    private fun handleModsShadersTap(x: Float, y: Float) {
        if (modsSubTabLocalRect.contains(x, y)) {
            isModrinthOnlineTab = false
            pageScrollY = 0f
            return
        }
        if (modsSubTabModrinthRect.contains(x, y)) {
            isModrinthOnlineTab = true
            pageScrollY = 0f
            if (modrinthCatalog.isEmpty() && ModrinthSyncManager.activeDownloadSlug == null) {
                triggerModrinthFetch(modrinthQuery)
            }
            return
        }

        if (!isModrinthOnlineTab) {
            when {
                shaderToggleRect.contains(x, y) -> {
                    val next = !RendererProfileManager.isShaderSupportEnabled(context)
                    RendererProfileManager.setShaderSupportEnabled(context, next)
                }
                shaderCycleRect.contains(x, y) -> {
                    val nextPack = RendererProfileManager.cycleActiveShaderpack(context)
                    showBannerMessage("Shaderpack dipilih: $nextPack")
                }
                shaderSyncRect.contains(x, y) -> {
                    val provisioner = DirectBootProvisioner(context)
                    val count = RendererProfileManager.syncExternalShaderpacks(context, provisioner.gameDir)
                    showBannerMessage("Sinkronisasi selesai: $count file .zip shader ditemukan.")
                }
                else -> {
                    for ((slug, r) in modCardRects) {
                        if (r.contains(x, y)) {
                            if (slug == "fabric-api") {
                                showBannerMessage("Fabric API adalah pustaka inti wajib dan selalu aktif.")
                            } else {
                                val nowOn = RendererProfileManager.toggleModEnabled(context, slug)
                                showBannerMessage("Mod '$slug' diatur ke: ${if (nowOn) "AKTIF" else "NONAKTIF"}")
                            }
                            return
                        }
                    }
                }
            }
        } else {
            when {
                modrinthSearchInputRect.contains(x, y) -> {
                    SystemImeHelper.requestTextInput(
                        label = "Cari Mod Modrinth 1.20.4",
                        initialText = modrinthQuery,
                        maxLength = 42,
                        onChanged = { q ->
                            modrinthQuery = q
                            postInvalidateOnAnimation()
                        },
                        onCommitted = { q ->
                            modrinthQuery = q
                            triggerModrinthFetch(q)
                        }
                    )
                }
                modrinthSearchBtnRect.contains(x, y) -> {
                    triggerModrinthFetch(modrinthQuery)
                }
                modrinthSyncAllBtnRect.contains(x, y) -> {
                    modrinthQuery = ""
                    triggerModrinthFetch("")
                }
                else -> {
                    for ((item, r) in modrinthItemRects) {
                        if (r.contains(x, y)) {
                            ModrinthSyncManager.installOrToggleMod(context, item) {
                                modrinthCatalog = ModrinthSyncManager.getCatalogWithDiskState(context)
                                showBannerMessage(ModrinthSyncManager.syncStatusMessage)
                                postInvalidateOnAnimation()
                            }
                            return
                        }
                    }
                }
            }
        }
    }

    private fun handleControlsHudTap(x: Float, y: Float) {
        when {
            ctrlVisualHudBtnRect.contains(x, y) -> onOpenVisualHudEditor?.invoke()
            ctrlKeybindModalBtnRect.contains(x, y) -> onOpenRendererAndKeybindModal?.invoke()
            ctrlSettingsModalBtnRect.contains(x, y) -> onOpenQuickSettings?.invoke()
            ctrlExportShareJsonRect.contains(x, y) -> {
                val buttons = ControlProfileManager.loadButtons(context)
                val outPath = ControlProfileManager.exportAndShareLayoutJson(
                    context,
                    buttons,
                    "kontrol-xyverse-pro",
                    openShareSheet = true
                )
                showBannerMessage("Kontrol .json diekspor & dibagikan: $outPath")
            }
            ctrlImportJsonClipRect.contains(x, y) -> {
                val (ok, msg) = ControlProfileManager.importLayoutFromClipboard(context)
                if (ok) onControlJsonReloadRequested?.invoke()
                showBannerMessage(msg)
            }
            ctrlOverlayPermBtnRect.contains(x, y) -> onRequestOverlayPermission?.invoke()
        }
    }

    private fun handleAccountTap(x: Float, y: Float) {
        for ((action, r) in accountActionRects) {
            if (r.contains(x, y)) {
                when (action) {
                    "OPEN_IME_USERNAME" -> {
                        SystemImeHelper.requestTextInput(
                            label = "Username Pemain Offline",
                            initialText = editingUsername,
                            maxLength = 16,
                            onChanged = { updated ->
                                editingUsername = updated.replace(Regex("[^a-zA-Z0-9_]"), "")
                                postInvalidateOnAnimation()
                            },
                            onCommitted = { submitted ->
                                val clean = submitted.replace(Regex("[^a-zA-Z0-9_]"), "")
                                val acc = AccountManager.setOfflineAccount(context, clean)
                                sessionManager.saveOfflineSession(acc.username)
                                editingUsername = acc.username
                                showBannerMessage("Username Offline disimpan via Keyboard IME: ${acc.username}")
                            }
                        )
                    }
                    "SAVE_OFFLINE" -> {
                        val acc = AccountManager.setOfflineAccount(context, editingUsername)
                        sessionManager.saveOfflineSession(acc.username)
                        editingUsername = acc.username
                        showBannerMessage("Akun Offline diaktifkan: ${acc.username} (UUID: ${acc.uuid.take(8)}...)")
                    }
                    "START_MSA_LOGIN", "COPY_OPEN_MSA" -> {
                        AccountManager.setAccountMode(context, AccountManager.AccountType.MICROSOFT)
                        if (AccountManager.isMsaPolling && AccountManager.msaVerificationUri.isNotBlank()) {
                            AccountManager.openVerificationBrowser(context)
                        } else {
                            AccountManager.startMicrosoftLoginFlow(context, openBrowserImmediately = true) {
                                postInvalidateOnAnimation()
                            }
                        }
                    }
                    "MSA_SECONDARY" -> {
                        if (AccountManager.isMsaPolling) {
                            AccountManager.cancelMsaPolling()
                        } else {
                            AccountManager.logoutMicrosoftAccount(context)
                            showBannerMessage("Akun Microsoft dikeluarkan. Beralih ke Akun Offline.")
                        }
                    }
                }
                return
            }
        }
    }

    private fun handleLogsTap(x: Float, y: Float) {
        if (logRefreshRect.contains(x, y)) {
            logLines = sanitizeLogLines(VeloLogger.readLatestLogsTail(15000).lines())
            showBannerMessage("Log diperbarui (${logLines.size} baris).")
        } else if (logCopyRect.contains(x, y)) {
            val fullText = sanitizeLogLines(VeloLogger.readLatestLogsTail(25000).lines()).joinToString("\n")
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MinecraftLog", fullText))
            showBannerMessage("Isi latest.log berhasil disalin ke Clipboard.")
        }
    }
}
