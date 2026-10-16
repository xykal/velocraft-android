package io.velocraft.android.controls

import android.content.Context
import io.velocraft.android.bridge.NativeEngineBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ButtonShape(val displayName: String) {
    CIRCLE("BULAT"),
    ROUND_RECT("KOTAK"),
    PILL("KAPSUL")
}

/**
 * Defines a single interactive button on the Pro PvP HUD.
 * Styled with a clean, matte, non-neon palette and strictly zero emojis.
 */
data class HudButtonConfig(
    val id: String,
    var label: String,
    var normX: Float,
    var normY: Float,
    var radiusDp: Float,
    var opacity: Float = 0.80f,
    var actionType: ActionType,
    var keyCodeOrButton: Int = 0,
    var isToggle: Boolean = false,
    var accentHex: Int = 0xFF78736A.toInt(),
    var shape: ButtonShape = ButtonShape.CIRCLE,
    var isVisible: Boolean = true
)

enum class ActionType {
    KEY_HOLD,
    KEY_TOGGLE,
    MOUSE_LEFT_ATTACK,
    MOUSE_RIGHT_PLACE,
    MOUSE_MIDDLE_PICK,
    MOUSE_SCROLL_UP,
    MOUSE_SCROLL_DOWN,
    HOTBAR_SLOT,
    GYRO_TOGGLE,
    CURSOR_TOGGLE,
    HUD_EDITOR_TOGGLE,
    QUICK_SETTINGS,
    PVP_COMBO_MACRO,
    MACRO_DROP_STACK,
    MACRO_HITBOXES,
    MACRO_CHUNK_BORDERS,
    MACRO_RELOAD_CHUNKS,
    TOGGLE_HUD_CLEAN
}

object ControlProfileManager {

    private const val PROFILE_FILENAME = "velocraft_pvp_hud_clean_v3.json"
    private const val LEGACY_PROFILE_FILENAME = "velocraft_pvp_hud_clean_v2.json"

    val STUDIO_ACCENT_PALETTE: List<Pair<String, Int>> = listOf(
        "HIJAU STUDIO" to 0xFF4E8C57.toInt(),
        "TERRACOTTA" to 0xFFC87D55.toInt(),
        "MERAH KOMBAT" to 0xFFB85448.toInt(),
        "BIRU BAJA" to 0xFF5A7D9A.toInt(),
        "EMAS AMBER" to 0xFFB89248.toInt(),
        "ABU MONOLIT" to 0xFF78736A.toInt()
    )

    /**
     * Preset 1: Standar Studio Lengkap (Balanced PvP + Survival + Menu Navigation).
     */
    fun createDefaultProPvpLayout(): MutableList<HudButtonConfig> = mutableListOf(
        // Left-side Movement Cluster (W, A, S, D + Sprint & Sneak)
        HudButtonConfig("move_w", "W", 0.14f, 0.56f, 30f, 0.80f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_W, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("move_s", "S", 0.14f, 0.79f, 30f, 0.80f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_S, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("move_a", "A", 0.065f, 0.675f, 30f, 0.80f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_A, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("move_d", "D", 0.215f, 0.675f, 30f, 0.80f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_D, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("sprint_lock", "SPRINT", 0.14f, 0.41f, 23f, 0.82f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_CONTROL, isToggle = true, accentHex = 0xFF4E8C57.toInt(), shape = ButtonShape.PILL),
        HudButtonConfig("sneak_toggle", "SNEAK", 0.06f, 0.87f, 23f, 0.82f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_SHIFT, isToggle = true, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.PILL),

        // Right-side Pro PvP Action Cluster
        HudButtonConfig("btn_attack", "ATK (L)", 0.82f, 0.64f, 40f, 0.88f, ActionType.MOUSE_LEFT_ATTACK, NativeEngineBridge.MOUSE_LEFT, accentHex = 0xFFB85448.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_place", "PLACE (R)", 0.92f, 0.51f, 35f, 0.88f, ActionType.MOUSE_RIGHT_PLACE, NativeEngineBridge.MOUSE_RIGHT, accentHex = 0xFF5A7D9A.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_jump", "JUMP", 0.90f, 0.79f, 34f, 0.84f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_SPACE, accentHex = 0xFF4E8C57.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_offhand", "OFF (F)", 0.73f, 0.81f, 24f, 0.80f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F, accentHex = 0xFFB89248.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_macro", "COMBO", 0.65f, 0.66f, 25f, 0.82f, ActionType.PVP_COMBO_MACRO, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_drop", "DROP (Q)", 0.74f, 0.47f, 22f, 0.76f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_Q, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_pick", "PICK (M)", 0.83f, 0.41f, 21f, 0.76f, ActionType.MOUSE_MIDDLE_PICK, NativeEngineBridge.MOUSE_MIDDLE, accentHex = 0xFF5A7D9A.toInt(), shape = ButtonShape.CIRCLE),

        // Top Utility Bar (CFG, ESC, F3, CAM, CHAT, CURSOR, GYRO, INV, EDIT HUD)
        HudButtonConfig("btn_settings", "CFG", 0.06f, 0.12f, 19f, 0.84f, ActionType.QUICK_SETTINGS, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_esc", "ESC", 0.13f, 0.12f, 19f, 0.82f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_ESCAPE, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_f3", "F3", 0.20f, 0.12f, 18f, 0.78f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F3, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_f5", "CAM", 0.27f, 0.12f, 19f, 0.78f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F5, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_chat", "CHAT", 0.34f, 0.12f, 19f, 0.78f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_T, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_cursor", "KURSOR", 0.67f, 0.12f, 20f, 0.84f, ActionType.CURSOR_TOGGLE, isToggle = true, accentHex = 0xFF5A7D9A.toInt(), shape = ButtonShape.PILL),
        HudButtonConfig("btn_gyro", "GYRO", 0.76f, 0.12f, 19f, 0.80f, ActionType.GYRO_TOGGLE, isToggle = true, accentHex = 0xFF4E8C57.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_inv", "INV (E)", 0.84f, 0.12f, 21f, 0.84f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_E, accentHex = 0xFFB89248.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_edit_hud", "EDIT HUD", 0.93f, 0.12f, 21f, 0.86f, ActionType.HUD_EDITOR_TOGGLE, isToggle = true, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.PILL)
    )

    /**
     * Preset 2: PvP Kompetitif (Claw / 3-4 Jari, Tombol Serang & Makro ekstra besar + Hitbox F3+B).
     */
    fun createCompetitivePvpLayout(): MutableList<HudButtonConfig> = mutableListOf(
        HudButtonConfig("move_w", "W", 0.14f, 0.54f, 32f, 0.82f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_W, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("move_s", "S", 0.14f, 0.78f, 32f, 0.82f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_S, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("move_a", "A", 0.06f, 0.66f, 32f, 0.82f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_A, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("move_d", "D", 0.22f, 0.66f, 32f, 0.82f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_D, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("sprint_lock", "SPRINT", 0.14f, 0.38f, 24f, 0.86f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_CONTROL, isToggle = true, accentHex = 0xFF4E8C57.toInt(), shape = ButtonShape.PILL),
        HudButtonConfig("sneak_toggle", "SNEAK", 0.06f, 0.88f, 24f, 0.84f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_SHIFT, isToggle = true, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.PILL),

        HudButtonConfig("btn_attack", "ATK (L)", 0.80f, 0.62f, 45f, 0.90f, ActionType.MOUSE_LEFT_ATTACK, NativeEngineBridge.MOUSE_LEFT, accentHex = 0xFFB85448.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_place", "PLACE (R)", 0.92f, 0.48f, 38f, 0.90f, ActionType.MOUSE_RIGHT_PLACE, NativeEngineBridge.MOUSE_RIGHT, accentHex = 0xFF5A7D9A.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_jump", "JUMP", 0.91f, 0.78f, 36f, 0.86f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_SPACE, accentHex = 0xFF4E8C57.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_macro", "COMBO", 0.67f, 0.62f, 28f, 0.88f, ActionType.PVP_COMBO_MACRO, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_offhand", "OFF (F)", 0.72f, 0.80f, 26f, 0.84f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F, accentHex = 0xFFB89248.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_f5", "CAM F5", 0.70f, 0.42f, 24f, 0.84f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_F5, accentHex = 0xFF5A7D9A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_hitbox", "HITBOX", 0.81f, 0.38f, 21f, 0.80f, ActionType.MACRO_HITBOXES, accentHex = 0xFFB89248.toInt(), shape = ButtonShape.PILL),

        HudButtonConfig("btn_settings", "CFG", 0.06f, 0.12f, 19f, 0.82f, ActionType.QUICK_SETTINGS, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_esc", "ESC", 0.13f, 0.12f, 19f, 0.82f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_ESCAPE, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_cursor", "KURSOR", 0.67f, 0.12f, 20f, 0.84f, ActionType.CURSOR_TOGGLE, isToggle = true, accentHex = 0xFF5A7D9A.toInt(), shape = ButtonShape.PILL),
        HudButtonConfig("btn_gyro", "GYRO", 0.76f, 0.12f, 19f, 0.82f, ActionType.GYRO_TOGGLE, isToggle = true, accentHex = 0xFF4E8C57.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_inv", "INV (E)", 0.84f, 0.12f, 21f, 0.84f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_E, accentHex = 0xFFB89248.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_edit_hud", "EDIT HUD", 0.93f, 0.12f, 21f, 0.86f, ActionType.HUD_EDITOR_TOGGLE, isToggle = true, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.PILL)
    ).apply {
        forEach { if (it.id == "move_s") it.accentHex = 0xFF78736A.toInt() }
    }

    /**
     * Preset 3: Builder & Survival Minimalis (Layar lega, opasitas ringan + Scroll & Pick Block).
     */
    fun createBuilderMinimalLayout(): MutableList<HudButtonConfig> = mutableListOf(
        HudButtonConfig("move_w", "W", 0.13f, 0.58f, 28f, 0.65f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_W, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("move_s", "S", 0.13f, 0.80f, 28f, 0.65f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_S, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("move_a", "A", 0.06f, 0.69f, 28f, 0.65f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_A, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("move_d", "D", 0.20f, 0.69f, 28f, 0.65f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_D, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("sprint_lock", "SPRINT", 0.13f, 0.44f, 21f, 0.68f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_CONTROL, isToggle = true, accentHex = 0xFF4E8C57.toInt(), shape = ButtonShape.PILL),
        HudButtonConfig("sneak_toggle", "SNEAK", 0.055f, 0.88f, 21f, 0.68f, ActionType.KEY_TOGGLE, NativeEngineBridge.KEY_LEFT_SHIFT, isToggle = true, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.PILL),

        HudButtonConfig("btn_attack", "HANCUR (L)", 0.83f, 0.66f, 36f, 0.72f, ActionType.MOUSE_LEFT_ATTACK, NativeEngineBridge.MOUSE_LEFT, accentHex = 0xFFB85448.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_place", "PASANG (R)", 0.92f, 0.52f, 36f, 0.72f, ActionType.MOUSE_RIGHT_PLACE, NativeEngineBridge.MOUSE_RIGHT, accentHex = 0xFF5A7D9A.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_jump", "JUMP", 0.91f, 0.79f, 32f, 0.70f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_SPACE, accentHex = 0xFF4E8C57.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_pick", "PICK", 0.82f, 0.44f, 22f, 0.68f, ActionType.MOUSE_MIDDLE_PICK, NativeEngineBridge.MOUSE_MIDDLE, accentHex = 0xFFB89248.toInt(), shape = ButtonShape.CIRCLE),
        HudButtonConfig("btn_scroll_up", "SCRL -", 0.73f, 0.52f, 20f, 0.65f, ActionType.MOUSE_SCROLL_UP, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_scroll_dn", "SCRL +", 0.73f, 0.66f, 20f, 0.65f, ActionType.MOUSE_SCROLL_DOWN, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),

        HudButtonConfig("btn_settings", "CFG", 0.06f, 0.12f, 18f, 0.72f, ActionType.QUICK_SETTINGS, accentHex = 0xFF78736A.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_esc", "ESC", 0.13f, 0.12f, 18f, 0.72f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_ESCAPE, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_cursor", "KURSOR", 0.73f, 0.12f, 19f, 0.75f, ActionType.CURSOR_TOGGLE, isToggle = true, accentHex = 0xFF5A7D9A.toInt(), shape = ButtonShape.PILL),
        HudButtonConfig("btn_inv", "INV (E)", 0.83f, 0.12f, 20f, 0.75f, ActionType.KEY_HOLD, NativeEngineBridge.KEY_E, accentHex = 0xFFB89248.toInt(), shape = ButtonShape.ROUND_RECT),
        HudButtonConfig("btn_edit_hud", "EDIT HUD", 0.93f, 0.12f, 20f, 0.78f, ActionType.HUD_EDITOR_TOGGLE, isToggle = true, accentHex = 0xFFC87D55.toInt(), shape = ButtonShape.PILL)
    )

    fun loadLayout(context: Context): MutableList<HudButtonConfig> {
        val file = File(context.filesDir, PROFILE_FILENAME)
        val sourceFile = if (file.exists()) file else File(context.filesDir, LEGACY_PROFILE_FILENAME)
        if (!sourceFile.exists()) return createDefaultProPvpLayout()

        return try {
            val arr = JSONArray(sourceFile.readText())
            val list = mutableListOf<HudButtonConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val shapeName = obj.optString("shape", ButtonShape.CIRCLE.name)
                val parsedShape = try {
                    ButtonShape.valueOf(shapeName)
                } catch (_: Exception) {
                    ButtonShape.CIRCLE
                }
                val actionName = obj.optString("actionType", ActionType.KEY_HOLD.name)
                val parsedAction = try {
                    ActionType.valueOf(actionName)
                } catch (_: Exception) {
                    ActionType.KEY_HOLD
                }
                list.add(
                    HudButtonConfig(
                        id = obj.getString("id"),
                        label = obj.getString("label"),
                        normX = obj.getDouble("normX").toFloat().coerceIn(0.03f, 0.97f),
                        normY = obj.getDouble("normY").toFloat().coerceIn(0.05f, 0.95f),
                        radiusDp = obj.getDouble("radiusDp").toFloat().coerceIn(14f, 64f),
                        opacity = obj.optDouble("opacity", 0.80).toFloat().coerceIn(0.15f, 1.0f),
                        actionType = parsedAction,
                        keyCodeOrButton = obj.optInt("keyCodeOrButton", 0),
                        isToggle = obj.optBoolean("isToggle", false),
                        accentHex = obj.optInt("accentHex", 0xFF78736A.toInt()),
                        shape = parsedShape,
                        isVisible = obj.optBoolean("isVisible", true)
                    )
                )
            }
            if (list.isEmpty()) createDefaultProPvpLayout() else list
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
                put("shape", b.shape.name)
                put("isVisible", b.isVisible)
            })
        }
        File(context.filesDir, PROFILE_FILENAME).writeText(arr.toString(2))
    }
}
