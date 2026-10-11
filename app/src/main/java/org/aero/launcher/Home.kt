package org.aero.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What is being dragged right now. Shared by the home and the app list (an app dragged out of the list). */
class DragState {
    var item by mutableStateOf<Item?>(null)
    var isNew by mutableStateOf(false)          // came from the app list: not on the home yet
    var finger by mutableStateOf(Offset.Zero)   // window coordinates
    var grab by mutableStateOf(Offset.Zero)     // where the finger holds the item, from its top-left
    var moved = 0f
    var cell = Offset(200f, 200f)               // size of one grid cell (set by the home)
    var onRelease: () -> Unit = {}              // set by the home: where it lands
    val active get() = item != null
}

/** A text field that must stay above the keyboard (the terminal) reports its bottom edge here. */
class ImeTarget { var bottom by mutableFloatStateOf(0f) }
val LocalImeTarget = staticCompositionLocalOf { ImeTarget() }

/**
 * The home screen: pages side by side, each one a grid of cells (4 or 5 columns). Widgets and app
 * icons sit on the cells. Long press + drag moves them; while dragging, an outline shows the cells
 * where it will land ("magnet") and on release it glides there. Dragging to the screen's side edge
 * turns the page; dropping on "remove" at the top takes it off. Long press without moving = its menu;
 * on empty space = launcher menu. Vertical swipes: down = notifications, up = app list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Home(
    repo: AppRepository, prefs: Prefs, host: WidgetHost, art: AsciiArt, icons: IconCache, drag: DragState,
    homePresses: Int, isDefault: Boolean, onMakeDefault: () -> Unit,
    onOpenSearch: () -> Unit, onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val L = LocalLang.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val imeTarget = LocalImeTarget.current
    val background by prefs.background
    val shimmer by prefs.shimmer
    val items by prefs.items
    val cols by prefs.gridCols
    val apps by repo.apps
    var menu by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<String?>(null) }
    var resizing by remember { mutableStateOf<String?>(null) }        // widget showing its resize handles
    var resizeTo by remember { mutableStateOf<Item?>(null) }          // its new size while a handle is dragged
    val pagesPref by prefs.pages
    val rowsManual by prefs.rowsManual
    val showPrompt by prefs.showPrompt
    val iconScale by prefs.iconScale
    BackHandler(enabled = resizing != null) { resizing = null; resizeTo = null }

    // grid geometry, measured below
    var grid by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cellW by remember { mutableFloatStateOf(1f) }
    var cellH by remember { mutableFloatStateOf(1f) }
    var gridWidth by remember { mutableFloatStateOf(1f) }
    val rows by prefs.gridRows
    var screenH by remember { mutableFloatStateOf(0f) }

    val usedPages = (items.filter { it.page >= 0 }.maxOfOrNull { it.page } ?: 0) + 1
    val basePages = maxOf(pagesPref, usedPages)                    // workspaces you created, empty ones too
    val pageCount = basePages + if (drag.active) 1 else 0          // an extra empty page to drop on while dragging
    val pager = rememberPagerState(pageCount = { pageCount })

    LaunchedEffect(homePresses) { focus.clearFocus(); menu = false; itemMenu = null; resizing = null; pager.animateScrollToPage(0) }

    // Keep the grid tidy: place new things (page -1), refit after a change of columns or rows, and drop
    // icons of uninstalled apps. Empty pages stay: they are workspaces you made.
    LaunchedEffect(items, cols, rows, apps) {
        if (drag.active) return@LaunchedEffect
        var list = items
        if (apps.isNotEmpty()) list = list.filter { it.kind != Kind.APP || apps.any { a -> a.key == it.appKey } }
        val ok = list.filter { it.page >= 0 && it.x + it.w <= cols && it.y + it.h <= rows }
        var placed = ok
        for (t in list - ok.toSet()) placed = placed + firstFree(placed, t, cols, rows, t.page.coerceAtLeast(0))
        if (placed.toSet() != items.toSet()) prefs.setItems(placed)
        val need = (placed.maxOfOrNull { it.page } ?: 0) + 1
        if (need > prefs.pages.value) prefs.setPages(need)
    }

    fun toGrid(window: Offset) = grid?.takeIf { it.isAttached }?.windowToLocal(window) ?: window
    fun cellRect(it: Item) = Rect(it.x * cellW, it.y * cellH, (it.x + it.w) * cellW, (it.y + it.h) * cellH)

    /** Where the dragged item would land: the cells under it, or the nearest free ones. */
    fun target(): Item? {
        val it = drag.item ?: return null
        val sized = it.copy(w = it.w.coerceAtMost(cols), h = it.h.coerceAtMost(rows))
        val topLeft = toGrid(drag.finger - drag.grab)
        val cand = sized.copy(
            page = pager.currentPage,
            x = (topLeft.x / cellW).roundToInt().coerceIn(0, cols - sized.w),
            y = (topLeft.y / cellH).roundToInt().coerceIn(0, rows - sized.h),
        )
        val others = prefs.items.value.filter { o -> o.id != it.id }
        return if (fits(others, cand, cols, rows)) cand else nearestFree(others, cand, cols, rows)
    }

    // drop targets at the top while dragging ("remove", and for apps "app info", "uninstall")
    val targets = remember { mutableStateMapOf<String, Rect>() }
    fun targetUnderFinger() = targets.entries.firstOrNull { it.value.contains(drag.finger) }?.key

    // the "magnet": after release the item glides from the finger to its cells
    val glide = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var landing by remember { mutableStateOf(false) }

    drag.onRelease = release@{
        val it = drag.item ?: return@release
        val app = if (it.kind == Kind.APP) repo.byKey(it.appKey) else null
        when (targetUnderFinger()) {
            "remove" -> {
                if (!drag.isNew) {
                    if (it.kind == Kind.ANDROID) host.remove(it.widgetId)
                    prefs.setItems(prefs.items.value.filter { o -> o.id != it.id })
                }
                drag.item = null; return@release
            }
            "info" -> { app?.let(repo::openInfo); drag.item = null; return@release }
            "uninstall" -> { app?.let(repo::uninstall); drag.item = null; return@release }
        }
        if (!drag.isNew && drag.moved < 12f) {
            if (it.kind == Kind.APP) itemMenu = it.id else { resizing = it.id; resizeTo = null }
            drag.item = null; return@release
        }
        val dest = target()
        if (dest == null) { drag.item = null; return@release }        // page full: it stays where it was
        scope.launch {
            landing = true
            glide.snapTo(drag.finger - drag.grab)
            val to = grid?.takeIf { g -> g.isAttached }?.localToWindow(Offset(dest.x * cellW, dest.y * cellH)) ?: glide.value
            glide.animateTo(to, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
            prefs.setItems(prefs.items.value.filter { o -> o.id != dest.id } + dest)
            drag.item = null
            landing = false
        }
    }

    // holding the dragged item at the left/right edge turns the page
    LaunchedEffect(drag.active) {
        var heldSince = 0L
        val edge = with(density) { 28.dp.toPx() }
        while (drag.active) {
            val now = withFrameNanos { it } / 1_000_000
            val x = toGrid(drag.finger).x
            val dir = if (x < edge) -1 else if (x > gridWidth - edge) 1 else 0
            if (dir == 0 || landing) heldSince = 0L
            else if (heldSince == 0L) heldSince = now
            else if (now - heldSince > 550) {
                val page = (pager.currentPage + dir).coerceIn(0, pageCount - 1)
                if (page != pager.currentPage) pager.animateScrollToPage(page)
                heldSince = 0L
            }
        }
    }

    // long press: on an item = pick it up (drag) ; on empty space = launcher menu
    val longPress = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val local = press.position
            val hit = prefs.items.value.firstOrNull { it.page == pager.currentPage && cellRect(it).contains(local) }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            press.consume()
            if (hit == null) { if (resizing != null) resizing = null else menu = true }
            else {
                resizing = null
                focus.clearFocus()
                drag.isNew = false
                drag.moved = 0f
                drag.grab = local - Offset(hit.x * cellW, hit.y * cellH)
                drag.finger = grid?.localToWindow(local) ?: local
                drag.item = hit
            }
            // from now on the touch is ours FIRST (Initial pass): buttons inside the item do not react
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                if (drag.active) {
                    drag.moved += (change.position - change.previousPosition).getDistance()
                    drag.finger = grid?.localToWindow(change.position) ?: change.position
                }
            }
            if (drag.active && !landing) drag.onRelease()
        }
    }
    // vertical swipes on the home: down = notifications, up = app list
    val swipes = Modifier.pointerInput(Unit) {
        var total = 0f
        val threshold = 60.dp.toPx()
        detectVerticalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (total > threshold) expandNotifications(ctx) else if (total < -threshold) onOpenSearch() },
        ) { _, dy -> total += dy }
    }

    // the keyboard covers the bottom: lift the home just enough to show the focused terminal
    val imeBottom = WindowInsets.ime.getBottom(density).toFloat()
    val lift = if (imeBottom > 0 && imeTarget.bottom > 0)
        (imeTarget.bottom - (screenH - imeBottom) + with(density) { 16.dp.toPx() }).coerceAtLeast(0f) else 0f

    Box(Modifier.fillMaxSize().onGloballyPositioned { screenH = it.size.height.toFloat() }) {
        if (background == "eris") AsciiBackground(art, shimmer)
        Column(Modifier.fillMaxSize().systemBarsPadding().graphicsLayer { translationY = -lift }) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                val cw = constraints.maxWidth / cols.toFloat()
                val newRows = if (rowsManual > 0) rowsManual else (constraints.maxHeight / cw).toInt().coerceAtLeast(4)
                cellW = cw
                cellH = constraints.maxHeight / newRows.toFloat()
                drag.cell = Offset(cellW, cellH)
                gridWidth = constraints.maxWidth.toFloat()
                LaunchedEffect(newRows) { if (newRows != prefs.gridRows.value && imeBottom == 0f) prefs.setGridRows(newRows) }
                Box(
                    Modifier.fillMaxSize()
                        .onGloballyPositioned { grid = it }
                        .then(longPress)
                        .then(swipes)
                ) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = !drag.active && resizing == null, beyondViewportPageCount = 1) { page ->
                        Box(Modifier.fillMaxSize()) {
                            items.filter { it.page == page }.forEach { stored ->
                                key(stored.id) {
                                    val item = if (stored.id == resizing) resizeTo ?: stored else stored
                                    val hidden = drag.item?.id == item.id
                                    Box(
                                        Modifier
                                            .offset { IntOffset((item.x * cellW).roundToInt(), (item.y * cellH).roundToInt()) }
                                            .size(with(density) { (item.w * cellW).toDp() }, with(density) { (item.h * cellH).toDp() })
                                            .padding(4.dp)
                                            .graphicsLayer { alpha = if (hidden) 0f else 1f }
                                    ) { ItemView(item, repo, prefs, host, icons, Modifier.fillMaxSize()) }
                                }
                            }
                            // the "magnet" outline: the cells where the dragged item will land
                            if (drag.active && !landing && page == pager.currentPage) target()?.let { t ->
                                Box(
                                    Modifier
                                        .offset { IntOffset((t.x * cellW).roundToInt(), (t.y * cellH).roundToInt()) }
                                        .size(with(density) { (t.w * cellW).toDp() }, with(density) { (t.h * cellH).toDp() })
                                        .padding(4.dp)
                                        .background(pal.fg.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                                        .border(1.dp, pal.fg.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                )
                            }
                            // resize: four handles on the edges; drag one and the widget follows, cell by cell
                            if (page == pager.currentPage) items.firstOrNull { it.id == resizing }?.let { base ->
                                val cur = resizeTo ?: base
                                val others = items.filter { it.id != base.id }
                                val ok = fits(others, cur, cols, rows)
                                Box(
                                    Modifier
                                        .offset { IntOffset((cur.x * cellW).roundToInt(), (cur.y * cellH).roundToInt()) }
                                        .size(with(density) { (cur.w * cellW).toDp() }, with(density) { (cur.h * cellH).toDp() })
                                        .padding(2.dp)
                                        .border(1.5.dp, if (ok) pal.fg else pal.dim, RoundedCornerShape(11.dp))
                                )
                                listOf("l", "r", "t", "b").forEach { edge ->
                                    val hs = with(density) { 26.dp.toPx() }
                                    // a little inside the widget: the very screen edge belongs to Android's back gesture
                                    val inset = with(density) { 14.dp.toPx() }
                                    val cx = when (edge) { "l" -> cur.x * cellW + inset; "r" -> (cur.x + cur.w) * cellW - inset; else -> (cur.x + cur.w / 2f) * cellW }
                                    val cy = when (edge) { "t" -> cur.y * cellH + inset; "b" -> (cur.y + cur.h) * cellH - inset; else -> (cur.y + cur.h / 2f) * cellH }
                                    Box(
                                        Modifier
                                            .offset { IntOffset((cx - hs / 2).roundToInt(), (cy - hs / 2).roundToInt()) }
                                            .size(26.dp)
                                            .systemGestureExclusion()      // and here the touch is ours, not "back"
                                            .background(pal.bg, CircleShape)
                                            .border(1.5.dp, pal.fg, CircleShape)
                                            .pointerInput(edge, base) {
                                                var acc = Offset.Zero
                                                detectDragGestures(
                                                    onDragStart = { acc = Offset.Zero },
                                                    onDragEnd = {
                                                        val c = resizeTo
                                                        if (c != null && fits(others, c, cols, rows)) prefs.setItems(others + c)
                                                        resizeTo = null
                                                    },
                                                    onDragCancel = { resizeTo = null },
                                                ) { change, amount ->
                                                    change.consume()
                                                    acc += amount
                                                    resizeTo = resized(base, edge, acc, cellW, cellH, cols, rows)
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (pageCount > 1) Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pageCount) { T(if (it == pager.currentPage) "●" else "○", 10.sp, if (it == pager.currentPage) pal.fg else pal.dim, Modifier.padding(horizontal = 3.dp)) }
            }
            if (!isDefault) TermBox(L("setup", "configurar"), Modifier.fillMaxWidth().padding(horizontal = 16.dp), onClick = onMakeDefault) {
                T(L("> make aero launcher your home screen", "> fazer do aero launcher sua tela inicial"), 14.sp)
            }
            if (showPrompt) TermBox("", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = onOpenSearch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("$ ", 18.sp, pal.dim)
                    Cursor()
                }
            } else Spacer(Modifier.height(12.dp))
        }

        // drop targets (top) while something is being dragged
        if (drag.active && !landing) {
            val isApp = drag.item?.kind == Kind.APP
            val over = targetUnderFinger()
            Row(Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull(
                    "remove" to (if (drag.isNew) L("× cancel", "× cancelar") else L("× remove", "× remover")),
                    if (isApp) "info" to L("i app info", "i informações") else null,
                    if (isApp) "uninstall" to L("⌫ uninstall", "⌫ desinstalar") else null,
                ).forEach { (id, text) ->
                    Box(
                        Modifier.weight(1f)
                            .onGloballyPositioned { targets[id] = it.boundsInWindow() }
                            .background(if (over == id) pal.fg else pal.fill, RoundedCornerShape(8.dp))
                            .border(1.dp, pal.line, RoundedCornerShape(8.dp))
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(text, 13.sp, if (over == id) pal.bg else pal.fg, maxLines = 1) }
                }
            }
        } else if (targets.isNotEmpty()) targets.clear()
        if (resizing != null && !drag.active) Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.BottomCenter) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    L("✓ done", "✓ pronto") to { resizing = null; resizeTo = null },
                    L("× remove", "× remover") to {
                        items.firstOrNull { it.id == resizing }?.let { r ->
                            if (r.kind == Kind.ANDROID) host.remove(r.widgetId)
                            prefs.setItems(items.filter { it.id != r.id })
                        }
                        resizing = null
                    },
                ).forEach { (text, action) ->
                    Box(
                        Modifier.weight(1f).glassScrim().border(1.dp, pal.line, RoundedCornerShape(8.dp))
                            .clickable(onClick = action).padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(text, 13.sp, pal.fg, maxLines = 1) }
                }
            }
        }

        // the dragged item itself, above everything, under the finger
        drag.item?.let { it ->
            val pos = if (landing) glide.value else drag.finger - drag.grab
            Box(
                Modifier
                    .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                    .size(with(density) { (it.w.coerceAtMost(cols) * cellW).toDp() }, with(density) { (it.h * cellH).toDp() })
                    .padding(4.dp)
                    .graphicsLayer { val s = if (landing) 1f else 1.06f; scaleX = s; scaleY = s; alpha = 0.95f }
            ) { ItemView(it, repo, prefs, host, icons, Modifier.fillMaxSize()) }
        }

        if (menu) Menu(
            "launcher",
            listOfNotNull(
                L("new workspace", "nova área de trabalho") to {
                    prefs.setPages(basePages + 1)
                    scope.launch { delay(60); pager.animateScrollToPage(basePages) }
                },
                if (basePages > 1 && items.none { it.page == pager.currentPage }) L("delete this empty workspace", "apagar esta área vazia") to {
                    val gone = pager.currentPage
                    prefs.setItems(items.map { if (it.page > gone) it.copy(page = it.page - 1) else it })
                    prefs.setPages(basePages - 1)
                } else null,
                L("launcher settings", "configurações do launcher") to onOpenSettings,
                L("system wallpaper", "papel de parede do sistema") to { openWallpaperPicker(ctx) },
                L("android settings", "configurações do android") to { openAndroidSettings(ctx) },
            ),
            onDismiss = { menu = false },
        )
        itemMenu?.let { id ->
            val it = items.firstOrNull { i -> i.id == id }
            if (it == null) { itemMenu = null; return@let }
            val others = items.filter { o -> o.id != id }
            fun resize(w: Int, h: Int) {
                val c = it.copy(w = w, h = h)
                val ok = if (fits(others, c, cols, rows)) c else c.copy(x = (cols - w).coerceAtMost(it.x)).takeIf { s -> fits(others, s, cols, rows) }
                if (ok != null) prefs.setItems(others + ok)
            }
            val app = if (it.kind == Kind.APP) repo.byKey(it.appKey) else null
            val entries: List<Pair<String, () -> Unit>> = if (app != null) listOf(
                L("app info", "informações do app") to { repo.openInfo(app) },
                L("uninstall", "desinstalar") to { repo.uninstall(app) },
                L("remove from home", "tirar da home") to { prefs.setItems(others) },
            ) else listOf(
                L("wider", "mais largo") to { resize(it.w + 1, it.h) },
                L("narrower", "mais estreito") to { if (it.w > 1) resize(it.w - 1, it.h) },
                L("taller", "mais alto") to { resize(it.w, it.h + 1) },
                L("shorter", "mais baixo") to { if (it.h > 1) resize(it.w, it.h - 1) },
                L("remove", "remover") to {
                    if (it.kind == Kind.ANDROID) host.remove(it.widgetId)
                    prefs.setItems(others)
                },
                L("launcher settings", "configurações do launcher") to onOpenSettings,
            )
            Menu(app?.label ?: it.kind.title(L), entries, onDismiss = { itemMenu = null })
        }
    }
}

/** One thing on the home grid. Long press / drag is handled by the home, so items have no menu of their own. */
@Composable
fun ItemView(item: Item, repo: AppRepository, prefs: Prefs, host: WidgetHost, icons: IconCache, modifier: Modifier) {
    when (item.kind) {
        Kind.APP -> repo.byKey(item.appKey)?.let { AppIcon(it, repo, icons, prefs.iconScale.value, modifier) } ?: Box(modifier)
        Kind.CLOCK -> ClockWidget(prefs, modifier, null)
        Kind.WEATHER -> WeatherWidget(modifier, null)
        Kind.APPS -> AppsWidget(repo, modifier, null)
        Kind.BATTERY -> BatteryWidget(modifier, null)
        Kind.RESOURCES -> ResourcesWidget(modifier, null)
        Kind.FETCH -> FetchWidget(repo, prefs, modifier, null)
        Kind.TERMINAL -> TerminalWidget(repo, prefs, modifier, null)
        Kind.ANDROID -> AndroidWidget(host, item.widgetId, modifier, null)
    }
}

/** An app on the home: its black-and-white icon and its name. */
@Composable
fun AppIcon(app: App, repo: AppRepository, icons: IconCache, scale: Float, modifier: Modifier) {
    Column(
        modifier.clickable { repo.launch(app) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MonoIcon(icons, app, 50.dp * scale)
        T(app.label, 11.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
    }
}

/** A blinking block cursor, like a terminal. */
@Composable
fun Cursor(height: Int = 20) {
    val pal = LocalPalette.current
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(530); on = !on } }
    Box(Modifier.width(10.dp).height(height.dp).background(if (on) pal.fg else pal.fill))
}

/** A small menu box at the bottom of the screen (used for long presses). */
@Composable
fun Menu(title: String, items: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxSize().glassScrim().clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        TermBox(title, Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp), opaque = true) {
            items.forEach { (text, action) ->
                T("> $text", 16.sp, pal.fg, Modifier.fillMaxWidth().clickable { onDismiss(); action() }.padding(vertical = 10.dp))
            }
            T(LocalLang.current("> cancel", "> cancelar"), 16.sp, pal.dim, Modifier.fillMaxWidth().clickable(onClick = onDismiss).padding(vertical = 10.dp))
        }
    }
}

/** The item with one edge moved by [drag] pixels, rounded to whole cells and kept inside the grid. */
private fun resized(base: Item, edge: String, drag: Offset, cellW: Float, cellH: Float, cols: Int, rows: Int): Item {
    val dx = (drag.x / cellW).roundToInt()
    val dy = (drag.y / cellH).roundToInt()
    return when (edge) {
        "r" -> base.copy(w = (base.w + dx).coerceIn(1, cols - base.x))
        "l" -> { val nx = (base.x + dx).coerceIn(0, base.x + base.w - 1); base.copy(x = nx, w = base.x + base.w - nx) }
        "b" -> base.copy(h = (base.h + dy).coerceIn(1, rows - base.y))
        else -> { val ny = (base.y + dy).coerceIn(0, base.y + base.h - 1); base.copy(y = ny, h = base.y + base.h - ny) }
    }
}
