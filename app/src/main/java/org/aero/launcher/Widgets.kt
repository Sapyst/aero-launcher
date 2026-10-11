package org.aero.launcher

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/* The built-in widgets. They mirror the desktop's (widget_relogio, widget_clima, widget_recursos):
   same information, same idea of layout, in the launcher's boxes. Every one accepts a long press
   (onMenu) that opens its own menu: move, half/full width, remove. */

private val HH = DateTimeFormatter.ofPattern("HH", Locale.ENGLISH)
private val MM = DateTimeFormatter.ofPattern(":mm", Locale.ENGLISH)
private val SS = DateTimeFormatter.ofPattern(":ss", Locale.ENGLISH)
private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val NUMDATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH)
private val WEEKMONTH = DateTimeFormatter.ofPattern("EEEE · MMMM", Locale.ENGLISH)
private val SAO_PAULO = ZoneId.of("America/Sao_Paulo")

// ---------- clock: hours bright, minutes dimmer (like the desktop), numeric date, SP and local ----------
@Composable
fun ClockWidget(prefs: Prefs, modifier: Modifier, onMenu: () -> Unit) {
    val pal = LocalPalette.current
    val seconds by prefs.seconds
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(seconds) {
        while (true) {
            now = ZonedDateTime.now()
            delay(if (seconds) 1000 - System.currentTimeMillis() % 1000 else 60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    TermBox("clock", modifier, onLongClick = onMenu) {
        Row(verticalAlignment = Alignment.Bottom) {
            T(now.format(HH), 62.sp)
            T(now.format(MM), 62.sp, pal.dim)
            if (seconds) T(now.format(SS), 26.sp, pal.dim, Modifier.padding(bottom = 9.dp))
        }
        T("${now.format(NUMDATE)} · ${now.format(WEEKMONTH).lowercase()}", 13.sp, pal.fg)
        val sp = now.withZoneSameInstant(SAO_PAULO)
        T("sp ${sp.format(HM)} · local ${now.format(HM)}", 12.sp, pal.dim)
    }
}

// ---------- weather: now + the next days in columns, a thin bar for each day's range ----------
@Composable
fun WeatherWidget(modifier: Modifier, onMenu: () -> Unit) {
    val pal = LocalPalette.current
    var weather by remember { mutableStateOf<WeatherNow?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            loadWeather()?.let { weather = it }
            delay(if (weather == null) 60_000L else 30 * 60_000L)    // retry in 1 min, refresh every 30 min
        }
    }
    val w = weather
    TermBox(if (w == null) "weather" else "weather · ${w.city}", modifier, onLongClick = onMenu) {
        if (w == null) { T("…", 30.sp); T("loading", 12.sp, pal.dim); return@TermBox }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Nerd Font icons are drawn wider than their advance: give them a fixed box so nothing overlaps
            Box(Modifier.width(54.dp), contentAlignment = Alignment.CenterStart) { T(glyph(w.code), 34.sp) }
            Column {
                T("${w.temp}°  ${w.text}", 20.sp, maxLines = 1)
                T("feels ${w.feels}° · wind ${w.wind} km/h", 12.sp, pal.dim, maxLines = 1)
            }
        }
        val days = w.days.take(5)
        val lo = days.minOf { it.min }; val hi = days.maxOf { it.max }.coerceAtLeast(lo + 1)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            days.forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    T(d.name, 11.sp, if (d.name == "today") pal.fg else pal.dim)
                    T(glyph(d.code), 18.sp, modifier = Modifier.padding(vertical = 2.dp))
                    T("${d.max}°", 12.sp)
                    T("${d.min}°", 12.sp, pal.dim)
                    // the day's range on the week's scale, cold blue -> warm orange (as on the desktop)
                    Canvas(Modifier.padding(top = 4.dp).width(36.dp).height(2.dp)) {
                        val a = (d.min - lo).toFloat() / (hi - lo) * size.width
                        val b = (d.max - lo).toFloat() / (hi - lo) * size.width
                        drawLine(pal.line, Offset(0f, 1f), Offset(size.width, 1f), 2f)
                        drawLine(Brush.horizontalGradient(listOf(Color(0xFF5DA9E9), Color(0xFFF39C4A)), 0f, size.width),
                            Offset(a, 1f), Offset(b.coerceAtLeast(a + 2f), 1f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
    }
}

// ---------- battery ----------
data class BatteryInfo(val level: Int = -1, val charging: Boolean = false, val tempC: Float = 0f, val plug: String = "")

@Composable
fun rememberBattery(): State<BatteryInfo> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(BatteryInfo()) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                state.value = BatteryInfo(
                    level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                    tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
                    plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "ac"; BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"; else -> ""
                    },
                )
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    return state
}

@Composable
fun BatteryWidget(modifier: Modifier, onMenu: () -> Unit) {
    val pal = LocalPalette.current
    val b by rememberBattery()
    TermBox("battery", modifier, onLongClick = onMenu) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(38.dp)) { T("", 22.sp) }
            T(if (b.level < 0) "…" else "${b.level}%" + if (b.charging) " +" else "", 26.sp)
        }
        val cells = ((b.level.coerceAtLeast(0) + 6) / 12.5).toInt().coerceIn(0, 8)
        T("█".repeat(cells) + "░".repeat(8 - cells), 12.sp, pal.dim)
        T("${"%.1f".format(b.tempC)}°c" + if (b.plug.isNotEmpty()) " · ${b.plug}" else "", 12.sp, pal.dim)
    }
}

// ---------- resources: rings (cpu, mem, disk, heat) and the network as a moving curve ----------
private data class Res(val cpu: Float?, val mem: Float, val memText: String, val disk: Float, val diskText: String)

private fun readLong(path: String) = runCatching { File(path).readText().trim().toLong() }.getOrNull()

/** CPU "load" from how fast the cores are clocked right now (apps may not read /proc/stat on Android). */
private fun cpuClock(): Float? {
    val cores = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { c ->
        val cur = readLong("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq")
        val max = readLong("/sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq")
        if (cur != null && max != null && max > 0) cur.toFloat() / max else null
    }
    return if (cores.isEmpty()) null else cores.average().toFloat()
}

private fun gib(bytes: Long) = "%.1f".format(bytes / 1073741824.0)

@Composable
fun ResourcesWidget(modifier: Modifier, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val battery by rememberBattery()
    var res by remember { mutableStateOf<Res?>(null) }
    var thermal by remember { mutableStateOf("") }
    val down = remember { mutableStateListOf<Float>() }
    val up = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        var lastRx = TrafficStats.getTotalRxBytes(); var lastTx = TrafficStats.getTotalTxBytes()
        while (true) {
            res = withContext(Dispatchers.IO) {
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                val fs = StatFs(Environment.getDataDirectory().path)
                val used = mi.totalMem - mi.availMem
                val dUsed = fs.totalBytes - fs.availableBytes
                Res(cpuClock(), used.toFloat() / mi.totalMem, "${gib(used)}/${gib(mi.totalMem)}g",
                    dUsed.toFloat() / fs.totalBytes, "${gib(dUsed)}/${gib(fs.totalBytes)}g")
            }
            thermal = if (android.os.Build.VERSION.SDK_INT >= 29) when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "cool"; PowerManager.THERMAL_STATUS_LIGHT -> "warm"
                PowerManager.THERMAL_STATUS_MODERATE -> "hot"; else -> "throttling"
            } else ""
            val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
            down.add(((rx - lastRx) / 2f).coerceAtLeast(0f)); up.add(((tx - lastTx) / 2f).coerceAtLeast(0f))
            lastRx = rx; lastTx = tx
            while (down.size > 40) { down.removeAt(0); up.removeAt(0) }
            delay(2000)
        }
    }
    TermBox("resources", modifier, onLongClick = onMenu) {
        val r = res
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Ring("cpu", r?.cpu, r?.cpu?.let { "${(it * 100).toInt()}%" } ?: "n/a", "clock")
            Ring("mem", r?.mem, r?.let { "${(it.mem * 100).toInt()}%" } ?: "…", r?.memText ?: "")
            Ring("disk", r?.disk, r?.let { "${(it.disk * 100).toInt()}%" } ?: "…", r?.diskText ?: "")
            Ring("heat", ((battery.tempC - 20f) / 30f).coerceIn(0f, 1f), "${battery.tempC.toInt()}°", thermal)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(26.dp)) { T("", 14.sp, pal.dim) }
            T("↓ ${speed(down.lastOrNull() ?: 0f)}  ↑ ${speed(up.lastOrNull() ?: 0f)}", 12.sp, pal.fg)
        }
        NetCurve(down.toList(), up.toList(), Modifier.fillMaxWidth().height(36.dp).padding(top = 4.dp))
    }
}

private fun speed(bps: Float) = when {
    bps >= 1_048_576 -> "%.1f mb/s".format(bps / 1_048_576)
    bps >= 1024 -> "%.0f kb/s".format(bps / 1024)
    else -> "%.0f b/s".format(bps)
}

/** A 270° meter like the desktop's: grey track, bright arc for the value, the number in the middle. */
@Composable
private fun Ring(title: String, value: Float?, center: String, below: String) {
    val pal = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(64.dp)) {
                val sw = 4.dp.toPx()
                val inset = Offset(sw / 2, sw / 2)
                val arcSize = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
                drawArc(pal.line, 135f, 270f, false, inset, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                if (value != null)
                    drawArc(pal.fg, 135f, 270f * value.coerceIn(0f, 1f), false, inset, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            }
            T(center, 13.sp)
        }
        T(title, 11.sp)
        T(below, 10.sp, pal.dim, maxLines = 1)
    }
}

/** Download (bright) and upload (dim) over the last 80 seconds, each scaled to the busiest moment. */
@Composable
private fun NetCurve(down: List<Float>, up: List<Float>, modifier: Modifier) {
    val pal = LocalPalette.current
    Canvas(modifier) {
        val top = maxOf(down.maxOrNull() ?: 1f, up.maxOrNull() ?: 1f, 1024f)
        fun curve(values: List<Float>, color: Color) {
            if (values.size < 2) return
            val step = size.width / 39f
            val start = 40 - values.size
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = (start + i) * step
                val y = size.height - v / top * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(1.5.dp.toPx()))
        }
        drawLine(pal.line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        curve(up, pal.dim)
        curve(down, pal.fg)
    }
}

// ---------- pinned apps ----------
@Composable
fun AppsWidget(repo: AppRepository, modifier: Modifier, onMenu: () -> Unit) {
    val pal = LocalPalette.current
    val pinnedKeys by repo.pinned
    val all by repo.apps
    val apps = remember(pinnedKeys, all) { repo.pinnedApps() }
    TermBox("apps", modifier, onLongClick = onMenu) {
        if (apps.isEmpty()) {
            T("swipe up, long-press an app, \"pin to home\"", 12.sp, pal.dim)
        } else {
            apps.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { app ->
                        T("> ${app.label}", 15.sp, pal.fg, Modifier.weight(1f).clickable { repo.launch(app) }.padding(vertical = 6.dp), maxLines = 1)
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
