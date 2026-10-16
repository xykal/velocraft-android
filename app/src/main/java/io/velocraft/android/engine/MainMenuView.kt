package io.velocraft.android.engine

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
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.auth.PlayerSessionManager
import io.velocraft.android.util.VeloLogger
import kotlin.math.max

/**
 * Clean, Structured 6-Page Studio Launcher Console for Minecraft: Java Edition (1.20.4 Fabric).
 *
 * Designed with a Left Navigation Sidebar, crisp card grid alignment, automatic text-fitting
 * (zero text overflow), zero stock Android widgets, zero neon, and zero emojis.
 */
class MainMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class NavPage(val label: String) {
        HOME("BERANDA"),
        MEMORY_ENGINE("MEMORI & ENGINE"),
        MODS_SHADERS("MODS & SHADER"),
        CONTROLS_HUD("KONTROL & HUD"),
        ACCOUNT("AKUN PEMAIN"),
        LOGS("LOG DIAGNOSTIK")
    }

    // Callbacks wired by GameActivity
    var onStartOrResumeDownload: ((skipMusicLite: Boolean) -> Unit)? = null
    var onLaunchGameRequested: (() -> Unit)? = null
    var onRequestOverlayPermission: (() -> Unit)? = null
    var onOpenQuickSettings: (() -> Unit)? = null
    var onOpenRendererAndKeybindModal: (() -> Unit)? = null
    var onOpenVisualHudEditor: (() -> Unit)? = null

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
            if (value == NavPage.LOGS) {
                logLines = sanitizeLogLines(VeloLogger.readLatestLogsTail(15000).lines())
                logScrollY = 0f
            }
            postInvalidateOnAnimation()
        }

    private val sessionManager = PlayerSessionManager(context)
    private var editingUsername: String = sessionManager.getActiveSession().username
    private var skipMusicLite: Boolean = ResumableDownloadEngine.isSkipMusicLiteEnabled(context)
    private var readiness = ResumableDownloadEngine.inspectEngineReadiness(context)
    private var downloadState = EngineDownloadService.latestState

    // Log scroll state
    private var logLines: List<String> = emptyList()
    private var logScrollY: Float = 0f
    private var lastTouchY: Float = 0f

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

    // Page 3 (MODS & SHADERS) Hit Rects
    private val modCardRects = mutableListOf<Pair<String, RectF>>()
    private val shaderToggleRect = RectF()
    private val shaderCycleRect = RectF()
    private val shaderSyncRect = RectF()

    // Page 4 (CONTROLS & HUD) Hit Rects
    private val ctrlVisualHudBtnRect = RectF()
    private val ctrlKeybindModalBtnRect = RectF()
    private val ctrlSettingsModalBtnRect = RectF()
    private val ctrlOverlayPermBtnRect = RectF()

    // Page 5 (ACCOUNT) Hit Rects
    private val accountKeyRects = mutableListOf<Pair<String, RectF>>()

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
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2B26.toInt() }
    private val scrimPaint = Paint().apply { color = 0xB8121211.toInt() }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Typography
    private val h1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(16f)
        isFakeBoldText = true
    }
    private val h2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEAE6DC.toInt()
        textSize = dp(12.5f)
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD4CFC4.toInt()
        textSize = dp(11f)
    }
    private val bodyBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF969186.toInt()
        textSize = dp(9.8f)
    }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(10.8f)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // =========================================================================
        // 1. LEFT NAVIGATION SIDEBAR
        // =========================================================================
        val sidebarW = dp(164f).coerceAtMost(w * 0.24f)
        canvas.drawRect(0f, 0f, sidebarW, h, sidebarPaint)
        canvas.drawLine(sidebarW, 0f, sidebarW, h, borderPaint)

        val sbPad = dp(12f)
        var sbY = dp(16f)

        // Clean Brand Header inside Sidebar (No boastful founder/publisher text)
        markBmp?.let { bmp ->
            val iconSz = dp(28f)
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(sbPad, sbY, sbPad + iconSz, sbY + iconSz)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            drawFittedText(canvas, "MINECRAFT", sbPad + iconSz + dp(8f), sbY + dp(13f), sidebarW - iconSz - dp(24f), bodyBoldPaint)
            drawFittedText(canvas, "1.20.4 Fabric", sbPad + iconSz + dp(8f), sbY + dp(26f), sidebarW - iconSz - dp(24f), mutedPaint)
        } ?: run {
            drawFittedText(canvas, "MINECRAFT 1.20.4", sbPad, sbY + dp(16f), sidebarW - sbPad * 2f, bodyBoldPaint)
        }

        sbY += dp(40f)
        canvas.drawLine(sbPad, sbY, sidebarW - sbPad, sbY, borderPaint)
        sbY += dp(10f)

        val navBtnH = ((h - sbY - dp(38f)) / NavPage.values().size).coerceIn(dp(30f), dp(40f))
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
                    navRects[idx].left + dp(3.5f),
                    navRects[idx].top + dp(5f),
                    navRects[idx].left + dp(6.8f),
                    navRects[idx].bottom - dp(5f)
                )
                bodyBoldPaint.color = 0xFFF4F1EA.toInt()
                canvas.drawRoundRect(dstRect, dp(2f), dp(2f), bodyBoldPaint)
            } else {
                canvas.drawRoundRect(navRects[idx], dp(7f), dp(7f), borderPaint)
            }
            btnTextPaint.textAlign = Paint.Align.LEFT
            drawFittedText(
                canvas,
                page.label,
                navRects[idx].left + dp(13f),
                navRects[idx].centerY() + dp(3.8f),
                navRects[idx].width() - dp(22f),
                btnTextPaint
            )
            btnTextPaint.textAlign = Paint.Align.CENTER
        }

        // Subtle version footer at bottom of sidebar
        drawFittedText(
            canvas,
            "v0.7.7-release | ${if (android.os.Process.is64Bit()) "ARM64" else "ARM32"}",
            sbPad,
            h - dp(12f),
            sidebarW - sbPad * 2f,
            mutedPaint
        )

        // =========================================================================
        // 2. MAIN WORKSPACE CONTENT AREA
        // =========================================================================
        val contentLeft = sidebarW + dp(16f)
        val contentRight = w - dp(16f)
        val contentTop = dp(14f)
        val contentBottom = h - dp(14f)

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
            val bw = (contentRight - contentLeft).coerceAtMost(dp(560f))
            val bLeft = contentLeft + ((contentRight - contentLeft) - bw) * 0.5f
            bannerRect.set(bLeft, dp(12f), bLeft + bw, dp(44f))
            canvas.drawRoundRect(bannerRect, dp(8f), dp(8f), accentGreenPaint)
            canvas.drawRoundRect(bannerRect, dp(8f), dp(8f), borderPaint)
            drawFittedText(canvas, msg, bannerRect.left + dp(14f), bannerRect.centerY() + dp(4f), bw - dp(28f), bodyBoldPaint)
        }
    }

    // =========================================================================
    // PAGE 1: BERANDA (HERO BANNER + STATUS GRID + LAUNCH / DOWNLOAD CONSOLE)
    // =========================================================================
    private val bannerRect = RectF()

    private fun drawHomePage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val totalH = bottom - top
        val hw = JvmOptimizer.detectHardware(context)
        val effectiveRenderer = RendererProfileManager.resolveEffectiveBackend(context)
        val session = sessionManager.getActiveSession()

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

        // Mascot Avatar inside Left of Hero Card
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
            "Renderer: ${effectiveRenderer.displayName}  |  Resolusi: ${hw.resolutionScalePct}%  |  Shader: ${if (RendererProfileManager.isShaderSupportEnabled(context)) "Aktif" else "Nonaktif"}",
            heroTextLeft,
            top + dp(45f),
            maxHeroTextW,
            bodyPaint
        )

        // Two Quick Info Pills inside Hero Card (Account Pill + RAM Pill)
        val pillY = top + heroH - dp(36f)
        val pillH = dp(26f)
        val pillW = ((maxHeroTextW - dp(10f)) * 0.5f).coerceAtMost(dp(220f))

        homeQuickAccountRect.set(heroTextLeft, pillY, heroTextLeft + pillW, pillY + pillH)
        canvas.drawRoundRect(homeQuickAccountRect, dp(6f), dp(6f), cardElevatedPaint)
        canvas.drawRoundRect(homeQuickAccountRect, dp(6f), dp(6f), borderPaint)
        drawFittedText(
            canvas,
            "Pemain: ${session.username} [Ubah]",
            homeQuickAccountRect.left + dp(10f),
            homeQuickAccountRect.centerY() + dp(3.8f),
            pillW - dp(20f),
            bodyBoldPaint
        )

        homeQuickRamRect.set(homeQuickAccountRect.right + dp(8f), pillY, homeQuickAccountRect.right + dp(8f) + pillW, pillY + pillH)
        canvas.drawRoundRect(homeQuickRamRect, dp(6f), dp(6f), cardElevatedPaint)
        canvas.drawRoundRect(homeQuickRamRect, dp(6f), dp(6f), borderPaint)
        val ramTag = if (hw.isCustomHeap) "Custom" else "Saran"
        drawFittedText(
            canvas,
            "RAM JVM: ${hw.allocatedHeapMb} MB ($ramTag) [Atur]",
            homeQuickRamRect.left + dp(10f),
            homeQuickRamRect.centerY() + dp(3.8f),
            pillW - dp(20f),
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
            Triple("ModMenu, Optimasi & Iris", readiness.installedModsCount >= 6, "${readiness.installedModsCount}/9 Mod Terpasang"),
            Triple("Asset Suara & Tekstur", readiness.isAssetIndexReady, if (readiness.isAssetIndexReady) "Index 12.json Siap" else "Belum Lengkap")
        )

        for ((i, item) in statusItems.withIndex()) {
            val cLeft = left + i * (cardW + gap)
            tmpRect.set(cLeft, gridTop, cLeft + cardW, gridTop + gridH)
            canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), cardPaint)
            canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), if (item.second) activeBorderPaint else borderPaint)

            // Pill badge inside top-left of card
            val badgeText = if (item.second) "SIAP" else "BELUM"
            val badgeW = dp(48f)
            val badgeH = dp(15f)
            dstRect.set(cLeft + dp(10f), gridTop + dp(7f), cLeft + dp(10f) + badgeW, gridTop + dp(7f) + badgeH)
            canvas.drawRoundRect(dstRect, dp(4f), dp(4f), if (item.second) accentGreenPaint else accentTerraPaint)
            btnTextPaint.textSize = dp(8.8f)
            canvas.drawText(badgeText, dstRect.centerX(), dstRect.centerY() + dp(3.1f), btnTextPaint)
            btnTextPaint.textSize = dp(10.8f)

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

        // Left side of Bottom Card: Package Mode Selector (Mode Hemat vs Mode Penuh) + Progress Bar
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

        // Progress Bar below mode buttons
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

        // Right side of Bottom Card: Big Primary Launch / Download Button
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
        btnTextPaint.textSize = dp(9.8f)
        canvas.drawText(
            fitText(mainBtnSub, btnTextPaint, homePrimaryActionRect.width() - dp(20f)),
            homePrimaryActionRect.centerX(),
            homePrimaryActionRect.centerY() + dp(13f),
            btnTextPaint
        )
        btnTextPaint.textSize = dp(10.8f)
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

        // LEFT COLUMN CARD: JVM Memory Allocation + Resolution Scale + GC
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

        // Interactive RAM Slider
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

        // [- 128 MB] [SARAN DEFAULT] [+ 128 MB]
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

        // Quick Preset Chips (1024, 1536, 2048, 3072, 4096)
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

        // Resolution Scale Section
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

        // Garbage Collector Section
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
    // PAGE 3: MODS & SHADER (BUILT-IN MOD MANAGER + SHADERPACK MANAGER)
    // =========================================================================
    private fun drawModsAndShadersPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val mods = RendererProfileManager.getModStackStatusCompat(context)

        // Top Shader Pipeline Bar Card
        val shaderCardH = dp(62f)
        tmpRect.set(left, top, right, top + shaderCardH)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), borderPaint)

        val shadersOn = RendererProfileManager.isShaderSupportEnabled(context)
        val activePack = RendererProfileManager.getActiveShaderpackName(context)
        drawFittedText(
            canvas,
            "Manajer Shaderpack (Iris Shaders)  |  Paket Aktif: $activePack",
            left + dp(14f),
            top + dp(20f),
            totalW * 0.55f,
            h2Paint
        )
        drawFittedText(
            canvas,
            "Folder: Android/media/${context.packageName}/shaderpacks/ (Taruh file .zip shader di sini)",
            left + dp(14f),
            top + dp(38f),
            totalW * 0.55f,
            mutedPaint
        )

        val btnW = dp(108f)
        val btnH = dp(34f)
        val btnY = top + (shaderCardH - btnH) * 0.5f
        shaderSyncRect.set(right - dp(12f) - btnW, btnY, right - dp(12f), btnY + btnH)
        shaderCycleRect.set(shaderSyncRect.left - dp(8f) - btnW, btnY, shaderSyncRect.left - dp(8f), btnY + btnH)
        shaderToggleRect.set(shaderCycleRect.left - dp(8f) - btnW, btnY, shaderCycleRect.left - dp(8f), btnY + btnH)

        canvas.drawRoundRect(shaderToggleRect, dp(7f), dp(7f), if (shadersOn) accentGreenPaint else cardElevatedPaint)
        canvas.drawRoundRect(shaderToggleRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText(if (shadersOn) "SHADER: AKTIF" else "SHADER: OFF", shaderToggleRect.centerX(), shaderToggleRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(shaderCycleRect, dp(7f), dp(7f), cardElevatedPaint)
        canvas.drawRoundRect(shaderCycleRect, dp(7f), dp(7f), borderPaint)
        canvas.drawText("GANTI SHADER", shaderCycleRect.centerX(), shaderCycleRect.centerY() + dp(3.8f), btnTextPaint)

        canvas.drawRoundRect(shaderSyncRect, dp(7f), dp(7f), accentTerraPaint)
        canvas.drawText("SINKRONKAN", shaderSyncRect.centerX(), shaderSyncRect.centerY() + dp(3.8f), btnTextPaint)

        // 2x4 Interactive Mod Stack Grid
        val gridTop = top + shaderCardH + dp(10f)
        val cols = 2
        val rows = 4
        val gapX = dp(10f)
        val gapY = dp(8f)
        val cellW = (totalW - gapX) / cols
        val cellH = ((bottom - gridTop - gapY * (rows - 1)) / rows).coerceAtLeast(dp(42f))

        modCardRects.clear()
        for ((idx, mod) in mods.withIndex()) {
            val col = idx % cols
            val row = idx / cols
            val cx = left + col * (cellW + gapX)
            val cy = gridTop + row * (cellH + gapY)
            val r = RectF(cx, cy, cx + cellW, cy + cellH)

            canvas.drawRoundRect(r, dp(8f), dp(8f), cardPaint)
            canvas.drawRoundRect(r, dp(8f), dp(8f), if (mod.isEnabled) activeBorderPaint else borderPaint)

            // Status Pill on Right of Mod Card
            val pillW = dp(78f)
            val pillH = dp(24f)
            val pillRect = RectF(r.right - dp(10f) - pillW, r.centerY() - pillH * 0.5f, r.right - dp(10f), r.centerY() + pillH * 0.5f)
            canvas.drawRoundRect(pillRect, dp(6f), dp(6f), if (mod.isEnabled) accentGreenPaint else cardElevatedPaint)
            canvas.drawText(if (mod.isEnabled) "AKTIF" else "NONAKTIF", pillRect.centerX(), pillRect.centerY() + dp(3.5f), btnTextPaint)

            val textMaxW = cellW - pillW - dp(28f)
            drawFittedText(canvas, "${mod.title} [${mod.category}]", cx + dp(12f), cy + dp(19f), textMaxW, bodyBoldPaint)
            drawFittedText(canvas, mod.summary, cx + dp(12f), cy + dp(35f), textMaxW, mutedPaint)
            modCardRects.add(mod.slug to r)
        }
    }

    // =========================================================================
    // PAGE 4: KONTROL & HUD (VISUAL HUD EDITOR, KEYBINDER, CURSOR, OVERLAY)
    // =========================================================================
    private fun drawControlsAndHudPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val gap = dp(12f)
        val cardW = (totalW - gap) * 0.5f
        val cardH = (bottom - top - gap) * 0.5f

        val cards = listOf(
            Triple(
                ctrlVisualHudBtnRect.apply { set(left, top, left + cardW, top + cardH) },
                "1. Visual Drag & Drop HUD Editor",
                "Atur posisi, ukuran, transparansi, serta tambah/hapus tombol langsung di atas layar permainan."
            ) to "BUKA VISUAL HUD EDITOR",
            Triple(
                ctrlKeybindModalBtnRect.apply { set(left + cardW + gap, top, right, top + cardH) },
                "2. Custom Keybind (104 Tombol Keyboard PC & Gamepad)",
                "Bind tombol HUD bebas ke A-Z, 0-9, F1-F12, Space, Shift, Ctrl, Alt, Tab, Arrow, atau Stik Gamepad."
            ) to "BUKA KEYBIND KEYBOARD PC",
            Triple(
                ctrlSettingsModalBtnRect.apply { set(left, top + cardH + gap, left + cardW, bottom) },
                "3. Sensitivitas Kamera, Gyro 200Hz & Kursor Virtual",
                "Atur slider sensitivitas sentuhan, Gyroscope, Auto-Click 14 CPS, FastPlace 15 CPS & Show Cursor."
            ) to "ATUR SENSITIVITAS & KURSOR",
            Triple(
                ctrlOverlayPermBtnRect.apply { set(left + cardW + gap, top + cardH + gap, right, bottom) },
                "4. Panel Kambang Unduhan (Display Over Other Apps)",
                "Izinkan panel progres melayang saat Anda berpindah ke aplikasi lain selama mengunduh data."
            ) to if (FloatingDownloadOverlay.canDrawOverlays(context)) "IZIN OVERLAY: SUDAH AKTIF" else "AKTIFKAN IZIN OVERLAY"
        )

        for ((info, btnLabel) in cards) {
            val r = info.first
            canvas.drawRoundRect(r, dp(10f), dp(10f), cardPaint)
            canvas.drawRoundRect(r, dp(10f), dp(10f), borderPaint)

            drawFittedText(canvas, info.second, r.left + dp(14f), r.top + dp(24f), r.width() - dp(28f), h2Paint)
            drawFittedText(canvas, info.third, r.left + dp(14f), r.top + dp(44f), r.width() - dp(28f), mutedPaint)

            val bRect = RectF(r.left + dp(14f), r.bottom - dp(44f), r.right - dp(14f), r.bottom - dp(12f))
            canvas.drawRoundRect(bRect, dp(7f), dp(7f), accentGreenPaint)
            canvas.drawText(btnLabel, bRect.centerX(), bRect.centerY() + dp(3.8f), btnTextPaint)
        }
    }

    // =========================================================================
    // PAGE 5: AKUN PEMAIN (PROFILE CARD + CLEAN ON-SCREEN KEYBOARD)
    // =========================================================================
    private fun drawAccountPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val session = sessionManager.getActiveSession()

        // Top Profile Summary Card
        val profH = dp(78f)
        tmpRect.set(left, top, right, top + profH)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), borderPaint)

        var textX = left + dp(14f)
        mascotBmp?.let { mBmp ->
            val sz = dp(54f)
            srcRect.set(0, 0, mBmp.width, mBmp.height)
            dstRect.set(left + dp(12f), top + dp(12f), left + dp(12f) + sz, top + dp(12f) + sz)
            canvas.drawBitmap(mBmp, srcRect, dstRect, bitmapPaint)
            canvas.drawRoundRect(dstRect, dp(8f), dp(8f), borderPaint)
            textX = dstRect.right + dp(14f)
        }

        drawFittedText(
            canvas,
            "Username Saat Ini: ${session.username}   |   Sedang Diedit: $editingUsername",
            textX,
            top + dp(28f),
            right - textX - dp(16f),
            h2Paint
        )
        drawFittedText(
            canvas,
            "UUID Offline Konsisten: ${session.uuid} (Maksimal 16 karakter huruf/angka/_)",
            textX,
            top + dp(48f),
            right - textX - dp(16f),
            mutedPaint
        )

        // Clean On-Canvas Keyboard Card
        val kbTop = top + profH + dp(10f)
        tmpRect.set(left, kbTop, right, bottom)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), borderPaint)

        accountKeyRects.clear()
        val rows = listOf(
            "1234567890_",
            "QWERTYUIOP",
            "ASDFGHJKL",
            "ZXCVBNM"
        )
        val pad = dp(14f)
        val areaW = totalW - pad * 2f
        val availH = (bottom - kbTop) - pad * 2f
        val rowH = ((availH - dp(20f)) / 5f).coerceIn(dp(26f), dp(38f))
        var kY = kbTop + pad

        for (row in rows) {
            val keyW = (areaW - dp(5f) * (row.length - 1)) / row.length
            for ((cIdx, ch) in row.withIndex()) {
                val kx = left + pad + cIdx * (keyW + dp(5f))
                val r = RectF(kx, kY, kx + keyW, kY + rowH)
                canvas.drawRoundRect(r, dp(6f), dp(6f), cardElevatedPaint)
                canvas.drawRoundRect(r, dp(6f), dp(6f), borderPaint)
                canvas.drawText(ch.toString(), r.centerX(), r.centerY() + dp(4f), btnTextPaint)
                accountKeyRects.add(ch.toString() to r)
            }
            kY += rowH + dp(5f)
        }

        val halfW = (areaW - dp(10f)) * 0.5f
        val bkRect = RectF(left + pad, kY, left + pad + halfW, kY + rowH)
        val saveRect = RectF(bkRect.right + dp(10f), kY, right - pad, kY + rowH)

        canvas.drawRoundRect(bkRect, dp(7f), dp(7f), accentTerraPaint)
        canvas.drawText("HAPUS KARAKTER [BACKSPACE]", bkRect.centerX(), bkRect.centerY() + dp(4f), btnTextPaint)

        canvas.drawRoundRect(saveRect, dp(7f), dp(7f), accentGreenPaint)
        canvas.drawText("SIMPAN USERNAME BARU", saveRect.centerX(), saveRect.centerY() + dp(4f), btnTextPaint)

        accountKeyRects.add("BACKSPACE" to bkRect)
        accountKeyRects.add("SAVE_USER" to saveRect)
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
                if (activePage == NavPage.MEMORY_ENGINE && ramSliderRect.contains(x, y)) {
                    handleRamSliderDrag(x)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePage == NavPage.MEMORY_ENGINE && ramSliderRect.contains(x, y)) {
                    handleRamSliderDrag(x)
                } else if (activePage == NavPage.LOGS) {
                    val dy = lastTouchY - y
                    lastTouchY = y
                    val maxScroll = max(0f, logLines.size * dp(14f) - (height * 0.6f))
                    logScrollY = (logScrollY + dy).coerceIn(0f, maxScroll)
                    postInvalidateOnAnimation()
                }
            }
            MotionEvent.ACTION_UP -> {
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
    }

    private fun handleControlsHudTap(x: Float, y: Float) {
        when {
            ctrlVisualHudBtnRect.contains(x, y) -> onOpenVisualHudEditor?.invoke()
            ctrlKeybindModalBtnRect.contains(x, y) -> onOpenRendererAndKeybindModal?.invoke()
            ctrlSettingsModalBtnRect.contains(x, y) -> onOpenQuickSettings?.invoke()
            ctrlOverlayPermBtnRect.contains(x, y) -> onRequestOverlayPermission?.invoke()
        }
    }

    private fun handleAccountTap(x: Float, y: Float) {
        for ((key, r) in accountKeyRects) {
            if (r.contains(x, y)) {
                when (key) {
                    "BACKSPACE" -> {
                        if (editingUsername.isNotEmpty()) {
                            editingUsername = editingUsername.dropLast(1)
                        }
                    }
                    "SAVE_USER" -> {
                        val saved = sessionManager.saveOfflineSession(editingUsername)
                        editingUsername = saved.username
                        showBannerMessage("Username disimpan: ${saved.username}")
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
