package io.velocraft.android.util

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.controls.ActionType
import kotlin.math.cos
import kotlin.math.sin

/**
 * Studio-grade, multi-layered custom Canvas vector icon renderer for VeloCraft.
 * Provides detailed geometric vector artwork for all launcher screens, modals,
 * telemetry bars, and every in-game HUD control button without external bitmap dependencies.
 */
object VeloVectorIcons {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val subPath = Path()
    private val rect = RectF()

    private fun withAlpha(color: Int, alphaFactor: Float): Int {
        val baseA = Color.alpha(color)
        val a = (baseA * alphaFactor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    /**
     * Draws a rich, multi-layered tactical gaming icon for an in-game HUD control button
     * centered at (cx, cy) with radius `r`.
     */
    fun drawControlActionIcon(
        canvas: Canvas,
        action: ActionType,
        keyCodeOrButton: Int = 0,
        cx: Float,
        cy: Float,
        r: Float,
        primaryColor: Int,
        accentColor: Int,
        alphaScale: Float = 1.0f
    ) {
        if (r <= 2f) return
        val sw = (r * 0.15f).coerceAtLeast(1.5f)
        val mainCol = withAlpha(primaryColor, alphaScale)
        val subCol = withAlpha(accentColor, alphaScale * 0.94f)
        val softFillCol = withAlpha(accentColor, alphaScale * 0.24f)
        val haloCol = withAlpha(accentColor, alphaScale * 0.11f)

        // Subtle ambient center halo behind the icon artwork
        glowPaint.color = haloCol
        canvas.drawCircle(cx, cy, r * 0.94f, glowPaint)

        strokePaint.strokeWidth = sw
        strokePaint.color = mainCol
        fillPaint.color = softFillCol

        when {
            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_W ->
                drawDirectionalArrow(canvas, cx, cy, r, 0f, mainCol, subCol, softFillCol, sw)

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_D ->
                drawDirectionalArrow(canvas, cx, cy, r, 90f, mainCol, subCol, softFillCol, sw)

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_S ->
                drawDirectionalArrow(canvas, cx, cy, r, 180f, mainCol, subCol, softFillCol, sw)

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_A ->
                drawDirectionalArrow(canvas, cx, cy, r, 270f, mainCol, subCol, softFillCol, sw)

            action == ActionType.MOUSE_LEFT_ATTACK -> {
                // Tactical 45-degree Broadsword + Corner Target Reticles
                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 0.75f
                val cr = r * 0.82f
                canvas.drawLine(cx - cr, cy - cr, cx - cr * 0.55f, cy - cr, strokePaint)
                canvas.drawLine(cx - cr, cy - cr, cx - cr, cy - cr * 0.55f, strokePaint)
                canvas.drawLine(cx + cr, cy + cr, cx + cr * 0.55f, cy + cr, strokePaint)
                canvas.drawLine(cx + cr, cy + cr, cx + cr, cy + cr * 0.55f, strokePaint)

                // Beveled Sword Blade (bottom-left hilt to top-right point)
                path.reset()
                path.moveTo(cx + r * 0.78f, cy - r * 0.78f)
                path.lineTo(cx + r * 0.25f, cy - r * 0.52f)
                path.lineTo(cx - r * 0.28f, cy + r * 0.02f)
                path.lineTo(cx - r * 0.02f, cy + r * 0.28f)
                path.lineTo(cx + r * 0.52f, cy - r * 0.25f)
                path.close()
                fillPaint.color = softFillCol
                canvas.drawPath(path, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawPath(path, strokePaint)

                // Inner blade fuller line
                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 0.7f
                canvas.drawLine(cx - r * 0.10f, cy + r * 0.10f, cx + r * 0.56f, cy - r * 0.56f, strokePaint)

                // Crossguard
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw * 1.15f
                canvas.drawLine(cx - r * 0.48f, cy - r * 0.12f, cx + r * 0.12f, cy + r * 0.48f, strokePaint)

                // Grip & Pommel
                strokePaint.strokeWidth = sw * 1.1f
                canvas.drawLine(cx - r * 0.18f, cy + r * 0.18f, cx - r * 0.62f, cy + r * 0.62f, strokePaint)
                fillPaint.color = subCol
                canvas.drawCircle(cx - r * 0.68f, cy + r * 0.68f, sw * 1.05f, fillPaint)
            }

            action == ActionType.MOUSE_RIGHT_PLACE -> {
                // Isometric 3D Voxel Block + Glowing '+' Spark
                val bx = cx - r * 0.08f
                val by = cy + r * 0.10f
                val br = r * 0.68f
                path.reset()
                path.moveTo(bx, by - br * 0.85f)
                path.lineTo(bx + br * 0.82f, by - br * 0.38f)
                path.lineTo(bx, by + br * 0.08f)
                path.lineTo(bx - br * 0.82f, by - br * 0.38f)
                path.close()
                fillPaint.color = withAlpha(accentColor, alphaScale * 0.35f)
                canvas.drawPath(path, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw * 0.9f
                canvas.drawPath(path, strokePaint)

                path.reset()
                path.moveTo(bx - br * 0.82f, by - br * 0.38f)
                path.lineTo(bx, by + br * 0.08f)
                path.lineTo(bx + br * 0.82f, by - br * 0.38f)
                path.lineTo(bx + br * 0.82f, by + br * 0.48f)
                path.lineTo(bx, by + br * 0.92f)
                path.lineTo(bx - br * 0.82f, by + br * 0.48f)
                path.close()
                fillPaint.color = softFillCol
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(bx, by + br * 0.08f, bx, by + br * 0.92f, strokePaint)

                // Top-Right '+' Placement Spark
                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 1.05f
                val px = cx + r * 0.56f
                val py = cy - r * 0.56f
                val pr = r * 0.24f
                canvas.drawLine(px - pr, py, px + pr, py, strokePaint)
                canvas.drawLine(px, py - pr, px, py + pr, strokePaint)
            }

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_SPACE -> {
                // Double Winged Upward Boost Chevrons + Launch Pad
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw * 1.15f
                path.reset()
                path.moveTo(cx - r * 0.62f, cy - r * 0.12f)
                path.lineTo(cx, cy - r * 0.72f)
                path.lineTo(cx + r * 0.62f, cy - r * 0.12f)
                canvas.drawPath(path, strokePaint)

                strokePaint.color = subCol
                strokePaint.strokeWidth = sw
                path.reset()
                path.moveTo(cx - r * 0.44f, cy + r * 0.32f)
                path.lineTo(cx, cy - r * 0.12f)
                path.lineTo(cx + r * 0.44f, cy + r * 0.32f)
                canvas.drawPath(path, strokePaint)

                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw * 1.1f
                canvas.drawLine(cx - r * 0.56f, cy + r * 0.68f, cx + r * 0.56f, cy + r * 0.68f, strokePaint)
            }

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_LEFT_SHIFT -> {
                // Tactical Crouch / Stealth Shield Chevron + Low Stance Bar
                path.reset()
                path.moveTo(cx - r * 0.58f, cy - r * 0.42f)
                path.lineTo(cx, cy + r * 0.16f)
                path.lineTo(cx + r * 0.58f, cy - r * 0.42f)
                path.lineTo(cx + r * 0.32f, cy - r * 0.42f)
                path.lineTo(cx, cy - r * 0.10f)
                path.lineTo(cx - r * 0.32f, cy - r * 0.42f)
                path.close()
                fillPaint.color = softFillCol
                canvas.drawPath(path, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawPath(path, strokePaint)

                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 1.1f
                canvas.drawLine(cx - r * 0.64f, cy + r * 0.55f, cx + r * 0.64f, cy + r * 0.55f, strokePaint)
                canvas.drawLine(cx - r * 0.64f, cy + r * 0.32f, cx - r * 0.64f, cy + r * 0.55f, strokePaint)
                canvas.drawLine(cx + r * 0.64f, cy + r * 0.32f, cx + r * 0.64f, cy + r * 0.55f, strokePaint)
            }

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_LEFT_CONTROL -> {
                // Sprint: Lightning Bolt + Aerodynamic Speed Chevrons
                path.reset()
                path.moveTo(cx + r * 0.08f, cy - r * 0.78f)
                path.lineTo(cx - r * 0.46f, cy + r * 0.05f)
                path.lineTo(cx - r * 0.04f, cy + r * 0.05f)
                path.lineTo(cx - r * 0.22f, cy + r * 0.78f)
                path.lineTo(cx + r * 0.42f, cy - r * 0.08f)
                path.lineTo(cx, cy - r * 0.08f)
                path.close()
                fillPaint.color = withAlpha(accentColor, alphaScale * 0.32f)
                canvas.drawPath(path, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw * 0.95f
                canvas.drawPath(path, strokePaint)

                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 0.9f
                canvas.drawLine(cx + r * 0.46f, cy - r * 0.42f, cx + r * 0.76f, cy, strokePaint)
                canvas.drawLine(cx + r * 0.76f, cy, cx + r * 0.46f, cy + r * 0.42f, strokePaint)
            }

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_E -> {
                // 3D Treasure Chest / Inventory Pack with Golden Lock Clasp
                rect.set(cx - r * 0.72f, cy - r * 0.48f, cx + r * 0.72f, cy + r * 0.58f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, strokePaint)

                canvas.drawLine(cx - r * 0.72f, cy - r * 0.06f, cx + r * 0.72f, cy - r * 0.06f, strokePaint)
                rect.set(cx - r * 0.28f, cy - r * 0.72f, cx + r * 0.28f, cy - r * 0.46f)
                canvas.drawRoundRect(rect, r * 0.10f, r * 0.10f, strokePaint)

                rect.set(cx - r * 0.16f, cy - r * 0.22f, cx + r * 0.16f, cy + r * 0.16f)
                fillPaint.color = subCol
                canvas.drawRoundRect(rect, r * 0.06f, r * 0.06f, fillPaint)
            }

            ((action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_Q) ||
                action == ActionType.MACRO_DROP_STACK -> {
                // Ejecting Cube + Downward Trajectory Arrow
                rect.set(cx - r * 0.36f, cy - r * 0.72f, cx + r * 0.36f, cy - r * 0.08f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.12f, r * 0.12f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawRoundRect(rect, r * 0.12f, r * 0.12f, strokePaint)

                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 1.1f
                canvas.drawLine(cx, cy - r * 0.02f, cx, cy + r * 0.72f, strokePaint)
                canvas.drawLine(cx - r * 0.36f, cy + r * 0.38f, cx, cy + r * 0.74f, strokePaint)
                canvas.drawLine(cx + r * 0.36f, cy + r * 0.38f, cx, cy + r * 0.74f, strokePaint)
            }

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_F -> {
                // Offhand Heater Shield + Swap Arrow
                path.reset()
                path.moveTo(cx, cy - r * 0.74f)
                path.lineTo(cx + r * 0.62f, cy - r * 0.48f)
                path.lineTo(cx + r * 0.52f, cy + r * 0.18f)
                path.lineTo(cx, cy + r * 0.76f)
                path.lineTo(cx - r * 0.52f, cy + r * 0.18f)
                path.lineTo(cx - r * 0.62f, cy - r * 0.48f)
                path.close()
                fillPaint.color = softFillCol
                canvas.drawPath(path, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawPath(path, strokePaint)

                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 0.9f
                canvas.drawLine(cx - r * 0.30f, cy - r * 0.06f, cx + r * 0.30f, cy - r * 0.06f, strokePaint)
                canvas.drawLine(cx + r * 0.12f, cy - r * 0.24f, cx + r * 0.30f, cy - r * 0.06f, strokePaint)
                canvas.drawLine(cx - r * 0.12f, cy + r * 0.12f, cx - r * 0.30f, cy - r * 0.06f, strokePaint)
            }

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) &&
                (keyCodeOrButton == NativeEngineBridge.KEY_T || keyCodeOrButton == NativeEngineBridge.KEY_SLASH) -> {
                // Tactical Speech Bubble with `>_` or `/` Command Prompt
                rect.set(cx - r * 0.74f, cy - r * 0.62f, cx + r * 0.74f, cy + r * 0.36f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawRoundRect(rect, r * 0.22f, r * 0.22f, strokePaint)

                canvas.drawLine(cx - r * 0.28f, cy + r * 0.36f, cx - r * 0.48f, cy + r * 0.72f, strokePaint)
                canvas.drawLine(cx - r * 0.48f, cy + r * 0.72f, cx - r * 0.04f, cy + r * 0.36f, strokePaint)

                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 0.95f
                if (keyCodeOrButton == NativeEngineBridge.KEY_SLASH) {
                    canvas.drawLine(cx - r * 0.20f, cy + r * 0.14f, cx + r * 0.20f, cy - r * 0.36f, strokePaint)
                } else {
                    canvas.drawLine(cx - r * 0.38f, cy - r * 0.30f, cx - r * 0.12f, cy - r * 0.12f, strokePaint)
                    canvas.drawLine(cx - r * 0.12f, cy - r * 0.12f, cx - r * 0.38f, cy + r * 0.06f, strokePaint)
                    canvas.drawLine(cx + r * 0.04f, cy + r * 0.08f, cx + r * 0.38f, cy + r * 0.08f, strokePaint)
                }
            }

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_TAB -> {
                // Scoreboard / Player Roster Grid Icon
                rect.set(cx - r * 0.68f, cy - r * 0.62f, cx + r * 0.68f, cy + r * 0.62f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, strokePaint)

                strokePaint.color = subCol
                for (row in -1..1) {
                    val ry = cy + row * r * 0.30f
                    canvas.drawCircle(cx - r * 0.36f, ry, sw * 0.65f, strokePaint)
                    canvas.drawLine(cx - r * 0.14f, ry, cx + r * 0.42f, ry, strokePaint)
                }
            }

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_F5 -> {
                // Camera Lens Aperture + Viewfinder
                rect.set(cx - r * 0.68f, cy - r * 0.42f, cx + r * 0.68f, cy + r * 0.56f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.20f, r * 0.20f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawRoundRect(rect, r * 0.20f, r * 0.20f, strokePaint)

                canvas.drawLine(cx - r * 0.30f, cy - r * 0.42f, cx - r * 0.18f, cy - r * 0.66f, strokePaint)
                canvas.drawLine(cx - r * 0.18f, cy - r * 0.66f, cx + r * 0.18f, cy - r * 0.66f, strokePaint)
                canvas.drawLine(cx + r * 0.18f, cy - r * 0.66f, cx + r * 0.30f, cy - r * 0.42f, strokePaint)

                strokePaint.color = subCol
                canvas.drawCircle(cx, cy + r * 0.08f, r * 0.28f, strokePaint)
                fillPaint.color = subCol
                canvas.drawCircle(cx, cy + r * 0.08f, r * 0.10f, fillPaint)
            }

            ((action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_F3) ||
                action == ActionType.MACRO_HITBOXES ||
                action == ActionType.MACRO_CHUNK_BORDERS ||
                action == ActionType.MACRO_RELOAD_CHUNKS -> {
                // Tactical Radar / Diagnostics Pulse Wave inside Octagon Frame
                rect.set(cx - r * 0.72f, cy - r * 0.62f, cx + r * 0.72f, cy + r * 0.62f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw * 0.95f
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, strokePaint)

                strokePaint.color = subCol
                strokePaint.strokeWidth = sw
                path.reset()
                path.moveTo(cx - r * 0.52f, cy + r * 0.06f)
                path.lineTo(cx - r * 0.24f, cy + r * 0.06f)
                path.lineTo(cx - r * 0.10f, cy - r * 0.38f)
                path.lineTo(cx + r * 0.12f, cy + r * 0.38f)
                path.lineTo(cx + r * 0.26f, cy - r * 0.08f)
                path.lineTo(cx + r * 0.52f, cy - r * 0.08f)
                canvas.drawPath(path, strokePaint)
            }

            (action == ActionType.KEY_HOLD || action == ActionType.KEY_TOGGLE) && keyCodeOrButton == NativeEngineBridge.KEY_ESCAPE -> {
                // Octagonal Tactical Gear + Twin Pause Pillars
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawCircle(cx, cy, r * 0.64f, strokePaint)
                for (i in 0 until 8) {
                    val ang = Math.toRadians((i * 45).toDouble())
                    val x0 = cx + cos(ang).toFloat() * (r * 0.58f)
                    val y0 = cy + sin(ang).toFloat() * (r * 0.58f)
                    val x1 = cx + cos(ang).toFloat() * (r * 0.80f)
                    val y1 = cy + sin(ang).toFloat() * (r * 0.80f)
                    canvas.drawLine(x0, y0, x1, y1, strokePaint)
                }
                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 1.1f
                canvas.drawLine(cx - r * 0.16f, cy - r * 0.24f, cx - r * 0.16f, cy + r * 0.24f, strokePaint)
                canvas.drawLine(cx + r * 0.16f, cy - r * 0.24f, cx + r * 0.16f, cy + r * 0.24f, strokePaint)
            }

            action == ActionType.CURSOR_TOGGLE -> {
                // Precision Arrow Pointer + Concentric Target Ring
                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 0.8f
                canvas.drawCircle(cx - r * 0.18f, cy - r * 0.18f, r * 0.45f, strokePaint)

                path.reset()
                path.moveTo(cx - r * 0.52f, cy - r * 0.56f)
                path.lineTo(cx + r * 0.58f, cy - r * 0.06f)
                path.lineTo(cx + r * 0.12f, cy + r * 0.12f)
                path.lineTo(cx + r * 0.38f, cy + r * 0.56f)
                path.lineTo(cx + r * 0.14f, cy + r * 0.68f)
                path.lineTo(cx - r * 0.10f, cy + r * 0.22f)
                path.lineTo(cx - r * 0.36f, cy + r * 0.52f)
                path.close()
                fillPaint.color = withAlpha(primaryColor, alphaScale * 0.28f)
                canvas.drawPath(path, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawPath(path, strokePaint)
            }

            action == ActionType.GYRO_TOGGLE -> {
                // Dual 3D Gyroscope Gimbal Orbits + Center Axis Core
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw * 0.95f
                canvas.drawCircle(cx, cy, r * 0.72f, strokePaint)

                strokePaint.color = subCol
                rect.set(cx - r * 0.72f, cy - r * 0.30f, cx + r * 0.72f, cy + r * 0.30f)
                canvas.drawOval(rect, strokePaint)
                rect.set(cx - r * 0.30f, cy - r * 0.72f, cx + r * 0.30f, cy + r * 0.72f)
                canvas.drawOval(rect, strokePaint)

                fillPaint.color = mainCol
                canvas.drawCircle(cx, cy, r * 0.16f, fillPaint)
            }

            action == ActionType.PVP_COMBO_MACRO -> {
                // Twin Crossed Blades + Center Lightning Spark
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw * 1.1f
                canvas.drawLine(cx - r * 0.64f, cy + r * 0.64f, cx + r * 0.68f, cy - r * 0.68f, strokePaint)
                canvas.drawLine(cx + r * 0.64f, cy + r * 0.64f, cx - r * 0.68f, cy - r * 0.68f, strokePaint)
                strokePaint.color = subCol
                canvas.drawLine(cx - r * 0.56f, cy + r * 0.20f, cx - r * 0.20f, cy + r * 0.56f, strokePaint)
                canvas.drawLine(cx + r * 0.56f, cy + r * 0.20f, cx + r * 0.20f, cy + r * 0.56f, strokePaint)
                fillPaint.color = subCol
                canvas.drawCircle(cx, cy, r * 0.20f, fillPaint)
            }

            action == ActionType.SIDEBAR_DRAWER_TOGGLE || action == ActionType.QUICK_SETTINGS -> {
                // Split Command Console Panel + 3 Sliders
                rect.set(cx - r * 0.74f, cy - r * 0.60f, cx + r * 0.74f, cy + r * 0.60f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, strokePaint)
                canvas.drawLine(cx - r * 0.22f, cy - r * 0.60f, cx - r * 0.22f, cy + r * 0.60f, strokePaint)
                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 0.9f
                canvas.drawLine(cx + r * 0.02f, cy - r * 0.26f, cx + r * 0.50f, cy - r * 0.26f, strokePaint)
                canvas.drawLine(cx + r * 0.02f, cy, cx + r * 0.50f, cy, strokePaint)
                canvas.drawLine(cx + r * 0.02f, cy + r * 0.26f, cx + r * 0.50f, cy + r * 0.26f, strokePaint)
            }

            action == ActionType.HUD_EDITOR_TOGGLE -> {
                // 4-Way Precision Layout Move Crosshair + Grid Frame
                rect.set(cx - r * 0.64f, cy - r * 0.64f, cx + r * 0.64f, cy + r * 0.64f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw * 0.9f
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, strokePaint)

                strokePaint.color = subCol
                strokePaint.strokeWidth = sw
                canvas.drawLine(cx - r * 0.42f, cy, cx + r * 0.42f, cy, strokePaint)
                canvas.drawLine(cx, cy - r * 0.42f, cx, cy + r * 0.42f, strokePaint)
                fillPaint.color = mainCol
                canvas.drawCircle(cx, cy, r * 0.14f, fillPaint)
            }

            action == ActionType.IME_KEYBOARD_TOGGLE -> {
                // Mechanical Keycap Deck (6 keys + Spacebar)
                rect.set(cx - r * 0.78f, cy - r * 0.56f, cx + r * 0.78f, cy + r * 0.56f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, strokePaint)

                fillPaint.color = subCol
                val kr = r * 0.11f
                for (ix in -1..1) {
                    canvas.drawCircle(cx + ix * r * 0.38f, cy - r * 0.18f, kr, fillPaint)
                }
                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 1.15f
                canvas.drawLine(cx - r * 0.42f, cy + r * 0.24f, cx + r * 0.42f, cy + r * 0.24f, strokePaint)
            }

            action == ActionType.TOGGLE_HUD_CLEAN -> {
                // Bionic Eye Lens + Iris Reticle
                path.reset()
                path.moveTo(cx - r * 0.78f, cy)
                path.quadTo(cx, cy - r * 0.68f, cx + r * 0.78f, cy)
                path.quadTo(cx, cy + r * 0.68f, cx - r * 0.78f, cy)
                path.close()
                fillPaint.color = softFillCol
                canvas.drawPath(path, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawPath(path, strokePaint)

                strokePaint.color = subCol
                canvas.drawCircle(cx, cy, r * 0.28f, strokePaint)
                fillPaint.color = mainCol
                canvas.drawCircle(cx, cy, r * 0.11f, fillPaint)
            }

            action == ActionType.MOUSE_SCROLL_UP || action == ActionType.MOUSE_SCROLL_DOWN || action == ActionType.MOUSE_MIDDLE_PICK -> {
                // Gaming Mouse Body + Highlighted Scroll Wheel + Direction Chevron
                rect.set(cx - r * 0.48f, cy - r * 0.64f, cx + r * 0.48f, cy + r * 0.64f)
                fillPaint.color = softFillCol
                canvas.drawRoundRect(rect, r * 0.44f, r * 0.44f, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawRoundRect(rect, r * 0.44f, r * 0.44f, strokePaint)

                strokePaint.color = subCol
                strokePaint.strokeWidth = sw * 1.25f
                canvas.drawLine(cx, cy - r * 0.36f, cx, cy - r * 0.08f, strokePaint)

                if (action == ActionType.MOUSE_SCROLL_UP) {
                    canvas.drawLine(cx - r * 0.22f, cy + r * 0.30f, cx, cy + r * 0.08f, strokePaint)
                    canvas.drawLine(cx + r * 0.22f, cy + r * 0.30f, cx, cy + r * 0.08f, strokePaint)
                } else if (action == ActionType.MOUSE_SCROLL_DOWN) {
                    canvas.drawLine(cx - r * 0.22f, cy + r * 0.12f, cx, cy + r * 0.36f, strokePaint)
                    canvas.drawLine(cx + r * 0.22f, cy + r * 0.12f, cx, cy + r * 0.36f, strokePaint)
                } else {
                    fillPaint.color = subCol
                    canvas.drawCircle(cx, cy - r * 0.22f, r * 0.15f, fillPaint)
                }
            }

            else -> {
                // Default Hexagon Tactical Emblem
                path.reset()
                for (i in 0 until 6) {
                    val ang = Math.toRadians((i * 60 - 30).toDouble())
                    val px = cx + cos(ang).toFloat() * (r * 0.68f)
                    val py = cy + sin(ang).toFloat() * (r * 0.68f)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                fillPaint.color = softFillCol
                canvas.drawPath(path, fillPaint)
                strokePaint.color = mainCol
                strokePaint.strokeWidth = sw
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    /**
     * Sculpted double-wing directional arrow for W / A / S / D movement buttons.
     */
    private fun drawDirectionalArrow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        angleDeg: Float,
        mainCol: Int,
        subCol: Int,
        softFillCol: Int,
        sw: Float
    ) {
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)

        path.reset()
        path.moveTo(cx, cy - r * 0.78f)
        path.lineTo(cx + r * 0.64f, cy + r * 0.14f)
        path.lineTo(cx + r * 0.24f, cy + r * 0.02f)
        path.lineTo(cx + r * 0.24f, cy + r * 0.66f)
        path.lineTo(cx - r * 0.24f, cy + r * 0.66f)
        path.lineTo(cx - r * 0.24f, cy + r * 0.02f)
        path.lineTo(cx - r * 0.64f, cy + r * 0.14f)
        path.close()

        fillPaint.color = softFillCol
        canvas.drawPath(path, fillPaint)

        strokePaint.color = mainCol
        strokePaint.strokeWidth = sw
        canvas.drawPath(path, strokePaint)

        strokePaint.color = subCol
        strokePaint.strokeWidth = sw * 0.85f
        subPath.reset()
        subPath.moveTo(cx - r * 0.30f, cy - r * 0.08f)
        subPath.lineTo(cx, cy - r * 0.44f)
        subPath.lineTo(cx + r * 0.30f, cy - r * 0.08f)
        canvas.drawPath(subPath, strokePaint)

        canvas.restore()
    }

    /**
     * Rich, multi-layered vector icon renderer for Launcher Sidebar Tabs, Modals,
     * Top Telemetry Bar buttons, and Boot Console headers.
     */
    fun drawUiIcon(
        canvas: Canvas,
        iconType: Int,
        cx: Float,
        cy: Float,
        r: Float,
        primaryColor: Int,
        accentColor: Int = primaryColor
    ) {
        if (r <= 2f) return
        val sw = (r * 0.16f).coerceAtLeast(1.6f)
        val softFill = withAlpha(accentColor, 0.22f)
        val subCol = withAlpha(accentColor, 0.90f)

        strokePaint.strokeWidth = sw
        strokePaint.color = primaryColor
        fillPaint.color = softFill

        when (iconType) {
            0 -> {
                // HOME: 3D Voxel Bastion / Portal with Roof & Glowing Core
                path.reset()
                path.moveTo(cx, cy - r * 0.85f)
                path.lineTo(cx + r * 0.86f, cy - r * 0.12f)
                path.lineTo(cx + r * 0.64f, cy - r * 0.12f)
                path.lineTo(cx + r * 0.64f, cy + r * 0.78f)
                path.lineTo(cx - r * 0.64f, cy + r * 0.78f)
                path.lineTo(cx - r * 0.64f, cy - r * 0.12f)
                path.lineTo(cx - r * 0.86f, cy - r * 0.12f)
                path.close()
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)

                rect.set(cx - r * 0.24f, cy + r * 0.08f, cx + r * 0.24f, cy + r * 0.78f)
                fillPaint.color = withAlpha(accentColor, 0.45f)
                canvas.drawRoundRect(rect, r * 0.12f, r * 0.12f, fillPaint)
                strokePaint.color = subCol
                canvas.drawRoundRect(rect, r * 0.12f, r * 0.12f, strokePaint)
            }

            1 -> {
                // MEMORY / ENGINE: 12-Pin Silicon Processor Die + 4-Core Matrix
                rect.set(cx - r * 0.58f, cy - r * 0.58f, cx + r * 0.58f, cy + r * 0.58f)
                canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, fillPaint)
                canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, strokePaint)

                rect.set(cx - r * 0.28f, cy - r * 0.28f, cx + r * 0.28f, cy + r * 0.28f)
                fillPaint.color = withAlpha(accentColor, 0.42f)
                canvas.drawRect(rect, fillPaint)
                strokePaint.color = subCol
                canvas.drawRect(rect, strokePaint)

                strokePaint.color = primaryColor
                for (i in -1..1) {
                    val o = i * r * 0.34f
                    canvas.drawLine(cx + o, cy - r * 0.90f, cx + o, cy - r * 0.58f, strokePaint)
                    canvas.drawLine(cx + o, cy + r * 0.58f, cx + o, cy + r * 0.90f, strokePaint)
                    canvas.drawLine(cx - r * 0.90f, cy + o, cx - r * 0.58f, cy + o, strokePaint)
                    canvas.drawLine(cx + r * 0.58f, cy + o, cx + r * 0.90f, cy + o, strokePaint)
                }
            }

            2 -> {
                // MODS / SHADERS: Isometric 3D Voxel Cube with Shaded Faces
                path.reset()
                path.moveTo(cx, cy - r * 0.88f)
                path.lineTo(cx + r * 0.82f, cy - r * 0.42f)
                path.lineTo(cx + r * 0.82f, cy + r * 0.44f)
                path.lineTo(cx, cy + r * 0.88f)
                path.lineTo(cx - r * 0.82f, cy + r * 0.44f)
                path.lineTo(cx - r * 0.82f, cy - r * 0.42f)
                path.close()
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)

                strokePaint.color = subCol
                canvas.drawLine(cx - r * 0.82f, cy - r * 0.42f, cx, cy + r * 0.04f, strokePaint)
                canvas.drawLine(cx + r * 0.82f, cy - r * 0.42f, cx, cy + r * 0.04f, strokePaint)
                canvas.drawLine(cx, cy + r * 0.04f, cx, cy + r * 0.88f, strokePaint)
            }

            3 -> {
                // CONTROLS / GAMEPAD: Pro Controller with D-Pad & Action Buttons
                rect.set(cx - r * 0.88f, cy - r * 0.52f, cx + r * 0.88f, cy + r * 0.56f)
                canvas.drawRoundRect(rect, r * 0.38f, r * 0.38f, fillPaint)
                canvas.drawRoundRect(rect, r * 0.38f, r * 0.38f, strokePaint)

                strokePaint.color = subCol
                canvas.drawLine(cx - r * 0.52f, cy, cx - r * 0.16f, cy, strokePaint)
                canvas.drawLine(cx - r * 0.34f, cy - r * 0.18f, cx - r * 0.34f, cy + r * 0.18f, strokePaint)

                fillPaint.color = subCol
                canvas.drawCircle(cx + r * 0.28f, cy + r * 0.12f, r * 0.11f, fillPaint)
                canvas.drawCircle(cx + r * 0.50f, cy - r * 0.12f, r * 0.11f, fillPaint)
            }

            4 -> {
                // ACCOUNT: Armored Player Avatar + Verification Shield
                canvas.drawCircle(cx, cy - r * 0.32f, r * 0.34f, fillPaint)
                canvas.drawCircle(cx, cy - r * 0.32f, r * 0.34f, strokePaint)

                path.reset()
                path.moveTo(cx - r * 0.78f, cy + r * 0.76f)
                path.quadTo(cx, cy + r * 0.04f, cx + r * 0.78f, cy + r * 0.76f)
                path.close()
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }

            5 -> {
                // LOGS / TERMINAL: Developer Console Window + `>_` Prompt
                rect.set(cx - r * 0.84f, cy - r * 0.64f, cx + r * 0.84f, cy + r * 0.64f)
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, fillPaint)
                canvas.drawRoundRect(rect, r * 0.18f, r * 0.18f, strokePaint)

                strokePaint.color = subCol
                canvas.drawLine(cx - r * 0.48f, cy - r * 0.24f, cx - r * 0.14f, cy + r * 0.04f, strokePaint)
                canvas.drawLine(cx - r * 0.14f, cy + r * 0.04f, cx - r * 0.48f, cy + r * 0.32f, strokePaint)
                canvas.drawLine(cx + r * 0.06f, cy + r * 0.30f, cx + r * 0.48f, cy + r * 0.30f, strokePaint)
            }

            6 -> {
                // ROCKET / BOOST: High-Voltage Lightning + Speed Ring
                canvas.drawCircle(cx, cy, r * 0.82f, strokePaint)
                path.reset()
                path.moveTo(cx + r * 0.10f, cy - r * 0.68f)
                path.lineTo(cx - r * 0.42f, cy + r * 0.06f)
                path.lineTo(cx - r * 0.04f, cy + r * 0.06f)
                path.lineTo(cx - r * 0.16f, cy + r * 0.68f)
                path.lineTo(cx + r * 0.42f, cy - r * 0.06f)
                path.lineTo(cx + r * 0.04f, cy - r * 0.06f)
                path.close()
                fillPaint.color = subCol
                canvas.drawPath(path, fillPaint)
            }

            7 -> {
                // JSON / SHARE: 3 Connected Nodes Graph
                strokePaint.color = subCol
                canvas.drawLine(cx - r * 0.46f, cy, cx + r * 0.42f, cy - r * 0.44f, strokePaint)
                canvas.drawLine(cx - r * 0.46f, cy, cx + r * 0.42f, cy + r * 0.44f, strokePaint)
                fillPaint.color = primaryColor
                canvas.drawCircle(cx - r * 0.46f, cy, r * 0.22f, fillPaint)
                canvas.drawCircle(cx + r * 0.42f, cy - r * 0.44f, r * 0.22f, fillPaint)
                canvas.drawCircle(cx + r * 0.42f, cy + r * 0.44f, r * 0.22f, fillPaint)
            }

            8 -> {
                // DOWNLOAD: Downward Arrow + Server Tray
                canvas.drawLine(cx, cy - r * 0.72f, cx, cy + r * 0.28f, strokePaint)
                canvas.drawLine(cx - r * 0.38f, cy - r * 0.08f, cx, cy + r * 0.32f, strokePaint)
                canvas.drawLine(cx + r * 0.38f, cy - r * 0.08f, cx, cy + r * 0.32f, strokePaint)
                strokePaint.color = subCol
                canvas.drawLine(cx - r * 0.64f, cy + r * 0.64f, cx + r * 0.64f, cy + r * 0.64f, strokePaint)
            }

            else -> {
                // SHADER / SUNBURST
                canvas.drawCircle(cx, cy, r * 0.42f, fillPaint)
                canvas.drawCircle(cx, cy, r * 0.42f, strokePaint)
                strokePaint.color = subCol
                for (i in 0 until 8) {
                    val ang = Math.toRadians((i * 45).toDouble())
                    canvas.drawLine(
                        cx + cos(ang).toFloat() * (r * 0.56f),
                        cy + sin(ang).toFloat() * (r * 0.56f),
                        cx + cos(ang).toFloat() * (r * 0.84f),
                        cy + sin(ang).toFloat() * (r * 0.84f),
                        strokePaint
                    )
                }
            }
        }
    }
}
