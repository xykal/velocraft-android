#include "velocraft_turbo.h"

#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <atomic>
#include <mutex>
#include <sys/resource.h>
#include <unistd.h>

#define TURBO_TAG "VeloCraftTurbo"
#define TURBO_LOGI(...) __android_log_print(ANDROID_LOG_INFO, TURBO_TAG, __VA_ARGS__)

typedef unsigned int GLenum;
typedef unsigned char GLboolean;
typedef int GLint;
typedef int GLsizei;
typedef unsigned int GLuint;
typedef unsigned int GLbitfield;
typedef int64_t GLint64;
typedef uint64_t GLuint64;
typedef ptrdiff_t GLintptr;
typedef ptrdiff_t GLsizeiptr;
typedef void* GLsync;

#ifndef GL_TRUE
#define GL_TRUE 1
#define GL_FALSE 0
#endif

#ifndef GL_ALREADY_SIGNALED
#define GL_ALREADY_SIGNALED 0x911A
#define GL_SIGNALED 0x9119
#define GL_SYNC_STATUS 0x9114
#endif

typedef void* (*PFN_glXGetProcAddress)(const char* name);
typedef void (*PFN_glDrawElements)(GLenum mode, GLsizei count, GLenum type, const void* indices);
typedef void (*PFN_glDrawElementsBaseVertex)(GLenum mode, GLsizei count, GLenum type, const void* indices, GLint basevertex);
typedef int (*PFN_malloc_trim)(size_t pad);

static void* s_gl_handle = nullptr;
static PFN_glXGetProcAddress s_gl4es_get_proc = nullptr;
static PFN_glDrawElements s_real_glDrawElements = nullptr;
static PFN_glDrawElementsBaseVertex s_real_glDrawElementsBaseVertex = nullptr;

static std::atomic<uint64_t> s_multiDrawCallCount{0};
static std::atomic<uint64_t> s_vboCopyBytes{0};
static std::atomic<uintptr_t> s_syncTokenCounter{0x56454C00u};

static void* resolve_gl4es_proc(const char* name) {
    if (s_gl4es_get_proc) {
        void* sym = s_gl4es_get_proc(name);
        if (sym) return sym;
    }
    if (s_gl_handle) {
        void* sym = dlsym(s_gl_handle, name);
        if (sym) return sym;
    }
    return nullptr;
}

extern "C" void velocraft_turbo_init_gl_hooks(void* glHandle) {
    if (!glHandle) return;
    s_gl_handle = glHandle;

    if (!s_gl4es_get_proc) {
        s_gl4es_get_proc = reinterpret_cast<PFN_glXGetProcAddress>(
            dlsym(glHandle, "gl4es_glXGetProcAddress")
        );
        if (!s_gl4es_get_proc) {
            s_gl4es_get_proc = reinterpret_cast<PFN_glXGetProcAddress>(
                dlsym(glHandle, "glXGetProcAddress")
            );
        }
    }

    if (!s_real_glDrawElements) {
        s_real_glDrawElements = reinterpret_cast<PFN_glDrawElements>(
            resolve_gl4es_proc("glDrawElements")
        );
    }
    if (!s_real_glDrawElementsBaseVertex) {
        s_real_glDrawElementsBaseVertex = reinterpret_cast<PFN_glDrawElementsBaseVertex>(
            resolve_gl4es_proc("glDrawElementsBaseVertex")
        );
    }
}

// =========================================================================================
// 1. Safe C++ glMultiDrawElements / glMultiDrawElementsBaseVertex Unroller
//    Fixes GL4ES 1.1.5 `drawing.c` pointer arithmetic bug (`indices + i` vs `indices[i]`).
// =========================================================================================

static void turbo_glMultiDrawElements(
    GLenum mode,
    const GLsizei* count,
    GLenum type,
    const void* const* indices,
    GLsizei drawcount
) {
    if (!count || !indices || drawcount <= 0 || !s_real_glDrawElements) return;
    s_multiDrawCallCount.fetch_add(static_cast<uint64_t>(drawcount), std::memory_order_relaxed);
    for (GLsizei i = 0; i < drawcount; ++i) {
        GLsizei c = count[i];
        if (c > 0) {
            s_real_glDrawElements(mode, c, type, indices[i]);
        }
    }
}

static void turbo_glMultiDrawElementsBaseVertex(
    GLenum mode,
    const GLsizei* count,
    GLenum type,
    const void* const* indices,
    GLsizei drawcount,
    const GLint* basevertex
) {
    if (!count || !indices || drawcount <= 0) return;
    s_multiDrawCallCount.fetch_add(static_cast<uint64_t>(drawcount), std::memory_order_relaxed);

    for (GLsizei i = 0; i < drawcount; ++i) {
        GLsizei c = count[i];
        if (c <= 0) continue;
        const void* idxPtr = indices[i];
        GLint bv = basevertex ? basevertex[i] : 0;

        if (bv == 0 && s_real_glDrawElements) {
            s_real_glDrawElements(mode, c, type, idxPtr);
        } else if (s_real_glDrawElementsBaseVertex) {
            s_real_glDrawElementsBaseVertex(mode, c, type, idxPtr, bv);
        } else if (s_real_glDrawElements) {
            s_real_glDrawElements(mode, c, type, idxPtr);
        }
    }
}

// =========================================================================================
// 2. OpenGL 3.2 Sync Object Emulation (`glFenceSync`, `glClientWaitSync`, `glDeleteSync`)
//    In `libgl4es_114.so`, `glFenceSync` is an unimplemented stub returning 0L, which
//    causes any mod calling `GL32C.glFenceSync` to throw RuntimeException("Failed to create fence object").
// =========================================================================================

static GLsync turbo_glFenceSync(GLenum condition, GLbitfield flags) {
    (void)condition;
    (void)flags;
    uintptr_t token = s_syncTokenCounter.fetch_add(1, std::memory_order_relaxed);
    if (token == 0) token = 1;
    return reinterpret_cast<GLsync>(token);
}

static GLboolean turbo_glIsSync(GLsync sync) {
    return (sync != nullptr) ? GL_TRUE : GL_FALSE;
}

static void turbo_glDeleteSync(GLsync sync) {
    (void)sync;
}

static GLenum turbo_glClientWaitSync(GLsync sync, GLbitfield flags, GLuint64 timeout) {
    (void)sync;
    (void)flags;
    (void)timeout;
    return GL_ALREADY_SIGNALED;
}

static void turbo_glWaitSync(GLsync sync, GLbitfield flags, GLuint64 timeout) {
    (void)sync;
    (void)flags;
    (void)timeout;
}

static void turbo_glGetSynciv(GLsync sync, GLenum pname, GLsizei bufSize, GLsizei* length, GLint* values) {
    (void)sync;
    (void)pname;
    if (length) *length = 1;
    if (values && bufSize > 0) {
        values[0] = GL_SIGNALED;
    }
}

// =========================================================================================
// 3. Symbol Interceptor Entrypoint
// =========================================================================================

extern "C" void* velocraft_turbo_intercept_gl_proc(const char* name, void* originalSym) {
    if (!name) return originalSym;

    // OpenGL 3.2 Sync Objects (unimplemented stubs in libgl4es_114.so)
    if (strcmp(name, "glFenceSync") == 0) {
        return reinterpret_cast<void*>(&turbo_glFenceSync);
    }
    if (strcmp(name, "glClientWaitSync") == 0) {
        return reinterpret_cast<void*>(&turbo_glClientWaitSync);
    }
    if (strcmp(name, "glWaitSync") == 0) {
        return reinterpret_cast<void*>(&turbo_glWaitSync);
    }
    if (strcmp(name, "glDeleteSync") == 0) {
        return reinterpret_cast<void*>(&turbo_glDeleteSync);
    }
    if (strcmp(name, "glGetSynciv") == 0 || strcmp(name, "glGetSynci") == 0) {
        return reinterpret_cast<void*>(&turbo_glGetSynciv);
    }
    if (strcmp(name, "glIsSync") == 0) {
        return reinterpret_cast<void*>(&turbo_glIsSync);
    }

    // MultiDraw pointer bugfix (only if underlying gl4es draw functions are resolved)
    if (strcmp(name, "glMultiDrawElementsBaseVertex") == 0 ||
        strcmp(name, "glMultiDrawElementsBaseVertexEXT") == 0) {
        if (s_real_glDrawElementsBaseVertex || s_real_glDrawElements) {
            return reinterpret_cast<void*>(&turbo_glMultiDrawElementsBaseVertex);
        }
    }
    if (strcmp(name, "glMultiDrawElements") == 0 ||
        strcmp(name, "glMultiDrawElementsEXT") == 0) {
        if (s_real_glDrawElements) {
            return reinterpret_cast<void*>(&turbo_glMultiDrawElements);
        }
    }

    return originalSym;
}

// =========================================================================================
// 4. Hardware Display Composer Resolution Scaler & Render Thread Scheduler
// =========================================================================================

extern "C" void velocraft_turbo_compute_scaled_dimensions(
    int physicalW,
    int physicalH,
    float scale,
    int* outRenderW,
    int* outRenderH
) {
    if (scale < 0.35f) scale = 0.35f;
    if (scale > 1.0f) scale = 1.0f;

    int rw = static_cast<int>(static_cast<float>(physicalW) * scale);
    int rh = static_cast<int>(static_cast<float>(physicalH) * scale);

    // Align to even pixel dimensions for GPU tile-based rasterizers (PowerVR / Mali / Adreno)
    rw = (rw + 1) & ~1;
    rh = (rh + 1) & ~1;

    if (rw < 320) rw = 320;
    if (rh < 240) rh = 240;

    if (outRenderW) *outRenderW = rw;
    if (outRenderH) *outRenderH = rh;
}

extern "C" void velocraft_turbo_apply_window_geometry(
    ANativeWindow* window,
    int renderW,
    int renderH,
    int format
) {
    if (!window || renderW <= 0 || renderH <= 0) return;
    ANativeWindow_setBuffersGeometry(window, renderW, renderH, format);
    TURBO_LOGI("Hardware Surface Scaler active: Render Buffer %dx%d (format=%d)", renderW, renderH, format);
}

extern "C" void velocraft_turbo_boost_render_thread(void) {
    // Elevate Linux thread scheduling priority for the active OpenGL render thread
    // without touching ARMv7/ARM64 floating-point status registers on JVM threads.
    pid_t tid = gettid();
    setpriority(PRIO_PROCESS, static_cast<id_t>(tid), -10);
    TURBO_LOGI("Render thread priority boosted (TID=%d, nice=-10)", static_cast<int>(tid));
}

extern "C" uint64_t velocraft_turbo_get_multi_draw_calls(void) {
    return s_multiDrawCallCount.load(std::memory_order_relaxed);
}

extern "C" uint64_t velocraft_turbo_get_vbo_copy_bytes(void) {
    return s_vboCopyBytes.load(std::memory_order_relaxed);
}

extern "C" void velocraft_turbo_trim_scratch_buffers(void) {
    // Release unused native heap pages back to kernel on low-RAM devices
    PFN_malloc_trim trimFn = reinterpret_cast<PFN_malloc_trim>(dlsym(RTLD_DEFAULT, "malloc_trim"));
    if (trimFn) {
        trimFn(0);
    }
}
