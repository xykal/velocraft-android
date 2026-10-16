package io.velocraft.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * 100% Custom Canvas Crash Report Viewer (Zero stock Android widgets, Zero emojis).
 * Displays the structured crash report and confirms that `crash.log` & `latest.log`
 * were written to `/storage/emulated/0/Android/media/io.velocraft.android/log/`.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra("crash_report") ?: "Unknown Fatal Error"
        val logPath = intent.getStringExtra("log_path") ?: "/sdcard/Android/media/$packageName/log"
        setContentView(CrashCanvasView(this, report, logPath))
    }

    private inner class CrashCanvasView(
        context: Context,
        private val reportText: String,
        private val logPath: String
    ) : View(context) {

        private val lines: List<String> = reportText.lines()
        private var scrollYOffset: Float = 0f
        private var lastTouchY: Float = 0f
        private var copiedBannerText: String? = null

        private val copyBtnRect = RectF()
        private val restartBtnRect = RectF()
        private val tmpRect = RectF()

        private val bgPaint = Paint().apply { color = 0xFF141413.toInt() }
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1D1A.toInt() }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF33312C.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(1.2f)
        }
        private val btnGreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4E8C57.toInt() }
        private val btnTerraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC87D55.toInt() }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD97757.toInt()
            textSize = dp(16f)
            isFakeBoldText = true
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA39E93.toInt()
            textSize = dp(11f)
        }
        private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF4F1EA.toInt()
            textSize = dp(10.5f)
            typeface = Typeface.MONOSPACE
        }
        private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF4F1EA.toInt()
            textSize = dp(11.5f)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        private fun dp(v: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = dp(16f)

            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // Header
            canvas.drawText("MINECRAFT PORTING - DIAGNOSTIC CRASH LOG", pad, pad + dp(16f), titlePaint)
            val pathInfo = copiedBannerText ?: "Saved to: $logPath/crash.log & latest.log"
            canvas.drawText(pathInfo, pad, pad + dp(34f), subPaint)

            // Action Buttons (Top Right)
            val btnW = dp(130f)
            val btnH = dp(34f)
            restartBtnRect.set(w - pad - btnW, pad, w - pad, pad + btnH)
            copyBtnRect.set(restartBtnRect.left - dp(10f) - btnW, pad, restartBtnRect.left - dp(10f), pad + btnH)

            canvas.drawRoundRect(copyBtnRect, dp(8f), dp(8f), btnTerraPaint)
            canvas.drawRoundRect(restartBtnRect, dp(8f), dp(8f), btnGreenPaint)
            canvas.drawText("COPY LOG", copyBtnRect.centerX(), copyBtnRect.centerY() + dp(4f), btnTextPaint)
            canvas.drawText("RESTART APP", restartBtnRect.centerX(), restartBtnRect.centerY() + dp(4f), btnTextPaint)

            // Log Box
            val boxTop = pad + dp(46f)
            tmpRect.set(pad, boxTop, w - pad, h - pad)
            canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), cardPaint)
            canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), borderPaint)

            canvas.save()
            canvas.clipRect(tmpRect.left + dp(10f), tmpRect.top + dp(10f), tmpRect.right - dp(10f), tmpRect.bottom - dp(10f))
            val lineH = dp(15f)
            var y = tmpRect.top + dp(24f) - scrollYOffset
            for (line in lines) {
                if (y >= tmpRect.top && y <= tmpRect.bottom + lineH) {
                    canvas.drawText(line, tmpRect.left + dp(12f), y, monoPaint)
                }
                y += lineH
            }
            canvas.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = lastTouchY - event.y
                    lastTouchY = event.y
                    val maxScroll = max(0f, lines.size * dp(15f) - (height - dp(100f)))
                    scrollYOffset = (scrollYOffset + dy).coerceIn(0f, maxScroll)
                    postInvalidateOnAnimation()
                }
                MotionEvent.ACTION_UP -> {
                    val x = event.x
                    val y = event.y
                    if (copyBtnRect.contains(x, y)) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("CrashLog", reportText))
                        copiedBannerText = "[COPIED TO CLIPBOARD] $logPath/crash.log"
                        postInvalidateOnAnimation()
                    } else if (restartBtnRect.contains(x, y)) {
                        val restartIntent = Intent(this@CrashReportActivity, GameActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        startActivity(restartIntent)
                        finish()
                    }
                }
            }
            return true
        }
    }
}
