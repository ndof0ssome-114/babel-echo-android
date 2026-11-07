package app.babelecho.android

import app.babelecho.android.cloud.CloudClient
import app.babelecho.android.cloud.NetworkPolicy
import app.babelecho.android.data.AppSettings
import app.babelecho.android.recording.RecordingService
import app.babelecho.android.recording.WavFileWriter
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regressions for the 2026-11 code review fixes. */
class ReviewFixesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun chat(content: String) = MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", content)))).toString())

    @Test fun plainHttpIsOnlyForLocalNetworks() {
        for (host in listOf("127.0.0.1", "localhost", "10.0.2.2", "192.168.1.20", "172.16.0.9", "nas", "gpu.local", "[::1]", "fd00::5")) {
            assertTrue(host, NetworkPolicy.isLocalNetworkHost(host))
        }
        for (host in listOf("api.example.com", "8.8.8.8", "172.32.1.1", "2001:db8::1", "")) {
            assertFalse(host, NetworkPolicy.isLocalNetworkHost(host))
        }
    }

    @Test fun publicPlainHttpIsRefusedEvenWhenInsecureHttpIsEnabled() = runBlocking {
        val settings = AppSettings(llmBaseUrl = "http://api.example.com/v1", llmApiKey = "sk-test", llmModel = "m",
            summaryModel = "m", allowInsecureHttp = true)
        val error = runCatching { CloudClient(temp.root).summarize("hello", settings) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!, error.message!!.contains("HTTPS"))
    }

    @Test fun retryDelaysFollowRetryAfterAndStop() {
        assertEquals(2_000L, NetworkPolicy.retryDelayMs(429, "2", attempt = 0))
        assertEquals(30_000L, NetworkPolicy.retryDelayMs(429, "600", attempt = 0))
        assertEquals(1_000L, NetworkPolicy.retryDelayMs(503, null, attempt = 0))
        assertEquals(2_000L, NetworkPolicy.retryDelayMs(503, "soon", attempt = 1))
        assertNull("last attempt is not retried", NetworkPolicy.retryDelayMs(429, "1", attempt = NetworkPolicy.MAX_ATTEMPTS - 1))
        assertNull("client errors are not retried", NetworkPolicy.retryDelayMs(400, "1", attempt = 0))
        assertNull(NetworkPolicy.retryDelayMs(401, null, attempt = 0))
    }

    @Test fun rateLimitedRequestIsRetriedAndSucceeds() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1").setBody("slow down"))
            server.enqueue(chat("summary after retry"))
            val settings = AppSettings(llmBaseUrl = server.url("/v1").toString(), llmNoAuth = true, llmModel = "m",
                summaryModel = "m", allowInsecureHttp = true)
            assertEquals("summary after retry", CloudClient(temp.root).summarize("hello", settings))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun liveUploadIsDownsampledTo16kHz() {
        val tone = ShortArray(48_000) { (it % 3 * 300).toShort() } // 0, 300, 600 repeating
        val out = RecordingService.downsampleForUpload(tone)
        assertEquals(16_000, out.size)
        assertTrue("groups of three are averaged", out.all { it.toInt() == 300 })
        val file = temp.newFile("live.wav")
        WavFileWriter(file, 16_000).use { it.write(out) }
        assertEquals(44L + 32_000L, file.length())
    }
}
