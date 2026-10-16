package io.velocraft.android.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.velocraft.android.util.VeloLogger

/**
 * 100% Custom Canvas Floating Overlay Pill ("Tampil di Atas Aplikasi Lain").
 *
 * Strictly zero stock Android widgets and zero emojis.
 * Allows monitoring resumable download progress while using other apps.
 */
class FloatingDownloadOverlay(private val context: Context) {

    companion object {
        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        }

        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    VeloLogger.warn("Could not open overlay permission settings: ${t.message}")
                }
            }
        }
    }

    private val appCtx = context.applicationContext
    private val windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var pillView: FloatingCanvasPillView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun showOrUpdate(percent: Int, phaseTitle: String, detailText: String) {
        if (!canDrawOverlays(appCtx)) return
        if (percent >= 100) {
            dismiss()
            return
        }

        if (pillView == null) {
            try {
                val density = appCtx.resources.displayMetrics.density
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    (270 * density).toInt(),
                    (52 * density).toInt(),
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (20 * density).toInt()
                    y = (32 * density).toInt()
                }
                layoutParams = params

                val view = FloatingCanvasPillView(appCtx) { dx, dy ->
                    params.x += dx
                    params.y += dy
                    runCatching { windowManager.updateViewLayout(pillView, params) }
                }
                windowManager.addView(view, params)
                pillView = view
            } catch (t: Throwable) {
                VeloLogger.warn("FloatingDownloadOverlay attach warning: ${t.message}")
            }
        }

        pillView?.updateContent(percent, phaseTitle, detailText)
    }

    fun dismiss() {
        val v = pillView ?: return
        runCatching { windowManager.removeView(v) }
        pillView = null
    }

    private class FloatingCanvasPillView(
        context: Context,
        private val onDragBy: (dx: Int, dy: Int) -> Unit
    ) : View(context) {

        private var pct: Int = 0
        private var title: String = "Mengunduh Minecraft 1.20.4"
        private var sub: String = ""

        private var lastRawX = 0f
        private var lastRawY = 0f

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF21C1B18.toInt() }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4E8C57.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF33312C.toInt() }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4E8C57.toInt() }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF4F1EA.toInt()
            textSize = dp(11f)
            isFakeBoldText = true
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA8A39A.toInt()
            textSize = dp(9.5f)
        }
        private val rect = RectF()

        private fun dp(v: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

        fun updateContent(percent: Int, phaseTitle: String, detailText: String) {
            pct = percent.coerceIn(0, 100)
            title = "[DL $pct%] ${phaseTitle.take(28)}"
            sub = detailText.take(36)
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            rect.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
            canvas.drawRoundRect(rect, dp(12f), dp(12f), bgPaint)
            canvas.drawRoundRect(rect, dp(12f), dp(12f), borderPaint)

            canvas.drawText(title, dp(12f), dp(18f), titlePaint)
            canvas.drawText(sub, dp(12f), dp(32f), subPaint)

            val barLeft = dp(12f)
            val barRight = w - dp(12f)
            val barTop = h - dp(10f)
            val barBottom = h - dp(5f)
            rect.set(barLeft, barTop, barRight, barBottom)
            canvas.drawRoundRect(rect, dp(3f), dp(3f), trackPaint)

            val fillW = (barRight - barLeft) * (pct / 100f)
            if (fillW > 0f) {
                rect.set(barLeft, barTop, barLeft + fillW, barBottom)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), fillPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).toInt()
                    val dy = (event.rawY - lastRawY).toInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onDragBy(dx, dy)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
