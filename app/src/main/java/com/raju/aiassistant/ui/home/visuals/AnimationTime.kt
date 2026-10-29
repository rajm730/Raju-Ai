package com.raju.aiassistant.ui.home.visuals

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive

/**
 * Seconds elapsed while [running], advanced on the frame clock and capped at [maxFps].
 * Read the value inside draw lambdas only, so frames redraw without recomposing.
 * When not running, time freezes (reduced motion / app in background).
 */
@Composable
fun rememberAnimationTime(running: Boolean, maxFps: Int): MutableFloatState {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, maxFps) {
        if (!running || maxFps <= 0) return@LaunchedEffect
        val minFrameNanos = 1_000_000_000L / maxFps
        val resumeFrom = time.floatValue
        var startNanos = -1L
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (startNanos < 0) startNanos = now
                // Small tolerance so a 60 Hz display is not throttled to 30 fps by jitter.
                if (now - lastNanos >= minFrameNanos - 2_000_000L) {
                    lastNanos = now
                    time.floatValue = resumeFrom + (now - startNanos) / 1_000_000_000f
                }
            }
        }
    }
    return time
}
