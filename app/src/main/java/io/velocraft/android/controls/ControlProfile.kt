package io.velocraft.android.controls

import android.content.Context
import io.velocraft.android.bridge.NativeEngineBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Defines a single interactive button or control widget on the Pro PvP HUD.
 * Styled with a clean, matte, non-neon palette (Warm Slate, Moss Green, Terracotta, Stone).
 */
data class HudButtonConfig(
    val id: String,
    var label: String,
    var normX: Float,
    var normY: Float,
    var radiusDp: Float,
    var opacity: Float = 0.80f,
    val actionType: ActionType,
    var keyCodeOrButton: Int = 0,
    val isToggle: Boolean = false,
    val accentHex: Int = 0xFF8C867B.toInt()
)

enum class ActionType {
    KEY_HOLD,
    KEY_TOGGLE,
    MOUSE_LEFT_ATTACK,
    MOUSE_RIGHT_PLACE,
    HOTBAR_SLOT,
    GYRO_TOGGLE,
    HUD_EDITOR_TOGGLE,
    QUICK_SETTINGS,
    PVP_COMBO_MACRO
}

object ControlProfileManager {

    private const val PROFILE_FILENAME = "velocraft_pvp_hud_clean_v1.json"

    /**
     * Default Pro PvP Layout using a clean, matte, non-neon color scheme.
     */
    fun createDefaultProPvpLayout(): MutableList<HudButtonConfig> = mutableListOf(
        // Left-side Movement Cluster (Matte Stone & Moss Green)
        HudButtonConfig("move_w", "▲", 0.14f, 0.56f, 30f, 0.78f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_W, accentHex = 0xFF78736A.toInt()),
        HudButtonConfig("move_s", "▼", 0.14f, 0.78f, 30f, 0.78f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_S, accentHex = 0xFF78736A.toInt()),
        HudButtonConfig("move_a", "◀", 0.07f, 0.67f, 30f, 0.78f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_A, accentHex = 0xFF78736A.toInt()),
        HudButtonConfig("move_d", "▶", 0.21f, 0.67f, 30f, 0.78f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_D, accentHex = 0xFF78736A.toInt()),
        HudButtonConfig("sprint_lock", "SPRINT", 0.14f, 0.42f, 24f, 0.82f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_CONTROL, isToggle = true, accentHex = 0xFF4E8C57.toInt()),
        HudButtonConfig("sneak_toggle", "SNEAK", 0.06f, 0.84f, 25f, 0.82f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_SHIFT, isToggle = true, accentHex = 0xFFC87D55.toInt()),

        // Right-side Pro PvP Action Cluster
        HudButtonConfig("btn_attack", "ATK (L)", 0.82f, 0.64f, 40f, 0.86f, ActionType.MOUSE_LEFT_ATTACK, NativeEngineBridge.MOUSE_LEFT, accentHex = 0xFFB85448.toInt()),
        HudButtonConfig("btn_place", "PLACE (R)", 0.92f, 0.52f, 36f, 0.86f, ActionType.MOUSE_RIGHT_PLACE, NativeEngineBridge.MOUSE_RIGHT, accentHex = 0xFF5A7D9A.toInt()),
        HudButtonConfig("btn_jump", "JUMP", 0.90f, 0.78f, 34f, 0.82f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_SPACE, accentHex = 0xFF4E8C57.toInt()),
        HudButtonConfig("btn_offhand", "OFF (F)", 0.74f, 0.80f, 24f, 0.78f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F, accentHex = 0xFF8C7A6B.toInt()),
        HudButtonConfig("btn_macro", "MACRO", 0.65f, 0.66f, 25f, 0.82f, ActionType.PVP_COMBO_MACRO, accentHex = 0xFFC87D55.toInt()),
        HudButtonConfig("btn_drop", "DROP (Q)", 0.74f, 0.48f, 22f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_Q, accentHex = 0xFF78736A.toInt()),

        // Top Utility Bar
        HudButtonConfig("btn_settings", "⚙ CFG", 0.10f, 0.12f, 20f, 0.80f, ActionType.QUICK_SETTINGS, accentHex = 0xFF78736A.toInt()),
        HudButtonConfig("btn_f3", "F3", 0.18f, 0.12f, 18f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F3, accentHex = 0xFF78736A.toInt()),
        HudButtonConfig("btn_f5", "CAM", 0.26f, 0.12f, 20f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F5, accentHex = 0xFF78736A.toInt()),
        HudButtonConfig("btn_chat", "CHAT", 0.34f, 0.12f, 20f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_T, accentHex = 0xFF78736A.toInt()),
        HudButtonConfig("btn_gyro", "GYRO", 0.75f, 0.12f, 20f, 0.80f, ActionType.GYRO_TOGGLE, isToggle = true, accentHex = 0xFF4E8C57.toInt()),
        HudButtonConfig("btn_inv", "INV (E)", 0.84f, 0.12f, 22f, 0.82f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_E, accentHex = 0xFFC87D55.toInt()),
        HudButtonConfig("btn_edit_hud", "SELESAI / HUD", 0.93f, 0.12f, 22f, 0.82f, ActionType.HUD_EDITOR_TOGGLE, isToggle = true, accentHex = 0xFFC87D55.toInt())
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
                        opacity = obj.optDouble("opacity", 0.80).toFloat(),
                        actionType = ActionType.valueOf(obj.getString("actionType")),
                        keyCodeOrButton = obj.optInt("keyCodeOrButton", 0),
                        isToggle = obj.optBoolean("isToggle", false),
                        accentHex = obj.optInt("accentHex", 0xFF78736A.toInt())
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
