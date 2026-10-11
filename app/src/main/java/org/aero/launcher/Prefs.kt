package org.aero.launcher

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/** The built-in widgets (the same family as the desktop's) plus any Android app widget. */
enum class Kind(val id: String, val title: String, val about: String) {
    CLOCK("clock", "clock", "time, date, são paulo and local"),
    WEATHER("weather", "weather", "now and the next five days"),
    APPS("apps", "apps", "your pinned apps"),
    BATTERY("battery", "battery", "level, charging, temperature"),
    RESOURCES("resources", "resources", "memory, storage, heat, network"),
    FETCH("fetch", "fetch", "fastfetch of this phone"),
    TERMINAL("terminal", "terminal", "a small shell"),
    ANDROID("android", "android widget", "a widget from another app"),
}

/** One place on the home screen. Two "half" slots in a row share the width. */
data class Slot(val kind: Kind, val half: Boolean = false, val widgetId: Int = -1, val heightDp: Int = 180)

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
    val slots = mutableStateOf(loadSlots())
    /** We ask once, on the first start, to become the home screen; after that only the banner asks. */
    val askedHome get() = sp.getBoolean("asked_home", false)
    fun setAskedHome() = sp.edit().putBoolean("asked_home", true).apply()

    fun setBackground(v: String) { background.value = v; sp.edit().putString("background", v).apply() }
    fun setShimmer(v: Boolean) { shimmer.value = v; sp.edit().putBoolean("shimmer", v).apply() }
    fun setSeconds(v: Boolean) { seconds.value = v; sp.edit().putBoolean("seconds", v).apply() }
    fun setEngine(v: String) { engine.value = v; sp.edit().putString("engine", v).apply() }
    fun setFetchUser(v: String) { fetchUser.value = v; sp.edit().putString("fetch_user", v).apply() }

    fun setSlots(list: List<Slot>) {
        slots.value = list
        sp.edit().putString("slots", list.joinToString("\n") { "${it.kind.id}|${it.half}|${it.widgetId}|${it.heightDp}" }).apply()
    }

    private fun loadSlots(): List<Slot> {
        val saved = sp.getString("slots", null)
            ?: return listOf(Slot(Kind.CLOCK), Slot(Kind.WEATHER), Slot(Kind.APPS))   // a calm home by default
        return saved.split("\n").mapNotNull { line ->
            val p = line.split("|")
            val kind = Kind.entries.firstOrNull { it.id == p.getOrNull(0) } ?: return@mapNotNull null
            Slot(kind, p.getOrNull(1) == "true", p.getOrNull(2)?.toIntOrNull() ?: -1, p.getOrNull(3)?.toIntOrNull() ?: 180)
        }
    }
}

val ENGINES = linkedMapOf(
    "google" to "https://www.google.com/search?q=",
    "duckduckgo" to "https://duckduckgo.com/?q=",
    "startpage" to "https://www.startpage.com/do/search?q=",
    "bing" to "https://www.bing.com/search?q=",
)
