package dev.kartiks.animiru.miruroll

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections

class MiruRollPlaybackSessionTest {

    private data class RecordedCall(
        val method: String,
        val url: String,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() {
        testScope.cancel()
    }

    private fun jsonResponse(request: okhttp3.Request, code: Int, body: String = "{}"): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun newManager(
        handler: (RecordedCall) -> Pair<Int, String>,
        invalidatedTokens: MutableList<String>,
        initialKeepAliveDelayMs: Long = 30L,
        minHeartbeatIntervalMs: Long = 40L,
        maxHeartbeatIntervalMs: Long = 120L,
        fallbackIdlePauseMs: Long = 5_000L,
        fallbackIdleDeleteMs: Long = 10_000L,
        pollStepMs: Long = 15L,
    ): MiruRollPlaybackSessionManager {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val recorded = RecordedCall(req.method, req.url.toString())
                val (code, body) = handler(recorded)
                jsonResponse(req, code, body)
            })
            .build()
        return MiruRollPlaybackSessionManager(
            client = client,
            json = json,
            scope = testScope,
            onTokenInvalidated = { invalidatedTokens.add(it) },
            initialKeepAliveDelayMs = initialKeepAliveDelayMs,
            minHeartbeatIntervalMs = minHeartbeatIntervalMs,
            maxHeartbeatIntervalMs = maxHeartbeatIntervalMs,
            fallbackIdlePauseMs = fallbackIdlePauseMs,
            fallbackIdleDeleteMs = fallbackIdleDeleteMs,
            pollStepMs = pollStepMs,
            logDebug = {},
            logWarn = { _, _ -> },
        )
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for condition")
            }
            delay(15L)
        }
    }

    @Test
    fun `startSession sends active then keepAlive with playhead and forwards renew state`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<RecordedCall>())
        val invalidated = Collections.synchronizedList(mutableListOf<String>())
        val manager = newManager(
            handler = { call ->
                calls.add(call)
                when {
                    call.url.endsWith("/active") -> 200 to """{"renewSeconds":15,"maximumPauseSeconds":600}"""
                    call.url.contains("/keepAlive") -> 200 to """{"renewSeconds":20,"maximumPauseSeconds":600}"""
                    else -> 204 to ""
                }
            },
            invalidatedTokens = invalidated,
        )

        manager.startSession(
            contentId = "EP1",
            videoToken = "tok1",
            initialSession = PlaybackSessionState(renewSeconds = 270),
            initialPlayheadSeconds = 12L,
        )
        manager.awaitPendingForTesting()

        waitUntil { calls.count { it.url.contains("/keepAlive") } >= 1 }
        waitUntil { manager.currentSessionSnapshot()?.sessionState?.renewSeconds == 20 }

        assertEquals("PATCH", calls[0].method)
        assertEquals("https://www.crunchyroll.com/playback/v1/token/EP1/tok1/active", calls[0].url)
        assertEquals("PATCH", calls[1].method)
        assertEquals("https://www.crunchyroll.com/playback/v1/token/EP1/tok1/keepAlive?playhead=12", calls[1].url)
        assertEquals(20, manager.currentSessionSnapshot()?.sessionState?.renewSeconds)

        manager.onPlaybackStopped()
        manager.awaitPendingForTesting()
        waitUntil { calls.any { it.method == "DELETE" } }

        val inactiveIdx = calls.indexOfLast { it.url.endsWith("/inactive") }
        val deleteIdx = calls.indexOfFirst { it.method == "DELETE" }
        assertTrue(inactiveIdx in 0 until deleteIdx)
        val deleteCall = calls[deleteIdx]
        assertEquals("https://www.crunchyroll.com/playback/v1/token/EP1/tok1", deleteCall.url)
        assertEquals(listOf("tok1"), invalidated.toList())
        assertNull(manager.currentSessionSnapshot())
    }

    @Test
    fun `noteProgress forwards updated playhead and onComplete deletes session`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<RecordedCall>())
        val invalidated = Collections.synchronizedList(mutableListOf<String>())
        val manager = newManager(
            handler = { call ->
                calls.add(call)
                200 to """{"renewSeconds":15}"""
            },
            invalidatedTokens = invalidated,
            initialKeepAliveDelayMs = 20L,
            minHeartbeatIntervalMs = 40L,
            maxHeartbeatIntervalMs = 40L,
        )

        manager.startSession("EP2", "tok2", null, initialPlayheadSeconds = 0L)
        manager.awaitPendingForTesting()
        waitUntil { calls.any { it.url.endsWith("/keepAlive?playhead=0") } }

        manager.noteProgress(positionSeconds = 145L, onComplete = false)
        waitUntil { calls.any { it.url.endsWith("/keepAlive?playhead=145") } }

        manager.noteProgress(positionSeconds = 1320L, onComplete = true)
        manager.awaitPendingForTesting()
        waitUntil { calls.any { it.method == "DELETE" && it.url.endsWith("/EP2/tok2") } }

        assertTrue(invalidated.contains("tok2"))
        assertNull(manager.currentSessionSnapshot())
    }

    @Test
    fun `explicit pause sends inactive and resume sends active without fallback idle delete`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<RecordedCall>())
        val invalidated = Collections.synchronizedList(mutableListOf<String>())
        val manager = newManager(
            handler = { call ->
                calls.add(call)
                200 to """{"renewSeconds":15,"maximumPauseSeconds":600}"""
            },
            invalidatedTokens = invalidated,
            initialKeepAliveDelayMs = 25L,
            minHeartbeatIntervalMs = 45L,
            maxHeartbeatIntervalMs = 45L,
            fallbackIdlePauseMs = 40L,
            fallbackIdleDeleteMs = 90L,
            pollStepMs = 15L,
        )

        manager.startSession("EP3", "tok3", null)
        manager.awaitPendingForTesting()
        waitUntil { calls.any { it.url.contains("/keepAlive") } }

        manager.onPlaybackPaused()
        manager.awaitPendingForTesting()
        waitUntil { calls.any { it.url.endsWith("/inactive") } }

        val snapshotPaused = manager.currentSessionSnapshot()
        assertNotNull(snapshotPaused)
        assertTrue(snapshotPaused!!.isPaused)

        val countAfterPause = calls.size
        delay(160L)
        // Explicit lifecycle uses server maximumPauseSeconds (600s), so fallback idle delete must not fire.
        assertEquals(countAfterPause, calls.size)
        assertTrue(calls.none { it.method == "DELETE" })

        manager.onPlaybackResumed(positionSeconds = 88L)
        manager.awaitPendingForTesting()
        waitUntil {
            calls.count { it.url.endsWith("/active") } >= 2 &&
                calls.any { it.url.endsWith("/keepAlive?playhead=88") }
        }

        manager.onPlaybackStopped()
        manager.awaitPendingForTesting()
    }

    @Test
    fun `starting a new episode session deletes previous active token first`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<RecordedCall>())
        val invalidated = Collections.synchronizedList(mutableListOf<String>())
        val manager = newManager(
            handler = { call ->
                calls.add(call)
                200 to "{}"
            },
            invalidatedTokens = invalidated,
            initialKeepAliveDelayMs = 500L,
        )

        manager.startSession("EP_OLD", "tok_old", null)
        manager.awaitPendingForTesting()

        manager.startSession("EP_NEW", "tok_new", null)
        manager.awaitPendingForTesting()

        val methodsAndPaths = calls.map { "${it.method}:${it.url.substringAfter("/playback/v1/token/")}" }
        assertEquals(
            listOf(
                "PATCH:EP_OLD/tok_old/active",
                "PATCH:EP_OLD/tok_old/inactive",
                "DELETE:EP_OLD/tok_old",
                "PATCH:EP_NEW/tok_new/active",
            ),
            methodsAndPaths,
        )
        assertTrue(invalidated.contains("tok_old"))

        manager.onPlaybackStopped()
        manager.awaitPendingForTesting()
    }

    @Test
    fun `keepAlive 410 evicts cached token and clears active session`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<RecordedCall>())
        val invalidated = Collections.synchronizedList(mutableListOf<String>())
        val manager = newManager(
            handler = { call ->
                calls.add(call)
                if (call.url.contains("/keepAlive")) 410 to "" else 200 to """{"renewSeconds":15}"""
            },
            invalidatedTokens = invalidated,
            initialKeepAliveDelayMs = 20L,
        )

        manager.startSession("EP410", "tok410", null)
        manager.awaitPendingForTesting()
        waitUntil { invalidated.contains("tok410") && calls.any { it.method == "DELETE" } }

        assertNull(manager.currentSessionSnapshot())
    }

    @Test
    fun `keepAlive retries after IOException and continues when network recovers`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<RecordedCall>())
        val invalidated = Collections.synchronizedList(mutableListOf<String>())
        var failFirstKeepAlive = true
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val recorded = RecordedCall(req.method, req.url.toString())
                calls.add(recorded)
                if (recorded.url.contains("/keepAlive") && failFirstKeepAlive) {
                    failFirstKeepAlive = false
                    throw IOException("simulated offline")
                }
                val body = if (recorded.url.endsWith("/active")) {
                    """{"renewSeconds":15,"noNetworkRetryIntervalSeconds":1,"noNetworkTimeoutSeconds":30}"""
                } else {
                    """{"renewSeconds":15}"""
                }
                jsonResponse(req, 200, body)
            })
            .build()

        val manager = MiruRollPlaybackSessionManager(
            client = client,
            json = json,
            scope = testScope,
            onTokenInvalidated = { invalidated.add(it) },
            initialKeepAliveDelayMs = 20L,
            minHeartbeatIntervalMs = 50L,
            maxHeartbeatIntervalMs = 100L,
            pollStepMs = 15L,
            logDebug = {},
            logWarn = { _, _ -> },
        )

        manager.startSession("EP_NET", "tok_net", null, initialPlayheadSeconds = 5L)
        manager.awaitPendingForTesting()

        waitUntil(timeoutMs = 8_000L) {
            calls.count { it.url.contains("/keepAlive") } >= 2
        }

        assertNotNull(manager.currentSessionSnapshot())
        assertTrue(invalidated.isEmpty())

        manager.onPlaybackStopped()
        manager.awaitPendingForTesting()
    }

    @Test
    fun `fallback idle pauses and deletes token when explicit lifecycle hooks are unused`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<RecordedCall>())
        val invalidated = Collections.synchronizedList(mutableListOf<String>())
        val manager = newManager(
            handler = { call ->
                calls.add(call)
                200 to """{"renewSeconds":15}"""
            },
            invalidatedTokens = invalidated,
            initialKeepAliveDelayMs = 20L,
            minHeartbeatIntervalMs = 150L,
            maxHeartbeatIntervalMs = 200L,
            fallbackIdlePauseMs = 40L,
            fallbackIdleDeleteMs = 90L,
            pollStepMs = 15L,
        )

        manager.startSession("EP_IDLE", "tok_idle", null)
        manager.awaitPendingForTesting()

        waitUntil { calls.any { it.url.endsWith("/inactive") } }
        waitUntil { calls.any { it.method == "DELETE" && it.url.endsWith("/EP_IDLE/tok_idle") } }

        assertTrue(invalidated.contains("tok_idle"))
        assertNull(manager.currentSessionSnapshot())
    }

    @Test
    fun `startSession with same active token does not send duplicate active`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<RecordedCall>())
        val invalidated = Collections.synchronizedList(mutableListOf<String>())
        val manager = newManager(
            handler = { call ->
                calls.add(call)
                200 to """{"renewSeconds":15}"""
            },
            invalidatedTokens = invalidated,
            initialKeepAliveDelayMs = 200L,
        )

        manager.startSession("EP_SAME", "tok_same", null)
        manager.awaitPendingForTesting()
        manager.startSession("EP_SAME", "tok_same", null)
        manager.awaitPendingForTesting()

        assertEquals(1, calls.count { it.url.endsWith("/active") })
        assertTrue(invalidated.isEmpty())

        manager.onPlaybackStopped()
        manager.awaitPendingForTesting()
    }
}
