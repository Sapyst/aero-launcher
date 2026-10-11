package org.aero.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/*
 * A small terminal on the home screen. Launcher commands (open, web, fetch, apps...) plus Android's
 * own shell (toybox: ls, cat, uname, getprop, df, date...). The shell runs INSIDE the launcher's
 * sandbox: it sees what the launcher may see, nothing more (no root, other apps' files are closed).
 */

private const val HELP = """launcher commands:
  open <app>     open an app (same search as the list)
  web <text>     search the web
  fetch          this phone, fastfetch-style
  apps           how many apps are installed
  clear          clear the screen
anything else runs in Android's shell, e.g.
  uname -a · getprop ro.product.model · df -h /data · date"""

private const val HELP_PT = """comandos do launcher:
  open <app>     abre um app (mesma busca da lista)
  web <texto>    busca na web
  fetch          este celular, estilo fastfetch
  apps           quantos apps estão instalados
  clear          limpa a tela
o resto roda no shell do Android, ex.:
  uname -a · getprop ro.product.model · df -h /data · date"""

@Composable
fun TerminalWidget(repo: AppRepository, prefs: Prefs, modifier: Modifier, onMenu: (() -> Unit)?) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val L = LocalLang.current
    val out = remember { mutableStateListOf(L("aero terminal · type help", "aero terminal · digite help")) }
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val battery by rememberBattery()
    val imeTarget = LocalImeTarget.current
    var fieldBottom by remember { mutableStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    // while typing, tell the home where we are, so it lifts us above the keyboard
    LaunchedEffect(focused, fieldBottom) { imeTarget.bottom = if (focused) fieldBottom else 0f }
    LaunchedEffect(out.size) { scroll.animateScrollTo(scroll.maxValue) }

    fun print(text: String) { text.trimEnd().lines().forEach { out.add(it) }; while (out.size > 300) out.removeAt(0) }

    fun run(line: String) {
        val cmd = line.trim()
        out.add("$ $cmd")
        val word = cmd.substringBefore(' ')
        val rest = cmd.substringAfter(' ', "").trim()
        when (word) {
            "" -> {}
            "help" -> print(L(HELP, HELP_PT))
            "clear" -> out.clear()
            "apps" -> print("${repo.apps.value.size} apps")
            "fetch" -> fetchLines(ctx, prefs.fetchUser.value, repo.apps.value.size, battery).forEach { (k, v) ->
                print(if (v.isEmpty()) k else k.padEnd(9) + v)
            }
            "web" -> if (rest.isEmpty()) print(L("usage: web <text>", "uso: web <texto>")) else { webSearch(ctx, rest, prefs.engine.value); print(L("opening the browser…", "abrindo o navegador…")) }
            "open" -> {
                val app = rank(repo.apps.value, rest).firstOrNull()
                if (app == null) print(L("no app matches \"$rest\"", "nenhum app encontrado: \"$rest\"")) else { repo.launch(app); print(L("opening ${app.label}…", "abrindo ${app.label}…")) }
            }
            else -> scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
                        val text = p.inputStream.bufferedReader().readText()
                        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroy(); "$text\n[timed out]" } else text
                    }.getOrElse { "error: ${it.message}" }
                }
                print(result.take(4000).ifBlank { "" })
            }
        }
    }

    TermBox("terminal", modifier, onLongClick = onMenu) {
        Box(Modifier.fillMaxWidth().weight(1f, fill = true).heightIn(min = 60.dp)) {
            Column(Modifier.verticalScroll(scroll)) { out.forEach { T(it, 11.sp, if (it.startsWith("$ ")) pal.fg else pal.dim) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("$ ", 14.sp, pal.dim)
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = pal.fg),
                cursorBrush = SolidColor(pal.fg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { run(input); input = "" }),
                modifier = Modifier.weight(1f)
                    .onFocusChanged { focused = it.isFocused }
                    .onGloballyPositioned { fieldBottom = it.boundsInWindow().bottom },
            )
        }
    }
}
