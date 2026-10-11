package org.aero.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* The same look as the desktop (Arch Aero OS): monochrome, monospace, thin outlined boxes with a
   label sitting on the top border (textfox/superfile style). Follows the phone's dark/light mode;
   the dark one is made for OLED: true black, so the screen's pixels are simply off. */

data class Palette(
    val fg: Color,      // text
    val dim: Color,     // secondary text
    val line: Color,    // box borders
    val fill: Color,    // box background (mostly opaque: the ASCII art behind must not fight the text)
    val scrim: Color,   // behind panels (search, settings)
    val bg: Color,      // screen background when the launcher draws its own (OLED: pure black = pixels off)
)

private val Dark = Palette(
    fg = Color(0xFFF0F0F0), dim = Color(0xFF8E8E8E), line = Color(0xFF3A3A3A),
    fill = Color(0xEB000000), scrim = Color(0xFA000000), bg = Color(0xFF000000),
)
private val Light = Palette(
    fg = Color(0xFF1E1E1E), dim = Color(0xFF666666), line = Color(0xFFC6C6C6),
    fill = Color(0xEBF6F6F6), scrim = Color(0xFAF6F6F6), bg = Color(0xFFF4F4F4),
)

val LocalPalette = staticCompositionLocalOf { Dark }
val Mono = FontFamily(Font(R.font.jetbrains_mono))

@Composable
fun AeroTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides if (isSystemInDarkTheme()) Dark else Light) { content() }
}

/** Plain text in the launcher's font. */
@Composable
fun T(
    text: String,
    size: TextUnit = 14.sp,
    color: Color = LocalPalette.current.fg,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text, modifier,
        style = TextStyle(fontFamily = Mono, fontSize = size, color = color),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** A box with a thin rounded border and its [label] written ON the top border, with a gap cut for it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TermBox(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    opaque: Boolean = false,                 // menus: nothing behind may show through
    content: @Composable ColumnScope.() -> Unit,
) {
    val pal = LocalPalette.current
    val fill = if (opaque) pal.fill.copy(alpha = 1f) else pal.fill
    val measurer = rememberTextMeasurer()
    val labelLayout = remember(label, pal) {
        measurer.measure(label, TextStyle(fontFamily = Mono, fontSize = 12.sp, color = pal.fg))
    }
    val click = if (onClick != null || onLongClick != null)
        Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick) else Modifier
    Column(
        modifier
            .padding(top = 8.dp)
            .drawBehind {
                val r = 10.dp.toPx()
                val sw = 1.dp.toPx()
                val l = sw / 2; val t = sw / 2; val rt = size.width - sw / 2; val b = size.height - sw / 2
                drawRoundRect(fill, cornerRadius = CornerRadius(r, r))
                val gapStart = 12.dp.toPx()
                val gapEnd = if (label.isEmpty()) gapStart else gapStart + labelLayout.size.width + 10.dp.toPx()
                val border = Path().apply {          // clockwise, starting right after the label
                    moveTo(gapEnd, t)
                    lineTo(rt - r, t)
                    arcTo(Rect(rt - 2 * r, t, rt, t + 2 * r), -90f, 90f, false)
                    lineTo(rt, b - r)
                    arcTo(Rect(rt - 2 * r, b - 2 * r, rt, b), 0f, 90f, false)
                    lineTo(l + r, b)
                    arcTo(Rect(l, b - 2 * r, l + 2 * r, b), 90f, 90f, false)
                    lineTo(l, t + r)
                    arcTo(Rect(l, t, l + 2 * r, t + 2 * r), 180f, 90f, false)
                    lineTo(gapStart, t)
                }
                drawPath(border, pal.line, style = Stroke(sw))
                if (label.isNotEmpty()) {
                    // the label sits on the border, half outside the box: give it its own backing so it
                    // stays readable on a light wallpaper too
                    drawRoundRect(
                        pal.fill.copy(alpha = maxOf(pal.fill.alpha, 0.9f)),
                        topLeft = Offset(gapStart + 1.dp.toPx(), -labelLayout.size.height / 2f),
                        size = androidx.compose.ui.geometry.Size(labelLayout.size.width + 8.dp.toPx(), labelLayout.size.height.toFloat()),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                    drawText(labelLayout, topLeft = Offset(gapStart + 5.dp.toPx(), -labelLayout.size.height / 2f))
                }
            }
            .then(click)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}
