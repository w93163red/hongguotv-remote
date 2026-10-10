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
    val hasTabBar: Boolean,
)

/** FEED is the 首页 video feed with the bottom tab bar under it; PLAYER is any other full-screen video pager. */
enum class Mode { FEED, PLAYER, TABS, CURSOR, PASS }

/** What the user has switched to on top of the screen's own mode. */
sealed interface Override {
    data object None : Override
    data object Cursor : Override
    data class Tabs(val index: Int) : Override
}

fun classify(s: ScreenSnapshot, override: Override): Mode = when {
    s.pkg != TARGET_PACKAGE || s.imeVisible -> Mode.PASS
    override is Override.Tabs && s.hasTabBar && !s.hasOverlaySheet -> Mode.TABS
    override == Override.Cursor -> Mode.CURSOR
    !s.hasFullHeightVerticalPager || s.hasOverlaySheet -> Mode.CURSOR
    s.activity == HOME_ACTIVITY && s.hasTabBar -> Mode.FEED
    s.activity == HOME_ACTIVITY || s.activity == PLAYER_ACTIVITY -> Mode.PLAYER
    else -> Mode.CURSOR
}

sealed interface Action {
    data class Flip(val next: Boolean) : Action
    data class MoveCursor(val dx: Int, val dy: Int) : Action
    data object CursorClick : Action
    data class ForceCursor(val on: Boolean) : Action
    /** Selects the bottom tab [step] places from the current one, entering the tab bar first if needed. */
    data class SelectTab(val step: Int) : Action
    data object OpenTab : Action
    data object LeaveTabs : Action
    data object Pass : Action
}

data class KeyPress(val keyCode: Int, val long: Boolean = false)

private val flips = mapOf(
    KeyPress(KeyEvent.KEYCODE_DPAD_UP) to Action.Flip(next = false),
    KeyPress(KeyEvent.KEYCODE_DPAD_DOWN) to Action.Flip(next = true),
    KeyPress(KeyEvent.KEYCODE_DPAD_CENTER) to Action.ForceCursor(on = true),
)

val KEYMAP: Map<Mode, Map<KeyPress, Action>> = mapOf(
    Mode.FEED to flips + mapOf(
        KeyPress(KeyEvent.KEYCODE_DPAD_LEFT) to Action.SelectTab(-1),
        KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT) to Action.SelectTab(1),
        KeyPress(KeyEvent.KEYCODE_BACK) to Action.SelectTab(0),
    ),
    Mode.PLAYER to flips,
    Mode.TABS to mapOf(
        KeyPress(KeyEvent.KEYCODE_DPAD_LEFT) to Action.SelectTab(-1),
        KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT) to Action.SelectTab(1),
        KeyPress(KeyEvent.KEYCODE_DPAD_CENTER) to Action.OpenTab,
        KeyPress(KeyEvent.KEYCODE_DPAD_UP) to Action.LeaveTabs,
        KeyPress(KeyEvent.KEYCODE_DPAD_DOWN) to Action.LeaveTabs,
    ),
    Mode.CURSOR to mapOf(
        KeyPress(KeyEvent.KEYCODE_DPAD_UP) to Action.MoveCursor(0, -1),
        KeyPress(KeyEvent.KEYCODE_DPAD_DOWN) to Action.MoveCursor(0, 1),
        KeyPress(KeyEvent.KEYCODE_DPAD_LEFT) to Action.MoveCursor(-1, 0),
        KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT) to Action.MoveCursor(1, 0),
        KeyPress(KeyEvent.KEYCODE_DPAD_CENTER) to Action.CursorClick,
        KeyPress(KeyEvent.KEYCODE_DPAD_CENTER, long = true) to Action.ForceCursor(on = false),
    ),
    Mode.PASS to emptyMap(),
)

fun actionFor(mode: Mode, press: KeyPress): Action = KEYMAP.getValue(mode)[press] ?: Action.Pass
