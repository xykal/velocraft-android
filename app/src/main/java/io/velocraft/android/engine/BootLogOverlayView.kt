package io.velocraft.android.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.util.VeloLogger
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 100% Custom Canvas Animated Boot Experience, Hadits & Hikmah Showcase, Live Terminal & ETA Overlay.
 *
 * Solves two key user needs:
 * 1. Never leaves the user staring at a pitch-black screen while OpenJDK 17 & Fabric are booting:
 *    - Features an interactive **Visual Animated Loading Mode** with a panoramic voxel world backdrop,
 *      real-time 3D Isometric Voxel Block Assembly animation, orbiting geometric rings, and a
 *      rotating **Hadits Shahih, Hikmah & Tips Boost** showcase card (auto-rotating or tap-to-next).
 *    - Features a **Full Technical Log Terminal Mode** streaming `[NATIVE-PROGRES-BOOT]` and `latest.log`.
 *    - Tapping "Sembunyikan Log Teknis" while OpenGL hasn't drawn Mojang Splash yet switches to the
 *      Visual Animated & Hadits mode instead of a black void; tapping "Tutup Overlay" hides it completely.
 * 2. Can always be reopened anytime via the `[LOG]` pill in the Top Telemetry Bar or In-Game Sidebar!
 *
 * Strictly zero Android stock widgets, zero neon colors, and zero emojis.
 */
class BootLogOverlayView(
    context: Context,
    private val onReturnToLauncher: () -> Unit
) : View(context) {

    enum class DisplayMode {
        VISUAL_ANIMATED_HADITS,
        TECHNICAL_TERMINAL_LOG
    }

    data class WisdomEntry(
        val categoryBadge: String,
        val quoteText: String,
        val sourceRef: String
    )

    companion object {
        private val HADITS_AND_HIKMAH_LIST = listOf(
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • AKHLAK & KEBAIKAN",
                quoteText = "\"Sebaik-baik manusia adalah yang paling bermanfaat bagi manusia lainnya.\"",
                sourceRef = "HR. Ahmad & Thabrani (Hasan)"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • KESABARAN & KETENANGAN",
                quoteText = "\"Ketahuilah bahwa kemenangan itu bersama kesabaran, jalan keluar itu bersama kesulitan, dan sesungguhnya bersama kesulitan ada kemudahan.\"",
                sourceRef = "HR. Tirmidzi & Ahmad"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • MENUNTUT ILMU",
                quoteText = "\"Barangsiapa menempuh suatu jalan untuk mencari ilmu, maka Allah akan mudahkan baginya jalan menuju Surga.\"",
                sourceRef = "HR. Muslim (No. 2699)"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • WAKTU & KESEHATAN",
                quoteText = "\"Dua nikmat yang banyak manusia tertipu (lalai) di dalamnya: nikmat sehat dan nikmat waktu luang.\"",
                sourceRef = "HR. Bukhari (No. 6412)"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • KELEMBUTAN HATI",
                quoteText = "\"Sesungguhnya Allah itu Maha Lembut dan mencintai kelembutan dalam segala urusan.\"",
                sourceRef = "HR. Bukhari & Muslim"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • SENYUM & SEDEKAH",
                quoteText = "\"Senyummu di hadapan saudaramu adalah sedekah bagimu.\"",
                sourceRef = "HR. Tirmidzi (No. 1956)"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • MENJAGA LISAN",
                quoteText = "\"Barangsiapa yang beriman kepada Allah dan Hari Akhir, hendaklah ia berkata yang baik atau diam.\"",
                sourceRef = "HR. Bukhari & Muslim"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • KEKUATAN SEJATI",
                quoteText = "\"Orang yang kuat bukanlah yang pandai bergulat, namun orang yang kuat adalah yang mampu mengendalikan dirinya saat marah.\"",
                sourceRef = "HR. Bukhari (No. 6114) & Muslim"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • AMAL YANG DICINTAI",
                quoteText = "\"Amalan yang paling dicintai oleh Allah adalah amalan yang kontinu (istiqamah) walaupun sedikit.\"",
                sourceRef = "HR. Bukhari & Muslim"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • KEJUJURAN",
                quoteText = "\"Hendaklah kalian senantiasa berlaku jujur, karena sesungguhnya kejujuran itu mengantarkan kepada kebaikan, dan kebaikan mengantarkan ke Surga.\"",
                sourceRef = "HR. Muslim (No. 2607)"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • NIAT SETIAP AMAL",
                quoteText = "\"Sesungguhnya setiap amalan tergantung pada niatnya, dan setiap orang akan mendapatkan sesuai dengan apa yang ia niatkan.\"",
                sourceRef = "HR. Bukhari (No. 1) & Muslim"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • MEMUDAHKAN URUSAN",
                quoteText = "\"Mudahkanlah dan jangan mempersulit, berilah kabar gembira dan jangan membuat orang lari.\"",
                sourceRef = "HR. Bukhari & Muslim"
            ),
            WisdomEntry(
                categoryBadge = "HADITS PILIHAN • RASA SYUKUR",
                quoteText = "\"Barangsiapa yang tidak bersyukur kepada manusia (atas kebaikannya), maka ia belum bersyukur kepada Allah.\"",
                sourceRef = "HR. Abu Dawud & Tirmidzi"
            ),
            WisdomEntry(
                categoryBadge = "TIPS PERFORMA • BOOST FPS IN-GAME",
                quoteText = "Gunakan tombol [BOOST] di bar statistik atas atau tab BOOST di Sidebar Kiri untuk membersihkan RAM & menerapkan profil Turbo FPS kapan saja.",
                sourceRef = "Tips Optimasi Engine 1.20.4"
            ),
            WisdomEntry(
                categoryBadge = "TIPS KONTROL • MEMBUKA KEMBALI LOG",
                quoteText = "Setelah log disembunyikan, Anda selalu bisa membuka kembali konsol ini lewat tombol [LOG] di bar statistik atas atau menu Sidebar Kiri.",
                sourceRef = "Panduan Cepat HUD Studio"
            )
        )
    }

    var bootStageTitle: String = "TAHAP 1/10: Menyiapkan Lingkungan Engine & Direktori..."
    var bootStageProgress: Int = 8
    var bootSummaryLine: String = "OpenJDK 17 HotSpot | Fabric 0.16.9 | Minecraft 1.20.4"
    var isJvmExited: Boolean = false
    var jvmExitCode: Int = 0
    var isMinimized: Boolean = false

    // Default to Visual Animated + Hadits + Live Telemetry split view so booting is engaging and never black!
    var displayMode: DisplayMode = DisplayMode.VISUAL_ANIMATED_HADITS

    private var currentSubTaskDetail: String = "Memverifikasi konfigurasi OpenJDK 17, Mod Fabric & Renderer..."
    private var currentActiveClass: String = "io.velocraft.android.engine.DirectBootProvisioner"
    private var currentStageIndex: Int = 1
    private var loadedClasses: Int = 0
    private var targetSplashClasses: Int = 5400
    private var classesPerSec: Int = 0
    private var jvmUsedRamMb: Int = 0
    private var jvmMaxRamMb: Int = 768
    private var elapsedSec: Int = 0
    private var etaMojangSplashSec: Int = 38
    private var etaMinecraftMenuSec: Int = 64
    private var mojangSplashReached: Boolean = false
    private var titleScreenReached: Boolean = false
    private var swapBuffersCount: Int = 0
    private var autoMinimizedOnSplash: Boolean = false
    private var splashReachedTimestampMs: Long = 0L

    // Animation & Wisdom rotation state
    private var animPhaseRad: Float = 0f
    private var currentWisdomIndex: Int = (System.currentTimeMillis() % HADITS_AND_HIKMAH_LIST.size).toInt()
    private var lastWisdomSwitchMs: Long = System.currentTimeMillis()
    private val wisdomRotateIntervalMs: Long = 8000L

    private var copyFeedbackMessage: String? = null
    private var copyFeedbackUntilMs: Long = 0L

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0141413")
        style = Paint.Style.FILL
    }
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F21D1C19")
        style = Paint.Style.FILL
    }
    private val cardActiveBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F21A2B21")
        style = Paint.Style.FILL
    }
    private val wisdomCardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5211F1B")
        style = Paint.Style.FILL
    }
    private val terminalBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F50E0E0D")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#35332D")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4E8C57")
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
    }
    private val terraStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C87D55")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2824")
        style = Paint.Style.FILL
    }
    private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4E8C57")
        style = Paint.Style.FILL
    }
    private val btnPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3E7446")
        style = Paint.Style.FILL
    }
    private val btnSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#282622")
        style = Paint.Style.FILL
    }
    private val btnTerraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A6633F")
        style = Paint.Style.FILL
    }
    private val btnWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C2D12")
        style = Paint.Style.FILL
    }
    private val voxelTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C9E65")
        style = Paint.Style.FILL
    }
    private val voxelLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#467A4D")
        style = Paint.Style.FILL
    }
    private val voxelRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#335938")
        style = Paint.Style.FILL
    }
    private val voxelWirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4CFC4")
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#D410100F")
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F1EA")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F1EA")
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0ABA0")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4CFC4")
        typeface = Typeface.MONOSPACE
    }
    private val btnTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F1EA")
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private var brandMarkBitmap: Bitmap? = null
    private var worldBannerBitmap: Bitmap? = null
    private var mascotBitmap: Bitmap? = null

    private val srcRect = Rect()
    private val panelRect = RectF()
    private val cardSplashRect = RectF()
    private val cardMenuRect = RectF()
    private val cardMetricsRect = RectF()
    private val subTaskStripRect = RectF()
    private val centerShowcaseRect = RectF()
    private val animBoxRect = RectF()
    private val wisdomBoxRect = RectF()
    private val nextWisdomBtnRect = RectF()
    private val prevWisdomBtnRect = RectF()
    private val terminalRect = RectF()
    private val progressTrackRect = RectF()
    private val progressFillRect = RectF()

    private val btnModeToggleRect = RectF()
    private val btnBoostRamRect = RectF()
    private val btnMinimizeRect = RectF()
    private val btnCopyLogRect = RectF()
    private val btnReturnMenuRect = RectF()
    private val isoPath = Path()

    private var lastTelemetryPullMs: Long = 0L

    private val refreshTick = object : Runnable {
        override fun run() {
            if (visibility == VISIBLE && !isMinimized) {
                val now = System.currentTimeMillis()
                animPhaseRad = (animPhaseRad + 0.085f) % (Math.PI.toFloat() * 20f)
                if (now - lastTelemetryPullMs >= 240L) {
                    lastTelemetryPullMs = now
                    pullNativeTelemetrySnapshot()
                }
                if (now - lastWisdomSwitchMs >= wisdomRotateIntervalMs) {
                    lastWisdomSwitchMs = now
                    currentWisdomIndex = (currentWisdomIndex + 1) % HADITS_AND_HIKMAH_LIST.size
                }
                postInvalidateOnAnimation()
                postDelayed(this, 45L) // ~22 FPS smooth loading animation with minimal CPU overhead
            }
        }
    }

    init {
        brandMarkBitmap = loadAssetBitmap("branding/xyverse_mark.png")
        worldBannerBitmap = loadAssetBitmap("branding/xyverse_world_banner.jpg")
        mascotBitmap = loadAssetBitmap("branding/xyverse_mascot.png")
    }

    private fun pullNativeTelemetrySnapshot() {
        val snap = NativeEngineBridge.getBootTelemetry() ?: return
        if (!isJvmExited && snap.stageIndex >= 1) {
            currentStageIndex = snap.stageIndex.coerceIn(1, 10)
            if (snap.stageTitle.isNotBlank()) {
                bootStageTitle = snap.stageTitle
            }
            if (snap.percent > bootStageProgress || snap.stageIndex >= 2) {
                bootStageProgress = snap.percent.coerceIn(0, 100)
            }
            if (snap.subTaskDetail.isNotBlank()) {
                currentSubTaskDetail = snap.subTaskDetail
            }
            if (snap.activeClassName.isNotBlank()) {
                currentActiveClass = snap.activeClassName
            }
            loadedClasses = snap.loadedClasses
            targetSplashClasses = snap.targetSplashClasses
            classesPerSec = snap.classesPerSec
            jvmUsedRamMb = snap.jvmUsedRamMb
            jvmMaxRamMb = snap.jvmMaxRamMb
            elapsedSec = snap.elapsedSec
            etaMojangSplashSec = snap.etaMojangSplashSec
            etaMinecraftMenuSec = snap.etaMinecraftMenuSec
            mojangSplashReached = snap.mojangSplashReached
            titleScreenReached = snap.titleScreenReached
            swapBuffersCount = snap.swapBuffersCount

            // Automatically close boot overlay 1.5s after the first OpenGL Mojang Splash frame renders
            if (mojangSplashReached && !autoMinimizedOnSplash && swapBuffersCount > 0) {
                if (splashReachedTimestampMs == 0L) {
                    splashReachedTimestampMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - splashReachedTimestampMs >= 1500L) {
                    autoMinimizedOnSplash = true
                    isMinimized = true
                    visibility = GONE
                }
            }
        }
    }

    fun startBootMonitoring() {
        visibility = VISIBLE
        isMinimized = false
        isJvmExited = false
        displayMode = DisplayMode.VISUAL_ANIMATED_HADITS
        autoMinimizedOnSplash = false
        splashReachedTimestampMs = 0L
        lastWisdomSwitchMs = System.currentTimeMillis()
        removeCallbacks(refreshTick)
        post(refreshTick)
    }

    fun reopenLogOverlay() {
        isMinimized = false
        visibility = VISIBLE
        removeCallbacks(refreshTick)
        post(refreshTick)
        invalidate()
    }

    fun toggleLogOverlay() {
        if (visibility == VISIBLE && !isMinimized) {
            isMinimized = true
            visibility = GONE
            removeCallbacks(refreshTick)
        } else {
            reopenLogOverlay()
        }
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
            displayMode = DisplayMode.TECHNICAL_TERMINAL_LOG
            visibility = VISIBLE
            bootStageTitle = if (exitCode == 0) {
                "SESI JVM SELESAI (KODE KELUAR 0)"
            } else {
                "SESI JVM BERHENTI (KODE KELUAR $exitCode) - PERIKSA DETAIL LOG DI BAWAH"
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
            return
        }

        // 0. Draw Panoramic Studio Voxel World Backdrop when OpenGL has not rendered any frame yet
        //    so the screen is NEVER pitch black during booting!
        if (swapBuffersCount <= 0 && worldBannerBitmap != null) {
            val bmp = worldBannerBitmap!!
            srcRect.set(0, 0, bmp.width, bmp.height)
            panelRect.set(0f, 0f, w, h)
            canvas.drawBitmap(bmp, srcRect, panelRect, bitmapPaint)
            canvas.drawRect(0f, 0f, w, h, scrimPaint)
        } else {
            canvas.drawColor(Color.parseColor("#C80D0D0C"))
        }

        val padX = w * 0.038f
        val padY = h * 0.036f
        panelRect.set(padX, padY, w - padX, h - padY)
        canvas.drawRoundRect(panelRect, 18f, 18f, panelPaint)
        canvas.drawRoundRect(panelRect, 18f, 18f, strokePaint)

        val innerPad = u * 0.024f
        val left = panelRect.left + innerPad
        val right = panelRect.right - innerPad
        var curY = panelRect.top + innerPad

        // 1. Header Row: Subtle Mark + Title + Top Mode Toggle Pill
        val markSize = u * 0.052f
        brandMarkBitmap?.let { bmp ->
            val dst = RectF(left, curY, left + markSize, curY + markSize)
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }
        val modeBtnW = u * 0.34f
        val modeBtnH = markSize
        btnModeToggleRect.set(right - modeBtnW, curY, right, curY + modeBtnH)
        canvas.drawRoundRect(btnModeToggleRect, 9f, 9f, if (displayMode == DisplayMode.TECHNICAL_TERMINAL_LOG) btnTerraPaint else btnPrimaryPaint)
        canvas.drawRoundRect(btnModeToggleRect, 9f, 9f, accentStrokePaint)
        btnTextPaint.textSize = u * 0.0195f
        val modeBtnLabel = if (displayMode == DisplayMode.VISUAL_ANIMATED_HADITS) {
            "MODE: ANIMASI & HADITS [LIHAT LOG]"
        } else {
            "MODE: TERMINAL LOG [LIHAT ANIMASI]"
        }
        canvas.drawText(modeBtnLabel, btnModeToggleRect.centerX(), btnModeToggleRect.centerY() + u * 0.007f, btnTextPaint)

        val titleX = left + markSize + innerPad * 0.55f
        val maxHeaderW = btnModeToggleRect.left - titleX - innerPad * 0.5f
        titlePaint.textSize = u * 0.027f
        titlePaint.color = Color.parseColor("#F4F1EA")
        drawFittedText(
            canvas,
            "MINECRAFT 1.20.4 FABRIC — LOADING ENGINE & TELEMETRI BOOT",
            titleX,
            curY + u * 0.023f,
            maxHeaderW,
            titlePaint
        )

        subPaint.textSize = u * 0.019f
        subPaint.color = Color.parseColor("#B0ABA0")
        drawFittedText(
            canvas,
            bootSummaryLine,
            titleX,
            curY + u * 0.047f,
            maxHeaderW,
            subPaint
        )

        curY += markSize + innerPad * 0.40f

        // 2. Stage Title & Animated Progress Bar
        titlePaint.textSize = u * 0.0225f
        titlePaint.color = if (isJvmExited && jvmExitCode != 0) {
            Color.parseColor("#FCA5A5")
        } else {
            Color.parseColor("#8CD996")
        }
        drawFittedText(
            canvas,
            "$bootStageTitle ($bootStageProgress%)",
            left,
            curY + u * 0.019f,
            right - left,
            titlePaint
        )
        titlePaint.color = Color.parseColor("#F4F1EA")

        curY += u * 0.025f
        val barH = u * 0.016f
        progressTrackRect.set(left, curY, right, curY + barH)
        canvas.drawRoundRect(progressTrackRect, 8f, 8f, progressTrackPaint)

        val fillW = (progressTrackRect.width() * (bootStageProgress / 100f)).coerceAtLeast(14f)
        progressFillRect.set(left, curY, left + fillW, curY + barH)
        canvas.drawRoundRect(progressFillRect, 8f, 8f, progressFillPaint)

        // Animated shimmer pulse along the progress bar so the user clearly sees live activity
        if (!isJvmExited) {
            val pulseRatio = ((sin(animPhaseRad * 1.4f) + 1f) * 0.5f)
            val pulseW = (fillW * 0.24f).coerceAtLeast(18f)
            val pulseLeft = left + (fillW - pulseW) * pulseRatio
            val pulseRect = RectF(pulseLeft, curY, pulseLeft + pulseW, curY + barH)
            voxelTopPaint.alpha = 140
            canvas.drawRoundRect(pulseRect, 8f, 8f, voxelTopPaint)
            voxelTopPaint.alpha = 255
        }

        // 10-Segment Stage Pill Strip (TAHAP 1..10)
        curY += barH + u * 0.007f
        val segGap = u * 0.007f
        val segW = (right - left - segGap * 9f) / 10f
        val segH = u * 0.0075f
        for (s in 1..10) {
            val sLeft = left + (s - 1) * (segW + segGap)
            progressFillRect.set(sLeft, curY, sLeft + segW, curY + segH)
            canvas.drawRoundRect(
                progressFillRect,
                4f,
                4f,
                if (s <= currentStageIndex) progressFillPaint else progressTrackPaint
            )
        }

        curY += segH + innerPad * 0.32f

        // 3. Three Dedicated ETA & Live Telemetry Cards
        val cardGap = u * 0.013f
        val cardW = (right - left - cardGap * 2f) / 3f
        val cardH = u * 0.090f

        cardSplashRect.set(left, curY, left + cardW, curY + cardH)
        cardMenuRect.set(cardSplashRect.right + cardGap, curY, cardSplashRect.right + cardGap + cardW, curY + cardH)
        cardMetricsRect.set(cardMenuRect.right + cardGap, curY, right, curY + cardH)

        // Card 1: ESTIMASI MUNCUL SPLASH MOJANG
        canvas.drawRoundRect(cardSplashRect, 10f, 10f, if (mojangSplashReached) cardActiveBgPaint else cardBgPaint)
        canvas.drawRoundRect(cardSplashRect, 10f, 10f, if (mojangSplashReached) accentStrokePaint else strokePaint)
        subPaint.textSize = u * 0.0168f
        subPaint.color = Color.parseColor("#B0ABA0")
        drawFittedText(
            canvas,
            "ESTIMASI MUNCUL SPLASH MOJANG",
            cardSplashRect.left + u * 0.014f,
            cardSplashRect.top + u * 0.024f,
            cardSplashRect.width() - u * 0.028f,
            subPaint
        )
        titlePaint.textSize = u * 0.023f
        titlePaint.color = if (mojangSplashReached) Color.parseColor("#8CD996") else Color.parseColor("#E6B86A")
        val splashEtaMain = if (mojangSplashReached) {
            "SUDAH TAMPIL DI LAYAR"
        } else {
            "~${etaMojangSplashSec} DETIK LAGI"
        }
        drawFittedText(
            canvas,
            splashEtaMain,
            cardSplashRect.left + u * 0.014f,
            cardSplashRect.top + u * 0.053f,
            cardSplashRect.width() - u * 0.028f,
            titlePaint
        )
        subPaint.textSize = u * 0.0168f
        subPaint.color = Color.parseColor("#D4CFC4")
        val splashSub = if (mojangSplashReached) {
            "OpenGL Aktif (${swapBuffersCount.coerceAtLeast(1)} frame dirender)"
        } else {
            "Kelas JVM: $loadedClasses / ~$targetSplashClasses kelas"
        }
        drawFittedText(
            canvas,
            splashSub,
            cardSplashRect.left + u * 0.014f,
            cardSplashRect.top + u * 0.078f,
            cardSplashRect.width() - u * 0.028f,
            subPaint
        )

        // Card 2: ESTIMASI MENU UTAMA MINECRAFT
        canvas.drawRoundRect(cardMenuRect, 10f, 10f, if (titleScreenReached) cardActiveBgPaint else cardBgPaint)
        canvas.drawRoundRect(cardMenuRect, 10f, 10f, if (titleScreenReached) accentStrokePaint else strokePaint)
        subPaint.textSize = u * 0.0168f
        subPaint.color = Color.parseColor("#B0ABA0")
        drawFittedText(
            canvas,
            "ESTIMASI MENU UTAMA MINECRAFT",
            cardMenuRect.left + u * 0.014f,
            cardMenuRect.top + u * 0.024f,
            cardMenuRect.width() - u * 0.028f,
            subPaint
        )
        titlePaint.textSize = u * 0.023f
        titlePaint.color = if (titleScreenReached) Color.parseColor("#8CD996") else Color.parseColor("#D99B78")
        val menuEtaMain = if (titleScreenReached) {
            "MENU UTAMA SIAP!"
        } else {
            "~${etaMinecraftMenuSec} DETIK LAGI"
        }
        drawFittedText(
            canvas,
            menuEtaMain,
            cardMenuRect.left + u * 0.014f,
            cardMenuRect.top + u * 0.053f,
            cardMenuRect.width() - u * 0.028f,
            titlePaint
        )
        subPaint.textSize = u * 0.0168f
        subPaint.color = Color.parseColor("#D4CFC4")
        val speedLabel = if (classesPerSec > 0) "$classesPerSec kelas/dtk" else "Mengukur kecepatan SoC..."
        drawFittedText(
            canvas,
            "Kecepatan: $speedLabel",
            cardMenuRect.left + u * 0.014f,
            cardMenuRect.top + u * 0.078f,
            cardMenuRect.width() - u * 0.028f,
            subPaint
        )

        // Card 3: TELEMETRI ENGINE & MEMORI JVM
        canvas.drawRoundRect(cardMetricsRect, 10f, 10f, cardBgPaint)
        canvas.drawRoundRect(cardMetricsRect, 10f, 10f, strokePaint)
        subPaint.textSize = u * 0.0168f
        subPaint.color = Color.parseColor("#B0ABA0")
        drawFittedText(
            canvas,
            "TELEMETRI ENGINE & MEMORI JVM",
            cardMetricsRect.left + u * 0.014f,
            cardMetricsRect.top + u * 0.024f,
            cardMetricsRect.width() - u * 0.028f,
            subPaint
        )
        titlePaint.textSize = u * 0.023f
        titlePaint.color = Color.parseColor("#F4F1EA")
        val ramStr = if (jvmUsedRamMb > 0) {
            "RAM: $jvmUsedRamMb / $jvmMaxRamMb MB"
        } else {
            "Target Heap: $jvmMaxRamMb MB"
        }
        drawFittedText(
            canvas,
            ramStr,
            cardMetricsRect.left + u * 0.014f,
            cardMetricsRect.top + u * 0.053f,
            cardMetricsRect.width() - u * 0.028f,
            titlePaint
        )
        subPaint.textSize = u * 0.0168f
        subPaint.color = Color.parseColor("#D4CFC4")
        drawFittedText(
            canvas,
            "Waktu Berjalan: ${elapsedSec} detik",
            cardMetricsRect.left + u * 0.014f,
            cardMetricsRect.top + u * 0.078f,
            cardMetricsRect.width() - u * 0.028f,
            subPaint
        )

        curY += cardH + innerPad * 0.34f

        // 4. Specific Sub-Task & Active Class Focus Banner
        val stripH = u * 0.050f
        subTaskStripRect.set(left, curY, right, curY + stripH)
        canvas.drawRoundRect(subTaskStripRect, 8f, 8f, cardBgPaint)
        canvas.drawRoundRect(subTaskStripRect, 8f, 8f, strokePaint)

        subPaint.textSize = u * 0.018f
        subPaint.color = Color.parseColor("#EAE6DC")
        drawFittedText(
            canvas,
            "Sub-Tahap: $currentSubTaskDetail",
            subTaskStripRect.left + u * 0.014f,
            subTaskStripRect.top + u * 0.021f,
            subTaskStripRect.width() - u * 0.028f,
            subPaint
        )
        monoPaint.textSize = u * 0.0165f
        monoPaint.color = Color.parseColor("#8CD996")
        drawFittedText(
            canvas,
            "Fokus Kelas JVM: $currentActiveClass",
            subTaskStripRect.left + u * 0.014f,
            subTaskStripRect.top + u * 0.042f,
            subTaskStripRect.width() - u * 0.028f,
            monoPaint
        )

        curY += stripH + innerPad * 0.34f

        // 5. Bottom Buttons Area reservation (4 Action Buttons)
        val btnH = u * 0.068f
        val btnBottom = panelRect.bottom - innerPad * 0.75f
        val btnTop = btnBottom - btnH
        val bodyBottom = btnTop - innerPad * 0.36f

        // 6. Middle Area: Either VISUAL_ANIMATED_HADITS or TECHNICAL_TERMINAL_LOG
        if (displayMode == DisplayMode.VISUAL_ANIMATED_HADITS) {
            drawVisualAnimatedAndHaditsArea(canvas, left, curY, right, bodyBottom, u)
        } else {
            drawTechnicalTerminalArea(canvas, left, curY, right, bodyBottom, u)
        }

        // Copy / Boost feedback banner if active
        val now = System.currentTimeMillis()
        if (copyFeedbackMessage != null && now < copyFeedbackUntilMs) {
            subPaint.color = Color.parseColor("#8CD996")
            subPaint.textSize = u * 0.0195f
            drawFittedText(
                canvas,
                copyFeedbackMessage ?: "",
                left + u * 0.012f,
                bodyBottom - u * 0.010f,
                right - left - u * 0.024f,
                subPaint
            )
        }

        // 7. Bottom 4 Action Buttons:
        //    [1. SEMBUNYIKAN LOG / TUTUP OVERLAY]  [2. BOOST RAM & FPS]  [3. SALIN LOG]  [4. MENU UTAMA]
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
        val textCenterOffset = btnH * 0.58f
        canvas.drawText(
            "SEMBUNYIKAN (BUKA LAGI DI [LOG])",
            btnMinimizeRect.centerX(),
            btnMinimizeRect.top + textCenterOffset,
            btnTextPaint
        )
        canvas.drawText(
            "BOOST RAM & CACHE SEKARANG",
            btnBoostRamRect.centerX(),
            btnBoostRamRect.top + textCenterOffset,
            btnTextPaint
        )
        canvas.drawText(
            "SALIN LOG KE CLIPBOARD",
            btnCopyLogRect.centerX(),
            btnCopyLogRect.top + textCenterOffset,
            btnTextPaint
        )
        canvas.drawText(
            "KEMBALI KE MENU LAUNCHER",
            btnReturnMenuRect.centerX(),
            btnReturnMenuRect.top + textCenterOffset,
            btnTextPaint
        )
    }

    /**
     * Draws the Engaging Visual Animated Voxel Scene (Left 36%) + Hadits Pilihan / Hikmah Card + Live Mini-Log (Right 64%).
     */
    private fun drawVisualAnimatedAndHaditsArea(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        u: Float
    ) {
        val totalW = right - left
        val gap = u * 0.015f
        val leftW = totalW * 0.35f

        animBoxRect.set(left, top, left + leftW, bottom)
        wisdomBoxRect.set(animBoxRect.right + gap, top, right, bottom)

        // LEFT CARD: 3D Isometric Voxel Cube Builder Animation + Orbiting Geometric Rings
        canvas.drawRoundRect(animBoxRect, 12f, 12f, cardBgPaint)
        canvas.drawRoundRect(animBoxRect, 12f, 12f, accentStrokePaint)

        val cx = animBoxRect.centerX()
        val cy = animBoxRect.top + animBoxRect.height() * 0.44f
        val maxR = min(animBoxRect.width(), animBoxRect.height()) * 0.32f

        // Orbiting outer & inner geometric astrolabe rings
        voxelWirePaint.color = Color.parseColor("#4E8C57")
        voxelWirePaint.strokeWidth = 2f
        val ringRect1 = RectF(cx - maxR, cy - maxR, cx + maxR, cy + maxR)
        val startDeg1 = (animPhaseRad * 38f) % 360f
        canvas.drawArc(ringRect1, startDeg1, 110f, false, voxelWirePaint)
        canvas.drawArc(ringRect1, startDeg1 + 180f, 110f, false, voxelWirePaint)

        voxelWirePaint.color = Color.parseColor("#C87D55")
        val r2 = maxR * 0.78f
        val ringRect2 = RectF(cx - r2, cy - r2, cx + r2, cy + r2)
        val startDeg2 = 360f - ((animPhaseRad * 52f) % 360f)
        canvas.drawArc(ringRect2, startDeg2, 85f, false, voxelWirePaint)
        canvas.drawArc(ringRect2, startDeg2 + 180f, 85f, false, voxelWirePaint)

        // 7 Animated 3D Isometric Voxel Cubes pulsing in a cluster
        val cubeSize = maxR * 0.30f
        val bobCenter = sin(animPhaseRad * 1.8f) * (u * 0.009f)
        val offsets = listOf(
            Triple(-1f, 0f, 0.6f),
            Triple(0f, -1f, 1.2f),
            Triple(0f, 1f, 1.8f),
            Triple(1f, 0f, 2.4f),
            Triple(0f, 0f, 0f)
        )
        for ((ox, oy, phaseOff) in offsets) {
            val localWave = sin(animPhaseRad * 2.1f + phaseOff) * (u * 0.008f)
            val isoX = cx + (ox - oy) * cubeSize * 0.86f
            val isoY = cy + (ox + oy) * cubeSize * 0.50f + bobCenter + localWave
            drawIsometricVoxelCube(canvas, isoX, isoY, cubeSize * 0.78f)
        }

        // Caption below 3D Voxel Animation
        btnTextPaint.textSize = u * 0.0195f
        btnTextPaint.color = Color.parseColor("#F4F1EA")
        canvas.drawText(
            "MENYIAPKAN DUNIA VOXEL 3D...",
            cx,
            animBoxRect.bottom - u * 0.052f,
            btnTextPaint
        )
        btnTextPaint.textSize = u * 0.0165f
        btnTextPaint.color = Color.parseColor("#8CD996")
        val animSub = if (mojangSplashReached) {
            "Layar OpenGL Siap • Ketuk Sembunyikan"
        } else {
            "Tahap $currentStageIndex/10 • ${loadedClasses} Kelas Dimuat"
        }
        canvas.drawText(
            animSub,
            cx,
            animBoxRect.bottom - u * 0.024f,
            btnTextPaint
        )
        btnTextPaint.color = Color.parseColor("#F4F1EA")

        // RIGHT CARD: Rotating Hadits Shahih & Hikmah Pilihan + Live 3-Line Log Preview
        canvas.drawRoundRect(wisdomBoxRect, 12f, 12f, wisdomCardBgPaint)
        canvas.drawRoundRect(wisdomBoxRect, 12f, 12f, terraStrokePaint)

        val wLeft = wisdomBoxRect.left + u * 0.022f
        val wRight = wisdomBoxRect.right - u * 0.022f
        val wMaxW = wRight - wLeft
        val wisdom = HADITS_AND_HIKMAH_LIST[currentWisdomIndex % HADITS_AND_HIKMAH_LIST.size]

        // Top Row inside Wisdom Card: Category Badge + [< SEBELUMNYA] [BERIKUTNYA >]
        val navBtnW = u * 0.16f
        val navBtnH = u * 0.042f
        val navTop = wisdomBoxRect.top + u * 0.016f
        nextWisdomBtnRect.set(wRight - navBtnW, navTop, wRight, navTop + navBtnH)
        prevWisdomBtnRect.set(nextWisdomBtnRect.left - u * 0.010f - navBtnW, navTop, nextWisdomBtnRect.left - u * 0.010f, navTop + navBtnH)

        canvas.drawRoundRect(prevWisdomBtnRect, 6f, 6f, btnSecondaryPaint)
        canvas.drawRoundRect(prevWisdomBtnRect, 6f, 6f, strokePaint)
        canvas.drawRoundRect(nextWisdomBtnRect, 6f, 6f, btnPrimaryPaint)
        canvas.drawRoundRect(nextWisdomBtnRect, 6f, 6f, accentStrokePaint)

        btnTextPaint.textSize = u * 0.0165f
        canvas.drawText("< SEBELUMNYA", prevWisdomBtnRect.centerX(), prevWisdomBtnRect.centerY() + u * 0.006f, btnTextPaint)
        canvas.drawText("GANTI HADITS >", nextWisdomBtnRect.centerX(), nextWisdomBtnRect.centerY() + u * 0.006f, btnTextPaint)

        titlePaint.textSize = u * 0.0195f
        titlePaint.color = Color.parseColor("#E6B86A")
        drawFittedText(
            canvas,
            "${wisdom.categoryBadge} (${currentWisdomIndex + 1}/${HADITS_AND_HIKMAH_LIST.size})",
            wLeft,
            navTop + u * 0.028f,
            prevWisdomBtnRect.left - wLeft - u * 0.014f,
            titlePaint
        )

        // Multi-line wrapped Hadits / Wisdom quote text
        quotePaint.textSize = u * 0.0235f
        quotePaint.color = Color.parseColor("#F4F1EA")
        val wrappedLines = wrapTextToLines(wisdom.quoteText, quotePaint, wMaxW, maxLines = 3)
        var qY = navTop + navBtnH + u * 0.036f
        for (line in wrappedLines) {
            canvas.drawText(line, wLeft, qY, quotePaint)
            qY += u * 0.030f
        }

        // Source Reference (Riwayat)
        titlePaint.textSize = u * 0.019f
        titlePaint.color = Color.parseColor("#8CD996")
        drawFittedText(canvas, "— ${wisdom.sourceRef}", wLeft, qY + u * 0.006f, wMaxW, titlePaint)

        // Compact 2-Line Live Terminal Log Strip at Bottom of Wisdom Card so user still sees live progress
        val miniLogH = u * 0.068f
        val miniLogTop = (wisdomBoxRect.bottom - miniLogH - u * 0.014f).coerceAtLeast(qY + u * 0.020f)
        if (wisdomBoxRect.bottom - miniLogTop >= u * 0.045f) {
            terminalRect.set(wLeft, miniLogTop, wRight, wisdomBoxRect.bottom - u * 0.012f)
            canvas.drawRoundRect(terminalRect, 8f, 8f, terminalBgPaint)
            canvas.drawRoundRect(terminalRect, 8f, 8f, strokePaint)

            val recentLines = readLiveLogTail(2)
            monoPaint.textSize = u * 0.0165f
            monoPaint.color = Color.parseColor("#B0ABA0")
            var ly = terminalRect.top + u * 0.023f
            for (l in recentLines) {
                drawFittedText(canvas, l, terminalRect.left + u * 0.012f, ly, terminalRect.width() - u * 0.024f, monoPaint)
                ly += u * 0.023f
            }
        }
    }

    /**
     * Draws a 3D isometric voxel block with top, left, and right shaded faces.
     */
    private fun drawIsometricVoxelCube(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val dx = r * 0.866f
        val dy = r * 0.5f

        // Top Face
        isoPath.reset()
        isoPath.moveTo(cx, cy - r)
        isoPath.lineTo(cx + dx, cy - dy)
        isoPath.lineTo(cx, cy)
        isoPath.lineTo(cx - dx, cy - dy)
        isoPath.close()
        canvas.drawPath(isoPath, voxelTopPaint)
        canvas.drawPath(isoPath, voxelWirePaint)

        // Left Face
        isoPath.reset()
        isoPath.moveTo(cx - dx, cy - dy)
        isoPath.lineTo(cx, cy)
        isoPath.lineTo(cx, cy + r)
        isoPath.lineTo(cx - dx, cy + dy)
        isoPath.close()
        canvas.drawPath(isoPath, voxelLeftPaint)
        canvas.drawPath(isoPath, voxelWirePaint)

        // Right Face
        isoPath.reset()
        isoPath.moveTo(cx, cy)
        isoPath.lineTo(cx + dx, cy - dy)
        isoPath.lineTo(cx + dx, cy + dy)
        isoPath.lineTo(cx, cy + r)
        isoPath.close()
        canvas.drawPath(isoPath, voxelRightPaint)
        canvas.drawPath(isoPath, voxelWirePaint)
    }

    private fun drawTechnicalTerminalArea(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        u: Float
    ) {
        // Compact 1-Line Hadits Banner above the Terminal Log
        val wisdom = HADITS_AND_HIKMAH_LIST[currentWisdomIndex % HADITS_AND_HIKMAH_LIST.size]
        val wStripH = u * 0.044f
        wisdomBoxRect.set(left, top, right, top + wStripH)
        canvas.drawRoundRect(wisdomBoxRect, 8f, 8f, wisdomCardBgPaint)
        canvas.drawRoundRect(wisdomBoxRect, 8f, 8f, terraStrokePaint)

        subPaint.textSize = u * 0.0175f
        subPaint.color = Color.parseColor("#E6B86A")
        drawFittedText(
            canvas,
            "[HADITS & HIKMAH] ${wisdom.quoteText} (${wisdom.sourceRef})  [Ketuk untuk Ganti]",
            wisdomBoxRect.left + u * 0.014f,
            wisdomBoxRect.centerY() + u * 0.006f,
            wisdomBoxRect.width() - u * 0.028f,
            subPaint
        )

        val termTop = top + wStripH + u * 0.010f
        terminalRect.set(left, termTop, right, bottom)
        canvas.drawRoundRect(terminalRect, 12f, 12f, terminalBgPaint)
        canvas.drawRoundRect(terminalRect, 12f, 12f, strokePaint)

        val lineH = u * 0.024f
        val maxLines = ((terminalRect.height() - u * 0.030f) / lineH).toInt().coerceIn(4, 22)
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
                line.contains("PROGRES-BOOT") ->
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
        if (visibility != VISIBLE || isMinimized) return false
        val x = event.x
        val y = event.y

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            when {
                btnModeToggleRect.contains(x, y) -> {
                    displayMode = if (displayMode == DisplayMode.VISUAL_ANIMATED_HADITS) {
                        DisplayMode.TECHNICAL_TERMINAL_LOG
                    } else {
                        DisplayMode.VISUAL_ANIMATED_HADITS
                    }
                    invalidate()
                    return true
                }
                displayMode == DisplayMode.VISUAL_ANIMATED_HADITS && nextWisdomBtnRect.contains(x, y) -> {
                    currentWisdomIndex = (currentWisdomIndex + 1) % HADITS_AND_HIKMAH_LIST.size
                    lastWisdomSwitchMs = System.currentTimeMillis()
                    invalidate()
                    return true
                }
                displayMode == DisplayMode.VISUAL_ANIMATED_HADITS && prevWisdomBtnRect.contains(x, y) -> {
                    currentWisdomIndex = (currentWisdomIndex - 1 + HADITS_AND_HIKMAH_LIST.size) % HADITS_AND_HIKMAH_LIST.size
                    lastWisdomSwitchMs = System.currentTimeMillis()
                    invalidate()
                    return true
                }
                wisdomBoxRect.contains(x, y) -> {
                    currentWisdomIndex = (currentWisdomIndex + 1) % HADITS_AND_HIKMAH_LIST.size
                    lastWisdomSwitchMs = System.currentTimeMillis()
                    invalidate()
                    return true
                }
                btnMinimizeRect.contains(x, y) -> {
                    // If user is in Technical Log mode and OpenGL hasn't rendered any frame yet (swapBuffersCount == 0),
                    // switch first to the Visual Animated & Hadits screen so it doesn't turn pitch black!
                    // If already in Visual Animated mode (or OpenGL is active), minimize completely (can be reopened via [LOG] on top bar).
                    if (displayMode == DisplayMode.TECHNICAL_TERMINAL_LOG && swapBuffersCount <= 0 && !isJvmExited) {
                        displayMode = DisplayMode.VISUAL_ANIMATED_HADITS
                        copyFeedbackMessage = "Beralih ke Mode Animasi & Hadits agar layar tidak hitam. Ketuk sekali lagi untuk sembunyikan penuh (buka lagi lewat tombol [LOG] di atas)."
                        copyFeedbackUntilMs = System.currentTimeMillis() + 4000L
                        invalidate()
                    } else {
                        isMinimized = true
                        visibility = GONE
                        removeCallbacks(refreshTick)
                        invalidate()
                    }
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
