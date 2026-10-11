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

/**
 * Swipe up: the search. Typing filters the apps live; Enter opens the first one (or searches the
 * web when nothing matches). With no text it lists every app, A to Z, with letter dividers.
 * Long press on an app: pin / app info / uninstall. Back, or pulling the list down at its top, closes it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchPanel(repo: AppRepository, prefs: Prefs, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<App?>(null) }
    val all by repo.apps
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

    Box(Modifier.fillMaxSize().background(pal.scrim)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            TermBox("search", Modifier.fillMaxWidth()) {
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
                    item { T("all apps · ${all.size}", 12.sp, pal.dim, Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                    var letter = ' '
                    all.forEach { app ->
                        val first = app.label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
                        if (first != letter) {
                            letter = first
                            item(key = "h$first") {
                                T("── ${first.lowercaseChar()} " + "─".repeat(24), 12.sp, pal.dim, Modifier.padding(top = 10.dp, bottom = 2.dp), maxLines = 1)
                            }
                        }
                        item(key = app.key) { AppRow(app, false, { open(app) }, { menuFor = app }) }
                    }
                } else {
                    item {
                        TermBox("apps · ${results.size}", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            if (results.isEmpty()) T("no app matches", 14.sp, pal.dim)
                            results.take(12).forEachIndexed { i, app -> AppRow(app, i == 0, { open(app) }, { menuFor = app }) }
                        }
                    }
                    item {
                        TermBox("web", Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { web() }) {
                            T("> search \"${query.removePrefix("web ").trim()}\" on the web", 14.sp, pal.dim)
                        }
                    }
                }
            }
        }
        menuFor?.let { app ->
            Menu(
                app.label,
                listOf(
                    (if (repo.isPinned(app)) "unpin from home" else "pin to home") to { repo.togglePin(app) },
                    "app info" to { repo.openInfo(app) },
                    "uninstall" to { repo.uninstall(app) },
                ),
                onDismiss = { menuFor = null },
            )
        }
    }
}

/** "> name" — the first search result is highlighted (that is what Enter opens). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(app: App, highlighted: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    val pal = LocalPalette.current
    val mod = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onMenu).padding(vertical = 7.dp)
    Box(mod) {
        if (highlighted)
            T(" > ${app.label} ", 15.sp, pal.fill.copy(alpha = 1f), Modifier.background(pal.fg, RoundedCornerShape(3.dp)), maxLines = 1)
        else
            T("> ${app.label}", 15.sp, pal.fg, maxLines = 1)
    }
}
