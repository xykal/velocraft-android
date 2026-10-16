package io.velocraft.android.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/**
 * Minimal, zero-clutter First-Boot Setup Overlay.
 * Shown ONLY on the first launch while VeloCraft provisions the optimization stack
 * (Sodium, Lithium, FerriteCore, ImmediatelyFast) and Minecraft 1.20.4 libraries.
 */
class FirstBootSplashView(context: Context) : View(context) {

    private val hwProfile = JvmOptimizer.detectHardware(context)
    private var statusMessage: String = "Menyiapkan VeloCraft Direct Engine..."
    private var progressPercent: Int = 5

    private val bgPaint = Paint().apply { color = 0xF2090D16.toInt() }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E293B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt()
        textSize = dp(20f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt()
        textSize = dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E293B.toInt() }
    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF38BDF8.toInt() }

    private val cardRect = RectF()
    private val barBgRect = RectF()
    private val barFillRect = RectF()

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    fun updateProgress(progress: DirectBootProvisioner.ProvisionProgress) {
        statusMessage = progress.statusText
        progressPercent = progress.percent.coerceIn(0, 100)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val cardW = dp(460f).coerceAtMost(w * 0.88f)
        val cardH = dp(185f)
        val left = (w - cardW) * 0.5f
        val top = (h - cardH) * 0.5f
        cardRect.set(left, top, left + cardW, top + cardH)

        canvas.drawRoundRect(cardRect, dp(16f), dp(16f), cardPaint)
        canvas.drawRoundRect(cardRect, dp(16f), dp(16f), borderPaint)

        val cx = w * 0.5f
        canvas.drawText("⚡ VELOCRAFT DIRECT 1.20.4", cx, top + dp(40f), titlePaint)

        val hwText =
            "ABI: ${hwProfile.abi}  •  SoC: ${hwProfile.socModel}  •  RAM Heap: ${hwProfile.allocatedHeapMb} MB"
        canvas.drawText(hwText, cx, top + dp(66f), subPaint)

        subPaint.color = Color.WHITE
        canvas.drawText("$statusMessage ($progressPercent%)", cx, top + dp(105f), subPaint)
        subPaint.color = 0xFF94A3B8.toInt()

        val barW = cardW - dp(56f)
        val barH = dp(12f)
        val barLeft = cx - barW * 0.5f
        val barTop = top + dp(122f)
        barBgRect.set(barLeft, barTop, barLeft + barW, barTop + barH)
        barFillRect.set(barLeft, barTop, barLeft + (barW * (progressPercent / 100f)), barTop + barH)

        canvas.drawRoundRect(barBgRect, dp(6f), dp(6f), barBgPaint)
        canvas.drawRoundRect(barFillRect, dp(6f), dp(6f), barFillPaint)

        canvas.drawText(
            "Setup otomatis 1x saja • Buka aplikasi berikutnya langsung masuk ke game",
            cx,
            top + dp(160f),
            subPaint
        )
    }
}
