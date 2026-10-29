package com.raju.aiassistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/** Glass HUD panel used across screens: cut corners, corner brackets and a light tracing its border. */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val trace = rememberBorderTrace()
    Column(
        modifier
            .fillMaxWidth()
            .hudFrame(accent, trace = trace)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) HudTitle(title, accent)
        content()
    }
}

/** Panel heading: a small diamond marker, the title, and a hairline running to the edge. */
@Composable
fun HudTitle(title: String, accent: Color = MaterialTheme.colorScheme.primary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) {
            val p = Path().apply {
                moveTo(size.width / 2, 0f); lineTo(size.width, size.height / 2); lineTo(size.width / 2, size.height); lineTo(0f, size.height / 2); close()
            }
            drawPath(p, accent)
        }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
        Spacer(Modifier.width(10.dp))
        Canvas(
            Modifier
                .weight(1f)
                .height(1.dp),
        ) { drawLine(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)), Offset.Zero, Offset(size.width, 0f), size.height) }
    }
}

/**
 * Status dot + label in a cut-corner tag, e.g. "AI: connected". [pulse] (0..1, read while drawing)
 * makes the dot breathe; pass the screen's own animation clock so no extra frames are drawn.
 */
@Composable
fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier, pulse: (() -> Float)? = null) {
    Surface(
        modifier = modifier,
        shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(8.dp)) {
                val p = pulse?.invoke() ?: 1f
                drawCircle(color.copy(alpha = 0.25f * p), radius = size.minDimension / 2f)
                drawCircle(color.copy(alpha = 0.5f + 0.5f * p), radius = size.minDimension / 3.2f)
            }
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}
