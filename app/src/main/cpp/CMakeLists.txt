cmake_minimum_required(VERSION 3.22.1)
project("velocraft_bridge")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Aggressive C++ optimization flags for low-end 32-bit/64-bit ARM devices (e.g., Redmi A2 / PowerVR GE8320)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -funroll-loops -fomit-frame-pointer -fvisibility=hidden")

# 1. Standalone C++ Turbo Renderer & Hardware Scaler Engine (`libvelocraft_turbo.so`)
# NOTE: Never link GLESv2/GLESv3 here so Android's system GLES symbols never shadow libgl4es_114.so
add_library(
    velocraft_turbo
    SHARED
    velocraft_turbo.cpp
)

# 2. Primary JNI & OpenJDK / LWJGL3 Bridge (`libvelocraft_bridge.so` / `libpojavexec.so`)
# Includes velocraft_turbo.cpp directly so libpojavexec.so has zero cross-namespace DT_NEEDED dependencies
add_library(
    velocraft_bridge
    SHARED
    velocraft_jni.cpp
    velocraft_turbo.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)

target_link_libraries(
    velocraft_turbo
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    dl
)

target_link_libraries(
    velocraft_bridge
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    dl
)
