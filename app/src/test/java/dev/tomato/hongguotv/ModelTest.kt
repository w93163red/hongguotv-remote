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
        hasTabBar = true,
    )
    private val player = feed.copy(activity = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity", hasTabBar = false)
    private val theater = feed.copy(hasFullHeightVerticalPager = false)

    @Test fun homeFeedIsFeedAndDramaIsPlayer() {
        assertEquals(Mode.FEED, classify(feed, Override.None))
        assertEquals(Mode.PLAYER, classify(player, Override.None))
        // Without a tab bar to select, the feed's LEFT/RIGHT/BACK must reach the app.
        assertEquals(Mode.PLAYER, classify(feed.copy(hasTabBar = false), Override.None))
    }

    @Test fun forcedCursorWinsOverVideo() {
        assertEquals(Mode.CURSOR, classify(player, Override.Cursor))
        assertEquals(Mode.CURSOR, classify(feed, Override.Cursor))
    }

    @Test fun tabsNeedAVisibleUncoveredTabBar() {
        assertEquals(Mode.TABS, classify(feed, Override.Tabs(1)))
        assertEquals(Mode.TABS, classify(theater, Override.Tabs(4)))
        assertEquals(Mode.PLAYER, classify(player, Override.Tabs(0)))
        assertEquals(Mode.CURSOR, classify(feed.copy(hasOverlaySheet = true), Override.Tabs(0)))
    }

    @Test fun overlayOrMissingPagerIsCursor() {
        assertEquals(Mode.CURSOR, classify(player.copy(hasOverlaySheet = true), Override.None))
        assertEquals(Mode.CURSOR, classify(theater, Override.None))
    }

    @Test fun otherActivityWithPagerIsCursor() {
        val search = feed.copy(activity = "com.dragon.read.component.biz.impl.HongguoSearchActivity")
        assertEquals(Mode.CURSOR, classify(search, Override.None))
        assertEquals(Mode.CURSOR, classify(feed.copy(activity = null), Override.None))
    }

    @Test fun otherAppOrKeyboardIsPass() {
        val launcher = feed.copy(pkg = "com.google.android.apps.tv.launcherx")
        assertEquals(Mode.PASS, classify(launcher, Override.None))
        assertEquals(Mode.PASS, classify(launcher, Override.Cursor))
        assertEquals(Mode.PASS, classify(launcher, Override.Tabs(0)))
        assertEquals(Mode.PASS, classify(feed.copy(imeVisible = true), Override.None))
        assertEquals(Mode.PASS, classify(feed.copy(imeVisible = true), Override.Cursor))
    }

    @Test fun feedKeys() {
        assertEquals(Action.Flip(next = false), actionFor(Mode.FEED, KeyPress(KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals(Action.Flip(next = true), actionFor(Mode.FEED, KeyPress(KeyEvent.KEYCODE_DPAD_DOWN)))
        assertEquals(Action.SelectTab(-1), actionFor(Mode.FEED, KeyPress(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(Action.SelectTab(1), actionFor(Mode.FEED, KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(Action.SelectTab(0), actionFor(Mode.FEED, KeyPress(KeyEvent.KEYCODE_BACK)))
        assertEquals(Action.ForceCursor(on = true), actionFor(Mode.FEED, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertEquals(Action.Pass, actionFor(Mode.FEED, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER, long = true)))
        assertEquals(Action.Pass, actionFor(Mode.FEED, KeyPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    }

    @Test fun playerKeys() {
        assertEquals(Action.Flip(next = false), actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals(Action.Flip(next = true), actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_DOWN)))
        assertEquals(Action.ForceCursor(on = true), actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertEquals(Action.Pass, actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(Action.Pass, actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(Action.Pass, actionFor(Mode.PLAYER, KeyPress(KeyEvent.KEYCODE_BACK)))
    }

    @Test fun tabKeys() {
        assertEquals(Action.SelectTab(-1), actionFor(Mode.TABS, KeyPress(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(Action.SelectTab(1), actionFor(Mode.TABS, KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(Action.OpenTab, actionFor(Mode.TABS, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertEquals(Action.LeaveTabs, actionFor(Mode.TABS, KeyPress(KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals(Action.LeaveTabs, actionFor(Mode.TABS, KeyPress(KeyEvent.KEYCODE_DPAD_DOWN)))
        assertEquals(Action.Pass, actionFor(Mode.TABS, KeyPress(KeyEvent.KEYCODE_BACK)))
    }

    @Test fun cursorKeys() {
        assertEquals(Action.MoveCursor(0, -1), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_UP)))
        assertEquals(Action.MoveCursor(0, 1), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_DOWN)))
        assertEquals(Action.MoveCursor(-1, 0), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_LEFT)))
        assertEquals(Action.MoveCursor(1, 0), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(Action.CursorClick, actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertEquals(Action.ForceCursor(on = false), actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_DPAD_CENTER, long = true)))
        assertEquals(Action.Pass, actionFor(Mode.CURSOR, KeyPress(KeyEvent.KEYCODE_BACK)))
    }

    @Test fun passModeConsumesNothing() {
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK)) {
            assertEquals(Action.Pass, actionFor(Mode.PASS, KeyPress(code)))
            assertEquals(Action.Pass, actionFor(Mode.PASS, KeyPress(code, long = true)))
        }
    }
}
