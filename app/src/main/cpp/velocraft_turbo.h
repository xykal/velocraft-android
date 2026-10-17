#ifndef VELOCRAFT_TURBO_H
#define VELOCRAFT_TURBO_H

#include <stdint.h>
#include <stddef.h>
#include <android/native_window.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initializes the VeloCraft C++ Turbo Engine against a loaded OpenGL / GL4ES library handle (`libgl4es_114.so`).
 */
void velocraft_turbo_init_gl_hooks(void* glHandle);

/**
 * Intercepts OpenGL procedure lookups (`glXGetProcAddress` / `gl4es_glXGetProcAddress`)
 * for missing or broken GL4ES 1.1.5 entrypoints (`glFenceSync`, `glClientWaitSync`,
 * `glWaitSync`, `glDeleteSync`, `glGetSynciv`, `glIsSync`, `glMultiDrawElementsBaseVertex`).
 * Never replaces core GL functions unless the underlying GL4ES target symbol is verified non-null.
 */
void* velocraft_turbo_intercept_gl_proc(const char* name, void* originalSym);

/**
 * Computes aligned hardware-scaled render dimensions (even multiples of 2).
 */
void velocraft_turbo_compute_scaled_dimensions(
    int physicalW,
    int physicalH,
    float scale,
    int* outRenderW,
    int* outRenderH
);

/**
 * Applies hardware-compositor surface scaling via `ANativeWindow_setBuffersGeometry`.
 */
void velocraft_turbo_apply_window_geometry(
    ANativeWindow* window,
    int renderW,
    int renderH,
    int format
);

/**
 * Elevates Linux thread scheduling priority for the active OpenGL render thread
 * without mutating JVM floating-point status registers.
 */
void velocraft_turbo_boost_render_thread(void);

/**
 * Telemetry & native memory trim helpers.
 */
uint64_t velocraft_turbo_get_multi_draw_calls(void);
uint64_t velocraft_turbo_get_vbo_copy_bytes(void);
void velocraft_turbo_trim_scratch_buffers(void);

#ifdef __cplusplus
}
#endif

#endif // VELOCRAFT_TURBO_H
