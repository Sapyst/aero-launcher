package org.aero.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * The fetch logo: the robot, or any image you pick in the settings, turned into ASCII ON the phone.
 * Its size is a PROPORTION of the widget (the logo column), never a fixed size: the number of
 * characters and their size are worked out again for every widget size, keeping the image's shape.
 * The info text next to it keeps its own size, whatever you do with the widget.
 */

/** Characters and their brightness (0-9), like the Eris asset. */
class TextArt(val cols: Int, val rows: Int, val chars: List<String>, val levels: List<IntArray>)

private const val RAMP = " .'`^\",:;Il!i><~+_-?][}{1)(|tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$"

/** Copies the picked image into the app's own files, at most 512 px (it is only a source for ASCII). */
fun saveLogo(ctx: Context, uri: Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }!!
    val file = File(ctx.filesDir, "fetch_logo.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    "${file.path}?${System.currentTimeMillis()}"          // the time makes the widget reload it
}.getOrNull()

/** The image as [cols]×[rows] characters: brighter = denser and brighter character (or the opposite). */
fun toTextArt(src: Bitmap, cols: Int, rows: Int, invert: Boolean): TextArt {
    val small = Bitmap.createScaledBitmap(src, cols, rows, true)
    val lum = FloatArray(cols * rows) { i ->
        val p = small.getPixel(i % cols, i / cols)
        val a = (p ushr 24) / 255f
        val l = (0.2126f * (p shr 16 and 255) + 0.7152f * (p shr 8 and 255) + 0.0722f * (p and 255)) / 255f
        (if (invert) 1 - l else l) * a                     // transparent parts count as empty
    }
    val sorted = lum.sorted()
    val lo = sorted[(sorted.size * 0.02).toInt()]
    val hi = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)].coerceAtLeast(lo + 0.01f)
    val chars = ArrayList<String>(rows); val levels = ArrayList<IntArray>(rows)
    for (r in 0 until rows) {
        val line = StringBuilder(); val lv = IntArray(cols)
        for (c in 0 until cols) {
            val t = ((lum[r * cols + c] - lo) / (hi - lo)).coerceIn(0f, 1f)
            line.append(RAMP[(t * (RAMP.length - 1)).toInt()])
            lv[c] = (t * 9).toInt()
        }
        chars += line.toString(); levels += lv
    }
    return TextArt(cols, rows, chars, levels)
}

private val ROBOT_ART by lazy {
    val w = ROBOT.maxOf { it.length }
    TextArt(w, ROBOT.size, ROBOT.map { it.padEnd(w) }, ROBOT.map { IntArray(w) { 6 } })
}

/** Fills its box with the logo, keeping the image's proportions, centred. */
@Composable
fun FetchLogo(path: String, invert: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val minCell = with(LocalDensity.current) { 4.dp.toPx() }
    val typeface = remember { ResourcesCompat.getFont(ctx, R.font.jetbrains_mono) }
    val image by produceState<Bitmap?>(null, path) {
        value = if (path.isEmpty()) null else withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path.substringBefore('?')) }.getOrNull() }
    }
    BoxWithConstraints(modifier) {
        val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
        val img = image
        // how many characters: the image fitted in the box (its own proportions), cells of 1:2
        val (cols, rows) = if (img == null) ROBOT_ART.cols to ROBOT_ART.rows else {
            val aspect = img.width.toFloat() / img.height
            val w = minOf(boxW, boxH * aspect)
            val c = (w / minCell).toInt().coerceIn(8, 64)
            c to ((w / aspect) / (2 * w / c)).toInt().coerceAtLeast(1)
        }
        val art by produceState<TextArt?>(null, img, cols, rows, invert) {
            value = if (img == null) ROBOT_ART else withContext(Dispatchers.Default) { toTextArt(img, cols, rows, invert) }
        }
        Canvas(Modifier.fillMaxSize()) {
            val a = art ?: return@Canvas
            val cw = minOf(size.width / a.cols, size.height / (a.rows * 2f))
            val ch = cw * 2
            val left = (size.width - cw * a.cols) / 2
            val top = (size.height - ch * a.rows) / 2
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = cw / 0.6f; color = pal.fg.toArgb() }
            val baseline = (ch - paint.textSize) / 2 - paint.fontMetrics.ascent
            drawIntoCanvas { cv ->
                for (r in 0 until a.rows) for (c in 0 until a.cols) {
                    val chr = a.chars[r][c]
                    if (chr == ' ') continue
                    paint.alpha = (60 + a.levels[r][c] * 22).coerceAtMost(255)
                    cv.nativeCanvas.drawText(chr.toString(), left + c * cw, top + r * ch + baseline, paint)
                }
            }
        }
    }
}
