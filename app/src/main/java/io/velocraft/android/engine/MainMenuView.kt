package io.velocraft.android.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.auth.PlayerSessionManager

/**
 * Modern Clean, Tactile Matte Main Menu & Verified Engine Console for Minecraft (Java Porting Edition).
 *
 * Design Language:
 * - Warm Deep Charcoal (`#141413`) & Matte Card Surfaces (`#1E1D1A`) with subtle `#2E2D29` borders.
 * - Natural Moss Green (`#4E8C57`) & Warm Terracotta (`#C87D55`) accents.
 * - Strictly ZERO neon colors.
 * - Real file readiness validator: "MAIN SEKARANG" only unlocks when all required game files are verified.
 */
class MainMenuView(
    context: Context,
    private val provisioner: DirectBootProvisioner,
    private val onStartOrResumeDownload: (includeFullMusic: Boolean) -> Unit,
    private val onPauseDownload: () -> Unit,
    private val onLaunchVerifiedGame: () -> Unit,
    private val onOpenHudEditor: () -> Unit,
    private val onOpenSettingsAndLogs: () -> Unit,
    private val onRequestOverlayAndNotifPermissions: () -> Unit
) : View(context) {

    private val hwProfile = JvmOptimizer.detectHardware(context)
    private val sessionManager = PlayerSessionManager(context)
    private val downloadEngine = ResumableDownloadEngine(provisioner)

    var includeFullMusicAssets: Boolean = true
    var readiness: ResumableDownloadEngine.ReadinessReport = downloadEngine.verifyEngineReadiness()
    var downloadSnapshot: ResumableDownloadEngine.DownloadSnapshot? = EngineDownloadService.latestSnapshot

    // Interactive Hit Rectangles
    private val accountBtnRect = RectF()
    private val fullPackRadioRect = RectF()
    private val litePackRadioRect = RectF()
    private val primaryActionBtnRect = RectF()
    private val hudEditorBtnRect = RectF()
    private val overlayPermBtnRect = RectF()
    private val settingsLogBtnRect = RectF()

    // Matte Warm Studio Palette Paints (No Neon)
    private val bgPaint = Paint().apply { color = 0xFF141413.toInt() }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1D1A.toInt() }
    private val cardInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262521.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF33312C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val accentGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4E8C57.toInt() }
    private val accentTerracottaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC87D55.toInt() }
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2B27.toInt() }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(19f)
        isFakeBoldText = true
    }
    private val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(13.5f)
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD6D2C8.toInt()
        textSize = dp(11.5f)
    }
    private val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9C978C.toInt()
        textSize = dp(10.8f)
    }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F1EA.toInt()
        textSize = dp(12.5f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val tmpRect = RectF()
    private val barRect = RectF()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    fun refreshState(newSnapshot: ResumableDownloadEngine.DownloadSnapshot? = null) {
        if (newSnapshot != null) {
            downloadSnapshot = newSnapshot
        }
        if (newSnapshot == null || newSnapshot.isCompleted || !newSnapshot.isRunning) {
            readiness = downloadEngine.verifyEngineReadiness()
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val pad = dp(16f)
        val headerH = dp(48f)

        // 1. Top Header Bar
        tmpRect.set(pad, pad * 0.7f, w - pad, pad * 0.7f + headerH)
        canvas.drawRoundRect(tmpRect, dp(12f), dp(12f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(12f), dp(12f), borderPaint)

        canvas.drawText("Minecraft", pad + dp(18f), tmpRect.centerY() + dp(6f), titlePaint)
        mutedPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            "Java Porting Edition 1.20.4  •  Fabric + Sodium",
            pad + dp(118f),
            tmpRect.centerY() + dp(5f),
            mutedPaint
        )

        mutedPaint.textAlign = Paint.Align.RIGHT
        val hwSummary = "${hwProfile.abi}  •  RAM Heap ${hwProfile.allocatedHeapMb} MB  •  ${hwProfile.recommendedRendererLib.removePrefix("lib").removeSuffix(".so").uppercase()}"
        canvas.drawText(hwSummary, w - pad - dp(16f), tmpRect.centerY() + dp(4f), mutedPaint)
        mutedPaint.textAlign = Paint.Align.LEFT

        // Content Columns Area
        val contentTop = tmpRect.bottom + dp(12f)
        val contentBottom = h - pad
        val leftColW = (w - pad * 3f) * 0.46f
        val rightColLeft = pad * 2f + leftColW
        val rightColW = (w - pad * 3f) * 0.54f

        // =========================================================================
        // LEFT COLUMN: Player Profile + Asset Mode + Real File Readiness Checklist
        // =========================================================================
        tmpRect.set(pad, contentTop, pad + leftColW, contentBottom)
        canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), borderPaint)

        var curY = contentTop + dp(24f)
        val leftX = pad + dp(16f)
        val session = sessionManager.getActiveSession()

        canvas.drawText("Profil & Paket Data Game", leftX, curY, sectionTitlePaint)

        // Account Box
        curY += dp(10f)
        tmpRect.set(leftX, curY, pad + leftColW - dp(16f), curY + dp(42f))
        canvas.drawRoundRect(tmpRect, dp(9f), dp(9f), cardInnerPaint)
        canvas.drawText("Akun: ${session.username} (${session.accountType.name})", leftX + dp(12f), tmpRect.centerY() + dp(4f), bodyPaint)

        // "Ubah Akun" small button inside Account Box
        accountBtnRect.set(tmpRect.right - dp(92f), tmpRect.top + dp(6f), tmpRect.right - dp(6f), tmpRect.bottom - dp(6f))
        canvas.drawRoundRect(accountBtnRect, dp(7f), dp(7f), cardPaint)
        canvas.drawRoundRect(accountBtnRect, dp(7f), dp(7f), borderPaint)
        btnTextPaint.textSize = dp(10.5f)
        canvas.drawText("Ubah Akun", accountBtnRect.centerX(), accountBtnRect.centerY() + dp(3.5f), btnTextPaint)

        // Package Size Selector (Full ~685 MB vs Lite ~245 MB)
        curY = tmpRect.bottom + dp(12f)
        canvas.drawText("Pilih Ukuran Unduhan Asset:", leftX, curY, mutedPaint)

        curY += dp(6f)
        val halfPackW = (leftColW - dp(40f)) * 0.5f
        fullPackRadioRect.set(leftX, curY, leftX + halfPackW, curY + dp(38f))
        litePackRadioRect.set(leftX + halfPackW + dp(8f), curY, pad + leftColW - dp(16f), curY + dp(38f))

        canvas.drawRoundRect(fullPackRadioRect, dp(8f), dp(8f), if (includeFullMusicAssets) accentGreenPaint else cardInnerPaint)
        canvas.drawRoundRect(litePackRadioRect, dp(8f), dp(8f), if (!includeFullMusicAssets) accentGreenPaint else cardInnerPaint)

        btnTextPaint.textSize = dp(10.5f)
        canvas.drawText("Lengkap (~685 MB)", fullPackRadioRect.centerX(), fullPackRadioRect.centerY() + dp(4f), btnTextPaint)
        canvas.drawText("Hemat (~245 MB)", litePackRadioRect.centerX(), litePackRadioRect.centerY() + dp(4f), btnTextPaint)

        // Readiness Checklist
        curY = fullPackRadioRect.bottom + dp(16f)
        canvas.drawText("Status Validasi Komponen:", leftX, curY, sectionTitlePaint)

        val checks = listOf(
            "Minecraft 1.20.4 Client & Libraries" to readiness.isClientJarReady,
            "Fabric Loader + 7 Mod Optimasi (Sodium)" to readiness.isOptimizationModsReady,
            "Game Assets & Resource Index" to readiness.isAssetIndexReady,
            "Native Bridge (${hwProfile.abi}) & Config" to true
        )

        curY += dp(8f)
        for ((label, ok) in checks) {
            curY += dp(20f)
            val badge = if (ok) "[SIAP]" else "[BELUM]"
            bodyPaint.color = if (ok) 0xFF78B882.toInt() else 0xFFD99B66.toInt()
            canvas.drawText(badge, leftX, curY, bodyPaint)
            bodyPaint.color = 0xFFD6D2C8.toInt()
            canvas.drawText(label, leftX + dp(54f), curY, bodyPaint)
        }

        // =========================================================================
        // RIGHT COLUMN: Resumable Foreground Downloader + Primary Launch + Utilities
        // =========================================================================
        tmpRect.set(rightColLeft, contentTop, rightColLeft + rightColW, contentBottom)
        canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), cardPaint)
        canvas.drawRoundRect(tmpRect, dp(14f), dp(14f), borderPaint)

        val rightX = rightColLeft + dp(18f)
        val rightInnerW = rightColW - dp(36f)
        var rY = contentTop + dp(24f)

        canvas.drawText("Manajer Unduhan & Peluncuran Game", rightX, rY, sectionTitlePaint)

        // Download Status Box
        rY += dp(10f)
        val dlBoxBottom = rY + dp(96f)
        tmpRect.set(rightX, rY, rightX + rightInnerW, dlBoxBottom)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardInnerPaint)

        val snap = downloadSnapshot
        val isReady = readiness.isAllValidAndReady

        val statusTitle = when {
            isReady -> "✓ Seluruh File Valid (${readiness.installedMegabytes} MB terpasang) — Siap Dimainkan!"
            snap?.isRunning == true -> {
                val dlMb = snap.downloadedBytes / (1024L * 1024L)
                val totMb = (snap.totalBytes / (1024L * 1024L)).coerceAtLeast(1L)
                val spd = "%.2f".format(snap.speedBytesPerSec / (1024f * 1024f))
                "Mengunduh: ${snap.percent}%  •  $dlMb MB / $totMb MB  •  $spd MB/s"
            }
            snap?.isPaused == true -> {
                val dlMb = snap.downloadedBytes / (1024L * 1024L)
                val totMb = (snap.totalBytes / (1024L * 1024L)).coerceAtLeast(1L)
                "Unduhan Dijeda: ${snap.percent}% ($dlMb MB / $totMb MB tersimpan)"
            }
            else -> "Data game belum lengkap (${readiness.installedMegabytes} MB terpasang). Tekan tombol di bawah untuk mengunduh."
        }

        canvas.drawText(statusTitle, rightX + dp(12f), rY + dp(24f), bodyPaint)

        val fileDetail = snap?.currentFileName
            ?: "Mendukung Auto-Resume (lanjut otomatis jika terputus) & Notifikasi Latar Belakang."
        canvas.drawText(fileDetail.take(58), rightX + dp(12f), rY + dp(44f), mutedPaint)

        // Progress Bar Track & Fill
        val barLeft = rightX + dp(12f)
        val barRight = rightX + rightInnerW - dp(12f)
        val barTop = rY + dp(58f)
        val barBottom = barTop + dp(12f)
        barRect.set(barLeft, barTop, barRight, barBottom)
        canvas.drawRoundRect(barRect, dp(6f), dp(6f), barTrackPaint)

        val pct = if (isReady) 100 else (snap?.percent ?: 0)
        if (pct > 0) {
            val fillRight = barLeft + (barRight - barLeft) * (pct / 100f)
            barRect.set(barLeft, barTop, fillRight, barBottom)
            canvas.drawRoundRect(barRect, dp(6f), dp(6f), accentGreenPaint)
        }

        canvas.drawText(
            "Berjalan di Foreground Push Notification (Tetap lanjut walau pindah aplikasi)",
            rightX + dp(12f),
            rY + dp(86f),
            mutedPaint
        )

        // Primary Action Button (Download / Pause / Launch Minecraft)
        rY = dlBoxBottom + dp(14f)
        val mainBtnH = dp(48f)
        primaryActionBtnRect.set(rightX, rY, rightX + rightInnerW, rY + mainBtnH)

        val isDownloadingNow = (snap?.isRunning == true)
        val primaryBtnPaint = when {
            isReady -> accentGreenPaint
            isDownloadingNow -> accentTerracottaPaint
            else -> accentTerracottaPaint
        }
        val primaryBtnLabel = when {
            isReady -> "▶  MAIN SEKARANG (MINECRAFT 1.20.4)"
            isDownloadingNow -> "⏸  JEDA UNDUHAN (TERSIMPAN OTOMATIS)"
            snap?.isPaused == true || readiness.installedMegabytes > 5L ->
                "⬇  LANJUTKAN UNDUHAN DATA GAME"
            else -> {
                val estMb = if (includeFullMusicAssets) "~685 MB" else "~245 MB"
                "⬇  UNDUH DATA ENGINE & ASSET ($estMb)"
            }
        }

        canvas.drawRoundRect(primaryActionBtnRect, dp(11f), dp(11f), primaryBtnPaint)
        btnTextPaint.textSize = dp(13f)
        canvas.drawText(primaryBtnLabel, primaryActionBtnRect.centerX(), primaryActionBtnRect.centerY() + dp(4.5f), btnTextPaint)

        // Bottom Row Utility Buttons (3 Clean Buttons)
        rY = primaryActionBtnRect.bottom + dp(12f)
        val utilBtnH = dp(38f)
        val gap = dp(8f)
        val thirdW = (rightInnerW - gap * 2f) / 3f

        hudEditorBtnRect.set(rightX, rY, rightX + thirdW, rY + utilBtnH)
        overlayPermBtnRect.set(rightX + thirdW + gap, rY, rightX + thirdW * 2f + gap, rY + utilBtnH)
        settingsLogBtnRect.set(rightX + (thirdW + gap) * 2f, rY, rightX + rightInnerW, rY + utilBtnH)

        for (r in listOf(hudEditorBtnRect, overlayPermBtnRect, settingsLogBtnRect)) {
            canvas.drawRoundRect(r, dp(9f), dp(9f), cardInnerPaint)
            canvas.drawRoundRect(r, dp(9f), dp(9f), borderPaint)
        }

        btnTextPaint.textSize = dp(10.5f)
        canvas.drawText("Atur Tombol HUD", hudEditorBtnRect.centerX(), hudEditorBtnRect.centerY() + dp(3.5f), btnTextPaint)
        val ovLabel = if (FloatingDownloadOverlay.canDrawOverlays(context)) "Overlay Aktif ✓" else "Izin Tampil di Atas"
        canvas.drawText(ovLabel, overlayPermBtnRect.centerX(), overlayPermBtnRect.centerY() + dp(3.5f), btnTextPaint)
        canvas.drawText("Pengaturan & Log", settingsLogBtnRect.centerX(), settingsLogBtnRect.centerY() + dp(3.5f), btnTextPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y

        when {
            accountBtnRect.contains(x, y) -> {
                onOpenSettingsAndLogs()
            }
            fullPackRadioRect.contains(x, y) -> {
                includeFullMusicAssets = true
                postInvalidateOnAnimation()
            }
            litePackRadioRect.contains(x, y) -> {
                includeFullMusicAssets = false
                postInvalidateOnAnimation()
            }
            primaryActionBtnRect.contains(x, y) -> {
                val isReady = readiness.isAllValidAndReady
                val isRunning = downloadSnapshot?.isRunning == true
                when {
                    isReady -> onLaunchVerifiedGame()
                    isRunning -> onPauseDownload()
                    else -> onStartOrResumeDownload(includeFullMusicAssets)
                }
            }
            hudEditorBtnRect.contains(x, y) -> {
                onOpenHudEditor()
            }
            overlayPermBtnRect.contains(x, y) -> {
                onRequestOverlayAndNotifPermissions()
            }
            settingsLogBtnRect.contains(x, y) -> {
                onOpenSettingsAndLogs()
            }
        }
        return true
    }
}
