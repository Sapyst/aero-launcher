package org.aero.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/** The home screen activity. Android shows it whenever Home is pressed (it has the HOME intent filter). */
class MainActivity : ComponentActivity() {
    private val homePresses = mutableIntStateOf(0)
    private lateinit var widgets: WidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // draw behind the status and navigation bars (the background goes edge to edge)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        onBackPressedDispatcher.addCallback(this) { }        // Back on the home screen does nothing
        val repo = AppRepository(this)
        val prefs = Prefs(this)
        widgets = WidgetHost(this)
        val art = AsciiArt.load(this, "eris.txt")
        setContent { AeroTheme { Launcher(repo, prefs, widgets, art, homePresses.intValue) } }
    }

    // widgets from other apps only update while the host is listening (= while we are visible)
    override fun onStart() { super.onStart(); runCatching { widgets.host.startListening() } }
    override fun onStop() { super.onStop(); runCatching { widgets.host.stopListening() } }

    @Deprecated("Android's widget binding/setup screens still answer through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        widgets.onResult(requestCode, resultCode)
    }

    // Home pressed while we are already in front: close panels and go back to the widgets
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePresses.intValue++
    }
}

@Composable
fun Launcher(repo: AppRepository, prefs: Prefs, widgets: WidgetHost, art: AsciiArt, homePresses: Int) {
    val activity = LocalContext.current as Activity
    val background by prefs.background
    var searching by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    LaunchedEffect(homePresses) { searching = false; settings = false }
    BackHandler(enabled = searching || settings) { searching = false; settings = false }
    // drawing our own OLED background: tell Android not to render the wallpaper under it at all
    LaunchedEffect(background) {
        if (background == "eris") activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        else activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    }
    Box(Modifier.fillMaxSize()) {
        Home(repo, prefs, widgets, art, homePresses, onOpenSearch = { searching = true }, onOpenSettings = { settings = true })
        AnimatedVisibility(
            visible = searching,
            enter = slideInVertically { it / 4 } + fadeIn(),
            exit = slideOutVertically { it / 4 } + fadeOut(),
        ) {
            SearchPanel(repo, prefs, onClose = { searching = false })
        }
        AnimatedVisibility(visible = settings, enter = fadeIn(), exit = fadeOut()) {
            SettingsPanel(prefs, widgets, onClose = { settings = false })
        }
    }
}
