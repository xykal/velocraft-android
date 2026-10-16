#pragma once

#include <atomic>
#include <cstdint>
#include <array>

/**
 * VeloCraft Lock-Free SPSC Input Bridge (Android UI/Sensor Thread -> LWJGL3/GLFW Render Thread)
 * Designed for < 0.5ms input latency during high-CPS PvP and Gyro aiming.
 */
namespace velocraft::input {

// Standard GLFW Keycodes used by Minecraft Java (LWJGL 3)
constexpr int GLFW_RELEASE = 0;
constexpr int GLFW_PRESS   = 1;
constexpr int GLFW_REPEAT  = 2;

constexpr int GLFW_MOUSE_BUTTON_LEFT   = 0;
constexpr int GLFW_MOUSE_BUTTON_RIGHT  = 1;
constexpr int GLFW_MOUSE_BUTTON_MIDDLE = 2;

constexpr int GLFW_KEY_SPACE        = 32;
constexpr int GLFW_KEY_0            = 48;
constexpr int GLFW_KEY_1            = 49;
constexpr int GLFW_KEY_2            = 50;
constexpr int GLFW_KEY_3            = 51;
constexpr int GLFW_KEY_4            = 52;
constexpr int GLFW_KEY_5            = 53;
constexpr int GLFW_KEY_6            = 54;
constexpr int GLFW_KEY_7            = 55;
constexpr int GLFW_KEY_8            = 56;
constexpr int GLFW_KEY_9            = 57;

constexpr int GLFW_KEY_A            = 65;
constexpr int GLFW_KEY_D            = 68;
constexpr int GLFW_KEY_E            = 69; // Inventory
constexpr int GLFW_KEY_F            = 70; // Swap Offhand
constexpr int GLFW_KEY_Q            = 81; // Drop Item
constexpr int GLFW_KEY_S            = 83;
constexpr int GLFW_KEY_T            = 84; // Chat
constexpr int GLFW_KEY_W            = 87;

constexpr int GLFW_KEY_ESCAPE       = 256;
constexpr int GLFW_KEY_TAB          = 258;
constexpr int GLFW_KEY_F1           = 290;
constexpr int GLFW_KEY_F3           = 292; // Debug HUD
constexpr int GLFW_KEY_F5           = 294; // Perspective Toggle
constexpr int GLFW_KEY_LEFT_SHIFT   = 340; // Sneak
constexpr int GLFW_KEY_LEFT_CONTROL = 341; // Sprint

enum class EventType : uint8_t {
    KEY_EVENT          = 1,
    MOUSE_BUTTON_EVENT = 2,
    CURSOR_POS_EVENT   = 3,
    CURSOR_DELTA_EVENT = 4,
    SCROLL_EVENT       = 5,
    CHAR_EVENT         = 6
};

struct alignas(32) InputEvent {
    EventType type;
    int32_t code;       // keycode, mouse button, or codepoint
    int32_t action;     // GLFW_PRESS / GLFW_RELEASE
    int32_t mods;       // Shift/Ctrl/Alt bitmask
    float x;            // Cursor X or Delta DX
    float y;            // Cursor Y or Delta DY
    int64_t timestampNs;
};

/**
 * Wait-free Single-Producer Single-Consumer Ring Buffer.
 * Capacity must be a power of 2.
 */
template <size_t Capacity = 1024>
class LockFreeInputQueue {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of 2");

public:
    bool push(const InputEvent& ev) noexcept {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t next = (head + 1) & (Capacity - 1);
        if (next == tail_.load(std::memory_order_acquire)) {
            return false; // Queue full
        }
        buffer_[head] = ev;
        head_.store(next, std::memory_order_release);
        return true;
    }

    bool pop(InputEvent& out) noexcept {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) {
            return false; // Queue empty
        }
        out = buffer_[tail];
        tail_.store((tail + 1) & (Capacity - 1), std::memory_order_release);
        return true;
    }

    void clear() noexcept {
        tail_.store(head_.load(std::memory_order_acquire), std::memory_order_release);
    }

private:
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
    std::array<InputEvent, Capacity> buffer_{};
};

} // namespace velocraft::input
