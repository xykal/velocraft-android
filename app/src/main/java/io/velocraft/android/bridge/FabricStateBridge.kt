package io.velocraft.android.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time IPC Bridge between the Companion Fabric Mod (`velocraft-bridge-mod`) inside Minecraft
 * and the Android Pro PvP HUD layer.
 *
 * Solves the #1 annoyance of mobile Minecraft Java ports:
 * Instead of forcing the player to press a clumsy "Mouse Toggle" button every time they open a
 * Chest, Inventory, or Crafting Table, the Fabric Mod hooks `MinecraftClient.setScreen()` and sends
 * an instant 16-byte UDP packet to `127.0.0.1:28465`.
 */
class FabricStateBridge(
    private val scope: CoroutineScope,
    private val onStateUpdate: (TelemetryState) -> Unit
) {
    companion object {
        const val BRIDGE_PORT = 28465
        const val MAGIC_HEADER: Short = 0x5643 // "VC"
    }

    enum class ScreenMode {
        IN_GAME_CROSSHAIR, // Playing in world: Lock crosshair, enable Gyro & PvP HUD buttons
        GUI_CURSOR,        // Inventory / Chest / Pause menu open: Enable direct touch/cursor interaction
        CHAT_INPUT         // Chat screen open: Trigger Android keyboard or macro bar
    }

    data class TelemetryState(
        val screenMode: ScreenMode = ScreenMode.IN_GAME_CROSSHAIR,
        val clientFps: Int = 0,
        val pingMs: Int = 0,
        val activeHotbarSlot: Int = 0,
        val isSprinting: Boolean = false,
        val isSneaking: Boolean = false,
        val screenClassName: String = ""
    )

    private var listenerJob: Job? = null
    private var socket: DatagramSocket? = null

    fun startListening() {
        if (listenerJob?.isActive == true) return

        listenerJob = scope.launch(Dispatchers.IO) {
            try {
                val udpSocket = DatagramSocket(BRIDGE_PORT, InetAddress.getByName("127.0.0.1"))
                socket = udpSocket
                val buffer = ByteArray(256)

                while (isActive && !udpSocket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket.receive(packet)
                    parsePacket(packet.data, packet.length)?.let { state ->
                        NativeEngineBridge.nativeSetCursorGrabbed(
                            state.screenMode == ScreenMode.IN_GAME_CROSSHAIR
                        )
                        onStateUpdate(state)
                    }
                }
            } catch (_: Exception) {
                // Socket closed or interrupted on activity teardown
            }
        }
    }

    fun stopListening() {
        socket?.close()
        listenerJob?.cancel()
    }

    /**
     * Binary packet format (Big Endian) sent by VeloCraftBridgeMod.java:
     * [0..1]   int16  magic (0x5643)
     * [2]      uint8  screenMode (0 = IN_GAME, 1 = GUI_CURSOR, 2 = CHAT_INPUT)
     * [3]      uint8  activeHotbarSlot (0..8)
     * [4..5]   uint16 clientFps
     * [6..7]   uint16 pingMs
     * [8]      uint8  flags (bit 0 = isSprinting, bit 1 = isSneaking)
     * [9]      uint8  screenNameLen
     * [10..N]  utf8   screenClassName
     */
    private fun parsePacket(data: ByteArray, length: Int): TelemetryState? {
        if (length < 10) return null
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.short
        if (magic != MAGIC_HEADER) return null

        val modeByte = buf.get().toInt() and 0xFF
        val hotbarSlot = (buf.get().toInt() and 0xFF).coerceIn(0, 8)
        val fps = buf.short.toInt() and 0xFFFF
        val ping = buf.short.toInt() and 0xFFFF
        val flags = buf.get().toInt() and 0xFF
        val nameLen = (buf.get().toInt() and 0xFF).coerceAtMost(length - 10)

        val screenName = if (nameLen > 0) {
            String(data, 10, nameLen, Charsets.UTF_8)
        } else {
            ""
        }

        val screenMode = when (modeByte) {
            1 -> ScreenMode.GUI_CURSOR
            2 -> ScreenMode.CHAT_INPUT
            else -> ScreenMode.IN_GAME_CROSSHAIR
        }

        return TelemetryState(
            screenMode = screenMode,
            clientFps = fps,
            pingMs = ping,
            activeHotbarSlot = hotbarSlot,
            isSprinting = (flags and 0x01) != 0,
            isSneaking = (flags and 0x02) != 0,
            screenClassName = screenName
        )
    }
}
