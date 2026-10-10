package dev.tomato.hongguotv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** A pointer ring in an accessibility overlay window. It never takes touches, so taps land on the app below. */
class CursorOverlay(context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val radius = 14 * density
    private val size = (2 * (radius + 4 * density)).toInt()
    private val ring = object : View(context) {
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7 * density; color = Color.BLACK }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * density; color = Color.WHITE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 64, 64) }
        override fun onDraw(canvas: Canvas) {
            val c = size / 2f
            canvas.drawCircle(c, c, radius, outer)
            canvas.drawCircle(c, c, radius, inner)
            canvas.drawCircle(c, c, 3 * density, dot)
        }
    }
    private val params = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    val position = Point()
    private val bounds = Rect()
    private var shown = false

    /** Appears at the centre each time it is shown, where the viewer is already looking. */
    fun show(content: Rect) {
        if (!shown || !content.contains(position.x, position.y)) position.set(content.centerX(), content.centerY())
        bounds.set(content)
        place()
        if (!shown) {
            wm.addView(ring, params)
            shown = true
        }
    }

    fun hide() {
        if (shown) wm.removeView(ring)
        shown = false
    }

    /** Moves by (dx, dy) clamped to the content. Returns false when already pinned against that edge. */
    fun moveBy(dx: Int, dy: Int): Boolean {
        val x = (position.x + dx).coerceIn(bounds.left + 1, bounds.right - 2)
        val y = (position.y + dy).coerceIn(bounds.top + 1, bounds.bottom - 2)
        if (x == position.x && y == position.y) return false
        position.set(x, y)
        place()
        return true
    }

    private fun place() {
        params.x = position.x - size / 2
        params.y = position.y - size / 2
        if (shown) wm.updateViewLayout(ring, params)
    }
}
