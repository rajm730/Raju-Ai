package com.raju.aiassistant.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 16-bit PCM audio extracted from a WAV file. */
class PcmAudio(val sampleRate: Int, val channels: Int, val data: ByteArray) {
    val frameCount: Int get() = data.size / (2 * channels)
}

/** Parses a RIFF/WAVE file containing 16-bit PCM. Returns null for anything else. */
fun parseWav(bytes: ByteArray): PcmAudio? {
    if (bytes.size < 44) return null
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
    var pos = 12
    var sampleRate = 0
    var channels = 0
    var bits = 0
    var format = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        var size = buf.getInt(pos + 4)
        val body = pos + 8
        if (size < 0 || body + size > bytes.size) size = bytes.size - body // tolerate streamed WAVs with bad sizes
        when (id) {
            "fmt " -> {
                format = buf.getShort(body).toInt() and 0xFFFF
                channels = buf.getShort(body + 2).toInt()
                sampleRate = buf.getInt(body + 4)
                bits = buf.getShort(body + 14).toInt()
            }
            "data" -> {
                val pcmFormat = format == 1 || format == 0xFFFE
                if (!pcmFormat || bits != 16 || channels !in 1..2 || sampleRate <= 0) return null
                return PcmAudio(sampleRate, channels, bytes.copyOfRange(body, body + size))
            }
        }
        pos = body + size + (size and 1)
    }
    return null
}

/** RMS loudness of 16-bit little-endian PCM in [offset, offset+length), normalised to roughly 0..1. */
fun pcmLevel(data: ByteArray, offset: Int, length: Int): Float {
    var sum = 0.0
    var count = 0
    var i = offset
    val end = (offset + length).coerceAtMost(data.size - 1)
    while (i < end) {
        val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toDouble()
        sum += sample * sample
        count++
        i += 2
    }
    if (count == 0) return 0f
    val rms = sqrt(sum / count) / Short.MAX_VALUE
    return (rms * 3.5).toFloat().coerceIn(0f, 1f) // speech RMS is usually < 0.3, so scale up
}
