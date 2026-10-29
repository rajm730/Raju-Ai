package com.raju.aiassistant.data

import com.raju.aiassistant.data.ai.GeminiProvider
import com.raju.aiassistant.data.ai.OpenAiCompatibleProvider
import com.raju.aiassistant.data.search.TavilyClient
import com.raju.aiassistant.data.tts.CartesiaClient
import com.raju.aiassistant.data.tts.DeepgramClient
import com.raju.aiassistant.data.tts.ElevenLabsClient
import com.raju.aiassistant.data.tts.SarvamTtsClient
import com.raju.aiassistant.domain.ai.AiResult
import com.raju.aiassistant.domain.ai.FailureKind
import com.raju.aiassistant.domain.ai.ProviderId
import com.raju.aiassistant.domain.ai.Turn
import com.raju.aiassistant.domain.ai.TurnRole
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.raju.aiassistant.domain.model.validSarvamSpeaker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class ProvidersTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
    private val url get() = server.url("/v1").toString().trimEnd('/')

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun enqueue(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun geminiChatSendsKeyInHeaderAndParsesText() = runBlocking {
        enqueue(200, """{"candidates":[{"content":{"parts":[{"text":"Namaste "},{"text":"Raju!"}]}}]}""")
        val r = GeminiProvider(http, "KEY123", url).chat("gemini-2.5-flash", "sys", listOf(Turn(TurnRole.USER, "hi")))
        assertEquals(AiResult.Success("Namaste Raju!"), r)
        val req = server.takeRequest()
        assertEquals("KEY123", req.getHeader("x-goog-api-key"))
        assertTrue(req.path!!.endsWith("/models/gemini-2.5-flash:generateContent"))
        assertTrue(!req.path!!.contains("KEY123"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"systemInstruction\"") && body.contains("\"role\":\"user\""))
    }

    @Test
    fun geminiModelsKeepOnlyChatModels() = runBlocking {
        enqueue(200, """{"models":[{"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","supportedGenerationMethods":["generateContent"]},{"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}""")
        val r = GeminiProvider(http, "k", url).listModels() as AiResult.Success
        assertEquals(listOf("gemini-2.5-flash"), r.value.map { it.id })
    }

    @Test
    fun invalidKeyIsReportedAndNotRetried() = runBlocking {
        enqueue(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""")
        val r = GeminiProvider(http, "bad", url).testConnection() as AiResult.Failure
        assertEquals(FailureKind.INVALID_KEY, r.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rateLimitAndQuotaAreDistinguished() = runBlocking {
        enqueue(429, """{"error":"Too many requests"}""")
        val groq = OpenAiCompatibleProvider(ProviderId.GROQ, http, "k", url)
        assertEquals(FailureKind.RATE_LIMITED, (groq.listModels() as AiResult.Failure).kind)
        enqueue(429, """{"error":{"message":"You exceeded your current quota"}}""")
        assertEquals(FailureKind.QUOTA_EXCEEDED, (groq.listModels() as AiResult.Failure).kind)
    }

    @Test
    fun serverErrorIsRetriedOnce() = runBlocking {
        enqueue(503, "{}")
        enqueue(200, """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}""")
        val r = OpenAiCompatibleProvider(ProviderId.GROQ, http, "k", url).chat("llama-3.3-70b-versatile", "sys", listOf(Turn(TurnRole.USER, "hi")))
        assertEquals(AiResult.Success("OK"), r)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("Bearer k", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun timeoutIsReported() = runBlocking {
        server.enqueue(MockResponse().setBody("{}").setHeadersDelay(5, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("{}").setHeadersDelay(5, TimeUnit.SECONDS))
        val r = OpenAiCompatibleProvider(ProviderId.GROQ, http, "k", url).listModels() as AiResult.Failure
        assertEquals(FailureKind.TIMEOUT, r.kind)
    }

    @Test
    fun tavilyDropsUnsafeUrls() = runBlocking {
        enqueue(200, """{"answer":"Sunny","results":[{"title":"A","url":"https://a.com","content":"x"},{"title":"B","url":"javascript:alert(1)","content":"y"}]}""")
        val r = TavilyClient(http, "tvly-k", url).search("weather") as AiResult.Success
        assertEquals(listOf("https://a.com"), r.value.results.map { it.url })
        assertEquals("Sunny", r.value.answer)
        assertEquals("Bearer tvly-k", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun tavilyNewsTopicDatesAndHindiQuery() = runBlocking {
        enqueue(200, """{"results":[{"title":"Khabar","url":"https://www.example.in/a","content":"x","published_date":"Wed, 28 Oct 2026"}]}""")
        val r = TavilyClient(http, "tvly-k", url).search("आज की ताज़ा खबरें", topic = "news") as AiResult.Success
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"topic\":\"news\""))
        assertTrue(body.contains("आज की ताज़ा खबरें")) // UTF-8 preserved
        assertEquals("Wed, 28 Oct 2026", r.value.results.single().publishedDate)
        assertEquals("example.in", r.value.results.single().domain)
    }

    @Test
    fun tavilyErrorsAreTypedAndHonest() = runBlocking {
        val client = TavilyClient(http, "bad", url)
        enqueue(401, """{"detail":{"error":"Unauthorized: missing or invalid API key."}}""")
        assertEquals(FailureKind.INVALID_KEY, (client.search("q") as AiResult.Failure).kind)
        enqueue(429, """{"detail":"rate limit"}""")
        assertEquals(FailureKind.RATE_LIMITED, (client.search("q") as AiResult.Failure).kind)
        enqueue(200, "not json")
        assertEquals(FailureKind.UNKNOWN, (client.search("q") as AiResult.Failure).kind)
        enqueue(200, """{"results":[]}""")
        assertTrue((client.search("q") as AiResult.Success).value.results.isEmpty())
    }

    @Test
    fun sarvamDecodesAudio() = runBlocking {
        val audio = byteArrayOf(1, 2, 3)
        enqueue(200, """{"request_id":"x","audios":["${Base64.getEncoder().encodeToString(audio)}"]}""")
        val r = SarvamTtsClient(http, "sk", url).synthesize("Namaste", "priya", "hi-IN", 1f) as AiResult.Success
        assertTrue(r.value.contentEquals(audio))
        val req = server.takeRequest()
        assertEquals("sk", req.getHeader("api-subscription-key"))
        val sent = req.body.readUtf8()
        assertTrue(sent.contains("\"speaker\":\"priya\""))
        assertTrue(sent.contains("\"model\":\"bulbul:v3\""))
        assertTrue(sent.contains("\"enable_preprocessing\":true"))
    }

    @Test
    fun cutOffRepliesAreMarked() = runBlocking {
        enqueue(200, """{"candidates":[{"content":{"parts":[{"text":"Adhura"}]},"finishReason":"MAX_TOKENS"}]}""")
        val g = GeminiProvider(http, "k", url).chat("gemini-2.5-flash", "s", listOf(Turn(TurnRole.USER, "hi"))) as AiResult.Success
        assertTrue(g.value.startsWith("Adhura") && g.value.contains("कट गया"))
        enqueue(200, """{"choices":[{"message":{"content":"Adhura"},"finish_reason":"length"}]}""")
        val q = OpenAiCompatibleProvider(ProviderId.GROQ, http, "k", url).chat("m", "s", listOf(Turn(TurnRole.USER, "hi"))) as AiResult.Success
        assertTrue(q.value.contains("कट गया"))
        server.takeRequest(); assertTrue(server.takeRequest().body.readUtf8().contains("\"max_tokens\":4096"))
    }

    @Test
    fun oldBulbulV2VoiceFallsBackToV3Default() {
        assertEquals("priya", validSarvamSpeaker("anushka"))
        assertEquals("priya", validSarvamSpeaker(null))
        assertEquals("kavya", validSarvamSpeaker("kavya"))
    }

    // ---------------------------------------------------------------- ElevenLabs / Cartesia / Deepgram

    private val wavBytes get() = com.raju.aiassistant.core.audio.pcm16ToWav(ByteArray(480), 24_000)
    private fun enqueueBytes(bytes: ByteArray) = server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes)))

    @Test
    fun elevenLabsSpeaksWithKeyHeaderModelAndWav() = runBlocking {
        enqueueBytes(wavBytes)
        val r = ElevenLabsClient(http, "xi", url).synthesize("Namaste", "voice1", "eleven_multilingual_v2", 1.5f) as AiResult.Success
        assertTrue(r.value.contentEquals(wavBytes))
        val req = server.takeRequest()
        assertEquals("xi", req.getHeader("xi-api-key"))
        assertTrue(req.path!!.endsWith("/v1/text-to-speech/voice1?output_format=wav_24000"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"model_id\":\"eleven_multilingual_v2\""))
        assertTrue(body.contains("\"speed\":1.2")) // clamped to ElevenLabs' range
    }

    @Test
    fun elevenLabsListsHindiTtsModelsAndFemaleVoices() = runBlocking {
        enqueue(200, """[{"model_id":"eleven_multilingual_v2","name":"Multilingual v2","can_do_text_to_speech":true,"languages":[{"language_id":"en","name":"English"},{"language_id":"hi","name":"Hindi"}]},
            {"model_id":"eleven_english_sts_v2","can_do_text_to_speech":false},
            {"model_id":"eleven_monolingual_v1","can_do_text_to_speech":true,"languages":[{"language_id":"en","name":"English"}]}]""")
        val models = ElevenLabsClient(http, "xi", url).listModels() as AiResult.Success
        assertEquals(listOf("eleven_multilingual_v2"), models.value.map { it.first })
        enqueue(200, """{"voices":[{"voice_id":"a","name":"Sarah","category":"premade","labels":{"gender":"female","accent":"american"}},{"voice_id":"b","name":"Adam","labels":{"gender":"male"}},{"voice_id":"c","name":"Tara","category":"professional","labels":{"gender":"female"}}],"has_more":false,"total_count":3}""")
        val voices = ElevenLabsClient(http, "xi", url).listVoices() as AiResult.Success
        assertEquals(listOf(true, false, true), voices.value.map { it.female })
        assertEquals(listOf(false, false, true), voices.value.map { it.paidOnly }) // library voice needs a paid plan
        assertEquals("american", voices.value.first().detail)
    }

    @Test
    fun elevenLabsUsedUpCreditsIsQuotaNotBadKey() = runBlocking {
        enqueue(401, """{"detail":{"status":"quota_exceeded","message":"This request exceeds your quota."}}""")
        val r = ElevenLabsClient(http, "xi", url).synthesize("Hi", "v", "m", 1f) as AiResult.Failure
        assertEquals(FailureKind.QUOTA_EXCEEDED, r.kind)
        assertTrue(r.message.contains("quota_exceeded"))
    }

    @Test
    fun cartesiaSpeaksWithVersionHeaderVoiceAndMood() = runBlocking {
        enqueueBytes(wavBytes)
        val r = CartesiaClient(http, "ck", url).synthesize("Namaste", "v-hi", "sonic-3", "hi", 1f, "content") as AiResult.Success
        assertTrue(r.value.contentEquals(wavBytes))
        val req = server.takeRequest()
        assertEquals("Bearer ck", req.getHeader("Authorization"))
        assertEquals(CartesiaClient.API_VERSION, req.getHeader("Cartesia-Version"))
        assertTrue(req.path!!.endsWith("/tts/bytes"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"voice\":{\"id\":\"v-hi\"}"))
        assertTrue(body.contains("\"container\":\"wav\"") && body.contains("\"encoding\":\"pcm_s16le\""))
        assertTrue(body.contains("\"language\":\"hi\"") && body.contains("\"emotion\":\"content\""))
    }

    @Test
    fun cartesiaVoicePagesAreFollowed() = runBlocking {
        enqueue(200, """{"data":[{"id":"v1","name":"Emma","language":"en","gender":"feminine"}],"has_more":true}""")
        enqueue(200, """{"data":[{"id":"v2","name":"Riya","language":"hi","gender":"feminine","description":"Warm Hindi voice"}],"has_more":false}""")
        val r = CartesiaClient(http, "ck", url).listVoices() as AiResult.Success
        assertEquals(listOf("v1", "v2"), r.value.map { it.id })
        assertTrue(r.value[1].hindi)
        server.takeRequest()
        val second = server.takeRequest()
        assertTrue(second.path!!.contains("starting_after=v1") && second.path!!.contains("gender=feminine"))
    }

    @Test
    fun deepgramSpeaksAndTranscribes() = runBlocking {
        enqueueBytes(wavBytes)
        val speak = DeepgramClient(http, "dg", url).synthesize("Hello", "aura-2-thalia-en") as AiResult.Success
        assertTrue(speak.value.contentEquals(wavBytes))
        val req = server.takeRequest()
        assertEquals("Token dg", req.getHeader("Authorization"))
        assertTrue(req.path!!.contains("/v1/speak?model=aura-2-thalia-en&encoding=linear16&container=wav&sample_rate=24000"))

        enqueue(200, """{"results":{"channels":[{"alternatives":[{"transcript":"Mom ko call karo","confidence":0.97}]}]}}""")
        val text = DeepgramClient(http, "dg", url).transcribe(wavBytes, "nova-3", "multi") as AiResult.Success
        assertEquals("Mom ko call karo", text.value)
        val listen = server.takeRequest()
        assertTrue(listen.path!!.contains("/v1/listen?model=nova-3&language=multi"))
        assertEquals("audio/wav", listen.getHeader("Content-Type"))
    }
}
