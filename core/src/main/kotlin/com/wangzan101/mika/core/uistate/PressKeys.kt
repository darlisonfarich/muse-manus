package com.wangzan101.mika.core.uistate

/** Key allowlist with the destination scope encoded in each definition. */
object PressKeys {
    enum class Scope { AnyDisplay, VirtualDisplay }
    data class Definition(val code: Int, val scope: Scope = Scope.AnyDisplay)

    val DEFINITIONS: Map<String, Definition> = linkedMapOf(
        "KEYCODE_DPAD_UP" to Definition(19), "KEYCODE_DPAD_DOWN" to Definition(20), "KEYCODE_DPAD_LEFT" to Definition(21), "KEYCODE_DPAD_RIGHT" to Definition(22),
        "KEYCODE_DPAD_CENTER" to Definition(23), "KEYCODE_ENTER" to Definition(66), "KEYCODE_NUMPAD_ENTER" to Definition(160), "KEYCODE_DEL" to Definition(67),
        "KEYCODE_FORWARD_DEL" to Definition(112), "KEYCODE_TAB" to Definition(61), "KEYCODE_SPACE" to Definition(62), "KEYCODE_ESCAPE" to Definition(111),
        "KEYCODE_PAGE_UP" to Definition(92), "KEYCODE_PAGE_DOWN" to Definition(93), "KEYCODE_MOVE_HOME" to Definition(122), "KEYCODE_MOVE_END" to Definition(123),
        "KEYCODE_BACK" to Definition(4), "KEYCODE_MENU" to Definition(82),
        "KEYCODE_HOME" to Definition(3, Scope.VirtualDisplay), "KEYCODE_APP_SWITCH" to Definition(187, Scope.VirtualDisplay),
        "KEYCODE_VOLUME_UP" to Definition(24, Scope.VirtualDisplay), "KEYCODE_VOLUME_DOWN" to Definition(25, Scope.VirtualDisplay),
        "KEYCODE_VOLUME_MUTE" to Definition(164, Scope.VirtualDisplay),
        "KEYCODE_MEDIA_PLAY_PAUSE" to Definition(85, Scope.VirtualDisplay), "KEYCODE_MEDIA_STOP" to Definition(86, Scope.VirtualDisplay),
        "KEYCODE_MEDIA_NEXT" to Definition(87, Scope.VirtualDisplay), "KEYCODE_MEDIA_PREVIOUS" to Definition(88, Scope.VirtualDisplay),
    )
    val BY_NAME: Map<String, Int> = DEFINITIONS.mapValues { it.value.code }
    private val BY_CODE = DEFINITIONS.values.associateBy { it.code }

    fun isAllowed(keyCode: Int): Boolean = keyCode in BY_CODE
    fun isAllowed(keyCode: Int, scope: Scope): Boolean = BY_CODE[keyCode]?.let {
        it.scope == Scope.AnyDisplay || scope == Scope.VirtualDisplay
    } == true

    /** Name or number, checked against the caller's encoded display scope. */
    fun resolve(key: String, scope: Scope = Scope.AnyDisplay): Int? {
        val k = key.trim()
        val code = k.toIntOrNull() ?: BY_NAME[k] ?: return null
        return code.takeIf { isAllowed(it, scope) }
    }
}
