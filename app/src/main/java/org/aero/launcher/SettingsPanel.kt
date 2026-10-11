package org.aero.launcher

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The launcher's own settings (long press on the home screen → launcher settings):
 * which widgets, in which order, half or full width; Android widgets; background; clock; search; fetch.
 */
@Composable
fun SettingsPanel(prefs: Prefs, host: WidgetHost, onClose: () -> Unit) {
    val pal = LocalPalette.current
    val slots by prefs.slots
    var picking by remember { mutableStateOf(false) }

    fun update(f: MutableList<Slot>.() -> Unit) = prefs.setSlots(slots.toMutableList().apply(f))

    Box(Modifier.fillMaxSize().background(pal.scrim)) {
        LazyColumn(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("$ aero-launcher --settings", 16.sp)
                    Spacer(Modifier.weight(1f))
                    T("[ close ]", 14.sp, pal.dim, Modifier.clickable(onClick = onClose).padding(6.dp))
                }
            }
            item {
                TermBox("home widgets · ${slots.size}", Modifier.fillMaxWidth()) {
                    if (slots.isEmpty()) T("nothing on the home screen: add something below", 12.sp, pal.dim)
                    slots.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val name = if (s.kind == Kind.ANDROID)
                                host.manager.getAppWidgetInfo(s.widgetId)?.let { host.label(it).lowercase() } ?: "widget (gone)"
                            else s.kind.title
                            T("${i + 1}. $name", 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Btn("↑") { if (i > 0) update { add(i - 1, removeAt(i)) } }
                            Btn("↓") { if (i < slots.size - 1) update { add(i + 1, removeAt(i)) } }
                            Btn(if (s.half) "½" else "1") { update { set(i, s.copy(half = !s.half)) } }
                            if (s.kind == Kind.ANDROID) {
                                Btn("−") { update { set(i, s.copy(heightDp = (s.heightDp - 30).coerceAtLeast(60))) } }
                                Btn("+") { update { set(i, s.copy(heightDp = (s.heightDp + 30).coerceAtMost(600))) } }
                            }
                            Btn("×") {
                                if (s.kind == Kind.ANDROID) host.remove(s.widgetId)
                                update { removeAt(i) }
                            }
                        }
                    }
                    T("↑↓ order · ½/1 half or full width (two halves share a row) · × remove", 11.sp, pal.dim, Modifier.padding(top = 4.dp))
                }
            }
            item {
                TermBox("add a widget", Modifier.fillMaxWidth()) {
                    Kind.entries.filter { it != Kind.ANDROID && slots.none { s -> s.kind == it } }.forEach { k ->
                        Column(Modifier.fillMaxWidth().clickable { update { add(Slot(k)) } }.padding(vertical = 6.dp)) {
                            T("> ${k.title}", 15.sp)
                            T("  ${k.about}", 11.sp, pal.dim)
                        }
                    }
                    Column(Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 6.dp)) {
                        T("> android widget…", 15.sp)
                        T("  from another app: music, calendar, notes…", 11.sp, pal.dim)
                    }
                }
            }
            item {
                val bg by prefs.background
                val shimmer by prefs.shimmer
                TermBox("background", Modifier.fillMaxWidth()) {
                    Choice("eris ascii · oled black", bg == "eris") { prefs.setBackground("eris") }
                    Choice("system wallpaper", bg == "wallpaper") { prefs.setBackground("wallpaper") }
                    Toggle("shimmer (characters flicker like a live terminal)", shimmer) { prefs.setShimmer(!shimmer) }
                }
            }
            item {
                val seconds by prefs.seconds
                TermBox("clock", Modifier.fillMaxWidth()) {
                    Toggle("show seconds", seconds) { prefs.setSeconds(!seconds) }
                }
            }
            item {
                val engine by prefs.engine
                TermBox("web search", Modifier.fillMaxWidth()) {
                    ENGINES.keys.forEach { e -> Choice(e, engine == e) { prefs.setEngine(e) } }
                    T("opens in your default browser", 11.sp, pal.dim, Modifier.padding(top = 4.dp))
                }
            }
            item {
                val user by prefs.fetchUser
                TermBox("fetch", Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("name  ", 14.sp, pal.dim)
                        BasicTextField(
                            value = user,
                            onValueChange = { prefs.setFetchUser(it.filter { c -> !c.isWhitespace() }.take(20)) },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = pal.fg),
                            cursorBrush = SolidColor(pal.fg),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    T("shown as name@phone at the top of fetch", 11.sp, pal.dim, Modifier.padding(top = 4.dp))
                }
            }
            item {
                TermBox("about", Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    T("aero launcher ${BuildConfig.VERSION_NAME}", 14.sp)
                    T("a terminal-style home screen · same look as arch aero os", 11.sp, pal.dim)
                }
            }
        }
        if (picking) WidgetPicker(host, onPick = { info ->
            picking = false
            host.add(info) { id, picked ->
                val minH = (picked.minHeight / host.density()).toInt().coerceIn(80, 400)
                prefs.setSlots(prefs.slots.value + Slot(Kind.ANDROID, widgetId = id, heightDp = minH + 20))
            }
        }, onClose = { picking = false })
    }
}

@Composable
private fun WidgetPicker(host: WidgetHost, onPick: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit) {
    val pal = LocalPalette.current
    val providers = remember { host.providers() }
    Box(Modifier.fillMaxSize().background(pal.scrim)) {
        LazyColumn(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    T("$ widgets --list · ${providers.size}", 16.sp)
                    Spacer(Modifier.weight(1f))
                    T("[ back ]", 14.sp, pal.dim, Modifier.clickable(onClick = onClose).padding(6.dp))
                }
            }
            items(providers) { info ->
                Column(Modifier.fillMaxWidth().clickable { onPick(info) }.padding(vertical = 8.dp)) {
                    T("> ${host.label(info).lowercase()}", 14.sp, maxLines = 1)
                    T("  ${info.targetCellWidthOrMin()}", 11.sp, pal.dim)
                }
            }
        }
    }
}

private fun AppWidgetProviderInfo.targetCellWidthOrMin(): String =
    if (android.os.Build.VERSION.SDK_INT >= 31 && targetCellWidth > 0) "${targetCellWidth}x$targetCellHeight cells"
    else "min ${minWidth}x$minHeight px"

private fun WidgetHost.density() = manager.let { android.content.res.Resources.getSystem().displayMetrics.density }

@Composable
private fun Btn(text: String, onClick: () -> Unit) {
    T(text, 15.sp, LocalPalette.current.fg, Modifier.clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 4.dp))
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    T((if (selected) "(•) " else "( ) ") + text, 14.sp, if (selected) pal.fg else pal.dim,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp))
}

@Composable
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    T((if (on) "[x] " else "[ ] ") + text, 14.sp, if (on) pal.fg else pal.dim,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp))
}
