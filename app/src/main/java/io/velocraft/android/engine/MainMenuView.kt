package io.velocraft.android.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Clean, Matte, Non-Neon Main Menu & Engine Readiness Console for Minecraft (Java Porting Edition).
 *
 * Strictly zero emojis and zero stock Android widgets.
 * Displays real-time OpenJDK 21 Runtime + Minecraft 1.20.4 + Fabric + Iris Shaders status,
 * active GPU Renderer profile, resumable download controls, and an in-canvas notification banner.
 */
class MainMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Callbacks wired by GameActivity
    var onStartOrResumeDownload: ((skipMusicLite: Boolean) -> Unit)? = null
    var onRequestOverlayPermission: (() -> Unit)? = null
    var onOpenQuickSettings: (() -> Unit)? = null
    var onOpenRendererAndKeybindModal: (() -> Unit)? = null

    private var skipMusicLite: Boolean = ResumableDownloadEngine.isSkipMusicLiteEnabled(context)
    private var readiness = ResumableDownloadEngine.inspectEngineReadiness(context)
    private var downloadState = EngineDownloadService.latestState

    // In-Canvas Notification Banner (replaces Android Toast)
    private var bannerMessage: String? = null
    private var bannerExpireAtMs: Long = 0L

    // Interactive Hit Rects
    private val liteModeToggleRect = RectF()
    private val primaryDownloadBtnRect = RectF()
    private val overlayPermBtnRect = RectF()
    private val rendererPickerBtnRect = RectF()

    // Clean Matte Non-Neon Paint Palette
    private val bgPaint = Paint().apply {
        color = 0xFF141413.toInt()
        style = Paint.Style.FILL
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F1E1B.toInt()
        style = Paint.Style.FILL
    }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF33312C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val accentGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4E8C57.toInt()
        style = Paint.Style.FILL
    }
    private val accentAmberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC87D55.toInt()
        style = Paint.Style.FILL
    }
    private val mutedBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2B2925.toInt()
        style = Paint.Style.FILL
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2B2A26.toInt()
        style = Paint.Style.FILL
    }

    // Typography Paints
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(20f)
        isFakeBoldText = true
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8A39A.toInt()
        textSize = dp(12f)
    }
    private val sectionHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6E2D8.toInt()
        textSize = dp(13.5f)
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD4CFC4.toInt()
        textSize = dp(11.8f)
    }
    private val smallMutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF948F84.toInt()
        textSize = dp(10.5f)
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(12.2f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val cardRect = RectF()
    private val rightCardRect = RectF()
    private val barRect = RectF()
    private val bannerRect = RectF()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    fun showBannerMessage(message: String, durationMs: Long = 4500L) {
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
        skipMusicLite = ResumableDownloadEngine.isSkipMusicLiteEnabled(context)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val padX = dp(24f)
        val topHeaderY = dp(50f)

        // Header Title
        canvas.drawText("MINECRAFT : JAVA EDITION (1.20.4 FABRIC)", padX, topHeaderY + dp(20f), titlePaint)
        val effectiveRenderer = RendererProfileManager.resolveEffectiveBackend(context)
        val shadersState = if (RendererProfileManager.isShaderSupportEnabled(context)) "ON (Iris Shaders)" else "OFF"
        canvas.drawText(
            "Renderer Aktif: ${effectiveRenderer.displayName}  |  Shader Support: $shadersState  |  Support All Android GPUs",
            padX,
            topHeaderY + dp(40f),
            subtitlePaint
        )

        val cardsTop = topHeaderY + dp(54f)
        val cardsBottom = (h - dp(56f)).coerceAtLeast(cardsTop + dp(210f))
        val colGap = dp(16f)
        val colWidth = (w - padX * 2f - colGap) / 2f

        // =========================================================================
        // LEFT CARD: Engine Readiness Checklist + Active Renderer & Shader Picker
        // =========================================================================
        cardRect.set(padX, cardsTop, padX + colWidth, cardsBottom)
        canvas.drawRoundRect(cardRect, dp(12f), dp(12f), cardPaint)
        canvas.drawRoundRect(cardRect, dp(12f), dp(12f), cardBorderPaint)

        var cy = cardsTop + dp(24f)
        val cx = padX + dp(16f)
        canvas.drawText("1. Status Komponen Engine & OpenJDK 21", cx, cy, sectionHeaderPaint)

        cy += dp(20f)
        drawCheckItem(
            canvas,
            cx,
            cy,
            readiness.isOpenJdkRuntimeReady,
            "OpenJDK 21 Runtime (${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"} / libjli.so)"
        )

        cy += dp(19f)
        drawCheckItem(
            canvas,
            cx,
            cy,
            readiness.isClientJarReady,
            "Minecraft 1.20.4 Client JAR + Libraries (~135 MB)"
        )

        cy += dp(19f)
        drawCheckItem(
            canvas,
            cx,
            cy,
            readiness.installedModsCount >= 6,
            "Modrinth Performance + Iris Shaders (${readiness.installedModsCount}/7 terpasang)"
        )

        cy += dp(19f)
        drawCheckItem(
            canvas,
            cx,
            cy,
            readiness.isAssetIndexReady,
            "Mojang Asset Index 12.json & Silent-OGG Safe Sounds"
        )

        cy += dp(22f)
        canvas.drawText("Mengapa ukuran unduhan penuh ~690 MB?", cx, cy, sectionHeaderPaint)
        cy += dp(16f)
        canvas.drawText("- OpenJDK 21 + Client JAR + Libraries + 7 Mods: ~185 MB", cx, cy, smallMutedPaint)
        cy += dp(14f)
        canvas.drawText("- 3.800 File Suara & Musik Original Mojang: ~500 MB", cx, cy, smallMutedPaint)
        cy += dp(14f)
        canvas.drawText("- Mode Hemat membuat file .ogg hening valid (100% aman, tidak akan crash/berhenti).", cx, cy, smallMutedPaint)

        // Button inside Left Card: Renderer, Shaders, Controls & Custom Keybind Modal
        val rBtnTop = (cardsBottom - dp(48f)).coerceAtLeast(cy + dp(10f))
        rendererPickerBtnRect.set(cx, rBtnTop, cardRect.right - dp(16f), rBtnTop + dp(36f))
        canvas.drawRoundRect(rendererPickerBtnRect, dp(8f), dp(8f), mutedBtnPaint)
        canvas.drawRoundRect(rendererPickerBtnRect, dp(8f), dp(8f), cardBorderPaint)
        canvas.drawText(
            "PENGATURAN RENDERER, SHADER, KURSOR & CUSTOM KEYBIND",
            rendererPickerBtnRect.centerX(),
            rendererPickerBtnRect.centerY() + dp(4f),
            buttonTextPaint
        )

        // =========================================================================
        // RIGHT CARD: Resumable Downloader, Silent-OGG Lite Toggle & Overlay
        // =========================================================================
        val rx = padX + colWidth + colGap
        rightCardRect.set(rx, cardsTop, rx + colWidth, cardsBottom)
        canvas.drawRoundRect(rightCardRect, dp(12f), dp(12f), cardPaint)
        canvas.drawRoundRect(rightCardRect, dp(12f), dp(12f), cardBorderPaint)

        var ry = cardsTop + dp(24f)
        val rcx = rx + dp(16f)
        val innerRight = rightCardRect.right - dp(16f)

        canvas.drawText("2. Unduhan Data & OpenJDK Runtime (Bisa Dilanjutkan)", rcx, ry, sectionHeaderPaint)

        // Mode Hemat Kuota Toggle Button
        ry += dp(12f)
        liteModeToggleRect.set(rcx, ry, innerRight, ry + dp(36f))
        canvas.drawRoundRect(
            liteModeToggleRect,
            dp(8f),
            dp(8f),
            if (skipMusicLite) accentGreenPaint else mutedBtnPaint
        )
        val liteLabel = if (skipMusicLite) {
            "MODE HEMAT (~245 MB - Silent OGG Aman): [AKTIF]"
        } else {
            "MODE PENUH (~695 MB - Termasuk Musik Latar): [AKTIF]"
        }
        canvas.drawText(liteLabel, liteModeToggleRect.centerX(), liteModeToggleRect.centerY() + dp(4f), buttonTextPaint)

        // Progress Bar + Status Text
        ry += dp(50f)
        val pct = downloadState.percent.coerceIn(0, 100)
        val statusLine = when {
            downloadState.isRunning -> "Mengunduh (${pct}%): ${downloadState.phaseTitle}"
            readiness.isReadyToLaunch -> "Status: Seluruh file (OpenJDK 21 + Minecraft 1.20.4) Lengkap!"
            else -> "Status: ${downloadState.detailText}"
        }
        canvas.drawText(statusLine, rcx, ry, bodyPaint)

        ry += dp(8f)
        barRect.set(rcx, ry, innerRight, ry + dp(12f))
        canvas.drawRoundRect(barRect, dp(6f), dp(6f), progressTrackPaint)
        val fillRatio = if (readiness.isReadyToLaunch && !downloadState.isRunning) 1f else (pct / 100f)
        if (fillRatio > 0f) {
            val fillRect = RectF(rcx, ry, rcx + barRect.width() * fillRatio, ry + dp(12f))
            canvas.drawRoundRect(fillRect, dp(6f), dp(6f), accentGreenPaint)
        }

        ry += dp(24f)
        val subDetail = if (downloadState.isRunning) {
            "${downloadState.detailText} (Tetap berjalan di notifikasi atas)"
        } else if (readiness.isReadyToLaunch) {
            "Engine siap! Menyiapkan sesi peluncuran JVM OpenJDK 21 + Renderer ${effectiveRenderer.id.uppercase()}..."
        } else {
            "Jika aplikasi ditutup saat mengunduh, file yang sudah masuk tersimpan dan otomatis lanjut."
        }
        canvas.drawText(subDetail.take(66), rcx, ry, smallMutedPaint)

        // Primary Action Button: Start / Resume Download
        ry += dp(14f)
        primaryDownloadBtnRect.set(rcx, ry, innerRight, ry + dp(40f))
        val btnPaint = if (downloadState.isRunning) accentAmberPaint else accentGreenPaint
        canvas.drawRoundRect(primaryDownloadBtnRect, dp(8f), dp(8f), btnPaint)

        val primaryBtnLabel = when {
            downloadState.isRunning -> "UNDUHAN BERJALAN DI LATAR BELAKANG (${pct}%)"
            readiness.isReadyToLaunch -> "VERIFIKASI ULANG FILE ENGINE & RUNTIME"
            else -> "UNDUH / LANJUTKAN ENGINE & OPENJDK 21"
        }
        canvas.drawText(primaryBtnLabel, primaryDownloadBtnRect.centerX(), primaryDownloadBtnRect.centerY() + dp(4.5f), buttonTextPaint)

        // Secondary Button: Floating Overlay Permission
        ry += dp(48f)
        overlayPermBtnRect.set(rcx, ry, innerRight, ry + dp(34f))
        canvas.drawRoundRect(overlayPermBtnRect, dp(8f), dp(8f), mutedBtnPaint)
        canvas.drawRoundRect(overlayPermBtnRect, dp(8f), dp(8f), cardBorderPaint)

        val hasOverlay = FloatingDownloadOverlay.canDrawOverlays(context)
        val overlayLabel = if (hasOverlay) {
            "PANEL KAMBANG DI ATAS APLIKASI LAIN: [AKTIF]"
        } else {
            "AKTIFKAN IZIN TAMPIL DI ATAS APLIKASI LAIN (FLOATING PROGRESS)"
        }
        buttonTextPaint.textSize = dp(10.8f)
        canvas.drawText(overlayLabel, overlayPermBtnRect.centerX(), overlayPermBtnRect.centerY() + dp(4f), buttonTextPaint)
        buttonTextPaint.textSize = dp(12.2f)

        // Draw In-Canvas Top Notification Banner if active
        val msg = bannerMessage
        if (msg != null && System.currentTimeMillis() < bannerExpireAtMs) {
            val bw = (w - padX * 2f).coerceAtMost(dp(680f))
            val bLeft = (w - bw) * 0.5f
            bannerRect.set(bLeft, dp(42f), bLeft + bw, dp(76f))
            canvas.drawRoundRect(bannerRect, dp(8f), dp(8f), accentGreenPaint)
            canvas.drawRoundRect(bannerRect, dp(8f), dp(8f), cardBorderPaint)
            canvas.drawText(msg, bannerRect.centerX(), bannerRect.centerY() + dp(4f), buttonTextPaint)
        }
    }

    private fun drawCheckItem(canvas: Canvas, x: Float, y: Float, ok: Boolean, text: String) {
        val badge = if (ok) "[OK]" else "[  ]"
        bodyPaint.color = if (ok) 0xFF76B580.toInt() else 0xFFC87D55.toInt()
        bodyPaint.isFakeBoldText = true
        canvas.drawText(badge, x, y, bodyPaint)
        bodyPaint.color = 0xFFD4CFC4.toInt()
        bodyPaint.isFakeBoldText = false
        canvas.drawText(text, x + dp(34f), y, bodyPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y

            when {
                liteModeToggleRect.contains(x, y) -> {
                    if (!downloadState.isRunning) {
                        skipMusicLite = !skipMusicLite
                        ResumableDownloadEngine.setSkipMusicLiteEnabled(context, skipMusicLite)
                        postInvalidateOnAnimation()
                    }
                    return true
                }
                primaryDownloadBtnRect.contains(x, y) -> {
                    onStartOrResumeDownload?.invoke(skipMusicLite)
                    return true
                }
                overlayPermBtnRect.contains(x, y) -> {
                    onRequestOverlayPermission?.invoke()
                    return true
                }
                rendererPickerBtnRect.contains(x, y) -> {
                    onOpenRendererAndKeybindModal?.invoke()
                    return true
                }
            }
        }
        return true
    }
}
