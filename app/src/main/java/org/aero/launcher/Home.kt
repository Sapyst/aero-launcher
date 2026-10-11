package org.aero.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import kotlinx.coroutines.delay

/**
 * The home screen: the background (the ASCII statue on OLED black, or the system wallpaper), the
 * chosen widgets (they scroll if there are many) and the `$` prompt.
 * Dragging DOWN when the widgets are at the top = notifications; dragging UP at the bottom = search.
 * Long press on empty space = launcher menu; long press on a widget = that widget's menu.
 */
@Composable
fun Home(
    repo: AppRepository, prefs: Prefs, host: WidgetHost, art: AsciiArt, homePresses: Int,
    onOpenSearch: () -> Unit, onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val background by prefs.background
    val shimmer by prefs.shimmer
    val slots by prefs.slots
    val threshold = with(LocalDensity.current) { 70.dp.toPx() }
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

    Box(Modifier.fillMaxSize()) {
        if (background == "eris") AsciiBackground(art, shimmer)
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {      // imePadding: the keyboard pushes the home up (terminal)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(edgePull)
                    .verticalScroll(scroll)
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { menu = true }) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                var i = 0
                while (i < slots.size) {
                    val s = slots[i]
                    val a = i
                    if (s.half && i + 1 < slots.size && slots[i + 1].half) {
                        Row(Modifier.fillMaxWidth()) {
                            SlotView(slots[a], repo, prefs, host, Modifier.weight(1f)) { slotMenu = a }
                            Spacer(Modifier.width(10.dp))
                            SlotView(slots[a + 1], repo, prefs, host, Modifier.weight(1f)) { slotMenu = a + 1 }
                        }
                        i += 2
                    } else {
                        SlotView(s, repo, prefs, host, Modifier.fillMaxWidth()) { slotMenu = a }
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
                    T("swipe up · all apps", 11.sp, pal.dim)
                }
            }
        }
        if (menu) Menu(
            "launcher",
            listOf(
                "launcher settings" to onOpenSettings,
                "system wallpaper" to { openWallpaperPicker(ctx) },
                "android settings" to { openAndroidSettings(ctx) },
            ),
            onDismiss = { menu = false },
        )
        slotMenu?.let { index ->
            val list = slots
            if (index < list.size) {
                val s = list[index]
                Menu(
                    s.kind.title,
                    listOf(
                        "move up" to { if (index > 0) prefs.setSlots(list.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                        "move down" to { if (index < list.size - 1) prefs.setSlots(list.toMutableList().apply { add(index + 1, removeAt(index)) }) },
                        (if (s.half) "full width" else "half width") to { prefs.setSlots(list.toMutableList().apply { set(index, s.copy(half = !s.half)) }) },
                        "remove" to {
                            if (s.kind == Kind.ANDROID) host.remove(s.widgetId)
                            prefs.setSlots(list.toMutableList().apply { removeAt(index) })
                        },
                        "launcher settings" to onOpenSettings,
                    ),
                    onDismiss = { slotMenu = null },
                )
            }
        }
    }
}

@Composable
fun SlotView(slot: Slot, repo: AppRepository, prefs: Prefs, host: WidgetHost, modifier: Modifier, onMenu: () -> Unit) {
    when (slot.kind) {
        Kind.CLOCK -> ClockWidget(prefs, modifier, onMenu)
        Kind.WEATHER -> WeatherWidget(modifier, onMenu)
        Kind.APPS -> AppsWidget(repo, modifier, onMenu)
        Kind.BATTERY -> BatteryWidget(modifier, onMenu)
        Kind.RESOURCES -> ResourcesWidget(modifier, onMenu)
        Kind.FETCH -> FetchWidget(repo, prefs, modifier, onMenu)
        Kind.TERMINAL -> TerminalWidget(repo, prefs, modifier, onMenu)
        Kind.ANDROID -> AndroidWidget(host, slot, modifier, onMenu)
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
            T("> cancel", 16.sp, pal.dim, Modifier.fillMaxWidth().clickable(onClick = onDismiss).padding(vertical = 10.dp))
        }
    }
}
