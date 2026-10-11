package org.aero.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/* Small things the launcher asks Android or the internet for. */

/**
 * Pulls down the notification shade. There is no public API for this: the StatusBarManager has a
 * hidden method that home screens call by name (reflection), allowed by the EXPAND_STATUS_BAR
 * permission. If a future Android removes it, the gesture just does nothing.
 */
@SuppressLint("WrongConstant")
fun expandNotifications(ctx: Context) {
    runCatching {
        val statusBar = ctx.getSystemService("statusbar") ?: return
        statusBar.javaClass.getMethod("expandNotificationsPanel").invoke(statusBar)
    }
}

/** Opens the search in the browser the user chose as default (Android picks it from the link). */
fun webSearch(ctx: Context, query: String, engine: String = "google") = runCatching {
    val base = ENGINES[engine] ?: ENGINES.getValue("google")
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(base + Uri.encode(query)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openWallpaperPicker(ctx: Context) = runCatching {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openAndroidSettings(ctx: Context) = runCatching {
    ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------- weather (same source as the desktop: Open-Meteo, location from the IP) ----------

data class Day(val name: String, val code: Int, val max: Int, val min: Int)
data class WeatherNow(val temp: Int, val code: Int, val feels: Int, val wind: Int, val city: String, val days: List<Day>) {
    val text get() = describe(code)
}

private fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    conn.setRequestProperty("User-Agent", "aero-launcher")
    return conn.inputStream.bufferedReader().use { it.readText() }
}

/** WMO weather codes (what Open-Meteo returns) in a few words. */
fun describe(code: Int) = when (code) {
    0 -> "clear"
    1, 2 -> "partly cloudy"
    3 -> "cloudy"
    45, 48 -> "fog"
    in 51..57 -> "drizzle"
    in 61..67 -> "rain"
    in 71..77 -> "snow"
    in 80..82 -> "showers"
    in 95..99 -> "storm"
    else -> "—"
}

/** The same code as a weather icon from the bundled Nerd Font (Weather Icons set). */
fun glyph(code: Int) = when (code) {
    0 -> "\ue30d"
    1, 2 -> "\ue302"
    3 -> "\ue312"
    45, 48 -> "\ue313"
    in 51..57 -> "\ue31b"
    in 61..67 -> "\ue318"
    in 71..77 -> "\ue31a"
    in 80..82 -> "\ue319"
    in 95..99 -> "\ue31d"
    else -> "\ue312"
}

suspend fun loadWeather(): WeatherNow? = withContext(Dispatchers.IO) {
    runCatching {
        val where = JSONObject(httpGet("https://ipinfo.io/json"))
        val (lat, lon) = where.getString("loc").split(",")
        val w = JSONObject(httpGet(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=6"))
        val cur = w.getJSONObject("current")
        val d = w.getJSONObject("daily")
        val days = (0 until d.getJSONArray("time").length()).map { i ->
            val date = java.time.LocalDate.parse(d.getJSONArray("time").getString(i))
            Day(
                name = if (i == 0) "today" else date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH).lowercase(),
                code = d.getJSONArray("weather_code").getInt(i),
                max = Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                min = Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
            )
        }
        WeatherNow(
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            code = cur.getInt("weather_code"),
            feels = Math.round(cur.getDouble("apparent_temperature")).toInt(),
            wind = Math.round(cur.getDouble("wind_speed_10m")).toInt(),
            city = where.optString("city").lowercase(),
            days = days,
        )
    }.getOrNull()
}
