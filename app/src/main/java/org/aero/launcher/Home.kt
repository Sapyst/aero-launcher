package org.aero.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * The home screen: the background (the ASCII statue on OLED black, or the system wallpaper), the
 * chosen widgets (they scroll if there are many) and the `$` prompt.
 * Dragging DOWN when the widgets are at the top = notifications; dragging UP at the bottom = search.
 * Long press on empty space = launcher menu. Long press on a widget and DRAG = move it;
 * long press and let go without moving = that widget's menu (width, remove).
 */
/** Identity of a slot that survives reordering (drag & drop, remembered positions). */
private val Slot.key get() = if (kind == Kind.ANDROID) "android:$widgetId" else kind.id

@Composable
fun Home(
    repo: AppRepository, prefs: Prefs, host: WidgetHost, art: AsciiArt, homePresses: Int,
    isDefault: Boolean, onMakeDefault: () -> Unit,
    onOpenSearch: () -> Unit, onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val L = LocalLang.current
    val background by prefs.background
    val shimmer by prefs.shimmer
    val slots by prefs.slots
    val density = LocalDensity.current
    val threshold = with(density) { 70.dp.toPx() }
    val edge = with(density) { 80.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var slotMenu by remember { mutableStateOf<Int?>(null) }
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    // Home button: back to the top, keyboard away (like any launcher)
    LaunchedEffect(homePresses) { focus.clearFocus(); menu = false; slotMenu = null; scroll.animateScrollTo(0) }

    // What the scrolling did NOT use is a pull past the top/bottom edge: that is the gesture.
    val edgePull = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                pulled = if (pulled == 0f || (available.y > 0) == (pulled > 0)) pulled + available.y else available.y
                when {
                    pulled > threshold -> { pulled = 0f; expandNotifications(ctx) }
                    pulled < -threshold -> { pulled = 0f; onOpenSearch() }
                }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity { pulled = 0f; return Velocity.Zero }
        }
    }

    // ---------- drag & drop ----------
    // Positions are kept in the CONTENT's coordinates (the scrolling column), which do not change
    // when a widget moves in the list. The dragged widget is drawn shifted (graphicsLayer) so that
    // it stays under the finger; the others take their new places as the list reorders live.
    var order by remember { mutableStateOf(slots) }                  // what is shown (changes while dragging)
    var dragKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(slots) { if (dragKey == null) order = slots }
    val bounds = remember { mutableStateMapOf<String, Rect>() }      // each widget, in content coordinates
    var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var grabTopLeft by remember { mutableStateOf(Offset.Zero) }      // the widget's top-left when grabbed
    var fingerStart by remember { mutableStateOf(Offset.Zero) }      // finger (content) when grabbed
    var fingerView by remember { mutableStateOf(Offset.Zero) }       // finger relative to the visible area
    var moved by remember { mutableFloatStateOf(0f) }
    fun finger() = Offset(fingerView.x, fingerView.y + scroll.value)  // finger in content coordinates

    fun reorderUnderFinger() {
        val key = dragKey ?: return
        val f = finger()
        val target = order.firstOrNull { it.key != key && bounds[it.key]?.contains(f) == true } ?: return
        val from = order.indexOfFirst { it.key == key }
        val to = order.indexOf(target)
        if (from >= 0 && to >= 0 && from != to) order = order.toMutableList().apply { add(to, removeAt(from)) }
    }
    // dragging near the top or bottom edge scrolls by itself
    LaunchedEffect(dragKey) {
        while (dragKey != null) {
            val step = when {
                fingerView.y < edge -> -14f
                fingerView.y > viewportHeight - edge -> 14f
                else -> 0f
            }
            if (step != 0f && scroll.dispatchRawDelta(step) != 0f) reorderUnderFinger()
            withFrameNanos { }
        }
    }
    val dragGesture = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture   // moved = normal scroll
            val key = order.firstOrNull { bounds[it.key]?.contains(press.position) == true }?.key
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            press.consume()
            if (key == null) {                                   // empty space: the launcher menu
                menu = true
            } else {
                focus.clearFocus()
                dragKey = key; moved = 0f
                grabTopLeft = bounds[key]!!.topLeft
                fingerStart = press.position
                fingerView = Offset(press.position.x, press.position.y - scroll.value)
            }
            // From here on we take the touch FIRST (Initial pass), so the buttons inside the widget
            // do not also react (a pinned app would open when you let go).
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                if (dragKey != null) {
                    moved += (change.position - change.previousPosition).getDistance()
                    fingerView = Offset(change.position.x, change.position.y - scroll.value)
                    reorderUnderFinger()
                }
            }
            val dropped = dragKey
            dragKey = null
            if (dropped != null) {
                if (moved < 12f) slotMenu = order.indexOfFirst { it.key == dropped }.takeIf { it >= 0 }
                else prefs.setSlots(order)
            }
        }
    }

    /** One widget in the list: remembers where it is; follows the finger while dragged. */
    @Composable
    fun Placed(slot: Slot, modifier: Modifier) {
        val dragged = dragKey == slot.key
        Box(
            modifier
                .zIndex(if (dragged) 1f else 0f)
                .onGloballyPositioned { c -> content?.takeIf { it.isAttached }?.let { bounds[slot.key] = it.localBoundingBoxOf(c) } }
        ) {
            SlotView(slot, repo, prefs, host, Modifier.fillMaxWidth().graphicsLayer {
                if (dragKey == slot.key) {
                    val base = bounds[slot.key]?.topLeft ?: grabTopLeft
                    val shift = grabTopLeft + (finger() - fingerStart) - base
                    translationX = shift.x; translationY = shift.y
                    scaleX = 1.03f; scaleY = 1.03f; alpha = 0.92f
                }
            })
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (background == "eris") AsciiBackground(art, shimmer)
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {      // imePadding: the keyboard pushes the home up (terminal)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { viewportHeight = it.size.height.toFloat() }
                    .nestedScroll(edgePull)
                    .verticalScroll(scroll)
                    .onGloballyPositioned { content = it }
                    .then(dragGesture)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!isDefault) TermBox(L("setup", "configurar"), Modifier.fillMaxWidth(), onClick = onMakeDefault) {
                    T(L("> make aero launcher your home screen", "> fazer do aero launcher sua tela inicial"), 15.sp)
                    T(L("so the Home button and the swipe-up gesture come here", "pro botão Home e o gesto de deslizar virem pra cá"), 11.sp, pal.dim)
                }
                var i = 0
                while (i < order.size) {
                    val s = order[i]
                    if (s.half && i + 1 < order.size && order[i + 1].half) {
                        val pair = listOf(s, order[i + 1])
                        Row(Modifier.fillMaxWidth().zIndex(if (pair.any { it.key == dragKey }) 1f else 0f)) {
                            Placed(pair[0], Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Placed(pair[1], Modifier.weight(1f))
                        }
                        i += 2
                    } else {
                        Placed(s, Modifier.fillMaxWidth().zIndex(if (s.key == dragKey) 1f else 0f))
                        i++
                    }
                }
                Spacer(Modifier.height(120.dp))       // room to see the statue under the widgets
            }
            TermBox("", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), onClick = onOpenSearch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("$ ", 18.sp, pal.dim)
                    Cursor()
                    Spacer(Modifier.weight(1f))
                    T(L("swipe up · all apps", "deslize pra cima · apps"), 11.sp, pal.dim)
                }
            }
        }
        if (menu) Menu(
            "launcher",
            listOf(
                L("launcher settings", "configurações do launcher") to onOpenSettings,
                L("system wallpaper", "papel de parede do sistema") to { openWallpaperPicker(ctx) },
                L("android settings", "configurações do android") to { openAndroidSettings(ctx) },
            ),
            onDismiss = { menu = false },
        )
        slotMenu?.let { index ->
            val list = slots
            if (index < list.size) {
                val s = list[index]
                Menu(
                    s.kind.title(L),
                    listOf(
                        (if (s.half) L("full width", "largura inteira") else L("half width", "meia largura")) to { prefs.setSlots(list.toMutableList().apply { set(index, s.copy(half = !s.half)) }) },
                        L("remove", "remover") to {
                            if (s.kind == Kind.ANDROID) host.remove(s.widgetId)
                            prefs.setSlots(list.toMutableList().apply { removeAt(index) })
                        },
                        L("launcher settings", "configurações do launcher") to onOpenSettings,
                    ),
                    onDismiss = { slotMenu = null },
                )
            }
        }
    }
}

/** The widget itself. Long press / drag is handled by the home screen, so widgets get no menu of their own. */
@Composable
fun SlotView(slot: Slot, repo: AppRepository, prefs: Prefs, host: WidgetHost, modifier: Modifier) {
    when (slot.kind) {
        Kind.CLOCK -> ClockWidget(prefs, modifier, null)
        Kind.WEATHER -> WeatherWidget(modifier, null)
        Kind.APPS -> AppsWidget(repo, modifier, null)
        Kind.BATTERY -> BatteryWidget(modifier, null)
        Kind.RESOURCES -> ResourcesWidget(modifier, null)
        Kind.FETCH -> FetchWidget(repo, prefs, modifier, null)
        Kind.TERMINAL -> TerminalWidget(repo, prefs, modifier, null)
        Kind.ANDROID -> AndroidWidget(host, slot, modifier, null)
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
    Box(Modifier.fillMaxSize().background(pal.scrim).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        TermBox(title, Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp), opaque = true) {
            items.forEach { (text, action) ->
                T("> $text", 16.sp, pal.fg, Modifier.fillMaxWidth().clickable { onDismiss(); action() }.padding(vertical = 10.dp))
            }
            T(LocalLang.current("> cancel", "> cancelar"), 16.sp, pal.dim, Modifier.fillMaxWidth().clickable(onClick = onDismiss).padding(vertical = 10.dp))
        }
    }
}
