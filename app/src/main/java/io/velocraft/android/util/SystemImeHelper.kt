package io.velocraft.android.util

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import io.velocraft.android.bridge.NativeEngineBridge

/**
 * Native Android System IME Keyboard Bridge (`InputMethodManager`).
 *
 * Replaces any custom canvas on-screen keyboard with the real Android system keyboard
 * (Gboard / Xiaomi / Samsung IME) in two modes:
 * 1. `requestTextInput(...)`: Streams live edits into Launcher / Sidebar text fields
 *    (Account Username, Modrinth Search, Control JSON Profile Name).
 * 2. `openInGameDirectKeyboard(...)`: Streams characters (`NativeEngineBridge.sendChar`),
 *    Backspace (`259`), and Enter (`257`) directly into Minecraft 1.20.4 (LWJGL 3).
 */
object SystemImeHelper {

    private const val GLFW_KEY_ENTER = 257
    private const val GLFW_KEY_BACKSPACE = 259
    private const val SENTINEL_PREFIX = "\u200B\u200B"

    private var attachedActivity: Activity? = null
    private var hiddenEditText: EditText? = null
    private var suppressWatcher: Boolean = false

    private enum class Mode {
        NONE,
        UI_FIELD,
        IN_GAME_LWJGL
    }

    private var currentMode: Mode = Mode.NONE
    private var activeFieldLabel: String = ""
    private var onFieldChanged: ((String) -> Unit)? = null
    private var onFieldCommitted: ((String) -> Unit)? = null

    fun attachToRoot(activity: Activity, rootLayout: FrameLayout) {
        attachedActivity = activity
        if (hiddenEditText != null && hiddenEditText?.parent == rootLayout) {
            return
        }

        val edit = object : EditText(activity) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP) {
                    hideIme()
                    return false
                }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(2, 2, Gravity.TOP or Gravity.START).apply {
                leftMargin = -10
                topMargin = -10
            }
            alpha = 0.01f
            isFocusable = true
            isFocusableInTouchMode = true
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

            setOnEditorActionListener { _, actionId, event ->
                val isEnter = actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)

                if (isEnter) {
                    when (currentMode) {
                        Mode.UI_FIELD -> {
                            val finalVal = text?.toString() ?: ""
                            onFieldCommitted?.invoke(finalVal)
                            hideIme()
                        }
                        Mode.IN_GAME_LWJGL -> {
                            NativeEngineBridge.sendKey(GLFW_KEY_ENTER, NativeEngineBridge.GLFW_PRESS, 0)
                            NativeEngineBridge.sendKey(GLFW_KEY_ENTER, NativeEngineBridge.GLFW_RELEASE, 0)
                            hideIme()
                        }
                        Mode.NONE -> hideIme()
                    }
                    true
                } else {
                    false
                }
            }

            setOnKeyListener { _, keyCode, event ->
                if (currentMode == Mode.IN_GAME_LWJGL && event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DEL -> {
                            NativeEngineBridge.sendKey(GLFW_KEY_BACKSPACE, NativeEngineBridge.GLFW_PRESS, 0)
                            NativeEngineBridge.sendKey(GLFW_KEY_BACKSPACE, NativeEngineBridge.GLFW_RELEASE, 0)
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            NativeEngineBridge.sendKey(GLFW_KEY_ENTER, NativeEngineBridge.GLFW_PRESS, 0)
                            NativeEngineBridge.sendKey(GLFW_KEY_ENTER, NativeEngineBridge.GLFW_RELEASE, 0)
                            hideIme()
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (suppressWatcher) return
                    val currentStr = s?.toString() ?: ""

                    when (currentMode) {
                        Mode.UI_FIELD -> {
                            onFieldChanged?.invoke(currentStr)
                        }
                        Mode.IN_GAME_LWJGL -> {
                            if (currentStr.length < SENTINEL_PREFIX.length) {
                                // User pressed Backspace on soft keyboard
                                NativeEngineBridge.sendKey(GLFW_KEY_BACKSPACE, NativeEngineBridge.GLFW_PRESS, 0)
                                NativeEngineBridge.sendKey(GLFW_KEY_BACKSPACE, NativeEngineBridge.GLFW_RELEASE, 0)
                                resetInGameSentinel()
                            } else if (currentStr.length > SENTINEL_PREFIX.length) {
                                val added = currentStr.substring(SENTINEL_PREFIX.length)
                                var i = 0
                                while (i < added.length) {
                                    val cp = Character.codePointAt(added, i)
                                    if (cp == '\n'.code) {
                                        NativeEngineBridge.sendKey(GLFW_KEY_ENTER, NativeEngineBridge.GLFW_PRESS, 0)
                                        NativeEngineBridge.sendKey(GLFW_KEY_ENTER, NativeEngineBridge.GLFW_RELEASE, 0)
                                    } else if (cp >= 32) {
                                        NativeEngineBridge.sendChar(cp, 0)
                                    }
                                    i += Character.charCount(cp)
                                }
                                resetInGameSentinel()
                            }
                        }
                        Mode.NONE -> {}
                    }
                }

                override fun afterTextChanged(s: Editable?) {}
            })
        }

        rootLayout.addView(edit)
        hiddenEditText = edit
    }

    private fun resetInGameSentinel() {
        val edit = hiddenEditText ?: return
        suppressWatcher = true
        edit.setText(SENTINEL_PREFIX)
        edit.setSelection(SENTINEL_PREFIX.length)
        suppressWatcher = false
    }

    /**
     * Opens the native Android System IME Keyboard to edit a UI text field
     * (e.g., Account Username, Modrinth Search, Control Profile Name).
     */
    fun requestTextInput(
        label: String,
        initialText: String,
        maxLength: Int = 64,
        onChanged: (String) -> Unit,
        onCommitted: ((String) -> Unit)? = null
    ) {
        val activity = attachedActivity ?: return
        val edit = hiddenEditText ?: return

        activity.runOnUiThread {
            currentMode = Mode.UI_FIELD
            activeFieldLabel = label
            onFieldChanged = { raw ->
                val clipped = if (raw.length > maxLength) raw.take(maxLength) else raw
                onChanged(clipped)
            }
            onFieldCommitted = { raw ->
                val clipped = if (raw.length > maxLength) raw.take(maxLength) else raw
                onCommitted?.invoke(clipped)
            }

            suppressWatcher = true
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            edit.imeOptions = EditorInfo.IME_ACTION_DONE or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            edit.setText(initialText)
            edit.setSelection(initialText.length)
            suppressWatcher = false

            edit.visibility = View.VISIBLE
            edit.requestFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_FORCED)
            VeloLogger.section("IME", "System IME Keyboard opened for field: $label")
        }
    }

    /**
     * Opens the native Android System IME Keyboard for direct Minecraft LWJGL 3 typing
     * (Chat, Commands, Signs, Anvil, Creative Search, World Name).
     */
    fun openInGameDirectKeyboard(openChatFirst: Boolean = false) {
        val activity = attachedActivity ?: return
        val edit = hiddenEditText ?: return

        if (openChatFirst && NativeEngineBridge.isCursorGrabbed()) {
            NativeEngineBridge.sendKey(NativeEngineBridge.KEY_T, NativeEngineBridge.GLFW_PRESS, 0)
            NativeEngineBridge.sendKey(NativeEngineBridge.KEY_T, NativeEngineBridge.GLFW_RELEASE, 0)
        }

        activity.runOnUiThread {
            currentMode = Mode.IN_GAME_LWJGL
            activeFieldLabel = "Minecraft Input"
            onFieldChanged = null
            onFieldCommitted = null

            edit.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            edit.imeOptions = EditorInfo.IME_ACTION_SEND or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            resetInGameSentinel()

            edit.visibility = View.VISIBLE
            edit.requestFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_FORCED)
            VeloLogger.section("IME", "System IME Keyboard opened for direct Minecraft input.")
        }
    }

    fun hideIme() {
        val activity = attachedActivity ?: return
        val edit = hiddenEditText ?: return
        activity.runOnUiThread {
            currentMode = Mode.NONE
            onFieldChanged = null
            onFieldCommitted = null
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(edit.windowToken, 0)
            edit.clearFocus()
        }
    }

    fun isImeActive(): Boolean = currentMode != Mode.NONE
    fun hideKeyboard() = hideIme()
    fun isEditingUiField(): Boolean = currentMode == Mode.UI_FIELD
    fun getActiveFieldLabel(): String = activeFieldLabel
}
