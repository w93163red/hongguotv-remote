package dev.tomato.hongguotv

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "HongguoTV"
private const val LONG_PRESS_MS = 500L
private const val CURSOR_REPEAT_DELAY_MS = 250L
private const val CURSOR_TICK_MS = 16L
private const val EDGE_SCROLL_GAP_MS = 350L

/**
 * Every action is an accessibility node action. Touch injection is not an option: the platform cancels each
 * injected gesture on devices without a touchscreen or faketouch feature, and the Google TV Streamer has neither.
 *
 * Window reads take up to ~440 ms on the Streamer, close to the 500 ms after which the framework hands an
 * unanswered key to the app as well. So keys are decided on the main thread from the last screen read, and every
 * window read and node action runs on one worker thread, first in first out: a slow read can delay what queues
 * behind it but never reorder it, and never holds up a key decision or a long-press timer.
 */
class RemoteService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var cursor: CursorOverlay
    private var override: Override = Override.None
    private var screen = Screen(
        ScreenSnapshot("", null, imeVisible = false, hasFullHeightVerticalPager = false, hasOverlaySheet = false, hasTabBar = false),
        Rect(), pagerNode = null, tabs = emptyList(), selectedTab = 0,
    )
    private var mode = Mode.PASS
    private var lastEdgeScroll = 0L
    // At most one read and one edge scroll wait in the worker queue, so held keys cannot build a backlog.
    private val readQueued = AtomicBoolean(false)
    private val edgeScrollQueued = AtomicBoolean(false)

    /** A key we consumed on ACTION_DOWN; its ACTION_UP must be consumed too. */
    private inner class Press(val short: Action, val long: Action, val downAt: Long) {
        var longFired = false
        val fireLong = Runnable { longFired = true; execute(long) }
        val repeat = object : Runnable {
            override fun run() {
                moveCursor(short as Action.MoveCursor, holdStep(SystemClock.uptimeMillis() - downAt, screen.content))
                handler.postDelayed(this, CURSOR_TICK_MS)
            }
        }
    }
    private val presses = mutableMapOf<Int, Press>()

    private var refreshAt = 0L
    private val refreshRunnable = Runnable { refreshAt = 0; refresh() }

    private companion object {
        // Process-wide so a rebind of the service (settings toggle, UiAutomation) keeps the last known activity.
        @Volatile var activity: String? = null
        val isActivity = mutableMapOf<String, Boolean>()
    }

    override fun onServiceConnected() {
        cursor = CursorOverlay(this)
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName == TARGET_PACKAGE) {
            val cls = event.className?.toString()
            if (cls != null && isActivity.getOrPut(cls) { resolvesToActivity(cls) }) activity = cls
        }
        // Another app came up: stop taking keys now rather than after the read that confirms it. Our own overlay
        // window reports state changes too, and must not count.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName != TARGET_PACKAGE &&
            event.packageName != packageName
        ) {
            mode = Mode.PASS
            cursor.hide()
        }
        // Other apps' content changes (launcher animations, other players) need no read.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.packageName != TARGET_PACKAGE) return
        // Content changes also announce in-window changes such as switching bottom tabs, but arrive several times
        // a second during playback, so they are batched into at most about one read a second.
        val delay = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) 1000L else 250L
        val at = SystemClock.uptimeMillis() + delay
        if (refreshAt == 0L || at < refreshAt) {
            handler.removeCallbacks(refreshRunnable)
            refreshAt = at
            handler.postAtTime(refreshRunnable, at)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        if (::cursor.isInitialized) cursor.hide()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                presses[code]?.let { p ->
                    // Real remotes deliver no repeats here; `input keyevent --longpress` style injections do.
                    if (!p.longFired && p.long != Action.Pass && event.eventTime - event.downTime >= LONG_PRESS_MS) {
                        handler.removeCallbacks(p.fireLong)
                        p.fireLong.run()
                    }
                    return true
                }
                if (event.repeatCount > 0 || mode == Mode.PASS) return false
                val short = actionFor(mode, KeyPress(code))
                val long = actionFor(mode, KeyPress(code, long = true))
                Log.d(TAG, "key ${KeyEvent.keyCodeToString(code)} mode=$mode short=$short long=$long ${screen.snapshot}")
                if (short == Action.Pass && long == Action.Pass) {
                    scheduleRefresh(400)
                    return false
                }
                // Timers run from the key's own down time, so a busy main thread does not eat into a hold.
                val p = Press(short, long, event.downTime)
                presses[code] = p
                if (long != Action.Pass) {
                    handler.postAtTime(p.fireLong, p.downAt + LONG_PRESS_MS)
                } else {
                    execute(short)
                    if (short is Action.MoveCursor) handler.postAtTime(p.repeat, p.downAt + CURSOR_REPEAT_DELAY_MS)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                val p = presses.remove(code) ?: return false
                handler.removeCallbacks(p.fireLong)
                handler.removeCallbacks(p.repeat)
                if (p.long != Action.Pass && !p.longFired) {
                    if (event.eventTime - event.downTime >= LONG_PRESS_MS) p.fireLong.run() else execute(p.short)
                }
                return true
            }
        }
        return false
    }

    /** Queues a window read; the result is applied on the main thread. */
    private fun refresh() {
        if (readQueued.getAndSet(true)) return
        worker.execute {
            readQueued.set(false)
            val t0 = SystemClock.uptimeMillis()
            val read = readScreen(windows, activity)
            Log.d(TAG, "read ${SystemClock.uptimeMillis() - t0}ms ${read.snapshot}")
            handler.post { if (!worker.isShutdown) apply(read) }
        }
    }

    private fun apply(read: Screen) {
        screen = read
        // A forced cursor is scoped to the video screen it was forced on, the tab bar to screens that show one.
        val own = classify(read.snapshot, Override.None)
        override = when (val o = override) {
            Override.Cursor -> if (own == Mode.FEED || own == Mode.PLAYER) o else Override.None
            is Override.Tabs -> if (classify(read.snapshot, o) == Mode.TABS) o.copy(index = o.index.coerceIn(read.tabs.indices)) else Override.None
            Override.None -> o
        }
        mode = classify(read.snapshot, override)
        when (mode) {
            Mode.CURSOR -> cursor.show(read.content)
            Mode.TABS -> cursor.frame(read.tabs[(override as Override.Tabs).index].bounds)
            else -> cursor.hide()
        }
    }

    private fun scheduleRefresh(delayMs: Long) {
        handler.postDelayed({ refresh() }, delayMs)
    }

    private fun execute(action: Action) {
        when (action) {
            is Action.Flip -> screen.pagerNode?.let { pager ->
                worker.execute { pager.performAction(scroll(action.next).id) }
                settle()
            }
            is Action.MoveCursor -> moveCursor(action, (screen.content.height() * 0.03).toInt())
            Action.CursorClick -> {
                val p = Point(cursor.position)
                worker.execute {
                    val target = nodeAt(windows, p.x, p.y) { it.isClickable }
                    Log.d(TAG, "click at $p on ${target?.className}")
                    target?.performAction(AccessibilityAction.ACTION_CLICK.id)
                }
                settle()
            }
            is Action.ForceCursor -> {
                override = if (action.on) Override.Cursor else Override.None
                apply(screen)
            }
            is Action.SelectTab -> {
                if (screen.tabs.isEmpty()) return
                val from = (override as? Override.Tabs)?.index ?: screen.selectedTab
                override = Override.Tabs((from + action.step).coerceIn(screen.tabs.indices))
                apply(screen)
            }
            Action.OpenTab -> {
                val tab = (override as? Override.Tabs)?.let { screen.tabs.getOrNull(it.index) } ?: return
                worker.execute { tab.node.performAction(AccessibilityAction.ACTION_CLICK.id) }
                override = Override.None
                apply(screen)
                settle()
            }
            Action.LeaveTabs -> {
                override = Override.None
                apply(screen)
            }
            Action.Pass -> Unit
        }
    }

    /** Re-reads the screen while a click's result (new page, sheet, popup) is still arriving. */
    private fun settle() {
        scheduleRefresh(400)
        scheduleRefresh(1200)
    }

    private fun holdStep(heldMs: Long, content: Rect): Int {
        val ramp = ((heldMs - CURSOR_REPEAT_DELAY_MS).coerceAtLeast(0) / 700f).coerceAtMost(1f)
        val heightsPerSecond = 0.5f + 1.1f * ramp
        return (content.height() * heightsPerSecond * CURSOR_TICK_MS / 1000f).toInt().coerceAtLeast(1)
    }

    private fun moveCursor(action: Action.MoveCursor, step: Int) {
        if (cursor.moveBy(action.dx * step, action.dy * step)) return
        // Pinned against an edge: scroll the list under the pointer instead, at most once per gap while held.
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeScroll < EDGE_SCROLL_GAP_MS || edgeScrollQueued.getAndSet(true)) return
        lastEdgeScroll = now
        val c = screen.content
        val p = Point(cursor.position)
        val vertical = action.dy != 0
        val forward = action.dx + action.dy > 0
        worker.execute {
            edgeScrollQueued.set(false)
            val wants = scrolls(vertical, c)
            // A pointer pinned to the top or bottom usually sits on a title or tab bar, so the column's middle is tried too.
            val target = nodeAt(windows, p.x, p.y, wants) ?: if (vertical) nodeAt(windows, p.x, c.centerY(), wants) else null
            Log.d(TAG, "edge scroll ${if (forward) "forward" else "back"} on ${target?.className}")
            target?.performAction(scroll(forward).id)
        }
    }

    private fun scroll(forward: Boolean) =
        if (forward) AccessibilityAction.ACTION_SCROLL_FORWARD else AccessibilityAction.ACTION_SCROLL_BACKWARD

    private fun resolvesToActivity(cls: String): Boolean = try {
        packageManager.getActivityInfo(ComponentName(TARGET_PACKAGE, cls), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
