package dev.tomato.hongguotv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTest {
    private val feed = ScreenSnapshot(
        pkg = "com.phoenix.read",
        activity = "com.dragon.read.pages.main.MainFragmentActivity",
        imeVisible = false,
        hasFullHeightVerticalPager = true,
        hasOverlaySheet = false,
    )
    private val player = feed.copy(activity = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity")

    @Test fun feedAndPlayerArePlayer() {
        assertEquals(Mode.PLAYER, classify(feed, cursorForced = false))
        assertEquals(Mode.PLAYER, classify(player, cursorForced = false))
    }

    @Test fun forcedCursorWinsOverPlayer() {
        assertEquals(Mode.CURSOR, classify(player, cursorForced = true))
    }

    @Test fun overlayOrMissingPagerIsCursor() {
        assertEquals(Mode.CURSOR, classify(player.copy(hasOverlaySheet = true), cursorForced = false))
        assertEquals(Mode.CURSOR, classify(feed.copy(hasFullHeightVerticalPager = false), cursorForced = false))
    }

    @Test fun otherActivityWithPagerIsCursor() {
        val search = feed.copy(activity = "com.dragon.read.component.biz.impl.HongguoSearchActivity")
        assertEquals(Mode.CURSOR, classify(search, cursorForced = false))
        assertEquals(Mode.CURSOR, classify(feed.copy(activity = null), cursorForced = false))
    }

    @Test fun otherAppOrKeyboardIsPass() {
        assertEquals(Mode.PASS, classify(feed.copy(pkg = "com.google.android.apps.tv.launcherx"), cursorForced = false))
        assertEquals(Mode.PASS, classify(feed.copy(pkg = "com.google.android.apps.tv.launcherx"), cursorForced = true))
        assertEquals(Mode.PASS, classify(feed.copy(imeVisible = true), cursorForced = false))
        assertEquals(Mode.PASS, classify(feed.copy(imeVisible = true), cursorForced = true))
    }

    @Test fun playerKeys() {
        assertEquals(Action.Flip(next = false), actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals(Action.Flip(next = true), actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_DOWN)))
        assertEquals(Action.ForceCursor(on = true), actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertEquals(Action.Pass, actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER, long = true)))
        assertEquals(Action.Pass, actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(Action.Pass, actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(Action.Pass, actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertEquals(Action.Pass, actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_BACK)))
    }

    @Test fun cursorKeys() {
        assertEquals(Action.MoveCursor(0, -1), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals(Action.MoveCursor(0, 1), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_DOWN)))
        assertEquals(Action.MoveCursor(-1, 0), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(Action.MoveCursor(1, 0), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(Action.CursorClick, actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertEquals(Action.ForceCursor(on = false), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER, long = true)))
        assertEquals(Action.Pass, actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_BACK)))
        assertEquals(Action.Pass, actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    }

    @Test fun passModeConsumesNothing() {
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK)) {
            assertEquals(Action.Pass, actionFor(Mode.PASS, KeyPress(code)))
            assertEquals(Action.Pass, actionFor(Mode.PASS, KeyPress(code, long = true)))
        }
    }
}
