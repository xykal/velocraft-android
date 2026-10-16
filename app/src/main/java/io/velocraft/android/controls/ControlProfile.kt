package io.velocraft.android.controls

import android.content.Context
import io.velocraft.android.bridge.NativeEngineBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Defines a single interactive button or control widget on the Pro PvP HUD.
 * Coordinates (normX, normY) are normalized (0.0f .. 1.0f) relative to screen width/height
 * so layouts adapt cleanly across different aspect ratios (16:9, 20:9, 21:9).
 */
data class HudButtonConfig(
    val id: String,
    var label: String,
    var normX: Float,
    var normY: Float,
    var radiusDp: Float,
    var opacity: Float = 0.78f,
    val actionType: ActionType,
    var keyCodeOrButton: Int = 0,
    val isToggle: Boolean = false,
    val accentHex: Int = 0xFF38BDF8.toInt()
)

enum class ActionType {
    KEY_HOLD,           // Standard GLFW key press on touch down, release on touch up
    KEY_TOGGLE,         // Toggles state on tap (e.g., Sneak Lock, Sprint Lock, Bridging Lock)
    MOUSE_LEFT_ATTACK,  // Left Click + CPS counter + optional Hold-to-Attack
    MOUSE_RIGHT_PLACE,  // Right Click + CPS counter + FastPlace / Bridging hold
    HOTBAR_SLOT,        // Direct 1-9 slot selection
    GYRO_TOGGLE,        // Enables/Disables Gyroscope aiming
    HUD_EDITOR_TOGGLE,  // Opens in-game drag-and-drop button customization mode
    QUICK_SETTINGS,     // Opens floating in-game Pro PvP Quick Settings drawer
    PVP_COMBO_MACRO     // 1-Tap Utility Swap -> Right Click -> Return to Sword Slot 1
}

object ControlProfileManager {

    private const val PROFILE_FILENAME = "velocraft_pvp_hud.json"

    /**
     * Default Pro PvP / BedWars / Survival Claw & Thumb Ergonomic Layout.
     */
    fun createDefaultProPvpLayout(): MutableList<HudButtonConfig> = mutableListOf(
        // Left-side Movement Cluster (Ergonomic WASD + Sprint Lock)
        HudButtonConfig("move_w", "▲", 0.14f, 0.56f, 30f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_W),
        HudButtonConfig("move_s", "▼", 0.14f, 0.78f, 30f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_S),
        HudButtonConfig("move_a", "◀", 0.07f, 0.67f, 30f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_A),
        HudButtonConfig("move_d", "▶", 0.21f, 0.67f, 30f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_D),
        HudButtonConfig("sprint_lock", "SPRINT", 0.14f, 0.42f, 24f, 0.80f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_CONTROL, isToggle = true, accentHex = 0xFF22C55E.toInt()),
        HudButtonConfig("sneak_toggle", "SNEAK", 0.06f, 0.84f, 25f, 0.80f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_SHIFT, isToggle = true, accentHex = 0xFFF59E0B.toInt()),

        // Right-side Pro PvP Action Cluster (Attack, Place/Bridge, Jump, Offhand, Drop)
        HudButtonConfig("btn_attack", "ATK (L)", 0.82f, 0.64f, 40f, 0.88f, ActionType.MOUSE_LEFT_ATTACK, NativeEngineBridge.MOUSE_LEFT, accentHex = 0xFFEF4444.toInt()),
        HudButtonConfig("btn_place", "PLACE (R)", 0.92f, 0.52f, 36f, 0.88f, ActionType.MOUSE_RIGHT_PLACE, NativeEngineBridge.MOUSE_RIGHT, accentHex = 0xFF3B82F6.toInt()),
        HudButtonConfig("btn_jump", "JUMP", 0.90f, 0.78f, 34f, 0.82f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_SPACE, accentHex = 0xFF10B981.toInt()),
        HudButtonConfig("btn_offhand", "OFFHAND (F)", 0.74f, 0.80f, 24f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F, accentHex = 0xFFA855F7.toInt()),
        HudButtonConfig("btn_macro", "⚡ MACRO", 0.65f, 0.66f, 25f, 0.82f, ActionType.PVP_COMBO_MACRO, accentHex = 0xFFEC4899.toInt()),
        HudButtonConfig("btn_drop", "DROP (Q)", 0.74f, 0.48f, 22f, 0.72f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_Q),

        // Top Utility Bar (Inventory, Perspective F5, Debug F3, Chat, Gyro, Layout Editor)
        HudButtonConfig("btn_inv", "INV (E)", 0.84f, 0.12f, 22f, 0.80f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_E, accentHex = 0xFFEAB308.toInt()),
        HudButtonConfig("btn_f5", "CAM (F5)", 0.26f, 0.12f, 20f, 0.72f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F5),
        HudButtonConfig("btn_f3", "F3", 0.18f, 0.12f, 18f, 0.70f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F3),
        HudButtonConfig("btn_chat", "CHAT", 0.34f, 0.12f, 20f, 0.72f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_T),
        HudButtonConfig("btn_gyro", "GYRO", 0.75f, 0.12f, 20f, 0.78f, ActionType.GYRO_TOGGLE, isToggle = true, accentHex = 0xFF06B6D4.toInt()),
        HudButtonConfig("btn_settings", "⚙ CFG", 0.10f, 0.12f, 20f, 0.78f, ActionType.QUICK_SETTINGS, accentHex = 0xFF38BDF8.toInt()),
        HudButtonConfig("btn_edit_hud", "EDIT HUD", 0.93f, 0.12f, 20f, 0.78f, ActionType.HUD_EDITOR_TOGGLE, isToggle = true, accentHex = 0xFFF97316.toInt())
    )

    fun loadLayout(context: Context): MutableList<HudButtonConfig> {
        val file = File(context.filesDir, PROFILE_FILENAME)
        if (!file.exists()) return createDefaultProPvpLayout()

        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<HudButtonConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    HudButtonConfig(
                        id = obj.getString("id"),
                        label = obj.getString("label"),
                        normX = obj.getDouble("normX").toFloat(),
                        normY = obj.getDouble("normY").toFloat(),
                        radiusDp = obj.getDouble("radiusDp").toFloat(),
                        opacity = obj.optDouble("opacity", 0.78).toFloat(),
                        actionType = ActionType.valueOf(obj.getString("actionType")),
                        keyCodeOrButton = obj.optInt("keyCodeOrButton", 0),
                        isToggle = obj.optBoolean("isToggle", false),
                        accentHex = obj.optInt("accentHex", 0xFF38BDF8.toInt())
                    )
                )
            }
            list
        } catch (_: Exception) {
            createDefaultProPvpLayout()
        }
    }

    fun saveLayout(context: Context, buttons: List<HudButtonConfig>) {
        val arr = JSONArray()
        for (b in buttons) {
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("label", b.label)
                put("normX", b.normX)
                put("normY", b.normY)
                put("radiusDp", b.radiusDp)
                put("opacity", b.opacity)
                put("actionType", b.actionType.name)
                put("keyCodeOrButton", b.keyCodeOrButton)
                put("isToggle", b.isToggle)
                put("accentHex", b.accentHex)
            })
        }
        File(context.filesDir, PROFILE_FILENAME).writeText(arr.toString(2))
    }
}
