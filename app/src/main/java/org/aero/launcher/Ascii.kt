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
        val cellH = size.height * 0.70f / art.rows
        val cellW = cellH / 2
        val left = (size.width - cellW * art.cols) / 2
        val top = size.height - bottomGap - cellH * art.rows
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = cellW / 0.6f }
        val baseline = (cellH - paint.textSize) / 2 - paint.fontMetrics.ascent
        val fg = pal.fg.toArgb()
        fun drawCell(c: android.graphics.Canvas, r: Int, col: Int, ch: Char, level: Int) {
            paint.color = fg
            paint.alpha = (25 + level * 25).coerceAtMost(255)
            c.drawText(ch.toString(), left + col * cellW, top + r * cellH + baseline, paint)
        }
        val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
        val canvas = android.graphics.Canvas(bitmap.asAndroidBitmap())
        canvas.drawColor(pal.bg.toArgb())
        for (r in 0 until art.rows) for (col in 0 until art.cols) {
            val ch = art.chars[r][col]
            if (ch != ' ') drawCell(canvas, r, col, ch, art.levels[r][col] - '0')
        }
        val erase = Paint().apply { color = pal.bg.toArgb() }
        onDrawBehind {
            drawImage(bitmap)
            if (shimmer && art.filled.isNotEmpty()) drawIntoCanvas { cv ->
                val rnd = Random(tick)
                repeat(10) {
                    val cell = art.filled[rnd.nextInt(art.filled.size)]
                    val r = cell / art.cols; val col = cell % art.cols
                    cv.nativeCanvas.drawRect(left + col * cellW, top + r * cellH, left + (col + 1) * cellW, top + (r + 1) * cellH, erase)
                    drawCell(cv.nativeCanvas, r, col, SHIMMER[rnd.nextInt(SHIMMER.length)], 9)
                }
            }
        }
    })
}
