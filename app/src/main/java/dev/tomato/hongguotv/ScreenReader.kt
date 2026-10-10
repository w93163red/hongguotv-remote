package dev.tomato.hongguotv

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** What the service knows about the foreground screen: the pure snapshot plus the nodes actions need. */
class Screen(val snapshot: ScreenSnapshot, val content: Rect, val pagerNode: AccessibilityNodeInfo?)

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
        hasFullHeightVerticalPager = found?.pagerNode != null,
        // A dialog window above the activity (选集 sheet), or something tappable drawn over the pager centre.
        hasOverlaySheet = ownRoots.size > 1 || found?.covered == true,
    )
    return Screen(snapshot, content, found?.pagerNode)
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

/** The top-most application window drawn under ([x], [y]); a touch there would land in it. */
private fun rootAt(windows: List<AccessibilityWindowInfo>, x: Int, y: Int): AccessibilityNodeInfo? =
    windows.firstOrNull { w ->
        w.type == AccessibilityWindowInfo.TYPE_APPLICATION && Rect().also(w::getBoundsInScreen).contains(x, y)
    }?.root

/**
 * The innermost node under ([x], [y]) that matches [want], or its nearest matching ancestor. Later siblings are
 * drawn on top, so among overlapping matches the last one visited wins. Stands in for a touch at that point.
 */
fun nodeAt(windows: List<AccessibilityWindowInfo>, x: Int, y: Int, want: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    val root = rootAt(windows, x, y) ?: return null
    var hit: AccessibilityNodeInfo? = null
    fun visit(node: AccessibilityNodeInfo) {
        if (!node.isVisibleToUser) return
        if (!Rect().also(node::getBoundsInScreen).contains(x, y)) return
        if (want(node)) hit = node
        for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
    }
    visit(root)
    return hit
}

/**
 * Scrollables carry no orientation, so it is read from the shape: rows of cards and tab strips are short and
 * wide, page lists are tall. ViewPagers are excluded because scrolling one changes section instead of moving
 * the list.
 */
fun scrolls(vertical: Boolean, content: Rect): (AccessibilityNodeInfo) -> Boolean = { node ->
    val r = Rect().also(node::getBoundsInScreen)
    node.isScrollable && node.className?.contains("ViewPager") != true &&
        (r.height() >= content.height() * 0.4) == vertical
}
