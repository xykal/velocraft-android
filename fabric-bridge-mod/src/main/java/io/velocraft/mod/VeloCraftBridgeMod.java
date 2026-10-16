package io.velocraft.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

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
 * 1. `onPreLaunch()`: Runs inside `KnotClassLoader` BEFORE `Main.main()` and `Bootstrap.wrapStreams()`.
 *    Initializes `org.lwjgl.glfw.GLFW`, `org.lwjgl.system.Library`, and `org.lwjgl.system.MemoryUtil`
 *    (`StdlibAllocator`) exclusively inside `KnotClassLoader` so `liblwjgl.so` is never loaded across
 *    two different ClassLoaders (`AppClassLoader` vs `KnotClassLoader`).
 * 2. `onInitializeClient()`: Verifies `RenderSystem` and starts the lightweight UDP telemetry bridge.
 */
public final class VeloCraftBridgeMod implements PreLaunchEntrypoint, ClientModInitializer {

    public static final short MAGIC_HEADER = 0x5643; // "VC"
    public static final byte MODE_IN_GAME_CROSSHAIR = 0;
    public static final byte MODE_GUI_CURSOR        = 1;
    public static final byte MODE_CHAT_INPUT        = 2;

    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
    private static DatagramSocket udpSocket;
    private static InetAddress loopbackAddr;
    private static int bridgePort = 28465;
    private static volatile boolean lwjglBootstrapped = false;

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

    private static synchronized void bootstrapLwjglInKnotClassLoader(String stageLabel) {
        if (lwjglBootstrapped) return;
        System.setProperty("org.lwjgl.system.allocator", "system");
        System.setProperty("org.lwjgl.util.NoChecks", "true");

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logThrowableChain("UncaughtException[" + thread.getName() + "]", throwable);
        });

        ClassLoader cl = VeloCraftBridgeMod.class.getClassLoader();
        logRaw(stageLabel + ": Menginisialisasi LWJGL 3 di ClassLoader=" + cl);

        try {
            Class.forName("org.lwjgl.glfw.GLFW", true, cl);
            Class<?> memUtil = Class.forName("org.lwjgl.system.MemoryUtil", true, cl);
            Class.forName("org.lwjgl.system.MemoryUtil$LazyInit", true, cl);
            long ptr = (Long) memUtil.getMethod("memAllocAddress", int.class).invoke(null, 64);
            memUtil.getMethod("nmemFree", long.class).invoke(null, ptr);
            lwjglBootstrapped = true;
            logRaw(stageLabel + ": LWJGL 3 Library, GLFW & MemoryUtil (StdlibAllocator) SIAP di KnotClassLoader (ptr=0x" +
                Long.toHexString(ptr) + ").");
        } catch (Throwable t) {
            logThrowableChain(stageLabel + "/LWJGL-Bootstrap", t);
        }
    }

    @Override
    public void onPreLaunch() {
        bootstrapLwjglInKnotClassLoader("onPreLaunch");
    }

    @Override
    public void onInitializeClient() {
        bootstrapLwjglInKnotClassLoader("onInitializeClient");
        ClassLoader cl = VeloCraftBridgeMod.class.getClassLoader();
        try {
            Class.forName("com.mojang.blaze3d.systems.RenderSystem", true, cl);
            logRaw("Blaze3D RenderSystem berhasil diverifikasi di KnotClassLoader.");
        } catch (Throwable t) {
            logThrowableChain("RenderSystem.<clinit>", t);
        }
        sendTelemetryTick(MODE_GUI_CURSOR, 120, 20.0f, 0, "velocraft:bridge_active");
    }

    public static void sendTelemetryState(
        byte uiMode,
        int hotbarSlot,
        int currentFps,
        int pingMs,
        boolean sprinting,
        boolean sneaking,
        String screenName
    ) {
        sendTelemetryTick(uiMode, currentFps, 20.0f, hotbarSlot, screenName);
    }

    public static void sendTelemetryTick(
        byte uiMode,
        int fps,
        float health,
        int hotbarSlot,
        String heldItemId
    ) {
        if (udpSocket == null || loopbackAddr == null) return;
        try {
            byte[] itemBytes = (heldItemId != null ? heldItemId : "minecraft:air")
                .getBytes(StandardCharsets.UTF_8);
            int len = Math.min(itemBytes.length, 64);

            ByteBuffer buf = ByteBuffer.allocate(16 + len).order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort(MAGIC_HEADER);
            buf.put(uiMode);
            buf.put((byte) (hotbarSlot & 0x0F));
            buf.putInt(fps);
            buf.putFloat(health);
            buf.putInt(len);
            buf.put(itemBytes, 0, len);

            byte[] packetData = buf.array();
            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, loopbackAddr, bridgePort);
            udpSocket.send(packet);
        } catch (Exception ignored) {
        }
    }
}
