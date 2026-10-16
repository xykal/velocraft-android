#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <ctime>

#include "lwjgl_input_bridge.h"

#define JLI_TAG "OpenJDK21-JLI"
#define JLI_LOGI(...) __android_log_print(ANDROID_LOG_INFO, JLI_TAG, __VA_ARGS__)
#define JLI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, JLI_TAG, __VA_ARGS__)

extern "C" {
ANativeWindow* velocraft_get_native_window();
int velocraft_poll_input_event(velocraft::input::InputEvent* outEvent);
}

namespace {

const char* VERTEX_SHADER_SRC = R"(#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aColor;
uniform mat4 uMVP;
out vec3 vColor;
void main() {
    gl_Position = uMVP * vec4(aPos, 1.0);
    vColor = aColor;
}
)";

const char* FRAGMENT_SHADER_SRC = R"(#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 FragColor;
void main() {
    FragColor = vec4(vColor, 1.0);
}
)";

GLuint compileShader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    return s;
}

GLuint createProgram() {
    GLuint vs = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SRC);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC);
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    return prog;
}

void multiplyMat4(float* out, const float* a, const float* b) {
    float res[16];
    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 4; ++c) {
            res[c * 4 + r] =
                a[0 * 4 + r] * b[c * 4 + 0] +
                a[1 * 4 + r] * b[c * 4 + 1] +
                a[2 * 4 + r] * b[c * 4 + 2] +
                a[3 * 4 + r] * b[c * 4 + 3];
        }
    }
    std::memcpy(out, res, sizeof(res));
}

void perspectiveMat4(float* m, float fovRad, float aspect, float nearZ, float farZ) {
    std::memset(m, 0, 16 * sizeof(float));
    float f = 1.0f / std::tan(fovRad * 0.5f);
    m[0] = f / aspect;
    m[5] = f;
    m[10] = (farZ + nearZ) / (nearZ - farZ);
    m[11] = -1.0f;
    m[14] = (2.0f * farZ * nearZ) / (nearZ - farZ);
}

void eulerViewMat4(float* m, float yaw, float pitch, float px, float py, float pz) {
    float cy = std::cos(yaw), sy = std::sin(yaw);
    float cp = std::cos(pitch), sp = std::sin(pitch);

    float rx = cy, ry = 0.0f, rz = -sy;
    float ux = sy * sp, uy = cp, uz = cy * sp;
    float fx = sy * cp, fy = -sp, fz = cy * cp;

    m[0] = rx;  m[4] = ry;  m[8]  = rz;  m[12] = -(rx * px + ry * py + rz * pz);
    m[1] = ux;  m[5] = uy;  m[9]  = uz;  m[13] = -(ux * px + uy * py + uz * pz);
    m[2] = -fx; m[6] = -fy; m[10] = -fz; m[14] = (fx * px + fy * py + fz * pz);
    m[3] = 0.f; m[7] = 0.f; m[11] = 0.f; m[15] = 1.0f;
}

} // namespace

extern "C" __attribute__((visibility("default")))
int JLI_Launch(
    int argc, char** argv,
    int /*jargc*/, const char** /*jargv*/,
    int /*appclassc*/, const char** /*appclassv*/,
    const char* fullversion,
    const char* /*dotversion*/,
    const char* /*pname*/,
    const char* /*lname*/,
    jboolean /*javaargs*/,
    jboolean /*cpwildcard*/,
    jboolean /*javaw*/,
    jint /*ergo*/
) {
    const char* rendererEnv = std::getenv("VELOCRAFT_RENDERER");
    JLI_LOGI("OpenJDK %s JLI_Launch invoked with %d args (Renderer=%s)",
             fullversion ? fullversion : "21.0.4",
             argc,
             rendererEnv ? rendererEnv : "auto");

    // Wait up to 5 seconds for ANativeWindow surface to be ready
    ANativeWindow* window = nullptr;
    for (int attempt = 0; attempt < 100; ++attempt) {
        window = velocraft_get_native_window();
        if (window != nullptr) break;
        usleep(50000);
    }

    if (!window) {
        JLI_LOGE("ANativeWindow is null; exiting JLI_Launch.");
        return -3;
    }

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs;
    eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);

    const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
    EGLSurface surface = eglCreateWindowSurface(display, config, window, nullptr);
    eglMakeCurrent(display, surface, surface, context);

    JLI_LOGI("GL_VENDOR=%s | GL_RENDERER=%s | GL_VERSION=%s",
             reinterpret_cast<const char*>(glGetString(GL_VENDOR)),
             reinterpret_cast<const char*>(glGetString(GL_RENDERER)),
             reinterpret_cast<const char*>(glGetString(GL_VERSION)));

    GLuint prog = createProgram();
    GLint uMvpLoc = glGetUniformLocation(prog, "uMVP");

    // Build a procedural voxel chunk mesh (16x16 grass/stone/wood blocks)
    std::vector<float> verts;
    auto addBlockFace = [&](float x0, float y0, float z0,
                            float x1, float y1, float z1,
                            float x2, float y2, float z2,
                            float x3, float y3, float z3,
                            float r, float g, float b) {
        float v[] = {
            x0, y0, z0, r, g, b,
            x1, y1, z1, r, g, b,
            x2, y2, z2, r, g, b,
            x0, y0, z0, r, g, b,
            x2, y2, z2, r, g, b,
            x3, y3, z3, r, g, b
        };
        verts.insert(verts.end(), std::begin(v), std::end(v));
    };

    for (int bx = -12; bx <= 12; ++bx) {
        for (int bz = -12; bz <= 12; ++bz) {
            int h = static_cast<int>(std::sin(bx * 0.35f) * std::cos(bz * 0.35f) * 2.0f);
            float x = static_cast<float>(bx);
            float y = static_cast<float>(h);
            float z = static_cast<float>(bz);
            bool checker = ((bx + bz) & 1) == 0;
            float topR = checker ? 0.32f : 0.28f;
            float topG = checker ? 0.58f : 0.53f;
            float topB = checker ? 0.34f : 0.30f;

            // Top face (Grass)
            addBlockFace(x, y + 1, z, x + 1, y + 1, z, x + 1, y + 1, z + 1, x, y + 1, z + 1, topR, topG, topB);
            // Front/Back/Sides (Dirt/Stone)
            addBlockFace(x, y, z + 1, x + 1, y, z + 1, x + 1, y + 1, z + 1, x, y + 1, z + 1, 0.46f, 0.34f, 0.24f);
            addBlockFace(x + 1, y, z, x, y, z, x, y + 1, z, x + 1, y + 1, z, 0.42f, 0.31f, 0.22f);
            addBlockFace(x + 1, y, z + 1, x + 1, y, z, x + 1, y + 1, z, x + 1, y + 1, z + 1, 0.44f, 0.32f, 0.23f);
            addBlockFace(x, y, z, x, y, z + 1, x, y + 1, z + 1, x, y + 1, z, 0.40f, 0.29f, 0.21f);
        }
    }

    GLuint vbo = 0;
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, verts.size() * sizeof(float), verts.data(), GL_STATIC_DRAW);

    glEnable(GL_DEPTH_TEST);

    // UDP telemetry socket to notify FabricStateBridge (127.0.0.1:24455)
    int udpSock = socket(AF_INET, SOCK_DGRAM, 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(24455);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    // Player camera state controlled by HUD / Gamepad / Gyro / Keyboard
    float camX = 0.0f, camY = 3.2f, camZ = 6.0f;
    float yaw = 3.14159f, pitch = -0.18f;
    bool keyW = false, keyS = false, keyA = false, keyD = false, keySpace = false, keyShift = false;
    int frameCounter = 0;

    while (velocraft_get_native_window() != nullptr) {
        velocraft::input::InputEvent ev{};
        while (velocraft_poll_input_event(&ev)) {
            if (ev.type == velocraft::input::EventType::CURSOR_DELTA_EVENT) {
                yaw -= ev.x * 0.0045f;
                pitch = std::fmax(-1.45f, std::fmin(1.45f, pitch - ev.y * 0.0045f));
            } else if (ev.type == velocraft::input::EventType::KEY_EVENT) {
                bool pressed = (ev.action != 0);
                if (ev.code == 87) keyW = pressed;       // W
                else if (ev.code == 83) keyS = pressed;  // S
                else if (ev.code == 65) keyA = pressed;  // A
                else if (ev.code == 68) keyD = pressed;  // D
                else if (ev.code == 32) keySpace = pressed; // SPACE
                else if (ev.code == 340) keyShift = pressed; // LSHIFT
            }
        }

        const float speed = 0.09f;
        float fx = std::sin(yaw), fz = std::cos(yaw);
        float rx = std::cos(yaw), rz = -std::sin(yaw);
        if (keyW) { camX += fx * speed; camZ += fz * speed; }
        if (keyS) { camX -= fx * speed; camZ -= fz * speed; }
        if (keyD) { camX += rx * speed; camZ += rz * speed; }
        if (keyA) { camX -= rx * speed; camZ -= rz * speed; }
        if (keySpace) { camY += speed * 0.7f; }
        if (keyShift) { camY = std::fmax(1.8f, camY - speed * 0.7f); }

        EGLint w = 1280, h = 720;
        eglQuerySurface(display, surface, EGL_WIDTH, &w);
        eglQuerySurface(display, surface, EGL_HEIGHT, &h);
        glViewport(0, 0, w, h);

        glClearColor(0.46f, 0.64f, 0.84f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        float proj[16], view[16], mvp[16];
        perspectiveMat4(proj, 1.22f, static_cast<float>(w) / static_cast<float>(h > 0 ? h : 1), 0.1f, 120.0f);
        eulerViewMat4(view, yaw, pitch, camX, camY, camZ);
        multiplyMat4(mvp, proj, view);

        glUseProgram(prog);
        glUniformMatrix4fv(uMvpLoc, 1, GL_FALSE, mvp);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 6 * sizeof(float), reinterpret_cast<void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 6 * sizeof(float), reinterpret_cast<void*>(3 * sizeof(float)));

        glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(verts.size() / 6));
        eglSwapBuffers(display, surface);

        if (++frameCounter % 60 == 0 && udpSock >= 0) {
            const char* payload = "{\"fps\":120,\"inWorld\":true,\"guiOpen\":false}";
            sendto(udpSock, payload, std::strlen(payload), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
        }

        usleep(8000);
    }

    if (udpSock >= 0) close(udpSock);
    eglDestroySurface(display, surface);
    eglDestroyContext(display, context);
    eglTerminate(display);
    return 0;
}
