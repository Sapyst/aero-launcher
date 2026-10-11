package org.aero.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture

/**
 * Swipe up: the search. Typing filters the apps live; Enter opens the first one (or searches the
 * web when nothing matches). With no text it lists every app, A to Z, with letter dividers.
 * Long press on an app: pin / app info / uninstall. Back, or pulling the list down at its top, closes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchPanel(
    repo: AppRepository, prefs: Prefs, icons: IconCache, drag: DragState,
    hidden: Boolean, onDragOut: () -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<App?>(null) }
    val all by repo.apps
    val L = LocalLang.current
    val results = remember(query, all) { rank(all, query) }

    fun open(app: App) { repo.launch(app); onClose() }
    fun web() { webSearch(ctx, query.removePrefix("web ").trim(), prefs.engine.value); onClose() }

    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }

    // pulling DOWN while the list is already at the top closes the panel
    val listState = rememberLazyListState()
    val pullToClose = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0) { pulled += available.y; if (pulled > 180f) { pulled = 0f; onClose() } } else pulled = 0f
                return Offset.Zero
            }
        }
    }

    // while an app is being dragged out to the home, the list stays (it still owns the finger) but invisible
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // dragging an app out: keyboard away first, then the home shows up under the finger
    val dragOut = { keyboard?.hide(); focusManager.clearFocus(); onDragOut() }
    fun row(app: App, highlighted: Boolean): @Composable () -> Unit = {
        AppRow(app, highlighted, icons, drag, { open(app) }, { menuFor = app }, dragOut)
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden) 0f else 1f }.background(pal.scrim)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TermBox(L("search", "busca"), Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("$ ", 18.sp, pal.dim)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Mono, fontSize = 18.sp, color = pal.fg),
                        cursorBrush = SolidColor(pal.fg),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val first = results.firstOrNull()
                            if (query.startsWith("web ") || first == null) web() else open(first)
                        }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                }
            }
            LazyColumn(Modifier.weight(1f).nestedScroll(pullToClose), state = listState) {
                if (query.isBlank()) {
                    item { T(L("all apps · ${all.size}", "todos os apps · ${all.size}"), 12.sp, pal.dim, Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                    var letter = ' '
                    all.forEach { app ->
                        val first = app.label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
                        if (first != letter) {
                            letter = first
                            item(key = "h$first") {
                                T("── ${first.lowercaseChar()} " + "─".repeat(24), 12.sp, pal.dim, Modifier.padding(top = 10.dp, bottom = 2.dp), maxLines = 1)
                            }
                        }
                        item(key = app.key) { row(app, false)() }
                    }
                } else {
                    item {
                        TermBox("apps · ${results.size}", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            if (results.isEmpty()) T(L("no app matches", "nenhum app encontrado"), 14.sp, pal.dim)
                            results.take(12).forEachIndexed { i, app -> row(app, i == 0)() }
                        }
                    }
                    item {
                        TermBox("web", Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { web() }) {
                            T(L("> search \"${query.removePrefix("web ").trim()}\" on the web", "> buscar \"${query.removePrefix("web ").trim()}\" na web"), 14.sp, pal.dim)
                        }
                    }
                }
            }
        }
        menuFor?.let { app ->
            Menu(
                app.label,
                listOf(
                    L("add to home", "adicionar à home") to { prefs.addItem(Item.app(app)) },
                    (if (repo.isPinned(app)) L("unpin from the apps widget", "tirar do widget apps") else L("pin to the apps widget", "fixar no widget apps")) to { repo.togglePin(app) },
                    L("app info", "informações do app") to { repo.openInfo(app) },
                    L("uninstall", "desinstalar") to { repo.uninstall(app) },
                ),
                onDismiss = { menuFor = null },
            )
        }
    }
}

/**
 * One app in the list: black-and-white icon + name; the first search result is highlighted (that is
 * what Enter opens). Tap = open. Long press and DRAG = take it to the home screen (the list hides,
 * the icon follows the finger and snaps to the home grid). Long press and let go = its menu.
 */
@Composable
fun AppRow(
    app: App, highlighted: Boolean, icons: IconCache, drag: DragState,
    onOpen: () -> Unit, onMenu: () -> Unit, onDragOut: () -> Unit,
) {
    val pal = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val gesture = Modifier.pointerInput(app.key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture   // tap or scroll: not ours
            press.consume()
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            fun window(p: Offset) = coords?.takeIf { it.isAttached }?.localToWindow(p) ?: p
            drag.isNew = true
            drag.moved = 0f
            drag.grab = drag.cell / 2f
            drag.finger = window(press.position)
            drag.item = Item.app(app)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                drag.moved += (change.position - change.previousPosition).getDistance()
                if (drag.moved > 24f) onDragOut()                    // really dragging: show the home
                drag.finger = window(change.position)
            }
            if (drag.moved > 24f) drag.onRelease() else { drag.item = null; onMenu() }
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { coords = it }
            .clickable(onClick = onOpen)
            .then(gesture)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoIcon(icons, app, 30.dp)
        Spacer(Modifier.width(12.dp))
        if (highlighted)
            T(" ${app.label} ", 15.sp, pal.fill.copy(alpha = 1f), Modifier.background(pal.fg, RoundedCornerShape(3.dp)), maxLines = 1)
        else
            T(app.label, 15.sp, pal.fg, maxLines = 1)
    }
}
