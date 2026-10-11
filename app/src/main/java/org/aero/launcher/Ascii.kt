package org.aero.launcher

import android.content.Context
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * ASCII art made by tools/ascii_grid.py: one character and one brightness (0-9) per cell.
 * The launcher draws it itself, so it is sharp on any screen and the background stays true black.
 */
class AsciiArt(val cols: Int, val rows: Int, val chars: List<String>, val levels: List<String>) {
    /** Cells that have something drawn (the shimmer only touches these). */
    val filled: List<Int> = (0 until rows).flatMap { r -> (0 until cols).filter { c -> chars[r][c] != ' ' }.map { c -> r * cols + c } }

    companion object {
        fun load(ctx: Context, asset: String): AsciiArt {
            val lines = ctx.assets.open(asset).bufferedReader().readLines()
            val (cols, rows) = lines[0].split(" ").map { it.toInt() }
            fun pad(s: String) = s.padEnd(cols).take(cols)
            return AsciiArt(cols, rows, lines.subList(1, 1 + rows).map(::pad), lines.subList(1 + rows, 1 + 2 * rows).map(::pad))
        }
    }
}

private const val SHIMMER = ".:-=+*#%@"

/** Where the statue goes on a screen of this size: 70% of the height, centred, above the bottom bar. */
private class AsciiLayout(art: AsciiArt, width: Float, height: Float, bottomGap: Float) {
    val cellH = height * 0.70f / art.rows
    val cellW = cellH / 2
    val left = (width - cellW * art.cols) / 2
    val top = height - bottomGap - cellH * art.rows
}

/** Draws the whole statue on [canvas] (used full size for the background, small for the blurred glass). */
private fun drawAscii(canvas: android.graphics.Canvas, art: AsciiArt, w: Float, h: Float, bottomGap: Float,
                      typeface: android.graphics.Typeface?, fg: Int, bg: Int): AsciiLayout {
    val lay = AsciiLayout(art, w, h, bottomGap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = lay.cellW / 0.6f; color = fg }
    val baseline = (lay.cellH - paint.textSize) / 2 - paint.fontMetrics.ascent
    canvas.drawColor(bg)
    for (r in 0 until art.rows) for (col in 0 until art.cols) {
        val ch = art.chars[r][col]
        if (ch == ' ') continue
        paint.alpha = (25 + (art.levels[r][col] - '0') * 25).coerceAtMost(255)
        canvas.drawText(ch.toString(), lay.left + col * lay.cellW, lay.top + r * lay.cellH + baseline, paint)
    }
    return lay
}

/**
 * The frosted-glass source: the background drawn at 1/4 size and blurred (three box-blur passes ≈ a
 * gaussian). Small and soft, so boxes and panels can show "what is behind them" cheaply.
 */
fun makeBackdrop(ctx: Context, art: AsciiArt, width: Int, height: Int, bottomGapPx: Float, fg: Int, bg: Int): Backdrop {
    val scale = 4f
    val w = (width / scale).toInt().coerceAtLeast(1); val h = (height / scale).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    drawAscii(android.graphics.Canvas(bmp), art, w.toFloat(), h.toFloat(), bottomGapPx / scale,
        ResourcesCompat.getFont(ctx, R.font.jetbrains_mono), fg, bg)
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    repeat(3) { boxBlur(px, w, h, 4) }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return Backdrop(bmp.asImageBitmap(), scale)
}

/** One horizontal + one vertical pass of a moving-average blur over ARGB pixels. */
private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    val tmp = IntArray(px.size)
    fun pass(src: IntArray, dst: IntArray, len: Int, lines: Int, at: (line: Int, i: Int) -> Int) {
        for (line in 0 until lines) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) { val p = src[at(line, i.coerceIn(0, len - 1))]; a += p ushr 24; rr += p shr 16 and 255; g += p shr 8 and 255; b += p and 255 }
            val n = 2 * r + 1
            for (i in 0 until len) {
                dst[at(line, i)] = (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
                val out = src[at(line, (i - r).coerceIn(0, len - 1))]; val inn = src[at(line, (i + r + 1).coerceIn(0, len - 1))]
                a += (inn ushr 24) - (out ushr 24); rr += (inn shr 16 and 255) - (out shr 16 and 255)
                g += (inn shr 8 and 255) - (out shr 8 and 255); b += (inn and 255) - (out and 255)
            }
        }
    }
    pass(px, tmp, w, h) { line, i -> line * w + i }
    pass(tmp, px, h, w) { line, i -> i * w + line }
}

/**
 * The statue standing at the bottom of the screen, 70% of its height, on the palette's background.
 * The full drawing is made once into a bitmap; the "shimmer" (a few characters flickering, like a
 * live terminal) only redraws ten cells every 140 ms.
 */
@Composable
fun AsciiBackground(art: AsciiArt, shimmer: Boolean) {
    val ctx = LocalContext.current
    val pal = LocalPalette.current
    val bottomGap = with(LocalDensity.current) { 96.dp.toPx() }
    val typeface = remember { ResourcesCompat.getFont(ctx, R.font.jetbrains_mono) }
    var tick by remember { mutableIntStateOf(0) }
    if (shimmer) LaunchedEffect(Unit) { while (true) { delay(140); tick++ } }

    Box(Modifier.fillMaxSize().drawWithCache {
        val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
        val lay = drawAscii(android.graphics.Canvas(bitmap.asAndroidBitmap()), art, size.width, size.height, bottomGap,
            typeface, pal.fg.toArgb(), pal.bg.toArgb())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = lay.cellW / 0.6f; color = pal.fg.toArgb() }
        val baseline = (lay.cellH - paint.textSize) / 2 - paint.fontMetrics.ascent
        val erase = Paint().apply { color = pal.bg.toArgb() }
        onDrawBehind {
            drawImage(bitmap)
            if (shimmer && art.filled.isNotEmpty()) drawIntoCanvas { cv ->
                val rnd = Random(tick)
                repeat(10) {
                    val cell = art.filled[rnd.nextInt(art.filled.size)]
                    val r = cell / art.cols; val col = cell % art.cols
                    val x = lay.left + col * lay.cellW; val y = lay.top + r * lay.cellH
                    cv.nativeCanvas.drawRect(x, y, x + lay.cellW, y + lay.cellH, erase)
                    cv.nativeCanvas.drawText(SHIMMER[rnd.nextInt(SHIMMER.length)].toString(), x, y + baseline, paint)
                }
            }
        }
    })
}
