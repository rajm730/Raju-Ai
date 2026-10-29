package com.raju.aiassistant.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.raju.aiassistant.domain.model.AssistantState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Which decorative HUD effects may run. They are decoration only: none of them shows (or pretends
 * to show) device data. Provided once at the root from the motion settings, so every effect can be
 * switched off together (Reduce motion, Low power, system "Remove animations") or for debugging.
 */
@Immutable
data class HudEffects(
    /** Moving light that travels along panel borders. */
    val borderTrace: Boolean = true,
    /** Labels "decode" from glyphs when they change. */
    val decodingText: Boolean = true,
    /** Pulsing status dots and the shimmer on the title. */
    val pulses: Boolean = true,
) {
    companion object {
        val Off = HudEffects(borderTrace = false, decodingText = false, pulses = false)

        /** Debug switch: set to false to draw the whole HUD static. */
        const val ENABLED = true

        fun forMotion(animate: Boolean, lowPower: Boolean): HudEffects = when {
            !ENABLED || !animate -> Off
            lowPower -> HudEffects(borderTrace = false, decodingText = true, pulses = false)
            else -> HudEffects()
        }
    }
}

val LocalHudEffects = staticCompositionLocalOf { HudEffects() }

/**
 * Holographic reveal: a light runs once around the panel's border when it appears (0..1), then
 * the panel is still. One pass instead of an endless loop keeps screens like Settings from
 * redrawing all the time (battery). Null when border traces are off.
 */
@Composable
fun rememberBorderTrace(durationMillis: Int = 1_400): (() -> Float)? {
    if (!LocalHudEffects.current.borderTrace) return null
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(durationMillis, easing = LinearEasing)) }
    return { progress.value }
}

/**
 * Glass HUD frame: translucent emerald glass, a thin technical border with cut corners, bright
 * corner brackets and (optionally) a light tracing the border. Drawing only; adds no layout.
 */
fun Modifier.hudFrame(
    accent: Color,
    cut: Dp = 10.dp,
    fillAlpha: Float = 0.10f,
    trace: (() -> Float)? = null,
): Modifier = drawBehind {
    val c = cut.toPx()
    val outline = cutCornerPath(size, c)
    drawPath(outline, Brush.verticalGradient(listOf(accent.copy(alpha = fillAlpha), accent.copy(alpha = fillAlpha * 0.25f), Color.Black.copy(alpha = 0.35f))))
    drawPath(outline, accent.copy(alpha = 0.28f), style = Stroke(1.dp.toPx()))
    drawCornerBrackets(accent.copy(alpha = 0.9f), c, (c * 1.8f).coerceAtMost(size.minDimension / 3f), 2.dp.toPx())
    trace?.invoke()?.takeIf { it < 1f }?.let { drawTrace(it, accent, c) }
}

private fun cutCornerPath(size: Size, c: Float) = Path().apply {
    moveTo(c, 0f)
    lineTo(size.width - c, 0f)
    lineTo(size.width, c)
    lineTo(size.width, size.height - c)
    lineTo(size.width - c, size.height)
    lineTo(c, size.height)
    lineTo(0f, size.height - c)
    lineTo(0f, c)
    close()
}

private fun DrawScope.drawCornerBrackets(color: Color, cut: Float, len: Float, width: Float) {
    val w = size.width
    val h = size.height
    // Top-left and bottom-right follow the cut; the other two are classic L brackets.
    drawLine(color, Offset(0f, cut + len * 0.6f), Offset(0f, cut), width, StrokeCap.Square)
    drawLine(color, Offset(0f, cut), Offset(cut, 0f), width, StrokeCap.Square)
    drawLine(color, Offset(cut, 0f), Offset(cut + len * 0.6f, 0f), width, StrokeCap.Square)
    drawLine(color, Offset(w, h - cut - len * 0.6f), Offset(w, h - cut), width, StrokeCap.Square)
    drawLine(color, Offset(w, h - cut), Offset(w - cut, h), width, StrokeCap.Square)
    drawLine(color, Offset(w - cut, h), Offset(w - cut - len * 0.6f, h), width, StrokeCap.Square)
    drawLine(color, Offset(w - len, 0f), Offset(w - cut, 0f), width * 0.6f)
    drawLine(color, Offset(0f, h - len), Offset(0f, h - cut), width * 0.6f)
}

/** A short comet of light running clockwise along the rectangle's edge. */
private fun DrawScope.drawTrace(progress: Float, accent: Color, inset: Float) {
    val w = size.width - 2 * inset
    val h = size.height - 2 * inset
    if (w <= 0f || h <= 0f) return
    val perimeter = 2 * (w + h)
    val tail = (perimeter * 0.12f).coerceAtMost(160.dp.toPx())
    fun point(d: Float): Offset {
        var x = ((d % perimeter) + perimeter) % perimeter
        return when {
            x < w -> Offset(inset + x, 0f)
            x < w + h -> { x -= w; Offset(size.width, inset + x) }
            x < 2 * w + h -> { x -= w + h; Offset(size.width - inset - x, size.height) }
            else -> { x -= 2 * w + h; Offset(0f, size.height - inset - x) }
        }
    }
    val head = progress * perimeter
    val steps = 10
    for (k in 0 until steps) {
        val a = point(head - tail * k / steps)
        val b = point(head - tail * (k + 1) / steps)
        // Skip the segment that would cut across a corner.
        if (abs(a.x - b.x) > 1f && abs(a.y - b.y) > 1f) continue
        val fade = 1f - k / steps.toFloat()
        drawLine(accent.copy(alpha = 0.9f * fade), a, b, (2.5f * fade + 0.5f).dp.toPx(), StrokeCap.Round)
    }
    drawCircle(Color.White.copy(alpha = 0.85f), 1.8.dp.toPx(), point(head))
}

private const val DECODE_GLYPHS = "01<>/{}[]#*+=ABCDEF"

/**
 * Text that "decodes" from technical glyphs whenever it changes (about 0.3 s), then stays still.
 * Screen readers always get the real text. Meant for short Latin labels (state names, codes); the
 * effect is skipped for Devanagari, whose letters must never be broken apart.
 */
@Composable
fun DecodingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    val enabled = LocalHudEffects.current.decodingText && text.none { it in 'ऀ'..'ॿ' }
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(text, enabled) {
        if (!enabled) { shown = text; return@LaunchedEffect }
        val frames = 10
        for (f in 1..frames) {
            shown = buildString {
                text.forEachIndexed { i, ch ->
                    val settled = i < text.length * f / frames
                    append(if (settled || ch.isWhitespace()) ch else DECODE_GLYPHS[(i * 7 + f * 13) % DECODE_GLYPHS.length])
                }
            }
            delay(28)
        }
        shown = text
    }
    Box(modifier.semantics { contentDescription = text }) {
        Text(shown, Modifier.clearAndSetSemantics { }, style = style, color = color, textAlign = textAlign, maxLines = 1)
    }
}

/**
 * Voice activity strip under the reactor. While Seema speaks the bars follow the real audio level
 * of her voice. Other states use clearly patterned motion (a travelling scan while listening, a
 * ripple while thinking) that does not pretend to be a microphone meter.
 */
@Composable
fun VoiceWaveform(state: AssistantState, accent: Color, time: () -> Float, speechLevel: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val t = time()
        val bars = 41
        val gap = size.width / bars
        val mid = size.height / 2f
        val barW = (gap * 0.42f).coerceAtLeast(1.5f)
        val level = speechLevel().coerceIn(0f, 1f)
        for (i in 0 until bars) {
            val x = gap * (i + 0.5f)
            val u = (i - bars / 2f) / (bars / 2f) // -1..1
            val envelope = exp(-u * u * 2.2f)
            val amp = when (state) {
                AssistantState.Speaking -> (0.15f + 0.85f * level) * envelope * (0.55f + 0.45f * abs(sin(t * 9f + i * 0.9f)))
                AssistantState.Listening -> {
                    val scan = ((t * 0.7f) % 1f) * 2.4f - 1.2f
                    0.12f + 0.55f * exp(-(u - scan) * (u - scan) * 18f)
                }
                AssistantState.Thinking, AssistantState.Executing -> 0.12f + 0.3f * envelope * ((sin(t * 5f - i * 0.45f) + 1f) / 2f)
                AssistantState.Sleeping -> 0.06f + 0.05f * ((sin(t * 0.8f) + 1f) / 2f)
                else -> 0.06f
            }
            val h = (size.height * amp).coerceAtLeast(2f)
            val alpha = 0.35f + 0.65f * envelope
            drawLine(accent.copy(alpha = alpha), Offset(x, mid - h / 2f), Offset(x, mid + h / 2f), barW, StrokeCap.Round)
        }
        drawLine(accent.copy(alpha = 0.18f), Offset(0f, mid), Offset(size.width, mid), 1f)
    }
}
