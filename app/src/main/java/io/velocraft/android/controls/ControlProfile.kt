package io.velocraft.android.controls

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.velocraft.android.bridge.NativeEngineBridge
import io.velocraft.android.util.VeloLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ActionType(val displayName: String) {
    KEY_HOLD("Tahan Tombol (Hold)"),
    KEY_TOGGLE("Saklar Tombol (Toggle)"),
    MOUSE_LEFT_ATTACK("Klik Kiri / Serang (LMB)"),
    MOUSE_RIGHT_PLACE("Klik Kanan / Pasang (RMB)"),
    MOUSE_MIDDLE_PICK("Klik Tengah / Pick Block (MMB)"),
    MOUSE_SCROLL_UP("Scroll Hotbar Naik (Wheel Up)"),
    MOUSE_SCROLL_DOWN("Scroll Hotbar Turun (Wheel Down)"),
    HOTBAR_SLOT("Slot Hotbar (1-9)"),
    CURSOR_TOGGLE("Mode Kursor / Crosshair"),
    HUD_EDITOR_TOGGLE("Mode Edit Tata Letak HUD"),
    GYRO_TOGGLE("Aim Giroskop (Gyro)"),
    PVP_COMBO_MACRO("Macro W-Tap + Sprint Combo"),
    MACRO_DROP_STACK("Macro Buang 1 Stack (Ctrl+Q)"),
    MACRO_HITBOXES("Macro Hitbox Entitas (F3+B)"),
    MACRO_CHUNK_BORDERS("Macro Batas Chunk (F3+G)"),
    MACRO_RELOAD_CHUNKS("Macro Muat Ulang Chunk (F3+A)"),
    TOGGLE_HUD_CLEAN("Sembunyikan / Tampilkan HUD"),
    QUICK_SETTINGS("Pengaturan Studio & Keybind"),
    SIDEBAR_DRAWER_TOGGLE("Panel Samping (Sidebar Menu)"),
    IME_KEYBOARD_TOGGLE("Keyboard Sistem Android (IME)")
}

enum class ButtonShape(val displayName: String) {
    ROUND_RECT("Kotak Sudut Halus"),
    CIRCLE("Lingkaran Taktis"),
    PILL("Kapsul Lebar")
}

data class HudButtonConfig(
    val id: String,
    var label: String,
    var normX: Float,
    var normY: Float,
    var radiusDp: Float,
    var opacity: Float,
    var actionType: ActionType,
    var keyCodeOrButton: Int = 0,
    var isToggle: Boolean = false,
    var isVisible: Boolean = true,
    var accentHex: Int = 0xFF4E8C57.toInt(),
    var shape: ButtonShape = ButtonShape.ROUND_RECT
)

object ControlProfileManager {
    private const val PROFILE_FILENAME = "velocraft_hud_pro_v6.json"
    private const val CURRENT_SCHEMA_VERSION = 6

    val STUDIO_ACCENT_PALETTE: List<Pair<String, Int>> = listOf(
        "Hijau Zamrud (Emerald)" to 0xFF4E8C57.toInt(),
        "Tembaga Hangat (Terracotta)" to 0xFFC87D55.toInt(),
        "Emas Kuningan (Amber)" to 0xFFB89248.toInt(),
        "Biru Baja (Slate Blue)" to 0xFF5A7D9A.toInt(),
        "Merah Koral (Crimson)" to 0xFFB85448.toInt(),
        "Abu Grafit (Graphite)" to 0xFF78736A.toInt()
    )

    @Volatile
    var lastShareStatusMessage: String =
        "Format berbagi kontrol: .json standar (Simpan di Android/media/id.xyverse.minecraft/controls/)"

    /**
     * Complete, Mathematically Non-Overlapping PC-to-Mobile Control Layout (v6):
     * - Verified for 360dp-tall 720p landscape screens (e.g. Redmi A2 1600x720) with >=20dp spacing
     *   between every button and zero overlap with the Top Telemetry Bar (`y = 4dp..28dp`) or
     *   the Bottom-Center 9-Slot Hotbar (`drawHotbarBar`).
     * - Zone 1 (Left 3x3 Symmetrical D-Pad & Mobility Grid):
     *   Row 1 (`normY = 0.46f`): [SPRINT]   [W]       [OFF (F)]
     *   Row 2 (`normY = 0.64f`): [A]        [SNEAK]   [D]
     *   Row 3 (`normY = 0.82f`): [DROP Q]   [S]       [INV (E)]
     * - Zone 2 (Right Combat & Action Cluster):
     *   Upper Row (`normY = 0.46f`): [SCRL -]  [SCRL +]  [PICK (M)]  [COMBO]
     *   Lower Arc (`normY = 0.69f..0.82f`): [ATK (L)]  [PLACE (R)]  [JUMP]
     * - Zone 3 (Top Utility Toolbar at `normY = 0.135f`, `ROUND_RECT` `15.5dp` radius):
     *   [ESC] [TAB] [F3] [CAM] [CHAT] [CMD /] [KURSOR] [GYRO] [CLEAN] [EDIT]
     */
    fun createDefaultProPvpLayout(): MutableList<HudButtonConfig> = mutableListOf(
        // =========================================================================
        // 1. LEFT 3x3 SYMMETRICAL D-PAD & MOBILITY GRID (20dp+ gap on all sides)
        // =========================================================================
        // Row 1 (Top of D-Pad: y = 0.46f)
        HudButtonConfig(
            id = "btn_sprint",
            label = "SPRINT",
            normX = 0.065f,
            normY = 0.46f,
            radiusDp = 19.5f,
            opacity = 0.82f,
            actionType = ActionType.KEY_TOGGLE,
            keyCodeOrButton = NativeEngineBridge.KEY_LEFT_CONTROL,
            isToggle = true,
            accentHex = 0xFF4E8C57.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_w",
            label = "W",
            normX = 0.145f,
            normY = 0.46f,
            radiusDp = 21.0f,
            opacity = 0.84f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_W,
            accentHex = 0xFF4E8C57.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_offhand",
            label = "OFF (F)",
            normX = 0.225f,
            normY = 0.46f,
            radiusDp = 19.5f,
            opacity = 0.82f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_F,
            accentHex = 0xFFC87D55.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),

        // Row 2 (Middle of D-Pad: y = 0.64f)
        HudButtonConfig(
            id = "btn_a",
            label = "A",
            normX = 0.065f,
            normY = 0.64f,
            radiusDp = 21.0f,
            opacity = 0.84f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_A,
            accentHex = 0xFF4E8C57.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_sneak",
            label = "SNEAK",
            normX = 0.145f,
            normY = 0.64f,
            radiusDp = 19.5f,
            opacity = 0.82f,
            actionType = ActionType.KEY_TOGGLE,
            keyCodeOrButton = NativeEngineBridge.KEY_LEFT_SHIFT,
            isToggle = true,
            accentHex = 0xFFB89248.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_d",
            label = "D",
            normX = 0.225f,
            normY = 0.64f,
            radiusDp = 21.0f,
            opacity = 0.84f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_D,
            accentHex = 0xFF4E8C57.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),

        // Row 3 (Bottom of D-Pad: y = 0.82f)
        HudButtonConfig(
            id = "btn_drop",
            label = "DROP Q",
            normX = 0.065f,
            normY = 0.82f,
            radiusDp = 19.5f,
            opacity = 0.82f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_Q,
            accentHex = 0xFFB85448.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_s",
            label = "S",
            normX = 0.145f,
            normY = 0.82f,
            radiusDp = 21.0f,
            opacity = 0.84f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_S,
            accentHex = 0xFF4E8C57.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_inv",
            label = "INV (E)",
            normX = 0.225f,
            normY = 0.82f,
            radiusDp = 19.5f,
            opacity = 0.84f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_E,
            accentHex = 0xFFB89248.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),

        // =========================================================================
        // 2. RIGHT ERGONOMIC COMBAT & ACTION CLUSTER
        // =========================================================================
        // Upper Utility Sub-Row (aligned with Row 1 of Left D-Pad at y = 0.46f)
        HudButtonConfig(
            id = "btn_scroll_up",
            label = "SCRL -",
            normX = 0.725f,
            normY = 0.46f,
            radiusDp = 17.5f,
            opacity = 0.78f,
            actionType = ActionType.MOUSE_SCROLL_UP,
            accentHex = 0xFF5A7D9A.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_scroll_down",
            label = "SCRL +",
            normX = 0.795f,
            normY = 0.46f,
            radiusDp = 17.5f,
            opacity = 0.78f,
            actionType = ActionType.MOUSE_SCROLL_DOWN,
            accentHex = 0xFF5A7D9A.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_pick_block",
            label = "PICK (M)",
            normX = 0.865f,
            normY = 0.46f,
            radiusDp = 17.5f,
            opacity = 0.78f,
            actionType = ActionType.MOUSE_MIDDLE_PICK,
            accentHex = 0xFF5A7D9A.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_combo",
            label = "COMBO",
            normX = 0.935f,
            normY = 0.46f,
            radiusDp = 17.5f,
            opacity = 0.80f,
            actionType = ActionType.PVP_COMBO_MACRO,
            accentHex = 0xFFC87D55.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),

        // Main Combat & Jump Arc (generous 30dp+ clearance between buttons)
        HudButtonConfig(
            id = "btn_attack",
            label = "ATK (L)",
            normX = 0.755f,
            normY = 0.73f,
            radiusDp = 25.0f,
            opacity = 0.86f,
            actionType = ActionType.MOUSE_LEFT_ATTACK,
            accentHex = 0xFFC87D55.toInt(),
            shape = ButtonShape.CIRCLE
        ),
        HudButtonConfig(
            id = "btn_place",
            label = "PLACE (R)",
            normX = 0.845f,
            normY = 0.82f,
            radiusDp = 23.0f,
            opacity = 0.86f,
            actionType = ActionType.MOUSE_RIGHT_PLACE,
            accentHex = 0xFFB89248.toInt(),
            shape = ButtonShape.CIRCLE
        ),
        HudButtonConfig(
            id = "btn_jump",
            label = "JUMP",
            normX = 0.930f,
            normY = 0.69f,
            radiusDp = 25.0f,
            opacity = 0.86f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_SPACE,
            accentHex = 0xFF4E8C57.toInt(),
            shape = ButtonShape.CIRCLE
        ),

        // =========================================================================
        // 3. TOP UTILITY TOOLBAR (10 uniform 31dp x 31dp ROUND_RECT buttons at y = 0.135f)
        // =========================================================================
        HudButtonConfig(
            id = "btn_esc",
            label = "ESC",
            normX = 0.060f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.78f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_ESCAPE,
            accentHex = 0xFFB85448.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_tab",
            label = "TAB",
            normX = 0.155f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.78f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_TAB,
            accentHex = 0xFF5A7D9A.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_f3",
            label = "F3",
            normX = 0.250f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.78f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_F3,
            accentHex = 0xFF78736A.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_f5",
            label = "CAM",
            normX = 0.345f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.78f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_F5,
            accentHex = 0xFF78736A.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_chat",
            label = "CHAT",
            normX = 0.440f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.78f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_T,
            accentHex = 0xFF78736A.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_slash",
            label = "CMD /",
            normX = 0.535f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.78f,
            actionType = ActionType.KEY_HOLD,
            keyCodeOrButton = NativeEngineBridge.KEY_SLASH,
            accentHex = 0xFF5A7D9A.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_cursor",
            label = "KURSOR",
            normX = 0.635f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.80f,
            actionType = ActionType.CURSOR_TOGGLE,
            isToggle = true,
            accentHex = 0xFF4E8C57.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_gyro",
            label = "GYRO",
            normX = 0.735f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.78f,
            actionType = ActionType.GYRO_TOGGLE,
            isToggle = true,
            accentHex = 0xFF4E8C57.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_clean_hud",
            label = "CLEAN",
            normX = 0.835f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.78f,
            actionType = ActionType.TOGGLE_HUD_CLEAN,
            isToggle = true,
            accentHex = 0xFF78736A.toInt(),
            shape = ButtonShape.ROUND_RECT
        ),
        HudButtonConfig(
            id = "btn_edit_hud",
            label = "EDIT",
            normX = 0.935f,
            normY = 0.135f,
            radiusDp = 15.5f,
            opacity = 0.82f,
            actionType = ActionType.HUD_EDITOR_TOGGLE,
            isToggle = true,
            accentHex = 0xFFC87D55.toInt(),
            shape = ButtonShape.ROUND_RECT
        )
    )

    fun getExternalControlsDir(context: Context): File {
        val base = VeloLogger.logDir?.parentFile ?: context.filesDir
        return File(base, "controls").apply { mkdirs() }
    }

    fun loadLayout(context: Context): MutableList<HudButtonConfig> {
        val file = File(context.filesDir, PROFILE_FILENAME)
        if (!file.exists()) return createDefaultProPvpLayout()

        val parsed = parseLayoutFromJson(file.readText())
        return if (parsed != null && parsed.isNotEmpty()) parsed else createDefaultProPvpLayout()
    }

    fun parseLayoutFromJson(rawJson: String): MutableList<HudButtonConfig>? {
        return try {
            val trimmed = rawJson.trim()
            val arr = if (trimmed.startsWith("{")) {
                JSONObject(trimmed).optJSONArray("buttons") ?: return null
            } else {
                JSONArray(trimmed)
            }
            val list = mutableListOf<HudButtonConfig>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val shapeName = obj.optString("shape", ButtonShape.ROUND_RECT.name)
                val resolvedShape = try {
                    ButtonShape.valueOf(shapeName)
                } catch (_: Exception) {
                    ButtonShape.ROUND_RECT
                }
                val actionName = obj.optString("actionType", ActionType.KEY_HOLD.name)
                val resolvedAction = try {
                    ActionType.valueOf(actionName)
                } catch (_: Exception) {
                    ActionType.KEY_HOLD
                }
                list.add(
                    HudButtonConfig(
                        id = obj.optString("id", "btn_$i"),
                        label = obj.optString("label", "KEY"),
                        normX = obj.optDouble("normX", 0.5).toFloat().coerceIn(0.03f, 0.97f),
                        normY = obj.optDouble("normY", 0.5).toFloat().coerceIn(0.05f, 0.95f),
                        radiusDp = obj.optDouble("radiusDp", 20.0).toFloat().coerceIn(14f, 68f),
                        opacity = obj.optDouble("opacity", 0.80).toFloat().coerceIn(0.08f, 1.0f),
                        actionType = resolvedAction,
                        keyCodeOrButton = obj.optInt("keyCodeOrButton", 0),
                        isToggle = obj.optBoolean("isToggle", false),
                        isVisible = obj.optBoolean("isVisible", true),
                        accentHex = obj.optInt("accentHex", 0xFF4E8C57.toInt()),
                        shape = resolvedShape
                    )
                )
            }
            if (list.isEmpty()) null else list
        } catch (_: Exception) {
            null
        }
    }

    fun serializeLayoutToJson(
        buttons: List<HudButtonConfig>,
        profileName: String = "Xyverse Pro HUD"
    ): String {
        val root = JSONObject()
        root.put("schema", "id.xyverse.minecraft.controls")
        root.put("version", CURRENT_SCHEMA_VERSION)
        root.put("profileName", profileName)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))

        val arr = JSONArray()
        for (btn in buttons) {
            val obj = JSONObject().apply {
                put("id", btn.id)
                put("label", btn.label)
                put("normX", btn.normX.toDouble())
                put("normY", btn.normY.toDouble())
                put("radiusDp", btn.radiusDp.toDouble())
                put("opacity", btn.opacity.toDouble())
                put("actionType", btn.actionType.name)
                put("keyCodeOrButton", btn.keyCodeOrButton)
                put("isToggle", btn.isToggle)
                put("isVisible", btn.isVisible)
                put("accentHex", btn.accentHex)
                put("shape", btn.shape.name)
            }
            arr.put(obj)
        }
        root.put("buttons", arr)
        return root.toString(2)
    }

    fun saveLayout(context: Context, buttons: List<HudButtonConfig>) {
        val jsonStr = serializeLayoutToJson(buttons)
        File(context.filesDir, PROFILE_FILENAME).writeText(jsonStr)
    }

    fun resetLayout(context: Context): MutableList<HudButtonConfig> {
        File(context.filesDir, PROFILE_FILENAME).delete()
        return createDefaultProPvpLayout().also { saveLayout(context, it) }
    }

    /**
     * Exports current HUD layout as a `.json` file inside
     * `Android/media/id.xyverse.minecraft/controls/<name>.json`, copies JSON to clipboard,
     * and optionally launches Android's native Share Sheet (`ACTION_SEND`).
     */
    fun exportAndShareLayoutJson(
        context: Context,
        buttons: List<HudButtonConfig>,
        customName: String = "xyverse_pro_hud",
        openShareSheet: Boolean = true
    ): String {
        val safeSlug = customName.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "_")
            .trim('_')
            .ifBlank { "xyverse_controls" }
        val fileName = if (safeSlug.endsWith(".json")) safeSlug else "$safeSlug.json"
        val outDir = getExternalControlsDir(context)
        val outFile = File(outDir, fileName)

        val jsonText = serializeLayoutToJson(buttons, safeSlug)
        outFile.writeText(jsonText)

        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("XyverseControlsJson", jsonText))
        } catch (_: Exception) {
        }

        lastShareStatusMessage = "Diekspor ke: ${outFile.absolutePath} (& disalin ke Clipboard)"
        VeloLogger.info("CONTROLS-JSON", "Control profile exported to ${outFile.absolutePath}")

        if (openShareSheet) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_SUBJECT, "Profil Kontrol Minecraft ($fileName)")
                    putExtra(Intent.EXTRA_TEXT, jsonText)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(shareIntent, "Bagikan Profil Kontrol (.json)").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                VeloLogger.warn("CONTROLS-JSON", "Share sheet fallback to text: ${e.message}")
            }
        }

        return outFile.absolutePath
    }

    fun listSavedExternalJsonProfiles(context: Context): List<File> {
        val dir = getExternalControlsDir(context)
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun importLayoutFromClipboard(context: Context): Pair<Boolean, String> {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) {
                return false to "Clipboard kosong. Salin teks .json kontrol terlebih dahulu."
            }
            val raw = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
            val parsed = parseLayoutFromJson(raw)
                ?: return false to "Format teks di Clipboard bukan JSON kontrol yang valid."
            saveLayout(context, parsed)
            val autoSavedFile = File(getExternalControlsDir(context), "imported_clipboard.json")
            autoSavedFile.writeText(serializeLayoutToJson(parsed, "Imported Clipboard"))
            lastShareStatusMessage = "Berhasil impor ${parsed.size} tombol dari Clipboard (.json)!"
            true to lastShareStatusMessage
        } catch (e: Exception) {
            false to "Gagal impor dari Clipboard: ${e.message}"
        }
    }

    fun importLayoutFromFile(context: Context, file: File): Pair<Boolean, String> {
        return try {
            if (!file.exists()) return false to "File ${file.name} tidak ditemukan."
            val parsed = parseLayoutFromJson(file.readText())
                ?: return false to "File ${file.name} bukan format JSON kontrol yang valid."
            saveLayout(context, parsed)
            lastShareStatusMessage = "Profil '${file.name}' (${parsed.size} tombol) berhasil dimuat!"
            true to lastShareStatusMessage
        } catch (e: Exception) {
            false to "Gagal membaca ${file.name}: ${e.message}"
        }
    }

    fun createCompetitivePvpLayout(): MutableList<HudButtonConfig> {
        val base = createDefaultProPvpLayout()
        base.find { it.id == "btn_attack" }?.apply {
            normX = 0.78f
            normY = 0.30f
            radiusDp = 25f
        }
        base.find { it.id == "btn_jump" }?.apply {
            normX = 0.91f
            normY = 0.30f
            radiusDp = 24f
        }
        base.find { it.id == "btn_place" }?.apply {
            normX = 0.85f
            normY = 0.74f
            radiusDp = 24f
        }
        base.find { it.id == "btn_combo" }?.apply {
            normX = 0.74f
            normY = 0.74f
            radiusDp = 20f
        }
        return base
    }

    fun createBuilderMinimalLayout(): MutableList<HudButtonConfig> {
        val base = createDefaultProPvpLayout()
        base.forEach { btn ->
            btn.radiusDp = (btn.radiusDp * 0.90f).coerceAtLeast(15f)
            btn.opacity = (btn.opacity * 0.85f).coerceIn(0.15f, 1.0f)
        }
        return base
    }

    /**
     * Applies one of 3 built-in ergonomic presets:
     * 0 = Standard Pro (Balanced 2-Thumb 3x3 D-Pad + Right Arc)
     * 1 = 4-Finger Claw PvP (Attack & Jump raised for index fingers)
     * 2 = Compact Builder / Survival (Slightly smaller buttons for maximum field of view)
     */
    fun applyPreset(context: Context, presetIndex: Int): MutableList<HudButtonConfig> {
        val base = when (presetIndex) {
            1 -> createCompetitivePvpLayout()
            2 -> createBuilderMinimalLayout()
            else -> createDefaultProPvpLayout()
        }
        saveLayout(context, base)
        return base
    }
}
