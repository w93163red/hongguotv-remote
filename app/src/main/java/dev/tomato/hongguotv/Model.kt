package dev.tomato.hongguotv

import android.view.KeyEvent

const val TARGET_PACKAGE = "com.phoenix.read"
const val HOME_ACTIVITY = "com.dragon.read.pages.main.MainFragmentActivity"
const val PLAYER_ACTIVITY = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity"

data class ScreenSnapshot(
    val pkg: String,
    val activity: String?,
    val imeVisible: Boolean,
    val hasFullHeightVerticalPager: Boolean,
    val hasOverlaySheet: Boolean,
)

enum class Mode { PLAYER, CURSOR, PASS }

fun classify(s: ScreenSnapshot, cursorForced: Boolean): Mode = when {
    s.pkg != TARGET_PACKAGE || s.imeVisible -> Mode.PASS
    cursorForced -> Mode.CURSOR
    (s.activity == HOME_ACTIVITY || s.activity == PLAYER_ACTIVITY) &&
        s.hasFullHeightVerticalPager && !s.hasOverlaySheet -> Mode.PLAYER
    else -> Mode.CURSOR
}

sealed interface Action {
    data class SwipeVertical(val next: Boolean) : Action
    data object TapCenter : Action
    data class Seek(val forward: Boolean) : Action
    data class MoveCursor(val dx: Int, val dy: Int) : Action
    data object CursorClick : Action
    data class ForceCursor(val on: Boolean) : Action
    data object Pass : Action
}

data class KeyPress(val keyCode: Int, val long: Boolean = false)

private val dpadMoves = mapOf(
    KeyPress(KeyEvent.KEYCODE_DPAD_UP) to Action.MoveCursor(0, -1),
    KeyPress(KeyEvent.KEYCODE_DPAD_DOWN) to Action.MoveCursor(0, 1),
    KeyPress(KeyEvent.KEYCODE_DPAD_LEFT) to Action.MoveCursor(-1, 0),
    KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT) to Action.MoveCursor(1, 0),
)

val KEYMAP: Map<Mode, Map<KeyPress, Action>> = mapOf(
    Mode.PLAYER to mapOf(
        KeyPress(KeyEvent.KEYCODE_DPAD_UP) to Action.SwipeVertical(next = false),
        KeyPress(KeyEvent.KEYCODE_DPAD_DOWN) to Action.SwipeVertical(next = true),
        KeyPress(KeyEvent.KEYCODE_DPAD_LEFT) to Action.Seek(forward = false),
        KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT) to Action.Seek(forward = true),
        KeyPress(KeyEvent.KEYCODE_DPAD_CENTER) to Action.TapCenter,
        KeyPress(KeyEvent.KEYCODE_DPAD_CENTER, long = true) to Action.ForceCursor(on = true),
        KeyPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) to Action.TapCenter,
        KeyPress(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) to Action.Seek(forward = true),
        KeyPress(KeyEvent.KEYCODE_MEDIA_REWIND) to Action.Seek(forward = false),
    ),
    Mode.CURSOR to dpadMoves + mapOf(
        KeyPress(KeyEvent.KEYCODE_DPAD_CENTER) to Action.CursorClick,
        KeyPress(KeyEvent.KEYCODE_DPAD_CENTER, long = true) to Action.ForceCursor(on = false),
    ),
    Mode.PASS to emptyMap(),
)

fun actionFor(mode: Mode, press: KeyPress): Action = KEYMAP.getValue(mode)[press] ?: Action.Pass
