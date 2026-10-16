package io.velocraft.android.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Clean, Matte, Non-Neon Main Menu & Engine Readiness Console for Minecraft (Java Porting Edition).
 *
 * Published by Xyverse Technology Global (Founder / Director: Haekal Saputra) - Package: id.xyverse.minecraft.
 * Strictly zero emojis and zero stock Android widgets.
 * Renders the official Xyverse Technology Global logo, AI Voxel Mascot & World Illustration,
 * interactive JVM Memory Allocation (-Xmx) controls with Recommended Default suggestions,
 * and Resumable Foreground Downloader status.
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

    // Branding & AI Illustrations loaded from APK assets
    private val xyverseLogoBmp: Bitmap? = loadAssetBitmap("branding/xyverse_logo_horizontal.png")
    private val mascotBmp: Bitmap? = loadAssetBitmap("branding/xyverse_mascot.png")
    private val worldBannerBmp: Bitmap? = loadAssetBitmap("branding/xyverse_world_banner.jpg")

    // In-Canvas Notification Banner (replaces Android Toast)
    private var bannerMessage: String? = null
    private var bannerExpireAtMs: Long = 0L

    // Interactive Hit Rects
    private val liteModeToggleRect = RectF()
    private val primaryDownloadBtnRect = RectF()
    private val overlayPermBtnRect = RectF()
    private val rendererPickerBtnRect = RectF()
    private val ramMinusRect = RectF()
    private val ramDefaultRect = RectF()
    private val ramPlusRect = RectF()

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
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bannerDimPaint = Paint().apply {
        color = 0xB8141413.toInt()
        style = Paint.Style.FILL
    }

    // Typography Paints
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(18f)
        isFakeBoldText = true
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA8A39A.toInt()
        textSize = dp(10.8f)
    }
    private val sectionHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6E2D8.toInt()
        textSize = dp(12.8f)
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD4CFC4.toInt()
        textSize = dp(11.2f)
    }
    private val smallMutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF948F84.toInt()
        textSize = dp(10f)
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(11.2f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val cardRect = RectF()
    private val rightCardRect = RectF()
    private val barRect = RectF()
    private val bannerRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun loadAssetBitmap(assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Throwable) {
            null
        }
    }

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

        val padX = dp(20f)
        val topHeaderY = dp(40f)

        // =========================================================================
        // TOP HEADER: Official Xyverse Technology Global Logo + Title & Publisher
        // =========================================================================
        var headerTextLeft = padX
        xyverseLogoBmp?.let { logo ->
            val logoH = dp(40f)
            val logoW = logoH * (logo.width.toFloat() / logo.height.coerceAtLeast(1))
            srcRect.set(0, 0, logo.width, logo.height)
            dstRect.set(padX, topHeaderY + dp(2f), padX + logoW, topHeaderY + dp(2f) + logoH)
            canvas.drawBitmap(logo, srcRect, dstRect, bitmapPaint)
            headerTextLeft = padX + logoW + dp(14f)
        }

        canvas.drawText(
            "MINECRAFT : JAVA EDITION (1.20.4 FABRIC - OFFICIAL SIGNED)",
            headerTextLeft,
            topHeaderY + dp(18f),
            titlePaint
        )

        val effectiveRenderer = RendererProfileManager.resolveEffectiveBackend(context)
        val hw = JvmOptimizer.detectHardware(context)
        canvas.drawText(
            "Publisher: Xyverse Technology Global (Founder/Direktur: Haekal Saputra) | Pkg: ${context.packageName} | Renderer: ${effectiveRenderer.id}",
            headerTextLeft,
            topHeaderY + dp(36f),
            subtitlePaint
        )

        val cardsTop = topHeaderY + dp(48f)
        val cardsBottom = (h - dp(44f)).coerceAtLeast(cardsTop + dp(220f))
        val colGap = dp(14f)
        val colWidth = (w - padX * 2f - colGap) / 2f

        // =========================================================================
        // LEFT CARD: Engine Readiness + AI Mascot + JVM Memory Allocation Controls
        // =========================================================================
        cardRect.set(padX, cardsTop, padX + colWidth, cardsBottom)
        canvas.drawRoundRect(cardRect, dp(12f), dp(12f), cardPaint)
        canvas.drawRoundRect(cardRect, dp(12f), dp(12f), cardBorderPaint)

        // Draw AI Mascot in top-right corner of Left Card
        mascotBmp?.let { mBmp ->
            val mSize = dp(76f)
            val mLeft = cardRect.right - mSize - dp(12f)
            val mTop = cardsTop + dp(12f)
            srcRect.set(0, 0, mBmp.width, mBmp.height)
            dstRect.set(mLeft, mTop, mLeft + mSize, mTop + mSize)
            canvas.drawBitmap(mBmp, srcRect, dstRect, bitmapPaint)
            canvas.drawRoundRect(dstRect, dp(8f), dp(8f), cardBorderPaint)
        }

        var cy = cardsTop + dp(22f)
        val cx = padX + dp(14f)
        canvas.drawText("1. Status Komponen Engine & OpenJDK 21", cx, cy, sectionHeaderPaint)

        cy += dp(18f)
        drawCheckItem(
            canvas,
            cx,
            cy,
            readiness.isOpenJdkRuntimeReady,
            "OpenJDK 21 Runtime (${hw.abi} / libjli.so)"
        )

        cy += dp(17f)
        drawCheckItem(
            canvas,
            cx,
            cy,
            readiness.isClientJarReady,
            "Minecraft 1.20.4 Client JAR + Libraries"
        )

        cy += dp(17f)
        drawCheckItem(
            canvas,
            cx,
            cy,
            readiness.installedModsCount >= 6,
            "Modrinth Stack + Iris Shaders (${readiness.installedModsCount}/7)"
        )

        cy += dp(17f)
        drawCheckItem(
            canvas,
            cx,
            cy,
            readiness.isAssetIndexReady,
            "Mojang Asset Index 12.json & Silent-OGG Safe"
        )

        // Interactive Memory Allocation (RAM Heap) Section with Default Recommendation
        cy += dp(22f)
        val ramModeBadge = if (hw.isCustomHeap) "[CUSTOM]" else "[SARAN DEFAULT]"
        canvas.drawText(
            "Alokasi Memori JVM: ${hw.allocatedHeapMb} MB $ramModeBadge (Total RAM HP: ${hw.totalRamMb} MB)",
            cx,
            cy,
            sectionHeaderPaint
        )
        cy += dp(14f)
        canvas.drawText(
            "Saran Default Optimal untuk HP ini: ${hw.recommendedHeapMb} MB (Batas aman: ${hw.minSafeHeapMb}-${hw.maxSafeHeapMb} MB)",
            cx,
            cy,
            smallMutedPaint
        )

        cy += dp(7f)
        val ramBarW = (cardRect.right - dp(14f)) - cx
        val btnGap = dp(6f)
        val smallBtnW = dp(76f)
        val midBtnW = ramBarW - smallBtnW * 2f - btnGap * 2f
        val ramBtnH = dp(28f)

        ramMinusRect.set(cx, cy, cx + smallBtnW, cy + ramBtnH)
        ramDefaultRect.set(ramMinusRect.right + btnGap, cy, ramMinusRect.right + btnGap + midBtnW, cy + ramBtnH)
        ramPlusRect.set(ramDefaultRect.right + btnGap, cy, cardRect.right - dp(14f), cy + ramBtnH)

        canvas.drawRoundRect(ramMinusRect, dp(6f), dp(6f), mutedBtnPaint)
        canvas.drawRoundRect(ramMinusRect, dp(6f), dp(6f), cardBorderPaint)
        canvas.drawText("- 256 MB", ramMinusRect.centerX(), ramMinusRect.centerY() + dp(3.8f), buttonTextPaint)

        canvas.drawRoundRect(
            ramDefaultRect,
            dp(6f),
            dp(6f),
            if (!hw.isCustomHeap) accentGreenPaint else mutedBtnPaint
        )
        canvas.drawRoundRect(ramDefaultRect, dp(6f), dp(6f), cardBorderPaint)
        canvas.drawText(
            "SARAN DEFAULT (${hw.recommendedHeapMb} MB)",
            ramDefaultRect.centerX(),
            ramDefaultRect.centerY() + dp(3.8f),
            buttonTextPaint
        )

        canvas.drawRoundRect(ramPlusRect, dp(6f), dp(6f), mutedBtnPaint)
        canvas.drawRoundRect(ramPlusRect, dp(6f), dp(6f), cardBorderPaint)
        canvas.drawText("+ 256 MB", ramPlusRect.centerX(), ramPlusRect.centerY() + dp(3.8f), buttonTextPaint)

        // Bottom Button inside Left Card: Renderer, RAM Slider, Shaders, Cursor & Keybind Modal
        val rBtnTop = (cardsBottom - dp(42f)).coerceAtLeast(cy + ramBtnH + dp(8f))
        rendererPickerBtnRect.set(cx, rBtnTop, cardRect.right - dp(14f), rBtnTop + dp(34f))
        canvas.drawRoundRect(rendererPickerBtnRect, dp(8f), dp(8f), accentAmberPaint)
        canvas.drawText(
            "PENGATURAN LENGKAP: RAM, RENDERER, SHADER, KURSOR & KEYBIND",
            rendererPickerBtnRect.centerX(),
            rendererPickerBtnRect.centerY() + dp(4f),
            buttonTextPaint
        )

        // =========================================================================
        // RIGHT CARD: AI World Illustration Banner + Resumable Downloader
        // =========================================================================
        val rx = padX + colWidth + colGap
        rightCardRect.set(rx, cardsTop, rx + colWidth, cardsBottom)
        canvas.drawRoundRect(rightCardRect, dp(12f), dp(12f), cardPaint)
        canvas.drawRoundRect(rightCardRect, dp(12f), dp(12f), cardBorderPaint)

        val rcx = rx + dp(14f)
        val innerRight = rightCardRect.right - dp(14f)
        var ry = cardsTop + dp(12f)

        // Top AI World Banner Strip inside Right Card
        worldBannerBmp?.let { bBmp ->
            val bannerH = dp(64f)
            srcRect.set(0, (bBmp.height * 0.18f).toInt(), bBmp.width, (bBmp.height * 0.82f).toInt())
            dstRect.set(rcx, ry, innerRight, ry + bannerH)
            canvas.save()
            canvas.clipRect(dstRect)
            canvas.drawBitmap(bBmp, srcRect, dstRect, bitmapPaint)
            canvas.drawRect(dstRect, bannerDimPaint)
            canvas.restore()
            canvas.drawRoundRect(dstRect, dp(8f), dp(8f), cardBorderPaint)

            canvas.drawText(
                "XYVERSE TECHNOLOGY GLOBAL - OFFICIAL SIGNED ENGINE",
                rcx + dp(10f),
                ry + dp(24f),
                sectionHeaderPaint
            )
            canvas.drawText(
                "Founder & Direktur: Haekal Saputra | Sertifikat Rilis Resmi v1/v2/v3",
                rcx + dp(10f),
                ry + dp(44f),
                bodyPaint
            )
            ry += bannerH + dp(12f)
        } ?: run {
            ry += dp(10f)
        }

        // Mode Hemat Kuota Toggle Button
        liteModeToggleRect.set(rcx, ry, innerRight, ry + dp(32f))
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
        ry += dp(44f)
        val pct = downloadState.percent.coerceIn(0, 100)
        val statusLine = when {
            downloadState.isRunning -> "Mengunduh (${pct}%): ${downloadState.phaseTitle}"
            readiness.isReadyToLaunch -> "Status: Seluruh File Lengkap (Notifikasi Otomatis Ditutup)"
            else -> "Status: ${downloadState.detailText}"
        }
        canvas.drawText(statusLine.take(58), rcx, ry, bodyPaint)

        ry += dp(7f)
        barRect.set(rcx, ry, innerRight, ry + dp(10f))
        canvas.drawRoundRect(barRect, dp(5f), dp(5f), progressTrackPaint)
        val fillRatio = if (readiness.isReadyToLaunch && !downloadState.isRunning) 1f else (pct / 100f)
        if (fillRatio > 0f) {
            val fillRect = RectF(rcx, ry, rcx + barRect.width() * fillRatio, ry + dp(10f))
            canvas.drawRoundRect(fillRect, dp(5f), dp(5f), accentGreenPaint)
        }

        // Primary Action Button: Start / Resume Download
        ry += dp(16f)
        primaryDownloadBtnRect.set(rcx, ry, innerRight, ry + dp(36f))
        val btnPaint = if (downloadState.isRunning) accentAmberPaint else accentGreenPaint
        canvas.drawRoundRect(primaryDownloadBtnRect, dp(8f), dp(8f), btnPaint)

        val primaryBtnLabel = when {
            downloadState.isRunning -> "UNDUHAN BERJALAN (${pct}%) - OTOMATIS HILANG SAAT SELESAI"
            readiness.isReadyToLaunch -> "MULAI / VERIFIKASI ULANG ENGINE"
            else -> "UNDUH / LANJUTKAN ENGINE & OPENJDK 21"
        }
        canvas.drawText(primaryBtnLabel, primaryDownloadBtnRect.centerX(), primaryDownloadBtnRect.centerY() + dp(4f), buttonTextPaint)

        // Secondary Button: Floating Overlay Permission
        val ovTop = (cardsBottom - dp(42f)).coerceAtLeast(ry + dp(42f))
        overlayPermBtnRect.set(rcx, ovTop, innerRight, ovTop + dp(34f))
        canvas.drawRoundRect(overlayPermBtnRect, dp(8f), dp(8f), mutedBtnPaint)
        canvas.drawRoundRect(overlayPermBtnRect, dp(8f), dp(8f), cardBorderPaint)

        val hasOverlay = FloatingDownloadOverlay.canDrawOverlays(context)
        val overlayLabel = if (hasOverlay) {
            "PANEL KAMBANG DI ATAS APLIKASI LAIN: [AKTIF]"
        } else {
            "AKTIFKAN IZIN TAMPIL DI ATAS APLIKASI LAIN (FLOATING PROGRESS)"
        }
        buttonTextPaint.textSize = dp(10.2f)
        canvas.drawText(overlayLabel, overlayPermBtnRect.centerX(), overlayPermBtnRect.centerY() + dp(3.8f), buttonTextPaint)
        buttonTextPaint.textSize = dp(11.2f)

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
        canvas.drawText(text, x + dp(32f), y, bodyPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y

            when {
                ramMinusRect.contains(x, y) -> {
                    val hw = JvmOptimizer.detectHardware(context)
                    JvmOptimizer.setCustomHeapMb(context, hw.allocatedHeapMb - 256)
                    val updated = JvmOptimizer.detectHardware(context)
                    showBannerMessage("Alokasi RAM diubah ke ${updated.allocatedHeapMb} MB (Saran Default: ${updated.recommendedHeapMb} MB)")
                    postInvalidateOnAnimation()
                    return true
                }
                ramDefaultRect.contains(x, y) -> {
                    JvmOptimizer.resetToRecommendedHeap(context)
                    val updated = JvmOptimizer.detectHardware(context)
                    showBannerMessage("Alokasi RAM dikembalikan ke Saran Default Optimal: ${updated.recommendedHeapMb} MB")
                    postInvalidateOnAnimation()
                    return true
                }
                ramPlusRect.contains(x, y) -> {
                    val hw = JvmOptimizer.detectHardware(context)
                    JvmOptimizer.setCustomHeapMb(context, hw.allocatedHeapMb + 256)
                    val updated = JvmOptimizer.detectHardware(context)
                    showBannerMessage("Alokasi RAM diubah ke ${updated.allocatedHeapMb} MB (Saran Default: ${updated.recommendedHeapMb} MB)")
                    postInvalidateOnAnimation()
                    return true
                }
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
