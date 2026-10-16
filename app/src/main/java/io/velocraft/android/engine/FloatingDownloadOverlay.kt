package io.velocraft.android.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.velocraft.android.util.VeloLogger

/**
 * Clean Matte Floating Overlay Pill ("Tampil di Atas Aplikasi Lain").
 * When the user grants `SYSTEM_ALERT_WINDOW` permission, this compact pill floats over other apps
 * so the user can monitor download progress (MB, %, Speed) while chatting or browsing.
 */
object FloatingDownloadOverlay {

    private var overlayView: View? = null
    private var statusTextView: TextView? = null
    private var windowManager: WindowManager? = null

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

    @SuppressLint("ClickableViewAccessibility")
    fun showOrUpdate(context: Context, snapshot: ResumableDownloadEngine.DownloadSnapshot) {
        if (!canDrawOverlays(context)) return

        if (snapshot.isCompleted) {
            dismiss()
            return
        }

        val appCtx = context.applicationContext
        val wm = (windowManager ?: appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }

        if (overlayView == null) {
            try {
                val density = appCtx.resources.displayMetrics.density
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#F21C1B18")) // Matte Warm Charcoal
                    cornerRadius = 22f * density
                    setStroke((1.5f * density).toInt(), Color.parseColor("#4E8C57")) // Matte Moss Green
                }

                val tv = TextView(appCtx).apply {
                    setTextColor(Color.parseColor("#F4F1EA"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.DEFAULT_BOLD
                    val padH = (14 * density).toInt()
                    val padV = (8 * density).toInt()
                    setPadding(padH, padV, padH, padV)
                }
                statusTextView = tv

                val container = LinearLayout(appCtx).apply {
                    background = bg
                    addView(tv)
                }

                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (24 * density).toInt()
                    y = (36 * density).toInt()
                }

                var initialX = 0
                var initialY = 0
                var touchX = 0f
                var touchY = 0f

                container.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            touchX = event.rawX
                            touchY = event.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - touchX).toInt()
                            params.y = initialY + (event.rawY - touchY).toInt()
                            runCatching { wm.updateViewLayout(container, params) }
                            true
                        }
                        else -> false
                    }
                }

                wm.addView(container, params)
                overlayView = container
            } catch (t: Throwable) {
                VeloLogger.warn("Failed to attach floating overlay: ${t.message}")
            }
        }

        val dlMb = snapshot.downloadedBytes / (1024L * 1024L)
        val totalMb = (snapshot.totalBytes / (1024L * 1024L)).coerceAtLeast(1L)
        val speedMbps = "%.1f".format(snapshot.speedBytesPerSec / (1024f * 1024f))
        val stateIcon = if (snapshot.isPaused) "⏸" else "⬇"
        statusTextView?.text = "$stateIcon Minecraft 1.20.4 • ${snapshot.percent}% ($dlMb/$totalMb MB) • $speedMbps MB/s"
    }

    fun dismiss() {
        val v = overlayView ?: return
        runCatching {
            windowManager?.removeView(v)
        }
        overlayView = null
        statusTextView = null
    }
}
