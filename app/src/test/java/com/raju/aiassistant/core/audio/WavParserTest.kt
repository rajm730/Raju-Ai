package com.raju.aiassistant.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavParserTest {
    private fun wav(sampleRate: Int, channels: Int, bits: Int, samples: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        return ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort()); putInt(sampleRate)
            putInt(sampleRate * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(data.size); put(data)
        }.array()
    }

    @Test
    fun parsesMono16BitPcm() {
        val audio = parseWav(wav(22050, 1, 16, ShortArray(100) { 1000 }))
        assertNotNull(audio)
        assertEquals(22050, audio!!.sampleRate)
        assertEquals(100, audio.frameCount)
    }

    @Test
    fun rejectsNonWavAnd8Bit() {
        assertNull(parseWav(ByteArray(100)))
        assertNull(parseWav(wav(8000, 1, 8, ShortArray(10))))
    }

    @Test
    fun levelReflectsLoudness() {
        val quiet = wav(16000, 1, 16, ShortArray(400) { 0 })
        val loud = wav(16000, 1, 16, ShortArray(400) { if (it % 2 == 0) 8000 else -8000 })
        val q = parseWav(quiet)!!; val l = parseWav(loud)!!
        assertEquals(0f, pcmLevel(q.data, 0, q.data.size))
        assertTrue(pcmLevel(l.data, 0, l.data.size) > 0.5f)
    }
}
