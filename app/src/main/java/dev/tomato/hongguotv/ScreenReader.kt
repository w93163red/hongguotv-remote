package dev.tomato.hongguotv

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** One bottom tab: the clickable node and where it is drawn. */
class Tab(val node: AccessibilityNodeInfo, val bounds: Rect)

/** What the service knows about the foreground screen: the pure snapshot plus the nodes actions need. */
class Screen(
    val snapshot: ScreenSnapshot,
    val content: Rect,
    val pagerNode: AccessibilityNodeInfo?,
    val tabs: List<Tab>,
    val selectedTab: Int,
)

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
    val ours = pkg == TARGET_PACKAGE && base != null
    val found = if (ours) PagerSearch(content).run(base!!) else null
    val tabs = if (ours && activity == HOME_ACTIVITY) findTabBar(base!!, content) else emptyList()
    val snapshot = ScreenSnapshot(
        pkg = pkg,
        activity = activity,
        imeVisible = imeVisible,
        hasFullHeightVerticalPager = found?.pagerNode != null,
        // A dialog window above the activity (选集 sheet), or something tappable drawn over the pager centre.
        hasOverlaySheet = ownRoots.size > 1 || found?.covered == true,
        hasTabBar = tabs.isNotEmpty(),
    )
    val selected = tabs.indexOfFirst { hasSelected(it.node) }.coerceAtLeast(0)
    return Screen(snapshot, content, found?.pagerNode, tabs, selected)
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

/**
 * The bottom navigation row: three or more clickable siblings of one height, together spanning most of the
 * width, with their bottom edge on the window's. Only wide subtrees that reach the bottom band are walked.
 */
private fun findTabBar(root: AccessibilityNodeInfo, content: Rect): List<Tab> {
    val bandTop = content.bottom - content.height() * 0.15
    fun visit(node: AccessibilityNodeInfo): List<Tab>? {
        if (!node.isVisibleToUser) return null
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() < content.width() * 0.8 || r.bottom < content.bottom - 2 || r.bottom < bandTop) return null
        val kids = (0 until node.childCount).mapNotNull { node.getChild(it) }.filter { it.isVisibleToUser }
        val tabs = kids.map { Tab(it, Rect().also(it::getBoundsInScreen)) }
        if (tabs.size >= 3 && kids.all { it.isClickable } &&
            tabs.all { it.bounds.top == tabs[0].bounds.top && it.bounds.bottom == tabs[0].bounds.bottom } &&
            tabs[0].bounds.top >= bandTop && tabs.sumOf { it.bounds.width() } >= content.width() * 0.8
        ) return tabs.sortedBy { it.bounds.left }
        return kids.firstNotNullOfOrNull(::visit)
    }
    return visit(root).orEmpty()
}

private fun hasSelected(node: AccessibilityNodeInfo): Boolean =
    node.isSelected || (0 until node.childCount).any { i -> node.getChild(i)?.let(::hasSelected) == true }

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
