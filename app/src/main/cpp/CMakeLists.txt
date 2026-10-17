cmake_minimum_required(VERSION 3.22.1)
project("velocraft_bridge" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles3-lib GLESv3)

# Standalone Native C++ Turbo Acceleration Library (libvelocraft_turbo.so)
add_library(
    velocraft_turbo
    SHARED
    velocraft_turbo.cpp
)

target_compile_options(
    velocraft_turbo
    PRIVATE
    -O3
    -ffast-math
    -funroll-loops
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(
    velocraft_turbo
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles3-lib}
    dl
)

# Main Native Engine & OpenJDK Bridge Library (libvelocraft_bridge.so)
add_library(
    velocraft_bridge
    SHARED
    velocraft_jni.cpp
)

target_compile_options(
    velocraft_bridge
    PRIVATE
    -O3
    -ffast-math
    -funroll-loops
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(
    velocraft_bridge
    velocraft_turbo
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles3-lib}
    dl
)
