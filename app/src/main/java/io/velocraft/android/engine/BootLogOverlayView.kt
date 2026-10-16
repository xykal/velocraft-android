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
import io.velocraft.android.util.VeloLogger
import kotlin.math.min

/**
 * 100% Custom Canvas Live Boot Console & Progress Overlay.
 *
 * Displayed during JVM/Minecraft startup so the user never sees a blank black screen
 * while OpenJDK (`libjli.so` / `libjvm.so`) and Fabric Loader (`KnotClient`) are booting.
 * Streams real-time native `stdout`/`stderr` and Kotlin `VeloLogger` lines on screen,
 * and allows collapsing to a compact top status bar or returning to the Launcher menu.
 *
 * Strictly zero Android stock widgets, zero neon colors, and zero emojis.
 */
class BootLogOverlayView(
    context: Context,
    private val onReturnToLauncher: () -> Unit
) : View(context) {

    var bootStageTitle: String = "TAHAP 1/5: Menyiapkan Lingkungan Engine..."
    var bootStageProgress: Int = 15
    var bootSummaryLine: String = "OpenJDK HotSpot | Fabric 0.16.9 | Minecraft 1.20.4"
    var isJvmExited: Boolean = false
    var jvmExitCode: Int = 0
    var isMinimized: Boolean = false

    private var copyFeedbackMessage: String? = null
    private var copyFeedbackUntilMs: Long = 0L

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EC11161F")
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
                invalidate()
                postDelayed(this, 250L)
            }
        }
    }

    init {
        brandMarkBitmap = loadAssetBitmap("branding/xyverse_mark.png")
    }

    fun startBootMonitoring() {
        visibility = VISIBLE
        isMinimized = false
        isJvmExited = false
        removeCallbacks(refreshTick)
        post(refreshTick)
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
            bootStageTitle = if (exitCode == 0) {
                "SESI JVM SELESAI (KODE KELUAR 0)"
            } else {
                "SESI JVM BERHENTI (KODE KELUAR $exitCode) - PERIKSA LOG DI BAWAH"
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
            drawMinimizedTopPill(canvas, w, h, u)
            return
        }

        // Semi-transparent dark backdrop so any underlying GL frame is still subtly visible
        canvas.drawColor(Color.parseColor("#B8070A0F"))

        val padX = w * 0.06f
        val padY = h * 0.06f
        panelRect.set(padX, padY, w - padX, h - padY)
        canvas.drawRoundRect(panelRect, 18f, 18f, panelPaint)
        canvas.drawRoundRect(panelRect, 18f, 18f, strokePaint)

        val innerPad = u * 0.032f
        val left = panelRect.left + innerPad
        val right = panelRect.right - innerPad
        var curY = panelRect.top + innerPad

        // Header Row: Subtle Mark + Title + Summary
        val markSize = u * 0.065f
        brandMarkBitmap?.let { bmp ->
            val dst = RectF(left, curY, left + markSize, curY + markSize)
            canvas.drawBitmap(bmp, null, dst, null)
        }
        val titleX = left + markSize + innerPad * 0.6f
        titlePaint.textSize = u * 0.033f
        drawFittedText(
            canvas,
            "KONSOL BOOTING & LOG ENGINE LIVE - MINECRAFT 1.20.4",
            titleX,
            curY + u * 0.030f,
            right - titleX,
            titlePaint
        )

        subPaint.textSize = u * 0.022f
        subPaint.color = Color.parseColor("#94A3B8")
        drawFittedText(
            canvas,
            bootSummaryLine,
            titleX,
            curY + u * 0.060f,
            right - titleX,
            subPaint
        )

        curY += markSize + innerPad * 0.65f

        // Stage Title & Progress Bar
        titlePaint.textSize = u * 0.025f
        titlePaint.color = if (isJvmExited && jvmExitCode != 0) {
            Color.parseColor("#FCA5A5")
        } else {
            Color.parseColor("#6EE7B7")
        }
        drawFittedText(
            canvas,
            "$bootStageTitle ($bootStageProgress%)",
            left,
            curY + u * 0.022f,
            right - left,
            titlePaint
        )
        titlePaint.color = Color.parseColor("#F8FAFC")

        curY += u * 0.034f
        val barH = u * 0.020f
        progressTrackRect.set(left, curY, right, curY + barH)
        canvas.drawRoundRect(progressTrackRect, 8f, 8f, progressTrackPaint)

        val fillW = (progressTrackRect.width() * (bootStageProgress / 100f)).coerceAtLeast(12f)
        progressFillRect.set(left, curY, left + fillW, curY + barH)
        canvas.drawRoundRect(progressFillRect, 8f, 8f, progressFillPaint)

        curY += barH + innerPad * 0.55f

        // Bottom Buttons Area reservation
        val btnH = u * 0.078f
        val btnBottom = panelRect.bottom - innerPad
        val btnTop = btnBottom - btnH

        // Live Terminal Box occupies middle space
        terminalRect.set(left, curY, right, btnTop - innerPad * 0.55f)
        canvas.drawRoundRect(terminalRect, 12f, 12f, terminalBgPaint)
        canvas.drawRoundRect(terminalRect, 12f, 12f, strokePaint)

        // Render Live Log Lines inside Terminal Box
        val lineH = u * 0.026f
        val maxLines = ((terminalRect.height() - u * 0.04f) / lineH).toInt().coerceIn(6, 26)
        val lines = readLiveLogTail(maxLines)

        monoPaint.textSize = u * 0.0195f
        var logY = terminalRect.top + u * 0.032f
        val maxLogW = terminalRect.width() - u * 0.03f

        for (line in lines) {
            monoPaint.color = when {
                line.contains("FATAL") || line.contains("ERROR") || line.contains("Exception") ->
                    Color.parseColor("#FCA5A5")
                line.contains("WARN") ->
                    Color.parseColor("#FCD34D")
                line.contains("NATIVE-JVM-OUT") || line.contains("JLI_Launch") ->
                    Color.parseColor("#6EE7B7")
                line.contains("STAGE") || line.contains("DLOPEN") || line.contains("BOOT") ->
                    Color.parseColor("#93C5FD")
                else ->
                    Color.parseColor("#CBD5E1")
            }
            drawFittedText(canvas, line, terminalRect.left + u * 0.015f, logY, maxLogW, monoPaint)
            logY += lineH
        }

        // Copy feedback banner if active
        val now = System.currentTimeMillis()
        if (copyFeedbackMessage != null && now < copyFeedbackUntilMs) {
            subPaint.color = Color.parseColor("#6EE7B7")
            subPaint.textSize = u * 0.021f
            drawFittedText(
                canvas,
                copyFeedbackMessage ?: "",
                terminalRect.left + u * 0.018f,
                terminalRect.bottom - u * 0.015f,
                maxLogW,
                subPaint
            )
        }

        // Bottom 3 Action Buttons
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

        btnTextPaint.textSize = u * 0.022f
        val textCenterOffset = btnH * 0.58f
        canvas.drawText(
            "SEMBUNYIKAN LOG (LIHAT LAYAR)",
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

    private fun drawMinimizedTopPill(canvas: Canvas, w: Float, h: Float, u: Float) {
        val pillW = w * 0.54f
        val pillH = u * 0.072f
        val left = (w - pillW) * 0.5f
        val top = h * 0.018f
        minimizedPillRect.set(left, top, left + pillW, top + pillH)

        canvas.drawRoundRect(minimizedPillRect, 14f, 14f, panelPaint)
        canvas.drawRoundRect(minimizedPillRect, 14f, 14f, accentStrokePaint)

        btnTextPaint.textSize = u * 0.021f
        val statusText = "$bootStageTitle  |  [ KETUK UNTUK TAMPILKAN LOG BOOT ]"
        drawFittedText(
            canvas,
            statusText,
            minimizedPillRect.left + u * 0.025f,
            minimizedPillRect.top + pillH * 0.60f,
            minimizedPillRect.width() - u * 0.05f,
            subPaint.apply {
                color = Color.parseColor("#E2E8F0")
                textSize = u * 0.021f
            }
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val x = event.x
        val y = event.y

        if (isMinimized) {
            if (minimizedPillRect.contains(x, y)) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    isMinimized = false
                    invalidate()
                }
                return true
            }
            // Let touches outside the minimized pill pass directly through to ProHudOverlayView!
            return false
        }

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            when {
                btnMinimizeRect.contains(x, y) -> {
                    isMinimized = true
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

    private fun readLiveLogTail(maxLines: Int): List<String> {
        val recent = VeloLogger.getRecentLines(maxLines * 2)
        val fileLines = try {
            val f = VeloLogger.getLatestLogFile()
            if (f != null && f.exists()) {
                f.readLines().takeLast(maxLines * 2)
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val combined = if (fileLines.isNotEmpty()) fileLines else recent
        return combined.filter { it.isNotBlank() }.takeLast(maxLines)
    }

    private fun copyBootLogToClipboard() {
        try {
            val content = VeloLogger.readFullLogText()
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Minecraft Boot Log", content))
            copyFeedbackMessage = "Berhasil disalin ke Clipboard (${content.length} karakter) | File: ${VeloLogger.getLogDirectoryPath()}/latest.log"
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
