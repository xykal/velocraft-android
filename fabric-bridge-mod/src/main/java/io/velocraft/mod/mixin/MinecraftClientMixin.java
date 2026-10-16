package io.velocraft.mod.mixin;

import io.velocraft.mod.VeloCraftBridgeMod;

/**
 * Mixin hook template for net.minecraft.client.MinecraftClient.
 *
 * When compiled with Fabric Loom against Minecraft 1.20.4 Yarn mappings,
 * @Inject(method = "setScreen", at = @At("RETURN")) notifies the Android native layer
 * within <0.2ms whenever any GUI screen (Inventory, Chest, Pause Menu, Chat) opens or closes.
 */
public class MinecraftClientMixin {

    /**
     * Hook invoked when Minecraft transitions between In-Game world (screen == null)
     * and any GUI Screen (InventoryScreen, GenericContainerScreen, ChatScreen, etc.).
     */
    public static void onScreenChanged(
            Object currentScreen,
            int hotbarSlot,
            int currentFps,
            int pingMs,
            boolean sprinting,
            boolean sneaking
    ) {
        final byte mode;
        final String screenName;

        if (currentScreen == null) {
            mode = VeloCraftBridgeMod.MODE_IN_GAME_CROSSHAIR;
            screenName = "";
        } else {
            screenName = currentScreen.getClass().getSimpleName();
            if (screenName.contains("Chat")) {
                mode = VeloCraftBridgeMod.MODE_CHAT_INPUT;
            } else {
                mode = VeloCraftBridgeMod.MODE_GUI_CURSOR;
            }
        }

        VeloCraftBridgeMod.sendTelemetryState(
                mode,
                hotbarSlot,
                currentFps,
                pingMs,
                sprinting,
                sneaking,
                screenName
        );
    }
}
