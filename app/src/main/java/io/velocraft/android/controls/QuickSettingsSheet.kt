package io.velocraft.android.controls

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * In-Game Pro PvP Quick Settings Drawer.
 * Opens instantly over the game surface without restarting Minecraft, allowing players to tune:
 * - Touch Camera Look Sensitivity
 * - Gyroscope Sensitivity & Activation Mode (Off / Always On / Hold Right-Click)
 * - Auto-Clicker / Hold-to-Attack (Off / 12 CPS / 16 CPS)
 * - Global HUD Button Opacity
 * - Reset Default Pro PvP Layout
 */
object QuickSettingsSheet {

    fun show(
        context: Context,
        hudView: ProHudOverlayView,
        gyroController: GyroAimController,
        onResetLayout: () -> Unit
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // 1. Touch Look Sensitivity Slider
        val touchLabel = TextView(context).apply {
            text = "Touch Camera Sensitivity: ${(hudView.touchLookSensitivity * 100).toInt()}%"
        }
        val touchSeek = SeekBar(context).apply {
            max = 180
            progress = (hudView.touchLookSensitivity * 100).toInt().coerceIn(15, 180)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    val clamped = value.coerceAtLeast(15)
                    hudView.touchLookSensitivity = clamped / 100f
                    touchLabel.text = "Touch Camera Sensitivity: $clamped%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(touchLabel)
        container.addView(touchSeek)

        // 2. Gyroscope Sensitivity Slider
        val gyroLabel = TextView(context).apply {
            text = "Gyroscope Sensitivity: ${gyroController.sensitivityX.toInt()}x"
        }
        val gyroSeek = SeekBar(context).apply {
            max = 30
            progress = gyroController.sensitivityX.toInt().coerceIn(1, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    val v = value.coerceAtLeast(1).toFloat()
                    gyroController.sensitivityX = v
                    gyroController.sensitivityY = v * 0.78f
                    gyroLabel.text = "Gyroscope Sensitivity: ${v.toInt()}x"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(gyroLabel)
        container.addView(gyroSeek)

        // 3. Hold-to-Attack PvP Auto-Clicker Toggle (14 CPS)
        val autoClickSwitch = Switch(context).apply {
            text = "Hold-to-Attack Auto-Clicker (14 CPS)"
            isChecked = hudView.holdAutoClickEnabled
            setOnCheckedChangeListener { _, checked ->
                hudView.holdAutoClickEnabled = checked
            }
        }
        container.addView(autoClickSwitch)

        // 4. Crosshair Style Switcher
        val crosshairSwitch = Switch(context).apply {
            text = "Dot + Circle Pro Crosshair"
            isChecked = hudView.useProCircleCrosshair
            setOnCheckedChangeListener { _, checked ->
                hudView.useProCircleCrosshair = checked
                hudView.postInvalidateOnAnimation()
            }
        }
        container.addView(crosshairSwitch)

        AlertDialog.Builder(context)
            .setTitle("⚡ VeloCraft Pro PvP Settings")
            .setView(container)
            .setPositiveButton("Simpan & Lanjut Main") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Reset Posisi Tombol HUD") { dialog, _ ->
                onResetLayout()
                dialog.dismiss()
            }
            .show()
    }
}
