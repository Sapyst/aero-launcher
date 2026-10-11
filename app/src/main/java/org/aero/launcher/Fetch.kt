package org.aero.launcher

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/* "fetch": fastfetch for this phone. All values are read live from Android, nothing is typed in. */

/** Our own little Android robot (fastfetch's logo is a robot too). */
val ROBOT = listOf(
    "  \\  ___  /  ",
    "   /     \\   ",
    "  | o   o |  ",
    "  |_______|  ",
    " _ _______ _ ",
    "| |       | |",
    "| |       | |",
    "|_|       |_|",
    "   |_| |_|   ",
)

fun fetchLines(ctx: Context, user: String, appCount: Int, battery: BatteryInfo?): List<Pair<String, String>> {
    val host = (Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL)
        .lowercase().replace(' ', '-')
    val am = ctx.getSystemService(ActivityManager::class.java)
    val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val fs = StatFs(Environment.getDataDirectory().path)
    val up = SystemClock.elapsedRealtime() / 60000
    val dm = ctx.resources.displayMetrics
    val hz = runCatching { if (Build.VERSION.SDK_INT >= 30) ctx.display?.refreshRate?.toInt() else null }.getOrNull()
    val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".lowercase() else Build.HARDWARE
    fun g(b: Long) = "%.1f".format(b / 1073741824.0)
    return listOf(
        "$user@$host" to "",
        "-".repeat(user.length + host.length + 1) to "",
        "os" to "android ${Build.VERSION.RELEASE} (${Build.ID.lowercase()})",
        "host" to "${Build.MANUFACTURER} ${Build.MODEL}".lowercase(),
        "kernel" to (System.getProperty("os.version") ?: "?").substringBefore('-').let { "linux $it" },
        "uptime" to (if (up >= 1440) "${up / 1440}d " else "") + "${up % 1440 / 60}h ${up % 60}m",
        "packages" to "$appCount (apps)",
        "shell" to "aero-launcher ${BuildConfig.VERSION_NAME}",
        "display" to "${dm.widthPixels}x${dm.heightPixels}" + (hz?.let { " @ ${it}hz" } ?: ""),
        "cpu" to "$soc (${Runtime.getRuntime().availableProcessors()})",
        "memory" to "${g(mem.totalMem - mem.availMem)} / ${g(mem.totalMem)} gib",
        "disk" to "${g(fs.totalBytes - fs.availableBytes)} / ${g(fs.totalBytes)} gib",
        "battery" to (battery?.let { "${it.level}%" + if (it.charging) " [${it.plug.ifEmpty { "charging" }}]" else "" } ?: "?"),
        "patch" to Build.VERSION.SECURITY_PATCH,
        "locale" to ctx.resources.configuration.locales[0].toLanguageTag(),
    )
}


@Composable
fun FetchWidget(repo: AppRepository, prefs: Prefs, modifier: Modifier, onMenu: (() -> Unit)?) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val user by prefs.fetchUser
    val apps by repo.apps
    val battery by rememberBattery()
    var lines by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(user, apps.size, battery.level, battery.charging) {
        while (true) { lines = fetchLines(ctx, user, apps.size, battery); delay(60_000) }
    }
    TermBox("fetch", modifier, onLongClick = onMenu) {
        Row {
            Column(Modifier.padding(end = 10.dp)) { ROBOT.forEach { T(it, 11.sp, pal.dim) } }
            Column {
                lines.forEachIndexed { i, (k, v) ->
                    if (i < 2) T(k, 12.sp, if (i == 0) pal.fg else pal.dim, maxLines = 1)
                    else Row {
                        T(k.padEnd(9), 11.sp, pal.fg)
                        T(v, 11.sp, pal.dim, maxLines = 1)
                    }
                }
                Row(Modifier.padding(top = 6.dp)) {          // fastfetch's colour row, in greys
                    listOf(0xFF2A2A2A, 0xFF4A4A4A, 0xFF6E6E6E, 0xFF949494, 0xFFBDBDBD, 0xFFF0F0F0).forEach {
                        T("███", 11.sp, androidx.compose.ui.graphics.Color(it))
                        Spacer(Modifier.width(1.dp))
                    }
                }
            }
        }
    }
}
