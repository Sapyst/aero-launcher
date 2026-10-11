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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.Canvas

/**
 * The launcher's own settings (long press on the home screen → launcher settings):
 * which widgets, in which order, half or full width; Android widgets; background; clock; search; fetch.
 */
@Composable
fun SettingsPanel(prefs: Prefs, host: WidgetHost, isDefault: Boolean, onMakeDefault: () -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val L = LocalLang.current
    val pal = LocalPalette.current
    val items by prefs.items
    val cols by prefs.gridCols
    var picking by remember { mutableStateOf(false) }


    Box(Modifier.fillMaxSize().glassScrim()) {
        LazyColumn(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("$ aero-launcher --settings", 16.sp)
                    Spacer(Modifier.weight(1f))
                    T(L("[ close ]", "[ fechar ]"), 14.sp, pal.dim, Modifier.clickable(onClick = onClose).padding(6.dp))
                }
            }
            item {
                TermBox(L("home screen", "tela inicial"), Modifier.fillMaxWidth()) {
                    if (isDefault) T(L("[x] aero launcher is the phone's home screen", "[x] o aero launcher é a tela inicial do celular"), 14.sp)
                    else T(L("[ ] not the home screen yet · tap to set", "[ ] ainda não é a tela inicial · toque pra definir"), 14.sp, pal.fg, Modifier.fillMaxWidth().clickable(onClick = onMakeDefault).padding(vertical = 6.dp))
                    T(L("> android's \"default home app\" page", "> página \"app de início padrão\" do android"), 13.sp, pal.dim, Modifier.fillMaxWidth().clickable { openHomeSettings(ctx) }.padding(vertical = 6.dp))
                }
            }
            item {
                val theme by prefs.theme
                val lang by prefs.lang
                TermBox(L("appearance", "aparência"), Modifier.fillMaxWidth()) {
                    T(L("theme", "tema"), 12.sp, pal.dim)
                    Choice(L("system (follows the phone)", "sistema (segue o celular)"), theme == "system") { prefs.setTheme("system") }
                    Choice(L("dark · oled black", "escuro · preto oled"), theme == "dark") { prefs.setTheme("dark") }
                    Choice(L("light", "claro"), theme == "light") { prefs.setTheme("light") }
                    T(L("language", "idioma"), 12.sp, pal.dim, Modifier.padding(top = 8.dp))
                    Choice("english", lang == "en") { prefs.setLang("en") }
                    Choice("português", lang == "pt") { prefs.setLang("pt") }
                }
            }
            item {
                val opacity by prefs.opacity
                val blur by prefs.blur
                val font by prefs.font
                val textScale by prefs.textScale
                val iconScale by prefs.iconScale
                TermBox(L("look", "visual"), Modifier.fillMaxWidth()) {
                    Slider(L("box opacity", "opacidade das caixas"), opacity, 0.15f..1f, 0.05f, { "${(it * 100).roundToInt()}%" }) { prefs.setOpacity(it) }
                    Toggle(L("frosted glass (blur what is behind)", "vidro fosco (desfoca o que está atrás)"), blur) { prefs.setBlur(!blur) }
                    T(L("font", "fonte"), 12.sp, pal.dim, Modifier.padding(top = 8.dp))
                    listOf("jetbrains" to "jetbrains mono", "monospace" to L("system mono", "mono do sistema"),
                        "sans" to L("system sans", "sans do sistema"), "serif" to "serif").forEach { (id, name) ->
                        Choice(name, font == id) { prefs.setFont(id) }
                    }
                    Slider(L("text size", "tamanho do texto"), textScale, 0.75f..1.4f, 0.05f, { "${(it * 100).roundToInt()}%" }) { prefs.setTextScale(it) }
                    Slider(L("icon size", "tamanho dos ícones"), iconScale, 0.6f..1.5f, 0.05f, { "${(it * 100).roundToInt()}%" }) { prefs.setIconScale(it) }
                }
            }
            item {
                val rowsManual by prefs.rowsManual
                val rowsNow by prefs.gridRows
                val pages by prefs.pages
                val showPrompt by prefs.showPrompt
                TermBox(L("home grid", "grade da home"), Modifier.fillMaxWidth()) {
                    Slider(L("columns", "colunas"), cols.toFloat(), 3f..6f, 1f, { it.roundToInt().toString() }) { prefs.setGridCols(it.roundToInt()) }
                    Toggle(L("rows: as many as fit", "linhas: quantas couberem"), rowsManual == 0) {
                        prefs.setRowsManual(if (rowsManual == 0) rowsNow else 0)
                    }
                    if (rowsManual > 0) Slider(L("rows", "linhas"), rowsManual.toFloat(), 3f..12f, 1f, { it.roundToInt().toString() }) { prefs.setRowsManual(it.roundToInt()) }
                    T(L("workspaces: $pages · long press an empty spot on the home for new / delete",
                        "áreas de trabalho: $pages · segure um espaço vazio na home pra criar / apagar"), 11.sp, pal.dim, Modifier.padding(vertical = 6.dp))
                    Toggle(L("show the \$ bar", "mostrar a barra \$"), showPrompt) { prefs.setShowPrompt(!showPrompt) }
                }
            }
            item {
                val widgets = items.filter { it.kind != Kind.APP }.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))
                TermBox(L("home widgets · ${widgets.size}", "widgets da home · ${widgets.size}"), Modifier.fillMaxWidth()) {
                    if (widgets.isEmpty()) T(L("no widgets on the home screen: add one below", "nenhum widget na home: adicione abaixo"), 12.sp, pal.dim)
                    widgets.forEach { w ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val name = if (w.kind == Kind.ANDROID)
                                host.manager.getAppWidgetInfo(w.widgetId)?.let { host.label(it).lowercase() } ?: "widget (gone)"
                            else w.kind.title(L)
                            T(name, 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            T(L("page", "pág.") + " ${w.page + 1} · ${w.w}×${w.h}", 11.sp, pal.dim)
                            Btn("×") {
                                if (w.kind == Kind.ANDROID) host.remove(w.widgetId)
                                prefs.setItems(items.filter { it.id != w.id })
                            }
                        }
                    }
                    T(L("on the home: long press + drag to move · long press = resize / remove",
                        "na home: segure + arraste pra mover · segure = tamanho / remover"), 11.sp, pal.dim, Modifier.padding(top = 4.dp))
                }
            }
            item {
                TermBox(L("add a widget", "adicionar widget"), Modifier.fillMaxWidth()) {
                    Kind.entries.filter { it != Kind.ANDROID && it != Kind.APP && items.none { s -> s.kind == it } }.forEach { k ->
                        Column(Modifier.fillMaxWidth().clickable { prefs.addItem(Item.widget(k, cols)) }.padding(vertical = 6.dp)) {
                            T("> ${k.title(L)}", 15.sp)
                            T("  ${k.about(L)}", 11.sp, pal.dim)
                        }
                    }
                    Column(Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 6.dp)) {
                        T(L("> android widget…", "> widget android…"), 15.sp)
                        T(L("  from another app: music, calendar, notes…", "  de outro app: música, agenda, notas…"), 11.sp, pal.dim)
                    }
                }
            }
            item {
                val bg by prefs.background
                val shimmer by prefs.shimmer
                TermBox(L("background", "fundo"), Modifier.fillMaxWidth()) {
                    Choice(L("eris ascii · drawn by the launcher", "éris ascii · desenhada pelo launcher"), bg == "eris") { prefs.setBackground("eris") }
                    Choice(L("system wallpaper", "papel de parede do sistema"), bg == "wallpaper") { prefs.setBackground("wallpaper") }
                    Toggle(L("shimmer (characters flicker like a live terminal)", "brilho (caracteres piscam como terminal vivo)"), shimmer) { prefs.setShimmer(!shimmer) }
                }
            }
            item {
                val seconds by prefs.seconds
                TermBox(Kind.CLOCK.title(L), Modifier.fillMaxWidth()) {
                    Toggle(L("show seconds", "mostrar segundos"), seconds) { prefs.setSeconds(!seconds) }
                }
            }
            item {
                val engine by prefs.engine
                TermBox(L("web search", "busca na web"), Modifier.fillMaxWidth()) {
                    ENGINES.keys.forEach { e -> Choice(e, engine == e) { prefs.setEngine(e) } }
                    T(L("opens in your default browser", "abre no seu navegador padrão"), 11.sp, pal.dim, Modifier.padding(top = 4.dp))
                }
            }
            item {
                val user by prefs.fetchUser
                TermBox("fetch", Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(L("name  ", "nome  "), 14.sp, pal.dim)
                        BasicTextField(
                            value = user,
                            onValueChange = { prefs.setFetchUser(it.filter { c -> !c.isWhitespace() }.take(20)) },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = LocalFont.current, fontSize = 14.sp, color = pal.fg),
                            cursorBrush = SolidColor(pal.fg),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    T(L("shown as name@phone at the top of fetch", "aparece como nome@celular no topo do fetch"), 11.sp, pal.dim, Modifier.padding(top = 4.dp))
                    val logo by prefs.fetchLogo
                    val invert by prefs.fetchInvert
                    val scope = rememberCoroutineScope()
                    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                        if (uri != null) scope.launch {
                            withContext(Dispatchers.IO) { saveLogo(ctx, uri) }?.let { prefs.setFetchLogo(it) }
                        }
                    }
                    T(L("logo", "logo"), 12.sp, pal.dim, Modifier.padding(top = 10.dp))
                    T(L("> pick an image (turned into ascii)", "> escolher uma imagem (vira ascii)"), 14.sp, pal.fg,
                        Modifier.fillMaxWidth().clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.padding(vertical = 7.dp))
                    if (logo.isNotEmpty()) {
                        Toggle(L("invert (for pictures on a light background)", "inverter (pra fotos de fundo claro)"), invert) { prefs.setFetchInvert(!invert) }
                        T(L("> back to the robot", "> voltar pro robô"), 14.sp, pal.dim,
                            Modifier.fillMaxWidth().clickable { prefs.setFetchLogo("") }.padding(vertical = 7.dp))
                    }
                }
            }
            item {
                TermBox(L("about", "sobre"), Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    T("aero launcher ${BuildConfig.VERSION_NAME}", 14.sp)
                    T(L("a terminal-style home screen · same look as arch aero os", "uma tela inicial estilo terminal · mesma cara do arch aero os"), 11.sp, pal.dim)
                    T("github.com/Sapyst/aero-launcher", 11.sp, pal.dim)
                }
            }
        }
        if (picking) WidgetPicker(host, onPick = { info ->
            picking = false
            host.add(info) { id, picked ->
                // its size in cells: what the widget asks for (Android 12+), else from its minimum size
                val cell = 80 * host.density()
                val w = if (android.os.Build.VERSION.SDK_INT >= 31 && picked.targetCellWidth > 0) picked.targetCellWidth
                        else Math.ceil(picked.minWidth / cell.toDouble()).toInt()
                val h = if (android.os.Build.VERSION.SDK_INT >= 31 && picked.targetCellHeight > 0) picked.targetCellHeight
                        else Math.ceil(picked.minHeight / cell.toDouble()).toInt()
                prefs.addItem(Item.android(id, w.coerceIn(1, prefs.gridCols.value), h.coerceIn(1, 4)))
            }
        }, onClose = { picking = false })
    }
}

@Composable
private fun WidgetPicker(host: WidgetHost, onPick: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit) {
    val pal = LocalPalette.current
    val providers = remember { host.providers() }
    Box(Modifier.fillMaxSize().glassScrim()) {
        LazyColumn(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    T("$ widgets --list · ${providers.size}", 16.sp)
                    Spacer(Modifier.weight(1f))
                    T(LocalLang.current("[ back ]", "[ voltar ]"), 14.sp, pal.dim, Modifier.clickable(onClick = onClose).padding(6.dp))
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

/** A slider drawn like the rest: a thin line, the filled part bright, a round knob. Drag or tap. */
@Composable
private fun Slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float,
                   format: (Float) -> String, onChange: (Float) -> Unit) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        T(label, 13.sp, pal.fg, Modifier.weight(1f))
        T(format(value), 13.sp, pal.dim)
    }
    Canvas(
        Modifier.fillMaxWidth().height(30.dp).pointerInput(range, step) {
            fun pick(x: Float) {
                val f = (x / size.width).coerceIn(0f, 1f)
                val raw = range.start + f * (range.endInclusive - range.start)
                onChange(((raw / step).roundToInt() * step).coerceIn(range.start, range.endInclusive))
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume(); pick(down.position.x)
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    c.consume(); pick(c.position.x)
                }
            }
        }
    ) {
        val f = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        val y = size.height / 2
        val r = 7.dp.toPx()
        drawLine(pal.line, Offset(r, y), Offset(size.width - r, y), 2.dp.toPx())
        drawLine(pal.fg, Offset(r, y), Offset(r + f * (size.width - 2 * r), y), 2.dp.toPx())
        drawCircle(pal.bg, r, Offset(r + f * (size.width - 2 * r), y))
        drawCircle(pal.fg, r, Offset(r + f * (size.width - 2 * r), y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}
