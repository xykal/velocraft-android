#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sched.h>
#include <unistd.h>
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
#elif defined(__arm__)
#define VELOCRAFT_ABI_NAME "armeabi-v7a"
#else
#define VELOCRAFT_ABI_NAME "unknown"
#endif

namespace {

using namespace velocraft::input;

LockFreeInputQueue<2048> g_inputQueue;

std::atomic<ANativeWindow*> g_nativeWindow{nullptr};
std::atomic<int32_t> g_surfaceWidth{1920};
std::atomic<int32_t> g_surfaceHeight{1080};
std::atomic<bool> g_cursorGrabbed{true};

std::atomic<float> g_cursorX{960.0f};
std::atomic<float> g_cursorY{540.0f};

std::string g_jreHomePath;
std::string g_logFilePath;
std::atomic<bool> g_stdioRedirected{false};
int g_stdioPipe[2] = {-1, -1};

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

void appendNativeLineToLogFile(const char* prefix, const std::string& line) {
    LOGI("[%s] %s", prefix, line.c_str());
    if (g_logFilePath.empty()) return;
    FILE* f = std::fopen(g_logFilePath.c_str(), "a");
    if (f) {
        std::fprintf(f, "[NATIVE-%s] %s\n", prefix, line.c_str());
        std::fflush(f);
        std::fclose(f);
    }
}

void* stdioPipeReaderThread(void* /*arg*/) {
    char buf[1024];
    std::string lineBuffer;
    ssize_t n = 0;
    while ((n = read(g_stdioPipe[0], buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        for (ssize_t i = 0; i < n; ++i) {
            char c = buf[i];
            if (c == '\n') {
                if (!lineBuffer.empty()) {
                    appendNativeLineToLogFile("JVM-OUT", lineBuffer);
                    lineBuffer.clear();
                }
            } else if (c != '\r') {
                lineBuffer.push_back(c);
            }
        }
    }
    if (!lineBuffer.empty()) {
        appendNativeLineToLogFile("JVM-OUT", lineBuffer);
    }
    return nullptr;
}

void ensureStdioRedirectedToLog() {
    bool expected = false;
    if (!g_stdioRedirected.compare_exchange_strong(expected, true)) {
        return;
    }
    if (pipe(g_stdioPipe) == 0) {
        setvbuf(stdout, nullptr, _IOLBF, 0);
        setvbuf(stderr, nullptr, _IONBF, 0);
        dup2(g_stdioPipe[1], STDOUT_FILENO);
        dup2(g_stdioPipe[1], STDERR_FILENO);

        pthread_t tid;
        pthread_create(&tid, nullptr, stdioPipeReaderThread, nullptr);
        pthread_detach(tid);
        appendNativeLineToLogFile("BOOT", "Redirected native stdout/stderr pipe to latest.log and Boot Console.");
    }
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
                "Pinned JVM Render Thread to " + std::to_string(pinnedCount) + " performance CPU cores."
            );
        }
    }
}

} // anonymous namespace

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
        LOGI("Bound ANativeWindow (%dx%d) for Direct OpenGL ES / Vulkan rendering", width, height);
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

    g_jreHomePath = jreHome;
    g_logFilePath = "/storage/emulated/0/Android/media/id.xyverse.minecraft/log/latest.log";

    std::string ldPath = std::string(jreHome) + "/lib:" +
                         std::string(jreHome) + "/lib/jli:" +
                         std::string(jreHome) + "/lib/server";

    setenv("JAVA_HOME", jreHome, 1);
    setenv("LD_LIBRARY_PATH", ldPath.c_str(), 1);
    setenv("VELOCRAFT_RENDERER", rendererLib, 1);
    setenv("MESA_SHADER_CACHE_DIR", cacheDir, 1);
    setenv("MG_SHADER_CACHE_PATH", cacheDir, 1);

    setenv("LIBGL_ES", "3", 1);
    setenv("LIBGL_MIPMAP", "3", 1);
    setenv("LIBGL_NORMALIZE", "1", 1);
    setenv("LIBGL_NOERROR", "1", 1);

    ensureStdioRedirectedToLog();
    appendNativeLineToLogFile(
        "ENV",
        std::string("JAVA_HOME=") + jreHome + " | RENDERER=" + rendererLib + " | LD_LIBRARY_PATH=" + ldPath
    );

    env->ReleaseStringUTFChars(jreHomePath, jreHome);
    env->ReleaseStringUTFChars(rendererLibName, rendererLib);
    env->ReleaseStringUTFChars(cacheDirPath, cacheDir);
}

JNIEXPORT jint JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeLaunchDirectJvm(
    JNIEnv* env, jobject /*thiz*/, jstring jliLibPath, jobjectArray jvmArgsArray
) {
    ensureStdioRedirectedToLog();
    pinThreadToPerformanceCores();

    // Preload core OpenJDK shared libraries in dependency order for Android Bionic linker
    if (!g_jreHomePath.empty()) {
        const std::vector<std::string> preloadLibs = {
            g_jreHomePath + "/lib/libjli.so",
            g_jreHomePath + "/lib/jli/libjli.so",
            g_jreHomePath + "/lib/server/libjvm.so",
            g_jreHomePath + "/lib/libverify.so",
            g_jreHomePath + "/lib/libjava.so",
            g_jreHomePath + "/lib/libnet.so",
            g_jreHomePath + "/lib/libnio.so",
            g_jreHomePath + "/lib/libawt.so",
            g_jreHomePath + "/lib/libawt_headless.so"
        };
        for (const auto& soPath : preloadLibs) {
            if (access(soPath.c_str(), R_OK) == 0) {
                void* handle = dlopen(soPath.c_str(), RTLD_LAZY | RTLD_GLOBAL);
                if (handle) {
                    appendNativeLineToLogFile("DLOPEN", "Loaded native OpenJDK library: " + soPath);
                } else {
                    const char* err = dlerror();
                    appendNativeLineToLogFile(
                        "DLOPEN-WARN",
                        "Could not preload " + soPath + ": " + (err ? err : "unknown")
                    );
                }
            }
        }
    }

    const char* jliPath = env->GetStringUTFChars(jliLibPath, nullptr);
    appendNativeLineToLogFile("BOOT", std::string("Opening JLI entrypoint: ") + jliPath);
    void* libJli = dlopen(jliPath, RTLD_LAZY | RTLD_GLOBAL);
    env->ReleaseStringUTFChars(jliLibPath, jliPath);

    if (!libJli) {
        const char* err = dlerror();
        appendNativeLineToLogFile("FATAL", std::string("Failed to dlopen libjli.so: ") + (err ? err : "unknown"));
        return -1;
    }

    auto jliLaunch = reinterpret_cast<JLI_Launch_t>(dlsym(libJli, "JLI_Launch"));
    if (!jliLaunch) {
        appendNativeLineToLogFile("FATAL", "Failed to resolve JLI_Launch symbol in libjli.so");
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

    appendNativeLineToLogFile(
        "BOOT",
        "Invoking OpenJDK JLI_Launch (" + std::string(VELOCRAFT_ABI_NAME) + ") with " + std::to_string(argc) + " arguments..."
    );

    const int exitCode = jliLaunch(
        argc, argv.data(),
        0, nullptr,
        0, nullptr,
        "17.0.8", "17.0",
        "java", "openjdk",
        JNI_FALSE, JNI_FALSE, JNI_FALSE, 0
    );

    appendNativeLineToLogFile("EXIT", "JLI_Launch returned exit code: " + std::to_string(exitCode));

    for (char* arg : argv) {
        free(arg);
    }

    return exitCode;
}

JNIEXPORT jstring JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeGetArchitectureAbi(
    JNIEnv* env, jobject /*thiz*/
) {
    return env->NewStringUTF(VELOCRAFT_ABI_NAME);
}

} // extern "C"
