package org.aero.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every app icon in black and white, automatically (also for apps installed later: icons are made
 * when they are shown, never stored):
 *  - apps that ship a MONOCHROME icon (Android 13+ "themed icons") are drawn the Pixel way: that
 *    single-colour drawing, in the text colour, on a round plate with a thin border;
 *  - the others: the original icon with the colour removed (saturation 0). If it comes out mostly
 *    light on the dark theme (or mostly dark on the light one) it is inverted, so a white icon does
 *    not become a bright blob on the OLED black.
 */
class IconCache(private val ctx: Context, private val repo: AppRepository) {
    private val cache = LruCache<String, ImageBitmap>(400)

    fun get(app: App, sizePx: Int, pal: Palette): ImageBitmap? {
        val key = "${app.key}|$sizePx|${pal.fg.toArgb()}"
        cache.get(key)?.let { return it }
        val drawable = repo.icon(app) ?: return null
        val bmp = render(drawable, sizePx, pal).asImageBitmap()
        cache.put(key, bmp)
        return bmp
    }

    fun clear() = cache.evictAll()

    private fun render(d: Drawable, size: Int, pal: Palette): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val mono = if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) d.monochrome else null
        if (mono != null) {
            val r = size / 2f
            paint.color = pal.fill.copy(alpha = 1f).toArgb()
            c.drawCircle(r, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size / 40f
            paint.color = pal.line.toArgb()
            c.drawCircle(r, r, r - paint.strokeWidth, paint)
            c.save()
            c.clipPath(Path().apply { addCircle(r, r, r, Path.Direction.CW) })
            // the layer is 108 units with the visible 72 in the middle: draw it 1.5x, centred
            val m = mono.mutate()
            m.setTint(pal.fg.toArgb())
            m.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
            m.draw(c)
            c.restore()
        } else {
            val tmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(tmp))
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            if (shouldInvert(tmp, pal)) matrix.postConcat(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )))
            paint.colorFilter = ColorMatrixColorFilter(matrix)
            c.drawBitmap(tmp, 0f, 0f, paint)
            tmp.recycle()
        }
        return out
    }

    /** Average brightness of the visible pixels against the theme. */
    private fun shouldInvert(bmp: Bitmap, pal: Palette): Boolean {
        var sum = 0.0; var n = 0
        val step = (bmp.width / 16).coerceAtLeast(1)
        for (y in 0 until bmp.height step step) for (x in 0 until bmp.width step step) {
            val p = bmp.getPixel(x, y)
            if ((p ushr 24) < 128) continue
            sum += (0.2126 * (p shr 16 and 255) + 0.7152 * (p shr 8 and 255) + 0.0722 * (p and 255)) / 255
            n++
        }
        if (n == 0) return false
        val avg = sum / n
        val darkTheme = pal.bg.red + pal.bg.green + pal.bg.blue < 1.5f
        return if (darkTheme) avg > 0.62 else avg < 0.38
    }
}

/** An app's black-and-white icon, made off the main thread the first time. */
@Composable
fun MonoIcon(icons: IconCache, app: App, size: Dp, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, app.key, px, pal) {
        value = withContext(Dispatchers.Default) { icons.get(app, px, pal) }
    }
    Box(modifier.size(size)) { bitmap?.let { Image(it, contentDescription = app.label, modifier = Modifier.size(size)) } }
}
