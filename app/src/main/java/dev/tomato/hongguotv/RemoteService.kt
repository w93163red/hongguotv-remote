package dev.tomato.hongguotv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

private const val TAG = "HongguoTV"
private const val LONG_PRESS_MS = 500L
private const val TAP_MS = 50L
private const val SWIPE_MS = 200L
private const val SCROLL_MS = 300L
private const val SEEK_SECONDS = 10
private const val CURSOR_REPEAT_DELAY_MS = 250L
private const val CURSOR_TICK_MS = 16L

class RemoteService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cursor: CursorOverlay
    private var cursorForced = false
    // A deadline rather than a flag, so a lost gesture callback can never wedge input.
    private var busyUntil = 0L
    private var seekDrag: SeekDrag? = null
    private val gestureBusy get() = SystemClock.uptimeMillis() < busyUntil || seekDrag != null

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
            is Action.SwipeVertical -> screen.pager?.let { swipe(it, action.next) }
            Action.TapCenter -> screen.pager?.let { tap(it.centerX(), it.centerY()) }
            is Action.Seek -> screen.pager?.let { seek(it, screen.content, action.forward) }
            is Action.MoveCursor -> moveCursor(action, screen, (screen.content.height() * 0.03).toInt())
            Action.CursorClick -> {
                Log.d(TAG, "click at ${cursor.position}")
                tap(cursor.position.x, cursor.position.y)
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
        if (cursor.moveBy(action.dx * step, action.dy * step) || action.dy == 0 || gestureBusy) return
        // Pinned against the top or bottom edge: scroll the content under the pointer instead.
        val c = screen.content
        val x = cursor.position.x.toFloat()
        val (from, to) = (c.top + c.height() * 0.65f) to (c.top + c.height() * 0.35f)
        if (action.dy > 0) drag(x, from, x, to, SCROLL_MS) else drag(x, to, x, from, SCROLL_MS)
    }

    private fun swipe(pager: Rect, next: Boolean) {
        if (gestureBusy) return
        val x = pager.centerX().toFloat()
        val low = pager.top + pager.height() * 0.72f
        val high = pager.top + pager.height() * 0.28f
        if (next) drag(x, low, x, high, SWIPE_MS) else drag(x, high, x, low, SWIPE_MS)
    }

    private fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatch(StrokeDescription(path, 0, TAP_MS))
    }

    private fun drag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatch(StrokeDescription(path, 0, durationMs))
    }

    private fun seek(pager: Rect, content: Rect, forward: Boolean) {
        val offset = if (forward) SEEK_SECONDS else -SEEK_SECONDS
        seekDrag?.let { it.add(offset); return }
        if (gestureBusy) return Unit.also { Log.d(TAG, "seek: gesture busy") }
        val root = rootInActiveWindow ?: return Unit.also { Log.d(TAG, "seek: no active window") }
        val row = findSeekRow(root, pager, content) ?: return Unit.also { Log.d(TAG, "seek: no progress row") }
        val drag = SeekDrag(
            row.start.x.toFloat(), row.start.y.toFloat(), row.width, content, handler,
            dispatch = ::dispatch,
            readout = { rootInActiveWindow?.let { readDrag(it, content) } },
            onEnd = { seekDrag = null },
        )
        seekDrag = drag
        drag.begin(offset)
    }

    private fun dispatch(stroke: StrokeDescription, onDone: () -> Unit = {}, onCancel: () -> Unit = {}) {
        busyUntil = SystemClock.uptimeMillis() + stroke.duration + 1000
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) {
                if (!stroke.willContinue()) busyUntil = 0
                onDone()
            }
            override fun onCancelled(g: GestureDescription) {
                busyUntil = 0
                onCancel()
            }
        }, null)
        if (!sent) {
            busyUntil = 0
            onCancel()
        }
    }

    private fun resolvesToActivity(cls: String): Boolean = try {
        packageManager.getActivityInfo(ComponentName(TARGET_PACKAGE, cls), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
