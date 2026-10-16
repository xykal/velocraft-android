#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sched.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "lwjgl_input_bridge.h"

#define LOG_TAG "VeloCraftNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using namespace velocraft::input;

// Global lock-free input queue read by LWJGL/GLFW hook and written by Android UI/Gyro threads
LockFreeInputQueue<2048> g_inputQueue;

// Native window handle for EGL / MobileGlues / Vulkan surface creation
std::atomic<ANativeWindow*> g_nativeWindow{nullptr};
std::atomic<int32_t> g_surfaceWidth{1920};
std::atomic<int32_t> g_surfaceHeight{1080};
std::atomic<bool> g_cursorGrabbed{true}; // True when in-game (crosshair), False when in GUI (Inventory/Chest)

// Current virtual cursor coordinates
std::atomic<float> g_cursorX{960.0f};
std::atomic<float> g_cursorY{540.0f};

// Function pointer type for OpenJDK JLI_Launch
using JLI_Launch_t = int (*)(
    int argc, char** argv,
    int jargc, const char** jargv,
    int appclassc, const char** appclassv,
    const char* fullversion,
    const char* dotversion,
    const char* pname,
    const char* lname,
    jboolean javaargs,
    jboolean cpwildcard,
    jboolean javaw,
    jint ergo
);

/**
 * Pins the calling thread (Minecraft Render Thread) to the highest-frequency ARM64 performance cores
 * (big.LITTLE / Prime cores) to minimize frame time spikes and GC jitter.
 */
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

    // Select cores running within 80% of peak frequency (Performance + Prime cluster)
    const int threshold = static_cast<int>(highestFreq * 0.78);
    for (int i = 0; i < numCores; ++i) {
        if (maxFreqs[i] >= threshold) {
            CPU_SET(i, &cpuset);
            pinnedCount++;
        }
    }

    if (pinnedCount >= 2) {
        if (sched_setaffinity(0, sizeof(cpu_set_t), &cpuset) == 0) {
            LOGI("Pinned Minecraft Render Thread to %d big/prime CPU cores (peak %d kHz)", pinnedCount, highestFreq);
        }
    }
}

} // anonymous namespace

// Exported C symbols so patched LWJGL3-Android / GLFW library can poll events directly without JNI overhead
extern "C" {

__attribute__((visibility("default")))
ANativeWindow* velocraft_get_native_window() {
    return g_nativeWindow.load(std::memory_order_acquire);
}

__attribute__((visibility("default")))
int velocraft_poll_input_event(velocraft::input::InputEvent* outEvent) {
    if (!outEvent) return 0;
    return g_inputQueue.pop(*outEvent) ? 1 : 0;
}

__attribute__((visibility("default")))
void velocraft_get_cursor_pos(float* outX, float* outY) {
    if (outX) *outX = g_cursorX.load(std::memory_order_relaxed);
    if (outY) *outY = g_cursorY.load(std::memory_order_relaxed);
}

// =========================================================================================
// JNI Bridge Methods called from io.velocraft.android.bridge.NativeEngineBridge
// =========================================================================================

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSetSurface(
    JNIEnv* env, jobject /*thiz*/, jobject surface, jint width, jint height
) {
    ANativeWindow* oldWindow = g_nativeWindow.exchange(nullptr, std::memory_order_acq_rel);
    if (oldWindow != nullptr) {
        ANativeWindow_release(oldWindow);
    }

    if (surface != nullptr) {
        ANativeWindow* newWindow = ANativeWindow_fromSurface(env, surface);
        g_nativeWindow.store(newWindow, std::memory_order_release);
        g_surfaceWidth.store(width, std::memory_order_relaxed);
        g_surfaceHeight.store(height, std::memory_order_relaxed);
        g_cursorX.store(width * 0.5f, std::memory_order_relaxed);
        g_cursorY.store(height * 0.5f, std::memory_order_relaxed);
        LOGI("Bound ANativeWindow (%dx%d) for Direct OpenGL ES 3.2 / Vulkan rendering", width, height);
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
    g_inputQueue.push(ev);
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
    g_inputQueue.push(ev);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSendCameraDelta(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat deltaX, jfloat deltaY
) {
    // Update accumulated cursor pos for LWJGL raw mouse delta consumption
    const float nextX = g_cursorX.load(std::memory_order_relaxed) + deltaX;
    const float nextY = g_cursorY.load(std::memory_order_relaxed) + deltaY;
    g_cursorX.store(nextX, std::memory_order_relaxed);
    g_cursorY.store(nextY, std::memory_order_relaxed);

    InputEvent ev{};
    ev.type = EventType::CURSOR_DELTA_EVENT;
    ev.x = deltaX;
    ev.y = deltaY;
    g_inputQueue.push(ev);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSendCursorPos(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat x, jfloat y
) {
    g_cursorX.store(x, std::memory_order_relaxed);
    g_cursorY.store(y, std::memory_order_relaxed);

    InputEvent ev{};
    ev.type = EventType::CURSOR_POS_EVENT;
    ev.x = x;
    ev.y = y;
    g_inputQueue.push(ev);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeSetCursorGrabbed(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean grabbed
) {
    g_cursorGrabbed.store(grabbed == JNI_TRUE, std::memory_order_release);
}

JNIEXPORT void JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeConfigureRendererEnv(
    JNIEnv* env, jobject /*thiz*/, jstring jreHomePath, jstring rendererLibName, jstring cacheDirPath
) {
    const char* jreHome = env->GetStringUTFChars(jreHomePath, nullptr);
    const char* rendererLib = env->GetStringUTFChars(rendererLibName, nullptr);
    const char* cacheDir = env->GetStringUTFChars(cacheDirPath, nullptr);

    setenv("JAVA_HOME", jreHome, 1);
    setenv("VELOCRAFT_RENDERER", rendererLib, 1);
    setenv("MESA_SHADER_CACHE_DIR", cacheDir, 1);
    setenv("MG_SHADER_CACHE_PATH", cacheDir, 1);

    // Enable OpenGL 4.6 compatibility profile translation via MobileGlues / GL4ES for Sodium 0.5+
    setenv("LIBGL_ES", "3", 1);
    setenv("LIBGL_MIPMAP", "3", 1);
    setenv("LIBGL_NORMALIZE", "1", 1);
    setenv("LIBGL_NOERROR", "1", 1);

    LOGI("Configured VeloCraft Native Environment: JAVA_HOME=%s, RENDERER=%s", jreHome, rendererLib);

    env->ReleaseStringUTFChars(jreHomePath, jreHome);
    env->ReleaseStringUTFChars(rendererLibName, rendererLib);
    env->ReleaseStringUTFChars(cacheDirPath, cacheDir);
}

JNIEXPORT jint JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeLaunchDirectJvm(
    JNIEnv* env, jobject /*thiz*/, jstring jliLibPath, jobjectArray jvmArgsArray
) {
    pinThreadToPerformanceCores();

    const char* jliPath = env->GetStringUTFChars(jliLibPath, nullptr);
    void* libJli = dlopen(jliPath, RTLD_LAZY | RTLD_GLOBAL);
    env->ReleaseStringUTFChars(jliLibPath, jliPath);

    if (!libJli) {
        LOGE("Failed to dlopen libjli.so: %s", dlerror());
        return -1;
    }

    auto jliLaunch = reinterpret_cast<JLI_Launch_t>(dlsym(libJli, "JLI_Launch"));
    if (!jliLaunch) {
        LOGE("Failed to resolve JLI_Launch symbol in libjli.so");
        return -2;
    }

    const jsize argc = env->GetArrayLength(jvmArgsArray);
    std::vector<char*> argv(argc);
    for (jsize i = 0; i < argc; ++i) {
        auto jstr = static_cast<jstring>(env->GetObjectArrayElement(jvmArgsArray, i));
        const char* utf = env->GetStringUTFChars(jstr, nullptr);
        argv[i] = strdup(utf);
        env->ReleaseStringUTFChars(jstr, utf);
        env->DeleteLocalRef(jstr);
    }

    LOGI("Booting Embedded OpenJDK 21 ARM64 directly into Fabric KnotClient (%d args)...", argc);
    const int exitCode = jliLaunch(
        argc, argv.data(),
        0, nullptr,
        0, nullptr,
        "21.0.4", "21.0",
        "java", "openjdk",
        JNI_FALSE, JNI_FALSE, JNI_FALSE, 0
    );

    for (char* arg : argv) {
        free(arg);
    }

    return exitCode;
}

} // extern "C"
