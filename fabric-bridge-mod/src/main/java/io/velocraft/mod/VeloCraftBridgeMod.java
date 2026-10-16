package io.velocraft.mod;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Client-side Fabric Companion Mod for VeloCraft Android.
 *
 * Broadcasts lightweight 16-byte UDP packets to 127.0.0.1:28465 whenever:
 * 1. The active GUI screen changes (e.g. player opens Inventory, Chest, Crafting Table, or Chat),
 *    allowing the Android HUD to switch instantaneously between Crosshair PvP Mode and Touch Cursor Mode.
 * 2. Client telemetry updates (FPS, Ping, Active Hotbar Slot 0..8, Sprinting/Sneaking flags).
 */
public final class VeloCraftBridgeMod {

    public static final short MAGIC_HEADER = 0x5643; // "VC"
    public static final byte MODE_IN_GAME_CROSSHAIR = 0;
    public static final byte MODE_GUI_CURSOR        = 1;
    public static final byte MODE_CHAT_INPUT        = 2;

    private static DatagramSocket udpSocket;
    private static InetAddress loopbackAddr;
    private static int bridgePort = 28465;

    static {
        try {
            String portProp = System.getProperty("velocraft.bridge.port", "28465");
            bridgePort = Integer.parseInt(portProp);
            loopbackAddr = InetAddress.getByName("127.0.0.1");
            udpSocket = new DatagramSocket();
        } catch (Exception ignored) {
        }
    }

    /**
     * Called by MinecraftClientMixin whenever setScreen() or client tick runs.
     */
    public static void sendTelemetryState(
            byte screenMode,
            int activeHotbarSlot,
            int clientFps,
            int pingMs,
            boolean isSprinting,
            boolean isSneaking,
            String screenSimpleName
    ) {
        if (udpSocket == null || loopbackAddr == null) return;

        try {
            byte[] nameBytes = screenSimpleName != null
                    ? screenSimpleName.getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            int nameLen = Math.min(nameBytes.length, 64);

            ByteBuffer buf = ByteBuffer.allocate(10 + nameLen).order(ByteOrder.BIG_ENDIAN);
            buf.putShort(MAGIC_HEADER);
            buf.put(screenMode);
            buf.put((byte) (activeHotbarSlot & 0xFF));
            buf.putShort((short) (clientFps & 0xFFFF));
            buf.putShort((short) (pingMs & 0xFFFF));

            byte flags = 0;
            if (isSprinting) flags |= 0x01;
            if (isSneaking)  flags |= 0x02;
            buf.put(flags);
            buf.put((byte) nameLen);
            if (nameLen > 0) {
                buf.put(nameBytes, 0, nameLen);
            }

            byte[] payload = buf.array();
            DatagramPacket packet = new DatagramPacket(payload, payload.length, loopbackAddr, bridgePort);
            udpSocket.send(packet);
        } catch (Exception ignored) {
        }
    }
}
