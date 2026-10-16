package io.velocraft.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Displayed automatically if an unexpected exception occurs, showing the exact stack trace
 * and confirming that the log was saved to `/storage/emulated/0/Android/media/io.velocraft.android/log/`.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = intent.getStringExtra("crash_report") ?: "Unknown Error"
        val logPath = intent.getStringExtra("log_path") ?: "/sdcard/Android/media/$packageName/log"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D16"))
            setPadding(pad, pad, pad, pad)
        }

        val titleView = TextView(this).apply {
            text = "⚠️ Minecraft Porting — Diagnostic Crash Log"
            setTextColor(Color.parseColor("#EF4444"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }

        val pathView = TextView(this).apply {
            text = "Log tersimpan otomatis di: $logPath/crash.log & latest.log"
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, pad / 3, 0, pad / 2)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val copyBtn = Button(this).apply {
            text = "Salin Log (Copy)"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MinecraftPortCrashLog", report))
                Toast.makeText(this@CrashReportActivity, "Log disalin ke Clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        val restartBtn = Button(this).apply {
            text = "Buka Ulang Game"
            setOnClickListener {
                val restartIntent = Intent(this@CrashReportActivity, GameActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(restartIntent)
                finish()
            }
        }

        btnRow.addView(copyBtn)
        btnRow.addView(restartBtn)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val logText = TextView(this).apply {
            text = report
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(logText)

        root.addView(titleView)
        root.addView(pathView)
        root.addView(btnRow)
        root.addView(scroll)

        setContentView(root)
    }
}
