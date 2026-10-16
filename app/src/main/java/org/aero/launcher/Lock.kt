package org.aero.launcher

import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/*
 * The terminal lock screen itself (the service that opens it is in LockService.kt). It sits on top of
 * Android's lock screen: swiping up (or tapping "$ unlock") asks Android to unlock — PIN, fingerprint,
 * or nothing when Extend Unlock keeps the phone open — and the way out is a short glitch.
 */
class LockActivity : ComponentActivity() {
    private val leaving = mutableStateOf(false)

    // unlocked behind us (fingerprint, face, Extend Unlock): leave the same way
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { leaving.value = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT))
        onBackPressedDispatcher.addCallback(this) { }        // Back does not get past the lock screen
        ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        val prefs = Prefs(this)
        val art = AsciiArt.load(this, "eris.txt")
        setContent {
            // always the dark palette: it is a lock screen on an OLED, whatever the launcher's theme
            AeroTheme("dark", prefs.lang.value, prefs.opacity.value, prefs.font.value, prefs.textScale.value) {
                LockScreen(prefs, art, leaving.value, onUnlock = ::unlock, onGone = ::gone)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {               // the screen went off again while we were here
        super.onNewIntent(intent)
        leaving.value = false
    }

    override fun onDestroy() { runCatching { unregisterReceiver(unlocked) }; super.onDestroy() }

    /** Locked for real (PIN, pattern…): Android asks first, then the glitch. Otherwise straight to the glitch. */
    private fun unlock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isDeviceLocked) km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { leaving.value = true }
        }) else leaving.value = true
    }

    /** After the glitch: Android's own lock screen (if it is still there, unlocked) goes too, then we close. */
    private fun gone() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = close()
            override fun onDismissCancelled() = close()
            override fun onDismissError() = close()
        }) else close()
    }

    private fun close() {
        finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE dd/MM")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun LockScreen(prefs: Prefs, art: AsciiArt, leaving: Boolean, onUnlock: () -> Unit, onGone: () -> Unit) {
    val pal = LocalPalette.current
    val L = LocalLang.current
    Glitch(active = leaving, onDone = onGone) {
        Box(Modifier.fillMaxSize().background(pal.bg)) {
            if (prefs.background.value == "eris") AsciiBackground(art, prefs.shimmer.value)
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)
                    .pointerInput(Unit) {
                        var total = 0f
                        val threshold = 80.dp.toPx()
                        detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -threshold) onUnlock() }) { _, dy -> total += dy }
                    },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Boot(0) { ClockWidget(prefs, Modifier.fillMaxWidth(), null) }
                Boot(1) { NowBox() }
                Boot(2) { NowPlaying() }
                Boot(3) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BatteryWidget(Modifier.weight(1f).fillMaxHeight(), null)
                        NotificationsBox(Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Spacer(Modifier.weight(1f))
                Boot(4) {
                    TermBox(L("unlock", "desbloquear"), Modifier.fillMaxWidth(), onClick = onUnlock) {
                        Row(verticalAlignment = Alignment.CenterVertically) { T("$ unlock ", 18.sp, pal.dim); Cursor() }
                        T(L("swipe up", "deslize pra cima"), 11.sp, pal.dim)
                    }
                }
            }
        }
    }
}

/** Each window shows up a moment after the one above it, like a terminal printing them. */
@Composable
private fun Boot(order: Int, content: @Composable () -> Unit) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(order * 70L); shown.animateTo(1f, tween(160)) }
    Box(Modifier.graphicsLayer { alpha = shown.value; translationY = (1 - shown.value) * 12.dp.toPx() }) { content() }
}

/** Date, the weather right now and the next alarm, on one line. */
@Composable
private fun NowBox() {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val L = LocalLang.current
    val weather by produceState<WeatherNow?>(null) { value = loadWeather() }
    val alarm = remember { ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }
    val now = remember { ZonedDateTime.now() }
    TermBox(weather?.city?.lowercase() ?: L("today", "hoje"), Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(now.format(DAY.withLocale(L.locale)).lowercase(), 14.sp)
            weather?.let { w ->
                T("  ·  ", 14.sp, pal.dim)
                T(glyph(w.code), 16.sp)
                T(" ${w.temp}°c ${describe(w.code, L)}", 14.sp, maxLines = 1)
            }
        }
        alarm?.let {
            val at = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
            T(L("alarm ", "alarme ") + at.format(HM) + if (at.toLocalDate() != now.toLocalDate()) " · " + at.format(DAY.withLocale(L.locale)).lowercase() else "",
                12.sp, pal.dim)
        }
    }
}

/** How many notifications each app has (counted by MediaListener; nothing of what they say). */
@Composable
private fun NotificationsBox(modifier: Modifier) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val L = LocalLang.current
    val counts by MediaListener.counts
    TermBox(L("notifications", "notificações"), modifier) {
        if (!LockService.hasMedia(ctx)) { T(L("allow notification access in the launcher settings", "permita o acesso a notificações nas configurações do launcher"), 11.sp, pal.dim); return@TermBox }
        if (counts.isEmpty()) { T(L("none", "nenhuma"), 14.sp, pal.dim); return@TermBox }
        T("${counts.values.sum()}", 26.sp)
        counts.entries.sortedByDescending { it.value }.take(3).forEach { (pkg, n) ->
            T("${appLabel(ctx, pkg)} ×$n", 12.sp, pal.dim, maxLines = 1)
        }
    }
}

private fun appLabel(ctx: Context, pkg: String) = runCatching {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString().lowercase()
}.getOrDefault(pkg.substringAfterLast('.'))

// ---------- now playing: any app's media session (YouTube, Spotify…), with its cover in ASCII ----------

private data class Playing(
    val title: String, val artist: String, val app: String, val art: Bitmap?, val playing: Boolean,
    val position: Long, val updated: Long, val speed: Float, val duration: Long, val controller: MediaController,
)

/** Follows the media session that is playing (or the most recent one), through MediaListener's access. */
@Composable
private fun rememberPlaying(): State<Playing?> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf<Playing?>(null) }
    DisposableEffect(Unit) {
        val sessions = ctx.getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(ctx, MediaListener::class.java)
        var current: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) { state.value = current?.let { read(ctx, it) } }
            override fun onPlaybackStateChanged(s: PlaybackState?) { state.value = current?.let { read(ctx, it) } }
        }
        fun pick(list: List<MediaController>) {
            current?.unregisterCallback(callback)
            current = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
            current?.registerCallback(callback)
            state.value = current?.let { read(ctx, it) }
        }
        val changed = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }
        runCatching {                                   // without notification access Android says no: no box content
            sessions.addOnActiveSessionsChangedListener(changed, me)
            pick(sessions.getActiveSessions(me))
        }
        onDispose {
            runCatching { sessions.removeOnActiveSessionsChangedListener(changed) }
            current?.unregisterCallback(callback)
        }
    }
    return state
}

private fun read(ctx: Context, c: MediaController): Playing? {
    val md = c.metadata
    val ps = c.playbackState
    val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title?.toString() ?: ""
    if (title.isBlank()) return null
    return Playing(
        title = title,
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.description?.subtitle?.toString() ?: "",
        app = appLabel(ctx, c.packageName),
        art = md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
        playing = ps?.state == PlaybackState.STATE_PLAYING,
        position = ps?.position ?: 0, updated = ps?.lastPositionUpdateTime ?: 0, speed = ps?.playbackSpeed ?: 1f,
        duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
        controller = c,
    )
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun NowPlaying() {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val L = LocalLang.current
    val p by rememberPlaying()
    val media = p
    if (media == null) {
        if (!LockService.hasMedia(ctx)) return              // the notifications box already says what to allow
        TermBox(L("now playing", "tocando"), Modifier.fillMaxWidth()) { T(L("-- nothing playing --", "-- nada tocando --"), 12.sp, pal.dim) }
        return
    }
    // the position moves on its own between Android's updates
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(media.playing) { while (media.playing) { now = SystemClock.elapsedRealtime(); delay(500) } }
    val pos = (media.position + if (media.playing) ((now - media.updated) * media.speed).toLong() else 0L)
        .let { if (media.duration > 0) it.coerceAtMost(media.duration) else it }
    val cover by produceState<TextArt?>(null, media.art) {
        value = media.art?.let { withContext(Dispatchers.Default) { toTextArt(it, 32, 16, false) } }
    }
    TermBox("${L("now playing", "tocando")} · ${media.app}", Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            cover?.let { TextArtCanvas(it, Modifier.size(84.dp).padding(end = 10.dp)) }
            Column(Modifier.weight(1f)) {
                T(media.title, 15.sp, maxLines = 2)
                if (media.artist.isNotBlank()) T(media.artist, 12.sp, pal.dim, maxLines = 1)
                if (media.duration > 0) {
                    val n = 18
                    val done = (pos * n / media.duration).toInt().coerceIn(0, n)
                    T("[" + "=".repeat(done) + (if (done < n) ">" else "") + "-".repeat((n - done - 1).coerceAtLeast(0)) + "]", 12.sp, pal.fg, Modifier.padding(top = 6.dp), maxLines = 1)
                    T("${clock(pos)} / ${clock(media.duration)}", 11.sp, pal.dim)
                }
            }
        }
        val t = media.controller.transportControls
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Control("[ |< ]") { t.skipToPrevious() }
            Control(if (media.playing) "[ || ]" else "[ >  ]") { if (media.playing) t.pause() else t.play() }
            Control("[ >| ]") { t.skipToNext() }
        }
    }
}

@Composable
private fun Control(text: String, onClick: () -> Unit) =
    T(text, 16.sp, LocalPalette.current.fg, Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp))

// ---------- the glitch on the way out ----------

private const val GLITCH_MS = 380f
private val RED = ColorFilter.tint(Color(1f, 0f, 0f), BlendMode.Modulate)
private val CYAN = ColorFilter.tint(Color(0f, 1f, 1f), BlendMode.Modulate)

/**
 * Draws [content] normally; when [active] turns true it takes a picture of it and tears it apart for a
 * moment — bands shifted sideways, red and cyan pulled apart, a few lines dropped — then collapses it
 * into one bright line like an old CRT switching off, and calls [onDone].
 */
@Composable
private fun Glitch(active: Boolean, onDone: () -> Unit, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    var shot by remember { mutableStateOf<ImageBitmap?>(null) }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) { shot = null; return@LaunchedEffect }
        shot = layer.toImageBitmap()
        val start = androidx.compose.runtime.withFrameMillis { it }
        do { t = ((androidx.compose.runtime.withFrameMillis { it } - start) / GLITCH_MS).coerceAtMost(1f) } while (t < 1f)
        onDone()
    }
    Box(Modifier.fillMaxSize().drawWithContent {
        val s = shot
        if (s == null) { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
        else drawGlitch(s, t)
    }) { content() }
}

private fun DrawScope.drawGlitch(img: ImageBitmap, t: Float) {
    drawRect(Color.Black)
    val w = img.width; val h = img.height
    val rnd = Random((t * 60).toInt())                         // a new tear every few frames
    val amp = (t / 0.7f).coerceAtMost(1f)
    val squash = if (t > 0.7f) 1f - (t - 0.7f) / 0.3f else 1f   // the CRT switching off
    scale(1f + (1 - squash) * 0.15f, squash.coerceAtLeast(0.003f), center) {
        var y = 0
        while (y < h) {
            val band = rnd.nextInt((h / 70).coerceAtLeast(1), (h / 12).coerceAtLeast(2)).coerceAtMost(h - y)
            val torn = rnd.nextFloat() < 0.3f + 0.6f * amp
            if (torn && rnd.nextFloat() < 0.15f * amp) { y += band; continue }       // a dropped line
            val dx = if (torn) ((rnd.nextFloat() * 2 - 1) * w * 0.14f * amp).toInt() else 0
            val split = (w * 0.01f * (0.4f + amp)).toInt()
            val src = IntOffset(0, y); val sz = IntSize(w, band)
            drawImage(img, src, sz, IntOffset(dx + split, y), sz, colorFilter = RED, blendMode = BlendMode.Plus)
            drawImage(img, src, sz, IntOffset(dx - split, y), sz, colorFilter = CYAN, blendMode = BlendMode.Plus)
            y += band
        }
        repeat((7 * amp).toInt()) {
            drawRect(Color.White.copy(alpha = 0.22f), Offset(0f, rnd.nextFloat() * size.height), Size(size.width, 1.5f))
        }
    }
    if (squash < 1f) drawRect(Color.White.copy(alpha = (1 - squash) * 0.9f), Offset(0f, size.height / 2 - 1f), Size(size.width, 2f))
}
