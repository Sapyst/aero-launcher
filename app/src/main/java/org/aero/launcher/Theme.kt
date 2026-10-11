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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* The same look as the desktop (Arch Aero OS): monochrome, monospace, thin outlined boxes with a
   label sitting on the top border (textfox/superfile style). Follows the phone's dark/light mode;
   the dark one is made for OLED: true black, so the screen's pixels are simply off.
   Boxes and panels are frosted glass: the background behind them, blurred, under a see-through
   fill (like the desktop's "carbon" windows). Opacity, blur, font and sizes come from the settings. */

data class Palette(
    val fg: Color,      // text
    val dim: Color,     // secondary text
    val line: Color,    // box borders
    val fill: Color,    // box background (its alpha = the opacity setting)
    val scrim: Color,   // behind panels (search, settings, menus)
    val bg: Color,      // screen background when the launcher draws its own (OLED: pure black = pixels off)
)

private val Dark = Palette(
    fg = Color(0xFFF0F0F0), dim = Color(0xFF8E8E8E), line = Color(0xFF3A3A3A),
    fill = Color(0xFF000000), scrim = Color(0xFF000000), bg = Color(0xFF000000),
)
private val Light = Palette(
    fg = Color(0xFF1E1E1E), dim = Color(0xFF666666), line = Color(0xFFC6C6C6),
    fill = Color(0xFFF6F6F6), scrim = Color(0xFFF6F6F6), bg = Color(0xFFF4F4F4),
)

val LocalPalette = staticCompositionLocalOf { Dark }
val Mono = FontFamily(Font(R.font.jetbrains_mono))
val LocalFont = staticCompositionLocalOf { Mono }

/** The background already blurred (small, scaled up when drawn): image pixel × scale = window pixel. */
class Backdrop(val image: ImageBitmap, val scale: Float)
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * The launcher's language: English by default, Portuguese as an option in its settings.
 * Every text is written with both versions side by side: L("clock", "relógio").
 */
class Lang(val pt: Boolean) {
    operator fun invoke(en: String, pt: String) = if (this.pt) pt else en
    val locale: java.util.Locale = if (pt) java.util.Locale("pt", "BR") else java.util.Locale.ENGLISH
}
val LocalLang = staticCompositionLocalOf { Lang(false) }

fun fontFamily(name: String) = when (name) {
    "monospace" -> FontFamily.Monospace
    "sans" -> FontFamily.SansSerif
    "serif" -> FontFamily.Serif
    else -> Mono
}

/**
 * theme: "system" follows the phone, "dark" (OLED) or "light" force one.
 * opacity: of the boxes (the panels are a bit more solid). textScale multiplies every text size:
 * it changes the "font scale" of the whole screen at once.
 */
@Composable
fun AeroTheme(
    theme: String, lang: String, opacity: Float = 0.9f, font: String = "jetbrains", textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val base = if (dark) Dark else Light
    val pal = base.copy(
        fill = base.fill.copy(alpha = opacity),
        scrim = base.scrim.copy(alpha = (opacity + 0.12f).coerceIn(0.55f, 0.97f)),
    )
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalPalette provides pal,
        LocalLang provides Lang(lang == "pt"),
        LocalFont provides fontFamily(font),
        LocalDensity provides Density(d.density, d.fontScale * textScale),
    ) { content() }
}

/** True when the dark palette is in use (status bar icons must be light then). */
@Composable
fun isDark() = LocalPalette.current.bg.luminance() < 0.5f

/** Draws the blurred background that sits behind this spot of the window (frosted glass). */
fun DrawScope.drawBackdrop(backdrop: Backdrop, windowPos: Offset) {
    val s = backdrop.scale
    val img = backdrop.image
    val sw = (size.width / s).toInt().coerceIn(1, img.width)
    val sh = (size.height / s).toInt().coerceIn(1, img.height)
    val sx = (windowPos.x / s).toInt().coerceIn(0, img.width - sw)
    val sy = (windowPos.y / s).toInt().coerceIn(0, img.height - sh)
    drawImage(img, IntOffset(sx, sy), IntSize(sw, sh), dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
}

/** Full-screen panels (search, settings, menus): blurred background + the see-through scrim. */
fun Modifier.glassScrim(): Modifier = composed {
    val pal = LocalPalette.current
    val backdrop = LocalBackdrop.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    this.onGloballyPositioned { pos = it.positionInWindow() }.drawBehind {
        if (backdrop != null) drawBackdrop(backdrop, pos)
        drawRect(pal.scrim)
    }
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
        style = TextStyle(fontFamily = LocalFont.current, fontSize = size, color = color),
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
    val font = LocalFont.current
    val backdrop = LocalBackdrop.current
    val fill = if (opaque) pal.fill.copy(alpha = 1f) else pal.fill
    val measurer = rememberTextMeasurer()
    val labelLayout = remember(label, pal, font) {
        measurer.measure(label, TextStyle(fontFamily = font, fontSize = 12.sp, color = pal.fg))
    }
    var windowPos by remember { mutableStateOf(Offset.Zero) }
    val click = if (onClick != null || onLongClick != null)
        Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick) else Modifier
    Column(
        modifier
            .padding(top = 8.dp)
            .onGloballyPositioned { windowPos = it.positionInWindow() }
            .drawBehind {
                val r = 10.dp.toPx()
                val sw = 1.dp.toPx()
                val l = sw / 2; val t = sw / 2; val rt = size.width - sw / 2; val b = size.height - sw / 2
                // frosted glass: what is behind the box, blurred, clipped to the rounded shape
                if (backdrop != null && !opaque) {
                    clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r))) }) {
                        drawBackdrop(backdrop, windowPos)
                    }
                }
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
            .clipToBounds()                          // a widget made small never spills over its neighbours
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}
