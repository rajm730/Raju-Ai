package com.raju.aiassistant.domain.voice

import com.raju.aiassistant.core.audio.PcmAudio
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import com.raju.aiassistant.domain.assistant.chunkForSpeech
import com.raju.aiassistant.domain.assistant.speakableText
import com.raju.aiassistant.domain.model.TtsEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechPipelineTest {
    private fun pcm(tag: String) = PcmAudio(16000, 1, tag.toByteArray())

    private class FakeProvider(
        override val engine: TtsEngine,
        val available: Boolean = true,
        val failAfter: Int = Int.MAX_VALUE,
    ) : TtsProvider {
        override val label = engine.name
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun isAvailable() = available
        override suspend fun synthesize(text: String, languageTag: String, rate: Float): AiResult<PcmAudio> {
            calls += text to languageTag
            return if (calls.size > failAfter) AiResult.Failure(FailureKind.NETWORK, "down")
            else AiResult.Success(PcmAudio(16000, 1, "$label:$text".toByteArray()))
        }
    }

    private class FakeSink(val gate: CompletableDeferred<Unit>? = null) : PcmSink {
        val played = mutableListOf<String>()
        var focus = 0
        override fun requestFocus() { focus++ }
        override fun abandonFocus() { focus-- }
        override suspend fun play(audio: PcmAudio, onLevel: (Float) -> Unit) {
            gate?.await()
            onLevel(0.5f)
            played += String(audio.data)
        }
    }

    private val text = "Pehla vaakya. Doosra vaakya. Teesra vaakya."
    private val longText = (1..12).joinToString(" ") { "Yeh vaakya number $it hai, thoda lamba likha gaya hai taki alag tukde banein." }

    @Test
    fun selectedEngineIsTriedFirstThenTheRest() {
        assertEquals(listOf(TtsEngine.ANDROID, TtsEngine.SARVAM), providerOrder(TtsEngine.ANDROID))
        assertEquals(TtsEngine.SARVAM, providerOrder(TtsEngine.SARVAM).first())
        assertEquals(TtsEngine.entries.size, providerOrder(TtsEngine.SARVAM).toSet().size)
    }

    @Test
    fun playsAllChunksInOrderAndReleasesFocus() = runTest {
        val sink = FakeSink()
        val r = SpeechPipeline(sink).speak(text, listOf(FakeProvider(TtsEngine.SARVAM)), "hi-IN", 1f)
        assertTrue(r.completed)
        assertEquals(chunkForSpeech(text).map { "SARVAM:$it" }, sink.played)
        assertEquals(0, sink.focus)
    }

    @Test
    fun unavailableProviderIsSkippedSilently() = runTest {
        val sink = FakeSink()
        val r = SpeechPipeline(sink).speak(text, listOf(FakeProvider(TtsEngine.SARVAM, available = false), FakeProvider(TtsEngine.ANDROID)), "hi-IN", 1f)
        assertTrue(r.completed)
        assertEquals(null, r.error) // no key/offline is expected, not an error
        assertTrue(sink.played.all { it.startsWith("ANDROID:") })
    }

    @Test
    fun failingProviderFallsBackFromTheSameChunkAndReportsWhy() = runTest {
        val long = longText
        val chunks = chunkForSpeech(long)
        assertTrue(chunks.size >= 3)
        val sink = FakeSink()
        val sarvam = FakeProvider(TtsEngine.SARVAM, failAfter = 1)
        val r = SpeechPipeline(sink).speak(long, listOf(sarvam, FakeProvider(TtsEngine.ANDROID)), "hi-IN", 1f)
        assertTrue(r.completed)
        assertEquals(chunks.size, sink.played.size) // nothing skipped
        assertEquals("SARVAM:${chunks[0]}", sink.played[0])
        assertEquals("ANDROID:${chunks[1]}", sink.played[1]) // failed chunk retried on the fallback
        assertEquals(2, sarvam.calls.size) // dropped after its failure
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("SARVAM failed"))
    }

    @Test
    fun allProvidersFailingReturnsError() = runTest {
        val sink = FakeSink()
        val r = SpeechPipeline(sink).speak(text, listOf(FakeProvider(TtsEngine.SARVAM, failAfter = 0), FakeProvider(TtsEngine.ANDROID, failAfter = 0)), "hi-IN", 1f)
        assertFalse(r.completed)
        assertTrue(sink.played.isEmpty())
        assertEquals(0, sink.focus)
    }

    @Test
    fun noProvidersAvailableIsReported() = runTest {
        val r = SpeechPipeline(FakeSink()).speak(text, listOf(FakeProvider(TtsEngine.SARVAM, available = false)), "hi-IN", 1f)
        assertFalse(r.completed)
    }

    @Test
    fun nextChunkIsPrefetchedWhileCurrentOnePlays() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sink = FakeSink(gate)
        val provider = FakeProvider(TtsEngine.SARVAM)
        assertTrue(chunkForSpeech(longText).size >= 3)
        val job = async { SpeechPipeline(sink).speak(longText, listOf(provider), "hi-IN", 1f) }
        runCurrent()
        // First chunk is "playing" (blocked); the second has already been synthesised.
        assertTrue(sink.played.isEmpty())
        assertTrue(provider.calls.size >= 2)
        gate.complete(Unit)
        assertTrue(job.await().completed)
    }

    @Test
    fun cancellingStopsPlaybackAndSynthesis() = runTest {
        val gate = CompletableDeferred<Unit>()
        val sink = FakeSink(gate)
        val provider = FakeProvider(TtsEngine.SARVAM)
        val job = async { SpeechPipeline(sink).speak(text, listOf(provider), "hi-IN", 1f) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(sink.played.isEmpty())
        assertEquals(0, sink.focus)
        assertTrue(provider.calls.size <= 2)
    }

    @Test
    fun devanagariIsAlwaysHindiRomanUsesPreference() {
        assertEquals("hi-IN", languageFor("आप कैसे हैं?", "en-IN"))
        assertEquals("en-IN", languageFor("How are you?", "en-IN"))
        assertEquals("hi-IN", languageFor("Aap kaise ho?", "hi-IN"))
    }

    @Test
    fun firstChunkIsShortSoSpeechStartsQuickly() {
        val long = (1..20).joinToString(" ") { "Yeh vaakya $it hai." }
        val chunks = chunkForSpeech(long)
        assertTrue(chunks.first().length <= 160)
        assertTrue(chunks.drop(1).all { it.length <= 400 })
        assertTrue(chunks.size >= 2)
    }

    @Test
    fun emojiAndAppMarkersAreNotSpoken() {
        assertEquals("Call ho gaya", speakableText("✅ Call ho gaya 🎉"))
        assertEquals("Dhyan dein", speakableText("⚠️ Dhyan dein"))
        assertEquals("नमस्ते", speakableText("नमस्ते")) // Devanagari untouched
    }
}
