package dev.tomato.hongguotv

import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.util.Log

private const val NUDGE_MS = 100L
private const val MOVE_MS = 120L
private const val POLL_MS = 75L
private const val POLLS = 8
private const val LINGER_MS = 700L

/**
 * One finger-down drag on the player's progress row that absorbs every seek press until the finger lifts.
 *
 * The player exposes no SeekBar or RangeInfo. Its progress row drags relative to where the finger lands and
 * only shows position, duration and the real track width while a drag is in progress. So the finger lands,
 * nudges right to open that readout, then follows the requested offset. It lingers before lifting so quick
 * repeated presses extend the same drag instead of re-touching a bar that is still animating, which was
 * observed to miss the bar and drag the detail-page pager instead.
 */
class SeekDrag(
    private val x0: Float,
    private val y: Float,
    private val rowWidth: Int,
    private val bounds: Rect,
    private val handler: Handler,
    private val dispatch: (stroke: StrokeDescription, onDone: () -> Unit, onCancel: () -> Unit) -> Unit,
    private val readout: () -> DragReadout?,
    private val onEnd: () -> Unit,
) {
    private lateinit var stroke: StrokeDescription
    private var x = x0
    private var seconds = 0
    private var pxPerSecond: Float? = null
    private var aborted = false
    private var moving = false
    private var polls = 0
    private val lift = Runnable {
        send(stroke.continueStroke(line(x, x), 0, 16, false)) { onEnd() }
    }

    fun begin(offsetSeconds: Int) {
        seconds = offsetSeconds
        val nudged = x0 + bounds.width() * 0.04f
        stroke = StrokeDescription(line(x0, nudged), 0, NUDGE_MS, true)
        x = nudged
        moving = true
        send(stroke) { moving = false; poll() }
    }

    fun add(offsetSeconds: Int) {
        seconds += offsetSeconds
        if (pxPerSecond != null) follow()
    }

    private fun poll() {
        val r = readout()
        if (r != null) {
            pxPerSecond = (r.trackWidth ?: rowWidth).toFloat() / r.durationSec
            Log.d("HongguoTV", "seek: from=${r.positionSec}s of=${r.durationSec}s track=${r.trackWidth} offset=${seconds}s")
        } else if (++polls < POLLS) {
            handler.postDelayed(::poll, POLL_MS)
            return
        } else {
            Log.d("HongguoTV", "seek: no readout, lifting at the start point")
            aborted = true
            pxPerSecond = 0f
        }
        follow()
    }

    private fun follow() {
        if (moving) return
        val target = (x0 + seconds * pxPerSecond!!).coerceIn(bounds.left + 1f, bounds.right - 2f)
        handler.removeCallbacks(lift)
        if (target == x) {
            handler.postDelayed(lift, if (aborted) 0 else LINGER_MS)
            return
        }
        moving = true
        stroke = stroke.continueStroke(line(x, target), 0, MOVE_MS, true)
        x = target
        // Presses that arrived during this move are picked up by following again once it lands.
        send(stroke) {
            moving = false
            Log.d("HongguoTV", "seek: landed at=${readout()?.positionSec}s offset=${seconds}s")
            follow()
        }
    }

    private fun send(s: StrokeDescription, onDone: () -> Unit): Unit = dispatch(s, onDone) {
        handler.removeCallbacks(lift)
        onEnd()
    }

    private fun line(from: Float, to: Float) = Path().apply { moveTo(from, y); lineTo(to, y) }
}
