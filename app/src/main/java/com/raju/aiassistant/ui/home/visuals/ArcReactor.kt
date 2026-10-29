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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private class Particle(val angle: Float, val radius: Float, val speed: Float, val size: Float, val phase: Float)

private fun makeParticles(count: Int): List<Particle> = List(count) { i ->
    Particle(
        angle = pseudoRandom(i * 4 + 1) * 360f,
        radius = 0.82f + pseudoRandom(i * 4 + 2) * 0.16f,
        speed = (8f + pseudoRandom(i * 4 + 3) * 22f) * if (i % 2 == 0) 1f else -1f,
        size = 0.006f + pseudoRandom(i * 4 + 4) * 0.01f,
        phase = pseudoRandom(i * 7 + 5) * 6.28f,
    )
}

/**
 * Original animated Arc Reactor drawn on a Canvas.
 *
 * @param time seconds from [rememberAnimationTime]; read during draw only.
 * @param speechLevel 0..1 audio level used when the style is speech-reactive (wired to TTS in Phase 4).
 */
@Composable
fun ArcReactor(
    style: ReactorStyle,
    particleCount: Int,
    time: () -> Float,
    speechLevel: () -> Float,
    modifier: Modifier = Modifier,
) {
    val primary by animateColorAsState(Color(style.primaryArgb), tween(500), label = "reactorPrimary")
    val secondary by animateColorAsState(Color(style.secondaryArgb), tween(500), label = "reactorSecondary")
    val glow by animateFloatAsState(style.glow, tween(500), label = "reactorGlow")
    val particles = remember(particleCount) { makeParticles(particleCount) }

    Canvas(modifier) {
        val t = time()
        val r = size.minDimension / 2f
        val c = center

        val pulse = when {
            style.speechReactive -> speechLevel().coerceIn(0f, 1f)
            style.pulseHz > 0f -> (sin(t * style.pulseHz * 2f * PI.toFloat()) + 1f) / 2f
            else -> 0.5f
        }
        val flicker = if (style.flicker) 0.65f + 0.35f * pseudoRandom((t * 12f).toInt()) else 1f
        val energy = (0.6f + 0.4f * pulse) * flicker

        // Outer glow
        drawCircle(
            brush = Brush.radialGradient(
                listOf(primary.copy(alpha = 0.45f * glow * energy), primary.copy(alpha = 0.08f * glow), Color.Transparent),
                center = c, radius = r,
            ),
            radius = r,
        )

        if (style.ripples) drawRipples(t, c, r, primary)

        // Orbiting particles
        particles.forEach { p ->
            val a = Math.toRadians((p.angle + p.speed * t).toDouble())
            val pr = r * p.radius
            val twinkle = 0.4f + 0.6f * ((sin(t * 2f + p.phase) + 1f) / 2f)
            drawCircle(
                color = secondary.copy(alpha = 0.7f * twinkle * flicker),
                radius = r * p.size,
                center = Offset(c.x + pr * cos(a).toFloat(), c.y + pr * sin(a).toFloat()),
            )
        }

        // Outer segmented ring (rotating)
        val outerAngle = t * style.rotationSpeed * 360f
        rotate(outerAngle, c) {
            drawSegmentedRing(c, r * 0.74f, r * 0.05f, segments = 12, gapDegrees = 8f, color = primary.copy(alpha = 0.9f * flicker))
        }
        // Thin counter-rotating tick ring
        rotate(-outerAngle * 1.6f, c) {
            drawTicks(c, r * 0.64f, r * 0.035f, count = 60, color = secondary.copy(alpha = 0.6f * flicker))
        }

        if (style.spinner) {
            rotate(t * 300f, c) {
                drawArcAround(c, r * 0.84f, 0f, 70f, primary, r * 0.02f)
                drawArcAround(c, r * 0.84f, 180f, 70f, secondary, r * 0.02f)
            }
        }
        if (style.sweep) {
            val progress = (t * 0.35f) % 1f
            drawArcAround(c, r * 0.9f, -90f, 360f * progress, primary, r * 0.025f)
        }

        // Coil housing: ten trapezoid-like blocks around the core
        rotate(outerAngle * 0.3f, c) {
            drawSegmentedRing(c, r * 0.47f, r * 0.12f, segments = 10, gapDegrees = 10f, color = primary.copy(alpha = 0.35f + 0.35f * energy))
        }
        drawCircle(secondary.copy(alpha = 0.8f * flicker), radius = r * 0.36f, center = c, style = Stroke(width = r * 0.015f))

        // Core
        val coreR = r * (0.24f + 0.04f * pulse)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.95f * energy), primary.copy(alpha = 0.85f * energy), primary.copy(alpha = 0f)),
                center = c, radius = coreR * 1.5f,
            ),
            radius = coreR * 1.5f,
            center = c,
        )
        drawCircle(Color.White.copy(alpha = 0.9f * energy), radius = coreR * 0.45f, center = c)
    }
}

private fun DrawScope.drawRipples(t: Float, c: Offset, r: Float, color: Color) {
    repeat(3) { i ->
        val progress = ((t * 0.6f) + i / 3f) % 1f
        drawCircle(
            color = color.copy(alpha = (1f - progress) * 0.5f),
            radius = r * (0.4f + 0.6f * progress),
            center = c,
            style = Stroke(width = r * 0.012f),
        )
    }
}

private fun DrawScope.drawSegmentedRing(c: Offset, radius: Float, thickness: Float, segments: Int, gapDegrees: Float, color: Color) {
    val sweep = 360f / segments - gapDegrees
    repeat(segments) { i ->
        drawArcAround(c, radius, i * 360f / segments, sweep, color, thickness, StrokeCap.Butt)
    }
}

private fun DrawScope.drawTicks(c: Offset, radius: Float, length: Float, count: Int, color: Color) {
    repeat(count) { i ->
        val a = 2.0 * PI * i / count
        val scale = if (i % 5 == 0) 1.6f else 1f
        val inner = radius - length * scale
        drawLine(
            color = color,
            start = Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
            end = Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat()),
            strokeWidth = length * 0.25f,
        )
    }
}

private fun DrawScope.drawArcAround(
    c: Offset,
    radius: Float,
    startAngle: Float,
    sweep: Float,
    color: Color,
    width: Float,
    cap: StrokeCap = StrokeCap.Round,
) {
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(c.x - radius, c.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = width, cap = cap),
    )
}
