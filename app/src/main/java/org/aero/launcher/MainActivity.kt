package org.aero.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The home screen activity. Android shows it whenever Home is pressed (it has the HOME intent filter). */
class MainActivity : ComponentActivity() {
    private val homePresses = mutableIntStateOf(0)
    private val isDefault = mutableStateOf(true)
    private lateinit var widgets: WidgetHost
    private lateinit var prefs: Prefs
    private var askedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // draw behind the status and navigation bars (the background goes edge to edge)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        onBackPressedDispatcher.addCallback(this) { }        // Back on the home screen does nothing
        val repo = AppRepository(this)
        prefs = Prefs(this)
        widgets = WidgetHost(this)
        val art = AsciiArt.load(this, "eris.txt")
        setContent {
            AeroTheme(prefs.theme.value, prefs.lang.value, prefs.opacity.value, prefs.font.value, prefs.textScale.value) {
                // status/navigation bar icons: light on the dark theme, dark on the light one (even when forced)
                val dark = isDark()
                LaunchedEffect(dark) {
                    val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                }
                Launcher(repo, prefs, widgets, art, homePresses.intValue, isDefault.value, ::requestDefaultHome)
            }
        }
    }

    /**
     * Asks to become the phone's home screen with Android's own dialog (the HOME "role", Android 10+).
     * If Android will not show the dialog (refused too many times, or an old Android), opens the
     * "Default home app" settings page instead.
     */
    fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                askedAt = SystemClock.elapsedRealtime()
                @Suppress("DEPRECATION") startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
                return
            }
        }
        openHomeSettings(this)
    }

    override fun onResume() {
        super.onResume()
        isDefault.value = isDefaultHome(this)
        if (!isDefault.value && !prefs.askedHome) { prefs.setAskedHome(); requestDefaultHome() }
    }

    // widgets from other apps only update while the host is listening (= while we are visible)
    override fun onStart() { super.onStart(); runCatching { widgets.host.startListening() } }
    override fun onStop() { super.onStop(); runCatching { widgets.host.stopListening() } }

    @Deprecated("Android's widget binding/setup screens still answer through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        widgets.onResult(requestCode, resultCode)
        if (requestCode == REQ_ROLE) {
            isDefault.value = isDefaultHome(this)
            // answered "no" in a blink = Android did not even show its dialog: send to the settings page
            if (!isDefault.value && SystemClock.elapsedRealtime() - askedAt < 600) openHomeSettings(this)
        }
    }

    companion object { const val REQ_ROLE = 4201 }

    // Home pressed while we are already in front: close panels and go back to the widgets
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePresses.intValue++
    }
}

@Composable
fun Launcher(
    repo: AppRepository, prefs: Prefs, widgets: WidgetHost, art: AsciiArt, homePresses: Int,
    isDefault: Boolean, onMakeDefault: () -> Unit,
) {
    val activity = LocalContext.current as Activity
    val background by prefs.background
    val ctx = LocalContext.current
    val icons = remember { IconCache(ctx, repo) }
    val drag = remember { DragState() }
    val imeTarget = remember { ImeTarget() }
    val apps by repo.apps
    LaunchedEffect(apps) { icons.clear() }                 // an app was installed/updated: redraw its icon
    var searching by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var listDrag by remember { mutableStateOf(false) }     // an app is being dragged out of the list
    LaunchedEffect(homePresses) { searching = false; settings = false }
    LaunchedEffect(drag.active) { if (!drag.active && listDrag) { listDrag = false; searching = false } }
    BackHandler(enabled = searching || settings) { searching = false; settings = false }
    // drawing our own OLED background: tell Android not to render the wallpaper under it at all
    LaunchedEffect(background) {
        if (background == "eris") activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        else activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    }
    // frosted glass: the background, small and blurred, made again when the screen, colours or background change
    val pal = LocalPalette.current
    val blurOn by prefs.blur
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val bottomGap = with(LocalDensity.current) { 96.dp.toPx() }
    val backdrop by produceState<Backdrop?>(null, screen, pal.fg, pal.bg, background, blurOn) {
        value = if (!blurOn || background != "eris" || screen.width == 0) null
        else withContext(Dispatchers.Default) { makeBackdrop(ctx, art, screen.width, screen.height, bottomGap, pal.fg.toArgb(), pal.bg.toArgb()) }
    }
    CompositionLocalProvider(LocalImeTarget provides imeTarget, LocalBackdrop provides backdrop) { Box(Modifier.fillMaxSize().onSizeChanged { screen = it }) {
        Home(repo, prefs, widgets, art, icons, drag, homePresses, isDefault, onMakeDefault,
            onOpenSearch = { searching = true }, onOpenSettings = { settings = true })
        AnimatedVisibility(
            visible = searching,
            enter = slideInVertically { it / 4 } + fadeIn(),
            exit = slideOutVertically { it / 4 } + fadeOut(),
        ) {
            SearchPanel(repo, prefs, icons, drag, hidden = listDrag, onDragOut = { listDrag = true }, onClose = { searching = false })
        }
        AnimatedVisibility(visible = settings, enter = fadeIn(), exit = fadeOut()) {
            SettingsPanel(prefs, widgets, isDefault, onMakeDefault, onClose = { settings = false })
        }
    } }
}
