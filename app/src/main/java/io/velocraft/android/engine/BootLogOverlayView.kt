package io.velocraft.android.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.util.VeloLogger
import kotlin.math.min

/**
 * 100% Custom Canvas Live Boot Console, Specific Sub-Stage Tracker & Mojang Splash / Menu ETA Overlay.
 *
 * Displays:
 * - 10 Granular Boot Stages (`TAHAP 1/10` .. `TAHAP 10/10`) & live percentage (`0%..100%`)
 * - 3 Dedicated Real-Time Telemetry & ETA Cards:
 *   1) ESTIMASI MUNCUL SPLASH MOJANG (`~XX DETIK LAGI` + live loaded class counter `XXXX / 5400`)
 *   2) ESTIMASI MENU UTAMA MINECRAFT (`~YY DETIK LAGI` + live class loading speed `XXX kelas/dtk`)
 *   3) TELEMETRI JVM & MEMORI (`Waktu Berjalan: XX dtk` + `RAM JVM: XXX / YYY MB`)
 * - Specific Sub-Task & Active Java Class Focus Banner (`Sub-Tahap` & `Fokus Kelas`)
 * - Real-time scrolling terminal streaming `[NATIVE-PROGRES-BOOT]` and `[NATIVE-JVM-OUT]`
 *
 * Strictly zero Android stock widgets, zero neon colors, and zero emojis.
 */
class BootLogOverlayView(
    context: Context,
    private val onReturnToLauncher: () -> Unit
) : View(context) {

    var bootStageTitle: String = "TAHAP 1/10: Menyiapkan Lingkungan Engine & Direktori..."
    var bootStageProgress: Int = 8
    var bootSummaryLine: String = "OpenJDK 17 HotSpot | Fabric 0.16.9 | Minecraft 1.20.4"
    var isJvmExited: Boolean = false
    var jvmExitCode: Int = 0
    var isMinimized: Boolean = false

    private var currentSubTaskDetail: String = "Memverifikasi konfigurasi OpenJDK 17, Mod Fabric & Renderer..."
    private var currentActiveClass: String = "io.velocraft.android.engine.DirectBootProvisioner"
    private var currentStageIndex: Int = 1
    private var loadedClasses: Int = 0
    private var targetSplashClasses: Int = 5400
    private var classesPerSec: Int = 0
    private var jvmUsedRamMb: Int = 0
    private var jvmMaxRamMb: Int = 768
    private var elapsedSec: Int = 0
    private var etaMojangSplashSec: Int = 38
    private var etaMinecraftMenuSec: Int = 64
    private var mojangSplashReached: Boolean = false
    private var titleScreenReached: Boolean = false
    private var swapBuffersCount: Int = 0
    private var autoMinimizedOnSplash: Boolean = false
    private var splashReachedTimestampMs: Long = 0L

    private var copyFeedbackMessage: String? = null
    private var copyFeedbackUntilMs: Long = 0L

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE11161F")
        style = Paint.Style.FILL
    }
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2161F2C")
        style = Paint.Style.FILL
    }
    private val cardActiveBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2132A23")
        style = Paint.Style.FILL
    }
    private val terminalBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F50A0D13")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D5B")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.FILL
    }
    private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E7D5B")
        style = Paint.Style.FILL
    }
    private val btnPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#256B4E")
        style = Paint.Style.FILL
    }
    private val btnSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.FILL
    }
    private val btnWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C2D12")
        style = Paint.Style.FILL
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        typeface = Typeface.MONOSPACE
    }
    private val btnTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var brandMarkBitmap: Bitmap? = null

    private val panelRect = RectF()
    private val cardSplashRect = RectF()
    private val cardMenuRect = RectF()
    private val cardMetricsRect = RectF()
    private val subTaskStripRect = RectF()
    private val terminalRect = RectF()
    private val progressTrackRect = RectF()
    private val progressFillRect = RectF()

    private val btnMinimizeRect = RectF()
    private val btnCopyLogRect = RectF()
    private val btnReturnMenuRect = RectF()
    private val minimizedPillRect = RectF()

    private val refreshTick = object : Runnable {
        override fun run() {
            if (visibility == VISIBLE) {
                pullNativeTelemetrySnapshot()
                invalidate()
                postDelayed(this, 250L)
            }
        }
    }

    init {
        brandMarkBitmap = loadAssetBitmap("branding/xyverse_mark.png")
    }

    private fun pullNativeTelemetrySnapshot() {
        val snap = NativeEngineBridge.getBootTelemetry() ?: return
        if (!isJvmExited && snap.stageIndex >= 1) {
            currentStageIndex = snap.stageIndex.coerceIn(1, 10)
            if (snap.stageTitle.isNotBlank()) {
                bootStageTitle = snap.stageTitle
            }
            if (snap.percent > bootStageProgress || snap.stageIndex >= 2) {
                bootStageProgress = snap.percent.coerceIn(0, 100)
            }
            if (snap.subTaskDetail.isNotBlank()) {
                currentSubTaskDetail = snap.subTaskDetail
            }
            if (snap.activeClassName.isNotBlank()) {
                currentActiveClass = snap.activeClassName
            }
            loadedClasses = snap.loadedClasses
            targetSplashClasses = snap.targetSplashClasses
            classesPerSec = snap.classesPerSec
            jvmUsedRamMb = snap.jvmUsedRamMb
            jvmMaxRamMb = snap.jvmMaxRamMb
            elapsedSec = snap.elapsedSec
            etaMojangSplashSec = snap.etaMojangSplashSec
            etaMinecraftMenuSec = snap.etaMinecraftMenuSec
            mojangSplashReached = snap.mojangSplashReached
            titleScreenReached = snap.titleScreenReached
            swapBuffersCount = snap.swapBuffersCount

            // Automatically close boot overlay 1.5s after the first OpenGL Mojang Splash frame renders
            if (mojangSplashReached && !autoMinimizedOnSplash && swapBuffersCount > 0) {
                if (splashReachedTimestampMs == 0L) {
                    splashReachedTimestampMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - splashReachedTimestampMs >= 1500L) {
                    autoMinimizedOnSplash = true
                    isMinimized = true
                    visibility = GONE
                }
            }
        }
    }

    fun startBootMonitoring() {
        visibility = VISIBLE
        isMinimized = false
        isJvmExited = false
        autoMinimizedOnSplash = false
        splashReachedTimestampMs = 0L
        removeCallbacks(refreshTick)
        post(refreshTick)
    }

    fun reopenLogOverlay() {
        isMinimized = false
        visibility = VISIBLE
        removeCallbacks(refreshTick)
        post(refreshTick)
        invalidate()
    }

    fun stopBootMonitoring() {
        removeCallbacks(refreshTick)
        visibility = GONE
    }

    fun updateBootStage(title: String, progress: Int, summary: String? = null) {
        post {
            bootStageTitle = title
            bootStageProgress = progress.coerceIn(0, 100)
            if (summary != null) {
                bootSummaryLine = summary
            }
            invalidate()
        }
    }

    fun notifyJvmFinished(exitCode: Int) {
        post {
            isJvmExited = true
            jvmExitCode = exitCode
            isMinimized = false
            visibility = VISIBLE
            bootStageTitle = if (exitCode == 0) {
                "SESI JVM SELESAI (KODE KELUAR 0)"
            } else {
                "SESI JVM BERHENTI (KODE KELUAR $exitCode) - PERIKSA DETAIL LOG DI BAWAH"
            }
            bootStageProgress = 100
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refreshTick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val u = min(w, h)

        if (isMinimized) {
            return
        }

        // Semi-transparent dark backdrop so any underlying GL frame is subtly visible
        canvas.drawColor(Color.parseColor("#B8070A0F"))

        val padX = w * 0.045f
        val padY = h * 0.042f
        panelRect.set(padX, padY, w - padX, h - padY)
        canvas.drawRoundRect(panelRect, 18f, 18f, panelPaint)
        canvas.drawRoundRect(panelRect, 18f, 18f, strokePaint)

        val innerPad = u * 0.026f
        val left = panelRect.left + innerPad
        val right = panelRect.right - innerPad
        var curY = panelRect.top + innerPad

        // 1. Header Row: Subtle Mark + Title + Hardware Summary
        val markSize = u * 0.056f
        brandMarkBitmap?.let { bmp ->
            val dst = RectF(left, curY, left + markSize, curY + markSize)
            canvas.drawBitmap(bmp, null, dst, null)
        }
        val titleX = left + markSize + innerPad * 0.55f
        titlePaint.textSize = u * 0.029f
        titlePaint.color = Color.parseColor("#F8FAFC")
        drawFittedText(
            canvas,
            "KONSOL TELEMETRI BOOT & ESTIMASI WAKTU (ETA) - MINECRAFT 1.20.4",
            titleX,
            curY + u * 0.025f,
            right - titleX,
            titlePaint
        )

        subPaint.textSize = u * 0.020f
        subPaint.color = Color.parseColor("#94A3B8")
        drawFittedText(
            canvas,
            bootSummaryLine,
            titleX,
            curY + u * 0.050f,
            right - titleX,
            subPaint
        )

        curY += markSize + innerPad * 0.45f

        // 2. Stage Title & Overall Progress Bar
        titlePaint.textSize = u * 0.0235f
        titlePaint.color = if (isJvmExited && jvmExitCode != 0) {
            Color.parseColor("#FCA5A5")
        } else {
            Color.parseColor("#6EE7B7")
        }
        drawFittedText(
            canvas,
            "$bootStageTitle ($bootStageProgress%)",
            left,
            curY + u * 0.020f,
            right - left,
            titlePaint
        )
        titlePaint.color = Color.parseColor("#F8FAFC")

        curY += u * 0.026f
        val barH = u * 0.015f
        progressTrackRect.set(left, curY, right, curY + barH)
        canvas.drawRoundRect(progressTrackRect, 8f, 8f, progressTrackPaint)

        val fillW = (progressTrackRect.width() * (bootStageProgress / 100f)).coerceAtLeast(12f)
        progressFillRect.set(left, curY, left + fillW, curY + barH)
        canvas.drawRoundRect(progressFillRect, 8f, 8f, progressFillPaint)

        // 10-Segment Stage Pill Strip (TAHAP 1..10)
        curY += barH + u * 0.008f
        val segGap = u * 0.007f
        val segW = (right - left - segGap * 9f) / 10f
        val segH = u * 0.008f
        for (s in 1..10) {
            val sLeft = left + (s - 1) * (segW + segGap)
            progressFillRect.set(sLeft, curY, sLeft + segW, curY + segH)
            canvas.drawRoundRect(
                progressFillRect,
                4f,
                4f,
                if (s <= currentStageIndex) progressFillPaint else progressTrackPaint
            )
        }

        curY += segH + innerPad * 0.34f

        // 3. Three Dedicated ETA & Live Telemetry Cards
        val cardGap = u * 0.014f
        val cardW = (right - left - cardGap * 2f) / 3f
        val cardH = u * 0.095f

        cardSplashRect.set(left, curY, left + cardW, curY + cardH)
        cardMenuRect.set(cardSplashRect.right + cardGap, curY, cardSplashRect.right + cardGap + cardW, curY + cardH)
        cardMetricsRect.set(cardMenuRect.right + cardGap, curY, right, curY + cardH)

        // Card 1: ESTIMASI MUNCUL SPLASH MOJANG
        canvas.drawRoundRect(cardSplashRect, 10f, 10f, if (mojangSplashReached) cardActiveBgPaint else cardBgPaint)
        canvas.drawRoundRect(cardSplashRect, 10f, 10f, if (mojangSplashReached) accentStrokePaint else strokePaint)
        subPaint.textSize = u * 0.0175f
        subPaint.color = Color.parseColor("#94A3B8")
        drawFittedText(
            canvas,
            "ESTIMASI MUNCUL SPLASH MOJANG",
            cardSplashRect.left + u * 0.014f,
            cardSplashRect.top + u * 0.025f,
            cardSplashRect.width() - u * 0.028f,
            subPaint
        )
        titlePaint.textSize = u * 0.024f
        titlePaint.color = if (mojangSplashReached) Color.parseColor("#6EE7B7") else Color.parseColor("#FCD34D")
        val splashEtaMain = if (mojangSplashReached) {
            "SUDAH TAMPIL DI LAYAR"
        } else {
            "~${etaMojangSplashSec} DETIK LAGI"
        }
        drawFittedText(
            canvas,
            splashEtaMain,
            cardSplashRect.left + u * 0.014f,
            cardSplashRect.top + u * 0.056f,
            cardSplashRect.width() - u * 0.028f,
            titlePaint
        )
        subPaint.textSize = u * 0.0175f
        subPaint.color = Color.parseColor("#CBD5E1")
        val splashSub = if (mojangSplashReached) {
            "OpenGL Aktif (${swapBuffersCount.coerceAtLeast(1)} frame dirender)"
        } else {
            "Kelas JVM: $loadedClasses / ~$targetSplashClasses kelas"
        }
        drawFittedText(
            canvas,
            splashSub,
            cardSplashRect.left + u * 0.014f,
            cardSplashRect.top + u * 0.082f,
            cardSplashRect.width() - u * 0.028f,
            subPaint
        )

        // Card 2: ESTIMASI MENU UTAMA MINECRAFT
        canvas.drawRoundRect(cardMenuRect, 10f, 10f, if (titleScreenReached) cardActiveBgPaint else cardBgPaint)
        canvas.drawRoundRect(cardMenuRect, 10f, 10f, if (titleScreenReached) accentStrokePaint else strokePaint)
        subPaint.textSize = u * 0.0175f
        subPaint.color = Color.parseColor("#94A3B8")
        drawFittedText(
            canvas,
            "ESTIMASI MENU UTAMA MINECRAFT",
            cardMenuRect.left + u * 0.014f,
            cardMenuRect.top + u * 0.025f,
            cardMenuRect.width() - u * 0.028f,
            subPaint
        )
        titlePaint.textSize = u * 0.024f
        titlePaint.color = if (titleScreenReached) Color.parseColor("#6EE7B7") else Color.parseColor("#93C5FD")
        val menuEtaMain = if (titleScreenReached) {
            "MENU UTAMA SIAP!"
        } else {
            "~${etaMinecraftMenuSec} DETIK LAGI"
        }
        drawFittedText(
            canvas,
            menuEtaMain,
            cardMenuRect.left + u * 0.014f,
            cardMenuRect.top + u * 0.056f,
            cardMenuRect.width() - u * 0.028f,
            titlePaint
        )
        subPaint.textSize = u * 0.0175f
        subPaint.color = Color.parseColor("#CBD5E1")
        val speedLabel = if (classesPerSec > 0) "$classesPerSec kelas/dtk" else "Mengukur kecepatan SoC..."
        drawFittedText(
            canvas,
            "Kecepatan: $speedLabel",
            cardMenuRect.left + u * 0.014f,
            cardMenuRect.top + u * 0.082f,
            cardMenuRect.width() - u * 0.028f,
            subPaint
        )

        // Card 3: TELEMETRI ENGINE & MEMORI JVM
        canvas.drawRoundRect(cardMetricsRect, 10f, 10f, cardBgPaint)
        canvas.drawRoundRect(cardMetricsRect, 10f, 10f, strokePaint)
        subPaint.textSize = u * 0.0175f
        subPaint.color = Color.parseColor("#94A3B8")
        drawFittedText(
            canvas,
            "TELEMETRI ENGINE & MEMORI JVM",
            cardMetricsRect.left + u * 0.014f,
            cardMetricsRect.top + u * 0.025f,
            cardMetricsRect.width() - u * 0.028f,
            subPaint
        )
        titlePaint.textSize = u * 0.024f
        titlePaint.color = Color.parseColor("#F8FAFC")
        val ramStr = if (jvmUsedRamMb > 0) {
            "RAM: $jvmUsedRamMb / $jvmMaxRamMb MB"
        } else {
            "Target Heap: $jvmMaxRamMb MB"
        }
        drawFittedText(
            canvas,
            ramStr,
            cardMetricsRect.left + u * 0.014f,
            cardMetricsRect.top + u * 0.056f,
            cardMetricsRect.width() - u * 0.028f,
            titlePaint
        )
        subPaint.textSize = u * 0.0175f
        subPaint.color = Color.parseColor("#CBD5E1")
        drawFittedText(
            canvas,
            "Waktu Berjalan: ${elapsedSec} detik",
            cardMetricsRect.left + u * 0.014f,
            cardMetricsRect.top + u * 0.082f,
            cardMetricsRect.width() - u * 0.028f,
            subPaint
        )

        curY += cardH + innerPad * 0.38f

        // 4. Specific Sub-Task & Active Class Focus Banner
        val stripH = u * 0.054f
        subTaskStripRect.set(left, curY, right, curY + stripH)
        canvas.drawRoundRect(subTaskStripRect, 8f, 8f, cardBgPaint)
        canvas.drawRoundRect(subTaskStripRect, 8f, 8f, strokePaint)

        subPaint.textSize = u * 0.0185f
        subPaint.color = Color.parseColor("#E2E8F0")
        drawFittedText(
            canvas,
            "Sub-Tahap: $currentSubTaskDetail",
            subTaskStripRect.left + u * 0.014f,
            subTaskStripRect.top + u * 0.022f,
            subTaskStripRect.width() - u * 0.028f,
            subPaint
        )
        monoPaint.textSize = u * 0.017f
        monoPaint.color = Color.parseColor("#93C5FD")
        drawFittedText(
            canvas,
            "Fokus Kelas JVM: $currentActiveClass",
            subTaskStripRect.left + u * 0.014f,
            subTaskStripRect.top + u * 0.044f,
            subTaskStripRect.width() - u * 0.028f,
            monoPaint
        )

        curY += stripH + innerPad * 0.38f

        // 5. Bottom Buttons Area reservation
        val btnH = u * 0.072f
        val btnBottom = panelRect.bottom - innerPad * 0.85f
        val btnTop = btnBottom - btnH

        // 6. Live Terminal Box occupies middle space
        terminalRect.set(left, curY, right, btnTop - innerPad * 0.42f)
        canvas.drawRoundRect(terminalRect, 12f, 12f, terminalBgPaint)
        canvas.drawRoundRect(terminalRect, 12f, 12f, strokePaint)

        val lineH = u * 0.0245f
        val maxLines = ((terminalRect.height() - u * 0.032f) / lineH).toInt().coerceIn(5, 22)
        val lines = readLiveLogTail(maxLines)

        monoPaint.textSize = u * 0.0185f
        var logY = terminalRect.top + u * 0.028f
        val maxLogW = terminalRect.width() - u * 0.028f

        for (line in lines) {
            monoPaint.color = when {
                line.contains("FATAL") || line.contains("ERROR") || line.contains("JAVA-ERR") || line.contains("Exception") ->
                    Color.parseColor("#FCA5A5")
                line.contains("WARN") ->
                    Color.parseColor("#FCD34D")
                line.contains("PROGRES-BOOT") ->
                    Color.parseColor("#6EE7B7")
                line.contains("NATIVE-JVM-OUT") || line.contains("JVM-VM") ->
                    Color.parseColor("#A7F3D0")
                line.contains("STAGE") || line.contains("DLOPEN") || line.contains("BOOT") || line.contains("EGL") ->
                    Color.parseColor("#93C5FD")
                else ->
                    Color.parseColor("#CBD5E1")
            }
            drawFittedText(canvas, line, terminalRect.left + u * 0.014f, logY, maxLogW, monoPaint)
            logY += lineH
        }

        // Copy feedback banner if active
        val now = System.currentTimeMillis()
        if (copyFeedbackMessage != null && now < copyFeedbackUntilMs) {
            subPaint.color = Color.parseColor("#6EE7B7")
            subPaint.textSize = u * 0.020f
            drawFittedText(
                canvas,
                copyFeedbackMessage ?: "",
                terminalRect.left + u * 0.016f,
                terminalRect.bottom - u * 0.014f,
                maxLogW,
                subPaint
            )
        }

        // 7. Bottom 3 Action Buttons
        val gap = u * 0.018f
        val btnW = (right - left - gap * 2f) / 3f

        btnMinimizeRect.set(left, btnTop, left + btnW, btnBottom)
        btnCopyLogRect.set(btnMinimizeRect.right + gap, btnTop, btnMinimizeRect.right + gap + btnW, btnBottom)
        btnReturnMenuRect.set(btnCopyLogRect.right + gap, btnTop, right, btnBottom)

        canvas.drawRoundRect(btnMinimizeRect, 10f, 10f, btnPrimaryPaint)
        canvas.drawRoundRect(btnMinimizeRect, 10f, 10f, accentStrokePaint)

        canvas.drawRoundRect(btnCopyLogRect, 10f, 10f, btnSecondaryPaint)
        canvas.drawRoundRect(btnCopyLogRect, 10f, 10f, strokePaint)

        canvas.drawRoundRect(btnReturnMenuRect, 10f, 10f, btnWarnPaint)
        canvas.drawRoundRect(btnReturnMenuRect, 10f, 10f, strokePaint)

        btnTextPaint.textSize = u * 0.021f
        val textCenterOffset = btnH * 0.58f
        canvas.drawText(
            "TUTUP LOG (LIHAT LAYAR GAME)",
            btnMinimizeRect.centerX(),
            btnMinimizeRect.top + textCenterOffset,
            btnTextPaint
        )
        canvas.drawText(
            "SALIN LOG KE CLIPBOARD",
            btnCopyLogRect.centerX(),
            btnCopyLogRect.top + textCenterOffset,
            btnTextPaint
        )
        canvas.drawText(
            "KEMBALI KE MENU UTAMA",
            btnReturnMenuRect.centerX(),
            btnReturnMenuRect.top + textCenterOffset,
            btnTextPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE || isMinimized) return false
        val x = event.x
        val y = event.y

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            when {
                btnMinimizeRect.contains(x, y) -> {
                    isMinimized = true
                    visibility = GONE
                    invalidate()
                    return true
                }
                btnCopyLogRect.contains(x, y) -> {
                    copyBootLogToClipboard()
                    return true
                }
                btnReturnMenuRect.contains(x, y) -> {
                    stopBootMonitoring()
                    onReturnToLauncher()
                    return true
                }
            }
        }
        return true
    }

    private fun sanitizeLine(raw: String): String {
        return raw
            .replace("libpojavexec.so", "libvelocraft_bridge.so", ignoreCase = true)
            .replace("pojavexec", "velocraft_bridge", ignoreCase = true)
            .replace("pojavInitOpenGL", "initOpenGLContext", ignoreCase = true)
            .replace("pojavInit", "initBridge", ignoreCase = true)
            .replace("Pojav", "VeloCraft", ignoreCase = true)
    }

    private fun readLiveLogTail(maxLines: Int): List<String> {
        return try {
            val f = VeloLogger.latestLogFile
            if (f != null && f.exists()) {
                f.readLines().filter { it.isNotBlank() }.takeLast(maxLines).map { sanitizeLine(it) }
            } else {
                VeloLogger.readLatestLogsTail(6000)
                    .lines()
                    .filter { it.isNotBlank() }
                    .takeLast(maxLines)
                    .map { sanitizeLine(it) }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun copyBootLogToClipboard() {
        try {
            val content = VeloLogger.readLatestLogsTail(65000)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Minecraft Boot Log", content))
            val dirPath = VeloLogger.logDir?.absolutePath ?: "Android/media/id.xyverse.minecraft/log"
            copyFeedbackMessage = "Berhasil disalin ke Clipboard (${content.length} karakter) | File: $dirPath/latest.log"
            copyFeedbackUntilMs = System.currentTimeMillis() + 3500L
            invalidate()
        } catch (t: Throwable) {
            copyFeedbackMessage = "Gagal menyalin: ${t.message}"
            copyFeedbackUntilMs = System.currentTimeMillis() + 3500L
            invalidate()
        }
    }

    private fun drawFittedText(
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

    private fun loadAssetBitmap(assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
