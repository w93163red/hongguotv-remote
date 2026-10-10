package dev.tomato.hongguotv

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

private const val TAG = "HongguoTV"
private const val LONG_PRESS_MS = 500L
private const val CURSOR_REPEAT_DELAY_MS = 250L
private const val CURSOR_TICK_MS = 16L
private const val EDGE_SCROLL_GAP_MS = 350L

/**
 * Every action is an accessibility node action. Touch injection is not an option: the platform cancels each
 * injected gesture on devices without a touchscreen or faketouch feature, and the Google TV Streamer has neither.
 */
class RemoteService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cursor: CursorOverlay
    private var cursorForced = false
    private var lastEdgeScroll = 0L

    /** A key we consumed on ACTION_DOWN; its ACTION_UP must be consumed too. */
    private inner class Press(val short: Action, val long: Action, val screen: Screen, val downAt: Long) {
        var longFired = false
        val fireLong = Runnable { longFired = true; execute(long, screen) }
        val repeat = object : Runnable {
            override fun run() {
                moveCursor(short as Action.MoveCursor, screen, holdStep(SystemClock.uptimeMillis() - downAt, screen.content))
                handler.postDelayed(this, CURSOR_TICK_MS)
            }
        }
    }
    private val presses = mutableMapOf<Int, Press>()

    private var foreground = ""
    private var refreshPending = false
    private val refreshRunnable = Runnable { refreshPending = false; refresh() }

    private companion object {
        // Process-wide so a rebind of the service (settings toggle, UiAutomation) keeps the last known activity.
        var activity: String? = null
        val isActivity = mutableMapOf<String, Boolean>()
    }

    override fun onServiceConnected() {
        cursor = CursorOverlay(this)
        handler.post { refresh() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName == TARGET_PACKAGE) {
            val cls = event.className?.toString()
            if (cls != null && isActivity.getOrPut(cls) { resolvesToActivity(cls) }) activity = cls
        }
        // Content changes are subscribed only so the node cache stays fresh; re-reading the screen on each of them
        // (several per second during playback) starved the main thread that runs key timers.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (!refreshPending) {
            refreshPending = true
            handler.postDelayed(refreshRunnable, 250)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
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
                // Other apps' keys pass without a window read, which cost ~100ms per key on the TV launcher.
                if (event.repeatCount > 0 || foreground != TARGET_PACKAGE) return false
                val t0 = SystemClock.uptimeMillis()
                val (screen, mode) = refresh()
                val short = actionFor(mode, KeyPress(code))
                val long = actionFor(mode, KeyPress(code, long = true))
                Log.d(TAG, "key ${KeyEvent.keyCodeToString(code)} mode=$mode short=$short long=$long read=${SystemClock.uptimeMillis() - t0}ms ${screen.snapshot}")
                if (short == Action.Pass && long == Action.Pass) {
                    if (mode != Mode.PASS) scheduleRefresh(400)
                    return false
                }
                // Timers run from the key's own down time, so the window read above does not eat into a hold.
                val p = Press(short, long, screen, event.downTime)
                presses[code] = p
                if (long != Action.Pass) {
                    handler.postAtTime(p.fireLong, p.downAt + LONG_PRESS_MS)
                } else {
                    execute(short, screen)
                    if (short is Action.MoveCursor) handler.postAtTime(p.repeat, p.downAt + CURSOR_REPEAT_DELAY_MS)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                val p = presses.remove(code) ?: return false
                handler.removeCallbacks(p.fireLong)
                handler.removeCallbacks(p.repeat)
                if (p.long != Action.Pass && !p.longFired) {
                    if (event.eventTime - event.downTime >= LONG_PRESS_MS) p.fireLong.run() else execute(p.short, p.screen)
                }
                return true
            }
        }
        return false
    }

    private fun refresh(): Pair<Screen, Mode> {
        val screen = readScreen(windows, activity)
        foreground = screen.snapshot.pkg
        // Forcing the cursor is scoped to the player screen it was forced on.
        if (classify(screen.snapshot, cursorForced = false) != Mode.PLAYER) cursorForced = false
        val mode = classify(screen.snapshot, cursorForced)
        if (mode == Mode.CURSOR) cursor.show(screen.content) else cursor.hide()
        return screen to mode
    }

    private fun scheduleRefresh(delayMs: Long) {
        handler.postDelayed({ refresh() }, delayMs)
    }

    private fun execute(action: Action, screen: Screen) {
        when (action) {
            is Action.Flip -> screen.pagerNode?.performAction(scroll(action.next).id)
            is Action.MoveCursor -> moveCursor(action, screen, (screen.content.height() * 0.03).toInt())
            Action.CursorClick -> {
                val p = cursor.position
                val target = nodeAt(windows, p.x, p.y) { it.isClickable }
                Log.d(TAG, "click at $p on ${target?.className}")
                target?.performAction(AccessibilityAction.ACTION_CLICK.id)
                scheduleRefresh(400)
                scheduleRefresh(1200)
            }
            is Action.ForceCursor -> {
                cursorForced = action.on
                refresh()
            }
            Action.Pass -> Unit
        }
    }

    private fun holdStep(heldMs: Long, content: Rect): Int {
        val ramp = ((heldMs - CURSOR_REPEAT_DELAY_MS).coerceAtLeast(0) / 700f).coerceAtMost(1f)
        val heightsPerSecond = 0.5f + 1.1f * ramp
        return (content.height() * heightsPerSecond * CURSOR_TICK_MS / 1000f).toInt().coerceAtLeast(1)
    }

    private fun moveCursor(action: Action.MoveCursor, screen: Screen, step: Int) {
        if (cursor.moveBy(action.dx * step, action.dy * step)) return
        // Pinned against an edge: scroll the list under the pointer instead, at most once per gap while held.
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeScroll < EDGE_SCROLL_GAP_MS) return
        lastEdgeScroll = now
        val c = screen.content
        val p = cursor.position
        val vertical = action.dy != 0
        val wants = scrolls(vertical, c)
        // A pointer pinned to the top or bottom usually sits on a title or tab bar, so the column's middle is tried too.
        val target = nodeAt(windows, p.x, p.y, wants) ?: if (vertical) nodeAt(windows, p.x, c.centerY(), wants) else null
        val forward = action.dx + action.dy > 0
        Log.d(TAG, "edge scroll ${if (forward) "forward" else "back"} on ${target?.className}")
        target?.performAction(scroll(forward).id)
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
