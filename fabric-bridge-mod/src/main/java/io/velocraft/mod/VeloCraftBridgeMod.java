package io.velocraft.mod;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * VeloCraft Native Bridge Mod (Fabric 1.20.4).
 *
 * 1. Pre-verifies LWJGL 3 MemoryUtil (StdlibAllocator) and Blaze3D RenderSystem on the Render thread
 *    with unbuffered FileDescriptor.out logging so no initialization error can ever be masked by
 *    Minecraft.fillReport's secondary NoClassDefFoundError.
 * 2. Broadcasts real-time client state over localhost UDP to the Android NDK HUD layer.
 */
public class VeloCraftBridgeMod implements ClientModInitializer {

    private static final int DEFAULT_UDP_PORT = Integer.getInteger("velocraft.bridge.port", 28465);
    private static final PrintStream RAW_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VeloCraft-State-Broadcaster");
        t.setDaemon(true);
        return t;
    });

    private DatagramSocket socket;
    private InetAddress loopback;

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
            int limit = Math.min(trace.length, 24);
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

        try {
            socket = new DatagramSocket();
            loopback = InetAddress.getLoopbackAddress();
            executor.scheduleAtFixedRate(this::broadcastClientState, 100, 50, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logRaw("Gagal menginisialisasi soket UDP telemetry: " + e.getMessage());
        }
    }

    private void broadcastClientState() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;

            boolean inWorld = client.world != null && client.player != null;
            boolean screenOpen = client.currentScreen != null;
            boolean cursorGrabbed = client.mouse != null && client.mouse.isCursorLocked();
            int fps = client.getCurrentFps();
            float health = inWorld && client.player != null ? client.player.getHealth() : 20.0f;
            int selectedSlot = inWorld && client.player != null ? client.player.getInventory().selectedSlot : 0;

            String payload = String.format(
                "{\"inWorld\":%b,\"screenOpen\":%b,\"cursorGrabbed\":%b,\"fps\":%d,\"health\":%.1f,\"slot\":%d}",
                inWorld, screenOpen, cursorGrabbed, fps, health, selectedSlot
            );

            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, loopback, DEFAULT_UDP_PORT);
            socket.send(packet);
        } catch (Exception ignored) {
        }
    }
}
