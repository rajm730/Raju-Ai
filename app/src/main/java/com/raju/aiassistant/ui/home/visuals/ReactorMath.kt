package com.raju.aiassistant.ui.home.visuals

import kotlin.math.exp

/** Deterministic 0..1 value for an integer seed (no allocation, stable across frames). */
fun pseudoRandom(seed: Int): Float {
    var x = seed * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0x7FFFFFFF) / 2147483647f
}

/** Position 0..1 inside the current beat. */
fun beatPhase(timeSec: Float, bpm: Float): Float {
    if (bpm <= 0f) return 0f
    val period = 60f / bpm
    val p = (timeSec % period) / period
    return if (p < 0f) p + 1f else p
}

/**
 * Heart-like "lub-dub" pulse, 0..1: a strong beat followed by a softer second beat,
 * then rest until the next cycle.
 */
fun heartbeat(timeSec: Float, bpm: Float): Float {
    val x = beatPhase(timeSec, bpm)
    val lub = gaussian(x, centre = 0.06f, width = 0.045f)
    val dub = 0.6f * gaussian(x, centre = 0.26f, width = 0.05f)
    return maxOf(lub, dub).coerceIn(0f, 1f)
}

private fun gaussian(x: Float, centre: Float, width: Float): Float {
    val d = (x - centre) / width
    return exp(-d * d)
}
