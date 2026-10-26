package com.inputleaf.android.inject

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast

class InputLeafIME : InputMethodService() {

    private val lock = Any()
    private val hangul = HangulComposer()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Korean (dubeolsik) mode. Toggle with Shift+Space, a tap of Right Alt, or the language key.
    private var koreanMode = false

    // Key whose down event is pending as a possible "tap" (pressed and released with no other key).
    private var tapKey = KeyEvent.KEYCODE_UNKNOWN

    // Keys whose ACTION_DOWN we consumed; the matching ACTION_UP is consumed too.
    private val swallowed = HashSet<Int>()

    private var lastEditUptime = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "InputLeafIME onCreate")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "InputLeafIME onDestroy")
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        synchronized(lock) {
            hangul.reset()
            swallowed.clear()
            tapKey = KeyEvent.KEYCODE_UNKNOWN
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        synchronized(lock) { hangul.reset() }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        synchronized(lock) {
            // The app ended the composition (e.g. the cursor was moved with the mouse): start fresh.
            // Updates that arrive right after our own edit are ignored, they can be stale.
            if (hangul.isComposing &&
                candidatesStart < 0 && candidatesEnd < 0 &&
                SystemClock.uptimeMillis() - lastEditUptime > STALE_UPDATE_MS
            ) {
                hangul.reset()
            }
        }
    }

    // Hardware keyboard events (Shizuku / virtual HID keyboard path) reach the IME here.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (processKey(KeyEvent.ACTION_DOWN, keyCode, event.metaState)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (processKey(KeyEvent.ACTION_UP, keyCode, event.metaState)) return true
        return super.onKeyUp(keyCode, event)
    }

    // Events injected by the Accessibility path.
    fun injectKeyEvent(action: Int, keyCode: Int, metaState: Int) {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "injectKeyEvent: No InputConnection available")
            return
        }

        if (processKey(action, keyCode, metaState)) return

        val eventTime = SystemClock.uptimeMillis()
        val event = KeyEvent(
            eventTime, eventTime,
            action, keyCode, 0, metaState,
            -1, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        )

        ic.sendKeyEvent(event)
    }

    fun commitText(text: String) {
        val ic = currentInputConnection
        if (ic == null) {
            Log.w(TAG, "commitText: No InputConnection available")
            return
        }
        synchronized(lock) {
            // The text being composed is replaced by this commit, so include it.
            val pending = hangul.flush()
            ic.commitText(pending + text, 1)
            lastEditUptime = SystemClock.uptimeMillis()
        }
    }

    /** Returns true when the key was handled here and must not be passed on. */
    private fun processKey(action: Int, keyCode: Int, metaState: Int): Boolean =
        synchronized(lock) { processKeyLocked(action, keyCode, metaState) }

    private fun processKeyLocked(action: Int, keyCode: Int, metaState: Int): Boolean {
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false

        if (action == KeyEvent.ACTION_UP) {
            if (swallowed.remove(keyCode)) return true
            if (tapKey != KeyEvent.KEYCODE_UNKNOWN && keyCode == tapKey) {
                tapKey = KeyEvent.KEYCODE_UNKNOWN
                toggleKorean()
            }
            return false
        }

        // ACTION_DOWN
        if (keyCode == TAP_TOGGLE_KEY) {
            tapKey = keyCode
            return false
        }
        tapKey = KeyEvent.KEYCODE_UNKNOWN
        if (isModifier(keyCode)) return false

        val shift = (metaState and KeyEvent.META_SHIFT_ON) != 0
        val shortcut = (metaState and SHORTCUT_META) != 0

        if (!shortcut && (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH ||
                (keyCode == KeyEvent.KEYCODE_SPACE && shift))
        ) {
            toggleKorean()
            swallowed.add(keyCode)
            return true
        }

        if (koreanMode && !shortcut) {
            val ic = currentInputConnection
            val jamo = HangulKeyMap.jamoFor(keyCode, shift)
            if (jamo != null && ic != null) {
                applyResult(ic, hangul.input(jamo))
                swallowed.add(keyCode)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DEL && hangul.isComposing && ic != null) {
                hangul.backspace()?.let { applyResult(ic, it) }
                swallowed.add(keyCode)
                return true
            }
        }

        // Any other key ends the syllable being composed; it then goes through as usual.
        finishComposition()
        return false
    }

    private fun applyResult(ic: InputConnection, result: HangulComposer.Result) {
        ic.beginBatchEdit()
        if (result.committed.isNotEmpty()) {
            ic.commitText(result.committed, 1)
        }
        if (result.composing.isNotEmpty()) {
            ic.setComposingText(result.composing, 1)
        } else if (result.committed.isEmpty()) {
            // Backspace removed the last jamo: drop the composing text.
            ic.commitText("", 1)
        }
        ic.endBatchEdit()
        lastEditUptime = SystemClock.uptimeMillis()
    }

    private fun finishComposition() {
        if (!hangul.isComposing) return
        hangul.reset()
        currentInputConnection?.finishComposingText()
        lastEditUptime = SystemClock.uptimeMillis()
    }

    private fun toggleKorean() {
        finishComposition()
        koreanMode = !koreanMode
        val label = if (koreanMode) "한글" else "English"
        mainHandler.post {
            Toast.makeText(applicationContext, label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isModifier(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK,
        KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.KEYCODE_FUNCTION -> true
        else -> false
    }

    companion object {
        private const val TAG = "InputLeafIME"
        private const val STALE_UPDATE_MS = 300L
        private const val SHORTCUT_META =
            KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON

        // A tap of this key (press and release with nothing in between) toggles Korean / English.
        // (Not Right Shift: Shift+click would toggle it by accident.)
        private const val TAP_TOGGLE_KEY = KeyEvent.KEYCODE_ALT_RIGHT

        private var instance: InputLeafIME? = null

        fun getInstance(): InputLeafIME? {
            return instance
        }

        fun isRunning(): Boolean {
            return instance != null
        }
    }
}
