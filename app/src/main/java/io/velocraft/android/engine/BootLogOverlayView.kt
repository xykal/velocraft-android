package io.velocraft.android.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.util.VeloLogger
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Two-Layer Boot & Waiting Overlay:
 *
 * 1. **LAYAR TUNGGU MOJANG SPLASH (Default on Black Screen while `!mojangSplashReached` / `swapBuffersCount == 0`)**:
 *    - Replaces the pitch-black OpenGL surface while waiting for the Mojang Studios Splash screen.
 *    - Features a full-screen 3D Isometric Voxel Loading Animation, rotating Hadits Shahih & Kata-Kata
 *      Hikmah Pilihan, real-time 10-stage progress bar + ETA countdown to Mojang Splash, and a
 *      `[BUKA LOG TEKNIS]` button.
 *    - Automatically disappears the instant the Mojang Splash screen renders (`swapBuffersCount > 0`)!
 *
 * 2. **JENDELA LOG TEKNIS (`isLogWindowOpen == true`)**:
 *    - Dedicated technical terminal log window (no clutter), toggleable anytime via `[BUKA LOG TEKNIS]`,
 *      `[SEMBUNYIKAN LOG]`, or the `[LOG]` button in the Top Telemetry Bar.
 *    - When hidden (`isLogWindowOpen = false`) before Mojang Splash appears, returns to the Animated
 *      Waiting Screen instead of a black screen!
 *
 * Strictly zero stock Android widgets, zero neon colors, and zero emojis.
 */
class BootLogOverlayView(
    context: Context,
    private val onReturnToLauncher: () -> Unit
) : View(context) {

    data class WisdomEntry(
        val categoryBadge: String,
        val quoteText: String,
        val sourceRef: String
    )

    companion object {
        private val HADITS_AND_HIKMAH_LIST = listOf(
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • NIAT & AMAL",
                quoteText = "\"Sesungguhnya setiap amal perbuatan itu bergantung pada niatnya, dan sesungguhnya setiap orang akan mendapatkan sesuai dengan apa yang ia niatkan.\"",
                sourceRef = "HR. Bukhari No. 1 & Muslim No. 1907"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • KEMUDAHAN & KESABARAN",
                quoteText = "\"Barangsiapa yang memudahkan urusan orang yang sedang kesulitan, niscaya Allah akan memudahkan urusannya di dunia dan di akhirat.\"",
                sourceRef = "HR. Muslim No. 2699"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • KEBAIKAN & MANFAAT",
                quoteText = "\"Sebaik-baik manusia adalah yang paling bermanfaat bagi manusia lainnya.\"",
                sourceRef = "HR. Ahmad & Thabrani (Hasan Shahih)"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • KETEKUNAN (ITQAN)",
                quoteText = "\"Sesungguhnya Allah mencintai apabila salah seorang di antara kalian mengerjakan suatu pekerjaan, ia mengerjakannya dengan tekun, rapi, dan sungguh-sungguh (itqan).\"",
                sourceRef = "HR. Baihaqi dalam Syu'abul Iman"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • MENUNTUT ILMU",
                quoteText = "\"Barangsiapa menempuh suatu jalan untuk mencari ilmu, maka Allah akan memudahkan baginya jalan menuju surga.\"",
                sourceRef = "HR. Muslim No. 2699"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • AKHLAK & SENYUM",
                quoteText = "\"Senyummu di hadapan saudaramu adalah sedekah, dan berkata yang baik adalah sedekah.\"",
                sourceRef = "HR. Tirmidzi No. 1956 & Bukhari"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • KESEHATAN & WAKTU LUANG",
                quoteText = "\"Ada dua nikmat yang banyak manusia tertipu (lalai) di dalamnya: nikmat kesehatan dan nikmat waktu luang.\"",
                sourceRef = "HR. Bukhari No. 6412"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • KESABARAN",
                quoteText = "\"Tidaklah seseorang diberikan suatu pemberian yang lebih baik dan lebih luas daripada kesabaran.\"",
                sourceRef = "HR. Bukhari No. 1469 & Muslim No. 1053"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • SYUKUR & KEBAIKAN",
                quoteText = "\"Barangsiapa yang tidak bersyukur kepada manusia (atas kebaikannya), maka ia belum bersyukur kepada Allah.\"",
                sourceRef = "HR. Abu Dawud No. 4811 & Tirmidzi No. 1954"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • OPTIMISME & KEMUDAHAN",
                quoteText = "\"Permudahlah dan jangan mempersulit, berilah kabar gembira dan jangan membuat orang lari menjauh.\"",
                sourceRef = "HR. Bukhari No. 69 & Muslim No. 1734"
            ),
            WisdomEntry(
                categoryBadge = "HADITS SHAHIH • DOA KEBERKAHAN ILMU",
                quoteText = "\"Ya Allah, aku memohon kepada-Mu ilmu yang bermanfaat, rezeki yang thayyib (baik), dan amal yang diterima.\"",
                sourceRef = "HR. Ibnu Majah No. 925 (Shahih)"
            ),
            WisdomEntry(
                categoryBadge = "KATA HIKMAH • KESUNGGUHAN",
                quoteText = "\"Barangsiapa bersungguh-sungguh (man jadda wajada), maka ia akan sampai pada tujuannya; dan barangsiapa bersabar, ia akan meraih keberhasilan.\"",
                sourceRef = "Pepatah Hikmah Klasik"
            )
        )
    }

    /**
     * `isLogWindowOpen == false`: Shows the Full-Screen Animated Voxel + Hadits Waiting Screen
     * while `!mojangSplashReached` (`swapBuffersCount <= 0`), and automatically hides once
     * Mojang Splash appears (`mojangSplashReached == true`).
     * `isLogWindowOpen == true`: Shows the Technical Terminal Boot Log window.
     */
    var isLogWindowOpen: Boolean = false
        private set

    var isJvmExited: Boolean = false
        private set
    var jvmExitCode: Int = 0
        private set

    // Live Telemetry State
    private var currentStageIndex: Int = 1
    private var totalStages: Int = 10
    private var bootPercent: Int = 8
    private var displayedProgress: Float = 8f
    private var loadedClasses: Int = 0
    private var targetSplashClasses: Int = 5400
    private var classesPerSec: Int = 0
    private var jvmUsedRamMb: Int = 0
    private var jvmMaxRamMb: Int = 768
    private var elapsedSec: Int = 0
    private var etaMojangSplashSec: Int = 25
    private var etaMinecraftMenuSec: Int = 48
    private var mojangSplashReached: Boolean = false
    private var titleScreenReached: Boolean = false
    private var swapBuffersCount: Int = 0
    private var currentStageTitle: String =
        "TAHAP 1/10: Menyiapkan Lingkungan Engine & Direktori..."
    private var currentSubTaskDetail: String =
        "Memverifikasi konfigurasi OpenJDK 17, Mod Fabric & Renderer..."
    private var currentActiveClass: String =
        "io.velocraft.android.engine.DirectBootProvisioner"

    // Animation & Rotating Hadits State
    private var animPhaseRad: Float = 0f
    private var currentWisdomIndex: Int = (System.currentTimeMillis() / 1000L).toInt().let {
        ((it % HADITS_AND_HIKMAH_LIST.size) + HADITS_AND_HIKMAH_LIST.size) % HADITS_AND_HIKMAH_LIST.size
    }
    private var lastWisdomSwitchMs: Long = System.currentTimeMillis()

    private var copyFeedbackMessage: String? = null
    private var copyFeedbackUntilMs: Long = 0L

    private val worldBannerBitmap: Bitmap? = loadAssetBitmap("branding/xyverse_world_banner.jpg")
    private val mascotBitmap: Bitmap? = loadAssetBitmap("branding/xyverse_mascot.png")
    private val bitmapSrcRect = Rect()

    // Hit Rectangles for Waiting Screen (on Black Screen)
    private val waitWisdomCardRect = RectF()
    private val waitPrevWisdomRect = RectF()
    private val waitNextWisdomRect = RectF()
    private val waitOpenLogBtnRect = RectF()
    private val waitBoostBtnRect = RectF()

    // Hit Rectangles for Technical Log Modal (`isLogWindowOpen == true`)
    private val panelRect = RectF()
    private val progressTrackRect = RectF()
    private val progressFillRect = RectF()
    private val cardSplashRect = RectF()
    private val cardMenuRect = RectF()
    private val cardMetricsRect = RectF()
    private val subTaskStripRect = RectF()
    private val terminalRect = RectF()
    private val btnMinimizeRect = RectF()
    private val btnBoostRamRect = RectF()
    private val btnCopyLogRect = RectF()
    private val btnReturnMenuRect = RectF()

    private val isoPath = Path()

    // Studio Non-Neon Matte Paints
    private val waitingBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF121110")
        style = Paint.Style.FILL
    }
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8111110")
        style = Paint.Style.FILL
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2191816")
        style = Paint.Style.FILL
    }
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF22211E")
        style = Paint.Style.FILL
    }
    private val cardActiveBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1D2B20")
        style = Paint.Style.FILL
    }
    private val wisdomCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F01F1E1B")
        style = Paint.Style.FILL
    }
    private val terminalBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF121110")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3A3731")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4E8C57")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val terraStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC87D55")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2B2925")
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4E8C57")
        style = Paint.Style.FILL
    }
    private val btnPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF385E3D")
        style = Paint.Style.FILL
    }
    private val btnSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2D2B27")
        style = Paint.Style.FILL
    }
    private val btnTerraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5C3E2E")
        style = Paint.Style.FILL
    }
    private val btnWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7A3E36")
        style = Paint.Style.FILL
    }
    private val bannerAlphaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 46
    }
    private val mascotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // 3D Isometric Voxel Face Paints
    private val voxelTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5FA869")
        style = Paint.Style.FILL
    }
    private val voxelLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8B6347")
        style = Paint.Style.FILL
    }
    private val voxelRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF634631")
        style = Paint.Style.FILL
    }
    private val voxelWirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE6B86A")
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F1EA")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F1EA")
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C2BDB2")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DEDAD0")
        typeface = Typeface.MONOSPACE
    }
    private val btnTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F1EA")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            pullTelemetrySnapshot()
            animPhaseRad = (animPhaseRad + 0.085f) % (Math.PI.toFloat() * 200f)

            val now = System.currentTimeMillis()
            if (now - lastWisdomSwitchMs >= 8000L) {
                currentWisdomIndex = (currentWisdomIndex + 1) % HADITS_AND_HIKMAH_LIST.size
                lastWisdomSwitchMs = now
            }

            // Smoothly interpolate progress bar
            val targetP = bootPercent.toFloat().coerceIn(5f, 100f)
            displayedProgress += (targetP - displayedProgress) * 0.22f

            // Auto-hide the Waiting Screen as soon as Mojang Splash renders (`swapBuffersCount > 0`),
            // unless the user explicitly opened the Technical Log window (`isLogWindowOpen == true`)
            // or the JVM exited with an error.
            if (mojangSplashReached && !isLogWindowOpen && !isJvmExited) {
                if (visibility != GONE) {
                    visibility = GONE
                }
                removeCallbacks(this)
                return
            }

            if (visibility == VISIBLE) {
                invalidate()
                postDelayed(this, 45L)
            }
        }
    }

    fun startBootMonitoring() {
        isLogWindowOpen = false
        isJvmExited = false
        jvmExitCode = 0
        visibility = VISIBLE
        lastWisdomSwitchMs = System.currentTimeMillis()
        pullTelemetrySnapshot()
        removeCallbacks(refreshTick)
        post(refreshTick)
    }

    /**
     * Reopens the Technical Boot Log window (`isLogWindowOpen = true`).
     */
    fun reopenLogOverlay(preferVisualHaditsMode: Boolean = false) {
        isLogWindowOpen = !preferVisualHaditsMode
        visibility = VISIBLE
        pullTelemetrySnapshot()
        removeCallbacks(refreshTick)
        post(refreshTick)
        invalidate()
    }

    /**
     * Toggles the Technical Boot Log window when the user taps `[LOG]` on the Top Telemetry Bar.
     */
    fun toggleLogOverlay() {
        if (visibility == VISIBLE && isLogWindowOpen) {
            isLogWindowOpen = false
            if (mojangSplashReached && !isJvmExited) {
                visibility = GONE
                removeCallbacks(refreshTick)
            }
            invalidate()
        } else {
            isLogWindowOpen = true
            visibility = VISIBLE
            pullTelemetrySnapshot()
            removeCallbacks(refreshTick)
            post(refreshTick)
            invalidate()
        }
    }

    fun stopBootMonitoring() {
        removeCallbacks(refreshTick)
    }

    fun updateBootStage(title: String, progress: Int, summary: String? = null) {
        post {
            currentStageTitle = sanitizeLine(title)
            bootPercent = max(bootPercent, progress.coerceIn(0, 100))
            if (!summary.isNullOrBlank()) {
                currentSubTaskDetail = sanitizeLine(summary)
            }
            if (visibility == VISIBLE) {
                invalidate()
            }
        }
    }

    fun notifyJvmFinished(exitCode: Int) {
        post {
            notifyJvmExited(exitCode)
        }
    }

    fun notifyJvmExited(exitCode: Int) {
        isJvmExited = true
        jvmExitCode = exitCode
        isLogWindowOpen = true
        visibility = VISIBLE
        pullTelemetrySnapshot()
        removeCallbacks(refreshTick)
        invalidate()
    }

    private fun pullTelemetrySnapshot() {
        val snap = NativeEngineBridge.getBootTelemetry() ?: return
        currentStageIndex = snap.stageIndex
        totalStages = snap.totalStages
        bootPercent = snap.percent
        loadedClasses = snap.loadedClasses
        targetSplashClasses = snap.targetSplashClasses
        classesPerSec = snap.classesPerSec
        jvmUsedRamMb = snap.jvmUsedRamMb
        jvmMaxRamMb = snap.jvmMaxRamMb
        elapsedSec = snap.elapsedSec
        etaMojangSplashSec = snap.etaMojangSplashSec
        etaMinecraftMenuSec = snap.etaMinecraftMenuSec
        mojangSplashReached = snap.mojangSplashReached || snap.swapBuffersCount > 0
        titleScreenReached = snap.titleScreenReached
        swapBuffersCount = snap.swapBuffersCount
        currentStageTitle = sanitizeLine(snap.stageTitle)
        currentSubTaskDetail = sanitizeLine(snap.subTaskDetail)
        currentActiveClass = sanitizeLine(snap.activeClassName)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val u = min(w, h)
        // Always leave the top 38dp telemetry bar completely uncovered!
        val topBarSafeOffset = (38f * resources.displayMetrics.density).coerceAtMost(h * 0.12f)

        // CASE 1: Technical Log Window is closed (`!isLogWindowOpen`) and we are waiting for Mojang Splash
        // (`!mojangSplashReached`). Draw the Full-Screen Animated Voxel + Hadits Waiting Screen directly
        // over the black screen!
        if (!isLogWindowOpen && !isJvmExited) {
            if (!mojangSplashReached) {
                drawWaitingForMojangSplashScreen(canvas, w, h, u, topBarSafeOffset)
            }
            return
        }

        // CASE 2: Technical Log Window is open (`isLogWindowOpen == true` or `isJvmExited == true`)
        drawTechnicalLogWindowModal(canvas, w, h, u, topBarSafeOffset)
    }

    /**
     * Draws the Full-Screen Animated 3D Voxel + Hadits / Kata-Kata Waiting Screen directly on the
     * black screen while waiting for the Mojang Studios Splash screen (`swapBuffersCount == 0`).
     */
    private fun drawWaitingForMojangSplashScreen(
        canvas: Canvas,
        w: Float,
        h: Float,
        u: Float,
        topSafeY: Float
    ) {
        // 1. Opaque Warm-Charcoal Background covering the black GL surface (below Top Telemetry Bar)
        canvas.drawRect(0f, topSafeY, w, h, waitingBgPaint)

        worldBannerBitmap?.let { bmp ->
            bitmapSrcRect.set(0, 0, bmp.width, bmp.height)
            val dst = RectF(0f, topSafeY, w, h)
            canvas.drawBitmap(bmp, bitmapSrcRect, dst, bannerAlphaPaint)
        }

        val contentTop = topSafeY + u * 0.025f
        val contentBottom = h - u * 0.025f
        val availH = contentBottom - contentTop
        val centerW = (w * 0.88f).coerceAtMost(u * 1.68f)
        val left = (w - centerW) * 0.5f
        val right = left + centerW

        // 2. Top Area: 3D Isometric Voxel Cluster Animation + Orbiting Geometric Rings + 4 Flanking Telemetry Pods
        val animCenterY = contentTop + availH * 0.22f
        val ringRad = availH * 0.145f

        // Radial ambient studio glow behind the 3D Voxel cluster
        fillPaint.shader = android.graphics.RadialGradient(
            w * 0.5f, animCenterY, ringRad * 2.2f,
            intArrayOf(Color.parseColor("#384E9C5C"), Color.parseColor("#18C88240"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.5f, animCenterY, ringRad * 2.2f, fillPaint)
        fillPaint.shader = null

        // Left & Right Flanking Live Telemetry Micro-Pods
        val podW = ((w * 0.5f - ringRad * 1.35f) - left).coerceIn(u * 0.22f, u * 0.42f)
        val podH = ringRad * 0.82f
        if (podW > u * 0.20f) {
            val lPod1 = RectF(left, animCenterY - ringRad, left + podW, animCenterY - ringRad + podH)
            val lPod2 = RectF(left, animCenterY + ringRad - podH, left + podW, animCenterY + ringRad)
            val rPod1 = RectF(right - podW, animCenterY - ringRad, right, animCenterY - ringRad + podH)
            val rPod2 = RectF(right - podW, animCenterY + ringRad - podH, right, animCenterY + ringRad)

            for ((pRect, info) in listOf(
                lPod1 to Triple("KELAS JVM DIMUAT", "$loadedClasses / $targetSplashClasses", true),
                lPod2 to Triple("KECEPATAN LOADER", "${if (classesPerSec > 0) classesPerSec else 180} kls/dtk", false),
                rPod1 to Triple("MEMORI HEAP JVM", "${if (jvmUsedRamMb > 0) jvmUsedRamMb else 256} / $jvmMaxRamMb MB", false),
                rPod2 to Triple("TAHAP AKTIF (${currentStageIndex}/10)", "Waktu: ${elapsedSec} dtk", true)
            )) {
                canvas.drawRoundRect(pRect, 10f, 10f, cardBgPaint)
                canvas.drawRoundRect(pRect, 10f, 10f, if (info.third) accentStrokePaint else strokePaint)
                subPaint.textSize = u * 0.0145f
                subPaint.color = Color.parseColor("#9AA39C")
                drawFittedText(canvas, info.first, pRect.left + u * 0.014f, pRect.top + pRect.height() * 0.40f, pRect.width() - u * 0.028f, subPaint)
                titlePaint.textSize = u * 0.0195f
                titlePaint.color = if (info.third) Color.parseColor("#8CD996") else Color.parseColor("#E6B86A")
                drawFittedText(canvas, info.second, pRect.left + u * 0.014f, pRect.top + pRect.height() * 0.78f, pRect.width() - u * 0.028f, titlePaint)
            }
        }

        voxelWirePaint.color = Color.parseColor("#54A862")
        voxelWirePaint.strokeWidth = 2.6f
        val ring1 = RectF(w * 0.5f - ringRad, animCenterY - ringRad, w * 0.5f + ringRad, animCenterY + ringRad)
        val deg1 = (animPhaseRad * 40f) % 360f
        canvas.drawArc(ring1, deg1, 115f, false, voxelWirePaint)
        canvas.drawArc(ring1, deg1 + 180f, 115f, false, voxelWirePaint)

        voxelWirePaint.color = Color.parseColor("#D88A44")
        val r2 = ringRad * 0.76f
        val ring2 = RectF(w * 0.5f - r2, animCenterY - r2, w * 0.5f + r2, animCenterY + r2)
        val deg2 = 360f - ((animPhaseRad * 55f) % 360f)
        canvas.drawArc(ring2, deg2, 85f, false, voxelWirePaint)
        canvas.drawArc(ring2, deg2 + 180f, 85f, false, voxelWirePaint)

        val cubeSize = ringRad * 0.34f
        val bobCenter = sin(animPhaseRad * 1.9f) * (u * 0.009f)
        val offsets = listOf(
            Triple(-1f, 0f, 0.6f),
            Triple(0f, -1f, 1.2f),
            Triple(0f, 1f, 1.8f),
            Triple(1f, 0f, 2.4f),
            Triple(0f, 0f, 0f)
        )
        for ((ox, oy, phaseOff) in offsets) {
            val wave = sin(animPhaseRad * 2.2f + phaseOff) * (u * 0.008f)
            val isoX = w * 0.5f + (ox - oy) * cubeSize * 0.86f
            val isoY = animCenterY + (ox + oy) * cubeSize * 0.50f + bobCenter + wave
            drawIsometricVoxelCube(canvas, isoX, isoY, cubeSize * 0.80f)
        }

        // 3. Status Title, Smooth Progress Bar & 10-Stage Segmented Stepper
        var curY = animCenterY + ringRad + u * 0.028f
        btnTextPaint.textSize = u * 0.024f
        btnTextPaint.color = Color.parseColor("#F4F1EA")
        canvas.drawText(
            "MENUNGGU LAYAR SPLASH MOJANG STUDIOS... (${bootPercent}%)",
            w * 0.5f,
            curY,
            btnTextPaint
        )

        curY += u * 0.026f
        btnTextPaint.textSize = u * 0.018f
        btnTextPaint.color = Color.parseColor("#8CD996")
        val etaText = "Estimasi Splash Mojang: ~${etaMojangSplashSec} dtk lagi  •  $currentStageTitle"
        canvas.drawText(
            TextUtils.ellipsize(etaText, btnTextPaint, centerW, TextUtils.TruncateAt.END).toString(),
            w * 0.5f,
            curY,
            btnTextPaint
        )

        curY += u * 0.014f
        val barH = u * 0.022f
        val barW = centerW * 0.82f
        val barLeft = (w - barW) * 0.5f
        progressTrackRect.set(barLeft, curY, barLeft + barW, curY + barH)
        canvas.drawRoundRect(progressTrackRect, barH * 0.5f, barH * 0.5f, trackPaint)
        canvas.drawRoundRect(progressTrackRect, barH * 0.5f, barH * 0.5f, strokePaint)

        val fillW = (barW * (displayedProgress / 100f)).coerceIn(barH, barW)
        progressFillRect.set(barLeft, curY, barLeft + fillW, curY + barH)
        fillPaint.shader = LinearGradient(
            barLeft, curY, barLeft + fillW, curY,
            Color.parseColor("#3E7346"),
            Color.parseColor("#68C876"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(progressFillRect, barH * 0.5f, barH * 0.5f, fillPaint)
        fillPaint.shader = null

        // 10-Stage Segmented Stepper Pills
        curY += barH + u * 0.010f
        val stepGap = u * 0.008f
        val stepW = (barW - stepGap * 9f) / 10f
        val stepH = u * 0.012f
        for (s in 1..10) {
            val sx = barLeft + (s - 1) * (stepW + stepGap)
            val sRect = RectF(sx, curY, sx + stepW, curY + stepH)
            val sPaint = when {
                s < currentStageIndex -> btnPrimaryPaint
                s == currentStageIndex -> btnTerraPaint
                else -> trackPaint
            }
            canvas.drawRoundRect(sRect, stepH * 0.5f, stepH * 0.5f, sPaint)
        }

        curY += stepH + u * 0.018f

        // 4. Bottom Action Pill Row Reservation
        val btnH = u * 0.064f
        val btnBottom = contentBottom
        val btnTop = btnBottom - btnH

        // 5. Center-Bottom Hadits & Kata-Kata Hikmah Card directly on the Waiting Screen!
        val cardBottom = btnTop - u * 0.020f
        waitWisdomCardRect.set(left, curY, right, cardBottom)
        canvas.drawRoundRect(waitWisdomCardRect, 14f, 14f, wisdomCardBgPaint)
        canvas.drawRoundRect(waitWisdomCardRect, 14f, 14f, terraStrokePaint)

        val wLeft = waitWisdomCardRect.left + u * 0.026f
        val wRight = waitWisdomCardRect.right - u * 0.026f
        val wMaxW = wRight - wLeft
        val wisdom = HADITS_AND_HIKMAH_LIST[currentWisdomIndex % HADITS_AND_HIKMAH_LIST.size]

        val navBtnW = u * 0.17f
        val navBtnH = u * 0.044f
        val navTop = waitWisdomCardRect.top + u * 0.016f
        waitNextWisdomRect.set(wRight - navBtnW, navTop, wRight, navTop + navBtnH)
        waitPrevWisdomRect.set(
            waitNextWisdomRect.left - u * 0.012f - navBtnW,
            navTop,
            waitNextWisdomRect.left - u * 0.012f,
            navTop + navBtnH
        )

        canvas.drawRoundRect(waitPrevWisdomRect, 7f, 7f, btnSecondaryPaint)
        canvas.drawRoundRect(waitPrevWisdomRect, 7f, 7f, strokePaint)
        canvas.drawRoundRect(waitNextWisdomRect, 7f, 7f, btnPrimaryPaint)
        canvas.drawRoundRect(waitNextWisdomRect, 7f, 7f, accentStrokePaint)

        btnTextPaint.textSize = u * 0.0165f
        btnTextPaint.color = Color.parseColor("#F4F1EA")
        canvas.drawText("< SEBELUMNYA", waitPrevWisdomRect.centerX(), waitPrevWisdomRect.centerY() + u * 0.006f, btnTextPaint)
        canvas.drawText("GANTI HADITS >", waitNextWisdomRect.centerX(), waitNextWisdomRect.centerY() + u * 0.006f, btnTextPaint)

        titlePaint.textSize = u * 0.020f
        titlePaint.color = Color.parseColor("#E6B86A")
        drawFittedText(
            canvas,
            "${wisdom.categoryBadge} (${currentWisdomIndex + 1}/${HADITS_AND_HIKMAH_LIST.size})",
            wLeft,
            navTop + u * 0.029f,
            waitPrevWisdomRect.left - wLeft - u * 0.014f,
            titlePaint
        )

        quotePaint.textSize = u * 0.0245f
        quotePaint.color = Color.parseColor("#F4F1EA")
        val wrappedLines = wrapTextToLines(wisdom.quoteText, quotePaint, wMaxW, maxLines = 3)
        var qY = navTop + navBtnH + u * 0.036f
        for (line in wrappedLines) {
            canvas.drawText(line, wLeft, qY, quotePaint)
            qY += u * 0.031f
        }

        titlePaint.textSize = u * 0.0195f
        titlePaint.color = Color.parseColor("#8CD996")
        drawFittedText(canvas, "— ${wisdom.sourceRef}", wLeft, qY + u * 0.006f, wMaxW, titlePaint)

        // 6. Bottom Pill Buttons on Waiting Screen: [BUKA LOG TEKNIS] & [TURBO BOOST RAM]
        val pillW = (centerW * 0.44f).coerceAtMost(u * 0.56f)
        val gap = u * 0.022f
        val totalPillsW = pillW * 2f + gap
        val pStart = (w - totalPillsW) * 0.5f

        waitOpenLogBtnRect.set(pStart, btnTop, pStart + pillW, btnBottom)
        waitBoostBtnRect.set(waitOpenLogBtnRect.right + gap, btnTop, waitOpenLogBtnRect.right + gap + pillW, btnBottom)

        canvas.drawRoundRect(waitOpenLogBtnRect, 10f, 10f, btnSecondaryPaint)
        canvas.drawRoundRect(waitOpenLogBtnRect, 10f, 10f, accentStrokePaint)

        canvas.drawRoundRect(waitBoostBtnRect, 10f, 10f, btnTerraPaint)
        canvas.drawRoundRect(waitBoostBtnRect, 10f, 10f, terraStrokePaint)

        btnTextPaint.textSize = u * 0.0195f
        btnTextPaint.color = Color.parseColor("#F4F1EA")
        canvas.drawText(
            "BUKA LOG TEKNIS (${loadedClasses} KELAS)",
            waitOpenLogBtnRect.centerX(),
            waitOpenLogBtnRect.centerY() + u * 0.007f,
            btnTextPaint
        )
        canvas.drawText(
            "TURBO BOOST RAM & FPS",
            waitBoostBtnRect.centerX(),
            waitBoostBtnRect.centerY() + u * 0.007f,
            btnTextPaint
        )

        val now = System.currentTimeMillis()
        if (copyFeedbackMessage != null && now < copyFeedbackUntilMs) {
            subPaint.color = Color.parseColor("#8CD996")
            subPaint.textSize = u * 0.0185f
            drawFittedText(
                canvas,
                copyFeedbackMessage ?: "",
                left,
                btnTop - u * 0.005f,
                centerW,
                subPaint
            )
        }
    }

    /**
     * Draws the Technical Terminal Boot Log Window (`isLogWindowOpen == true`).
     */
    private fun drawTechnicalLogWindowModal(
        canvas: Canvas,
        w: Float,
        h: Float,
        u: Float,
        topSafeY: Float
    ) {
        canvas.drawRect(0f, topSafeY, w, h, scrimPaint)

        val padX = w * 0.025f
        val padBottom = h * 0.025f
        panelRect.set(padX, topSafeY + h * 0.012f, w - padX, h - padBottom)

        canvas.drawRoundRect(panelRect, 18f, 18f, panelPaint)
        canvas.drawRoundRect(panelRect, 18f, 18f, strokePaint)

        val innerPad = u * 0.025f
        val left = panelRect.left + innerPad
        val right = panelRect.right - innerPad
        var curY = panelRect.top + innerPad * 0.85f

        val mascotSize = u * 0.068f
        val textLeft = if (mascotBitmap != null) left + mascotSize + u * 0.018f else left
        mascotBitmap?.let { bmp ->
            bitmapSrcRect.set(0, 0, bmp.width, bmp.height)
            val dst = RectF(left, curY, left + mascotSize, curY + mascotSize)
            canvas.drawBitmap(bmp, bitmapSrcRect, dst, mascotPaint)
        }

        titlePaint.textSize = u * 0.026f
        titlePaint.color = if (isJvmExited && jvmExitCode != 0) {
            Color.parseColor("#E07A5F")
        } else {
            Color.parseColor("#F4F1EA")
        }
        val headerTitle = when {
            isJvmExited -> "PROSES OPENJDK BERHENTI (KODE KELUAR: $jvmExitCode)"
            titleScreenReached -> "MENU UTAMA MINECRAFT 1.20.4 AKTIF (100%)"
            mojangSplashReached -> "LAYAR SPLASH MOJANG STUDIOS AKTIF (${bootPercent}%)"
            else -> "KONSOL LOG TEKNIS • $currentStageTitle"
        }
        drawFittedText(
            canvas,
            headerTitle,
            textLeft,
            curY + u * 0.028f,
            right - textLeft,
            titlePaint
        )

        subPaint.textSize = u * 0.0185f
        subPaint.color = Color.parseColor("#8CD996")
        val subHeader = if (mojangSplashReached) {
            "Layar OpenGL Minecraft sudah tampil di belakang. Ketuk 'SEMBUNYIKAN LOG' di bawah untuk lanjut bermain."
        } else {
            "Ketuk 'SEMBUNYIKAN LOG' di bawah untuk kembali ke Layar Tunggu Animasi & Hadits sambil menunggu Mojang Splash."
        }
        drawFittedText(
            canvas,
            subHeader,
            textLeft,
            curY + u * 0.056f,
            right - textLeft,
            subPaint
        )

        curY += mascotSize + innerPad * 0.35f

        // Progress Bar
        val barH = u * 0.024f
        progressTrackRect.set(left, curY, right, curY + barH)
        canvas.drawRoundRect(progressTrackRect, barH * 0.5f, barH * 0.5f, trackPaint)
        canvas.drawRoundRect(progressTrackRect, barH * 0.5f, barH * 0.5f, strokePaint)

        val fillW = ((right - left) * (displayedProgress / 100f)).coerceIn(barH, right - left)
        progressFillRect.set(left, curY, left + fillW, curY + barH)
        fillPaint.shader = LinearGradient(
            left, curY, left + fillW, curY,
            Color.parseColor("#3E7346"),
            Color.parseColor("#68B572"),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(progressFillRect, barH * 0.5f, barH * 0.5f, fillPaint)
        fillPaint.shader = null

        curY += barH + innerPad * 0.35f

        // 3 Telemetry Cards
        val cardGap = u * 0.015f
        val cardW = (right - left - cardGap * 2f) / 3f
        val cardH = u * 0.088f

        cardSplashRect.set(left, curY, left + cardW, curY + cardH)
        cardMenuRect.set(cardSplashRect.right + cardGap, curY, cardSplashRect.right + cardGap + cardW, curY + cardH)
        cardMetricsRect.set(cardMenuRect.right + cardGap, curY, right, curY + cardH)

        canvas.drawRoundRect(cardSplashRect, 10f, 10f, if (mojangSplashReached) cardActiveBgPaint else cardBgPaint)
        canvas.drawRoundRect(cardSplashRect, 10f, 10f, if (mojangSplashReached) accentStrokePaint else strokePaint)
        subPaint.textSize = u * 0.0165f
        subPaint.color = Color.parseColor("#B0ABA0")
        drawFittedText(canvas, "STATUS MOJANG SPLASH", cardSplashRect.left + u * 0.014f, cardSplashRect.top + u * 0.024f, cardW - u * 0.028f, subPaint)
        titlePaint.textSize = u * 0.022f
        titlePaint.color = if (mojangSplashReached) Color.parseColor("#8CD996") else Color.parseColor("#E6B86A")
        drawFittedText(
            canvas,
            if (mojangSplashReached) "TAMPIL DI LAYAR!" else "~${etaMojangSplashSec} DETIK LAGI",
            cardSplashRect.left + u * 0.014f,
            cardSplashRect.top + u * 0.052f,
            cardW - u * 0.028f,
            titlePaint
        )
        subPaint.color = Color.parseColor("#D4CFC4")
        drawFittedText(
            canvas,
            "Kelas JVM: $loadedClasses / $targetSplashClasses",
            cardSplashRect.left + u * 0.014f,
            cardSplashRect.top + u * 0.076f,
            cardW - u * 0.028f,
            subPaint
        )

        canvas.drawRoundRect(cardMenuRect, 10f, 10f, if (titleScreenReached) cardActiveBgPaint else cardBgPaint)
        canvas.drawRoundRect(cardMenuRect, 10f, 10f, if (titleScreenReached) accentStrokePaint else strokePaint)
        subPaint.color = Color.parseColor("#B0ABA0")
        drawFittedText(canvas, "ESTIMASI MENU UTAMA", cardMenuRect.left + u * 0.014f, cardMenuRect.top + u * 0.024f, cardW - u * 0.028f, subPaint)
        titlePaint.color = if (titleScreenReached) Color.parseColor("#8CD996") else Color.parseColor("#D99B78")
        drawFittedText(
            canvas,
            if (titleScreenReached) "MENU SIAP!" else "~${etaMinecraftMenuSec} DETIK LAGI",
            cardMenuRect.left + u * 0.014f,
            cardMenuRect.top + u * 0.052f,
            cardW - u * 0.028f,
            titlePaint
        )
        subPaint.color = Color.parseColor("#D4CFC4")
        drawFittedText(
            canvas,
            "Kecepatan: ${if (classesPerSec > 0) "$classesPerSec kelas/dtk" else "Mengukur..."}",
            cardMenuRect.left + u * 0.014f,
            cardMenuRect.top + u * 0.076f,
            cardW - u * 0.028f,
            subPaint
        )

        canvas.drawRoundRect(cardMetricsRect, 10f, 10f, cardBgPaint)
        canvas.drawRoundRect(cardMetricsRect, 10f, 10f, strokePaint)
        subPaint.color = Color.parseColor("#B0ABA0")
        drawFittedText(canvas, "MEMORI & WAKTU ENGINE", cardMetricsRect.left + u * 0.014f, cardMetricsRect.top + u * 0.024f, cardW - u * 0.028f, subPaint)
        titlePaint.color = Color.parseColor("#F4F1EA")
        drawFittedText(
            canvas,
            if (jvmUsedRamMb > 0) "RAM: $jvmUsedRamMb / $jvmMaxRamMb MB" else "Target: $jvmMaxRamMb MB",
            cardMetricsRect.left + u * 0.014f,
            cardMetricsRect.top + u * 0.052f,
            cardW - u * 0.028f,
            titlePaint
        )
        subPaint.color = Color.parseColor("#D4CFC4")
        drawFittedText(
            canvas,
            "Waktu Berjalan: ${elapsedSec} dtk",
            cardMetricsRect.left + u * 0.014f,
            cardMetricsRect.top + u * 0.076f,
            cardW - u * 0.028f,
            subPaint
        )

        curY += cardH + innerPad * 0.32f

        // Sub-Task Strip
        val stripH = u * 0.046f
        subTaskStripRect.set(left, curY, right, curY + stripH)
        canvas.drawRoundRect(subTaskStripRect, 8f, 8f, cardBgPaint)
        canvas.drawRoundRect(subTaskStripRect, 8f, 8f, strokePaint)
        subPaint.textSize = u * 0.0175f
        subPaint.color = Color.parseColor("#EAE6DC")
        drawFittedText(
            canvas,
            "Sub-Tahap: $currentSubTaskDetail  |  Kelas Aktif: $currentActiveClass",
            subTaskStripRect.left + u * 0.014f,
            subTaskStripRect.centerY() + u * 0.006f,
            subTaskStripRect.width() - u * 0.028f,
            subPaint
        )

        curY += stripH + innerPad * 0.32f

        val btnH = u * 0.068f
        val btnBottom = panelRect.bottom - innerPad * 0.75f
        val btnTop = btnBottom - btnH
        val bodyBottom = btnTop - innerPad * 0.36f

        // Pure Technical Terminal Area (No Hadits clutter inside the log window!)
        terminalRect.set(left, curY, right, bodyBottom)
        canvas.drawRoundRect(terminalRect, 12f, 12f, terminalBgPaint)
        canvas.drawRoundRect(terminalRect, 12f, 12f, strokePaint)

        val lineH = u * 0.024f
        val maxLines = ((terminalRect.height() - u * 0.030f) / lineH).toInt().coerceIn(5, 24)
        val lines = readLiveLogTail(maxLines)

        monoPaint.textSize = u * 0.018f
        var logY = terminalRect.top + u * 0.026f
        val maxLogW = terminalRect.width() - u * 0.028f

        for (line in lines) {
            monoPaint.color = when {
                line.contains("FATAL") || line.contains("ERROR") || line.contains("JAVA-ERR") || line.contains("Exception") ->
                    Color.parseColor("#FCA5A5")
                line.contains("WARN") ->
                    Color.parseColor("#E6B86A")
                line.contains("PROGRES-BOOT") || line.contains("TURBO") ->
                    Color.parseColor("#8CD996")
                line.contains("NATIVE-JVM-OUT") || line.contains("JVM-VM") ->
                    Color.parseColor("#A7F3D0")
                line.contains("STAGE") || line.contains("DLOPEN") || line.contains("BOOT") || line.contains("EGL") ->
                    Color.parseColor("#93C5FD")
                else ->
                    Color.parseColor("#D4CFC4")
            }
            drawFittedText(canvas, line, terminalRect.left + u * 0.014f, logY, maxLogW, monoPaint)
            logY += lineH
        }

        val now = System.currentTimeMillis()
        if (copyFeedbackMessage != null && now < copyFeedbackUntilMs) {
            subPaint.color = Color.parseColor("#8CD996")
            subPaint.textSize = u * 0.019f
            drawFittedText(
                canvas,
                copyFeedbackMessage ?: "",
                left + u * 0.012f,
                bodyBottom - u * 0.010f,
                right - left - u * 0.024f,
                subPaint
            )
        }

        // Bottom 4 Action Buttons
        val gap = u * 0.013f
        val btnW = (right - left - gap * 3f) / 4f

        btnMinimizeRect.set(left, btnTop, left + btnW, btnBottom)
        btnBoostRamRect.set(btnMinimizeRect.right + gap, btnTop, btnMinimizeRect.right + gap + btnW, btnBottom)
        btnCopyLogRect.set(btnBoostRamRect.right + gap, btnTop, btnBoostRamRect.right + gap + btnW, btnBottom)
        btnReturnMenuRect.set(btnCopyLogRect.right + gap, btnTop, right, btnBottom)

        canvas.drawRoundRect(btnMinimizeRect, 9f, 9f, btnPrimaryPaint)
        canvas.drawRoundRect(btnMinimizeRect, 9f, 9f, accentStrokePaint)

        canvas.drawRoundRect(btnBoostRamRect, 9f, 9f, btnTerraPaint)
        canvas.drawRoundRect(btnBoostRamRect, 9f, 9f, terraStrokePaint)

        canvas.drawRoundRect(btnCopyLogRect, 9f, 9f, btnSecondaryPaint)
        canvas.drawRoundRect(btnCopyLogRect, 9f, 9f, strokePaint)

        canvas.drawRoundRect(btnReturnMenuRect, 9f, 9f, btnWarnPaint)
        canvas.drawRoundRect(btnReturnMenuRect, 9f, 9f, strokePaint)

        btnTextPaint.textSize = u * 0.0185f
        btnTextPaint.color = Color.parseColor("#F4F1EA")
        val textCenterOffset = btnH * 0.58f
        val hideLabel = if (mojangSplashReached) {
            "SEMBUNYIKAN LOG (MAIN)"
        } else {
            "TUTUP LOG -> LAYAR TUNGGU"
        }
        canvas.drawText(hideLabel, btnMinimizeRect.centerX(), btnMinimizeRect.top + textCenterOffset, btnTextPaint)
        canvas.drawText("TURBO BOOST RAM & FPS", btnBoostRamRect.centerX(), btnBoostRamRect.top + textCenterOffset, btnTextPaint)
        canvas.drawText("SALIN LOG CLIPBOARD", btnCopyLogRect.centerX(), btnCopyLogRect.top + textCenterOffset, btnTextPaint)
        canvas.drawText("KEMBALI KE LAUNCHER", btnReturnMenuRect.centerX(), btnReturnMenuRect.top + textCenterOffset, btnTextPaint)
    }

    private fun drawIsometricVoxelCube(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val dx = r * 0.866f
        val dy = r * 0.5f

        isoPath.reset()
        isoPath.moveTo(cx, cy - r)
        isoPath.lineTo(cx + dx, cy - dy)
        isoPath.lineTo(cx, cy)
        isoPath.lineTo(cx - dx, cy - dy)
        isoPath.close()
        canvas.drawPath(isoPath, voxelTopPaint)
        canvas.drawPath(isoPath, voxelWirePaint)

        isoPath.reset()
        isoPath.moveTo(cx - dx, cy - dy)
        isoPath.lineTo(cx, cy)
        isoPath.lineTo(cx, cy + r)
        isoPath.lineTo(cx - dx, cy + dy)
        isoPath.close()
        canvas.drawPath(isoPath, voxelLeftPaint)
        canvas.drawPath(isoPath, voxelWirePaint)

        isoPath.reset()
        isoPath.moveTo(cx, cy)
        isoPath.lineTo(cx + dx, cy - dy)
        isoPath.lineTo(cx + dx, cy + dy)
        isoPath.lineTo(cx, cy + r)
        isoPath.close()
        canvas.drawPath(isoPath, voxelRightPaint)
        canvas.drawPath(isoPath, voxelWirePaint)
    }

    private fun wrapTextToLines(text: String, paint: TextPaint, maxWidth: Float, maxLines: Int): List<String> {
        if (maxWidth <= 20f) return listOf(text)
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val test = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(test) <= maxWidth) {
                currentLine = StringBuilder(test)
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    if (lines.size >= maxLines) break
                }
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty() && lines.size < maxLines) {
            lines.add(currentLine.toString())
        }
        return lines
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val x = event.x
        val y = event.y

        // Never intercept touches on the Top Telemetry Bar (top 38dp)
        val topBarSafeOffset = (38f * resources.displayMetrics.density).coerceAtMost(height * 0.12f)
        if (y < topBarSafeOffset) {
            return false
        }

        // Touch handling when on the Full-Screen Waiting for Mojang Splash screen (`!isLogWindowOpen`)
        if (!isLogWindowOpen && !isJvmExited) {
            if (mojangSplashReached) return false
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                when {
                    waitNextWisdomRect.contains(x, y) || waitWisdomCardRect.contains(x, y) && !waitPrevWisdomRect.contains(x, y) -> {
                        currentWisdomIndex = (currentWisdomIndex + 1) % HADITS_AND_HIKMAH_LIST.size
                        lastWisdomSwitchMs = System.currentTimeMillis()
                        invalidate()
                        return true
                    }
                    waitPrevWisdomRect.contains(x, y) -> {
                        currentWisdomIndex = (currentWisdomIndex - 1 + HADITS_AND_HIKMAH_LIST.size) % HADITS_AND_HIKMAH_LIST.size
                        lastWisdomSwitchMs = System.currentTimeMillis()
                        invalidate()
                        return true
                    }
                    waitOpenLogBtnRect.contains(x, y) -> {
                        isLogWindowOpen = true
                        invalidate()
                        return true
                    }
                    waitBoostBtnRect.contains(x, y) -> {
                        val res = PerformanceBooster.runInstantBoost(context)
                        copyFeedbackMessage = res.summaryMessage
                        copyFeedbackUntilMs = System.currentTimeMillis() + 3500L
                        invalidate()
                        return true
                    }
                }
            }
            return true
        }

        // Touch handling inside the Technical Log Window (`isLogWindowOpen == true`)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            when {
                btnMinimizeRect.contains(x, y) -> {
                    isLogWindowOpen = false
                    if (mojangSplashReached && !isJvmExited) {
                        visibility = GONE
                        removeCallbacks(refreshTick)
                    }
                    invalidate()
                    return true
                }
                btnBoostRamRect.contains(x, y) -> {
                    val res = PerformanceBooster.runInstantBoost(context)
                    copyFeedbackMessage = res.summaryMessage
                    copyFeedbackUntilMs = System.currentTimeMillis() + 4000L
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
