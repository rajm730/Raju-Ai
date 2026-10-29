package com.raju.aiassistant.ui.home.visuals

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.raju.aiassistant.domain.model.AssistantState
import kotlin.math.min
import kotlin.math.sin

/**
 * 2D animated hologram of an original, stylised female assistant (face + upper body).
 * This is the fallback renderer; a 3D renderer can replace it once a licensed model file
 * is supplied. Supports blinking, subtle head motion, state-based gaze and speech-driven
 * mouth movement ([speechLevel], wired to real TTS audio in Phase 4).
 */
@Composable
fun HologramAvatar(
    state: AssistantState,
    style: ReactorStyle,
    scanlines: Boolean,
    time: () -> Float,
    speechLevel: () -> Float,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(Color(style.primaryArgb), tween(600), label = "holoTint")
    val accent by animateColorAsState(Color(style.secondaryArgb), tween(600), label = "holoAccent")

    Canvas(modifier) {
        val t = time()
        val u = min(size.height, size.width * 1.3f) // drawing unit
        val cx = size.width / 2f
        val top = (size.height - u) / 2f

        val flicker = when {
            style.flicker -> 0.6f + 0.4f * pseudoRandom((t * 10f).toInt())
            state == AssistantState.Offline -> 0.45f
            else -> 0.92f + 0.08f * pseudoRandom((t * 8f).toInt())
        }
        val line = tint.copy(alpha = 0.85f * flicker)
        val fill = Brush.verticalGradient(
            listOf(tint.copy(alpha = 0.28f * flicker), accent.copy(alpha = 0.06f * flicker)),
            startY = top, endY = top + u,
        )
        val stroke = Stroke(width = u * 0.006f, cap = StrokeCap.Round)

        // Head motion: gentle sway, bob and tilt; listening tilts a little more.
        val tiltAmp = if (state == AssistantState.Listening) 4f else 2f
        val tilt = tiltAmp * sin(t * 0.6f)
        val sway = u * 0.008f * sin(t * 0.35f)
        val bob = u * 0.005f * sin(t * 0.9f)
        // Pivot is in the translated space, i.e. the untranslated head centre.
        val headCenter = Offset(cx, top + u * 0.30f)

        // Projection base light
        drawOval(
            brush = Brush.radialGradient(listOf(tint.copy(alpha = 0.25f * flicker), Color.Transparent), center = Offset(cx, top + u * 0.98f), radius = u * 0.4f),
            topLeft = Offset(cx - u * 0.4f, top + u * 0.92f),
            size = Size(u * 0.8f, u * 0.12f),
        )

        // Back hair (moves with head)
        withTransform({ translate(sway, bob); rotate(tilt, headCenter) }) {
            val hair = backHairPath(cx, top, u)
            drawPath(hair, fill)
            drawPath(hair, line.copy(alpha = line.alpha * 0.6f), style = stroke)
        }

        // Torso and neck (static)
        val torso = torsoPath(cx, top, u)
        drawPath(torso, fill)
        drawPath(torso, line, style = stroke)
        drawCollarDetail(cx, top, u, accent.copy(alpha = 0.7f * flicker), stroke)

        // Head group
        withTransform({ translate(sway, bob); rotate(tilt, headCenter) }) {
            val hc = Offset(cx, top + u * 0.30f)
            drawOval(fill, topLeft = Offset(hc.x - u * 0.11f, hc.y - u * 0.145f), size = Size(u * 0.22f, u * 0.29f))
            drawOval(line, topLeft = Offset(hc.x - u * 0.11f, hc.y - u * 0.145f), size = Size(u * 0.22f, u * 0.29f), style = stroke)
            drawFace(state, hc, u, t, line, accent.copy(alpha = flicker), speechLevel())
            val fringe = fringePath(hc, u)
            drawPath(fringe, fill)
            drawPath(fringe, line, style = stroke)
        }

        if (scanlines) drawScanlines(t, tint.copy(alpha = 0.07f * flicker))
    }
}

private fun backHairPath(cx: Float, top: Float, u: Float) = Path().apply {
    moveTo(cx - u * 0.17f, top + u * 0.62f)
    cubicTo(cx - u * 0.22f, top + u * 0.35f, cx - u * 0.2f, top + u * 0.1f, cx, top + u * 0.11f)
    cubicTo(cx + u * 0.2f, top + u * 0.1f, cx + u * 0.22f, top + u * 0.35f, cx + u * 0.17f, top + u * 0.62f)
    quadraticTo(cx, top + u * 0.56f, cx - u * 0.17f, top + u * 0.62f)
    close()
}

private fun fringePath(hc: Offset, u: Float) = Path().apply {
    moveTo(hc.x - u * 0.115f, hc.y - u * 0.02f)
    cubicTo(hc.x - u * 0.12f, hc.y - u * 0.16f, hc.x + u * 0.05f, hc.y - u * 0.2f, hc.x + u * 0.115f, hc.y - u * 0.06f)
    quadraticTo(hc.x + u * 0.03f, hc.y - u * 0.12f, hc.x - u * 0.04f, hc.y - u * 0.08f)
    quadraticTo(hc.x - u * 0.09f, hc.y - u * 0.06f, hc.x - u * 0.115f, hc.y - u * 0.02f)
    close()
}

private fun torsoPath(cx: Float, top: Float, u: Float) = Path().apply {
    // Neck
    moveTo(cx - u * 0.04f, top + u * 0.42f)
    lineTo(cx - u * 0.045f, top + u * 0.53f)
    // Left shoulder and arm line
    cubicTo(cx - u * 0.12f, top + u * 0.56f, cx - u * 0.3f, top + u * 0.57f, cx - u * 0.34f, top + u * 0.66f)
    lineTo(cx - u * 0.38f, top + u * 0.95f)
    lineTo(cx + u * 0.38f, top + u * 0.95f)
    lineTo(cx + u * 0.34f, top + u * 0.66f)
    cubicTo(cx + u * 0.3f, top + u * 0.57f, cx + u * 0.12f, top + u * 0.56f, cx + u * 0.045f, top + u * 0.53f)
    lineTo(cx + u * 0.04f, top + u * 0.42f)
    close()
}

private fun DrawScope.drawCollarDetail(cx: Float, top: Float, u: Float, color: Color, stroke: Stroke) {
    val collar = Path().apply {
        moveTo(cx - u * 0.13f, top + u * 0.57f)
        quadraticTo(cx, top + u * 0.68f, cx + u * 0.13f, top + u * 0.57f)
    }
    drawPath(collar, color, style = stroke)
    // Circuit-like suit lines
    drawLine(color.copy(alpha = color.alpha * 0.6f), Offset(cx - u * 0.25f, top + u * 0.72f), Offset(cx - u * 0.12f, top + u * 0.85f), strokeWidth = stroke.width * 0.7f)
    drawLine(color.copy(alpha = color.alpha * 0.6f), Offset(cx + u * 0.25f, top + u * 0.72f), Offset(cx + u * 0.12f, top + u * 0.85f), strokeWidth = stroke.width * 0.7f)
}

private fun DrawScope.drawFace(
    state: AssistantState,
    hc: Offset,
    u: Float,
    t: Float,
    line: Color,
    accent: Color,
    speechLevel: Float,
) {
    val eyeY = hc.y - u * 0.005f
    val eyeDx = u * 0.045f
    val eyeW = u * 0.042f
    val openness = when (state) {
        AssistantState.Offline -> 0.35f
        AssistantState.Success -> 0.75f // soft, smiling eyes
        else -> blinkOpenness(t)
    }
    val eyeH = u * 0.022f * openness.coerceAtLeast(0.08f)
    // Gaze: thinking looks up-right, otherwise slow idle drift.
    val gaze = when (state) {
        AssistantState.Thinking -> Offset(u * 0.008f, -u * 0.005f)
        else -> Offset(u * 0.004f * sin(t * 0.5f), 0f)
    }
    for (side in listOf(-1f, 1f)) {
        val ec = Offset(hc.x + side * eyeDx, eyeY)
        drawOval(line, topLeft = Offset(ec.x - eyeW / 2, ec.y - eyeH / 2), size = Size(eyeW, eyeH), style = Stroke(u * 0.004f))
        if (openness > 0.3f) drawCircle(accent, radius = u * 0.008f, center = ec + gaze)
        // Brow
        val browLift = if (state == AssistantState.Error) u * 0.006f else 0f
        drawLine(
            line,
            Offset(ec.x - side * eyeW * 0.6f, ec.y - u * 0.028f + browLift),
            Offset(ec.x + side * eyeW * 0.5f, ec.y - u * 0.034f - browLift),
            strokeWidth = u * 0.004f, cap = StrokeCap.Round,
        )
    }
    // Nose
    drawLine(line.copy(alpha = line.alpha * 0.6f), Offset(hc.x, hc.y + u * 0.02f), Offset(hc.x - u * 0.006f, hc.y + u * 0.05f), strokeWidth = u * 0.003f, cap = StrokeCap.Round)

    // Mouth
    val mouthY = hc.y + u * 0.085f
    val mouthW = u * 0.05f
    val open = if (state == AssistantState.Speaking) mouthOpenness(speechLevel) else 0f
    val smile = when (state) {
        AssistantState.Success -> u * 0.012f
        AssistantState.Error, AssistantState.Offline -> -u * 0.004f
        else -> u * 0.004f
    }
    val upper = Path().apply {
        moveTo(hc.x - mouthW / 2, mouthY)
        quadraticTo(hc.x, mouthY - u * 0.006f, hc.x + mouthW / 2, mouthY)
    }
    val lower = Path().apply {
        moveTo(hc.x - mouthW / 2, mouthY)
        quadraticTo(hc.x, mouthY + smile + open * u * 0.03f, hc.x + mouthW / 2, mouthY)
    }
    drawPath(upper, accent, style = Stroke(u * 0.004f, cap = StrokeCap.Round))
    drawPath(lower, accent, style = Stroke(u * 0.004f, cap = StrokeCap.Round))
    if (open > 0.05f) {
        drawOval(
            accent.copy(alpha = 0.35f),
            topLeft = Offset(hc.x - mouthW * 0.3f, mouthY),
            size = Size(mouthW * 0.6f, open * u * 0.022f),
        )
    }
}

private fun DrawScope.drawScanlines(t: Float, color: Color) {
    val gap = size.height / 90f
    val offset = (t * gap * 2f) % gap
    var y = offset
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += gap
    }
}
