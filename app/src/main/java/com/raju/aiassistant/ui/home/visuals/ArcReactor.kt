package com.raju.aiassistant.ui.home.visuals

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private class Particle(val angle: Float, val radius: Float, val speed: Float, val size: Float, val phase: Float, val colorIndex: Int)

private fun makeParticles(count: Int): List<Particle> = List(count) { i ->
    Particle(
        angle = pseudoRandom(i * 4 + 1) * 360f,
        radius = 0.84f + pseudoRandom(i * 4 + 2) * 0.16f,
        speed = (8f + pseudoRandom(i * 4 + 3) * 22f) * if (i % 2 == 0) 1f else -1f,
        size = 0.005f + pseudoRandom(i * 4 + 4) * 0.01f,
        phase = pseudoRandom(i * 7 + 5) * 6.28f,
        colorIndex = i % PALETTE_SIZE,
    )
}

/**
 * Original animated, multi-colour Arc Reactor that beats like a heart.
 *
 * @param time seconds from [rememberAnimationTime]; read during draw only.
 * @param speechLevel 0..1 audio level used when the style is speech-reactive (wired to TTS in Phase 4).
 */
@Composable
fun ArcReactor(
    style: ReactorStyle,
    particleCount: Int,
    richEffects: Boolean,
    time: () -> Float,
    speechLevel: () -> Float,
    modifier: Modifier = Modifier,
) {
    val palette = List(PALETTE_SIZE) { i ->
        animateColorAsState(Color(style.paletteArgb[i]), tween(600), label = "reactorColor$i").value
    }
    val glow by animateFloatAsState(style.glow, tween(600), label = "reactorGlow")
    val particles = remember(particleCount) { makeParticles(particleCount) }

    Canvas(modifier) {
        val t = time()
        val r = size.minDimension / 2f
        val c = center
        val primary = palette[0]
        val accent = palette[2]
        val ringBrush = Brush.sweepGradient(palette, center = c)

        val beat = if (style.speechReactive) speechLevel().coerceIn(0f, 1f) else heartbeat(t, style.bpm)
        val flicker = if (style.flicker) 0.65f + 0.35f * pseudoRandom((t * 12f).toInt()) else 1f
        val energy = (0.55f + 0.45f * beat) * flicker
        val spin = t * style.rotationSpeed * 360f

        // Ambient glow breathes with each beat
        drawCircle(
            brush = Brush.radialGradient(
                listOf(primary.copy(alpha = (0.3f + 0.35f * beat) * glow * flicker), accent.copy(alpha = 0.1f * glow), Color.Transparent),
                center = c, radius = r,
            ),
            radius = r,
        )

        // Heartbeat shock-waves: one ring per beat, expanding and fading
        if (richEffects && !style.speechReactive && style.bpm > 0f) {
            val phase = beatPhase(t, style.bpm)
            drawCircle(ringBrush, radius = r * (0.45f + 0.55f * phase), center = c, alpha = (1f - phase) * 0.45f * glow, style = Stroke(r * 0.012f))
        }
        if (style.ripples) drawRipples(t, c, r, ringBrush)

        // Orbiting multi-colour particles
        particles.forEach { p ->
            val a = Math.toRadians((p.angle + p.speed * t).toDouble())
            val pr = r * p.radius
            val twinkle = 0.4f + 0.6f * ((sin(t * 2f + p.phase) + 1f) / 2f)
            drawCircle(
                color = palette[p.colorIndex].copy(alpha = 0.75f * twinkle * flicker),
                radius = r * p.size,
                center = Offset(c.x + pr * cos(a).toFloat(), c.y + pr * sin(a).toFloat()),
            )
        }

        if (richEffects) {
            // Radar sweep: a soft wedge of light circling the reactor.
            rotate(t * 70f, c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent, 0.82f to Color.Transparent, 0.97f to primary.copy(alpha = 0.22f * glow), 1f to Color.Transparent,
                        center = c,
                    ),
                    radius = r * 0.98f,
                    center = c,
                )
            }
            // Outer HUD ring: thin dashes plus four brackets turning the other way.
            rotate(-t * 9f, c) {
                drawSegmentedRing(c, r * 0.995f, r * 0.006f, segments = 90, gapDegrees = 2.2f, brush = ringBrush, alpha = 0.45f * flicker)
            }
            rotate(t * 14f, c) {
                repeat(4) { i -> drawArcAround(c, r * 0.965f, i * 90f + 20f, 28f, accent.copy(alpha = 0.75f * flicker), r * 0.014f) }
            }
            drawComets(t, c, r, palette, flicker)
        }

        // Everything below beats: the whole reactor swells slightly on each pulse
        scale(1f + 0.06f * beat, pivot = c) {
            if (richEffects) {
                rotate(-spin * 0.7f, c) {
                    drawSegmentedRing(c, r * 0.9f, r * 0.012f, segments = 36, gapDegrees = 4f, brush = ringBrush, alpha = 0.7f * flicker)
                }
            }
            rotate(spin, c) {
                drawSegmentedRing(c, r * 0.78f, r * 0.065f, segments = 12, gapDegrees = 7f, brush = ringBrush, alpha = 0.95f * flicker)
            }
            rotate(-spin * 1.6f, c) {
                drawTicks(c, r * 0.68f, r * 0.035f, count = 72, color = accent.copy(alpha = 0.65f * flicker))
            }

            if (style.spinner) {
                rotate(t * 300f, c) {
                    drawArcAround(c, r * 0.86f, 0f, 70f, palette[1], r * 0.022f)
                    drawArcAround(c, r * 0.86f, 180f, 70f, palette[3], r * 0.022f)
                }
            }
            if (style.sweep) {
                drawArcAround(c, r * 0.94f, -90f, 360f * ((t * 0.35f) % 1f), primary, r * 0.025f)
            }

            // Coil housing
            rotate(spin * 0.35f, c) {
                drawSegmentedRing(c, r * 0.52f, r * 0.15f, segments = 10, gapDegrees = 9f, brush = ringBrush, alpha = 0.35f + 0.5f * energy)
            }
            drawCircle(ringBrush, radius = r * 0.4f, center = c, alpha = 0.85f * flicker, style = Stroke(width = r * 0.018f))

            if (richEffects) drawEnergyArcs(t, c, r, palette, energy, beat)

            // Palladium triangle turning inside the core ring, like the classic reactor.
            rotate(-spin * 0.5f, c) { drawTriangle(c, r * 0.36f, ringBrush, r * 0.022f, 0.75f * flicker) }
            rotate(spin * 0.8f + 60f, c) { drawTriangle(c, r * 0.30f, ringBrush, r * 0.010f, 0.45f * flicker) }

            // Heart core
            val coreR = r * (0.25f + 0.07f * beat)
            if (richEffects) {
                // Light rays bursting from the core on each beat.
                repeat(16) { i ->
                    val a = Math.toRadians((i * 22.5f + spin * 0.2f).toDouble())
                    val len = r * (0.30f + 0.12f * beat * pseudoRandom(i + 3))
                    drawLine(
                        color = primary.copy(alpha = (0.10f + 0.25f * beat) * flicker),
                        start = Offset(c.x + coreR * 0.6f * cos(a).toFloat(), c.y + coreR * 0.6f * sin(a).toFloat()),
                        end = Offset(c.x + len * cos(a).toFloat(), c.y + len * sin(a).toFloat()),
                        strokeWidth = r * 0.006f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.95f * energy), primary.copy(alpha = 0.9f * energy), accent.copy(alpha = 0.4f * energy), Color.Transparent),
                    center = c, radius = coreR * 1.6f,
                ),
                radius = coreR * 1.6f,
                center = c,
            )
            drawCircle(Color.White.copy(alpha = 0.9f * energy), radius = coreR * 0.42f, center = c)
        }
    }
}

private fun DrawScope.drawRipples(t: Float, c: Offset, r: Float, brush: Brush) {
    repeat(3) { i ->
        val progress = ((t * 0.6f) + i / 3f) % 1f
        drawCircle(brush, radius = r * (0.4f + 0.6f * progress), center = c, alpha = (1f - progress) * 0.5f, style = Stroke(width = r * 0.012f))
    }
}

private fun DrawScope.drawSegmentedRing(c: Offset, radius: Float, thickness: Float, segments: Int, gapDegrees: Float, brush: Brush, alpha: Float) {
    val sweep = 360f / segments - gapDegrees
    repeat(segments) { i ->
        drawArc(
            brush = brush,
            startAngle = i * 360f / segments,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(c.x - radius, c.y - radius),
            size = Size(radius * 2, radius * 2),
            alpha = alpha.coerceIn(0f, 1f),
            style = Stroke(width = thickness, cap = StrokeCap.Butt),
        )
    }
}

private fun DrawScope.drawTicks(c: Offset, radius: Float, length: Float, count: Int, color: Color) {
    repeat(count) { i ->
        val a = 2.0 * PI * i / count
        val scale = if (i % 6 == 0) 1.7f else 1f
        val inner = radius - length * scale
        drawLine(
            color = color,
            start = Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
            end = Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat()),
            strokeWidth = length * 0.25f,
        )
    }
}

private fun DrawScope.drawArcAround(c: Offset, radius: Float, startAngle: Float, sweep: Float, color: Color, width: Float) {
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(c.x - radius, c.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

/** Three bright comets with fading tails orbiting just inside the rim. */
private fun DrawScope.drawComets(t: Float, c: Offset, r: Float, palette: List<Color>, flicker: Float) {
    repeat(3) { i ->
        val dir = if (i == 1) -1f else 1f
        val head = i * 120f + dir * t * (38f + i * 9f)
        val radius = r * (0.93f - i * 0.025f)
        repeat(10) { k ->
            val a = Math.toRadians((head - dir * k * 2.6f).toDouble())
            val fade = 1f - k / 10f
            drawCircle(
                color = (if (k == 0) Color.White else palette[(i + 1) % palette.size]).copy(alpha = 0.85f * fade * flicker),
                radius = r * 0.012f * (0.35f + 0.65f * fade),
                center = Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat()),
            )
        }
    }
}

/** Crackling energy arcs between the core ring and the coil, re-drawn a few times a second. */
private fun DrawScope.drawEnergyArcs(t: Float, c: Offset, r: Float, palette: List<Color>, energy: Float, beat: Float) {
    val frame = (t * 12f).toInt()
    val bolts = 4 + (beat * 3f).toInt()
    repeat(bolts) { b ->
        val seed = frame * 97 + b * 13
        if (pseudoRandom(seed) < 0.25f) return@repeat // not every bolt fires every frame
        val base = pseudoRandom(seed + 1) * 360f
        val path = Path()
        val steps = 6
        for (k in 0..steps) {
            val f = k / steps.toFloat()
            val rr = r * (0.42f + 0.2f * f)
            val jitter = (pseudoRandom(seed + 10 + k) - 0.5f) * 14f
            val a = Math.toRadians((base + jitter).toDouble())
            val x = c.x + rr * cos(a).toFloat()
            val y = c.y + rr * sin(a).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val color = palette[b % palette.size]
        drawPath(path, color.copy(alpha = 0.25f * energy), style = Stroke(width = r * 0.018f, cap = StrokeCap.Round))
        drawPath(path, Color.White.copy(alpha = 0.7f * energy), style = Stroke(width = r * 0.005f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawTriangle(c: Offset, radius: Float, brush: Brush, width: Float, alpha: Float) {
    val path = Path()
    repeat(3) { i ->
        val a = Math.toRadians((i * 120.0) - 90.0)
        val x = c.x + radius * cos(a).toFloat()
        val y = c.y + radius * sin(a).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, brush, alpha = alpha.coerceIn(0f, 1f), style = Stroke(width = width, cap = StrokeCap.Round))
}
