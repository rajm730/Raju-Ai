package com.raju.aiassistant.ui.home.visuals

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** Deterministic 0..1 value for an integer seed (no allocation, stable across frames). */
fun pseudoRandom(seed: Int): Float {
    var x = seed * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0x7FFFFFFF) / 2147483647f
}

/**
 * Eye openness (1 = open, 0 = closed) at [timeSec]. One blink per [intervalSec] window,
 * at a pseudo-random offset inside the window so it feels natural.
 */
fun blinkOpenness(timeSec: Float, intervalSec: Float = 4f, blinkDurationSec: Float = 0.18f): Float {
    if (timeSec < 0f) return 1f
    val index = floor(timeSec / intervalSec)
    val jitter = pseudoRandom(index.toInt()) * (intervalSec - blinkDurationSec) * 0.6f
    val local = timeSec - index * intervalSec - jitter
    if (local < 0f || local > blinkDurationSec) return 1f
    val half = blinkDurationSec / 2f
    return (abs(local - half) / half).coerceIn(0f, 1f)
}

/** Maps a speech amplitude (0..1) to mouth openness; small sounds still visibly move the lips. */
fun mouthOpenness(speechLevel: Float): Float = speechLevel.coerceIn(0f, 1f).pow(0.7f)
