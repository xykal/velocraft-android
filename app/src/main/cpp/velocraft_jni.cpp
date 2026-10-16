#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <dlfcn.h>
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <sched.h>
#include <setjmp.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cmath>
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
using Elf_Ehdr = Elf64_Ehdr;
using Elf_Phdr = Elf64_Phdr;
using Elf_Dyn  = Elf64_Dyn;
using Elf_Sym  = Elf64_Sym;
using Elf_Rel  = Elf64_Rela;
#define ELF_R_SYM(i) ELF64_R_SYM(i)
#else
#define VELOCRAFT_ABI_NAME "armeabi-v7a"
#define SYSTEM_LIB_DIR "/system/lib:/vendor/lib:/vendor/lib/hw"
using Elf_Ehdr = Elf32_Ehdr;
using Elf_Phdr = Elf32_Phdr;
using Elf_Dyn  = Elf32_Dyn;
using Elf_Sym  = Elf32_Sym;
using Elf_Rel  = Elf32_Rel;
#define ELF_R_SYM(i) ELF32_R_SYM(i)
#endif

namespace {

using namespace velocraft::input;

LockFreeInputQueue<2048> g_inputQueue;

std::atomic<ANativeWindow*> g_nativeWindow{nullptr};
std::atomic<int32_t> g_surfaceWidth{1280};
std::atomic<int32_t> g_surfaceHeight{720};
std::atomic<bool> g_cursorGrabbed{false};
std::atomic<bool> g_inputReady{false};

std::atomic<double> g_cursorX{640.0};
std::atomic<double> g_cursorY{360.0};
std::atomic<double> g_lastCursorX{640.0};
std::atomic<double> g_lastCursorY{360.0};

std::string g_jreHomePath;
std::string g_nativesDirPath;
std::string g_logFilePath;
std::atomic<bool> g_stdioRedirected{false};
int g_stdioPipe[2] = {-1, -1};

JavaVM* g_dalvikJavaVM = nullptr;
JavaVM* g_runtimeJavaVM = nullptr;
jclass g_vmGlfwClass = nullptr;
jmethodID g_methodInternalChangeMonitorSize = nullptr;
jmethodID g_methodInternalWindowSizeChanged = nullptr;
jbyte* g_glfwKeyDownBuffer = nullptr;
jbyte* g_glfwMouseDownBuffer = nullptr;
jlong g_showingWindow = 1;

uint8_t g_gamepadButtons[64] = {0};
float g_gamepadAxes[16] = {0.0f};

// GLFW callback function pointers registered by org.lwjgl.glfw.GLFW
using GLFW_invoke_Char_t        = void (*)(void* window, unsigned int codepoint);
using GLFW_invoke_CharMods_t    = void (*)(void* window, unsigned int codepoint, int mods);
using GLFW_invoke_CursorEnter_t = void (*)(void* window, int entered);
using GLFW_invoke_CursorPos_t   = void (*)(void* window, double xpos, double ypos);
using GLFW_invoke_Key_t         = void (*)(void* window, int key, int scancode, int action, int mods);
using GLFW_invoke_MouseButton_t = void (*)(void* window, int button, int action, int mods);
using GLFW_invoke_Scroll_t      = void (*)(void* window, double xoffset, double yoffset);

std::atomic<GLFW_invoke_Char_t>        g_cbChar{nullptr};
std::atomic<GLFW_invoke_CharMods_t>    g_cbCharMods{nullptr};
std::atomic<GLFW_invoke_CursorEnter_t> g_cbCursorEnter{nullptr};
std::atomic<GLFW_invoke_CursorPos_t>   g_cbCursorPos{nullptr};
std::atomic<GLFW_invoke_Key_t>         g_cbKey{nullptr};
std::atomic<GLFW_invoke_MouseButton_t> g_cbMouseButton{nullptr};
std::atomic<GLFW_invoke_Scroll_t>      g_cbScroll{nullptr};

// EGL state for pojavInit / pojavCreateContext / pojavMakeCurrent / pojavSwapBuffers
EGLDisplay g_eglDisplay = EGL_NO_DISPLAY;
EGLSurface g_eglSurface = EGL_NO_SURFACE;
EGLContext g_eglContext = EGL_NO_CONTEXT;
EGLConfig  g_eglConfig  = nullptr;
EGLint     g_eglFormat  = 0;

// Exit / execve interception state so JLI_Launch / libjvm.so exit() never kills the Android process
pthread_t g_jvmLauncherThread{};
std::atomic<bool> g_inJliLaunch{false};
std::atomic<int> g_interceptedExitCode{0};
sigjmp_buf g_jvmExitJmpBuf;

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

using android_update_LD_LIBRARY_PATH_t = void (*)(const char*);

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

void drainStdioPipeNonBlocking() {
    if (g_stdioPipe[0] < 0) return;
    int flags = fcntl(g_stdioPipe[0], F_GETFL, 0);
    fcntl(g_stdioPipe[0], F_SETFL, flags | O_NONBLOCK);

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
    fcntl(g_stdioPipe[0], F_SETFL, flags);
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

extern "C" void velocraft_hooked_exit(int code) {
    std::fflush(stdout);
    std::fflush(stderr);
    drainStdioPipeNonBlocking();
    g_interceptedExitCode.store(code, std::memory_order_release);
    appendNativeLineToLogFile(
        "EXIT-HOOK",
        "Intercepted native exit(" + std::to_string(code) + ") from OpenJDK/JVM without killing Android UI."
    );

    if (g_inJliLaunch.load(std::memory_order_acquire)) {
        if (pthread_equal(pthread_self(), g_jvmLauncherThread)) {
            siglongjmp(g_jvmExitJmpBuf, (code == 0) ? 1000 : code);
        } else {
            pthread_exit(reinterpret_cast<void*>(static_cast<intptr_t>(code)));
        }
    }
    pthread_exit(reinterpret_cast<void*>(static_cast<intptr_t>(code)));
}

extern "C" int velocraft_hooked_execve(const char* pathname, char* const argv[], char* const envp[]) {
    (void)argv;
    (void)envp;
    std::fflush(stdout);
    std::fflush(stderr);
    drainStdioPipeNonBlocking();
    appendNativeLineToLogFile(
        "EXEC-HOOK",
        std::string("Blocked JLI re-execve(") + (pathname ? pathname : "null") + ") on Android."
    );
    errno = EPERM;
    return -1;
}

void patchRelTable(
    ElfW(Addr) baseAddr,
    const Elf_Rel* rels,
    size_t relCount,
    const Elf_Sym* symtab,
    const char* strtab
) {
    const long pageSize = sysconf(_SC_PAGESIZE);
    for (size_t i = 0; i < relCount; ++i) {
        size_t symIdx = ELF_R_SYM(rels[i].r_info);
        if (symIdx == 0) continue;
        const char* symName = strtab + symtab[symIdx].st_name;
        void* replacement = nullptr;
        if (std::strcmp(symName, "exit") == 0 || std::strcmp(symName, "_exit") == 0) {
            replacement = reinterpret_cast<void*>(&velocraft_hooked_exit);
        } else if (std::strcmp(symName, "execve") == 0 || std::strcmp(symName, "execv") == 0) {
            replacement = reinterpret_cast<void*>(&velocraft_hooked_execve);
        }
        if (replacement != nullptr) {
            auto* slot = reinterpret_cast<void**>(baseAddr + rels[i].r_offset);
            uintptr_t pageStart = reinterpret_cast<uintptr_t>(slot) & ~(pageSize - 1);
            if (mprotect(reinterpret_cast<void*>(pageStart), pageSize, PROT_READ | PROT_WRITE) == 0) {
                *slot = replacement;
            }
        }
    }
}

int dlPhdrCallback(struct dl_phdr_info* info, size_t /*size*/, void* /*data*/) {
    if (!info->dlpi_name) return 0;
    if (std::strstr(info->dlpi_name, "libjli.so") == nullptr &&
        std::strstr(info->dlpi_name, "libjvm.so") == nullptr &&
        std::strstr(info->dlpi_name, "libjava.so") == nullptr) {
        return 0;
    }

    const Elf_Dyn* dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const Elf_Dyn*>(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    const Elf_Sym* symtab = nullptr;
    const char* strtab = nullptr;
    const Elf_Rel* jmprel = nullptr;
    size_t pltrelsz = 0;
    const Elf_Rel* rel = nullptr;
    size_t relsz = 0;

    for (const Elf_Dyn* d = dyn; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const Elf_Sym*>(
                    d->d_un.d_ptr < info->dlpi_addr ? info->dlpi_addr + d->d_un.d_ptr : d->d_un.d_ptr
                );
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char*>(
                    d->d_un.d_ptr < info->dlpi_addr ? info->dlpi_addr + d->d_un.d_ptr : d->d_un.d_ptr
                );
                break;
            case DT_JMPREL:
                jmprel = reinterpret_cast<const Elf_Rel*>(
                    d->d_un.d_ptr < info->dlpi_addr ? info->dlpi_addr + d->d_un.d_ptr : d->d_un.d_ptr
                );
                break;
            case DT_PLTRELSZ:
                pltrelsz = d->d_un.d_val;
                break;
            case DT_REL:
            case DT_RELA:
                rel = reinterpret_cast<const Elf_Rel*>(
                    d->d_un.d_ptr < info->dlpi_addr ? info->dlpi_addr + d->d_un.d_ptr : d->d_un.d_ptr
                );
                break;
            case DT_RELSZ:
            case DT_RELASZ:
                relsz = d->d_un.d_val;
                break;
            default:
                break;
        }
    }

    if (symtab && strtab) {
        if (jmprel && pltrelsz > 0) {
            patchRelTable(info->dlpi_addr, jmprel, pltrelsz / sizeof(Elf_Rel), symtab, strtab);
        }
        if (rel && relsz > 0) {
            patchRelTable(info->dlpi_addr, rel, relsz / sizeof(Elf_Rel), symtab, strtab);
        }
    }
    return 0;
}

void installGotExitAndExecHooks() {
    dl_iterate_phdr(dlPhdrCallback, nullptr);
    appendNativeLineToLogFile("HOOK", "Installed ELF GOT exit/execve protection hooks on OpenJDK libraries.");
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
        appendNativeLineToLogFile("LINKER", "Updated Android Bionic LD_LIBRARY_PATH via libdl.so");
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

JNIEnv* getAttachedRuntimeEnv() {
    if (!g_runtimeJavaVM) return nullptr;
    JNIEnv* env = nullptr;
    jint res = g_runtimeJavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_4);
    if (res == JNI_EDETACHED) {
        if (g_runtimeJavaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

static jlong ndlopen_bugfix(JNIEnv* /*env*/, jclass /*cls*/, jlong filename_ptr, jint jmode) {
    const char* filename = reinterpret_cast<const char*>(filename_ptr);
    if (!filename) return 0;
    void* handle = dlopen(filename, static_cast<int>(jmode) | RTLD_GLOBAL);
    if (!handle && !g_nativesDirPath.empty() && std::strchr(filename, '/') == nullptr) {
        std::string fullPath = g_nativesDirPath + "/" + filename;
        handle = dlopen(fullPath.c_str(), static_cast<int>(jmode) | RTLD_GLOBAL);
    }
    return reinterpret_cast<jlong>(handle);
}

void installLwjglDlopenHookOnJvm(JNIEnv* vmEnv) {
    jclass dynLoader = vmEnv->FindClass("org/lwjgl/system/linux/DynamicLinkLoader");
    if (!dynLoader) {
        vmEnv->ExceptionClear();
        return;
    }
    JNINativeMethod m[] = {
        {const_cast<char*>("ndlopen"), const_cast<char*>("(JI)J"), reinterpret_cast<void*>(&ndlopen_bugfix)}
    };
    vmEnv->RegisterNatives(dynLoader, m, 1);
    vmEnv->ExceptionClear();
}

static void gl4esGetMainFbSize(int* width, int* height) {
    if (width) *width = g_surfaceWidth.load(std::memory_order_relaxed);
    if (height) *height = g_surfaceHeight.load(std::memory_order_relaxed);
}

} // anonymous namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    if (g_dalvikJavaVM == nullptr) {
        g_dalvikJavaVM = vm;
    } else if (g_dalvikJavaVM != vm) {
        g_runtimeJavaVM = vm;
        JNIEnv* vmEnv = nullptr;
        if (vm->GetEnv(reinterpret_cast<void**>(&vmEnv), JNI_VERSION_1_4) == JNI_OK && vmEnv) {
            appendNativeLineToLogFile("GLFW", "JNI_OnLoad invoked inside OpenJDK HotSpot VM.");
            jclass glfwCls = vmEnv->FindClass("org/lwjgl/glfw/GLFW");
            if (glfwCls) {
                g_vmGlfwClass = reinterpret_cast<jclass>(vmEnv->NewGlobalRef(glfwCls));
                g_methodInternalChangeMonitorSize = vmEnv->GetStaticMethodID(
                    g_vmGlfwClass, "internalChangeMonitorSize", "(II)V"
                );
                g_methodInternalWindowSizeChanged = vmEnv->GetStaticMethodID(
                    g_vmGlfwClass, "internalWindowSizeChanged", "(J)V"
                );
                jfieldID fKey = vmEnv->GetStaticFieldID(g_vmGlfwClass, "keyDownBuffer", "Ljava/nio/ByteBuffer;");
                if (fKey) {
                    jobject keyBuf = vmEnv->GetStaticObjectField(g_vmGlfwClass, fKey);
                    if (keyBuf) {
                        g_glfwKeyDownBuffer = reinterpret_cast<jbyte*>(vmEnv->GetDirectBufferAddress(keyBuf));
                    }
                }
                jfieldID fMouse = vmEnv->GetStaticFieldID(g_vmGlfwClass, "mouseDownBuffer", "Ljava/nio/ByteBuffer;");
                if (fMouse) {
                    jobject mouseBuf = vmEnv->GetStaticObjectField(g_vmGlfwClass, fMouse);
                    if (mouseBuf) {
                        g_glfwMouseDownBuffer = reinterpret_cast<jbyte*>(vmEnv->GetDirectBufferAddress(mouseBuf));
                    }
                }
            }
            vmEnv->ExceptionClear();
            installLwjglDlopenHookOnJvm(vmEnv);
        }
    }
    return JNI_VERSION_1_6;
}

// =========================================================================================
// PojavExec / LWJGL3-Android GLFW C Symbols (Loaded by org.lwjgl.glfw.GLFW$Functions)
// =========================================================================================

__attribute__((visibility("default"), used))
int pojavInit() {
    appendNativeLineToLogFile("EGL", "pojavInit() initializing EGL display & monitor dimensions...");
    g_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_eglDisplay == EGL_NO_DISPLAY) {
        appendNativeLineToLogFile("EGL-ERR", "eglGetDisplay returned EGL_NO_DISPLAY");
        return 0;
    }
    if (eglInitialize(g_eglDisplay, nullptr, nullptr) != EGL_TRUE) {
        appendNativeLineToLogFile("EGL-ERR", "eglInitialize failed");
        return 0;
    }

    int w = g_surfaceWidth.load(std::memory_order_relaxed);
    int h = g_surfaceHeight.load(std::memory_order_relaxed);
    ANativeWindow* win = g_nativeWindow.load(std::memory_order_acquire);
    if (win) {
        ANativeWindow_acquire(win);
        int nw = ANativeWindow_getWidth(win);
        int nh = ANativeWindow_getHeight(win);
        if (nw > 0 && nh > 0) {
            w = nw;
            h = nh;
            g_surfaceWidth.store(w, std::memory_order_relaxed);
            g_surfaceHeight.store(h, std::memory_order_relaxed);
        }
    }

    JNIEnv* vmEnv = getAttachedRuntimeEnv();
    if (vmEnv && g_vmGlfwClass && g_methodInternalChangeMonitorSize) {
        vmEnv->CallStaticVoidMethod(g_vmGlfwClass, g_methodInternalChangeMonitorSize, w, h);
        vmEnv->ExceptionClear();
    }
    appendNativeLineToLogFile(
        "EGL",
        "pojavInit() ready: resolution=" + std::to_string(w) + "x" + std::to_string(h)
    );
    return 1;
}

__attribute__((visibility("default"), used))
void* pojavCreateContext(void* contextSrc) {
    if (g_eglDisplay == EGL_NO_DISPLAY) {
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
    eglChooseConfig(g_eglDisplay, configAttribs, &g_eglConfig, 1, &numConfigs);
    eglGetConfigAttrib(g_eglDisplay, g_eglConfig, EGL_NATIVE_VISUAL_ID, &g_eglFormat);
    eglBindAPI(EGL_OPENGL_ES_API);

    const EGLint ctxAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };
    EGLContext shareCtx = contextSrc ? reinterpret_cast<EGLContext>(contextSrc) : EGL_NO_CONTEXT;
    g_eglContext = eglCreateContext(g_eglDisplay, g_eglConfig, shareCtx, ctxAttribs);
    appendNativeLineToLogFile("EGL", "Created EGLContext for Minecraft window.");
    return reinterpret_cast<void*>(g_eglContext ? g_eglContext : reinterpret_cast<void*>(1));
}

__attribute__((visibility("default"), used))
void* pojavGetCurrentContext() {
    return reinterpret_cast<void*>(g_eglContext);
}

__attribute__((visibility("default"), used))
void pojavMakeCurrent(void* window) {
    if (window == nullptr) {
        eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return;
    }
    if (g_eglSurface == EGL_NO_SURFACE) {
        ANativeWindow* win = g_nativeWindow.load(std::memory_order_acquire);
        if (win != nullptr) {
            ANativeWindow_setBuffersGeometry(win, 0, 0, g_eglFormat);
            g_eglSurface = eglCreateWindowSurface(g_eglDisplay, g_eglConfig, win, nullptr);
            appendNativeLineToLogFile("EGL", "Bound ANativeWindow to EGLSurface.");
        } else {
            const EGLint pbufAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            g_eglSurface = eglCreatePbufferSurface(g_eglDisplay, g_eglConfig, pbufAttribs);
        }
    }
    EGLContext ctx = reinterpret_cast<EGLContext>(window);
    if (eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, ctx) == EGL_TRUE) {
        g_eglContext = ctx;
    }
}

__attribute__((visibility("default"), used))
void pojavSwapBuffers() {
    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglSurface != EGL_NO_SURFACE) {
        eglSwapBuffers(g_eglDisplay, g_eglSurface);
    }
}

__attribute__((visibility("default"), used))
void pojavSwapInterval(int interval) {
    if (g_eglDisplay != EGL_NO_DISPLAY) {
        eglSwapInterval(g_eglDisplay, interval);
    }
}

__attribute__((visibility("default"), used))
void pojavSetWindowHint(int /*hint*/, int /*value*/) {
}

__attribute__((visibility("default"), used))
void pojavTerminate() {
    if (g_eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g_eglSurface != EGL_NO_SURFACE) {
            eglDestroySurface(g_eglDisplay, g_eglSurface);
            g_eglSurface = EGL_NO_SURFACE;
        }
        if (g_eglContext != EGL_NO_CONTEXT) {
            eglDestroyContext(g_eglDisplay, g_eglContext);
            g_eglContext = EGL_NO_CONTEXT;
        }
        eglTerminate(g_eglDisplay);
        g_eglDisplay = EGL_NO_DISPLAY;
    }
}

__attribute__((visibility("default"), used))
void pojavStartPumping() {
}

__attribute__((visibility("default"), used))
void pojavPumpEvents(void* window) {
    void* targetWin = window ? window : reinterpret_cast<void*>(g_showingWindow);
    double cx = g_cursorX.load(std::memory_order_relaxed);
    double cy = g_cursorY.load(std::memory_order_relaxed);
    double lx = g_lastCursorX.load(std::memory_order_relaxed);
    double ly = g_lastCursorY.load(std::memory_order_relaxed);

    auto cbPos = g_cbCursorPos.load(std::memory_order_acquire);
    if ((cx != lx || cy != ly) && cbPos != nullptr) {
        g_lastCursorX.store(cx, std::memory_order_relaxed);
        g_lastCursorY.store(cy, std::memory_order_relaxed);
        cbPos(targetWin, std::floor(cx), std::floor(cy));
    }

    InputEvent ev{};
    while (g_inputQueue.pop(ev)) {
        switch (ev.type) {
            case EventType::KEY_EVENT: {
                if (g_glfwKeyDownBuffer && ev.code >= 32 && ev.code < 348) {
                    g_glfwKeyDownBuffer[ev.code - 31] = static_cast<jbyte>(ev.action);
                }
                auto cbKey = g_cbKey.load(std::memory_order_acquire);
                if (cbKey) cbKey(targetWin, ev.code, 0, ev.action, ev.mods);
                break;
            }
            case EventType::MOUSE_BUTTON_EVENT: {
                if (g_glfwMouseDownBuffer && ev.code >= 0 && ev.code < 8) {
                    g_glfwMouseDownBuffer[ev.code] = static_cast<jbyte>(ev.action);
                }
                auto cbMouse = g_cbMouseButton.load(std::memory_order_acquire);
                if (cbMouse) cbMouse(targetWin, ev.code, ev.action, ev.mods);
                break;
            }
            case EventType::CURSOR_POS_EVENT:
            case EventType::CURSOR_DELTA_EVENT: {
                if (cbPos) {
                    cbPos(
                        targetWin,
                        std::floor(g_cursorX.load(std::memory_order_relaxed)),
                        std::floor(g_cursorY.load(std::memory_order_relaxed))
                    );
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

#define DEFINE_GLFW_CALLBACK_SETTER(NAME, STORAGE) \
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback( \
    JNIEnv* /*env*/, jclass /*cls*/, jlong /*window*/, jlong callbackptr \
) { \
    auto prev = STORAGE.exchange(reinterpret_cast<GLFW_invoke_##NAME##_t>(callbackptr), std::memory_order_acq_rel); \
    return reinterpret_cast<jlong>(prev); \
}

DEFINE_GLFW_CALLBACK_SETTER(Char, g_cbChar)
DEFINE_GLFW_CALLBACK_SETTER(CharMods, g_cbCharMods)
DEFINE_GLFW_CALLBACK_SETTER(CursorEnter, g_cbCursorEnter)
DEFINE_GLFW_CALLBACK_SETTER(CursorPos, g_cbCursorPos)
DEFINE_GLFW_CALLBACK_SETTER(Key, g_cbKey)
DEFINE_GLFW_CALLBACK_SETTER(MouseButton, g_cbMouseButton)
DEFINE_GLFW_CALLBACK_SETTER(Scroll, g_cbScroll)

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(JNIEnv* /*env*/, jclass /*cls*/, jlong window) {
    g_showingWindow = window;
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer(JNIEnv* /*env*/, jclass /*cls*/) {
    return reinterpret_cast<jlong>(g_gamepadButtons);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(
    JNIEnv* env, jclass /*cls*/, jlong /*window*/, jobject xpos, jobject ypos
) {
    if (xpos) {
        auto* px = reinterpret_cast<double*>(env->GetDirectBufferAddress(xpos));
        if (px) *px = g_cursorX.load(std::memory_order_relaxed);
    }
    if (ypos) {
        auto* py = reinterpret_cast<double*>(env->GetDirectBufferAddress(ypos));
        if (py) *py = g_cursorY.load(std::memory_order_relaxed);
    }
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(
    JNIEnv* env, jclass /*cls*/, jlong /*window*/, jdoubleArray xpos, jdoubleArray ypos
) {
    double cx = g_cursorX.load(std::memory_order_relaxed);
    double cy = g_cursorY.load(std::memory_order_relaxed);
    if (xpos) env->SetDoubleArrayRegion(xpos, 0, 1, &cx);
    if (ypos) env->SetDoubleArrayRegion(ypos, 0, 1, &cy);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(
    JNIEnv* /*env*/, jclass /*cls*/, jlong /*window*/, jdouble xpos, jdouble ypos
) {
    g_cursorX.store(xpos, std::memory_order_relaxed);
    g_cursorY.store(ypos, std::memory_order_relaxed);
    g_lastCursorX.store(xpos, std::memory_order_relaxed);
    g_lastCursorY.store(ypos, std::memory_order_relaxed);
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
    g_inputReady.store(ready == JNI_TRUE, std::memory_order_release);
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
    g_cursorGrabbed.store(grab == JNI_TRUE, std::memory_order_release);
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadButtonBuffer(JNIEnv* env, jclass /*cls*/) {
    return env->NewDirectByteBuffer(g_gamepadButtons, sizeof(g_gamepadButtons));
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadAxisBuffer(JNIEnv* env, jclass /*cls*/) {
    return env->NewDirectByteBuffer(g_gamepadAxes, sizeof(g_gamepadAxes));
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
        appendNativeLineToLogFile("GL4ES", "Initialized GL4ES main framebuffer dimension callback.");
    }
}

// =========================================================================================
// NativeEngineBridge JNI Methods (Called by Kotlin GameActivity & ProHudOverlayView)
// =========================================================================================

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
    if (outX) *outX = static_cast<float>(g_cursorX.load(std::memory_order_relaxed));
    if (outY) *outY = static_cast<float>(g_cursorY.load(std::memory_order_relaxed));
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
        g_cursorX.store(width * 0.5, std::memory_order_relaxed);
        g_cursorY.store(height * 0.5, std::memory_order_relaxed);
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
    const double nextX = g_cursorX.load(std::memory_order_relaxed) + deltaX;
    const double nextY = g_cursorY.load(std::memory_order_relaxed) + deltaY;
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
    const char* jreHomeRaw = env->GetStringUTFChars(jreHomePath, nullptr);
    const char* rendererLib = env->GetStringUTFChars(rendererLibName, nullptr);
    const char* cacheDir = env->GetStringUTFChars(cacheDirPath, nullptr);

    char resolvedJre[PATH_MAX];
    if (realpath(jreHomeRaw, resolvedJre) != nullptr) {
        g_jreHomePath = resolvedJre;
    } else {
        g_jreHomePath = jreHomeRaw;
    }

    // Derive nativesDir sibling of jreDir
    std::string engineRoot = g_jreHomePath;
    auto slashPos = engineRoot.find_last_of('/');
    if (slashPos != std::string::npos) {
        engineRoot = engineRoot.substr(0, slashPos);
    }
    g_nativesDirPath = engineRoot + "/natives-" + VELOCRAFT_ABI_NAME;
    g_logFilePath = "/storage/emulated/0/Android/media/id.xyverse.minecraft/log/latest.log";

    // IMPORTANT: Do NOT include "lib/server" or "lib/client" in setenv("LD_LIBRARY_PATH")!
    // OpenJDK's RequiresSetenv() checks ContainsLibJVM(getenv("LD_LIBRARY_PATH")).
    // Keeping "lib/server" out of setenv("LD_LIBRARY_PATH") guarantees mustsetenv == JNI_FALSE
    // so libjli.so NEVER attempts execve("/proc/self/exe").
    std::string envLdPath = g_jreHomePath + "/lib/jli:" +
                            g_jreHomePath + "/lib:" +
                            SYSTEM_LIB_DIR + ":" +
                            g_nativesDirPath;

    // Pass full path including lib/server to Android Bionic's internal linker update function
    std::string internalLinkerPath = g_jreHomePath + "/lib/server:" + envLdPath;

    setenv("JAVA_HOME", g_jreHomePath.c_str(), 1);
    setenv("HOME", (engineRoot + "/minecraft").c_str(), 1);
    setenv("TMPDIR", cacheDir, 1);
    setenv("LD_LIBRARY_PATH", envLdPath.c_str(), 1);
    setenv("PATH", (g_jreHomePath + "/bin:/system/bin").c_str(), 1);
    setenv("POJAV_NATIVEDIR", g_nativesDirPath.c_str(), 1);
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
        "JAVA_HOME=" + g_jreHomePath + " | RENDERER=" + rendererLib + " | NATIVES=" + g_nativesDirPath
    );

    env->ReleaseStringUTFChars(jreHomePath, jreHomeRaw);
    env->ReleaseStringUTFChars(rendererLibName, rendererLib);
    env->ReleaseStringUTFChars(cacheDirPath, cacheDir);
}

JNIEXPORT jint JNICALL
Java_io_velocraft_android_bridge_NativeEngineBridge_nativeLaunchDirectJvm(
    JNIEnv* env, jobject /*thiz*/, jstring jliLibPath, jobjectArray jvmArgsArray
) {
    ensureStdioRedirectedToLog();
    pinThreadToPerformanceCores();

    // Remove duplicate lib/jli/libjli.so if lib/libjli.so exists so only ONE libjli.so is loaded
    std::string primaryJli = g_jreHomePath + "/lib/libjli.so";
    std::string dupJli = g_jreHomePath + "/lib/jli/libjli.so";
    if (access(primaryJli.c_str(), R_OK) == 0 && access(dupJli.c_str(), F_OK) == 0) {
        unlink(dupJli.c_str());
    }

    // 1. Preload core OpenJDK shared libraries in strict dependency order
    if (!g_jreHomePath.empty()) {
        const std::vector<std::string> orderedCoreLibs = {
            g_jreHomePath + "/lib/libjli.so",
            g_jreHomePath + "/lib/server/libjvm.so",
            g_jreHomePath + "/lib/libverify.so",
            g_jreHomePath + "/lib/libjava.so",
            g_jreHomePath + "/lib/libzip.so",
            g_jreHomePath + "/lib/libjimage.so",
            g_jreHomePath + "/lib/libnet.so",
            g_jreHomePath + "/lib/libnio.so",
            g_jreHomePath + "/lib/libawt.so",
            g_jreHomePath + "/lib/libawt_headless.so",
            g_jreHomePath + "/lib/libfreetype.so",
            g_jreHomePath + "/lib/libfontmanager.so"
        };
        for (const auto& soPath : orderedCoreLibs) {
            if (access(soPath.c_str(), R_OK) == 0) {
                void* handle = dlopen(soPath.c_str(), RTLD_LAZY | RTLD_GLOBAL);
                if (handle) {
                    appendNativeLineToLogFile("DLOPEN", "Loaded OpenJDK core library: " + soPath);
                } else {
                    const char* err = dlerror();
                    appendNativeLineToLogFile(
                        "DLOPEN-WARN",
                        "Could not load " + soPath + ": " + (err ? err : "unknown")
                    );
                }
            }
        }

        // Load remaining .so libraries in <jreHome>/lib (skipping libjsig.so)
        std::string libDirStr = g_jreHomePath + "/lib";
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

    // 2. Preload Android LWJGL3 + OpenAL + Renderer + PojavExec bridge libraries from nativesDir
    if (!g_nativesDirPath.empty()) {
        const std::vector<std::string> nativeEngineLibs = {
            g_nativesDirPath + "/libfreetype.so",
            g_nativesDirPath + "/libopenal.so",
            g_nativesDirPath + "/libgl4es_114.so",
            g_nativesDirPath + "/libjnidispatch.so",
            g_nativesDirPath + "/liblwjgl.so",
            g_nativesDirPath + "/liblwjgl_opengl.so",
            g_nativesDirPath + "/liblwjgl_stb.so",
            g_nativesDirPath + "/liblwjgl_tinyfd.so",
            g_nativesDirPath + "/liblwjgl_nanovg.so",
            g_nativesDirPath + "/libpojavexec.so"
        };
        for (const auto& soPath : nativeEngineLibs) {
            if (access(soPath.c_str(), R_OK) == 0) {
                void* h = dlopen(soPath.c_str(), RTLD_LAZY | RTLD_GLOBAL);
                if (h) {
                    appendNativeLineToLogFile("NATIVES", "Loaded native engine library: " + soPath);
                } else {
                    const char* err = dlerror();
                    appendNativeLineToLogFile("NATIVES-WARN", "Failed " + soPath + ": " + (err ? err : "unknown"));
                }
            }
        }
    }

    const char* jliPath = env->GetStringUTFChars(jliLibPath, nullptr);
    std::string chosenJliPath = (access(primaryJli.c_str(), R_OK) == 0) ? primaryJli : std::string(jliPath);
    env->ReleaseStringUTFChars(jliLibPath, jliPath);

    appendNativeLineToLogFile("BOOT", "Opening JLI entrypoint: " + chosenJliPath);
    void* libJli = dlopen(chosenJliPath.c_str(), RTLD_LAZY | RTLD_GLOBAL);
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

    // Install GOT exit/execve hooks so JLI/HotSpot exit() never kills the Android app process
    installGotExitAndExecHooks();

    const jsize argc = env->GetArrayLength(jvmArgsArray);
    std::vector<char*> argv(argc + 1, nullptr);
    for (jsize i = 0; i < argc; ++i) {
        auto jstr = static_cast<jstring>(env->GetObjectArrayElement(jvmArgsArray, i));
        const char* utf = env->GetStringUTFChars(jstr, nullptr);
        argv[i] = strdup(utf);
        env->ReleaseStringUTFChars(jstr, utf);
        env->DeleteLocalRef(jstr);
    }
    argv[argc] = nullptr;

    appendNativeLineToLogFile(
        "BOOT",
        "Invoking OpenJDK JLI_Launch (" + std::string(VELOCRAFT_ABI_NAME) + ") with " + std::to_string(argc) + " arguments..."
    );

    resetSignalsForHotSpot();
    g_jvmLauncherThread = pthread_self();
    g_interceptedExitCode.store(0, std::memory_order_release);
    g_inJliLaunch.store(true, std::memory_order_release);

    int exitCode = 0;
    int jmpVal = sigsetjmp(g_jvmExitJmpBuf, 1);
    if (jmpVal == 0) {
        exitCode = jliLaunch(
            argc, argv.data(),
            0, nullptr,
            0, nullptr,
            "1.8.0-internal", "1.8",
            argv[0] ? argv[0] : "java",
            argv[0] ? argv[0] : "openjdk",
            JNI_FALSE, JNI_TRUE, JNI_FALSE, 0
        );
    } else {
        exitCode = (jmpVal == 1000) ? 0 : jmpVal;
    }

    g_inJliLaunch.store(false, std::memory_order_release);
    std::fflush(stdout);
    std::fflush(stderr);
    drainStdioPipeNonBlocking();

    int intercepted = g_interceptedExitCode.load(std::memory_order_acquire);
    if (exitCode == 0 && intercepted != 0) {
        exitCode = intercepted;
    }

    appendNativeLineToLogFile("EXIT", "JLI_Launch completed with exit code: " + std::to_string(exitCode));

    for (jsize i = 0; i < argc; ++i) {
        free(argv[i]);
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
