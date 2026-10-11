package org.aero.launcher

/* The home screen is a grid, like Android's own launchers: pages side by side, each page split in
   cells; every widget or app icon takes a rectangle of cells (x, y, width w, height h). Dropping
   something snaps it to the cells ("magnet"); if the place is taken, it goes to the nearest free one. */

/** Something on the home screen. [id] stays the same when it moves. */
data class Item(
    val id: String,
    val kind: Kind,
    val page: Int, val x: Int, val y: Int, val w: Int, val h: Int,
    val widgetId: Int = -1,          // Android widgets
    val appKey: String = "",         // app icons (App.key)
) {
    fun overlaps(o: Item) = page == o.page && x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
    fun encode() = "$id|${kind.id}|$page|$x|$y|$w|$h|$widgetId|$appKey"

    companion object {
        fun decode(line: String): Item? {
            val p = line.split("|")
            if (p.size < 9) return null
            val kind = Kind.entries.firstOrNull { it.id == p[1] } ?: return null
            return Item(p[0], kind, p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toInt(), p[6].toInt(), p[7].toInt(), p[8])
        }
        fun widget(kind: Kind, cols: Int) = defaultSpan(kind, cols).let { (w, h) -> Item("w:${kind.id}", kind, 0, 0, 0, w, h) }
        fun android(widgetId: Int, w: Int, h: Int) = Item("w:android:$widgetId", Kind.ANDROID, 0, 0, 0, w, h, widgetId = widgetId)
        fun app(app: App) = Item("a:${app.key}", Kind.APP, 0, 0, 0, 1, 1, appKey = app.key)
    }
}

/** Default size of each built-in widget, in cells. */
fun defaultSpan(kind: Kind, cols: Int): Pair<Int, Int> = when (kind) {
    Kind.CLOCK -> cols to 2
    Kind.WEATHER -> cols to 2
    Kind.APPS -> cols to 2
    Kind.BATTERY -> (cols / 2) to 2
    Kind.RESOURCES -> cols to 2
    Kind.FETCH -> cols to 3
    Kind.TERMINAL -> cols to 3
    Kind.ANDROID -> cols to 2
    Kind.APP -> 1 to 1
}

/** Does [cand] fit inside the grid without covering anything (except itself / [ignoreId])? */
fun fits(items: List<Item>, cand: Item, cols: Int, rows: Int, ignoreId: String? = null) =
    cand.x >= 0 && cand.y >= 0 && cand.x + cand.w <= cols && cand.y + cand.h <= rows &&
        items.none { it.id != cand.id && it.id != ignoreId && it.overlaps(cand) }

/** The free place on [cand]'s page closest to where it was dropped, or null if the page is full. */
fun nearestFree(items: List<Item>, cand: Item, cols: Int, rows: Int, ignoreId: String? = null): Item? {
    if (cand.w > cols || cand.h > rows) return null
    return (0..rows - cand.h).flatMap { y -> (0..cols - cand.w).map { x -> cand.copy(x = x, y = y) } }
        .sortedBy { (it.x - cand.x) * (it.x - cand.x) + (it.y - cand.y) * (it.y - cand.y) }
        .firstOrNull { fits(items, it, cols, rows, ignoreId) }
}

/** First free place for [item] scanning the pages from [fromPage] on (a new page if all are full). */
fun firstFree(items: List<Item>, item: Item, cols: Int, rows: Int, fromPage: Int = 0): Item {
    val sized = item.copy(w = item.w.coerceIn(1, cols), h = item.h.coerceIn(1, rows))
    var page = fromPage
    while (true) {
        nearestFree(items, sized.copy(page = page, x = 0, y = 0), cols, rows)?.let { return it }
        page++
    }
}

/** Puts every item where it can be on a grid of this size (after a change of columns, or a smaller screen). */
fun fitAll(items: List<Item>, cols: Int, rows: Int): List<Item> {
    val placed = mutableListOf<Item>()
    for (it in items.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))) {
        val sized = it.copy(w = it.w.coerceIn(1, cols), h = it.h.coerceIn(1, rows))
        placed += if (fits(placed, sized, cols, rows)) sized
            else nearestFree(placed, sized, cols, rows) ?: firstFree(placed, sized, cols, rows, sized.page)
    }
    return placed
}
