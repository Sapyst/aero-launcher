package org.aero.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Widgets from other apps (Spotify, Calendar, Keep...). Android's recipe for a home screen:
 *  1. ask the AppWidgetHost for a new widget id;
 *  2. "bind" the id to the chosen widget: Android asks the user once ("allow Aero Launcher to create widgets?");
 *  3. if the widget has a setup screen (e.g. which calendar), open it;
 *  4. AppWidgetHost.createView gives a normal Android View, shown inside Compose with AndroidView.
 * The host must be "listening" while the launcher is visible, so the widgets get their updates.
 */
class WidgetHost(private val activity: Activity) {
    val manager: AppWidgetManager = AppWidgetManager.getInstance(activity)
    val host = AppWidgetHost(activity, 0xAE20)
    private var pending: Pair<Int, AppWidgetProviderInfo>? = null
    private var onAdded: ((Int, AppWidgetProviderInfo) -> Unit)? = null

    fun providers(): List<AppWidgetProviderInfo> = manager.installedProviders
        .sortedBy { label(it).lowercase() }

    fun label(info: AppWidgetProviderInfo): String {
        val pm = activity.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault(info.provider.packageName)
        val name = info.loadLabel(pm) ?: ""
        return if (name.isBlank() || name == app) app else "$app · $name"
    }

    fun add(info: AppWidgetProviderInfo, done: (Int, AppWidgetProviderInfo) -> Unit) {
        val id = host.allocateAppWidgetId()
        pending = id to info
        onAdded = done
        if (manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) configure(id, info)
        else activity.startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
            REQ_BIND,
        )
    }

    private fun configure(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure == null) return finish(id, info)
        runCatching { host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQ_CONFIG, null) }
            .onFailure { finish(id, info) }
    }

    /** Called from the activity's onActivityResult. */
    fun onResult(requestCode: Int, resultCode: Int) {
        val (id, info) = pending ?: return
        if (requestCode != REQ_BIND && requestCode != REQ_CONFIG) return
        if (resultCode != Activity.RESULT_OK) { host.deleteAppWidgetId(id); pending = null; return }
        if (requestCode == REQ_BIND) configure(id, info) else finish(id, info)
    }

    private fun finish(id: Int, info: AppWidgetProviderInfo) {
        onAdded?.invoke(id, info)
        pending = null
    }

    fun remove(id: Int) = runCatching { host.deleteAppWidgetId(id) }

    fun view(id: Int): AppWidgetHostView? {
        val info = manager.getAppWidgetInfo(id) ?: return null
        return host.createView(activity.applicationContext, id, info)
    }

    companion object {
        const val REQ_BIND = 4101
        const val REQ_CONFIG = 4102
    }
}

/** An app widget inside a launcher box. Tells the widget its real size so it lays itself out right. */
@Composable
fun AndroidWidget(host: WidgetHost, slot: Slot, modifier: Modifier, onMenu: () -> Unit) {
    val info = host.manager.getAppWidgetInfo(slot.widgetId)
    val L = LocalLang.current
    TermBox(info?.let { host.label(it).lowercase() } ?: "widget", modifier, onLongClick = onMenu) {
        if (info == null) { T(L("this widget is gone (app removed?)", "esse widget sumiu (app desinstalado?)"), 12.sp, LocalPalette.current.dim); return@TermBox }
        AndroidView(
            factory = { _ ->
                var last = 0 to 0                             // only tell the widget when the size really changes
                host.view(slot.widgetId)!!.apply {
                    addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                        val d = v.resources.displayMetrics.density
                        val w = (r - l) / d; val h = (b - t) / d
                        if (w > 0 && h > 0 && last != (r - l) to (b - t)) {
                            last = (r - l) to (b - t)
                            if (Build.VERSION.SDK_INT >= 31) (v as AppWidgetHostView).updateAppWidgetSize(Bundle(), listOf(SizeF(w, h)))
                            else @Suppress("DEPRECATION") (v as AppWidgetHostView).updateAppWidgetSize(null, w.toInt(), h.toInt(), w.toInt(), h.toInt())
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(slot.heightDp.dp),
        )
    }
}

