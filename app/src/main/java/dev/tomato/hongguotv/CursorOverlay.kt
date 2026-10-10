package dev.tomato.hongguotv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * One accessibility overlay window that either draws the pointer ring or frames the selected bottom tab.
 * It never takes touches or focus.
 */
class CursorOverlay(context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val radius = 14 * density
    private val ringSize = (2 * (radius + 4 * density)).toInt()
    private var framing = false
    private val view = object : View(context) {
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7 * density; color = Color.BLACK }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * density; color = Color.WHITE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 64, 64) }
        override fun onDraw(canvas: Canvas) {
            if (framing) {
                val inset = outer.strokeWidth / 2
                val box = RectF(inset, inset, width - inset, height - inset)
                canvas.drawRoundRect(box, 12 * density, 12 * density, outer)
                canvas.drawRoundRect(box, 12 * density, 12 * density, inner)
                return
            }
            val c = width / 2f
            canvas.drawCircle(c, c, radius, outer)
            canvas.drawCircle(c, c, radius, inner)
            canvas.drawCircle(c, c, 3 * density, dot)
        }
    }
    private val params = WindowManager.LayoutParams(
        ringSize, ringSize,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    val position = Point()
    private val bounds = Rect()
    private var shown = false
    private var pointerShown = false

    /** Appears at the centre each time it is shown, where the viewer is already looking. */
    fun show(content: Rect) {
        if (!pointerShown || !content.contains(position.x, position.y)) position.set(content.centerX(), content.centerY())
        pointerShown = true
        bounds.set(content)
        framing = false
        params.width = ringSize
        params.height = ringSize
        place()
    }

    fun frame(r: Rect) {
        pointerShown = false
        framing = true
        params.x = r.left
        params.y = r.top
        params.width = r.width()
        params.height = r.height()
        attach()
    }

    fun hide() {
        if (shown) wm.removeView(view)
        shown = false
        pointerShown = false
    }

    /** Moves by (dx, dy) clamped to the content. Returns false when already pinned against that edge. */
    fun moveBy(dx: Int, dy: Int): Boolean {
        // A held key's repeat can outlive the pointer when the screen changes under it.
        if (!pointerShown) return true
        val x = (position.x + dx).coerceIn(bounds.left + 1, bounds.right - 2)
        val y = (position.y + dy).coerceIn(bounds.top + 1, bounds.bottom - 2)
        if (x == position.x && y == position.y) return false
        position.set(x, y)
        place()
        return true
    }

    private fun place() {
        params.x = position.x - ringSize / 2
        params.y = position.y - ringSize / 2
        attach()
    }

    private fun attach() {
        if (shown) wm.updateViewLayout(view, params) else wm.addView(view, params)
        shown = true
        view.invalidate()
    }
}
