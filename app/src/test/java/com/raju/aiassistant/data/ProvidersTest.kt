package com.raju.aiassistant.data

import com.raju.aiassistant.data.ai.GeminiProvider
import com.raju.aiassistant.data.ai.OpenAiCompatibleProvider
import com.raju.aiassistant.data.search.TavilyClient
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
    fun sarvamDecodesAudio() = runBlocking {
        val audio = byteArrayOf(1, 2, 3)
        enqueue(200, """{"request_id":"x","audios":["${Base64.getEncoder().encodeToString(audio)}"]}""")
        val r = SarvamTtsClient(http, "sk", url).synthesize("Namaste", "anushka", "hi-IN", 1f) as AiResult.Success
        assertTrue(r.value.contentEquals(audio))
        val req = server.takeRequest()
        assertEquals("sk", req.getHeader("api-subscription-key"))
        assertTrue(req.body.readUtf8().contains("\"speaker\":\"anushka\""))
    }
}
