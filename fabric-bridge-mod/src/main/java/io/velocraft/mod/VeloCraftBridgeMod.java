package io.velocraft.mod;

import net.fabricmc.api.ClientModInitializer;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Client-side Fabric Companion Mod for VeloCraft Android (Minecraft 1.20.4).
 *
 * 1. Pre-verifies LWJGL 3 MemoryUtil (StdlibAllocator) and Blaze3D RenderSystem on the Render thread
 *    with unbuffered FileDescriptor.out logging so no initialization error can ever be masked by
 *    Minecraft.fillReport's secondary NoClassDefFoundError.
 * 2. Broadcasts lightweight UDP telemetry packets to 127.0.0.1:28465.
 */
public final class VeloCraftBridgeMod implements ClientModInitializer {

    public static final short MAGIC_HEADER = 0x5643; // "VC"
    public static final byte MODE_IN_GAME_CROSSHAIR = 0;
    public static final byte MODE_GUI_CURSOR        = 1;
    public static final byte MODE_CHAT_INPUT        = 2;

    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
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

    public static void logRaw(String msg) {
        RAW_OUT.println("[VeloCraft-Bridge] " + msg);
        RAW_OUT.flush();
    }

    public static void logThrowableChain(String context, Throwable t) {
        logRaw("ERROR di " + context + ": " + t);
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 6) {
            if (depth > 0) {
                logRaw("Caused by [" + depth + "]: " + cur);
            }
            StackTraceElement[] trace = cur.getStackTrace();
            int limit = Math.min(trace.length, 28);
            for (int i = 0; i < limit; i++) {
                logRaw("  at " + trace[i]);
            }
            Throwable[] suppressed = cur.getSuppressed();
            if (suppressed != null) {
                for (Throwable s : suppressed) {
                    logRaw("  Suppressed: " + s);
                }
            }
            Throwable next = cur.getCause();
            if (next == cur) break;
            cur = next;
            depth++;
        }
    }

    @Override
    public void onInitializeClient() {
        System.setProperty("org.lwjgl.system.allocator", "system");
        System.setProperty("org.lwjgl.util.NoChecks", "true");

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logThrowableChain("UncaughtException[" + thread.getName() + "]", throwable);
        });

        ClassLoader cl = VeloCraftBridgeMod.class.getClassLoader();
        try {
            logRaw("Memverifikasi LWJGL 3 MemoryUtil (allocator=" +
                System.getProperty("org.lwjgl.system.allocator") + ") di thread: " +
                Thread.currentThread().getName());
            Class.forName("org.lwjgl.system.MemoryUtil", true, cl);
            Class.forName("org.lwjgl.system.MemoryUtil$LazyInit", true, cl);
            Class.forName("org.lwjgl.system.MemoryStack", true, cl);
            logRaw("LWJGL 3 MemoryUtil & MemoryStack siap.");
        } catch (Throwable t) {
            logThrowableChain("LWJGL MemoryUtil.<clinit>", t);
        }

        try {
            logRaw("Menginisialisasi com.mojang.blaze3d.systems.RenderSystem...");
            Class.forName("com.mojang.blaze3d.systems.RenderSystem", true, cl);
            logRaw("com.mojang.blaze3d.systems.RenderSystem siap.");
        } catch (Throwable t) {
            logThrowableChain("RenderSystem.<clinit>", t);
        }
    }

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
