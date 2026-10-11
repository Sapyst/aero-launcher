package org.aero.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.mutableStateOf

/** One app icon on the phone (an app can have more than one, and the work profile has its own). */
data class App(val label: String, val component: ComponentName, val user: UserHandle, val userSerial: Long) {
    val key: String get() = "${component.flattenToString()}#$userSerial"
}

/**
 * The list of apps, from Android's LauncherApps service (the official way for a home screen).
 * It also tells us when an app is installed, updated or removed, so the list stays current.
 * Pinned apps (the "apps" box on the home screen) are saved in the app's preferences.
 */
class AppRepository(private val ctx: Context) {
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    private val users = ctx.getSystemService(UserManager::class.java)
    private val prefs = ctx.getSharedPreferences("aero", Context.MODE_PRIVATE)

    val apps = mutableStateOf<List<App>>(emptyList())
    val pinned = mutableStateOf(prefs.getString("pinned", "")!!.split("\n").filter { it.isNotBlank() })

    init {
        reload()
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
            override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
            override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        })
    }

    fun reload() {
        apps.value = users.userProfiles.flatMap { user ->
            val serial = users.getSerialNumberForUser(user)
            launcherApps.getActivityList(null, user).map { App(it.label.toString(), it.componentName, user, serial) }
        }.filter { it.component.packageName != ctx.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(app: App, bounds: Rect? = null) =
        runCatching { launcherApps.startMainActivity(app.component, app.user, bounds, null) }

    fun openInfo(app: App) =
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }

    fun uninstall(app: App) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun isPinned(app: App) = app.key in pinned.value
    fun togglePin(app: App) {
        pinned.value = if (isPinned(app)) pinned.value - app.key else pinned.value + app.key
        prefs.edit().putString("pinned", pinned.value.joinToString("\n")).apply()
    }
    fun pinnedApps(): List<App> {
        val byKey = apps.value.associateBy { it.key }
        return pinned.value.mapNotNull { byKey[it] }
    }
}

/**
 * Search ranking: name starts with the text > a word starts with it > contains it > the letters
 * appear in order ("ffx" finds "firefox"). Shorter names first when tied.
 */
fun rank(apps: List<App>, query: String): List<App> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return apps
    fun inOrder(text: String): Boolean {
        var i = 0
        for (c in text) if (i < q.length && c == q[i]) i++
        return i == q.length
    }
    return apps.mapNotNull { app ->
        val name = app.label.lowercase()
        val score = when {
            name.startsWith(q) -> 0
            name.split(' ', '-', '.', '_').any { it.startsWith(q) } -> 1
            q in name -> 2
            inOrder(name) -> 3
            else -> return@mapNotNull null
        }
        score to app
    }.sortedWith(compareBy({ it.first }, { it.second.label.length })).map { it.second }
}
