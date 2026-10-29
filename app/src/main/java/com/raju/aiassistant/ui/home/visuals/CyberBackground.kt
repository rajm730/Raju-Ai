package com.raju.aiassistant.ui.home.visuals

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Glyphs of the falling "code rain": binary, hex and code symbols. ASCII only — glyphs from other
 * scripts need font fallback on every draw, which is far too slow on budget phones.
 */
private val GLYPHS = "0101011001ABCDEF0123456789<>/{}[]=+*#$%&XYZ".toCharArray()

/** The rain and HUD redraw at this rate, independent of the reactor's frame rate. */
private const val RAIN_FPS = 12f

/**
 * Full-screen sci-fi backdrop for the home screen: falling code columns, a faint grid, HUD corner
 * brackets, a slow scan line and live telemetry text. Everything is drawn on one Canvas with the
 * shared animation clock, kept dim so the reactor and text stay readable, and fades out around the
 * reactor. [density] 0..1 controls how many code columns run (lower on low-power mode).
 *
 * @param focus centre of the reactor as a fraction of the screen (x, y), where the rain fades out.
 * @param telemetry short status lines printed in the HUD (real assistant state, not decoration).
 */
@Composable
fun CyberBackground(
    color: Color,
    accent: Color,
    time: () -> Float,
    density: Float,
    telemetry: () -> List<String>,
    modifier: Modifier = Modifier,
    focus: Offset = Offset(0.5f, 0.42f),
) {
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
    }
    // Static parts (glow, grid, vignette) are drawn once, not every frame.
    Canvas(modifier) { drawBackdrop(color, accent, focus) }
    // The animated layer only redraws when this tick changes (RAIN_FPS times a second),
    // so the reactor can animate smoothly on top without repainting the whole screen.
    val tick by remember(time) { derivedStateOf { (time() * RAIN_FPS).toInt() } }
    Canvas(modifier.graphicsLayer()) {
        val t = tick / RAIN_FPS
        drawCodeRain(paint, color, t, density, focus)
        drawScanLine(color, t)
        drawHudFrame(color, accent, t)
        drawTelemetry(paint, color, accent, t, telemetry())
    }
}

private fun DrawScope.drawBackdrop(color: Color, accent: Color, focus: Offset) {
    val c = Offset(size.width * focus.x, size.height * focus.y)
    drawRect(Brush.radialGradient(listOf(color.copy(alpha = 0.14f), accent.copy(alpha = 0.05f), Color.Transparent), center = c, radius = size.maxDimension * 0.65f))
    // Faint perspective grid in the lower part (a "floor" under the reactor).
    val grid = color.copy(alpha = 0.05f)
    val step = 28f * density
    var x = 0f
    while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height)); x += step }
    var y = 0f
    while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y)); y += step }
    // Vignette so the edges fall into darkness.
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), center = center, radius = size.maxDimension * 0.75f))
}

private fun DrawScope.drawCodeRain(paint: Paint, color: Color, t: Float, density: Float, focus: Offset) {
    if (density <= 0f) return
    val cell = 17f * this.density
    paint.textSize = cell * 0.85f
    paint.isFakeBoldText = false
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    val fx = size.width * focus.x
    val fy = size.height * focus.y
    val clearRadius = size.minDimension * 0.42f
    val head = Color(0xFFE8FFFF)
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        for (col in 0 until cols) {
            if (pseudoRandom(col * 31 + 7) > density) continue // only some columns run
            val speed = 5f + pseudoRandom(col * 13 + 1) * 9f // rows per second
            val length = 5 + (pseudoRandom(col * 17 + 3) * 9f).toInt()
            val cycle = rows + length + (pseudoRandom(col * 23 + 9) * rows).toInt()
            val headRow = ((t * speed + pseudoRandom(col * 19 + 5) * cycle) % cycle).toInt()
            val x = col * cell
            for (k in 0 until length) {
                val row = headRow - k
                if (row < 0 || row >= rows) continue
                val y = (row + 1) * cell
                // Fade the rain around the reactor so it never fights with it.
                val d = hypot(x - fx, y - fy)
                val clear = ((d - clearRadius * 0.55f) / (clearRadius * 0.6f)).coerceIn(0f, 1f)
                if (clear <= 0.02f) continue
                val fade = 1f - k / length.toFloat()
                val alpha = (if (k == 0) 0.85f else 0.32f * fade) * clear
                paint.color = (if (k == 0) head else color).copy(alpha = alpha).toArgb()
                // Glyphs change now and then, like the classic terminal rain.
                val glyph = (pseudoRandom(col * 7919 + row * 104729 + (t * (2f + k % 3)).toInt()) * GLYPHS.size).toInt().coerceIn(0, GLYPHS.size - 1)
                nc.drawText(GLYPHS, glyph, 1, x, y, paint) // no per-glyph allocation
            }
        }
    }
}

private fun DrawScope.drawScanLine(color: Color, t: Float) {
    val y = ((t * 0.08f) % 1f) * size.height
    val band = 70f * density
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.10f), Color.Transparent), startY = y - band, endY = y + band),
        topLeft = Offset(0f, y - band),
        size = androidx.compose.ui.geometry.Size(size.width, band * 2),
    )
    drawLine(color.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f * density)
}

private fun DrawScope.drawHudFrame(color: Color, accent: Color, t: Float) {
    val m = 10f * density
    val len = 34f * density
    val w = 2f * density
    val pulse = 0.35f + 0.25f * ((sin(t * 2.2f) + 1f) / 2f)
    val c = color.copy(alpha = pulse)
    // Corner brackets
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(c, Offset(x, y), Offset(x + dx * len, y), strokeWidth = w)
        drawLine(c, Offset(x, y), Offset(x, y + dy * len), strokeWidth = w)
    }
    corner(m, m, 1f, 1f)
    corner(size.width - m, m, -1f, 1f)
    corner(m, size.height - m, 1f, -1f)
    corner(size.width - m, size.height - m, -1f, -1f)
    // Side rulers with a moving marker
    val rulerTop = size.height * 0.2f
    val rulerBottom = size.height * 0.75f
    var y = rulerTop
    var i = 0
    while (y < rulerBottom) {
        val long = i % 5 == 0
        drawLine(color.copy(alpha = 0.22f), Offset(m, y), Offset(m + (if (long) 12f else 6f) * density, y), strokeWidth = 1f * density)
        drawLine(color.copy(alpha = 0.22f), Offset(size.width - m, y), Offset(size.width - m - (if (long) 12f else 6f) * density, y), strokeWidth = 1f * density)
        y += 9f * density
        i++
    }
    val marker = rulerTop + (rulerBottom - rulerTop) * ((sin(t * 0.7f) + 1f) / 2f)
    drawLine(accent.copy(alpha = 0.8f), Offset(m, marker), Offset(m + 18f * density, marker), strokeWidth = 2.5f * density)
    drawLine(accent.copy(alpha = 0.8f), Offset(size.width - m, size.height - marker + rulerTop * 0.3f), Offset(size.width - m - 18f * density, size.height - marker + rulerTop * 0.3f), strokeWidth = 2.5f * density)
}

private fun DrawScope.drawTelemetry(paint: Paint, color: Color, accent: Color, t: Float, lines: List<String>) {
    val text = 9.5f * density
    paint.textSize = text
    paint.isFakeBoldText = true
    // Top corners of the reactor's square are empty (the reactor is round): the HUD text sits there.
    val x = 24f * density
    var y = size.height * 0.165f
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        lines.forEachIndexed { i, line ->
            paint.color = (if (i == 0) accent else color).copy(alpha = 0.55f).toArgb()
            nc.drawText(line, x, y, paint)
            y += text * 1.35f
        }
        // Right side: scrolling memory addresses, like a terminal dump.
        paint.isFakeBoldText = false
        paint.color = color.copy(alpha = 0.28f).toArgb()
        val right = size.width - 24f * density
        val base = (t * 3f).toInt()
        for (k in 0 until 6) {
            val v = (pseudoRandom(base + k) * 0xFFFFFF).toInt()
            val s = "0x%06X".format(v)
            nc.drawText(s, right - paint.measureText(s), size.height * 0.165f + text * k * 1.35f, paint)
        }
    }
}
