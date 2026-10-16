package io.velocraft.android.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.util.VeloLogger
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive 3D Voxel Sandbox & Engine Viewport rendered behind the Pro PvP HUD
 * while the external OpenJDK 21 (`libjli.so`) runtime is initializing or waiting to be mounted.
 *
 * Ensures the app NEVER displays a blank/black screen and allows immediate 120 FPS testing
 * of WASD movement, camera rotation, Gyroscope aiming, CPS attack hits, block placement, and logging.
 */
class VoxelWorldView(context: Context) : View(context), NativeEngineBridge.InteractiveInputListener {

    private var yawDeg: Float = 0f
    private var pitchDeg: Float = 0f
    private var posX: Float = 0f
    private var posZ: Float = 0f

    private var moveForward = false
    private var moveBackward = false
    private var moveLeft = false
    private var moveRight = false
    private var isJumping = false
    private var isSneaking = false
    private var isSprinting = false
    private var showDebugOverlay = false

    private var targetHitFlashTicks: Int = 0
    private var placedBlocksCount: Int = 0
    private var totalHitsCount: Int = 0
    var engineStatusLine: String = "Engine Aktif • Log: Android/media/io.velocraft.android/log/"

    private val skyPaint = Paint()
    private val groundPaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = 2f
    }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blockStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFF0F172A.toInt()
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E8F0.toInt()
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val debugBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3090D16.toInt()
    }
    private val rect = RectF()

    init {
        NativeEngineBridge.inputListener = this
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onKeyAction(keyCode: Int, action: Int) {
        val pressed = (action == NativeEngineBridge.GLFW_PRESS)
        when (keyCode) {
            NativeEngineBridge.KEY_W -> moveForward = pressed
            NativeEngineBridge.KEY_S -> moveBackward = pressed
            NativeEngineBridge.KEY_A -> moveLeft = pressed
            NativeEngineBridge.KEY_D -> moveRight = pressed
            NativeEngineBridge.KEY_SPACE -> isJumping = pressed
            NativeEngineBridge.KEY_LEFT_SHIFT -> isSneaking = pressed
            NativeEngineBridge.KEY_LEFT_CONTROL -> isSprinting = pressed
            NativeEngineBridge.KEY_F3 -> if (pressed) showDebugOverlay = !showDebugOverlay
        }
        postInvalidateOnAnimation()
    }

    override fun onMouseAction(button: Int, action: Int) {
        if (action == NativeEngineBridge.GLFW_PRESS) {
            if (button == NativeEngineBridge.MOUSE_LEFT) {
                targetHitFlashTicks = 8
                totalHitsCount++
            } else if (button == NativeEngineBridge.MOUSE_RIGHT) {
                placedBlocksCount++
            }
            postInvalidateOnAnimation()
        }
    }

    override fun onCameraDelta(deltaX: Float, deltaY: Float) {
        yawDeg = (yawDeg + deltaX * 0.35f) % 360f
        pitchDeg = (pitchDeg + deltaY * 0.30f).coerceIn(-65f, 65f)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Update player movement
        val speed = if (isSprinting) 0.22f else if (isSneaking) 0.06f else 0.12f
        val rad = Math.toRadians(yawDeg.toDouble())
        if (moveForward) {
            posX += (sin(rad) * speed).toFloat()
            posZ += (cos(rad) * speed).toFloat()
        }
        if (moveBackward) {
            posX -= (sin(rad) * speed).toFloat()
            posZ -= (cos(rad) * speed).toFloat()
        }
        if (moveLeft) {
            posX -= (cos(rad) * speed).toFloat()
            posZ += (sin(rad) * speed).toFloat()
        }
        if (moveRight) {
            posX += (cos(rad) * speed).toFloat()
            posZ -= (sin(rad) * speed).toFloat()
        }

        val jumpOffset = if (isJumping) dp(18f) else if (isSneaking) -dp(10f) else 0f
        val horizonY = (h * 0.5f - pitchDeg * dp(3.2f) + jumpOffset).coerceIn(0f, h)

        // 1. Sky Gradient
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, horizonY.coerceAtLeast(1f),
            0xFF0284C7.toInt(), 0xFFBAE6FD.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, horizonY, skyPaint)

        // 2. Voxel Grass Terrain Gradient
        groundPaint.shader = LinearGradient(
            0f, horizonY, 0f, h,
            0xFF4D7C0F.toInt(), 0xFF1A2E05.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, horizonY, w, h, groundPaint)

        // 3. Perspective Voxel Grid Lines
        val gridShift = (((yawDeg * 4f + posX * 20f) % 48f) + 48f) % 48f
        var gx = -w * 0.5f
        while (gx < w * 1.5f) {
            val topX = w * 0.5f + (gx - w * 0.5f) * 0.18f - gridShift
            val botX = gx - gridShift * 2.5f
            canvas.drawLine(topX, horizonY, botX, h, gridPaint)
            gx += 48f
        }

        // 4. Interactive PvP Target Dummy & Placed Voxel Blocks in World Space
        var relAngle = (-yawDeg) % 360f
        if (relAngle > 180f) relAngle -= 360f
        if (relAngle < -180f) relAngle += 360f

        val targetScreenX = w * 0.5f + relAngle * dp(7.5f)
        val blockW = dp(86f)
        val blockH = dp(86f)
        val blockTop = horizonY - blockH * 0.65f

        // Draw placed bridging blocks next to target
        val shownBlocks = placedBlocksCount.coerceAtMost(8)
        for (i in 1..shownBlocks) {
            val bx = targetScreenX + i * (blockW * 0.78f)
            rect.set(bx - blockW * 0.35f, horizonY - blockH * 0.35f, bx + blockW * 0.35f, horizonY + blockH * 0.25f)
            blockPaint.color = 0xFFD97706.toInt() // Oak Planks / Sandstone bridge block
            canvas.drawRoundRect(rect, dp(4f), dp(4f), blockPaint)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), blockStrokePaint)
        }

        // Draw Main PvP Target Cube
        rect.set(targetScreenX - blockW * 0.5f, blockTop, targetScreenX + blockW * 0.5f, blockTop + blockH)
        blockPaint.color = if (targetHitFlashTicks > 0) 0xFFEF4444.toInt() else 0xFF10B981.toInt()
        if (targetHitFlashTicks > 0) targetHitFlashTicks--
        canvas.drawRoundRect(rect, dp(6f), dp(6f), blockPaint)
        canvas.drawRoundRect(rect, dp(6f), dp(6f), blockStrokePaint)

        infoPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "PVP TARGET (Hits: $totalHitsCount | Blocks: $placedBlocksCount)",
            targetScreenX,
            blockTop - dp(8f),
            infoPaint
        )

        // 5. F3 Debug & Log Path Info Box (top-left)
        if (showDebugOverlay) {
            val boxW = dp(330f)
            val boxH = dp(82f)
            rect.set(dp(12f), dp(42f), dp(12f) + boxW, dp(42f) + boxH)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), debugBgPaint)
            infoPaint.textAlign = Paint.Align.LEFT
            val logPath = VeloLogger.logDir?.absolutePath ?: "/sdcard/Android/media/io.velocraft.android/log"
            canvas.drawText("Minecraft 1.20.4 (Java Porting Edition / Fabric)", dp(20f), dp(60f), infoPaint)
            canvas.drawText(
                "XYZ: ${"%.1f".format(posX)} / 64.0 / ${"%.1f".format(posZ)}  (Yaw: ${yawDeg.toInt()}° Pitch: ${pitchDeg.toInt()}°)",
                dp(20f),
                dp(78f),
                infoPaint
            )
            canvas.drawText("Log Dir: $logPath", dp(20f), dp(96f), infoPaint)
            canvas.drawText(engineStatusLine, dp(20f), dp(114f), infoPaint)
        }

        if (moveForward || moveBackward || moveLeft || moveRight || targetHitFlashTicks > 0) {
            postInvalidateOnAnimation()
        }
    }
}
