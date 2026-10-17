#ifndef VELOCRAFT_TURBO_H
#define VELOCRAFT_TURBO_H

#include <stdint.h>
#include <stddef.h>
#include <android/native_window.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Initializes the VeloCraft C++ Turbo Engine (`libvelocraft_turbo.so`) against a loaded
 * OpenGL / GL4ES library handle (`libgl4es_114.so`).
 */
void velocraft_turbo_init_gl_hooks(void* glHandle);

/**
 * Intercepts OpenGL procedure lookups (`glXGetProcAddress`, `eglGetProcAddress`, `dlsym`)
 * and returns C++ accelerated / crash-safe replacements for broken GL4ES 1.1.5 entrypoints:
 * - `glMultiDrawElementsBaseVertex` / `glMultiDrawElements` (fixes Sodium 0.5.8 `__memcpy_a53` SIGSEGV)
 * - `glBindBuffer` / `glCopyBufferSubData` / `glFlushMappedBufferRange` for `0x8F36` & `0x8F37`
 *   (preserves `GL_ARRAY_BUFFER` hardware VBO type and syncs `real_buffer` via DSA)
 */
void* velocraft_turbo_intercept_gl_proc(const char* name, void* originalSym);

/**
 * Applies hardware-compositor surface scaling via `ANativeWindow_setBuffersGeometry`
 * and computes aligned render dimensions (multiples of 2).
 */
void velocraft_turbo_compute_scaled_dimensions(
    int physicalW,
    int physicalH,
    float scale,
    int* outRenderW,
    int* outRenderH
);

void velocraft_turbo_apply_window_geometry(
    ANativeWindow* window,
    int renderW,
    int renderH
);

/**
 * Enables ARM hardware Flush-To-Zero (FZ) / Default-NaN (DN) floating-point acceleration
 * and elevates Linux thread scheduling priority for the active OpenGL render thread.
 */
void velocraft_turbo_boost_render_thread(void);

/**
 * Releases unused native heap/scratch pages and returns total intercepted draw batch count.
 */
uint64_t velocraft_turbo_get_multi_draw_calls(void);
uint64_t velocraft_turbo_get_vbo_copy_bytes(void);
void velocraft_turbo_trim_scratch_buffers(void);

#ifdef __cplusplus
}
#endif

#endif // VELOCRAFT_TURBO_H
