package org.aero.launcher

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** The built-in widgets (the same family as the desktop's) plus any Android app widget. */
enum class Kind(val id: String) { CLOCK("clock"), WEATHER("weather"), APPS("apps"), BATTERY("battery"),
    RESOURCES("resources"), FETCH("fetch"), TERMINAL("terminal"), ANDROID("android"), APP("app") }

fun Kind.title(L: Lang) = when (this) {
    Kind.CLOCK -> L("clock", "relógio"); Kind.WEATHER -> L("weather", "clima"); Kind.APPS -> "apps"
    Kind.BATTERY -> L("battery", "bateria"); Kind.RESOURCES -> L("resources", "recursos"); Kind.FETCH -> "fetch"
    Kind.TERMINAL -> "terminal"; Kind.ANDROID -> L("android widget", "widget android"); Kind.APP -> "app"
}
fun Kind.about(L: Lang) = when (this) {
    Kind.CLOCK -> L("time, date, são paulo and local", "hora, data, são paulo e local")
    Kind.WEATHER -> L("now and the next five days", "agora e os próximos cinco dias")
    Kind.APPS -> L("your pinned apps", "seus apps fixados")
    Kind.BATTERY -> L("level, charging, temperature", "nível, carregando, temperatura")
    Kind.RESOURCES -> L("memory, storage, heat, network", "memória, armazenamento, calor, rede")
    Kind.FETCH -> L("fastfetch of this phone", "o fastfetch deste celular")
    Kind.TERMINAL -> L("a small shell", "um pequeno shell")
    Kind.ANDROID -> L("a widget from another app", "um widget de outro app")
    Kind.APP -> ""
}


/**
 * Launcher settings, saved in the app's preferences. Each value is also a Compose state, so the
 * screen updates the moment a setting changes.
 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("aero", Context.MODE_PRIVATE)

    val background = mutableStateOf(sp.getString("background", "eris")!!)   // "eris" | "wallpaper"
    val shimmer = mutableStateOf(sp.getBoolean("shimmer", true))
    val seconds = mutableStateOf(sp.getBoolean("seconds", true))
    val engine = mutableStateOf(sp.getString("engine", "google")!!)
    val fetchUser = mutableStateOf(sp.getString("fetch_user", "aero")!!)
    val theme = mutableStateOf(sp.getString("theme", "system")!!)          // "system" | "dark" | "light"
    val lang = mutableStateOf(sp.getString("lang", "en")!!)                // "en" | "pt"
    val items = mutableStateOf(loadItems())                                 // what is on the home grid
    val gridCols = mutableStateOf(sp.getInt("grid_cols", 4))
    val gridRows = mutableStateOf(sp.getInt("grid_rows", 7))              // rows in use (measured or chosen)
    val rowsManual = mutableStateOf(sp.getInt("rows_manual", 0))          // 0 = as many as fit the screen
    val pages = mutableStateOf(sp.getInt("pages", 1))                     // workspaces, empty ones too
    val opacity = mutableStateOf(sp.getFloat("opacity", 0.78f))           // boxes: 1 = solid
    val blur = mutableStateOf(sp.getBoolean("blur", true))                // frosted glass behind boxes and panels
    val font = mutableStateOf(sp.getString("font", "jetbrains")!!)
    val textScale = mutableStateOf(sp.getFloat("text_scale", 1f))
    val iconScale = mutableStateOf(sp.getFloat("icon_scale", 1f))
    val showPrompt = mutableStateOf(sp.getBoolean("show_prompt", true))
    val fetchLogo = mutableStateOf(sp.getString("fetch_logo", "")!!)       // "" = the robot
    val fetchInvert = mutableStateOf(sp.getBoolean("fetch_invert", false))
    val lockScreen = mutableStateOf(sp.getBoolean("lock_screen", false))    // the terminal lock screen (Lock.kt)
    /** We ask once, on the first start, to become the home screen; after that only the banner asks. */
    val askedHome get() = sp.getBoolean("asked_home", false)
    fun setAskedHome() = sp.edit().putBoolean("asked_home", true).apply()

    fun setBackground(v: String) { background.value = v; sp.edit().putString("background", v).apply() }
    fun setShimmer(v: Boolean) { shimmer.value = v; sp.edit().putBoolean("shimmer", v).apply() }
    fun setSeconds(v: Boolean) { seconds.value = v; sp.edit().putBoolean("seconds", v).apply() }
    fun setEngine(v: String) { engine.value = v; sp.edit().putString("engine", v).apply() }
    fun setFetchUser(v: String) { fetchUser.value = v; sp.edit().putString("fetch_user", v).apply() }
    fun setTheme(v: String) { theme.value = v; sp.edit().putString("theme", v).apply() }
    fun setLang(v: String) { lang.value = v; sp.edit().putString("lang", v).apply() }

    fun setItems(list: List<Item>) {
        items.value = list
        sp.edit().putString("items", list.joinToString("\n") { it.encode() }).apply()
    }
    fun setGridCols(v: Int) { gridCols.value = v; sp.edit().putInt("grid_cols", v).apply() }
    fun setGridRows(v: Int) { gridRows.value = v; sp.edit().putInt("grid_rows", v).apply() }
    fun setRowsManual(v: Int) { rowsManual.value = v; sp.edit().putInt("rows_manual", v).apply() }
    fun setPages(v: Int) { pages.value = v.coerceAtLeast(1); sp.edit().putInt("pages", pages.value).apply() }
    fun setOpacity(v: Float) { opacity.value = v; sp.edit().putFloat("opacity", v).apply() }
    fun setBlur(v: Boolean) { blur.value = v; sp.edit().putBoolean("blur", v).apply() }
    fun setFont(v: String) { font.value = v; sp.edit().putString("font", v).apply() }
    fun setTextScale(v: Float) { textScale.value = v; sp.edit().putFloat("text_scale", v).apply() }
    fun setIconScale(v: Float) { iconScale.value = v; sp.edit().putFloat("icon_scale", v).apply() }
    fun setFetchLogo(v: String) { fetchLogo.value = v; sp.edit().putString("fetch_logo", v).apply() }
    fun setFetchInvert(v: Boolean) { fetchInvert.value = v; sp.edit().putBoolean("fetch_invert", v).apply() }
    fun setLockScreen(v: Boolean) { lockScreen.value = v; sp.edit().putBoolean("lock_screen", v).apply() }
    fun setShowPrompt(v: Boolean) { showPrompt.value = v; sp.edit().putBoolean("show_prompt", v).apply() }

    /** Adds to the home: page -1 = "not placed yet", the home puts it in the first free cells. */
    fun addItem(item: Item) = setItems(items.value.filter { it.id != item.id } + item.copy(page = -1))

    private fun loadItems(): List<Item> {
        sp.getString("items", null)?.let { saved -> return saved.split("\n").mapNotNull(Item::decode) }
        val cols = sp.getInt("grid_cols", 4)
        // version 0.5 and before kept a list ("slots"): bring those widgets over, to be placed
        val old = sp.getString("slots", null)?.split("\n")?.mapNotNull { line ->
            val p = line.split("|")
            val kind = Kind.entries.firstOrNull { it.id == p.getOrNull(0) } ?: return@mapNotNull null
            val wid = p.getOrNull(2)?.toIntOrNull() ?: -1
            if (kind == Kind.ANDROID) Item.android(wid, cols, 2) else Item.widget(kind, cols)
        } ?: listOf(Kind.CLOCK, Kind.WEATHER).map { Item.widget(it, cols) }   // a calm home by default: room for app icons
        return old.map { it.copy(page = -1) }
    }
}

val ENGINES = linkedMapOf(
    "google" to "https://www.google.com/search?q=",
    "duckduckgo" to "https://duckduckgo.com/?q=",
    "startpage" to "https://www.startpage.com/do/search?q=",
    "bing" to "https://www.bing.com/search?q=",
)
