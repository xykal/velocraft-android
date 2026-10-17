package io.velocraft.android.engine

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.auth.PlayerSessionManager
import io.velocraft.android.controls.ControlProfileManager
import io.velocraft.android.util.SystemImeHelper
import io.velocraft.android.util.VeloLogger
import io.velocraft.android.util.VeloVectorIcons
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rich, Layered Tactical Voxel Studio Command Deck (6-Page Launcher Console)
 * for Minecraft: Java Edition (1.20.4 Fabric).
 *
 * Features:
 * - Multi-layered 3D-beveled Studio Cards with top specular highlights, drop shadows,
 *   corner tactical brackets, and atmospheric isometric grid background
 * - Top Tactical Telemetry Command Header Bar showing live CPU/ABI, C++ Turbo .SO status,
 *   Hardware Resolution Scale, and JVM Heap allocation
 * - Tactile 3D-beveled buttons (Emerald, Amber Gold, Slate, Obsidian) with thumb-friendly hit targets
 * - Smooth draggable sliders for both JVM RAM Allocation and Hardware Resolution Scale (50% - 100%)
 * - Zero emojis, zero stock Android widgets, humble branding
 */
class MainMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class NavPage(val label: String, val subtitle: String, val iconType: Int) {
        HOME("BERANDA", "Pusat Komando Peluncuran & Telemetri Engine", 0),
        MEMORY_ENGINE("MEMORI & ENGINE", "Alokasi RAM JVM, Skala Resolusi C++ & Renderer GPU", 1),
        MODS_SHADERS("MODS & MODRINTH", "Manajer Mod Optimasi, Shaderpack & Katalog Modrinth v2", 2),
        CONTROLS_HUD("KONTROL & JSON", "Editor HUD Visual, Opasitas, Sensitivitas 60/120Hz & JSON", 3),
        ACCOUNT("AKUN PEMAIN", "Autentikasi Microsoft OAuth2 Resmi & Akun Offline IME", 4),
        LOGS("LOG DIAGNOSTIK", "Konsol Diagnostik Real-Time & Riwayat Booting", 5)
    }

    private enum class ButtonStyle {
        EMERALD,
        AMBER,
        SLATE,
        ELEVATED,
        SUBTLE
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
            raw.replace("libvelocexec.so", "libvelocraft_bridge.so", ignoreCase = true)
        }
    }

    private var activePage: NavPage = NavPage.HOME
        set(value) {
            field = value
            pageScrollY = 0f
            if (value == NavPage.LOGS) {
                logLines = sanitizeLogLines(VeloLogger.readLatestLogsTail(15000).lines())
                logScrollY = 0f
            } else if (value == NavPage.MODS_SHADERS) {
                modrinthCatalog = ModrinthSyncManager.getCatalogWithDiskState(context)
                if (ModrinthSyncManager.activeDownloadSlug == null) {
                    triggerModrinthFetch(modrinthQuery)
                }
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
    private var isDraggingRamSlider: Boolean = false
    private var isDraggingResSlider: Boolean = false

    // Page 3 Sub-Mode: true = 4-Category Content Hub (Mods, Shaders, Textures, Maps), false = Built-in Core Stack
    private var isModrinthOnlineTab: Boolean = true
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
    private val homeQuickBoostRect = RectF()

    // Page 2 (MEMORY & ENGINE) Hit Rects
    private val ramSliderRect = RectF()
    private val ramMinusBtnRect = RectF()
    private val ramDefaultBtnRect = RectF()
    private val ramPlusBtnRect = RectF()
    private val ramPresetRects = mutableListOf<Pair<Int, RectF>>()
    private val rendererItemRects = Array(RendererProfileManager.RendererBackend.values().size) { RectF() }
    private val resSliderRect = RectF()
    private val res50Rect = RectF()
    private val res60Rect = RectF()
    private val res75Rect = RectF()
    private val res100Rect = RectF()
    private val gcG1Rect = RectF()
    private val gcZgcRect = RectF()
    private val boostTurboRect = RectF()
    private val boostPvpRect = RectF()
    private val boostCleanRamRect = RectF()

    // Page 3 (MODS, SHADERS & MODRINTH) Hit Rects
    private val modsSubTabLocalRect = RectF()
    private val modsSubTabModrinthRect = RectF()
    private val modCardRects = mutableListOf<Pair<String, RectF>>()
    private val shaderToggleRect = RectF()
    private val shaderCycleRect = RectF()
    private val shaderSyncRect = RectF()
    private val modrinthCategoryRects = Array(ModrinthSyncManager.ContentCategory.values().size) { RectF() }
    private val modrinthSearchInputRect = RectF()
    private val modrinthSearchBtnRect = RectF()
    private val modrinthSyncAllBtnRect = RectF()
    private val modrinthLoadMoreBtnRect = RectF()
    private val modrinthItemRects = mutableListOf<Pair<ModrinthSyncManager.ModrinthProjectItem, RectF>>()

    // Page 4 (CONTROLS, HUD & JSON) Hit Rects
    private val ctrlVisualHudBtnRect = RectF()
    private val ctrlKeybindModalBtnRect = RectF()
    private val ctrlSettingsModalBtnRect = RectF()
    private val ctrlOverlayPermBtnRect = RectF()
    private val ctrlExportShareJsonRect = RectF()
    private val ctrlImportJsonClipRect = RectF()

    // Page 5 (ACCOUNT: MICROSOFT & OFFLINE + SYSTEM IME) Hit Rects
    private val accountActionRects = mutableListOf<Pair<String, RectF>>()

    // Page 6 (LOGS) Hit Rects
    private val logRefreshRect = RectF()
    private val logCopyRect = RectF()

    // Multi-Layered Studio Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sidebarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x0C689874
        strokeWidth = dp(0.8f)
    }
    private val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A78B088
        style = Paint.Style.FILL
    }
    private val ambientGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x58000000
        style = Paint.Style.FILL
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardElevatedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF252A26.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF363F39.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
    }
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF52A864.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val amberBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD88E42.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
    }
    private val topHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2EFFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
    }
    private val bottomLipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x52000000
        style = Paint.Style.FILL
    }
    private val accentGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF428E52.toInt() }
    private val accentTerraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC87D3E.toInt() }
    private val accentSlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A566E.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF151816.toInt() }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

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
        color = 0xFFF6F4EE.toInt()
        textSize = dp(15.2f)
        isFakeBoldText = true
    }
    private val h2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEEECE4.toInt()
        textSize = dp(11.8f)
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD4D0C5.toInt()
        textSize = dp(10.3f)
    }
    private val bodyBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF6F4EE.toInt()
        textSize = dp(10.3f)
        isFakeBoldText = true
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA39C.toInt()
        textSize = dp(9.3f)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF6F4EE.toInt()
        textSize = dp(8.8f)
        isFakeBoldText = true
    }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF6F4EE.toInt()
        textSize = dp(10.1f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6E4DC.toInt()
        textSize = dp(9.6f)
        typeface = Typeface.MONOSPACE
    }

    private val tmpRect = RectF()
    private val shadowRect = RectF()
    private val lipRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val bannerRect = RectF()

    private var cachedW = 0f
    private var cachedH = 0f

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
     * Draws a rich, multi-layered 3D-beveled Studio Card with drop shadow, vertical gradient,
     * top specular highlight line, and optional tactical corner brackets.
     */
    private fun drawStudioCard(
        canvas: Canvas,
        rect: RectF,
        radius: Float = dp(10f),
        isHighlighted: Boolean = false,
        useAmberHighlight: Boolean = false
    ) {
        // 1. Soft drop shadow
        shadowRect.set(rect.left + dp(1.5f), rect.top + dp(3f), rect.right + dp(1.5f), rect.bottom + dp(3f))
        canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)

        // 2. Multi-stop vertical gradient card body
        val topCol = when {
            useAmberHighlight -> 0xFF2B241D.toInt()
            isHighlighted -> 0xFF212B24.toInt()
            else -> 0xFF212622.toInt()
        }
        val botCol = when {
            useAmberHighlight -> 0xFF1B1713.toInt()
            isHighlighted -> 0xFF161D18.toInt()
            else -> 0xFF171A18.toInt()
        }
        cardPaint.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.bottom,
            topCol, botCol, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, cardPaint)
        cardPaint.shader = null

        // 3. Top inner specular glass highlight
        val inset = radius * 0.7f
        if (rect.width() > inset * 2f) {
            topHighlightPaint.color = if (isHighlighted || useAmberHighlight) 0x38FFFFFF else 0x22FFFFFF
            canvas.drawLine(
                rect.left + inset,
                rect.top + dp(1.2f),
                rect.right - inset,
                rect.top + dp(1.2f),
                topHighlightPaint
            )
        }

        // 4. Outer beveled border
        val strokeP = when {
            useAmberHighlight -> amberBorderPaint
            isHighlighted -> activeBorderPaint
            else -> borderPaint
        }
        canvas.drawRoundRect(rect, radius, radius, strokeP)

        // 5. Subtle corner accent ticks on highlighted cards
        if (isHighlighted || useAmberHighlight) {
            val tickLen = dp(7f)
            val cCol = if (useAmberHighlight) 0xFFF0A652.toInt() else 0xFF68CE7C.toInt()
            topHighlightPaint.color = cCol
            // Top-left L tick
            canvas.drawLine(rect.left + dp(3f), rect.top + dp(3f), rect.left + dp(3f) + tickLen, rect.top + dp(3f), topHighlightPaint)
            canvas.drawLine(rect.left + dp(3f), rect.top + dp(3f), rect.left + dp(3f), rect.top + dp(3f) + tickLen, topHighlightPaint)
            // Bottom-right L tick
            canvas.drawLine(rect.right - dp(3f) - tickLen, rect.bottom - dp(3f), rect.right - dp(3f), rect.bottom - dp(3f), topHighlightPaint)
            canvas.drawLine(rect.right - dp(3f), rect.bottom - dp(3f) - tickLen, rect.right - dp(3f), rect.bottom - dp(3f), topHighlightPaint)
        }
    }

    /**
     * Draws a tactile 3D-beveled button with top specular highlight, vertical gradient fill,
     * darker 3D bottom lip, and crisp outer frame.
     */
    private fun drawTactileButton(
        canvas: Canvas,
        rect: RectF,
        radius: Float = dp(7f),
        style: ButtonStyle = ButtonStyle.ELEVATED
    ) {
        // 1. Subtle drop shadow
        shadowRect.set(rect.left + dp(1f), rect.top + dp(2f), rect.right + dp(1f), rect.bottom + dp(2f))
        canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)

        // 2. Gradient palette per style
        val (topColor, botColor, borderColor) = when (style) {
            ButtonStyle.EMERALD -> Triple(0xFF4BA35E.toInt(), 0xFF2D703C.toInt(), 0xFF6AD07E.toInt())
            ButtonStyle.AMBER -> Triple(0xFFD98B3E.toInt(), 0xFFA25E1C.toInt(), 0xFFF2AE63.toInt())
            ButtonStyle.SLATE -> Triple(0xFF42637E.toInt(), 0xFF2B4458.toInt(), 0xFF648EB0.toInt())
            ButtonStyle.ELEVATED -> Triple(0xFF2E3530.toInt(), 0xFF202521.toInt(), 0xFF434D46.toInt())
            ButtonStyle.SUBTLE -> Triple(0xFF222723.toInt(), 0xFF181C19.toInt(), 0xFF333B36.toInt())
        }

        cardPaint.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.bottom,
            topColor, botColor, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, cardPaint)
        cardPaint.shader = null

        // 3. Bottom 3D lip
        val lipH = dp(2.2f).coerceAtMost(rect.height() * 0.18f)
        lipRect.set(rect.left + dp(1.5f), rect.bottom - lipH, rect.right - dp(1.5f), rect.bottom)
        canvas.drawRoundRect(lipRect, radius * 0.6f, radius * 0.6f, bottomLipPaint)

        // 4. Top inner specular highlight
        val inset = radius * 0.65f
        if (rect.width() > inset * 2f) {
            topHighlightPaint.color = if (style == ButtonStyle.EMERALD || style == ButtonStyle.AMBER) 0x45FFFFFF else 0x26FFFFFF
            canvas.drawLine(
                rect.left + inset,
                rect.top + dp(1.2f),
                rect.right - inset,
                rect.top + dp(1.2f),
                topHighlightPaint
            )
        }

        // 5. Outer border
        val prevCol = borderPaint.color
        borderPaint.color = borderColor
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
        borderPaint.color = prevCol
    }

    /**
     * Draws rich multi-layered geometric vector icons for navigation sidebar items & cards (zero emojis).
     */
    private fun drawNavVectorIcon(canvas: Canvas, iconType: Int, cx: Float, cy: Float, r: Float, color: Int) {
        val accent = when (iconType) {
            0 -> 0xFF68CE7C.toInt()
            1 -> 0xFFE6B86A.toInt()
            2 -> 0xFF6AA2CE.toInt()
            3 -> 0xFF5FA869.toInt()
            4 -> 0xFFC87D55.toInt()
            else -> 0xFF68CE7C.toInt()
        }
        VeloVectorIcons.drawUiIcon(canvas, iconType, cx, cy, r * 1.15f, color, accent)
    }

    private fun drawAtmosphericBackground(canvas: Canvas, w: Float, h: Float, sidebarW: Float) {
        if (cachedW != w || cachedH != h) {
            cachedW = w
            cachedH = h
            bgPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xFF0E1210.toInt(), 0xFF151C18.toInt(), 0xFF0E1210.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            sidebarPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                0xFF161B18.toInt(), 0xFF0F1210.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Subtle ambient emerald & amber studio lights in workspace
        ambientGlowPaint.shader = RadialGradient(
            w * 0.85f, h * 0.12f, w * 0.45f,
            0x22469A5A, 0x00000000, Shader.TileMode.CLAMP
        )
        canvas.drawRect(sidebarW, 0f, w, h, ambientGlowPaint)

        ambientGlowPaint.shader = RadialGradient(
            sidebarW + (w - sidebarW) * 0.2f, h * 0.88f, w * 0.4f,
            0x18C88240, 0x00000000, Shader.TileMode.CLAMP
        )
        canvas.drawRect(sidebarW, 0f, w, h, ambientGlowPaint)
        ambientGlowPaint.shader = null

        // Subtle architectural grid & nodes across workspace
        val step = dp(28f)
        var gx = sidebarW + step
        while (gx < w) {
            canvas.drawLine(gx, 0f, gx, h, gridLinePaint)
            gx += step
        }
        var gy = step
        while (gy < h) {
            canvas.drawLine(sidebarW, gy, w, gy, gridLinePaint)
            gy += step
        }
    }

    private fun drawTopStudioHeader(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        headerH: Float,
        hw: JvmOptimizer.HardwareProfile
    ) {
        tmpRect.set(left, top, right, top + headerH)
        drawStudioCard(canvas, tmpRect, dp(8f), isHighlighted = false)

        // Left: Page icon + title + subtitle
        val iconCx = left + dp(16f)
        val iconCy = tmpRect.centerY()
        dotPaint.color = 0xFF28382C.toInt()
        canvas.drawCircle(iconCx, iconCy, dp(10f), dotPaint)
        drawNavVectorIcon(canvas, activePage.iconType, iconCx, iconCy, dp(5.5f), 0xFF66D07A.toInt())

        val titleX = left + dp(32f)
        drawFittedText(
            canvas,
            "${activePage.label}  •  ${activePage.subtitle}",
            titleX,
            tmpRect.centerY() + dp(3.8f),
            (right - left) * 0.50f,
            bodyBoldPaint
        )

        // Right: 3 Tactical Hardware Status Capsules
        val capH = headerH - dp(8f)
        val capY = top + dp(4f)
        val cap1W = dp(122f)
        val cap2W = dp(112f)
        val cap3W = dp(126f)

        // Capsule 3 (Rightmost): RAM & ABI
        dstRect.set(right - dp(6f) - cap3W, capY, right - dp(6f), capY + capH)
        drawTactileButton(canvas, dstRect, dp(5f), ButtonStyle.SUBTLE)
        dotPaint.color = 0xFF64B5F6.toInt()
        canvas.drawCircle(dstRect.left + dp(9f), dstRect.centerY(), dp(3f), dotPaint)
        badgeTextPaint.color = 0xFFE8ECE9.toInt()
        drawFittedText(
            canvas,
            "RAM ${hw.allocatedHeapMb}M • ${if (hw.is64Bit) "ARM64" else "ARM32"}",
            dstRect.left + dp(16f),
            dstRect.centerY() + dp(3.2f),
            cap3W - dp(20f),
            badgeTextPaint
        )

        // Capsule 2 (Middle): Resolution Scale
        val c2Right = dstRect.left - dp(6f)
        dstRect.set(c2Right - cap2W, capY, c2Right, capY + capH)
        drawTactileButton(canvas, dstRect, dp(5f), ButtonStyle.SUBTLE)
        dotPaint.color = 0xFFF2AA52.toInt()
        canvas.drawCircle(dstRect.left + dp(9f), dstRect.centerY(), dp(3f), dotPaint)
        drawFittedText(
            canvas,
            "SKALA RENDER ${hw.resolutionScalePct}%",
            dstRect.left + dp(16f),
            dstRect.centerY() + dp(3.2f),
            cap2W - dp(20f),
            badgeTextPaint
        )

        // Capsule 1 (Left): C++ Turbo Engine Status
        val c1Right = dstRect.left - dp(6f)
        if (c1Right - cap1W > titleX + dp(140f)) {
            dstRect.set(c1Right - cap1W, capY, c1Right, capY + capH)
            drawTactileButton(canvas, dstRect, dp(5f), ButtonStyle.SUBTLE)
            dotPaint.color = 0xFF5CE074.toInt()
            canvas.drawCircle(dstRect.left + dp(9f), dstRect.centerY(), dp(3f), dotPaint)
            drawFittedText(
                canvas,
                "C++ TURBO .SO AKTIF",
                dstRect.left + dp(16f),
                dstRect.centerY() + dp(3.2f),
                cap1W - dp(20f),
                badgeTextPaint
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sidebarW = dp(178f).coerceAtMost(w * 0.25f)
        drawAtmosphericBackground(canvas, w, h, sidebarW)

        // =========================================================================
        // 1. LEFT NAVIGATION SIDEBAR WITH SCULPTED ICON WELLS & STATUS CARD
        // =========================================================================
        canvas.drawRect(0f, 0f, sidebarW, h, sidebarPaint)
        canvas.drawLine(sidebarW - dp(1f), 0f, sidebarW - dp(1f), h, borderPaint)

        val sbPad = dp(10f)
        var sbY = dp(10f)

        // Sculpted Brand Header Card inside Sidebar
        val brandCardH = dp(42f)
        tmpRect.set(sbPad, sbY, sidebarW - sbPad, sbY + brandCardH)
        drawStudioCard(canvas, tmpRect, dp(8f), isHighlighted = true)

        markBmp?.let { bmp ->
            val iconSz = dp(26f)
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(sbPad + dp(8f), sbY + (brandCardH - iconSz) * 0.5f, sbPad + dp(8f) + iconSz, sbY + (brandCardH + iconSz) * 0.5f)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            drawFittedText(canvas, "MINECRAFT", dstRect.right + dp(8f), sbY + dp(18f), sidebarW - iconSz - dp(32f), bodyBoldPaint)
            drawFittedText(canvas, "1.20.4 • Fabric Studio", dstRect.right + dp(8f), sbY + dp(31f), sidebarW - iconSz - dp(32f), mutedPaint)
        } ?: run {
            drawFittedText(canvas, "MINECRAFT 1.20.4", sbPad + dp(10f), sbY + dp(25f), sidebarW - sbPad * 2f, bodyBoldPaint)
        }

        sbY += brandCardH + dp(8f)

        val navBtnH = ((h - sbY - dp(40f)) / NavPage.values().size).coerceIn(dp(29f), dp(39f))
        for ((idx, page) in NavPage.values().withIndex()) {
            val top = sbY + idx * (navBtnH + dp(4f))
            navRects[idx].set(sbPad, top, sidebarW - sbPad, top + navBtnH - dp(1.5f))
            val isSelected = (activePage == page)

            drawTactileButton(
                canvas,
                navRects[idx],
                dp(7f),
                if (isSelected) ButtonStyle.EMERALD else ButtonStyle.SUBTLE
            )

            if (isSelected) {
                // Glowing left accent bar
                dstRect.set(
                    navRects[idx].left + dp(3.5f),
                    navRects[idx].top + dp(5f),
                    navRects[idx].left + dp(6.5f),
                    navRects[idx].bottom - dp(5f)
                )
                dotPaint.color = 0xFFE8FFF0.toInt()
                canvas.drawRoundRect(dstRect, dp(2f), dp(2f), dotPaint)
            }

            // Icon well circle
            val iconCx = navRects[idx].left + dp(19f)
            val iconCy = navRects[idx].centerY()
            dotPaint.color = if (isSelected) 0x33000000 else 0xFF161A17.toInt()
            canvas.drawCircle(iconCx, iconCy, dp(9.5f), dotPaint)

            drawNavVectorIcon(
                canvas,
                page.iconType,
                iconCx,
                iconCy,
                dp(5.8f),
                if (isSelected) 0xFFFFFFFF.toInt() else 0xFFB0BAB2.toInt()
            )

            btnTextPaint.textAlign = Paint.Align.LEFT
            btnTextPaint.color = if (isSelected) 0xFFFFFFFF.toInt() else 0xFFD8DFDA.toInt()
            drawFittedText(
                canvas,
                page.label,
                navRects[idx].left + dp(33f),
                navRects[idx].centerY() + dp(3.5f),
                navRects[idx].width() - dp(38f),
                btnTextPaint
            )
            btnTextPaint.textAlign = Paint.Align.CENTER
            btnTextPaint.color = 0xFFF6F4EE.toInt()
        }

        // Bottom Sidebar Telemetry Capsule
        tmpRect.set(sbPad, h - dp(30f), sidebarW - sbPad, h - dp(8f))
        drawTactileButton(canvas, tmpRect, dp(6f), ButtonStyle.SUBTLE)
        dotPaint.color = if (readiness.isReadyToLaunch) 0xFF5CE074.toInt() else 0xFFF2AA52.toInt()
        canvas.drawCircle(tmpRect.left + dp(10f), tmpRect.centerY(), dp(3.2f), dotPaint)
        drawFittedText(
            canvas,
            "v0.8.3-release • ${if (android.os.Process.is64Bit()) "64-BIT" else "32-BIT"}",
            tmpRect.left + dp(18f),
            tmpRect.centerY() + dp(3.2f),
            tmpRect.width() - dp(24f),
            mutedPaint
        )

        // =========================================================================
        // 2. TOP STUDIO HEADER BAR + MAIN WORKSPACE CONTENT AREA
        // =========================================================================
        val hw = JvmOptimizer.detectHardware(context)
        val contentLeft = sidebarW + dp(12f)
        val contentRight = w - dp(12f)
        val headerTop = dp(9f)
        val headerH = dp(31f)
        drawTopStudioHeader(canvas, contentLeft, headerTop, contentRight, headerH, hw)

        val contentTop = headerTop + headerH + dp(8f)
        val contentBottom = h - dp(10f)

        when (activePage) {
            NavPage.HOME -> drawHomePage(canvas, contentLeft, contentTop, contentRight, contentBottom, hw)
            NavPage.MEMORY_ENGINE -> drawMemoryAndEnginePage(canvas, contentLeft, contentTop, contentRight, contentBottom, hw)
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
            drawTactileButton(canvas, bannerRect, dp(8f), ButtonStyle.EMERALD)
            drawFittedText(canvas, msg, bannerRect.left + dp(14f), bannerRect.centerY() + dp(3.8f), bw - dp(28f), bodyBoldPaint)
        }
    }

    // =========================================================================
    // PAGE 1: BERANDA (HERO BANNER + 4-CARD TELEMETRY GRID + LAUNCH CONSOLE)
    // =========================================================================
    private fun drawHomePage(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        hw: JvmOptimizer.HardwareProfile
    ) {
        val totalW = right - left
        val totalH = bottom - top
        val effectiveRenderer = RendererProfileManager.resolveEffectiveBackend(context)
        val activeAcc = AccountManager.getActiveAccount(context)

        // 1. Top Hero Banner Card (38% of height)
        val heroH = (totalH * 0.39f).coerceIn(dp(92f), dp(136f))
        tmpRect.set(left, top, right, top + heroH)
        drawStudioCard(canvas, tmpRect, dp(10f), isHighlighted = true)

        worldBannerBmp?.let { bBmp ->
            srcRect.set(0, (bBmp.height * 0.22f).toInt(), bBmp.width, (bBmp.height * 0.78f).toInt())
            canvas.save()
            canvas.clipRect(tmpRect)
            canvas.drawBitmap(bBmp, srcRect, tmpRect, bitmapPaint)
            scrimPaint.shader = LinearGradient(
                tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom,
                intArrayOf(0xEE101512.toInt(), 0xCC141B16.toInt(), 0xE6101512.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(tmpRect, scrimPaint)
            scrimPaint.shader = null
            canvas.restore()
            canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), activeBorderPaint)
        }

        var heroTextLeft = left + dp(14f)
        mascotBmp?.let { mBmp ->
            val mSz = (heroH - dp(20f)).coerceIn(dp(60f), dp(96f))
            srcRect.set(0, 0, mBmp.width, mBmp.height)
            dstRect.set(left + dp(12f), top + (heroH - mSz) * 0.5f, left + dp(12f) + mSz, top + (heroH + mSz) * 0.5f)
            canvas.drawBitmap(mBmp, srcRect, dstRect, bitmapPaint)
            canvas.drawRoundRect(dstRect, dp(8f), dp(8f), activeBorderPaint)
            heroTextLeft = dstRect.right + dp(14f)
        }

        val maxHeroTextW = (right - dp(14f)) - heroTextLeft
        drawFittedText(
            canvas,
            "Minecraft: Java Edition 1.20.4 (Fabric Studio)",
            heroTextLeft,
            top + dp(24f),
            maxHeroTextW,
            h1Paint
        )
        drawFittedText(
            canvas,
            "Renderer: ${effectiveRenderer.displayName}  •  Skala Hardware: ${hw.resolutionScalePct}%  •  SoC: ${hw.socModel}",
            heroTextLeft,
            top + dp(42f),
            maxHeroTextW,
            bodyPaint
        )

        val pillY = top + heroH - dp(34f)
        val pillH = dp(25f)
        val pillW = ((maxHeroTextW - dp(16f)) / 3f).coerceAtMost(dp(195f))

        homeQuickAccountRect.set(heroTextLeft, pillY, heroTextLeft + pillW, pillY + pillH)
        drawTactileButton(canvas, homeQuickAccountRect, dp(6f), ButtonStyle.ELEVATED)
        drawNavVectorIcon(canvas, 4, homeQuickAccountRect.left + dp(11f), homeQuickAccountRect.centerY(), dp(5f), 0xFF68D47E.toInt())
        drawFittedText(
            canvas,
            "${activeAcc.username} [Akun]",
            homeQuickAccountRect.left + dp(21f),
            homeQuickAccountRect.centerY() + dp(3.5f),
            pillW - dp(26f),
            bodyBoldPaint
        )

        homeQuickRamRect.set(homeQuickAccountRect.right + dp(8f), pillY, homeQuickAccountRect.right + dp(8f) + pillW, pillY + pillH)
        drawTactileButton(canvas, homeQuickRamRect, dp(6f), ButtonStyle.ELEVATED)
        drawNavVectorIcon(canvas, 1, homeQuickRamRect.left + dp(11f), homeQuickRamRect.centerY(), dp(5f), 0xFF64B5F6.toInt())
        drawFittedText(
            canvas,
            "RAM: ${hw.allocatedHeapMb} MB [Atur]",
            homeQuickRamRect.left + dp(21f),
            homeQuickRamRect.centerY() + dp(3.5f),
            pillW - dp(26f),
            bodyBoldPaint
        )

        val activeBoost = PerformanceBooster.getActiveProfile(context)
        homeQuickBoostRect.set(homeQuickRamRect.right + dp(8f), pillY, homeQuickRamRect.right + dp(8f) + pillW, pillY + pillH)
        drawTactileButton(canvas, homeQuickBoostRect, dp(6f), ButtonStyle.AMBER)
        drawFittedText(
            canvas,
            "BOOST: ${activeBoost.shortTag} [AKTIFKAN]",
            homeQuickBoostRect.left + dp(10f),
            homeQuickBoostRect.centerY() + dp(3.5f),
            pillW - dp(20f),
            bodyBoldPaint
        )

        // 2. Middle 4-Card Engine Readiness Telemetry Grid
        val gridTop = top + heroH + dp(8f)
        val gridH = dp(56f)
        val gap = dp(8f)
        val cardW = (totalW - gap * 3f) / 4f

        val statusItems = listOf(
            Triple("OpenJDK 17 Runtime", readiness.isOpenJdkRuntimeReady, if (readiness.isOpenJdkRuntimeReady) "Siap (${hw.abi})" else "Belum Terpasang"),
            Triple("Client 1.20.4 & Libs", readiness.isClientJarReady, if (readiness.isClientJarReady) "Terverifikasi Lengkap" else "Perlu Diunduh"),
            Triple("Stack Optimasi & C++", readiness.installedModsCount >= 6, "${readiness.installedModsCount}/9 Mod + Turbo .SO"),
            Triple("Asset Suara & Tekstur", readiness.isAssetIndexReady, if (readiness.isAssetIndexReady) "Index 12.json Siap" else "Belum Lengkap")
        )

        for ((i, item) in statusItems.withIndex()) {
            val cLeft = left + i * (cardW + gap)
            tmpRect.set(cLeft, gridTop, cLeft + cardW, gridTop + gridH)
            drawStudioCard(canvas, tmpRect, dp(8f), isHighlighted = item.second, useAmberHighlight = !item.second)

            val badgeText = if (item.second) "SIAP" else "UNDUH"
            val badgeW = dp(48f)
            val badgeH = dp(15f)
            dstRect.set(cLeft + dp(10f), gridTop + dp(7f), cLeft + dp(10f) + badgeW, gridTop + dp(7f) + badgeH)
            drawTactileButton(canvas, dstRect, dp(4f), if (item.second) ButtonStyle.EMERALD else ButtonStyle.AMBER)
            btnTextPaint.textSize = dp(8.4f)
            canvas.drawText(badgeText, dstRect.centerX(), dstRect.centerY() + dp(3f), btnTextPaint)
            btnTextPaint.textSize = dp(10.1f)

            // Status LED dot on top-right of card
            dotPaint.color = if (item.second) 0xFF5CE074.toInt() else 0xFFF2AA52.toInt()
            canvas.drawCircle(cLeft + cardW - dp(12f), gridTop + dp(14f), dp(3.5f), dotPaint)

            drawFittedText(canvas, item.first, cLeft + dp(10f), gridTop + dp(35f), cardW - dp(20f), bodyBoldPaint)
            drawFittedText(canvas, item.third, cLeft + dp(10f), gridTop + dp(49f), cardW - dp(20f), mutedPaint)
        }

        // 3. Bottom Action & Download Command Console Card
        val botTop = gridTop + gridH + dp(8f)
        tmpRect.set(left, botTop, right, bottom)
        drawStudioCard(canvas, tmpRect, dp(10f), isHighlighted = readiness.isReadyToLaunch)

        val innerL = left + dp(14f)
        val innerR = right - dp(14f)
        val innerW = innerR - innerL

        val leftZoneW = innerW * 0.58f
        val modeBtnW = (leftZoneW - dp(8f)) * 0.5f
        val modeBtnH = dp(28f)
        val modeY = botTop + dp(10f)

        homeLiteModeRect.set(innerL, modeY, innerL + modeBtnW, modeY + modeBtnH)
        homeFullModeRect.set(homeLiteModeRect.right + dp(8f), modeY, innerL + leftZoneW, modeY + modeBtnH)

        drawTactileButton(canvas, homeLiteModeRect, dp(6f), if (skipMusicLite) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("MODE HEMAT (~245 MB)", homeLiteModeRect.centerX(), homeLiteModeRect.centerY() + dp(3.5f), btnTextPaint)

        drawTactileButton(canvas, homeFullModeRect, dp(6f), if (!skipMusicLite) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("MODE PENUH (~695 MB)", homeFullModeRect.centerX(), homeFullModeRect.centerY() + dp(3.5f), btnTextPaint)

        val pct = downloadState.percent.coerceIn(0, 100)
        val progY = modeY + modeBtnH + dp(15f)
        val statusStr = when {
            downloadState.isRunning -> "Mengunduh ($pct%): ${downloadState.phaseTitle} - ${downloadState.detailText}"
            readiness.isReadyToLaunch -> "Seluruh komponen OpenJDK 17 & Minecraft 1.20.4 siap. Tekan tombol peluncuran di kanan."
            else -> "Mode Hemat membuat stub audio .ogg hening 108-byte yang 100% aman dari crash suara."
        }
        drawFittedText(canvas, statusStr, innerL, progY, leftZoneW, bodyPaint)

        val barTop = (progY + dp(7f)).coerceAtMost(bottom - dp(16f))
        val barH = dp(10f)
        dstRect.set(innerL, barTop, innerL + leftZoneW, barTop + barH)
        canvas.drawRoundRect(dstRect, dp(5f), dp(5f), trackPaint)
        canvas.drawRoundRect(dstRect, dp(5f), dp(5f), borderPaint)
        val fillRatio = if (readiness.isReadyToLaunch && !downloadState.isRunning) 1f else (pct / 100f)
        if (fillRatio > 0f) {
            dstRect.set(innerL, barTop, innerL + leftZoneW * fillRatio, barTop + barH)
            drawTactileButton(canvas, dstRect, dp(5f), ButtonStyle.EMERALD)
        }

        val actionLeft = innerL + leftZoneW + dp(14f)
        homePrimaryActionRect.set(actionLeft, botTop + dp(10f), innerR, bottom - dp(10f))
        drawTactileButton(
            canvas,
            homePrimaryActionRect,
            dp(9f),
            if (downloadState.isRunning) ButtonStyle.AMBER else ButtonStyle.EMERALD
        )

        val mainBtnTitle = when {
            downloadState.isRunning -> "UNDUHAN BERJALAN ($pct%)"
            readiness.isReadyToLaunch -> "MAIN SEKARANG"
            else -> "UNDUH / LANJUTKAN DATA"
        }
        val mainBtnSub = when {
            downloadState.isRunning -> "Berjalan otomatis di latar belakang"
            readiness.isReadyToLaunch -> "Minecraft 1.20.4 • Skala ${hw.resolutionScalePct}% • ${hw.allocatedHeapMb} MB"
            else -> if (skipMusicLite) "Paket Hemat ~245 MB (Resume Aktif)" else "Paket Penuh ~695 MB (Resume Aktif)"
        }
        btnTextPaint.textSize = dp(13f)
        canvas.drawText(mainBtnTitle, homePrimaryActionRect.centerX(), homePrimaryActionRect.centerY() - dp(3f), btnTextPaint)
        btnTextPaint.textSize = dp(9.3f)
        canvas.drawText(
            fitText(mainBtnSub, btnTextPaint, homePrimaryActionRect.width() - dp(18f)),
            homePrimaryActionRect.centerX(),
            homePrimaryActionRect.centerY() + dp(12f),
            btnTextPaint
        )
        btnTextPaint.textSize = dp(10.1f)
    }

    // =========================================================================
    // PAGE 2: MEMORI & ENGINE (RAM SLIDER, RESOLUTION SLIDER, RENDERER, BOOST)
    // =========================================================================
    private fun drawMemoryAndEnginePage(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        hw: JvmOptimizer.HardwareProfile
    ) {
        val totalW = right - left
        val colGap = dp(10f)
        val leftColW = (totalW - colGap) * 0.52f
        val rightColLeft = left + leftColW + colGap
        val rightColW = right - rightColLeft

        tmpRect.set(left, top, left + leftColW, bottom)
        drawStudioCard(canvas, tmpRect, dp(10f), isHighlighted = true)

        val pad = dp(12f)
        val inL = left + pad
        val inW = leftColW - pad * 2f
        var y = top + dp(20f)

        val modeLabel = if (hw.isCustomHeap) "Custom" else "Saran Default"
        drawFittedText(canvas, "1. Alokasi Memori JVM (-Xmx): ${hw.allocatedHeapMb} MB ($modeLabel)", inL, y, inW, h2Paint)
        y += dp(13f)
        drawFittedText(
            canvas,
            "Saran HP ini: ${hw.recommendedHeapMb} MB  •  Total RAM Fisik: ${hw.totalRamMb} MB",
            inL,
            y,
            inW,
            mutedPaint
        )

        y += dp(7f)
        ramSliderRect.set(inL, y, inL + inW, y + dp(24f))
        canvas.drawRoundRect(ramSliderRect, dp(7f), dp(7f), trackPaint)
        canvas.drawRoundRect(ramSliderRect, dp(7f), dp(7f), borderPaint)
        val ratio = ((hw.allocatedHeapMb - hw.minSafeHeapMb).toFloat() /
            (hw.maxSafeHeapMb - hw.minSafeHeapMb).coerceAtLeast(1)).coerceIn(0f, 1f)
        dstRect.set(inL, y, (inL + inW * ratio).coerceAtLeast(inL + dp(16f)), y + dp(24f))
        drawTactileButton(canvas, dstRect, dp(7f), ButtonStyle.EMERALD)
        canvas.drawText(
            "Geser Slider RAM (${hw.minSafeHeapMb} MB - ${hw.maxSafeHeapMb} MB)",
            ramSliderRect.centerX(),
            ramSliderRect.centerY() + dp(3.5f),
            btnTextPaint
        )

        y += dp(29f)
        val stepW = dp(68f)
        val defW = inW - stepW * 2f - dp(10f)
        val stepH = dp(25f)
        ramMinusBtnRect.set(inL, y, inL + stepW, y + stepH)
        ramDefaultBtnRect.set(ramMinusBtnRect.right + dp(5f), y, ramMinusBtnRect.right + dp(5f) + defW, y + stepH)
        ramPlusBtnRect.set(ramDefaultBtnRect.right + dp(5f), y, inL + inW, y + stepH)

        drawTactileButton(canvas, ramMinusBtnRect, dp(6f), ButtonStyle.ELEVATED)
        canvas.drawText("- 128 MB", ramMinusBtnRect.centerX(), ramMinusBtnRect.centerY() + dp(3.5f), btnTextPaint)

        drawTactileButton(canvas, ramDefaultBtnRect, dp(6f), if (!hw.isCustomHeap) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("DEFAULT (${hw.recommendedHeapMb} MB)", ramDefaultBtnRect.centerX(), ramDefaultBtnRect.centerY() + dp(3.5f), btnTextPaint)

        drawTactileButton(canvas, ramPlusBtnRect, dp(6f), ButtonStyle.ELEVATED)
        canvas.drawText("+ 128 MB", ramPlusBtnRect.centerX(), ramPlusBtnRect.centerY() + dp(3.5f), btnTextPaint)

        y += dp(30f)
        ramPresetRects.clear()
        val presets = if (hw.is64Bit) listOf(1024, 1536, 2048, 3072, 4096) else listOf(512, 640, 768, 896, 1024)
        val pGap = dp(5f)
        val pW = (inW - pGap * (presets.size - 1)) / presets.size
        for ((idx, mb) in presets.withIndex()) {
            val px = inL + idx * (pW + pGap)
            val r = RectF(px, y, px + pW, y + dp(22f))
            val isCur = (hw.allocatedHeapMb == mb)
            drawTactileButton(canvas, r, dp(5f), if (isCur) ButtonStyle.EMERALD else ButtonStyle.SUBTLE)
            canvas.drawText("${mb}M", r.centerX(), r.centerY() + dp(3.3f), btnTextPaint)
            ramPresetRects.add(mb to r)
        }

        // 2. Interactive Hardware Resolution Scale Slider + 4 Presets
        y += dp(34f)
        val dm = resources.displayMetrics
        val physW = maxOf(dm.widthPixels, dm.heightPixels)
        val physH = minOf(dm.widthPixels, dm.heightPixels)
        val scaledW = (((physW * (hw.resolutionScalePct / 100f)).toInt() + 1) and 1.inv())
        val scaledH = (((physH * (hw.resolutionScalePct / 100f)).toInt() + 1) and 1.inv())
        drawFittedText(
            canvas,
            "2. Skala Resolusi Hardware C++: ${hw.resolutionScalePct}% (${scaledW}x${scaledH} px)",
            inL,
            y,
            inW,
            h2Paint
        )
        y += dp(7f)
        resSliderRect.set(inL, y, inL + inW, y + dp(22f))
        canvas.drawRoundRect(resSliderRect, dp(6f), dp(6f), trackPaint)
        canvas.drawRoundRect(resSliderRect, dp(6f), dp(6f), borderPaint)
        val resRatio = ((hw.resolutionScalePct - 50) / 50f).coerceIn(0f, 1f)
        dstRect.set(inL, y, (inL + inW * resRatio).coerceAtLeast(inL + dp(16f)), y + dp(22f))
        drawTactileButton(canvas, dstRect, dp(6f), ButtonStyle.AMBER)
        canvas.drawText(
            "Geser Skala Resolusi 50% - 100% (Semakin Kecil = FPS Makin Tinggi)",
            resSliderRect.centerX(),
            resSliderRect.centerY() + dp(3.3f),
            btnTextPaint
        )

        y += dp(26f)
        val resW = (inW - dp(15f)) / 4f
        val resH = dp(24f)
        res50Rect.set(inL, y, inL + resW, y + resH)
        res60Rect.set(res50Rect.right + dp(5f), y, res50Rect.right + dp(5f) + resW, y + resH)
        res75Rect.set(res60Rect.right + dp(5f), y, res60Rect.right + dp(5f) + resW, y + resH)
        res100Rect.set(res75Rect.right + dp(5f), y, inL + inW, y + resH)

        for ((r, pair) in listOf(
            res50Rect to (50 to "50% (Max)"),
            res60Rect to (60 to "60% (A2)"),
            res75Rect to (75 to "75% (HD)"),
            res100Rect to (100 to "100% (Full)")
        )) {
            val active = (hw.resolutionScalePct == pair.first)
            drawTactileButton(canvas, r, dp(5f), if (active) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
            canvas.drawText(pair.second, r.centerX(), r.centerY() + dp(3.3f), btnTextPaint)
        }

        // 3. Garbage Collector & Instant Boost Row
        y += dp(32f)
        val gcW = (inW - dp(6f)) * 0.5f
        gcG1Rect.set(inL, y, inL + gcW, y + dp(24f))
        gcZgcRect.set(gcG1Rect.right + dp(6f), y, inL + inW, y + dp(24f))

        drawTactileButton(canvas, gcG1Rect, dp(5f), if (!hw.useGenerationalZgc) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("G1GC (Stabil Semua HP)", gcG1Rect.centerX(), gcG1Rect.centerY() + dp(3.4f), btnTextPaint)

        drawTactileButton(canvas, gcZgcRect, dp(5f), if (hw.useGenerationalZgc) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("Generational ZGC (64-bit)", gcZgcRect.centerX(), gcZgcRect.centerY() + dp(3.4f), btnTextPaint)

        y += dp(28f)
        val bW = (inW - dp(10f)) / 3f
        val bH = dp(26f)
        val curBoost = PerformanceBooster.getActiveProfile(context)
        boostTurboRect.set(inL, y, inL + bW, y + bH)
        boostPvpRect.set(boostTurboRect.right + dp(5f), y, boostTurboRect.right + dp(5f) + bW, y + bH)
        boostCleanRamRect.set(boostPvpRect.right + dp(5f), y, inL + inW, y + bH)

        drawTactileButton(canvas, boostTurboRect, dp(6f), if (curBoost == PerformanceBooster.BoostProfile.TURBO_FPS) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("TURBO A2 (2 CHUNK)", boostTurboRect.centerX(), boostTurboRect.centerY() + dp(3.4f), btnTextPaint)

        drawTactileButton(canvas, boostPvpRect, dp(6f), if (curBoost == PerformanceBooster.BoostProfile.PVP_COMPETITIVE) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("PVP (6 CHUNK)", boostPvpRect.centerX(), boostPvpRect.centerY() + dp(3.4f), btnTextPaint)

        drawTactileButton(canvas, boostCleanRamRect, dp(6f), ButtonStyle.AMBER)
        canvas.drawText("BOOST RAM & C++", boostCleanRamRect.centerX(), boostCleanRamRect.centerY() + dp(3.4f), btnTextPaint)

        // RIGHT COLUMN CARD: Multi-Renderer Selector
        tmpRect.set(rightColLeft, top, right, bottom)
        drawStudioCard(canvas, tmpRect, dp(10f), isHighlighted = false)

        val rInL = rightColLeft + pad
        val rInW = rightColW - pad * 2f
        var ry = top + dp(20f)

        val selectedBackend = RendererProfileManager.getSelectedBackend(context)
        val effectiveBackend = RendererProfileManager.resolveEffectiveBackend(context)
        drawFittedText(canvas, "Pilih Arsitektur Renderer GPU (Aktif: ${effectiveBackend.id}):", rInL, ry, rInW, h2Paint)
        ry += dp(8f)

        val availH = bottom - ry - dp(10f)
        val itemH = ((availH - dp(24f)) / RendererProfileManager.RendererBackend.values().size).coerceIn(dp(34f), dp(48f))
        for ((idx, backend) in RendererProfileManager.RendererBackend.values().withIndex()) {
            rendererItemRects[idx].set(rInL, ry, rInL + rInW, ry + itemH)
            val isChosen = (selectedBackend == backend)
            drawTactileButton(
                canvas,
                rendererItemRects[idx],
                dp(7f),
                if (isChosen) ButtonStyle.EMERALD else ButtonStyle.SUBTLE
            )

            val tag = if (isChosen) "[AKTIF] " else ""
            drawFittedText(canvas, tag + backend.displayName, rInL + dp(10f), ry + dp(15f), rInW - dp(20f), bodyBoldPaint)
            drawFittedText(canvas, backend.shortDesc, rInL + dp(10f), ry + dp(29f), rInW - dp(20f), mutedPaint)
            ry += itemH + dp(5f)
        }
    }

    // =========================================================================
    // PAGE 3: HUB KONTEN LENGKAP (MODS, SHADER, TEKSTUR, MAP/DUNIA) & OPTIMASI INTI
    // =========================================================================
    private fun drawModsAndShadersPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val halfW = (totalW - dp(8f)) * 0.5f

        val subTabH = dp(27f)
        modsSubTabModrinthRect.set(left, top, left + halfW, top + subTabH)
        modsSubTabLocalRect.set(left + halfW + dp(8f), top, right, top + subTabH)

        drawTactileButton(canvas, modsSubTabModrinthRect, dp(6f), if (isModrinthOnlineTab) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("HUB UNDUH: MOD, SHADER, TEKSTUR & MAP (1.20.4)", modsSubTabModrinthRect.centerX(), modsSubTabModrinthRect.centerY() + dp(3.4f), btnTextPaint)

        drawTactileButton(canvas, modsSubTabLocalRect, dp(6f), if (!isModrinthOnlineTab) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("MOD OPTIMASI INTI BAWAAN & SHADERPACK", modsSubTabLocalRect.centerX(), modsSubTabLocalRect.centerY() + dp(3.4f), btnTextPaint)

        val bodyTop = top + subTabH + dp(6f)
        if (!isModrinthOnlineTab) {
            drawLocalModsAndShaderContent(canvas, left, bodyTop, right, bottom)
        } else {
            drawModrinthOnlineContent(canvas, left, bodyTop, right, bottom)
        }
    }

    private fun drawLocalModsAndShaderContent(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val mods = RendererProfileManager.getModStackStatusCompat(context)

        val shaderCardH = dp(48f)
        tmpRect.set(left, top, right, top + shaderCardH)
        drawStudioCard(canvas, tmpRect, dp(9f), isHighlighted = false)

        val shadersOn = RendererProfileManager.isShaderSupportEnabled(context)
        val activePack = RendererProfileManager.getActiveShaderpackName(context)
        drawFittedText(
            canvas,
            "Manajer Shaderpack  •  Paket Aktif: $activePack",
            left + dp(14f),
            top + dp(17f),
            totalW * 0.54f,
            h2Paint
        )
        drawFittedText(
            canvas,
            "Pelindung Kompatibilitas GL4ES 1.1.5 aktif otomatis menjaga stabilitas Mojang Splash & Dunia 100%.",
            left + dp(14f),
            top + dp(33f),
            totalW * 0.54f,
            mutedPaint
        )

        val btnW = dp(98f)
        val btnH = dp(28f)
        val btnY = top + (shaderCardH - btnH) * 0.5f
        shaderSyncRect.set(right - dp(12f) - btnW, btnY, right - dp(12f), btnY + btnH)
        shaderCycleRect.set(shaderSyncRect.left - dp(6f) - btnW, btnY, shaderSyncRect.left - dp(6f), btnY + btnH)
        shaderToggleRect.set(shaderCycleRect.left - dp(6f) - btnW, btnY, shaderCycleRect.left - dp(6f), btnY + btnH)

        drawTactileButton(canvas, shaderToggleRect, dp(6f), if (shadersOn) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText(if (shadersOn) "SHADER: ON" else "SHADER: OFF", shaderToggleRect.centerX(), shaderToggleRect.centerY() + dp(3.4f), btnTextPaint)

        drawTactileButton(canvas, shaderCycleRect, dp(6f), ButtonStyle.ELEVATED)
        canvas.drawText("GANTI SHADER", shaderCycleRect.centerX(), shaderCycleRect.centerY() + dp(3.4f), btnTextPaint)

        drawTactileButton(canvas, shaderSyncRect, dp(6f), ButtonStyle.AMBER)
        canvas.drawText("SINKRONKAN", shaderSyncRect.centerX(), shaderSyncRect.centerY() + dp(3.4f), btnTextPaint)

        // Scrollable 2-Column Mod Grid
        val gridTop = top + shaderCardH + dp(7f)
        canvas.save()
        canvas.clipRect(left, gridTop, right, bottom)

        val cols = 2
        val gapX = dp(8f)
        val gapY = dp(7f)
        val cellW = (totalW - gapX) / cols
        val cellH = dp(44f)

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
                drawStudioCard(canvas, r, dp(8f), isHighlighted = mod.isEnabled)

                val pillW = dp(74f)
                val pillH = dp(23f)
                val pillRect = RectF(r.right - dp(9f) - pillW, r.centerY() - pillH * 0.5f, r.right - dp(9f), r.centerY() + pillH * 0.5f)
                drawTactileButton(canvas, pillRect, dp(6f), if (mod.isEnabled) ButtonStyle.EMERALD else ButtonStyle.SUBTLE)
                canvas.drawText(if (mod.isEnabled) "AKTIF" else "NONAKTIF", pillRect.centerX(), pillRect.centerY() + dp(3.3f), btnTextPaint)

                val textMaxW = cellW - pillW - dp(24f)
                drawFittedText(canvas, "${mod.title} [${mod.category}]", cx + dp(11f), cy + dp(17f), textMaxW, bodyBoldPaint)
                drawFittedText(canvas, mod.summary, cx + dp(11f), cy + dp(32f), textMaxW, mutedPaint)
            }
            modCardRects.add(mod.slug to r)
        }
        pageContentHeight = maxBottom - gridTop
        canvas.restore()
    }

    private fun drawModrinthOnlineContent(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        if (modrinthCatalog.isEmpty()) {
            modrinthCatalog = ModrinthSyncManager.getCatalogWithDiskState(context)
        }

        // 1. 4-Category Switcher Bar: [MODS (.JAR)]  [SHADER (.ZIP)]  [TEKSTUR (RP)]  [MAP & DUNIA]
        val cats = ModrinthSyncManager.ContentCategory.values()
        val catGap = dp(6f)
        val catW = (totalW - catGap * (cats.size - 1)) / cats.size
        val catH = dp(26f)
        val activeCat = ModrinthSyncManager.activeCategory
        for ((idx, cat) in cats.withIndex()) {
            val cl = left + idx * (catW + catGap)
            modrinthCategoryRects[idx].set(cl, top, cl + catW, top + catH)
            val isSel = (cat == activeCat)
            drawTactileButton(
                canvas,
                modrinthCategoryRects[idx],
                dp(6f),
                if (isSel) ButtonStyle.EMERALD else ButtonStyle.SUBTLE
            )
            canvas.drawText(
                cat.fullTitle,
                modrinthCategoryRects[idx].centerX(),
                modrinthCategoryRects[idx].centerY() + dp(3.3f),
                btnTextPaint
            )
        }

        // 2. Search Bar + [CARI] + [MUAT +30 LAGI] + [SEGARKAN]
        val barTop = top + catH + dp(6f)
        val barH = dp(28f)
        val moreW = dp(118f)
        val syncW = dp(96f)
        val cariW = dp(68f)
        val inputW = totalW - moreW - syncW - cariW - dp(18f)

        modrinthSearchInputRect.set(left, barTop, left + inputW, barTop + barH)
        modrinthSearchBtnRect.set(modrinthSearchInputRect.right + dp(6f), barTop, modrinthSearchInputRect.right + dp(6f) + cariW, barTop + barH)
        modrinthLoadMoreBtnRect.set(modrinthSearchBtnRect.right + dp(6f), barTop, modrinthSearchBtnRect.right + dp(6f) + moreW, barTop + barH)
        modrinthSyncAllBtnRect.set(modrinthLoadMoreBtnRect.right + dp(6f), barTop, right, barTop + barH)

        drawStudioCard(canvas, modrinthSearchInputRect, dp(6f), isHighlighted = true)
        val qDisplay = if (modrinthQuery.isBlank()) {
            "Cari ${activeCat.fullTitle} 1.20.4 (Ketuk IME)..."
        } else {
            "Cari: $modrinthQuery"
        }
        drawFittedText(canvas, qDisplay, modrinthSearchInputRect.left + dp(10f), modrinthSearchInputRect.centerY() + dp(3.4f), inputW - dp(20f), bodyBoldPaint)

        drawTactileButton(canvas, modrinthSearchBtnRect, dp(6f), ButtonStyle.EMERALD)
        canvas.drawText("CARI", modrinthSearchBtnRect.centerX(), modrinthSearchBtnRect.centerY() + dp(3.4f), btnTextPaint)

        drawTactileButton(canvas, modrinthLoadMoreBtnRect, dp(6f), ButtonStyle.SLATE)
        canvas.drawText("+ MUAT 30 LAGI", modrinthLoadMoreBtnRect.centerX(), modrinthLoadMoreBtnRect.centerY() + dp(3.4f), btnTextPaint)

        drawTactileButton(canvas, modrinthSyncAllBtnRect, dp(6f), ButtonStyle.AMBER)
        canvas.drawText("SEGARKAN", modrinthSyncAllBtnRect.centerX(), modrinthSyncAllBtnRect.centerY() + dp(3.4f), btnTextPaint)

        val statusY = barTop + barH + dp(11f)
        drawFittedText(canvas, ModrinthSyncManager.syncStatusMessage, left + dp(4f), statusY, totalW, mutedPaint)

        // 3. 2-Column Scrollable Content Grid (Fits twice as many items neatly without clutter!)
        val listTop = statusY + dp(5f)
        canvas.save()
        canvas.clipRect(left, listTop, right, bottom)

        modrinthItemRects.clear()
        val cols = 2
        val gapX = dp(8f)
        val gapY = dp(6f)
        val cellW = (totalW - gapX) / cols
        val itemH = dp(44f)
        var maxBottom = listTop

        for ((idx, item) in modrinthCatalog.withIndex()) {
            val col = idx % cols
            val row = idx / cols
            val cx = left + col * (cellW + gapX)
            val cy = listTop + row * (itemH + gapY) - pageScrollY
            val r = RectF(cx, cy, cx + cellW, cy + itemH)
            maxBottom = max(maxBottom, listTop + (row + 1) * (itemH + gapY))

            if (r.bottom >= listTop && r.top <= bottom) {
                drawStudioCard(canvas, r, dp(8f), isHighlighted = item.isInstalled)

                val btnW = dp(86f)
                val actRect = RectF(r.right - dp(8f) - btnW, r.top + dp(8f), r.right - dp(8f), r.bottom - dp(8f))
                val isDownloading = (ModrinthSyncManager.activeDownloadSlug == item.slug)
                val actStyle = when {
                    isDownloading -> ButtonStyle.AMBER
                    item.isEnabled -> ButtonStyle.EMERALD
                    item.isInstalled -> ButtonStyle.AMBER
                    else -> ButtonStyle.SLATE
                }
                drawTactileButton(canvas, actRect, dp(6f), actStyle)
                val actLabel = when {
                    isDownloading -> "UNDUH..."
                    item.isEnabled -> "AKTIF [ON]"
                    item.isInstalled -> "MATI [OFF]"
                    else -> "+ PASANG"
                }
                canvas.drawText(actLabel, actRect.centerX(), actRect.centerY() + dp(3.3f), btnTextPaint)

                val textW = cellW - btnW - dp(24f)
                drawFittedText(
                    canvas,
                    "${item.title} [${item.categoryTag}]",
                    r.left + dp(10f),
                    r.top + dp(17f),
                    textW,
                    bodyBoldPaint
                )
                drawFittedText(
                    canvas,
                    "${item.downloadsText} • ${item.summary}",
                    r.left + dp(10f),
                    r.top + dp(32f),
                    textW,
                    mutedPaint
                )
                modrinthItemRects.add(item to r)
            }
        }
        pageContentHeight = maxBottom - listTop + dp(20f)
        canvas.restore()
    }

    // =========================================================================
    // PAGE 4: KONTROL, HUD & SHARE/IMPORT .JSON (3x2 TACTICAL GRID)
    // =========================================================================
    private fun drawControlsAndHudPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val totalW = right - left
        val gap = dp(8f)
        val cardW = (totalW - gap) * 0.5f
        val cardH = ((bottom - top - gap * 2f) / 3f).coerceAtLeast(dp(64f))

        val row1Y = top
        val row2Y = row1Y + cardH + gap
        val row3Y = row2Y + cardH + gap

        val cards = listOf(
            Triple(
                ctrlVisualHudBtnRect.apply { set(left, row1Y, left + cardW, row1Y + cardH) },
                "1. Visual Drag & Drop HUD Editor",
                "Geser posisi, ubah ukuran, opasitas, atau tambah tombol langsung di layar."
            ) to ("BUKA VISUAL HUD EDITOR" to ButtonStyle.EMERALD),
            Triple(
                ctrlKeybindModalBtnRect.apply { set(left + cardW + gap, row1Y, right, row1Y + cardH) },
                "2. Custom Keybind (104 Tombol PC & Gamepad)",
                "Bind tombol bebas ke A-Z, F1-F12, Mouse, Macro, Keyboard IME, atau Sidebar."
            ) to ("BUKA KEYBIND KEYBOARD PC" to ButtonStyle.EMERALD),
            Triple(
                ctrlSettingsModalBtnRect.apply { set(left, row2Y, left + cardW, row2Y + cardH) },
                "3. Opasitas HUD, Sensitivitas 60/120Hz & Kursor",
                "Geser slider Opasitas Tombol (8%-100%), Ukuran, & Sensitivitas Geser 60/120Hz."
            ) to ("ATUR OPASITAS & SENSITIVITAS" to ButtonStyle.EMERALD),
            Triple(
                ctrlExportShareJsonRect.apply { set(left + cardW + gap, row2Y, right, row2Y + cardH) },
                "4. Bagikan / Ekspor Kontrol .JSON",
                "Ekspor tata letak HUD ke file .json dan bagikan ke teman atau komunitas."
            ) to ("EKSPOR & BAGIKAN .JSON" to ButtonStyle.AMBER),
            Triple(
                ctrlImportJsonClipRect.apply { set(left, row3Y, left + cardW, row3Y + cardH) },
                "5. Impor Kontrol .JSON dari Clipboard",
                "Tempel teks JSON kontrol yang disalin dari teman untuk langsung dipakai."
            ) to ("IMPOR .JSON DARI CLIPBOARD" to ButtonStyle.SLATE),
            Triple(
                ctrlOverlayPermBtnRect.apply { set(left + cardW + gap, row3Y, right, row3Y + cardH) },
                "6. Panel Kambang Unduhan (Overlay)",
                "Izinkan panel progres melayang saat berpindah aplikasi selama mengunduh."
            ) to ((if (FloatingDownloadOverlay.canDrawOverlays(context)) "IZIN OVERLAY: SUDAH AKTIF" else "AKTIFKAN IZIN OVERLAY") to ButtonStyle.ELEVATED)
        )

        for ((info, pair) in cards) {
            val r = info.first
            drawStudioCard(canvas, r, dp(9f), isHighlighted = true)

            drawFittedText(canvas, info.second, r.left + dp(12f), r.top + dp(16f), r.width() - dp(24f), h2Paint)
            drawFittedText(canvas, info.third, r.left + dp(12f), r.top + dp(30f), r.width() - dp(24f), mutedPaint)

            val bRect = RectF(r.left + dp(12f), r.bottom - dp(29f), r.right - dp(12f), r.bottom - dp(6f))
            drawTactileButton(canvas, bRect, dp(6f), pair.second)
            canvas.drawText(pair.first, bRect.centerX(), bRect.centerY() + dp(3.4f), btnTextPaint)
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

        val profH = dp(70f)
        tmpRect.set(left, top, right, top + profH)
        drawStudioCard(canvas, tmpRect, dp(10f), isHighlighted = true)

        var textX = left + dp(14f)
        mascotBmp?.let { mBmp ->
            val sz = dp(48f)
            srcRect.set(0, 0, mBmp.width, mBmp.height)
            dstRect.set(left + dp(12f), top + dp(11f), left + dp(12f) + sz, top + dp(11f) + sz)
            canvas.drawBitmap(mBmp, srcRect, dstRect, bitmapPaint)
            canvas.drawRoundRect(dstRect, dp(8f), dp(8f), activeBorderPaint)
            textX = dstRect.right + dp(14f)
        }

        drawFittedText(
            canvas,
            "Akun Aktif: ${activeAcc.username}   •   Tipe: ${activeAcc.type.label}",
            textX,
            top + dp(25f),
            right - textX - dp(16f),
            h2Paint
        )
        drawFittedText(
            canvas,
            "UUID Pemain: ${activeAcc.uuid}   •   Keyboard IME Sistem Android Aktif",
            textX,
            top + dp(45f),
            right - textX - dp(16f),
            mutedPaint
        )

        val bodyTop = top + profH + dp(9f)
        val colGap = dp(10f)
        val colW = (totalW - colGap) * 0.5f
        val rightColL = left + colW + colGap

        // LEFT CARD: OFFLINE ACCOUNT + SYSTEM IME KEYBOARD
        tmpRect.set(left, bodyTop, left + colW, bottom)
        drawStudioCard(canvas, tmpRect, dp(10f), isHighlighted = !isMsaActive)

        val inL = left + dp(14f)
        val inW = colW - dp(28f)
        var ly = bodyTop + dp(22f)

        drawFittedText(canvas, "Opsi A: Akun Offline (Lokal / Server Non-Premium)", inL, ly, inW, h2Paint)
        ly += dp(15f)
        drawFittedText(canvas, "Ketuk kotak di bawah untuk mengetik Username dengan Keyboard IME Android:", inL, ly, inW, mutedPaint)
        ly += dp(9f)

        val imeFieldRect = RectF(inL, ly, inL + inW, ly + dp(38f))
        drawTactileButton(canvas, imeFieldRect, dp(7f), ButtonStyle.SUBTLE)
        drawFittedText(
            canvas,
            "Username: $editingUsername",
            imeFieldRect.left + dp(12f),
            imeFieldRect.centerY() + dp(3.8f),
            inW - dp(116f),
            h2Paint
        )
        val kbBadgeRect = RectF(imeFieldRect.right - dp(102f), imeFieldRect.top + dp(5f), imeFieldRect.right - dp(5f), imeFieldRect.bottom - dp(5f))
        drawTactileButton(canvas, kbBadgeRect, dp(5f), ButtonStyle.SLATE)
        canvas.drawText("KETIK (IME)", kbBadgeRect.centerX(), kbBadgeRect.centerY() + dp(3.4f), btnTextPaint)
        accountActionRects.add("OPEN_IME_USERNAME" to imeFieldRect)
        ly += dp(46f)

        val saveOfflineRect = RectF(inL, ly, inL + inW, ly + dp(34f))
        drawTactileButton(canvas, saveOfflineRect, dp(7f), if (!isMsaActive) ButtonStyle.EMERALD else ButtonStyle.ELEVATED)
        canvas.drawText("SIMPAN & GUNAKAN AKUN OFFLINE", saveOfflineRect.centerX(), saveOfflineRect.centerY() + dp(3.6f), btnTextPaint)
        accountActionRects.add("SAVE_OFFLINE" to saveOfflineRect)

        // RIGHT CARD: MICROSOFT OFFICIAL OAUTH2 LOGIN
        tmpRect.set(rightColL, bodyTop, right, bottom)
        drawStudioCard(canvas, tmpRect, dp(10f), isHighlighted = isMsaActive)

        val rInL = rightColL + dp(14f)
        val rInW = colW - dp(28f)
        var ry = bodyTop + dp(22f)

        drawFittedText(canvas, "Opsi B: Akun Microsoft Resmi (Xbox Live / Mojang)", rInL, ry, rInW, h2Paint)
        ry += dp(15f)
        drawFittedText(canvas, AccountManager.msaAuthStatusText, rInL, ry, rInW, bodyPaint)
        ry += dp(9f)

        val code = AccountManager.msaUserCode
        if (code.isNotBlank()) {
            val codeBox = RectF(rInL, ry, rInL + rInW, ry + dp(32f))
            drawTactileButton(canvas, codeBox, dp(7f), ButtonStyle.SLATE)
            canvas.drawText("KODE LOGIN MICROSOFT: $code (Tersalin)", codeBox.centerX(), codeBox.centerY() + dp(3.6f), btnTextPaint)
            accountActionRects.add("COPY_OPEN_MSA" to codeBox)
            ry += dp(39f)
        }

        val startMsaRect = RectF(rInL, ry, rInL + rInW, ry + dp(34f))
        drawTactileButton(canvas, startMsaRect, dp(7f), ButtonStyle.EMERALD)
        canvas.drawText(
            if (AccountManager.isMsaPolling) "BUKA BROWSER MICROSOFT.COM/LINK" else "LOGIN AKUN MICROSOFT RESMI (OAUTH2)",
            startMsaRect.centerX(),
            startMsaRect.centerY() + dp(3.6f),
            btnTextPaint
        )
        accountActionRects.add("START_MSA_LOGIN" to startMsaRect)
        ry += dp(41f)

        if (AccountManager.hasSavedMicrosoftAccount(context) || AccountManager.isMsaPolling) {
            val secRect = RectF(rInL, ry, rInL + rInW, ry + dp(30f))
            drawTactileButton(canvas, secRect, dp(7f), ButtonStyle.AMBER)
            val secLabel = if (AccountManager.isMsaPolling) "BATALKAN PROSES LOGIN" else "KELUAR (LOGOUT) AKUN MICROSOFT"
            canvas.drawText(secLabel, secRect.centerX(), secRect.centerY() + dp(3.5f), btnTextPaint)
            accountActionRects.add("MSA_SECONDARY" to secRect)
        }
    }

    // =========================================================================
    // PAGE 6: LOG DIAGNOSTIK (STRUCTURED MONOSPACE LOG CONSOLE)
    // =========================================================================
    private fun drawLogsPage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val btnW = dp(124f)
        val btnH = dp(28f)
        logCopyRect.set(right - btnW, top, right, top + btnH)
        logRefreshRect.set(logCopyRect.left - dp(8f) - btnW, top, logCopyRect.left - dp(8f), top + btnH)

        drawTactileButton(canvas, logRefreshRect, dp(7f), ButtonStyle.ELEVATED)
        canvas.drawText("SEGARKAN LOG", logRefreshRect.centerX(), logRefreshRect.centerY() + dp(3.5f), btnTextPaint)

        drawTactileButton(canvas, logCopyRect, dp(7f), ButtonStyle.EMERALD)
        canvas.drawText("SALIN LOG", logCopyRect.centerX(), logCopyRect.centerY() + dp(3.5f), btnTextPaint)

        drawFittedText(
            canvas,
            "File: ${VeloLogger.latestLogFile?.absolutePath ?: "latest.log"} (Geser ke atas/bawah untuk scroll)",
            left,
            top + dp(18f),
            logRefreshRect.left - left - dp(12f),
            bodyBoldPaint
        )

        val boxTop = top + btnH + dp(7f)
        tmpRect.set(left, boxTop, right, bottom)
        drawStudioCard(canvas, tmpRect, dp(9f), isHighlighted = false)

        canvas.save()
        canvas.clipRect(tmpRect.left + dp(10f), tmpRect.top + dp(8f), tmpRect.right - dp(10f), tmpRect.bottom - dp(8f))
        val lineH = dp(14f)
        var y = tmpRect.top + dp(20f) - logScrollY
        for (line in logLines) {
            if (y >= tmpRect.top && y <= tmpRect.bottom + lineH) {
                monoPaint.color = when {
                    line.contains("FATAL") || line.contains("Exception") || line.contains("Error") -> 0xFFFF8A80.toInt()
                    line.contains("PROGRES-BOOT") || line.contains("TAHAP") -> 0xFF80E296.toInt()
                    line.contains("EGL") || line.contains("GL4ES") -> 0xFF82B1FF.toInt()
                    else -> 0xFFE6E4DC.toInt()
                }
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
                isDraggingRamSlider = false
                isDraggingResSlider = false
                if (activePage == NavPage.MEMORY_ENGINE) {
                    if (ramSliderRect.contains(x, y)) {
                        isDraggingRamSlider = true
                        handleRamSliderDrag(x)
                    } else if (resSliderRect.contains(x, y)) {
                        isDraggingResSlider = true
                        handleResSliderDrag(x)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastTouchY - y
                if (kotlin.math.abs(y - touchDownY) > dp(8f) && !isDraggingRamSlider && !isDraggingResSlider) {
                    isDraggingScroll = true
                }
                if (activePage == NavPage.MEMORY_ENGINE && isDraggingRamSlider) {
                    handleRamSliderDrag(x)
                } else if (activePage == NavPage.MEMORY_ENGINE && isDraggingResSlider) {
                    handleResSliderDrag(x)
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingRamSlider || isDraggingResSlider) {
                    isDraggingRamSlider = false
                    isDraggingResSlider = false
                    postInvalidateOnAnimation()
                    return true
                }
                if (isDraggingScroll && (activePage == NavPage.LOGS || activePage == NavPage.MODS_SHADERS)) {
                    isDraggingScroll = false
                    return true
                }
                isDraggingScroll = false

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

    private fun handleResSliderDrag(x: Float) {
        val ratio = ((x - resSliderRect.left) / resSliderRect.width()).coerceIn(0f, 1f)
        val pct = (50f + ratio * 50f).roundToInt().coerceIn(50, 100)
        PerformanceBooster.setLiveResolutionScalePct(context, pct)
        postInvalidateOnAnimation()
    }

    private fun handleHomeTap(x: Float, y: Float) {
        when {
            homeQuickAccountRect.contains(x, y) -> activePage = NavPage.ACCOUNT
            homeQuickRamRect.contains(x, y) -> activePage = NavPage.MEMORY_ENGINE
            homeQuickBoostRect.contains(x, y) -> {
                val res = PerformanceBooster.runInstantBoost(context)
                showBannerMessage(res.summaryMessage)
            }
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
            res50Rect.contains(x, y) -> {
                PerformanceBooster.setLiveResolutionScalePct(context, 50)
                showBannerMessage("Skala Resolusi Hardware diatur ke 50% (FPS Tertinggi).")
            }
            res60Rect.contains(x, y) -> {
                PerformanceBooster.setLiveResolutionScalePct(context, 60)
                showBannerMessage("Skala Resolusi Hardware diatur ke 60% (Rekomendasi Redmi A2 / 32-bit).")
            }
            res75Rect.contains(x, y) -> {
                PerformanceBooster.setLiveResolutionScalePct(context, 75)
                showBannerMessage("Skala Resolusi Hardware diatur ke 75% (Seimbang HD).")
            }
            res100Rect.contains(x, y) -> {
                PerformanceBooster.setLiveResolutionScalePct(context, 100)
                showBannerMessage("Skala Resolusi Hardware diatur ke 100% (Resolusi Penuh).")
            }
            gcG1Rect.contains(x, y) -> JvmOptimizer.setZgcModeEnabled(context, false)
            gcZgcRect.contains(x, y) -> JvmOptimizer.setZgcModeEnabled(context, true)
            boostTurboRect.contains(x, y) -> {
                val msg = PerformanceBooster.applyBoostProfile(context, PerformanceBooster.BoostProfile.TURBO_FPS)
                showBannerMessage(msg)
            }
            boostPvpRect.contains(x, y) -> {
                val msg = PerformanceBooster.applyBoostProfile(context, PerformanceBooster.BoostProfile.PVP_COMPETITIVE)
                showBannerMessage(msg)
            }
            boostCleanRamRect.contains(x, y) -> {
                val res = PerformanceBooster.runInstantBoost(context)
                showBannerMessage(res.summaryMessage)
            }
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
            for ((idx, r) in modrinthCategoryRects.withIndex()) {
                if (r.contains(x, y)) {
                    val cat = ModrinthSyncManager.ContentCategory.values()[idx]
                    pageScrollY = 0f
                    modrinthQuery = ""
                    ModrinthSyncManager.setCategory(context, cat) {
                        modrinthCatalog = ModrinthSyncManager.getCatalogWithDiskState(context)
                        postInvalidateOnAnimation()
                    }
                    return
                }
            }
            when {
                modrinthSearchInputRect.contains(x, y) -> {
                    val activeCat = ModrinthSyncManager.activeCategory
                    SystemImeHelper.requestTextInput(
                        label = "Cari ${activeCat.fullTitle} 1.20.4",
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
                modrinthLoadMoreBtnRect.contains(x, y) -> {
                    ModrinthSyncManager.loadMoreFromModrinth(context) {
                        modrinthCatalog = ModrinthSyncManager.getCatalogWithDiskState(context)
                        showBannerMessage(ModrinthSyncManager.syncStatusMessage)
                        postInvalidateOnAnimation()
                    }
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
                val buttons = ControlProfileManager.loadLayout(context)
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
