#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#include <cmath>
#include <cstdarg>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
#include <atomic>

#include "lwjgl_input_bridge.h"

#define LOG_TAG "VeloCraftNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(__aarch64__)
#define VELOCRAFT_ABI_NAME "arm64-v8a"
#define SYSTEM_LIB_DIR "/system/lib64:/vendor/lib64:/vendor/lib64/hw"
#define IS_64BIT_ABI 1
#else
#define VELOCRAFT_ABI_NAME "armeabi-v7a"
#define SYSTEM_LIB_DIR "/system/lib:/vendor/lib:/vendor/lib/hw"
#define IS_64BIT_ABI 0
#endif

namespace {

using namespace velocraft::input;

using GLFW_invoke_Char_t        = void (*)(void* window, unsigned int codepoint);
using GLFW_invoke_CharMods_t    = void (*)(void* window, unsigned int codepoint, int mods);
using GLFW_invoke_CursorEnter_t = void (*)(void* window, int entered);
using GLFW_invoke_CursorPos_t   = void (*)(void* window, double xpos, double ypos);
using GLFW_invoke_Key_t         = void (*)(void* window, int key, int scancode, int action, int mods);
using GLFW_invoke_MouseButton_t = void (*)(void* window, int button, int action, int mods);
using GLFW_invoke_Scroll_t      = void (*)(void* window, double xoffset, double yoffset);

using CreateJavaVM_t = jint (JNICALL *)(JavaVM** pvm, void** penv, void* args);
using android_update_LD_LIBRARY_PATH_t = void (*)(const char*);

int64_t currentTimeMillis() {
    struct timeval tv{};
    gettimeofday(&tv, nullptr);
    return static_cast<int64_t>(tv.tv_sec) * 1000LL + static_cast<int64_t>(tv.tv_usec) / 1000LL;
}

/**
 * Cross-namespace Singleton State shared between ART's classloader namespace (`libvelocraft_bridge.so`)
 * and OpenJDK/Bionic's default namespace (`libpojavexec.so`) via `VELOCRAFT_SHARED_STATE_ADDR`.
 */
struct SharedEngineState {
    LockFreeInputQueue<2048> inputQueue;

    std::atomic<ANativeWindow*> nativeWindow{nullptr};
    std::atomic<int32_t> surfaceWidth{1280};
    std::atomic<int32_t> surfaceHeight{720};
    std::atomic<bool> cursorGrabbed{false};
    std::atomic<bool> inputReady{false};

    std::atomic<double> cursorX{640.0};
    std::atomic<double> cursorY{360.0};
    std::atomic<double> lastCursorX{640.0};
    std::atomic<double> lastCursorY{360.0};

    char jreHomePath[512] = {0};
    char nativesDirPath[512] = {0};
    char logFilePath[512] = "/storage/emulated/0/Android/media/id.xyverse.minecraft/log/latest.log";

    std::atomic<bool> stdioRedirected{false};
    int stdioPipe[2] = {-1, -1};

    JavaVM* dalvikJavaVM = nullptr;
    std::atomic<JavaVM*> runtimeJavaVM{nullptr};
    jclass vmGlfwClass = nullptr;
    jmethodID methodInternalChangeMonitorSize = nullptr;
    jmethodID methodInternalWindowSizeChanged = nullptr;
    jbyte* glfwKeyDownBuffer = nullptr;
    jbyte* glfwMouseDownBuffer = nullptr;
    jlong showingWindow = 1;

    uint8_t gamepadButtons[64] = {0};
    float gamepadAxes[16] = {0.0f};

    std::atomic<GLFW_invoke_Char_t>        cbChar{nullptr};
    std::atomic<GLFW_invoke_CharMods_t>    cbCharMods{nullptr};
    std::atomic<GLFW_invoke_CursorEnter_t> cbCursorEnter{nullptr};
    std::atomic<GLFW_invoke_CursorPos_t>   cbCursorPos{nullptr};
    std::atomic<GLFW_invoke_Key_t>         cbKey{nullptr};
    std::atomic<GLFW_invoke_MouseButton_t> cbMouseButton{nullptr};
    std::atomic<GLFW_invoke_Scroll_t>      cbScroll{nullptr};

    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLSurface eglSurface = EGL_NO_SURFACE;
    EGLSurface pbufferSurface = EGL_NO_SURFACE;
    EGLContext eglContext = EGL_NO_CONTEXT;
    EGLConfig  eglConfig  = nullptr;
    EGLint     eglFormat  = 0;
    std::atomic<bool> isBackgrounded{false};
    std::atomic<bool> surfaceNeedsRebind{false};

    // Real-time Boot Progress & Mojang Splash / Minecraft Title Screen ETA Telemetry
    pthread_mutex_t telemetryMutex = PTHREAD_MUTEX_INITIALIZER;
    pthread_mutex_t exitWaitMutex  = PTHREAD_MUTEX_INITIALIZER;
    pthread_cond_t  exitWaitCond   = PTHREAD_COND_INITIALIZER;

    std::atomic<int64_t> bootStartTimeMs{0};
    std::atomic<int64_t> jvmFinishedTimeMs{0};
    std::atomic<int64_t> lastProgressLogTimeMs{0};
    std::atomic<int32_t> bootStageIndex{1};         // 1..10
    std::atomic<int32_t> bootPercent{8};            // 0..100
    std::atomic<int32_t> loadedClassCount{0};
    std::atomic<int32_t> targetSplashClasses{9800}; // True class count up to Mojang Splash
    std::atomic<int32_t> targetMenuClasses{13200};  // True class count up to TitleScreen
    std::atomic<int32_t> classesPerSec{0};
    std::atomic<int32_t> jvmUsedRamMb{0};
    std::atomic<int32_t> jvmMaxRamMb{768};
    std::atomic<int32_t> etaMojangSplashSec{IS_64BIT_ABI ? 24 : 42};
    std::atomic<int32_t> etaMinecraftMenuSec{IS_64BIT_ABI ? 38 : 64};
    std::atomic<int32_t> swapBuffersCount{0};
    std::atomic<int32_t> windowSizeSyncCounter{0};
    std::atomic<int32_t> lastSyncedWidth{0};
    std::atomic<int32_t> lastSyncedHeight{0};
    std::atomic<bool> cursorEnterSent{false};
    std::atomic<bool> mojangSplashReached{false};
    std::atomic<bool> titleScreenReached{false};
    std::atomic<bool> jvmRunning{false};
    std::atomic<bool> jvmFinishedSignal{false};
    std::atomic<int32_t> interceptedExitCode{0};

    char stageTitle[256] = "TAHAP 1/10: Menyiapkan Lingkungan Engine & Direktori...";
    char subTaskDetail[256] = "Memverifikasi konfigurasi OpenJDK 17, Mod Fabric & Renderer...";
    char activeClassName[256] = "io.velocraft.android.engine.DirectBootProvisioner";
};

SharedEngineState* g_localStateCache = nullptr;

SharedEngineState* getSharedState() {
    if (g_localStateCache != nullptr) {
        return g_localStateCache;
    }
    const char* envAddr = std::getenv("VELOCRAFT_SHARED_STATE_ADDR");
    if (envAddr != nullptr && envAddr[0] == '0' && (envAddr[1] == 'x' || envAddr[1] == 'X')) {
        uintptr_t addr = static_cast<uintptr_t>(std::strtoull(envAddr, nullptr, 16));
        if (addr != 0) {
            g_localStateCache = reinterpret_cast<SharedEngineState*>(addr);
            return g_localStateCache;
        }
    }
    auto* created = new SharedEngineState();
    char addrBuf[64];
    std::snprintf(
        addrBuf,
        sizeof(addrBuf),
        "0x%llx",
        static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(created))
    );
    setenv("VELOCRAFT_SHARED_STATE_ADDR", addrBuf, 1);
    g_localStateCache = created;
    return created;
}

std::string sanitizeLogString(std::string s) {
    const struct {
        const char* from;
        const char* to;
    } replacements[] = {
        {"libpojavexec.so", "libvelocraft_bridge.so"},
        {"pojavexec", "velocraft_bridge"},
        {"PojavRendererInit", "VeloCraftRendererInit"},
        {"fixPojavGLContext", "fixVeloCraftGLContext"},
        {"pojavInitOpenGL", "initOpenGLContext"},
        {"pojavInit", "initBridge"},
        {"POJAV_RENDERER", "VELOCRAFT_RENDERER"},
        {"Pojav", "VeloCraft"},
        {"pojav", "velocraft"}
    };
    for (const auto& r : replacements) {
        size_t pos = 0;
        const size_t fromLen = std::strlen(r.from);
        const size_t toLen = std::strlen(r.to);
        while ((pos = s.find(r.from, pos)) != std::string::npos) {
            s.replace(pos, fromLen, r.to);
            pos += toLen;
        }
    }
    return s;
}

void setTelemetryStrings(
    SharedEngineState* st,
    const char* stageTitle,
    const char* subTaskDetail,
    const char* activeClassName
) {
    pthread_mutex_lock(&st->telemetryMutex);
    if (stageTitle != nullptr) {
        std::string cleanStage = sanitizeLogString(stageTitle);
        std::snprintf(st->stageTitle, sizeof(st->stageTitle), "%s", cleanStage.c_str());
    }
    if (subTaskDetail != nullptr) {
        std::string cleanDetail = sanitizeLogString(subTaskDetail);
        std::snprintf(st->subTaskDetail, sizeof(st->subTaskDetail), "%s", cleanDetail.c_str());
    }
    if (activeClassName != nullptr) {
        std::string cleanClass = sanitizeLogString(activeClassName);
        std::snprintf(st->activeClassName, sizeof(st->activeClassName), "%s", cleanClass.c_str());
    }
    pthread_mutex_unlock(&st->telemetryMutex);
}

void signalJvmFinished(SharedEngineState* st, int exitCode) {
    st->interceptedExitCode.store(exitCode, std::memory_order_release);
    st->jvmRunning.store(false, std::memory_order_release);
    st->jvmFinishedTimeMs.store(currentTimeMillis(), std::memory_order_release);

    pthread_mutex_lock(&st->exitWaitMutex);
    st->jvmFinishedSignal.store(true, std::memory_order_release);
    pthread_cond_broadcast(&st->exitWaitCond);
    pthread_mutex_unlock(&st->exitWaitMutex);
}

void appendNativeLineToLogFile(const char* prefix, const std::string& rawLine) {
    std::string line = sanitizeLogString(rawLine);
    LOGI("[%s] %s", prefix, line.c_str());
    SharedEngineState* st = getSharedState();
    if (st->logFilePath[0] == '\0') return;
    FILE* f = std::fopen(st->logFilePath, "a");
    if (f) {
        std::fprintf(f, "[NATIVE-%s] %s\n", prefix, line.c_str());
        std::fflush(f);
        std::fclose(f);
    }
}

void emitBootProgressLogLine(SharedEngineState* st, bool forceEmit) {
    const int64_t nowMs = currentTimeMillis();
    const int64_t prevMs = st->lastProgressLogTimeMs.load(std::memory_order_relaxed);
    if (!forceEmit && (nowMs - prevMs) < 1200LL) {
        return;
    }
    st->lastProgressLogTimeMs.store(nowMs, std::memory_order_relaxed);

    const int64_t startMs = st->bootStartTimeMs.load(std::memory_order_relaxed);
    const int elapsedSec = startMs > 0 ? static_cast<int>((nowMs - startMs) / 1000LL) : 0;
    const int stage = st->bootStageIndex.load(std::memory_order_relaxed);
    const int pct = st->bootPercent.load(std::memory_order_relaxed);
    const int classes = st->loadedClassCount.load(std::memory_order_relaxed);
    const int targetSplash = st->targetSplashClasses.load(std::memory_order_relaxed);
    const int rate = st->classesPerSec.load(std::memory_order_relaxed);
    const int usedRam = st->jvmUsedRamMb.load(std::memory_order_relaxed);
    const int maxRam = st->jvmMaxRamMb.load(std::memory_order_relaxed);
    const int etaSplash = st->etaMojangSplashSec.load(std::memory_order_relaxed);
    const int etaMenu = st->etaMinecraftMenuSec.load(std::memory_order_relaxed);
    const bool splashDone = st->mojangSplashReached.load(std::memory_order_relaxed);
    const bool menuDone = st->titleScreenReached.load(std::memory_order_relaxed);

    char detailBuf[256];
    char classBuf[256];
    pthread_mutex_lock(&st->telemetryMutex);
    std::snprintf(detailBuf, sizeof(detailBuf), "%s", st->subTaskDetail);
    std::snprintf(classBuf, sizeof(classBuf), "%s", st->activeClassName);
    pthread_mutex_unlock(&st->telemetryMutex);

    char splashStr[64];
    if (splashDone) {
        std::snprintf(splashStr, sizeof(splashStr), "AKTIF DI LAYAR");
    } else {
        std::snprintf(splashStr, sizeof(splashStr), "~%d dtk lagi", etaSplash);
    }

    char menuStr[64];
    if (menuDone) {
        std::snprintf(menuStr, sizeof(menuStr), "SIAP DIMAINKAN");
    } else {
        std::snprintf(menuStr, sizeof(menuStr), "~%d dtk lagi", etaMenu);
    }

    char line[1024];
    std::snprintf(
        line,
        sizeof(line),
        "[TAHAP %d/10 | %d%%] %s | Kelas: %d/%d (%d kls/dtk) | RAM JVM: %d/%d MB | Fokus: %s | Waktu: %d dtk | Estimasi Splash Mojang: %s | Estimasi Menu Minecraft: %s",
        stage,
        pct,
        detailBuf,
        classes,
        targetSplash,
        rate,
        usedRam,
        maxRam,
        classBuf,
        elapsedSec,
        splashStr,
        menuStr
    );
    appendNativeLineToLogFile("PROGRES-BOOT", line);
}

void recalculateEtaAndProgress(SharedEngineState* st, int32_t newClasses) {
    const int64_t nowMs = currentTimeMillis();
    const int64_t startMs = st->bootStartTimeMs.load(std::memory_order_relaxed);
    const double elapsedSec = startMs > 0 ? std::max(0.5, (nowMs - startMs) / 1000.0) : 1.0;

    const double measuredRate = newClasses / elapsedSec;
    const double baselineRate = IS_64BIT_ABI ? 320.0 : 210.0;
    const double weight = std::min(1.0, newClasses / 1200.0);
    const double effectiveRate = std::max(60.0, baselineRate * (1.0 - weight) + measuredRate * weight);
    st->classesPerSec.store(static_cast<int32_t>(effectiveRate), std::memory_order_relaxed);

    const int32_t targetSplash = st->targetSplashClasses.load(std::memory_order_relaxed);
    const int32_t targetMenu = st->targetMenuClasses.load(std::memory_order_relaxed);

    // If loadedClassCount approaches targetSplash without Splash yet, dynamically extend targetSplash
    if (!st->mojangSplashReached.load(std::memory_order_relaxed) && newClasses > targetSplash - 300) {
        st->targetSplashClasses.store(newClasses + 600, std::memory_order_relaxed);
        st->targetMenuClasses.store(newClasses + 3200, std::memory_order_relaxed);
    }

    const int32_t adjSplash = st->targetSplashClasses.load(std::memory_order_relaxed);
    const int32_t adjMenu = st->targetMenuClasses.load(std::memory_order_relaxed);

    if (st->mojangSplashReached.load(std::memory_order_relaxed)) {
        st->etaMojangSplashSec.store(0, std::memory_order_relaxed);
    } else {
        const int32_t remSplash = std::max(120, adjSplash - newClasses);
        const int32_t etaSplash = std::max(2, static_cast<int32_t>(std::ceil(remSplash / effectiveRate)));
        st->etaMojangSplashSec.store(etaSplash, std::memory_order_relaxed);
    }

    if (st->titleScreenReached.load(std::memory_order_relaxed)) {
        st->etaMinecraftMenuSec.store(0, std::memory_order_relaxed);
    } else {
        const int32_t remMenu = std::max(240, adjMenu - newClasses);
        const int32_t etaMenu = std::max(4, static_cast<int32_t>(std::ceil(remMenu / effectiveRate)));
        st->etaMinecraftMenuSec.store(etaMenu, std::memory_order_relaxed);
    }

    if (st->titleScreenReached.load(std::memory_order_relaxed)) {
        st->bootPercent.store(100, std::memory_order_relaxed);
    } else if (st->mojangSplashReached.load(std::memory_order_relaxed)) {
        int32_t postSplashPct = 94 + ((newClasses - adjSplash) * 5) / std::max(1, adjMenu - adjSplash);
        st->bootPercent.store(std::min(99, std::max(94, postSplashPct)), std::memory_order_relaxed);
    } else {
        int32_t classPct = 24 + (newClasses * 68) / std::max(1, adjSplash);
        int32_t curPct = st->bootPercent.load(std::memory_order_relaxed);
        int32_t nextPct = std::min(92, std::max(curPct, classPct));
        st->bootPercent.store(nextPct, std::memory_order_relaxed);
    }
}

void onJvmClassLoaded(const std::string& rawClassName) {
    SharedEngineState* st = getSharedState();
    const int32_t count = st->loadedClassCount.fetch_add(1, std::memory_order_relaxed) + 1;

    recalculateEtaAndProgress(st, count);

    int32_t prevStage = st->bootStageIndex.load(std::memory_order_relaxed);
    int32_t newStage = prevStage;
    const char* newStageTitle = nullptr;
    const char* newDetail = nullptr;
    bool isMilestoneClass = false;

    if (rawClassName.find("TitleScreen") != std::string::npos ||
        rawClassName.find("AccessibilityOnboardingScreen") != std::string::npos ||
        rawClassName.find("net.minecraft.class_442") != std::string::npos ||
        rawClassName.find("net.minecraft.class_8032") != std::string::npos) {
        st->mojangSplashReached.store(true, std::memory_order_relaxed);
        st->titleScreenReached.store(true, std::memory_order_relaxed);
        st->etaMojangSplashSec.store(0, std::memory_order_relaxed);
        st->etaMinecraftMenuSec.store(0, std::memory_order_relaxed);
        st->bootPercent.store(100, std::memory_order_relaxed);
        newStage = 10;
        newStageTitle = "TAHAP 10/10: MENU UTAMA MINECRAFT 1.20.4 SIAP DIMAINKAN!";
        newDetail = "Menu Utama (TitleScreen) aktif — Seluruh registri, tekstur & audio siap!";
        isMilestoneClass = true;
    } else if (rawClassName.find("LoadingOverlay") != std::string::npos ||
               rawClassName.find("ReloadInstance") != std::string::npos ||
               rawClassName.find("net.minecraft.class_425") != std::string::npos) {
        st->mojangSplashReached.store(true, std::memory_order_relaxed);
        st->etaMojangSplashSec.store(0, std::memory_order_relaxed);
        newStage = std::max(prevStage, 10);
        newStageTitle = "TAHAP 10/10: LAYAR SPLASH MOJANG STUDIOS TAMPIL DI LAYAR!";
        newDetail = "Splash Mojang Studios aktif — Memuat Atlas Tekstur, Font & Model 3D...";
        isMilestoneClass = true;
    } else if (prevStage >= 7 && (
               rawClassName.find("com.mojang.blaze3d") != std::string::npos ||
               rawClassName.find("org.lwjgl.glfw.GLFW") != std::string::npos ||
               rawClassName.find("org.lwjgl.opengl.GL") != std::string::npos ||
               rawClassName.find("net.minecraft.class_1041") != std::string::npos ||
               rawClassName.find("net.coderbot.iris") != std::string::npos ||
               rawClassName.find("me.jellysquid.mods.sodium") != std::string::npos)) {
        if (prevStage < 9) {
            newStage = 9;
            newStageTitle = "TAHAP 9/10: Membuka Jendela GLFW & Konteks OpenGL ES (GL4ES 1.1.5)...";
            newDetail = "Menginisialisasi pipeline grafis Blaze3D, Sodium & Shader GLSL...";
            isMilestoneClass = true;
        } else if (rawClassName == "com.mojang.blaze3d.platform.GLX" ||
                   rawClassName == "com.mojang.blaze3d.platform.GlStateManager" ||
                   rawClassName == "org.lwjgl.glfw.GLFW" ||
                   rawClassName == "org.lwjgl.opengl.GL" ||
                   rawClassName == "org.lwjgl.opengl.GLCapabilities" ||
                   rawClassName == "net.minecraft.class_1041") {
            newStageTitle = "TAHAP 9/10: Membuka Jendela GLFW & Konteks OpenGL ES (GL4ES 1.1.5)...";
            newDetail = "Sub-tahap Grafis: Menyiapkan GLFW Window, Konteks EGL & OpenGL Capabilities...";
            isMilestoneClass = true;
        }
    } else if (rawClassName.find("net.minecraft.client.Minecraft") != std::string::npos ||
               rawClassName.find("net.minecraft.class_310") != std::string::npos ||
               rawClassName.find("net.minecraft.client.main.Main") != std::string::npos ||
               rawClassName.find("net.minecraft.client.Options") != std::string::npos) {
        if (prevStage < 8) {
            newStage = 8;
            newStageTitle = "TAHAP 8/10: Menginisialisasi Klien Minecraft, Audio OpenAL & Manajer Aset...";
            newDetail = "Menyiapkan instance klien Minecraft 1.20.4, profil pemain & opsi...";
            isMilestoneClass = true;
        }
    } else if (rawClassName.find("net.minecraft.server.Bootstrap") != std::string::npos ||
               rawClassName.find("net.minecraft.core.registries") != std::string::npos ||
               rawClassName.find("net.minecraft.world.level.block.Blocks") != std::string::npos ||
               rawClassName.find("net.minecraft.world.item.Items") != std::string::npos ||
               rawClassName.find("com.mojang.datafixers") != std::string::npos) {
        if (prevStage < 7) {
            newStage = 7;
            newStageTitle = "TAHAP 7/10: Bootstrap Minecraft 1.20.4 — Registri Blok, Item & DataFixer...";
            newDetail = "Mendaftarkan ribuan Blok, Item, Entitas, Bioma & skema DataFixer...";
            isMilestoneClass = true;
        }
    } else if (rawClassName.find("org.spongepowered.asm.mixin.transformer") != std::string::npos ||
               rawClassName.find("org.spongepowered.asm.mixin.MixinEnvironment") != std::string::npos) {
        if (prevStage < 6) {
            newStage = 6;
            newStageTitle = "TAHAP 6/10: Mixin Engine — Menyuntikkan Optimasi Bytecode Mod...";
            newDetail = "Menerapkan patch Mixin (Sodium, Lithium, FerriteCore, ImmediatelyFast, ModernFix)...";
            isMilestoneClass = true;
        }
    } else if (rawClassName.find("net.fabricmc.loader.impl.discovery") != std::string::npos ||
               rawClassName.find("net.fabricmc.loader.impl.FabricLoaderImpl") != std::string::npos ||
               rawClassName.find("net.fabricmc.loader.impl.lib.sat4j") != std::string::npos) {
        if (prevStage < 5) {
            newStage = 5;
            newStageTitle = "TAHAP 5/10: Fabric Loader 0.16.9 — Memindai 49 JAR & 9 Mod Optimasi...";
            newDetail = "Memvalidasi dependensi mod Fabric (.jar) dan membangun pohon classpath...";
            isMilestoneClass = true;
        }
    } else if (rawClassName.find("net.fabricmc.loader.impl.launch.knot.Knot") != std::string::npos ||
               rawClassName.find("org.apache.logging.log4j") != std::string::npos) {
        if (prevStage < 4) {
            newStage = 4;
            newStageTitle = "TAHAP 4/10: Memuat KnotClient & Mengaktifkan Sistem Log4j2...";
            newDetail = "Menginisialisasi ClassLoader KnotClient dan konfigurasi logger Minecraft...";
            isMilestoneClass = true;
        }
    }

    if (newStage > prevStage) {
        st->bootStageIndex.store(newStage, std::memory_order_relaxed);
    }

    std::string displayClass = rawClassName;
    if (displayClass.size() > 58) {
        displayClass = "..." + displayClass.substr(displayClass.size() - 55);
    }
    setTelemetryStrings(st, newStageTitle, newDetail, displayClass.c_str());

    if (isMilestoneClass || (count % 220 == 0)) {
        emitBootProgressLogLine(st, isMilestoneClass);
    }
}

void inspectStdoutLineForMilestones(const std::string& line) {
    SharedEngineState* st = getSharedState();
    if (line.find("Loading Minecraft") != std::string::npos) {
        if (st->bootStageIndex.load(std::memory_order_relaxed) < 5) {
            st->bootStageIndex.store(5, std::memory_order_relaxed);
        }
        setTelemetryStrings(
            st,
            "TAHAP 5/10: Fabric Loader 0.16.9 — Memuat Minecraft 1.20.4 & 9 Mod...",
            line.c_str(),
            "net.fabricmc.loader.impl.FabricLoaderImpl"
        );
        emitBootProgressLogLine(st, true);
    } else if (line.find("SpongePowered MIXIN") != std::string::npos) {
        if (st->bootStageIndex.load(std::memory_order_relaxed) < 6) {
            st->bootStageIndex.store(6, std::memory_order_relaxed);
        }
        setTelemetryStrings(
            st,
            "TAHAP 6/10: Sub-sistem SpongePowered Mixin Aktif — Menyuntikkan Mod...",
            line.c_str(),
            "org.spongepowered.asm.mixin.MixinEnvironment"
        );
        emitBootProgressLogLine(st, true);
    } else if (line.find("Backend library: LWJGL") != std::string::npos) {
        if (st->bootStageIndex.load(std::memory_order_relaxed) < 9) {
            st->bootStageIndex.store(9, std::memory_order_relaxed);
        }
        setTelemetryStrings(
            st,
            "TAHAP 9/10: Backend LWJGL 3.3.2 Aktif — Menyiapkan Jendela OpenGL ES...",
            line.c_str(),
            "com.mojang.blaze3d.systems.RenderSystem"
        );
        emitBootProgressLogLine(st, true);
    } else if (line.find("OpenAL initialized") != std::string::npos ||
               line.find("Created: ") != std::string::npos) {
        st->mojangSplashReached.store(true, std::memory_order_relaxed);
        st->etaMojangSplashSec.store(0, std::memory_order_relaxed);
        st->bootStageIndex.store(10, std::memory_order_relaxed);
        setTelemetryStrings(
            st,
            "TAHAP 10/10: LAYAR SPLASH MOJANG AKTIF — Memuat Atlas Tekstur & Audio...",
            line.c_str(),
            "net.minecraft.client.renderer.texture.TextureAtlas"
        );
        emitBootProgressLogLine(st, true);
    }
}

int JNICALL jvmVfprintfHook(FILE* /*fp*/, const char* format, va_list args) {
    if (!format) return 0;
    char buf[2048];
    int written = std::vsnprintf(buf, sizeof(buf), format, args);
    if (written <= 0) return written;

    std::string text(buf);
    auto srcPos = text.find(" source: ");
    if (srcPos != std::string::npos) {
        std::string clsName = text.substr(0, srcPos);
        auto lastBracket = clsName.find_last_of(']');
        if (lastBracket != std::string::npos && lastBracket + 1 < clsName.size()) {
            clsName = clsName.substr(lastBracket + 1);
        }
        while (!clsName.empty() && (clsName.front() == ' ' || clsName.front() == '\t')) {
            clsName.erase(clsName.begin());
        }
        if (!clsName.empty()) {
            onJvmClassLoaded(clsName);
        }
        return written;
    }

    while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) {
        text.pop_back();
    }
    if (!text.empty()) {
        inspectStdoutLineForMilestones(text);
        appendNativeLineToLogFile("JVM-VM", text);
    }
    return written;
}

void drainStdioPipeNonBlocking() {
    SharedEngineState* st = getSharedState();
    if (st->stdioPipe[0] < 0) return;
    int flags = fcntl(st->stdioPipe[0], F_GETFL, 0);
    fcntl(st->stdioPipe[0], F_SETFL, flags | O_NONBLOCK);

    char buf[1024];
    std::string lineBuffer;
    ssize_t n = 0;
    while ((n = read(st->stdioPipe[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        for (ssize_t i = 0; i < n; ++i) {
            char c = buf[i];
            if (c == '\n') {
                if (!lineBuffer.empty()) {
                    inspectStdoutLineForMilestones(lineBuffer);
                    appendNativeLineToLogFile("JVM-OUT", lineBuffer);
                    lineBuffer.clear();
                }
            } else if (c != '\r') {
                lineBuffer.push_back(c);
            }
        }
    }
    if (!lineBuffer.empty()) {
        inspectStdoutLineForMilestones(lineBuffer);
        appendNativeLineToLogFile("JVM-OUT", lineBuffer);
    }
    fcntl(st->stdioPipe[0], F_SETFL, flags);
}

void JNICALL jvmExitHook(jint code) {
    std::fflush(stdout);
    std::fflush(stderr);
    drainStdioPipeNonBlocking();
    SharedEngineState* st = getSharedState();
    appendNativeLineToLogFile(
        "EXIT-HOOK",
        "HotSpot VM memanggil exit(" + std::to_string(code) + ") — ditangani dengan aman tanpa menutup aplikasi."
    );
    signalJvmFinished(st, code == 0 ? 1000 : code);
    pthread_exit(reinterpret_cast<void*>(static_cast<intptr_t>(code)));
}

void dumpNewestHsErrLogIfPresent() {
    DIR* dir = opendir(".");
    if (!dir) return;
    std::string newestName;
    time_t newestMtime = 0;
    struct dirent* ent = nullptr;
    while ((ent = readdir(dir)) != nullptr) {
        std::string name = ent->d_name;
        if (name.rfind("hs_err_pid", 0) == 0 && name.size() > 14) {
            struct stat stBuf{};
            if (stat(name.c_str(), &stBuf) == 0 && stBuf.st_mtime >= newestMtime) {
                newestMtime = stBuf.st_mtime;
                newestName = name;
            }
        }
    }
    closedir(dir);
    if (newestName.empty()) return;

    std::ifstream hsFile(newestName);
    if (!hsFile.is_open()) return;
    appendNativeLineToLogFile("HS-ERR", "=== Ringkasan " + newestName + " ===");
    std::string line;
    int lineCount = 0;
    while (std::getline(hsFile, line) && lineCount < 85) {
        if (!line.empty()) {
            appendNativeLineToLogFile("HS-ERR", line);
        }
        lineCount++;
    }
}

void JNICALL jvmAbortHook() {
    std::fflush(stdout);
    std::fflush(stderr);
    drainStdioPipeNonBlocking();
    dumpNewestHsErrLogIfPresent();
    SharedEngineState* st = getSharedState();
    appendNativeLineToLogFile(
        "ABORT-HOOK",
        "HotSpot VM melaporkan kondisi abort() — periksa detail pesan JVM-VM di atas."
    );
    signalJvmFinished(st, -6);
    pthread_exit(reinterpret_cast<void*>(static_cast<intptr_t>(-6)));
}

void* stdioPipeReaderThread(void* /*arg*/) {
    SharedEngineState* st = getSharedState();
    char buf[1024];
    std::string lineBuffer;
    ssize_t n = 0;
    while ((n = read(st->stdioPipe[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        for (ssize_t i = 0; i < n; ++i) {
            char c = buf[i];
            if (c == '\n') {
                if (!lineBuffer.empty()) {
                    inspectStdoutLineForMilestones(lineBuffer);
                    appendNativeLineToLogFile("JVM-OUT", lineBuffer);
                    lineBuffer.clear();
                }
            } else if (c != '\r') {
                lineBuffer.push_back(c);
            }
        }
    }
    if (!lineBuffer.empty()) {
        inspectStdoutLineForMilestones(lineBuffer);
        appendNativeLineToLogFile("JVM-OUT", lineBuffer);
    }
    return nullptr;
}

void ensureStdioRedirectedToLog() {
    SharedEngineState* st = getSharedState();
    bool expected = false;
    if (!st->stdioRedirected.compare_exchange_strong(expected, true)) {
        return;
    }
    if (pipe(st->stdioPipe) == 0) {
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        dup2(st->stdioPipe[1], STDOUT_FILENO);
        dup2(st->stdioPipe[1], STDERR_FILENO);

        pthread_t tid;
        pthread_create(&tid, nullptr, stdioPipeReaderThread, nullptr);
        pthread_detach(tid);
        appendNativeLineToLogFile("BOOT", "Pipa stdout/stderr native dialihkan ke latest.log & Konsol Boot Live.");
    }
}

void updateAndroidLinkerSearchPath(const std::string& ldPath) {
    void* libdl = dlopen("libdl.so", RTLD_LAZY);
    if (!libdl) return;
    auto updateFn = reinterpret_cast<android_update_LD_LIBRARY_PATH_t>(
        dlsym(libdl, "android_update_LD_LIBRARY_PATH")
    );
    if (!updateFn) {
        updateFn = reinterpret_cast<android_update_LD_LIBRARY_PATH_t>(
            dlsym(libdl, "__loader_android_update_LD_LIBRARY_PATH")
        );
    }
    if (updateFn) {
        updateFn(ldPath.c_str());
        appendNativeLineToLogFile("LINKER", "Berhasil memperbarui jalur pencarian linker Bionic Android.");
    }
}

void resetSignalsForHotSpot() {
    struct sigaction cleanSa{};
    std::memset(&cleanSa, 0, sizeof(struct sigaction));
    for (int sigid = SIGHUP; sigid < NSIG; ++sigid) {
        if (sigid == SIGKILL || sigid == SIGSTOP || sigid == SIGCHLD) continue;
        if (sigid == SIGSEGV) {
            cleanSa.sa_handler = SIG_IGN;
        } else {
            cleanSa.sa_handler = SIG_DFL;
        }
        sigaction(sigid, &cleanSa, nullptr);
    }
}

int32_t probeMaxContiguousMmapMb() {
    for (int32_t testMb = 1536; testMb >= 192; testMb -= 32) {
        size_t bytes = static_cast<size_t>(testMb) * 1024ULL * 1024ULL;
        void* ptr = mmap(nullptr, bytes, PROT_NONE, MAP_PRIVATE | MAP_NORESERVE | MAP_ANONYMOUS, -1, 0);
        if (ptr != MAP_FAILED && ptr != nullptr) {
            munmap(ptr, bytes);
            return testMb;
        }
    }
    return 192;
}

void pinThreadToPerformanceCores() {
    const int numCores = sysconf(_SC_NPROCESSORS_CONF);
    if (numCores <= 4) return;

    std::vector<int> maxFreqs(numCores, 0);
    int highestFreq = 0;

    for (int i = 0; i < numCores; ++i) {
        std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq";
        std::ifstream freqFile(path);
        if (freqFile.is_open()) {
            freqFile >> maxFreqs[i];
            if (maxFreqs[i] > highestFreq) {
                highestFreq = maxFreqs[i];
            }
        }
    }

    if (highestFreq <= 0) return;

    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int pinnedCount = 0;

    const int threshold = static_cast<int>(highestFreq * 0.78);
    for (int i = 0; i < numCores; ++i) {
        if (maxFreqs[i] >= threshold) {
            CPU_SET(i, &cpuset);
            pinnedCount++;
        }
    }

    if (pinnedCount >= 2) {
        if (sched_setaffinity(0, sizeof(cpu_set_t), &cpuset) == 0) {
            appendNativeLineToLogFile(
                "CPU",
                "Thread utama JVM dipasang ke " + std::to_string(pinnedCount) + " core CPU performa tinggi."
            );
        }
    }
}

JNIEnv* getAttachedRuntimeEnv() {
    SharedEngineState* st = getSharedState();
    JavaVM* vm = st->runtimeJavaVM.load(std::memory_order_acquire);
    if (!vm) return nullptr;
    JNIEnv* env = nullptr;
    jint res = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4);
    if (res == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

void logJavaException(JNIEnv* env, const char* where);

static jlong ndlopen_bugfix(JNIEnv* /*env*/, jclass /*cls*/, jlong filename_ptr, jint jmode) {
    const char* filename = reinterpret_cast<const char*>(filename_ptr);
    if (!filename) return 0;

    // Never allow fallback to Android Bionic's /system/lib/libjemalloc.so
    // (which lacks je_malloc and causes recursive SharedLibraryUtil -> MemoryUtil.<clinit> NPE).
    if (std::strstr(filename, "jemalloc") != nullptr) {
        return 0;
    }

    SharedEngineState* st = getSharedState();
    void* handle = nullptr;

    // Prefer bundled Android natives inside st->nativesDirPath first for bare filenames!
    if (st->nativesDirPath[0] != '\0' && std::strchr(filename, '/') == nullptr) {
        std::string fullPath = std::string(st->nativesDirPath) + "/" + filename;
        handle = dlopen(fullPath.c_str(), static_cast<int>(jmode) | RTLD_GLOBAL);
        if (!handle) {
            handle = dlopen(fullPath.c_str(), static_cast<int>(jmode));
        }
    }
    if (!handle) {
        handle = dlopen(filename, static_cast<int>(jmode) | RTLD_GLOBAL);
    }
    if (!handle) {
        handle = dlopen(filename, static_cast<int>(jmode));
    }
    if (handle) {
        appendNativeLineToLogFile(
            "NDLOPEN",
            std::string("Pustaka native LWJGL dimuat: ") + filename
        );
    } else {
        const char* err = dlerror();
        appendNativeLineToLogFile(
            "NDLOPEN-WARN",
            std::string("Gagal memuat ") + filename + ": " + (err ? err : "unknown")
        );
    }
    return reinterpret_cast<jlong>(handle);
}

/**
 * Registers `DynamicLinkLoader.ndlopen` inside the active caller ClassLoader (`KnotClassLoader`)
 * and pre-initializes `APIUtil`, `MemoryUtil`, and `MemoryUtil$LazyInit` (`StdlibAllocator`).
 *
 * IMPORTANT: Never call this from `jvmDirectMainThread` before `KnotClient.main()` starts,
 * because JNI `FindClass` with no Java caller frame on the stack defaults to `AppClassLoader`,
 * which would load `liblwjgl.so` in `AppClassLoader` and cause `KnotClassLoader` to fail with
 * `UnsatisfiedLinkError: Native Library .../liblwjgl.so already loaded in another classloader`!
 */
void registerLwjglDlopenHookOnly(JNIEnv* vmEnv) {
    if (!vmEnv) return;
    jclass dynLoader = vmEnv->FindClass("org/lwjgl/system/linux/DynamicLinkLoader");
    if (dynLoader) {
        JNINativeMethod m[] = {
            {const_cast<char*>("ndlopen"), const_cast<char*>("(JI)J"), reinterpret_cast<void*>(&ndlopen_bugfix)}
        };
        vmEnv->RegisterNatives(dynLoader, m, 1);
    }
    if (vmEnv->ExceptionCheck()) {
        logJavaException(vmEnv, "DynamicLinkLoader.<clinit>");
    }

    jclass apiUtilCls = vmEnv->FindClass("org/lwjgl/system/APIUtil");
    if (!apiUtilCls || vmEnv->ExceptionCheck()) {
        logJavaException(vmEnv, "APIUtil.<clinit>");
    }

    jclass memUtilCls = vmEnv->FindClass("org/lwjgl/system/MemoryUtil");
    if (!memUtilCls || vmEnv->ExceptionCheck()) {
        logJavaException(vmEnv, "MemoryUtil.<clinit>");
    }

    jclass memLazyCls = vmEnv->FindClass("org/lwjgl/system/MemoryUtil$LazyInit");
    if (!memLazyCls || vmEnv->ExceptionCheck()) {
        logJavaException(vmEnv, "MemoryUtil$LazyInit.<clinit>");
    } else {
        appendNativeLineToLogFile(
            "BOOT",
            "LWJGL 3 MemoryUtil & StdlibAllocator berhasil diinisialisasi di KnotClassLoader."
        );
    }
}

/**
 * Binds `org.lwjgl.glfw.GLFW` buffers and monitor methods inside `KnotClassLoader`.
 */
void bindGlfwBuffersAndMethods(JNIEnv* vmEnv) {
    if (!vmEnv) return;
    SharedEngineState* st = getSharedState();
    registerLwjglDlopenHookOnly(vmEnv);

    if (!st->vmGlfwClass || !st->glfwKeyDownBuffer || !st->glfwMouseDownBuffer) {
        jclass glfwCls = vmEnv->FindClass("org/lwjgl/glfw/GLFW");
        if (glfwCls) {
            if (!st->vmGlfwClass) {
                st->vmGlfwClass = reinterpret_cast<jclass>(vmEnv->NewGlobalRef(glfwCls));
            }
            st->methodInternalChangeMonitorSize = vmEnv->GetStaticMethodID(
                st->vmGlfwClass, "internalChangeMonitorSize", "(II)V"
            );
            st->methodInternalWindowSizeChanged = vmEnv->GetStaticMethodID(
                st->vmGlfwClass, "internalWindowSizeChanged", "(J)V"
            );
            jfieldID fKey = vmEnv->GetStaticFieldID(st->vmGlfwClass, "keyDownBuffer", "Ljava/nio/ByteBuffer;");
            if (fKey) {
                jobject keyBuf = vmEnv->GetStaticObjectField(st->vmGlfwClass, fKey);
                if (keyBuf) {
                    st->glfwKeyDownBuffer = reinterpret_cast<jbyte*>(vmEnv->GetDirectBufferAddress(keyBuf));
                }
            }
            jfieldID fMouse = vmEnv->GetStaticFieldID(st->vmGlfwClass, "mouseDownBuffer", "Ljava/nio/ByteBuffer;");
            if (fMouse) {
                jobject mouseBuf = vmEnv->GetStaticObjectField(st->vmGlfwClass, fMouse);
                if (mouseBuf) {
                    st->glfwMouseDownBuffer = reinterpret_cast<jbyte*>(vmEnv->GetDirectBufferAddress(mouseBuf));
                }
            }
            int w = st->surfaceWidth.load(std::memory_order_relaxed);
            int h = st->surfaceHeight.load(std::memory_order_relaxed);
            if (w > 0 && h > 0 && st->methodInternalChangeMonitorSize) {
                vmEnv->CallStaticVoidMethod(st->vmGlfwClass, st->methodInternalChangeMonitorSize, w, h);
            }
        }
        vmEnv->ExceptionClear();
    }
}

void logJavaException(JNIEnv* env, const char* where) {
    if (!env || !env->ExceptionCheck()) return;
    jthrowable exc = env->ExceptionOccurred();
    env->ExceptionClear();
    if (!exc) return;

    jclass objCls = env->FindClass("java/lang/Object");
    jmethodID toStr = env->GetMethodID(objCls, "toString", "()Ljava/lang/String;");
    jclass thrCls = env->FindClass("java/lang/Throwable");
    jmethodID getStack = env->GetMethodID(thrCls, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
    jmethodID getCause = env->GetMethodID(thrCls, "getCause", "()Ljava/lang/Throwable;");

    jthrowable current = exc;
    int depth = 0;
    while (current != nullptr && depth < 5) {
        jstring msgStr = reinterpret_cast<jstring>(env->CallObjectMethod(current, toStr));
        if (msgStr) {
            const char* utf = env->GetStringUTFChars(msgStr, nullptr);
            std::string prefix = (depth == 0)
                ? (std::string("Exception di ") + where + ": ")
                : (std::string("Caused by [") + std::to_string(depth) + "]: ");
            appendNativeLineToLogFile("JAVA-ERR", prefix + (utf ? utf : ""));
            env->ReleaseStringUTFChars(msgStr, utf);
            env->DeleteLocalRef(msgStr);
        }

        if (getStack) {
            auto frames = reinterpret_cast<jobjectArray>(env->CallObjectMethod(current, getStack));
            if (frames) {
                jsize len = std::min<jsize>(env->GetArrayLength(frames), 18);
                for (jsize i = 0; i < len; ++i) {
                    jobject elem = env->GetObjectArrayElement(frames, i);
                    if (elem) {
                        jstring fStr = reinterpret_cast<jstring>(env->CallObjectMethod(elem, toStr));
                        if (fStr) {
                            const char* fUtf = env->GetStringUTFChars(fStr, nullptr);
                            appendNativeLineToLogFile("JAVA-STACK", std::string("  at ") + (fUtf ? fUtf : ""));
                            env->ReleaseStringUTFChars(fStr, fUtf);
                            env->DeleteLocalRef(fStr);
                        }
                        env->DeleteLocalRef(elem);
                    }
                }
                env->DeleteLocalRef(frames);
            }
        }

        jthrowable nextCause = getCause
            ? reinterpret_cast<jthrowable>(env->CallObjectMethod(current, getCause))
            : nullptr;
        if (nextCause && env->IsSameObject(current, nextCause)) {
            env->DeleteLocalRef(nextCause);
            nextCause = nullptr;
        }
        if (current != exc) {
            env->DeleteLocalRef(current);
        }
        current = nextCause;
        depth++;
    }
    env->ExceptionClear();
    env->DeleteLocalRef(exc);
}

void* jvmHeartbeatMonitorThread(void* /*arg*/) {
    SharedEngineState* st = getSharedState();
    JavaVM* vm = st->runtimeJavaVM.load(std::memory_order_acquire);
    if (!vm) return nullptr;

    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK || !env) {
        return nullptr;
    }

    jclass runtimeCls = env->FindClass("java/lang/Runtime");
    jmethodID getRuntimeMid = runtimeCls
        ? env->GetStaticMethodID(runtimeCls, "getRuntime", "()Ljava/lang/Runtime;")
        : nullptr;
    jobject runtimeObj = (runtimeCls && getRuntimeMid)
        ? env->CallStaticObjectMethod(runtimeCls, getRuntimeMid)
        : nullptr;
    jmethodID totalMemMid = runtimeCls ? env->GetMethodID(runtimeCls, "totalMemory", "()J") : nullptr;
    jmethodID freeMemMid  = runtimeCls ? env->GetMethodID(runtimeCls, "freeMemory", "()J")  : nullptr;
    jmethodID maxMemMid   = runtimeCls ? env->GetMethodID(runtimeCls, "maxMemory", "()J")   : nullptr;
    env->ExceptionClear();

    while (st->jvmRunning.load(std::memory_order_acquire)) {
        usleep(1800 * 1000);
        if (!st->jvmRunning.load(std::memory_order_acquire)) break;

        if (runtimeObj && totalMemMid && freeMemMid && maxMemMid) {
            jlong totalB = env->CallLongMethod(runtimeObj, totalMemMid);
            jlong freeB  = env->CallLongMethod(runtimeObj, freeMemMid);
            jlong maxB   = env->CallLongMethod(runtimeObj, maxMemMid);
            env->ExceptionClear();

            int32_t usedMb = static_cast<int32_t>(std::max<jlong>(1L, (totalB - freeB) / (1024L * 1024L)));
            int32_t maxMb  = static_cast<int32_t>(std::max<jlong>(64L, maxB / (1024L * 1024L)));
            st->jvmUsedRamMb.store(usedMb, std::memory_order_relaxed);
            st->jvmMaxRamMb.store(maxMb, std::memory_order_relaxed);
        }

        if (!st->titleScreenReached.load(std::memory_order_relaxed)) {
            int32_t classes = st->loadedClassCount.load(std::memory_order_relaxed);
            recalculateEtaAndProgress(st, classes);
            emitBootProgressLogLine(st, false);
        }
    }

    vm->DetachCurrentThread();
    return nullptr;
}

struct DirectJvmLaunchContext {
    CreateJavaVM_t createJavaVM = nullptr;
    std::vector<std::string> vmOptionStrings;
    std::string mainClassName;
    std::vector<std::string> appArgs;
};

void* jvmDirectMainThread(void* rawCtx) {
    auto* ctx = reinterpret_cast<DirectJvmLaunchContext*>(rawCtx);
    SharedEngineState* st = getSharedState();

    pinThreadToPerformanceCores();
    resetSignalsForHotSpot();

    std::vector<JavaVMOption> options;
    options.reserve(ctx->vmOptionStrings.size() + 4);

    JavaVMOption optVfprintf{};
    optVfprintf.optionString = const_cast<char*>("vfprintf");
    optVfprintf.extraInfo = reinterpret_cast<void*>(&jvmVfprintfHook);
    options.push_back(optVfprintf);

    JavaVMOption optExit{};
    optExit.optionString = const_cast<char*>("exit");
    optExit.extraInfo = reinterpret_cast<void*>(&jvmExitHook);
    options.push_back(optExit);

    JavaVMOption optAbort{};
    optAbort.optionString = const_cast<char*>("abort");
    optAbort.extraInfo = reinterpret_cast<void*>(&jvmAbortHook);
    options.push_back(optAbort);

    for (auto& s : ctx->vmOptionStrings) {
        JavaVMOption opt{};
        opt.optionString = const_cast<char*>(s.c_str());
        opt.extraInfo = nullptr;
        options.push_back(opt);
    }

    JavaVMInitArgs vmInitArgs{};
    vmInitArgs.version = JNI_VERSION_1_6;
    vmInitArgs.nOptions = static_cast<jint>(options.size());
    vmInitArgs.options = options.data();
    vmInitArgs.ignoreUnrecognized = JNI_TRUE;

    st->bootStageIndex.store(3, std::memory_order_relaxed);
    st->bootPercent.store(24, std::memory_order_relaxed);
    setTelemetryStrings(
        st,
        "TAHAP 3/10: Menginisialisasi Mesin Virtual OpenJDK 17 HotSpot (JNI_CreateJavaVM)...",
        "Mengalokasikan memori Java Heap & memuat modul sistem dasar (lib/modules)...",
        "JNI_CreateJavaVM"
    );
    emitBootProgressLogLine(st, true);

    st->jvmRunning.store(true, std::memory_order_release);

    const int64_t vmStartMs = currentTimeMillis();
    JavaVM* vm = nullptr;
    JNIEnv* vmEnv = nullptr;
    jint createRes = ctx->createJavaVM(&vm, reinterpret_cast<void**>(&vmEnv), &vmInitArgs);
    const int64_t vmElapsedMs = currentTimeMillis() - vmStartMs;

    if (createRes != JNI_OK || vm == nullptr || vmEnv == nullptr) {
        appendNativeLineToLogFile(
            "FATAL",
            "JNI_CreateJavaVM gagal dengan kode: " + std::to_string(createRes)
        );
        signalJvmFinished(st, createRes != 0 ? createRes : -3);
        delete ctx;
        return nullptr;
    }

    st->runtimeJavaVM.store(vm, std::memory_order_release);
    appendNativeLineToLogFile(
        "BOOT",
        "OpenJDK 17 HotSpot VM aktif dalam " + std::to_string(vmElapsedMs) +
            " ms! (Kelas dasar dimuat: " + std::to_string(st->loadedClassCount.load()) + " kelas)"
    );

    // NOTE: Do NOT call vmEnv->FindClass("org/lwjgl/...") here in jvmDirectMainThread!
    // At this point there is no Java frame on the stack, so JNI FindClass uses AppClassLoader,
    // which would load liblwjgl.so in AppClassLoader and cause KnotClassLoader to throw
    // UnsatisfiedLinkError: Native Library .../liblwjgl.so already loaded in another classloader!
    // Instead, LWJGL is initialized inside KnotClassLoader via VeloCraftBridgeMod.onPreLaunch() & JNI_OnLoad.

    pthread_t hbTid;
    if (pthread_create(&hbTid, nullptr, jvmHeartbeatMonitorThread, nullptr) == 0) {
        pthread_detach(hbTid);
    }

    st->bootStageIndex.store(4, std::memory_order_relaxed);
    st->bootPercent.store(32, std::memory_order_relaxed);
    setTelemetryStrings(
        st,
        "TAHAP 4/10: Memuat Kelas Utama Fabric Loader (KnotClient)...",
        ("Memuat " + ctx->mainClassName + " melalui ClassLoader sistem...").c_str(),
        ctx->mainClassName.c_str()
    );
    emitBootProgressLogLine(st, true);

    jclass mainCls = nullptr;
    jclass helperCls = vmEnv->FindClass("sun/launcher/LauncherHelper");
    if (helperCls != nullptr) {
        jmethodID checkAndLoad = vmEnv->GetStaticMethodID(
            helperCls,
            "checkAndLoadMain",
            "(ZILjava/lang/String;)Ljava/lang/Class;"
        );
        if (checkAndLoad != nullptr) {
            jstring jMainStr = vmEnv->NewStringUTF(ctx->mainClassName.c_str());
            mainCls = reinterpret_cast<jclass>(
                vmEnv->CallStaticObjectMethod(helperCls, checkAndLoad, JNI_TRUE, 1, jMainStr)
            );
            vmEnv->DeleteLocalRef(jMainStr);
        }
    }
    if (vmEnv->ExceptionCheck()) {
        logJavaException(vmEnv, "LauncherHelper.checkAndLoadMain");
    }

    if (mainCls == nullptr) {
        std::string slashName = ctx->mainClassName;
        for (char& c : slashName) {
            if (c == '.') c = '/';
        }
        mainCls = vmEnv->FindClass(slashName.c_str());
        if (vmEnv->ExceptionCheck()) {
            logJavaException(vmEnv, "FindClass(KnotClient)");
        }
    }

    if (mainCls == nullptr) {
        appendNativeLineToLogFile("FATAL", "Tidak dapat menemukan kelas utama: " + ctx->mainClassName);
        signalJvmFinished(st, -4);
        delete ctx;
        return nullptr;
    }

    jmethodID mainMethod = vmEnv->GetStaticMethodID(mainCls, "main", "([Ljava/lang/String;)V");
    if (vmEnv->ExceptionCheck() || mainMethod == nullptr) {
        logJavaException(vmEnv, "GetStaticMethodID(main)");
        signalJvmFinished(st, -5);
        delete ctx;
        return nullptr;
    }

    jclass stringCls = vmEnv->FindClass("java/lang/String");
    jobjectArray mainArgsArray = vmEnv->NewObjectArray(
        static_cast<jsize>(ctx->appArgs.size()),
        stringCls,
        vmEnv->NewStringUTF("")
    );
    for (size_t i = 0; i < ctx->appArgs.size(); ++i) {
        jstring argStr = vmEnv->NewStringUTF(ctx->appArgs[i].c_str());
        vmEnv->SetObjectArrayElement(mainArgsArray, static_cast<jsize>(i), argStr);
        vmEnv->DeleteLocalRef(argStr);
    }

    appendNativeLineToLogFile(
        "BOOT",
        "Memulai eksekusi " + ctx->mainClassName + ".main() dengan " +
            std::to_string(ctx->appArgs.size()) + " argumen aplikasi..."
    );

    vmEnv->CallStaticVoidMethod(mainCls, mainMethod, mainArgsArray);

    int exitCode = 0;
    if (vmEnv->ExceptionCheck()) {
        logJavaException(vmEnv, "KnotClient.main");
        exitCode = 1;
    }

    signalJvmFinished(st, exitCode == 0 ? 1000 : exitCode);
    vm->DetachCurrentThread();
    delete ctx;
    return nullptr;
}

static void gl4esGetMainFbSize(int* width, int* height) {
    SharedEngineState* st = getSharedState();
    EGLint qw = 0;
    EGLint qh = 0;
    if (st->eglDisplay != EGL_NO_DISPLAY && st->eglSurface != EGL_NO_SURFACE) {
        if (eglQuerySurface(st->eglDisplay, st->eglSurface, EGL_WIDTH, &qw) == EGL_TRUE &&
            eglQuerySurface(st->eglDisplay, st->eglSurface, EGL_HEIGHT, &qh) == EGL_TRUE &&
            qw > 0 && qh > 0) {
            if (width) *width = qw;
            if (height) *height = qh;
            return;
        }
    }
    int sw = st->surfaceWidth.load(std::memory_order_relaxed);
    int sh = st->surfaceHeight.load(std::memory_order_relaxed);
    if (width) *width = (sw > 0) ? sw : 1280;
    if (height) *height = (sh > 0) ? sh : 720;
}

} // anonymous namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    SharedEngineState* st = getSharedState();
    if (st->dalvikJavaVM == nullptr) {
        st->dalvikJavaVM = vm;
    } else if (st->dalvikJavaVM != vm) {
        st->runtimeJavaVM.store(vm, std::memory_order_release);
        JNIEnv* vmEnv = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&vmEnv), JNI_VERSION_1_4) == JNI_OK && vmEnv) {
            appendNativeLineToLogFile("GLFW", "JNI_OnLoad dipanggil oleh OpenJDK HotSpot VM (KnotClassLoader).");
            bindGlfwBuffersAndMethods(vmEnv);
        }
    }
    return JNI_VERSION_1_6;
}

// =========================================================================================
// PojavExec / LWJGL3-Android GLFW C Symbols (Loaded by org.lwjgl.glfw.GLFW$Functions)
// =========================================================================================

__attribute__((visibility("default"), used))
int pojavInit() {
    SharedEngineState* st = getSharedState();
    st->bootStageIndex.store(9, std::memory_order_relaxed);
    st->bootPercent.store(std::max(90, st->bootPercent.load(std::memory_order_relaxed)), std::memory_order_relaxed);
    setTelemetryStrings(
        st,
        "TAHAP 9/10: Menginisialisasi Jendela EGL & Layar OpenGL ES...",
        "Menghubungkan tampilan EGL ke SurfaceView perangkat...",
        "org.lwjgl.glfw.GLFW.glfwInit"
    );
    emitBootProgressLogLine(st, true);

    st->eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (st->eglDisplay == EGL_NO_DISPLAY) {
        appendNativeLineToLogFile("EGL-ERR", "eglGetDisplay mengembalikan EGL_NO_DISPLAY");
        return 0;
    }
    if (eglInitialize(st->eglDisplay, nullptr, nullptr) != EGL_TRUE) {
        appendNativeLineToLogFile("EGL-ERR", "eglInitialize gagal");
        return 0;
    }

    int w = st->surfaceWidth.load(std::memory_order_relaxed);
    int h = st->surfaceHeight.load(std::memory_order_relaxed);
    ANativeWindow* win = st->nativeWindow.load(std::memory_order_acquire);
    if (win) {
        ANativeWindow_acquire(win);
        int nw = ANativeWindow_getWidth(win);
        int nh = ANativeWindow_getHeight(win);
        if (nw > 0 && nh > 0) {
            w = nw;
            h = nh;
            st->surfaceWidth.store(w, std::memory_order_relaxed);
            st->surfaceHeight.store(h, std::memory_order_relaxed);
        }
    }

    JNIEnv* vmEnv = getAttachedRuntimeEnv();
    if (vmEnv) {
        bindGlfwBuffersAndMethods(vmEnv);
        if (st->vmGlfwClass && st->methodInternalChangeMonitorSize) {
            vmEnv->CallStaticVoidMethod(st->vmGlfwClass, st->methodInternalChangeMonitorSize, w, h);
            vmEnv->ExceptionClear();
        }
    }
    appendNativeLineToLogFile(
        "EGL",
        "pojavInit() siap: resolusi jendela=" + std::to_string(w) + "x" + std::to_string(h)
    );
    return 1;
}

__attribute__((visibility("default"), used))
void* pojavCreateContext(void* contextSrc) {
    SharedEngineState* st = getSharedState();
    if (st->eglDisplay == EGL_NO_DISPLAY) {
        pojavInit();
    }
    const EGLint configAttribs[] = {
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    eglChooseConfig(st->eglDisplay, configAttribs, &st->eglConfig, 1, &numConfigs);
    eglGetConfigAttrib(st->eglDisplay, st->eglConfig, EGL_NATIVE_VISUAL_ID, &st->eglFormat);
    eglBindAPI(EGL_OPENGL_ES_API);

    const EGLint ctxAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };
    EGLContext shareCtx = contextSrc ? reinterpret_cast<EGLContext>(contextSrc) : EGL_NO_CONTEXT;
    st->eglContext = eglCreateContext(st->eglDisplay, st->eglConfig, shareCtx, ctxAttribs);
    appendNativeLineToLogFile("EGL", "Berhasil membuat konteks OpenGL ES (EGLContext) untuk jendela Minecraft.");
    return reinterpret_cast<void*>(st->eglContext ? st->eglContext : reinterpret_cast<void*>(1));
}

__attribute__((visibility("default"), used))
void* pojavGetCurrentContext() {
    return reinterpret_cast<void*>(getSharedState()->eglContext);
}

__attribute__((visibility("default"), used))
void pojavMakeCurrent(void* window) {
    SharedEngineState* st = getSharedState();
    if (window == nullptr) {
        eglMakeCurrent(st->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return;
    }
    if (st->eglSurface == EGL_NO_SURFACE) {
        ANativeWindow* win = st->nativeWindow.load(std::memory_order_acquire);
        if (win != nullptr) {
            ANativeWindow_setBuffersGeometry(win, 0, 0, st->eglFormat);
            st->eglSurface = eglCreateWindowSurface(st->eglDisplay, st->eglConfig, win, nullptr);
            appendNativeLineToLogFile("EGL", "ANativeWindow berhasil diikat ke EGLSurface utama.");
        } else {
            const EGLint pbufAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            st->eglSurface = eglCreatePbufferSurface(st->eglDisplay, st->eglConfig, pbufAttribs);
        }
    }
    EGLContext ctx = reinterpret_cast<EGLContext>(window);
    if (eglMakeCurrent(st->eglDisplay, st->eglSurface, st->eglSurface, ctx) == EGL_TRUE) {
        st->eglContext = ctx;
    }
}

__attribute__((visibility("default"), used))
void pojavSwapBuffers() {
    SharedEngineState* st = getSharedState();
    if (st->eglDisplay == EGL_NO_DISPLAY || st->eglContext == EGL_NO_CONTEXT) {
        return;
    }

    // Handle seamless background/foreground ANativeWindow swap without restarting JVM or losing GL state
    if (st->surfaceNeedsRebind.exchange(false, std::memory_order_acq_rel)) {
        if (st->pbufferSurface == EGL_NO_SURFACE) {
            const EGLint pbufAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            st->pbufferSurface = eglCreatePbufferSurface(st->eglDisplay, st->eglConfig, pbufAttribs);
        }
        eglMakeCurrent(st->eglDisplay, st->pbufferSurface, st->pbufferSurface, st->eglContext);
        if (st->eglSurface != EGL_NO_SURFACE && st->eglSurface != st->pbufferSurface) {
            eglDestroySurface(st->eglDisplay, st->eglSurface);
        }
        st->eglSurface = st->pbufferSurface;

        ANativeWindow* win = st->nativeWindow.load(std::memory_order_acquire);
        if (win != nullptr && !st->isBackgrounded.load(std::memory_order_acquire)) {
            int w = st->surfaceWidth.load(std::memory_order_relaxed);
            int h = st->surfaceHeight.load(std::memory_order_relaxed);
            ANativeWindow_setBuffersGeometry(win, w, h, st->eglFormat);
            EGLSurface newWinSurf = eglCreateWindowSurface(st->eglDisplay, st->eglConfig, win, nullptr);
            if (newWinSurf != EGL_NO_SURFACE) {
                if (eglMakeCurrent(st->eglDisplay, newWinSurf, newWinSurf, st->eglContext) == EGL_TRUE) {
                    st->eglSurface = newWinSurf;
                    st->windowSizeSyncCounter.store(0, std::memory_order_relaxed);
                    appendNativeLineToLogFile("EGL", "EGLSurface berhasil diikat ulang ke jendela aktif (Zero-Relog).");
                } else {
                    eglDestroySurface(st->eglDisplay, newWinSurf);
                }
            }
        }
    }

    if (st->isBackgrounded.load(std::memory_order_relaxed) || st->eglSurface == st->pbufferSurface) {
        usleep(25000);
        return;
    }

    if (st->eglSurface != EGL_NO_SURFACE) {
        if (eglSwapBuffers(st->eglDisplay, st->eglSurface) != EGL_TRUE) {
            EGLint err = eglGetError();
            if (err == EGL_BAD_SURFACE || err == EGL_BAD_NATIVE_WINDOW) {
                st->surfaceNeedsRebind.store(true, std::memory_order_release);
            }
            return;
        }
        int32_t frameNum = st->swapBuffersCount.fetch_add(1, std::memory_order_relaxed) + 1;
        if (frameNum == 1) {
            st->mojangSplashReached.store(true, std::memory_order_relaxed);
            st->etaMojangSplashSec.store(0, std::memory_order_relaxed);
            st->bootStageIndex.store(10, std::memory_order_relaxed);
            st->bootPercent.store(std::max(96, st->bootPercent.load(std::memory_order_relaxed)), std::memory_order_relaxed);
            setTelemetryStrings(
                st,
                "TAHAP 10/10: LAYAR SPLASH MOJANG STUDIOS TAMPIL DI LAYAR!",
                "Frame OpenGL pertama berhasil dirender — Memuat Atlas Tekstur & Model...",
                "org.lwjgl.glfw.GLFW.glfwSwapBuffers"
            );
            emitBootProgressLogLine(st, true);
        }
    }
}

__attribute__((visibility("default"), used))
void pojavSwapInterval(int interval) {
    SharedEngineState* st = getSharedState();
    if (st->eglDisplay != EGL_NO_DISPLAY) {
        eglSwapInterval(st->eglDisplay, interval);
    }
}

__attribute__((visibility("default"), used))
void pojavSetWindowHint(int /*hint*/, int /*value*/) {
}

__attribute__((visibility("default"), used))
void pojavTerminate() {
    SharedEngineState* st = getSharedState();
    if (st->eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(st->eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (st->eglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(st->eglDisplay, st->eglSurface);
            st->eglSurface = EGL_NO_SURFACE;
        }
        if (st->eglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(st->eglDisplay, st->eglContext);
            st->eglContext = EGL_NO_CONTEXT;
        }
        eglTerminate(st->eglDisplay);
        st->eglDisplay = EGL_NO_DISPLAY;
    }
}

__attribute__((visibility("default"), used))
void pojavStartPumping() {
}

__attribute__((visibility("default"), used))
void pojavPumpEvents(void* window) {
    SharedEngineState* st = getSharedState();
    void* targetWin = window ? window : reinterpret_cast<void*>(st->showingWindow);
    if (!targetWin) {
        targetWin = reinterpret_cast<void*>(0x1ULL);
    }

    int curW = st->surfaceWidth.load(std::memory_order_relaxed);
    int curH = st->surfaceHeight.load(std::memory_order_relaxed);
    if (curW > 0 && curH > 0) {
        int syncCount = st->windowSizeSyncCounter.load(std::memory_order_relaxed);
        if (syncCount < 8 ||
            st->lastSyncedWidth.load(std::memory_order_relaxed) != curW ||
            st->lastSyncedHeight.load(std::memory_order_relaxed) != curH) {
            JNIEnv* vmEnv = getAttachedRuntimeEnv();
            if (vmEnv && st->vmGlfwClass) {
                if (st->methodInternalChangeMonitorSize) {
                    vmEnv->CallStaticVoidMethod(st->vmGlfwClass, st->methodInternalChangeMonitorSize, curW, curH);
                    if (vmEnv->ExceptionCheck()) vmEnv->ExceptionClear();
                }
                if (st->methodInternalWindowSizeChanged) {
                    vmEnv->CallStaticVoidMethod(
                        st->vmGlfwClass,
                        st->methodInternalWindowSizeChanged,
                        static_cast<jlong>(reinterpret_cast<uintptr_t>(targetWin))
                    );
                    if (vmEnv->ExceptionCheck()) vmEnv->ExceptionClear();
                }
                st->lastSyncedWidth.store(curW, std::memory_order_relaxed);
                st->lastSyncedHeight.store(curH, std::memory_order_relaxed);
                st->windowSizeSyncCounter.store(syncCount + 1, std::memory_order_relaxed);
            }
        }
    }

    auto cbEnter = st->cbCursorEnter.load(std::memory_order_acquire);
    if (cbEnter != nullptr && !st->cursorEnterSent.exchange(true, std::memory_order_acq_rel)) {
        cbEnter(targetWin, 1);
    }

    double cx = st->cursorX.load(std::memory_order_relaxed);
    double cy = st->cursorY.load(std::memory_order_relaxed);
    double lx = st->lastCursorX.load(std::memory_order_relaxed);
    double ly = st->lastCursorY.load(std::memory_order_relaxed);

    auto cbPos = st->cbCursorPos.load(std::memory_order_acquire);
    if ((cx != lx || cy != ly) && cbPos != nullptr) {
        st->lastCursorX.store(cx, std::memory_order_relaxed);
        st->lastCursorY.store(cy, std::memory_order_relaxed);
        cbPos(targetWin, std::floor(cx), std::floor(cy));
    }

    InputEvent ev{};
    while (st->inputQueue.pop(ev)) {
        switch (ev.type) {
            case EventType::KEY_EVENT: {
                if (st->glfwKeyDownBuffer && ev.code >= 32 && ev.code < 348) {
                    st->glfwKeyDownBuffer[ev.code - 31] = static_cast<jbyte>(ev.action);
                }
                auto cbKey = st->cbKey.load(std::memory_order_acquire);
                if (cbKey) cbKey(targetWin, ev.code, 0, ev.action, ev.mods);
                break;
            }
            case EventType::MOUSE_BUTTON_EVENT: {
                if (cbPos) {
                    double curX = st->cursorX.load(std::memory_order_relaxed);
                    double curY = st->cursorY.load(std::memory_order_relaxed);
                    st->lastCursorX.store(curX, std::memory_order_relaxed);
                    st->lastCursorY.store(curY, std::memory_order_relaxed);
                    cbPos(targetWin, std::floor(curX), std::floor(curY));
                }
                if (st->glfwMouseDownBuffer && ev.code >= 0 && ev.code < 8) {
                    st->glfwMouseDownBuffer[ev.code] = static_cast<jbyte>(ev.action);
                }
                auto cbMouse = st->cbMouseButton.load(std::memory_order_acquire);
                if (cbMouse) cbMouse(targetWin, ev.code, ev.action, ev.mods);
                break;
            }
            case EventType::CURSOR_POS_EVENT: {
                double px = std::floor(static_cast<double>(ev.x));
                double py = std::floor(static_cast<double>(ev.y));
                st->lastCursorX.store(px, std::memory_order_relaxed);
                st->lastCursorY.store(py, std::memory_order_relaxed);
                if (cbPos) {
                    cbPos(targetWin, px, py);
                }
                break;
            }
            case EventType::CURSOR_DELTA_EVENT: {
                if (cbPos) {
                    cbPos(
                        targetWin,
                        std::floor(st->cursorX.load(std::memory_order_relaxed)),
                        std::floor(st->cursorY.load(std::memory_order_relaxed))
                    );
                }
                break;
            }
            case EventType::SCROLL_EVENT: {
                auto cbScroll = st->cbScroll.load(std::memory_order_acquire);
                if (cbScroll) {
                    cbScroll(targetWin, static_cast<double>(ev.x), static_cast<double>(ev.y));
                }
                break;
            }
            case EventType::CHAR_EVENT: {
                auto cbChar = st->cbChar.load(std::memory_order_acquire);
                if (cbChar && ev.code > 0) {
                    cbChar(targetWin, static_cast<unsigned int>(ev.code));
                }
                auto cbCharMods = st->cbCharMods.load(std::memory_order_acquire);
                if (cbCharMods && ev.code > 0) {
                    cbCharMods(targetWin, static_cast<unsigned int>(ev.code), ev.mods);
                }
                break;
            }
            default:
                break;
        }
    }
}

__attribute__((visibility("default"), used))
void pojavStopPumping() {
}

// =========================================================================================
// JNI Methods for org.lwjgl.glfw.GLFW & org.lwjgl.glfw.CallbackBridge & PojavRendererInit
// =========================================================================================

#define DEFINE_GLFW_CALLBACK_SETTER(NAME, FIELD) \
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback( \
    JNIEnv* /*env*/, jclass /*cls*/, jlong /*window*/, jlong callbackptr \
) { \
    auto* st = getSharedState(); \
    st->windowSizeSyncCounter.store(0, std::memory_order_relaxed); \
    st->cursorEnterSent.store(false, std::memory_order_relaxed); \
    auto prev = st->FIELD.exchange(reinterpret_cast<GLFW_invoke_##NAME##_t>(callbackptr), std::memory_order_acq_rel); \
    return reinterpret_cast<jlong>(prev); \
}

DEFINE_GLFW_CALLBACK_SETTER(Char, cbChar)
DEFINE_GLFW_CALLBACK_SETTER(CharMods, cbCharMods)
DEFINE_GLFW_CALLBACK_SETTER(CursorEnter, cbCursorEnter)
DEFINE_GLFW_CALLBACK_SETTER(CursorPos, cbCursorPos)
DEFINE_GLFW_CALLBACK_SETTER(Key, cbKey)
DEFINE_GLFW_CALLBACK_SETTER(MouseButton, cbMouseButton)
DEFINE_GLFW_CALLBACK_SETTER(Scroll, cbScroll)

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(JNIEnv* /*env*/, jclass /*cls*/, jlong window) {
    SharedEngineState* st = getSharedState();
    st->showingWindow = window;
    st->windowSizeSyncCounter.store(0, std::memory_order_relaxed);
    st->cursorEnterSent.store(false, std::memory_order_relaxed);
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer(JNIEnv* /*env*/, jclass /*cls*/) {
    return reinterpret_cast<jlong>(getSharedState()->gamepadButtons);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(
    JNIEnv* env, jclass /*cls*/, jlong /*window*/, jobject xpos, jobject ypos
) {
    SharedEngineState* st = getSharedState();
    if (xpos) {
        auto* px = reinterpret_cast<double*>(env->GetDirectBufferAddress(xpos));
        if (px) *px = st->cursorX.load(std::memory_order_relaxed);
    }
    if (ypos) {
        auto* py = reinterpret_cast<double*>(env->GetDirectBufferAddress(ypos));
        if (py) *py = st->cursorY.load(std::memory_order_relaxed);
    }
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(
    JNIEnv* env, jclass /*cls*/, jlong /*window*/, jdoubleArray xpos, jdoubleArray ypos
) {
    SharedEngineState* st = getSharedState();
    double cx = st->cursorX.load(std::memory_order_relaxed);
    double cy = st->cursorY.load(std::memory_order_relaxed);
    if (xpos) env->SetDoubleArrayRegion(xpos, 0, 1, &cx);
    if (ypos) env->SetDoubleArrayRegion(ypos, 0, 1, &cy);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(
    JNIEnv* /*env*/, jclass /*cls*/, jlong /*window*/, jdouble xpos, jdouble ypos
) {
    SharedEngineState* st = getSharedState();
    st->cursorX.store(xpos, std::memory_order_relaxed);
    st->cursorY.store(ypos, std::memory_order_relaxed);
    st->lastCursorX.store(xpos, std::memory_order_relaxed);
    st->lastCursorY.store(ypos, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSendData(
    JNIEnv* /*env*/, jclass /*cls*/, jboolean /*isAndroid*/, jint /*type*/, jstring /*data*/
) {
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(
    JNIEnv* /*env*/, jclass /*cls*/, jboolean ready
) {
    getSharedState()->inputReady.store(ready == JNI_TRUE, std::memory_order_release);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(
    JNIEnv* env, jclass /*cls*/, jint /*action*/, jbyteArray /*copySrc*/
) {
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(
    JNIEnv* /*env*/, jclass /*cls*/, jboolean grab
) {
    getSharedState()->cursorGrabbed.store(grab == JNI_TRUE, std::memory_order_release);
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadButtonBuffer(JNIEnv* env, jclass /*cls*/) {
    SharedEngineState* st = getSharedState();
    return env->NewDirectByteBuffer(st->gamepadButtons, sizeof(st->gamepadButtons));
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadAxisBuffer(JNIEnv* env, jclass /*cls*/) {
    SharedEngineState* st = getSharedState();
    return env->NewDirectByteBuffer(st->gamepadAxes, sizeof(st->gamepadAxes));
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeEnableGamepadDirectInput(JNIEnv* /*env*/, jclass /*cls*/) {
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(
    JNIEnv* env, jclass /*cls*/, jobject function_provider
) {
    if (!function_provider) return;
    jclass fpClass = env->GetObjectClass(function_provider);
    jmethodID getAddr = env->GetMethodID(fpClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
    if (!getAddr) {
        env->ExceptionClear();
        return;
    }
    jstring symStr = env->NewStringUTF("set_getmainfbsize");
    jlong addr = env->CallLongMethod(function_provider, getAddr, symStr);
    env->DeleteLocalRef(symStr);
    if (addr != 0) {
        using set_getmainfbsize_t = void (*)(void (*)(int*, int*));
        auto fn = reinterpret_cast<set_getmainfbsize_t>(addr);
        fn(&gl4esGetMainFbSize);
        appendNativeLineToLogFile("GL4ES", "Callback ukuran framebuffer utama GL4ES diaktifkan.");
    }
}

// =========================================================================================
// NativeEngineBridge JNI Methods (Called by Kotlin GameActivity, BootLogOverlay & HUD)
// =========================================================================================

__attribute__((visibility("default")))
ANativeWindow* velocraft_get_native_window() {
    return getSharedState()->nativeWindow.load(std::memory_order_acquire);
}

__attribute__((visibility("default")))
int velocraft_poll_input_event(velocraft::input::InputEvent* outEvent) {
    if (!outEvent) return 0;
    return getSharedState()->inputQueue.pop(*outEvent) ? 1 : 0;
}

__attribute__((visibility("default")))
void velocraft_get_cursor_pos(float* outX, float* outY) {
    SharedEngineState* st = getSharedState();
    if (outX) *outX = static_cast<float>(st->cursorX.load(std::memory_order_relaxed));
    if (outY) *outY = static_cast<float>(st->cursorY.load(std::memory_order_relaxed));
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSetSurface(
    JNIEnv* env, jobject /*thiz*/, jobject surface, jint width, jint height
) {
    SharedEngineState* st = getSharedState();
    ANativeWindow* oldWindow = st->nativeWindow.exchange(nullptr, std::memory_order_acq_rel);
    if (oldWindow != nullptr) {
        ANativeWindow_release(oldWindow);
    }

    if (surface != nullptr) {
        ANativeWindow* newWindow = ANativeWindow_fromSurface(env, surface);
        if (width > 0 && height > 0) {
            st->surfaceWidth.store(width, std::memory_order_relaxed);
            st->surfaceHeight.store(height, std::memory_order_relaxed);
        }
        st->nativeWindow.store(newWindow, std::memory_order_release);
        st->isBackgrounded.store(false, std::memory_order_release);
        st->surfaceNeedsRebind.store(true, std::memory_order_release);
        st->windowSizeSyncCounter.store(0, std::memory_order_relaxed);
        if (st->swapBuffersCount.load(std::memory_order_relaxed) == 0 && width > 0 && height > 0) {
            st->cursorX.store(width * 0.5, std::memory_order_relaxed);
            st->cursorY.store(height * 0.5, std::memory_order_relaxed);
        }
        LOGI("Bound ANativeWindow (%dx%d) for Direct OpenGL ES / Vulkan rendering", width, height);
    } else {
        st->isBackgrounded.store(true, std::memory_order_release);
        st->surfaceNeedsRebind.store(true, std::memory_order_release);
        LOGI("Surface paused to background — preserving OpenGL context on 1x1 Pbuffer");
    }
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSendKey(
    JNIEnv* /*env*/, jobject /*thiz*/, jint glfwKeyCode, jint action, jint mods
) {
    InputEvent ev{};
    ev.type = EventType::KEY_EVENT;
    ev.code = glfwKeyCode;
    ev.action = action;
    ev.mods = mods;
    getSharedState()->inputQueue.push(ev);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSendChar(
    JNIEnv* /*env*/, jobject /*thiz*/, jint codepoint, jint mods
) {
    InputEvent ev{};
    ev.type = EventType::CHAR_EVENT;
    ev.code = codepoint;
    ev.action = GLFW_PRESS;
    ev.mods = mods;
    getSharedState()->inputQueue.push(ev);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSendMouseButton(
    JNIEnv* /*env*/, jobject /*thiz*/, jint button, jint action, jint mods
) {
    InputEvent ev{};
    ev.type = EventType::MOUSE_BUTTON_EVENT;
    ev.code = button;
    ev.action = action;
    ev.mods = mods;
    getSharedState()->inputQueue.push(ev);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSendCameraDelta(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat deltaX, jfloat deltaY
) {
    SharedEngineState* st = getSharedState();
    const double nextX = st->cursorX.load(std::memory_order_relaxed) + deltaX;
    const double nextY = st->cursorY.load(std::memory_order_relaxed) + deltaY;
    st->cursorX.store(nextX, std::memory_order_relaxed);
    st->cursorY.store(nextY, std::memory_order_relaxed);

    InputEvent ev{};
    ev.type = EventType::CURSOR_DELTA_EVENT;
    ev.x = deltaX;
    ev.y = deltaY;
    st->inputQueue.push(ev);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSendCursorPos(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat x, jfloat y
) {
    SharedEngineState* st = getSharedState();
    st->cursorX.store(x, std::memory_order_relaxed);
    st->cursorY.store(y, std::memory_order_relaxed);

    InputEvent ev{};
    ev.type = EventType::CURSOR_POS_EVENT;
    ev.x = x;
    ev.y = y;
    st->inputQueue.push(ev);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSetCursorGrabbed(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean grabbed
) {
    getSharedState()->cursorGrabbed.store(grabbed == JNI_TRUE, std::memory_order_release);
}

JNIEXPORT jboolean JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeIsCursorGrabbed(
    JNIEnv* /*env*/, jobject /*thiz*/
) {
    return getSharedState()->cursorGrabbed.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSendScroll(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat xOffset, jfloat yOffset
) {
    InputEvent ev{};
    ev.type = EventType::SCROLL_EVENT;
    ev.x = xOffset;
    ev.y = yOffset;
    getSharedState()->inputQueue.push(ev);
}

JNIEXPORT jstring JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeGetBootTelemetrySnapshot(
    JNIEnv* env, jobject /*thiz*/
) {
    SharedEngineState* st = getSharedState();
    const int64_t endMs = st->jvmFinishedTimeMs.load(std::memory_order_relaxed);
    const int64_t nowMs = endMs > 0 ? endMs : currentTimeMillis();
    const int64_t startMs = st->bootStartTimeMs.load(std::memory_order_relaxed);
    const int elapsedSec = startMs > 0 ? static_cast<int>((nowMs - startMs) / 1000LL) : 0;

    char stageBuf[256];
    char detailBuf[256];
    char classBuf[256];
    pthread_mutex_lock(&st->telemetryMutex);
    std::snprintf(stageBuf, sizeof(stageBuf), "%s", st->stageTitle);
    std::snprintf(detailBuf, sizeof(detailBuf), "%s", st->subTaskDetail);
    std::snprintf(classBuf, sizeof(classBuf), "%s", st->activeClassName);
    pthread_mutex_unlock(&st->telemetryMutex);

    char payload[1536];
    std::snprintf(
        payload,
        sizeof(payload),
        "%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%d|%s|%s|%s",
        st->bootStageIndex.load(std::memory_order_relaxed),
        10,
        st->bootPercent.load(std::memory_order_relaxed),
        st->loadedClassCount.load(std::memory_order_relaxed),
        st->targetSplashClasses.load(std::memory_order_relaxed),
        st->classesPerSec.load(std::memory_order_relaxed),
        st->jvmUsedRamMb.load(std::memory_order_relaxed),
        st->jvmMaxRamMb.load(std::memory_order_relaxed),
        elapsedSec,
        st->etaMojangSplashSec.load(std::memory_order_relaxed),
        st->etaMinecraftMenuSec.load(std::memory_order_relaxed),
        st->mojangSplashReached.load(std::memory_order_relaxed) ? 1 : 0,
        st->titleScreenReached.load(std::memory_order_relaxed) ? 1 : 0,
        st->swapBuffersCount.load(std::memory_order_relaxed),
        stageBuf,
        detailBuf,
        classBuf
    );
    return env->NewStringUTF(payload);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeConfigureRendererEnv(
    JNIEnv* env, jobject /*thiz*/, jstring jreHomePath, jstring rendererLibName, jstring cacheDirPath
) {
    SharedEngineState* st = getSharedState();
    const char* jreHomeRaw = env->GetStringUTFChars(jreHomePath, nullptr);
    const char* rendererLib = env->GetStringUTFChars(rendererLibName, nullptr);
    const char* cacheDir = env->GetStringUTFChars(cacheDirPath, nullptr);

    char resolvedJre[PATH_MAX];
    std::string jreHomeStr;
    if (realpath(jreHomeRaw, resolvedJre) != nullptr) {
        jreHomeStr = resolvedJre;
    } else {
        jreHomeStr = jreHomeRaw;
    }
    std::snprintf(st->jreHomePath, sizeof(st->jreHomePath), "%s", jreHomeStr.c_str());

    std::string engineRoot = jreHomeStr;
    auto slashPos = engineRoot.find_last_of('/');
    if (slashPos != std::string::npos) {
        engineRoot = engineRoot.substr(0, slashPos);
    }
    std::string nativesStr = engineRoot + "/natives-" + VELOCRAFT_ABI_NAME;
    std::snprintf(st->nativesDirPath, sizeof(st->nativesDirPath), "%s", nativesStr.c_str());
    std::snprintf(
        st->logFilePath,
        sizeof(st->logFilePath),
        "/storage/emulated/0/Android/media/id.xyverse.minecraft/log/latest.log"
    );

    st->bootStartTimeMs.store(currentTimeMillis(), std::memory_order_relaxed);
    st->jvmFinishedTimeMs.store(0, std::memory_order_relaxed);
    st->jvmFinishedSignal.store(false, std::memory_order_relaxed);
    st->interceptedExitCode.store(0, std::memory_order_relaxed);
    st->loadedClassCount.store(0, std::memory_order_relaxed);
    st->targetSplashClasses.store(9800, std::memory_order_relaxed);
    st->targetMenuClasses.store(13200, std::memory_order_relaxed);
    st->bootStageIndex.store(1, std::memory_order_relaxed);
    st->bootPercent.store(12, std::memory_order_relaxed);
    st->mojangSplashReached.store(false, std::memory_order_relaxed);
    st->titleScreenReached.store(false, std::memory_order_relaxed);
    st->swapBuffersCount.store(0, std::memory_order_relaxed);
    setTelemetryStrings(
        st,
        "TAHAP 1/10: Mengonfigurasi Lingkungan OpenJDK 17 & Jalur Pustaka Native...",
        "Menyiapkan variabel lingkungan JAVA_HOME, LD_LIBRARY_PATH & Renderer...",
        "io.velocraft.android.bridge.NativeEngineBridge"
    );

    std::string envLdPath = jreHomeStr + "/lib/jli:" +
                            jreHomeStr + "/lib:" +
                            SYSTEM_LIB_DIR + ":" +
                            nativesStr;

    std::string internalLinkerPath = jreHomeStr + "/lib/server:" + envLdPath;

    setenv("JAVA_HOME", jreHomeStr.c_str(), 1);
    setenv("HOME", (engineRoot + "/minecraft").c_str(), 1);
    setenv("TMPDIR", cacheDir, 1);
    setenv("LD_LIBRARY_PATH", envLdPath.c_str(), 1);
    setenv("PATH", (jreHomeStr + "/bin:/system/bin").c_str(), 1);
    setenv("POJAV_NATIVEDIR", nativesStr.c_str(), 1);
    setenv("POJAV_RENDERER", "opengles2", 1);
    setenv("VELOCRAFT_RENDERER", rendererLib, 1);
    setenv("MESA_SHADER_CACHE_DIR", cacheDir, 1);
    setenv("MESA_GLSL_CACHE_DIR", cacheDir, 1);
    setenv("MG_SHADER_CACHE_PATH", cacheDir, 1);

    setenv("LIBGL_ES", "2", 1);
    setenv("LIBGL_MIPMAP", "3", 1);
    setenv("LIBGL_NORMALIZE", "1", 1);
    setenv("LIBGL_NOERROR", "1", 1);
    setenv("LIBGL_NOINTOVLHACK", "1", 1);

    ensureStdioRedirectedToLog();
    updateAndroidLinkerSearchPath(internalLinkerPath);
    chdir((engineRoot + "/minecraft").c_str());

    appendNativeLineToLogFile(
        "ENV",
        "JAVA_HOME=" + jreHomeStr + " | RENDERER=" + rendererLib + " | NATIVES=" + nativesStr
    );
    emitBootProgressLogLine(st, true);

    env->ReleaseStringUTFChars(jreHomePath, jreHomeRaw);
    env->ReleaseStringUTFChars(rendererLibName, rendererLib);
    env->ReleaseStringUTFChars(cacheDirPath, cacheDir);
}

JNIEXPORT jint JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeLaunchDirectJvm(
    JNIEnv* env, jobject /*thiz*/, jstring /*jliLibPath*/, jobjectArray jvmArgsArray
) {
    SharedEngineState* st = getSharedState();
    ensureStdioRedirectedToLog();

    std::string jreHomeStr(st->jreHomePath);
    std::string nativesStr(st->nativesDirPath);

    st->bootStageIndex.store(2, std::memory_order_relaxed);
    st->bootPercent.store(18, std::memory_order_relaxed);
    setTelemetryStrings(
        st,
        "TAHAP 2/10: Memuat Pustaka Inti OpenJDK 17 (libjvm.so) & Mesin Render GL4ES...",
        "Memuat 12 pustaka native OpenJDK 17 dan pustaka grafis LWJGL 3...",
        "dlopen(libjvm.so)"
    );
    emitBootProgressLogLine(st, true);

    std::string primaryJli = jreHomeStr + "/lib/libjli.so";
    std::string dupJli = jreHomeStr + "/lib/jli/libjli.so";
    if (access(primaryJli.c_str(), R_OK) == 0 && access(dupJli.c_str(), F_OK) == 0) {
        unlink(dupJli.c_str());
    }

    void* libJvmHandle = nullptr;

    if (!jreHomeStr.empty()) {
        const std::vector<std::string> orderedCoreLibs = {
            jreHomeStr + "/lib/libjli.so",
            jreHomeStr + "/lib/server/libjvm.so",
            jreHomeStr + "/lib/libverify.so",
            jreHomeStr + "/lib/libjava.so",
            jreHomeStr + "/lib/libzip.so",
            jreHomeStr + "/lib/libjimage.so",
            jreHomeStr + "/lib/libnet.so",
            jreHomeStr + "/lib/libnio.so",
            jreHomeStr + "/lib/libawt.so",
            jreHomeStr + "/lib/libawt_headless.so",
            jreHomeStr + "/lib/libfreetype.so",
            jreHomeStr + "/lib/libfontmanager.so"
        };
        for (const auto& soPath : orderedCoreLibs) {
            if (access(soPath.c_str(), R_OK) == 0) {
                void* handle = dlopen(soPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
                if (handle) {
                    if (soPath.find("libjvm.so") != std::string::npos) {
                        libJvmHandle = handle;
                    }
                    appendNativeLineToLogFile("DLOPEN", "Pustaka OpenJDK siap: " + soPath);
                } else {
                    const char* err = dlerror();
                    appendNativeLineToLogFile(
                        "DLOPEN-WARN",
                        "Gagal memuat " + soPath + ": " + (err ? err : "unknown")
                    );
                }
            }
        }

        std::string libDirStr = jreHomeStr + "/lib";
        DIR* dir = opendir(libDirStr.c_str());
        if (dir) {
            struct dirent* ent = nullptr;
            while ((ent = readdir(dir)) != nullptr) {
                std::string name = ent->d_name;
                if (name.size() > 3 && name.substr(name.size() - 3) == ".so" && name != "libjsig.so") {
                    std::string fullPath = libDirStr + "/" + name;
                    dlopen(fullPath.c_str(), RTLD_LAZY | RTLD_GLOBAL);
                }
            }
            closedir(dir);
        }
    }

    if (!nativesStr.empty()) {
        const std::vector<std::string> nativeEngineLibs = {
            nativesStr + "/libfreetype.so",
            nativesStr + "/libopenal.so",
            nativesStr + "/libgl4es_114.so",
            nativesStr + "/libjnidispatch.so",
            nativesStr + "/liblwjgl.so",
            nativesStr + "/liblwjgl_opengl.so",
            nativesStr + "/liblwjgl_stb.so",
            nativesStr + "/liblwjgl_tinyfd.so",
            nativesStr + "/liblwjgl_nanovg.so",
            nativesStr + "/libpojavexec.so"
        };
        for (const auto& soPath : nativeEngineLibs) {
            if (access(soPath.c_str(), R_OK) == 0) {
                void* h = dlopen(soPath.c_str(), RTLD_LAZY | RTLD_GLOBAL);
                if (h) {
                    appendNativeLineToLogFile("NATIVES", "Pustaka Engine siap: " + soPath);
                } else {
                    const char* err = dlerror();
                    appendNativeLineToLogFile("NATIVES-WARN", "Gagal " + soPath + ": " + (err ? err : "unknown"));
                }
            }
        }
    }

    if (!libJvmHandle) {
        std::string jvmSoPath = jreHomeStr + "/lib/server/libjvm.so";
        libJvmHandle = dlopen(jvmSoPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    }
    if (!libJvmHandle) {
        const char* err = dlerror();
        appendNativeLineToLogFile("FATAL", std::string("Gagal memuat libjvm.so: ") + (err ? err : "unknown"));
        return -1;
    }

    auto createJavaVM = reinterpret_cast<CreateJavaVM_t>(dlsym(libJvmHandle, "JNI_CreateJavaVM"));
    if (!createJavaVM) {
        appendNativeLineToLogFile("FATAL", "Simbol JNI_CreateJavaVM tidak ditemukan di libjvm.so");
        return -2;
    }

    const int32_t maxContigMb = probeMaxContiguousMmapMb();
    appendNativeLineToLogFile(
        "MEM-PROBE",
        "Blok ruang alamat virtual kontigu maksimum yang tersedia: " + std::to_string(maxContigMb) + " MB"
    );

    auto* launchCtx = new DirectJvmLaunchContext();
    launchCtx->createJavaVM = createJavaVM;
    launchCtx->vmOptionStrings.push_back("-Xlog:class+load=info:stdout:none");
    launchCtx->vmOptionStrings.push_back("-Dsun.java.launcher=SUN_STANDARD");
#if !IS_64BIT_ABI
    launchCtx->vmOptionStrings.push_back("-XX:ReservedCodeCacheSize=64M");
    launchCtx->vmOptionStrings.push_back("-XX:InitialCodeCacheSize=16M");
#else
    launchCtx->vmOptionStrings.push_back("-XX:ReservedCodeCacheSize=128M");
#endif

    const jsize argc = env->GetArrayLength(jvmArgsArray);
    std::vector<std::string> rawArgs;
    rawArgs.reserve(argc);
    for (jsize i = 0; i < argc; ++i) {
        auto jstr = static_cast<jstring>(env->GetObjectArrayElement(jvmArgsArray, i));
        const char* utf = env->GetStringUTFChars(jstr, nullptr);
        rawArgs.emplace_back(utf ? utf : "");
        env->ReleaseStringUTFChars(jstr, utf);
        env->DeleteLocalRef(jstr);
    }

    bool foundMainClass = false;
    for (size_t i = 0; i < rawArgs.size(); ++i) {
        const std::string& arg = rawArgs[i];
        if (i == 0 && (arg == "java" || arg == "openjdk")) {
            continue;
        }
        if (!foundMainClass) {
            if ((arg == "-cp" || arg == "-classpath") && (i + 1 < rawArgs.size())) {
                launchCtx->vmOptionStrings.push_back("-Djava.class.path=" + rawArgs[++i]);
            } else if (!arg.empty() && arg[0] == '-') {
                if (!IS_64BIT_ABI && arg.rfind("-Xmx", 0) == 0) {
                    int requestedMb = std::atoi(arg.c_str() + 4);
                    int safeLimitMb = std::max(384, maxContigMb - 220);
                    int finalMaxMb = std::min(requestedMb, safeLimitMb);
                    st->jvmMaxRamMb.store(finalMaxMb, std::memory_order_relaxed);
                    launchCtx->vmOptionStrings.push_back("-Xmx" + std::to_string(finalMaxMb) + "M");
                } else if (!IS_64BIT_ABI && arg.rfind("-Xms", 0) == 0) {
                    int requestedInitMb = std::atoi(arg.c_str() + 4);
                    int safeInitMb = std::min(requestedInitMb, 256);
                    st->jvmUsedRamMb.store(safeInitMb / 2, std::memory_order_relaxed);
                    launchCtx->vmOptionStrings.push_back("-Xms" + std::to_string(safeInitMb) + "M");
                } else {
                    if (arg.rfind("-Xmx", 0) == 0) {
                        st->jvmMaxRamMb.store(std::atoi(arg.c_str() + 4), std::memory_order_relaxed);
                    }
                    launchCtx->vmOptionStrings.push_back(arg);
                }
            } else {
                foundMainClass = true;
                launchCtx->mainClassName = arg;
            }
        } else {
            launchCtx->appArgs.push_back(arg);
        }
    }

    if (launchCtx->mainClassName.empty()) {
        launchCtx->mainClassName = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    }

    std::string sunCmd = launchCtx->mainClassName;
    for (const auto& a : launchCtx->appArgs) {
        sunCmd += " " + a;
    }
    launchCtx->vmOptionStrings.push_back("-Dsun.java.command=" + sunCmd);

    appendNativeLineToLogFile(
        "BOOT",
        "Meluncurkan OpenJDK 17 HotSpot (" + std::string(VELOCRAFT_ABI_NAME) + ") -> " +
            launchCtx->mainClassName + " (Opsi VM: " + std::to_string(launchCtx->vmOptionStrings.size()) +
            ", Argumen Game: " + std::to_string(launchCtx->appArgs.size()) + ")"
    );

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_attr_setstacksize(&attr, 4 * 1024 * 1024);
    pthread_attr_setguardsize(&attr, 0);

    pthread_t jvmTid;
    if (pthread_create(&jvmTid, &attr, jvmDirectMainThread, launchCtx) == 0) {
        pthread_mutex_lock(&st->exitWaitMutex);
        while (!st->jvmFinishedSignal.load(std::memory_order_acquire)) {
            pthread_cond_wait(&st->exitWaitCond, &st->exitWaitMutex);
        }
        pthread_mutex_unlock(&st->exitWaitMutex);
    } else {
        jvmDirectMainThread(launchCtx);
    }
    pthread_attr_destroy(&attr);

    std::fflush(stdout);
    std::fflush(stderr);
    drainStdioPipeNonBlocking();
    dumpNewestHsErrLogIfPresent();

    int rawCode = st->interceptedExitCode.load(std::memory_order_acquire);
    int exitCode = (rawCode == 1000) ? 0 : rawCode;

    appendNativeLineToLogFile("EXIT", "Sesi OpenJDK HotSpot selesai dengan kode keluar: " + std::to_string(exitCode));
    return exitCode;
}

JNIEXPORT jstring JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeGetArchitectureAbi(
    JNIEnv* env, jobject /*thiz*/
) {
    return env->NewStringUTF(VELOCRAFT_ABI_NAME);
}

} // extern "C"
