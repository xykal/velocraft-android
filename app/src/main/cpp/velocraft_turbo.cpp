#include "velocraft_turbo.h"

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/time.h>
#include <unistd.h>
#include <atomic>
#include <vector>

#define TURBO_LOG_TAG "VeloCraftTurbo"
#define TURBO_LOGI(...) __android_log_print(ANDROID_LOG_INFO, TURBO_LOG_TAG, __VA_ARGS__)
#define TURBO_LOGW(...) __android_log_print(ANDROID_LOG_WARN, TURBO_LOG_TAG, __VA_ARGS__)

// Standard OpenGL types & constants needed for GL4ES 1.1.5 + Sodium 0.5.8 C++ interception
typedef unsigned int GLenum;
typedef unsigned char GLboolean;
typedef unsigned int GLbitfield;
typedef int GLint;
typedef unsigned int GLuint;
typedef int GLsizei;
typedef ptrdiff_t GLintptr;
typedef ptrdiff_t GLsizeiptr;
typedef void GLvoid;

#ifndef GL_FALSE
#define GL_FALSE 0
#endif
#ifndef GL_TRUE
#define GL_TRUE 1
#endif
#ifndef GL_UNSIGNED_BYTE
#define GL_UNSIGNED_BYTE 0x1401
#endif
#ifndef GL_UNSIGNED_SHORT
#define GL_UNSIGNED_SHORT 0x1403
#endif
#ifndef GL_UNSIGNED_INT
#define GL_UNSIGNED_INT 0x1405
#endif
#ifndef GL_ARRAY_BUFFER
#define GL_ARRAY_BUFFER 0x8892
#endif
#ifndef GL_ELEMENT_ARRAY_BUFFER
#define GL_ELEMENT_ARRAY_BUFFER 0x8893
#endif
#ifndef GL_COPY_READ_BUFFER
#define GL_COPY_READ_BUFFER 0x8F36
#endif
#ifndef GL_COPY_WRITE_BUFFER
#define GL_COPY_WRITE_BUFFER 0x8F37
#endif

// Underlying GL4ES function pointer types
typedef void (*PFN_glDrawElements)(GLenum mode, GLsizei count, GLenum type, const void* indices);
typedef void (*PFN_glDrawElementsBaseVertex)(GLenum mode, GLsizei count, GLenum type, const void* indices, GLint basevertex);
typedef void (*PFN_glBindBuffer)(GLenum target, GLuint buffer);
typedef void (*PFN_glDeleteBuffers)(GLsizei n, const GLuint* buffers);
typedef void (*PFN_glBufferData)(GLenum target, GLsizeiptr size, const void* data, GLenum usage);
typedef void (*PFN_glBufferSubData)(GLenum target, GLintptr offset, GLsizeiptr size, const void* data);
typedef void* (*PFN_glMapBuffer)(GLenum target, GLenum access);
typedef void* (*PFN_glMapBufferRange)(GLenum target, GLintptr offset, GLsizeiptr length, GLbitfield access);
typedef void (*PFN_glFlushMappedBufferRange)(GLenum target, GLintptr offset, GLsizeiptr length);
typedef GLboolean (*PFN_glUnmapBuffer)(GLenum target);
typedef void (*PFN_glCopyBufferSubData)(GLenum readTarget, GLenum writeTarget, GLintptr readOffset, GLintptr writeOffset, GLsizeiptr size);

// GL4ES Direct State Access (DSA) functions that operate on glbuffer_t by ID without changing target binding
typedef void (*PFN_glNamedBufferData)(GLuint buffer, GLsizeiptr size, const void* data, GLenum usage);
typedef void (*PFN_glNamedBufferSubData)(GLuint buffer, GLintptr offset, GLsizeiptr size, const void* data);
typedef void (*PFN_glGetNamedBufferSubData)(GLuint buffer, GLintptr offset, GLsizeiptr size, void* data);
typedef void* (*PFN_glMapNamedBuffer)(GLuint buffer, GLenum access);
typedef GLboolean (*PFN_glUnmapNamedBuffer)(GLuint buffer);

static PFN_glDrawElements s_real_glDrawElements = nullptr;
static PFN_glDrawElementsBaseVertex s_real_glDrawElementsBaseVertex = nullptr;
static PFN_glBindBuffer s_real_glBindBuffer = nullptr;
static PFN_glDeleteBuffers s_real_glDeleteBuffers = nullptr;
static PFN_glBufferData s_real_glBufferData = nullptr;
static PFN_glBufferSubData s_real_glBufferSubData = nullptr;
static PFN_glMapBuffer s_real_glMapBuffer = nullptr;
static PFN_glMapBufferRange s_real_glMapBufferRange = nullptr;
static PFN_glFlushMappedBufferRange s_real_glFlushMappedBufferRange = nullptr;
static PFN_glUnmapBuffer s_real_glUnmapBuffer = nullptr;
static PFN_glCopyBufferSubData s_real_glCopyBufferSubData = nullptr;

static PFN_glNamedBufferData s_real_glNamedBufferData = nullptr;
static PFN_glNamedBufferSubData s_real_glNamedBufferSubData = nullptr;
static PFN_glGetNamedBufferSubData s_real_glGetNamedBufferSubData = nullptr;
static PFN_glMapNamedBuffer s_real_glMapNamedBuffer = nullptr;
static PFN_glUnmapNamedBuffer s_real_glUnmapNamedBuffer = nullptr;

// Tracked bindings for GL_ARRAY_BUFFER, GL_COPY_READ_BUFFER (0x8F36), and GL_COPY_WRITE_BUFFER (0x8F37)
static thread_local GLuint t_boundArrayBuffer = 0;
static thread_local GLuint t_boundCopyReadBuffer = 0;
static thread_local GLuint t_boundCopyWriteBuffer = 0;

// Reusable thread-local scratch buffer for zero-malloc VBO staging copies
static thread_local std::vector<uint8_t> t_copyScratch;

// Telemetry counters
static std::atomic<uint64_t> g_multiDrawCalls{0};
static std::atomic<uint64_t> g_vboCopyBytes{0};
static std::atomic<bool> g_renderThreadBoosted{false};

static inline void ensureBufferRegisteredInGl4es(GLuint bufferId) {
    if (bufferId == 0 || !s_real_glBindBuffer) return;
    // Binding to GL_ARRAY_BUFFER ensures gl4es creates the glbuffer_t entry with type=GL_ARRAY_BUFFER
    GLuint prevArray = t_boundArrayBuffer;
    s_real_glBindBuffer(GL_ARRAY_BUFFER, bufferId);
    s_real_glBindBuffer(GL_ARRAY_BUFFER, prevArray);
}

// =========================================================================================
// 1. C++ FIX FOR GL4ES `glMultiDrawElementsBaseVertex` & `glMultiDrawElements` SIGSEGV
//    In `libgl4es_114.so` (`src/gl/drawing.c:989-1090`), `gl4es_glMultiDrawElementsBaseVertex`
//    computes `glstate->vao->elements->data + (uintptr_t)indices` instead of `indices[i]`,
//    adding two pointers together and crashing in `libc.so (__memcpy_a53+0x60)`.
//    `gl4es_glDrawElementsBaseVertex` takes scalar `indices[i]` and `basevertex[i]` and works
//    flawlessly!
// =========================================================================================
static void turbo_glMultiDrawElementsBaseVertex(
    GLenum mode,
    const GLsizei* count,
    GLenum type,
    const void* const* indices,
    GLsizei drawcount,
    const GLint* basevertex
) {
    if (!count || !indices || drawcount <= 0) return;

    if (!g_renderThreadBoosted.load(std::memory_order_relaxed)) {
        velocraft_turbo_boost_render_thread();
    }

    g_multiDrawCalls.fetch_add(1, std::memory_order_relaxed);

    if (s_real_glDrawElementsBaseVertex) {
        for (GLsizei i = 0; i < drawcount; ++i) {
            const GLsizei c = count[i];
            if (c <= 0) continue;
            const GLint bv = basevertex ? basevertex[i] : 0;
            const void* idxPtr = indices[i];
            if (bv == 0 && s_real_glDrawElements) {
                s_real_glDrawElements(mode, c, type, idxPtr);
            } else {
                s_real_glDrawElementsBaseVertex(mode, c, type, idxPtr, bv);
            }
        }
    } else if (s_real_glDrawElements) {
        for (GLsizei i = 0; i < drawcount; ++i) {
            const GLsizei c = count[i];
            if (c > 0) {
                s_real_glDrawElements(mode, c, type, indices[i]);
            }
        }
    }
}

static void turbo_glMultiDrawElements(
    GLenum mode,
    const GLsizei* count,
    GLenum type,
    const void* const* indices,
    GLsizei drawcount
) {
    if (!count || !indices || drawcount <= 0 || !s_real_glDrawElements) return;

    if (!g_renderThreadBoosted.load(std::memory_order_relaxed)) {
        velocraft_turbo_boost_render_thread();
    }

    g_multiDrawCalls.fetch_add(1, std::memory_order_relaxed);

    for (GLsizei i = 0; i < drawcount; ++i) {
        const GLsizei c = count[i];
        if (c > 0) {
            s_real_glDrawElements(mode, c, type, indices[i]);
        }
    }
}

// =========================================================================================
// 2. C++ FIX FOR GL4ES `GL_COPY_READ_BUFFER` (0x8F36) & `GL_COPY_WRITE_BUFFER` (0x8F37)
//    In `libgl4es_114.so` (`src/gl/buffers.c:145`), calling `gl4es_glBindBuffer(0x8F37, id)`
//    overwrites `buff->type = 0x8F37`, which prevents `gl4es` from ever updating the hardware
//    GLES VBO (`buff->real_buffer`) again, and `glBufferData`/`glMapBufferRange` reject
//    `0x8F36`/`0x8F37` with `GL_INVALID_ENUM`.
//    By tracking `0x8F36`/`0x8F37` in C++ and routing operations via `glNamedBuffer*` DSA,
//    `buff->type` remains `GL_ARRAY_BUFFER` and hardware VBOs stay 100% synchronized!
// =========================================================================================
static void turbo_glBindBuffer(GLenum target, GLuint buffer) {
    if (target == GL_COPY_READ_BUFFER) {
        t_boundCopyReadBuffer = buffer;
        return;
    }
    if (target == GL_COPY_WRITE_BUFFER) {
        t_boundCopyWriteBuffer = buffer;
        return;
    }
    if (target == GL_ARRAY_BUFFER) {
        t_boundArrayBuffer = buffer;
    }
    if (s_real_glBindBuffer) {
        s_real_glBindBuffer(target, buffer);
    }
}

static void turbo_glDeleteBuffers(GLsizei n, const GLuint* buffers) {
    if (buffers && n > 0) {
        for (GLsizei i = 0; i < n; ++i) {
            GLuint id = buffers[i];
            if (id != 0) {
                if (t_boundArrayBuffer == id) t_boundArrayBuffer = 0;
                if (t_boundCopyReadBuffer == id) t_boundCopyReadBuffer = 0;
                if (t_boundCopyWriteBuffer == id) t_boundCopyWriteBuffer = 0;
            }
        }
    }
    if (s_real_glDeleteBuffers) {
        s_real_glDeleteBuffers(n, buffers);
    }
}

static GLuint getCopyTargetBufferId(GLenum target) {
    if (target == GL_COPY_READ_BUFFER) return t_boundCopyReadBuffer;
    if (target == GL_COPY_WRITE_BUFFER) return t_boundCopyWriteBuffer;
    return 0;
}

static void turbo_glBufferData(GLenum target, GLsizeiptr size, const void* data, GLenum usage) {
    if (target == GL_COPY_READ_BUFFER || target == GL_COPY_WRITE_BUFFER) {
        GLuint bufId = getCopyTargetBufferId(target);
        if (bufId != 0) {
            ensureBufferRegisteredInGl4es(bufId);
            if (s_real_glNamedBufferData) {
                s_real_glNamedBufferData(bufId, size, data, usage);
                return;
            }
            if (s_real_glBindBuffer && s_real_glBufferData) {
                GLuint prev = t_boundArrayBuffer;
                s_real_glBindBuffer(GL_ARRAY_BUFFER, bufId);
                s_real_glBufferData(GL_ARRAY_BUFFER, size, data, usage);
                s_real_glBindBuffer(GL_ARRAY_BUFFER, prev);
            }
        }
        return;
    }
    if (s_real_glBufferData) {
        s_real_glBufferData(target, size, data, usage);
    }
}

static void turbo_glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const void* data) {
    if (target == GL_COPY_READ_BUFFER || target == GL_COPY_WRITE_BUFFER) {
        GLuint bufId = getCopyTargetBufferId(target);
        if (bufId != 0 && s_real_glNamedBufferSubData) {
            s_real_glNamedBufferSubData(bufId, offset, size, data);
        }
        return;
    }
    if (s_real_glBufferSubData) {
        s_real_glBufferSubData(target, offset, size, data);
    }
}

static void* turbo_glMapBufferRange(GLenum target, GLintptr offset, GLsizeiptr length, GLbitfield access) {
    if (target == GL_COPY_READ_BUFFER || target == GL_COPY_WRITE_BUFFER) {
        GLuint bufId = getCopyTargetBufferId(target);
        if (bufId != 0 && s_real_glBindBuffer && s_real_glMapBufferRange) {
            GLuint prev = t_boundArrayBuffer;
            s_real_glBindBuffer(GL_ARRAY_BUFFER, bufId);
            void* ptr = s_real_glMapBufferRange(GL_ARRAY_BUFFER, offset, length, access);
            s_real_glBindBuffer(GL_ARRAY_BUFFER, prev);
            return ptr;
        }
        return nullptr;
    }
    if (s_real_glMapBufferRange) {
        return s_real_glMapBufferRange(target, offset, length, access);
    }
    return nullptr;
}

static void* turbo_glMapBuffer(GLenum target, GLenum access) {
    if (target == GL_COPY_READ_BUFFER || target == GL_COPY_WRITE_BUFFER) {
        GLuint bufId = getCopyTargetBufferId(target);
        if (bufId != 0 && s_real_glMapNamedBuffer) {
            return s_real_glMapNamedBuffer(bufId, access);
        }
        return nullptr;
    }
    if (s_real_glMapBuffer) {
        return s_real_glMapBuffer(target, access);
    }
    return nullptr;
}

static void turbo_glFlushMappedBufferRange(GLenum target, GLintptr offset, GLsizeiptr length) {
    if (target == GL_COPY_READ_BUFFER || target == GL_COPY_WRITE_BUFFER) {
        GLuint bufId = getCopyTargetBufferId(target);
        if (bufId != 0 && s_real_glBindBuffer && s_real_glFlushMappedBufferRange) {
            GLuint prev = t_boundArrayBuffer;
            s_real_glBindBuffer(GL_ARRAY_BUFFER, bufId);
            s_real_glFlushMappedBufferRange(GL_ARRAY_BUFFER, offset, length);
            s_real_glBindBuffer(GL_ARRAY_BUFFER, prev);
        }
        return;
    }
    if (s_real_glFlushMappedBufferRange) {
        s_real_glFlushMappedBufferRange(target, offset, length);
    }
}

static GLboolean turbo_glUnmapBuffer(GLenum target) {
    if (target == GL_COPY_READ_BUFFER || target == GL_COPY_WRITE_BUFFER) {
        GLuint bufId = getCopyTargetBufferId(target);
        if (bufId != 0 && s_real_glUnmapNamedBuffer) {
            return s_real_glUnmapNamedBuffer(bufId);
        }
        return GL_TRUE;
    }
    if (s_real_glUnmapBuffer) {
        return s_real_glUnmapBuffer(target);
    }
    return GL_FALSE;
}

static void turbo_glCopyBufferSubData(
    GLenum readTarget,
    GLenum writeTarget,
    GLintptr readOffset,
    GLintptr writeOffset,
    GLsizeiptr size
) {
    if (size <= 0) return;

    GLuint readId = getCopyTargetBufferId(readTarget);
    GLuint writeId = getCopyTargetBufferId(writeTarget);

    if (readId != 0 && writeId != 0 && s_real_glGetNamedBufferSubData && s_real_glNamedBufferSubData) {
        if (t_copyScratch.size() < static_cast<size_t>(size)) {
            t_copyScratch.resize(static_cast<size_t>(size));
        }
        s_real_glGetNamedBufferSubData(readId, readOffset, size, t_copyScratch.data());
        s_real_glNamedBufferSubData(writeId, writeOffset, size, t_copyScratch.data());
        g_vboCopyBytes.fetch_add(static_cast<uint64_t>(size), std::memory_order_relaxed);
        return;
    }

    if (s_real_glCopyBufferSubData) {
        s_real_glCopyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size);
    }
}

// =========================================================================================
// 3. PUBLIC C++ TURBO API IMPLEMENTATION
// =========================================================================================
extern "C" void velocraft_turbo_init_gl_hooks(void* glHandle) {
    if (!glHandle) return;

    s_real_glDrawElements = reinterpret_cast<PFN_glDrawElements>(dlsym(glHandle, "gl4es_glDrawElements"));
    if (!s_real_glDrawElements) {
        s_real_glDrawElements = reinterpret_cast<PFN_glDrawElements>(dlsym(glHandle, "glDrawElements"));
    }

    s_real_glDrawElementsBaseVertex = reinterpret_cast<PFN_glDrawElementsBaseVertex>(
        dlsym(glHandle, "gl4es_glDrawElementsBaseVertex")
    );
    if (!s_real_glDrawElementsBaseVertex) {
        s_real_glDrawElementsBaseVertex = reinterpret_cast<PFN_glDrawElementsBaseVertex>(
            dlsym(glHandle, "glDrawElementsBaseVertex")
        );
    }

    s_real_glBindBuffer = reinterpret_cast<PFN_glBindBuffer>(dlsym(glHandle, "gl4es_glBindBuffer"));
    if (!s_real_glBindBuffer) {
        s_real_glBindBuffer = reinterpret_cast<PFN_glBindBuffer>(dlsym(glHandle, "glBindBuffer"));
    }

    s_real_glDeleteBuffers = reinterpret_cast<PFN_glDeleteBuffers>(dlsym(glHandle, "gl4es_glDeleteBuffers"));
    if (!s_real_glDeleteBuffers) {
        s_real_glDeleteBuffers = reinterpret_cast<PFN_glDeleteBuffers>(dlsym(glHandle, "glDeleteBuffers"));
    }

    s_real_glBufferData = reinterpret_cast<PFN_glBufferData>(dlsym(glHandle, "gl4es_glBufferData"));
    if (!s_real_glBufferData) {
        s_real_glBufferData = reinterpret_cast<PFN_glBufferData>(dlsym(glHandle, "glBufferData"));
    }

    s_real_glBufferSubData = reinterpret_cast<PFN_glBufferSubData>(dlsym(glHandle, "gl4es_glBufferSubData"));
    if (!s_real_glBufferSubData) {
        s_real_glBufferSubData = reinterpret_cast<PFN_glBufferSubData>(dlsym(glHandle, "glBufferSubData"));
    }

    s_real_glMapBuffer = reinterpret_cast<PFN_glMapBuffer>(dlsym(glHandle, "gl4es_glMapBuffer"));
    if (!s_real_glMapBuffer) {
        s_real_glMapBuffer = reinterpret_cast<PFN_glMapBuffer>(dlsym(glHandle, "glMapBuffer"));
    }

    s_real_glMapBufferRange = reinterpret_cast<PFN_glMapBufferRange>(dlsym(glHandle, "gl4es_glMapBufferRange"));
    if (!s_real_glMapBufferRange) {
        s_real_glMapBufferRange = reinterpret_cast<PFN_glMapBufferRange>(dlsym(glHandle, "glMapBufferRange"));
    }

    s_real_glFlushMappedBufferRange = reinterpret_cast<PFN_glFlushMappedBufferRange>(
        dlsym(glHandle, "gl4es_glFlushMappedBufferRange")
    );
    if (!s_real_glFlushMappedBufferRange) {
        s_real_glFlushMappedBufferRange = reinterpret_cast<PFN_glFlushMappedBufferRange>(
            dlsym(glHandle, "glFlushMappedBufferRange")
        );
    }

    s_real_glUnmapBuffer = reinterpret_cast<PFN_glUnmapBuffer>(dlsym(glHandle, "gl4es_glUnmapBuffer"));
    if (!s_real_glUnmapBuffer) {
        s_real_glUnmapBuffer = reinterpret_cast<PFN_glUnmapBuffer>(dlsym(glHandle, "glUnmapBuffer"));
    }

    s_real_glCopyBufferSubData = reinterpret_cast<PFN_glCopyBufferSubData>(
        dlsym(glHandle, "gl4es_glCopyBufferSubData")
    );
    if (!s_real_glCopyBufferSubData) {
        s_real_glCopyBufferSubData = reinterpret_cast<PFN_glCopyBufferSubData>(
            dlsym(glHandle, "glCopyBufferSubData")
        );
    }

    s_real_glNamedBufferData = reinterpret_cast<PFN_glNamedBufferData>(
        dlsym(glHandle, "gl4es_glNamedBufferData")
    );
    if (!s_real_glNamedBufferData) {
        s_real_glNamedBufferData = reinterpret_cast<PFN_glNamedBufferData>(
            dlsym(glHandle, "glNamedBufferData")
        );
    }

    s_real_glNamedBufferSubData = reinterpret_cast<PFN_glNamedBufferSubData>(
        dlsym(glHandle, "gl4es_glNamedBufferSubData")
    );
    if (!s_real_glNamedBufferSubData) {
        s_real_glNamedBufferSubData = reinterpret_cast<PFN_glNamedBufferSubData>(
            dlsym(glHandle, "glNamedBufferSubData")
        );
    }

    s_real_glGetNamedBufferSubData = reinterpret_cast<PFN_glGetNamedBufferSubData>(
        dlsym(glHandle, "gl4es_glGetNamedBufferSubData")
    );
    if (!s_real_glGetNamedBufferSubData) {
        s_real_glGetNamedBufferSubData = reinterpret_cast<PFN_glGetNamedBufferSubData>(
            dlsym(glHandle, "glGetNamedBufferSubData")
        );
    }

    s_real_glMapNamedBuffer = reinterpret_cast<PFN_glMapNamedBuffer>(
        dlsym(glHandle, "gl4es_glMapNamedBuffer")
    );
    if (!s_real_glMapNamedBuffer) {
        s_real_glMapNamedBuffer = reinterpret_cast<PFN_glMapNamedBuffer>(
            dlsym(glHandle, "glMapNamedBuffer")
        );
    }

    s_real_glUnmapNamedBuffer = reinterpret_cast<PFN_glUnmapNamedBuffer>(
        dlsym(glHandle, "gl4es_glUnmapNamedBuffer")
    );
    if (!s_real_glUnmapNamedBuffer) {
        s_real_glUnmapNamedBuffer = reinterpret_cast<PFN_glUnmapNamedBuffer>(
            dlsym(glHandle, "glUnmapNamedBuffer")
        );
    }

    TURBO_LOGI(
        "libvelocraft_turbo.so initialized: DrawElementsBaseVertex=%p NamedBufferSubData=%p",
        reinterpret_cast<void*>(s_real_glDrawElementsBaseVertex),
        reinterpret_cast<void*>(s_real_glNamedBufferSubData)
    );
}

extern "C" void* velocraft_turbo_intercept_gl_proc(const char* name, void* originalSym) {
    if (!name) return originalSym;

    // 1. MultiDraw interceptors (Sodium 0.5.8 chunk batch renderer)
    if (strcmp(name, "glMultiDrawElementsBaseVertex") == 0 ||
        strcmp(name, "glMultiDrawElementsBaseVertexEXT") == 0 ||
        strcmp(name, "glMultiDrawElementsBaseVertexARB") == 0) {
        return reinterpret_cast<void*>(&turbo_glMultiDrawElementsBaseVertex);
    }
    if (strcmp(name, "glMultiDrawElements") == 0 ||
        strcmp(name, "glMultiDrawElementsEXT") == 0) {
        return reinterpret_cast<void*>(&turbo_glMultiDrawElements);
    }

    // 2. Staging & Arena Buffer interceptors (0x8F36 / 0x8F37 hardware VBO sync)
    if (strcmp(name, "glBindBuffer") == 0 || strcmp(name, "glBindBufferARB") == 0) {
        return reinterpret_cast<void*>(&turbo_glBindBuffer);
    }
    if (strcmp(name, "glDeleteBuffers") == 0 || strcmp(name, "glDeleteBuffersARB") == 0) {
        return reinterpret_cast<void*>(&turbo_glDeleteBuffers);
    }
    if (strcmp(name, "glBufferData") == 0 || strcmp(name, "glBufferDataARB") == 0) {
        return reinterpret_cast<void*>(&turbo_glBufferData);
    }
    if (strcmp(name, "glBufferSubData") == 0 || strcmp(name, "glBufferSubDataARB") == 0) {
        return reinterpret_cast<void*>(&turbo_glBufferSubData);
    }
    if (strcmp(name, "glMapBufferRange") == 0) {
        return reinterpret_cast<void*>(&turbo_glMapBufferRange);
    }
    if (strcmp(name, "glMapBuffer") == 0 || strcmp(name, "glMapBufferARB") == 0) {
        return reinterpret_cast<void*>(&turbo_glMapBuffer);
    }
    if (strcmp(name, "glFlushMappedBufferRange") == 0) {
        return reinterpret_cast<void*>(&turbo_glFlushMappedBufferRange);
    }
    if (strcmp(name, "glUnmapBuffer") == 0 || strcmp(name, "glUnmapBufferARB") == 0) {
        return reinterpret_cast<void*>(&turbo_glUnmapBuffer);
    }
    if (strcmp(name, "glCopyBufferSubData") == 0) {
        return reinterpret_cast<void*>(&turbo_glCopyBufferSubData);
    }

    return originalSym;
}

extern "C" void velocraft_turbo_compute_scaled_dimensions(
    int physicalW,
    int physicalH,
    float scale,
    int* outRenderW,
    int* outRenderH
) {
    float clamped = scale;
    if (clamped < 0.45f) clamped = 0.45f;
    if (clamped > 1.0f) clamped = 1.0f;

    int rw = static_cast<int>(physicalW * clamped + 0.5f);
    int rh = static_cast<int>(physicalH * clamped + 0.5f);
    // Align to even pixel boundaries for mobile tile-based GPUs (PowerVR / Mali / Adreno)
    rw = (rw & ~1);
    rh = (rh & ~1);
    if (rw < 320) rw = 320;
    if (rh < 180) rh = 180;

    if (outRenderW) *outRenderW = rw;
    if (outRenderH) *outRenderH = rh;
}

extern "C" void velocraft_turbo_apply_window_geometry(
    ANativeWindow* window,
    int renderW,
    int renderH
) {
    if (!window || renderW <= 0 || renderH <= 0) return;
    int32_t fmt = ANativeWindow_getFormat(window);
    ANativeWindow_setBuffersGeometry(window, renderW, renderH, fmt);
    TURBO_LOGI("ANativeWindow hardware scaler locked to %dx%d (fmt=%d)", renderW, renderH, fmt);
}

extern "C" void velocraft_turbo_boost_render_thread(void) {
    g_renderThreadBoosted.store(true, std::memory_order_relaxed);
    // Elevate render thread nice priority for smoother frame pacing on 4-core / 8-core SoCs
    setpriority(PRIO_PROCESS, 0, -8);

#if defined(__aarch64__)
    // Enable Flush-to-Zero (bit 24) and Default-NaN (bit 25) in AArch64 FPCR
    uint64_t fpcr = 0;
    __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24) | (1ULL << 25);
    __asm__ __volatile__("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__) && defined(__VFP_FP__) && !defined(__SOFTFP__)
    // Enable Flush-to-Zero (bit 24) and Default-NaN (bit 25) in ARMv7 VFP FPSCR (Helio G36 / Redmi A2!)
    uint32_t fpscr = 0;
    __asm__ __volatile__("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1U << 24) | (1U << 25);
    __asm__ __volatile__("vmsr fpscr, %0" : : "r"(fpscr));
#endif
}

extern "C" uint64_t velocraft_turbo_get_multi_draw_calls(void) {
    return g_multiDrawCalls.load(std::memory_order_relaxed);
}

extern "C" uint64_t velocraft_turbo_get_vbo_copy_bytes(void) {
    return g_vboCopyBytes.load(std::memory_order_relaxed);
}

extern "C" void velocraft_turbo_trim_scratch_buffers(void) {
    if (t_copyScratch.capacity() > 262144) {
        std::vector<uint8_t>().swap(t_copyScratch);
    }
}
