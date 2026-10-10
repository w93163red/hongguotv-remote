package dev.tomato.hongguotv

import android.graphics.Point
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** What the service knows about the foreground screen: the pure snapshot plus the geometry and nodes actions need. */
class Screen(val snapshot: ScreenSnapshot, val content: Rect, val pager: Rect?, val pagerNode: AccessibilityNodeInfo?)

/**
 * Reads the window list. Everything is keyed on framework class names, collection info and geometry,
 * never on the target app's resource ids, which are obfuscated and change between releases.
 */
fun readScreen(windows: List<AccessibilityWindowInfo>, activity: String?): Screen {
    val imeVisible = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    // getWindows() is ordered top-most first.
    val appRoots = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root }
    val pkg = appRoots.firstOrNull()?.packageName?.toString().orEmpty()
    val ownRoots = appRoots.filter { it.packageName == pkg }
    val base = ownRoots.lastOrNull()
    val content = Rect().also { base?.getBoundsInScreen(it) }
    val found = if (pkg == TARGET_PACKAGE && base != null) PagerSearch(content).run(base) else null
    val snapshot = ScreenSnapshot(
        pkg = pkg,
        activity = activity,
        imeVisible = imeVisible,
        hasFullHeightVerticalPager = found?.pager != null,
        // A dialog window above the activity (选集 sheet), or something tappable drawn over the pager centre.
        hasOverlaySheet = ownRoots.size > 1 || found?.covered == true,
    )
    return Screen(snapshot, content, found?.pager, found?.pagerNode)
}

private class PagerSearch(val content: Rect) {
    var pager: Rect? = null
    var pagerNode: AccessibilityNodeInfo? = null
    var covered = false

    fun run(root: AccessibilityNodeInfo): PagerSearch = apply { visit(root) }

    private fun visit(node: AccessibilityNodeInfo) {
        if (!node.isVisibleToUser) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        val p = pager
        if (p == null) {
            // Subtrees too small to hold a full-height pager are skipped; each child fetch is an IPC.
            if (r.width() < content.width() * 0.95 || r.height() < content.height() * 0.75) return
            // ViewPager2 reports itself as ViewPager; vertical orientation shows up as a one-column collection.
            if (node.className?.contains("ViewPager") == true && node.collectionInfo?.columnCount == 1) {
                pager = r
                pagerNode = node
                return
            }
        } else {
            if (!r.contains(p.centerX(), p.centerY())) return
            if (node.isClickable || !node.text.isNullOrEmpty()) {
                // Visited after the pager subtree, so it is drawn above the pager: a popup or detail layer.
                covered = true
                return
            }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
    }
}

/** The thin full-width progress row at the bottom of the current page, and a point on it to drag from. */
class SeekRow(val start: Point, val width: Int)

fun findSeekRow(root: AccessibilityNodeInfo, pager: Rect, content: Rect): SeekRow? {
    val rows = mutableListOf<Rect>()
    val thumbs = mutableListOf<Rect>()
    val bandTop = pager.top + pager.height() * 0.8
    fun visit(node: AccessibilityNodeInfo) {
        if (!node.isVisibleToUser) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.bottom < bandTop || !Rect.intersects(r, pager)) return
        if (r.top >= bandTop) {
            if (r.width() >= content.width() * 0.95 && r.height() <= content.height() * 0.06) rows += r
            if (node.className == "android.widget.ImageView" && r.width() <= content.width() * 0.035 && r.height() <= content.width() * 0.035) thumbs += r
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
    }
    visit(root)
    val thumb = thumbs.firstOrNull { t -> rows.any { it.contains(t.centerX(), t.centerY()) } } ?: return null
    val row = rows.first { it.contains(thumb.centerX(), thumb.centerY()) }
    return SeekRow(Point(row.centerX(), thumb.centerY()), row.width())
}

/** While the progress bar is being dragged the app shows "mm:ss / mm:ss" and the real track. */
class DragReadout(val positionSec: Int, val durationSec: Int, val trackWidth: Int?)

private val TIME = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")

private fun seconds(text: CharSequence): Int? {
    val m = TIME.matchEntire(text.trim()) ?: return null
    val (a, b, c) = m.destructured
    return if (c.isEmpty()) a.toInt() * 60 + b.toInt() else a.toInt() * 3600 + b.toInt() * 60 + c.toInt()
}

fun readDrag(root: AccessibilityNodeInfo, content: Rect): DragReadout? {
    val times = mutableListOf<Int>()
    var track: Int? = null
    val bandTop = content.top + content.height() * 0.75
    fun visit(node: AccessibilityNodeInfo) {
        if (!node.isVisibleToUser) return
        node.text?.let(::seconds)?.let { times += it }
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (node.childCount == 0 && r.top >= bandTop && r.height() <= content.height() * 0.08 &&
            r.width() >= content.width() * 0.6 && r.width() < content.width() * 0.97
        ) track = maxOf(track ?: 0, r.width())
        for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
    }
    visit(root)
    if (times.size < 2 || times[1] <= 0) return null
    return DragReadout(times[0], times[1], track)
}
