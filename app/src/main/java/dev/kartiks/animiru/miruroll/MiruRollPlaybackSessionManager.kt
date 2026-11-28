package dev.kartiks.animiru.miruroll

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Coordinates Crunchyroll's `/playback/v1/token/{contentId}/{videoToken}` lifecycle
 * (`active`, `keepAlive`, `inactive`, and `DELETE`) so server-side integrations
 * (including Crunchyroll's Discord Connected App and stream-concurrency leases) stay accurate.
 */
internal class MiruRollPlaybackSessionManager(
    private val client: OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
    private val onTokenInvalidated: (String) -> Unit,
    private val initialKeepAliveDelayMs: Long = DEFAULT_INITIAL_KEEPALIVE_DELAY_MS,
    private val minHeartbeatIntervalMs: Long = DEFAULT_MIN_HEARTBEAT_INTERVAL_MS,
    private val maxHeartbeatIntervalMs: Long = DEFAULT_MAX_HEARTBEAT_INTERVAL_MS,
    private val fallbackIdlePauseMs: Long = DEFAULT_FALLBACK_IDLE_PAUSE_MS,
    private val fallbackIdleDeleteMs: Long = DEFAULT_FALLBACK_IDLE_DELETE_MS,
    private val pollStepMs: Long = DEFAULT_POLL_STEP_MS,
    private val timeProvider: () -> Long = System::currentTimeMillis,
    private val logDebug: (String) -> Unit = { Log.d(TAG, it) },
    private val logWarn: (String, Throwable?) -> Unit = { msg, err -> Log.w(TAG, msg, err) },
) {

    internal data class ActiveSession(
        val contentId: String,
        val videoToken: String,
        val startedAtMs: Long,
        @Volatile var lastActivityMs: Long,
        @Volatile var playheadSeconds: Long,
        @Volatile var sessionState: PlaybackSessionState,
        @Volatile var isPaused: Boolean = false,
        @Volatile var pausedAtMs: Long = 0L,
        @Volatile var hasExplicitLifecycle: Boolean = false,
    )

    private data class PatchResult(
        val statusCode: Int,
        val sessionState: PlaybackSessionState?,
        val networkFailed: Boolean = false,
    )

    private val sessionMutex = Mutex()

    @Volatile
    private var activeSession: ActiveSession? = null
    private var heartbeatJob: Job? = null
    private var pendingCommandJob: Job? = null

    fun startSession(
        contentId: String,
        videoToken: String,
        initialSession: PlaybackSessionState?,
        initialPlayheadSeconds: Long = 0L,
    ) {
        if (contentId.isBlank() || videoToken.isBlank()) return
        enqueueCommand {
            sessionMutex.withLock {
                val prev = activeSession
                if (prev != null && prev.contentId == contentId && prev.videoToken == videoToken &&
                    !prev.isPaused && heartbeatJob?.isActive == true
                ) {
                    prev.lastActivityMs = timeProvider()
                    return@withLock
                }

                heartbeatJob?.cancel()
                heartbeatJob = null

                if (prev != null && (prev.contentId != contentId || prev.videoToken != videoToken)) {
                    activeSession = null
                    if (!prev.isPaused) {
                        patchTokenRemote(prev.contentId, prev.videoToken, "inactive")
                    }
                    deleteTokenRemote(prev.contentId, prev.videoToken)
                }

                val now = timeProvider()
                val session = ActiveSession(
                    contentId = contentId,
                    videoToken = videoToken,
                    startedAtMs = now,
                    lastActivityMs = now,
                    playheadSeconds = initialPlayheadSeconds.coerceAtLeast(0L),
                    sessionState = initialSession ?: PlaybackSessionState(),
                )
                activeSession = session

                val activeResult = patchTokenRemote(contentId, videoToken, "active")
                if (activeResult.statusCode == 404 || activeResult.statusCode == 410) {
                    activeSession = null
                    onTokenInvalidated(videoToken)
                    return@withLock
                }
                activeResult.sessionState?.let { session.sessionState = it }

                launchHeartbeatLocked(session)
            }
        }
    }

    fun noteProgress(positionSeconds: Long, onComplete: Boolean) {
        val session = activeSession ?: return
        session.lastActivityMs = timeProvider()
        if (positionSeconds >= 0L) {
            session.playheadSeconds = positionSeconds
        }
        if (onComplete) {
            onPlaybackStopped()
            return
        }
        // Only auto-resume from fallback idle if Animiru hasn't driven explicit pause/resume hooks.
        if (session.isPaused && !session.hasExplicitLifecycle) {
            onPlaybackResumed(positionSeconds)
        }
    }

    fun onPlaybackPaused() {
        activeSession?.hasExplicitLifecycle = true
        enqueueCommand {
            sessionMutex.withLock {
                val session = activeSession ?: return@withLock
                session.hasExplicitLifecycle = true
                if (session.isPaused) return@withLock
                session.isPaused = true
                session.pausedAtMs = timeProvider()
                session.lastActivityMs = session.pausedAtMs
                heartbeatJob?.cancel()
                heartbeatJob = null
                val result = patchTokenRemote(session.contentId, session.videoToken, "inactive")
                if (result.statusCode == 404 || result.statusCode == 410) {
                    activeSession = null
                    onTokenInvalidated(session.videoToken)
                    return@withLock
                }
                launchPausedTimeoutWatcherLocked(session)
            }
        }
    }

    fun onPlaybackResumed(positionSeconds: Long) {
        activeSession?.hasExplicitLifecycle = true
        enqueueCommand {
            sessionMutex.withLock {
                val session = activeSession ?: return@withLock
                session.hasExplicitLifecycle = true
                if (positionSeconds >= 0L) {
                    session.playheadSeconds = positionSeconds
                }
                session.lastActivityMs = timeProvider()
                if (!session.isPaused && heartbeatJob?.isActive == true) {
                    return@withLock
                }
                session.isPaused = false
                session.pausedAtMs = 0L
                heartbeatJob?.cancel()
                heartbeatJob = null

                val activeResult = patchTokenRemote(session.contentId, session.videoToken, "active")
                if (activeResult.statusCode == 404 || activeResult.statusCode == 410) {
                    activeSession = null
                    onTokenInvalidated(session.videoToken)
                    return@withLock
                }
                activeResult.sessionState?.let { session.sessionState = it }
                launchHeartbeatLocked(session)
            }
        }
    }

    fun onPlaybackStopped() {
        enqueueCommand {
            stopCurrentSessionLocked(expectedToken = null, cancelHeartbeat = true)
        }
    }

    private fun enqueueCommand(block: suspend () -> Unit) {
        val prevJob = pendingCommandJob
        pendingCommandJob = scope.launch {
            runCatching { prevJob?.join() }
            block()
        }
    }

    private fun launchHeartbeatLocked(session: ActiveSession) {
        val token = session.videoToken
        heartbeatJob = scope.launch {
            if (initialKeepAliveDelayMs > 0L) {
                delay(initialKeepAliveDelayMs)
            }
            if (!isActive || activeSession?.videoToken != token || session.isPaused) return@launch

            if (!sendKeepAliveWithRetry(session)) {
                return@launch
            }

            while (isActive && activeSession?.videoToken == token) {
                if (session.isPaused) break

                val targetIntervalMs = computeHeartbeatIntervalMs(session.sessionState)
                var waitedMs = 0L
                while (waitedMs < targetIntervalMs && isActive && activeSession?.videoToken == token && !session.isPaused) {
                    val step = minOf(pollStepMs, targetIntervalMs - waitedMs)
                    delay(step)
                    waitedMs += step

                    if (!session.hasExplicitLifecycle && !session.isPaused) {
                        val idleMs = timeProvider() - session.lastActivityMs
                        if (idleMs > fallbackIdlePauseMs) {
                            sessionMutex.withLock {
                                if (activeSession?.videoToken == token && !session.isPaused && !session.hasExplicitLifecycle) {
                                    session.isPaused = true
                                    session.pausedAtMs = timeProvider()
                                    patchTokenRemote(session.contentId, token, "inactive")
                                    launchPausedTimeoutWatcherLocked(session)
                                }
                            }
                            return@launch
                        }
                    }
                }

                if (!isActive || activeSession?.videoToken != token || session.isPaused) break
                if (!sendKeepAliveWithRetry(session)) {
                    return@launch
                }
            }
        }
    }

    private fun launchPausedTimeoutWatcherLocked(session: ActiveSession) {
        val token = session.videoToken
        val maxPauseMs = if (session.hasExplicitLifecycle) {
            val serverMaxSec = session.sessionState.maximumPauseSeconds.takeIf { it > 0 } ?: 900
            ((serverMaxSec * 1000L) - 10_000L).coerceAtLeast(60_000L)
        } else {
            fallbackIdleDeleteMs
        }
        heartbeatJob = scope.launch {
            var elapsed = 0L
            while (elapsed < maxPauseMs && isActive && activeSession?.videoToken == token && session.isPaused) {
                val step = minOf(pollStepMs, maxPauseMs - elapsed)
                delay(step)
                elapsed += step
            }
            if (isActive && activeSession?.videoToken == token && session.isPaused) {
                stopCurrentSessionLocked(expectedToken = token, cancelHeartbeat = false)
            }
        }
    }

    private suspend fun sendKeepAliveWithRetry(session: ActiveSession): Boolean {
        val token = session.videoToken
        val result = patchTokenRemote(
            contentId = session.contentId,
            videoToken = token,
            action = "keepAlive",
            playheadSeconds = session.playheadSeconds,
        )
        if (result.statusCode == 404 || result.statusCode == 410 || result.statusCode == 401 || result.statusCode == 403) {
            stopCurrentSessionLocked(
                expectedToken = token,
                cancelHeartbeat = false,
                deactivateBeforeDelete = false,
            )
            return false
        }
        if (result.sessionState != null) {
            session.sessionState = result.sessionState
            return true
        }
        if (!result.networkFailed) {
            return true
        }

        val retryIntervalMs = ((session.sessionState.noNetworkRetryIntervalSeconds.takeIf { it > 0 } ?: 30) * 1000L)
            .coerceIn(5_000L, 60_000L)
        val timeoutMs = ((session.sessionState.noNetworkTimeoutSeconds.takeIf { it > 0 } ?: 370) * 1000L)
            .coerceAtLeast(retryIntervalMs)
        val deadlineMs = timeProvider() + timeoutMs

        while (timeProvider() < deadlineMs && activeSession?.videoToken == token && !session.isPaused) {
            delay(retryIntervalMs)
            if (activeSession?.videoToken != token || session.isPaused) return false
            val retryResult = patchTokenRemote(
                contentId = session.contentId,
                videoToken = token,
                action = "keepAlive",
                playheadSeconds = session.playheadSeconds,
            )
            if (retryResult.statusCode == 404 || retryResult.statusCode == 410 || retryResult.statusCode == 401 || retryResult.statusCode == 403) {
                stopCurrentSessionLocked(
                    expectedToken = token,
                    cancelHeartbeat = false,
                    deactivateBeforeDelete = false,
                )
                return false
            }
            if (!retryResult.networkFailed) {
                retryResult.sessionState?.let { session.sessionState = it }
                return true
            }
        }
        stopCurrentSessionLocked(
            expectedToken = token,
            cancelHeartbeat = false,
            deactivateBeforeDelete = false,
        )
        return false
    }

    private suspend fun stopCurrentSessionLocked(
        expectedToken: String?,
        cancelHeartbeat: Boolean,
        deactivateBeforeDelete: Boolean = true,
    ) {
        sessionMutex.withLock {
            val current = activeSession ?: return@withLock
            if (expectedToken != null && current.videoToken != expectedToken) return@withLock
            if (cancelHeartbeat) {
                heartbeatJob?.cancel()
            }
            heartbeatJob = null
            activeSession = null
            if (deactivateBeforeDelete && !current.isPaused) {
                patchTokenRemote(current.contentId, current.videoToken, "inactive")
            }
            deleteTokenRemote(current.contentId, current.videoToken)
        }
    }

    private fun computeHeartbeatIntervalMs(state: PlaybackSessionState): Long {
        val renewMs = (state.renewSeconds.takeIf { it > 0 } ?: 270) * 1000L
        return (renewMs - 10_000L).coerceIn(minHeartbeatIntervalMs, maxHeartbeatIntervalMs)
    }

    private fun patchTokenRemote(
        contentId: String,
        videoToken: String,
        action: String,
        playheadSeconds: Long? = null,
    ): PatchResult {
        val base = "https://www.crunchyroll.com/playback/v1/token/$contentId/$videoToken/$action"
        val url = if (playheadSeconds != null) "$base?playhead=$playheadSeconds" else base
        val req = Request.Builder()
            .url(url)
            .patch(ByteArray(0).toRequestBody(null))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                val body = r.body.string()
                logDebug("Playback session $action contentId=$contentId -> HTTP ${r.code}")
                val parsed = if (r.isSuccessful && body.isNotBlank()) {
                    runCatching { json.decodeFromString<PlaybackSessionState>(body) }.getOrNull()
                } else {
                    if (!r.isSuccessful && r.code != 404 && r.code != 410) {
                        logWarn("Playback session $action unexpected HTTP ${r.code}: ${body.take(200)}", null)
                    }
                    null
                }
                PatchResult(statusCode = r.code, sessionState = parsed)
            }
        } catch (e: IOException) {
            logWarn("Playback session $action network error for $contentId: ${e.message}", null)
            PatchResult(statusCode = 0, sessionState = null, networkFailed = true)
        } catch (e: Exception) {
            logWarn("Playback session $action failed for $contentId", e)
            PatchResult(statusCode = 0, sessionState = null)
        }
    }

    private fun deleteTokenRemote(contentId: String, videoToken: String) {
        onTokenInvalidated(videoToken)
        val url = "https://www.crunchyroll.com/playback/v1/token/$contentId/$videoToken"
        val req = Request.Builder()
            .url(url)
            .delete()
            .build()
        runCatching {
            client.newCall(req).execute().use { r ->
                logDebug("Playback session DELETE contentId=$contentId -> HTTP ${r.code}")
            }
        }.onFailure { err ->
            logWarn("Playback session DELETE failed for $contentId", err)
        }
    }

    internal suspend fun awaitPendingForTesting() {
        runCatching { pendingCommandJob?.join() }
    }

    internal fun currentSessionSnapshot(): ActiveSession? = activeSession

    companion object {
        private const val TAG = "MiruRoll"
        private const val DEFAULT_INITIAL_KEEPALIVE_DELAY_MS = 5_000L
        private const val DEFAULT_MIN_HEARTBEAT_INTERVAL_MS = 30_000L
        private const val DEFAULT_MAX_HEARTBEAT_INTERVAL_MS = 260_000L
        private const val DEFAULT_FALLBACK_IDLE_PAUSE_MS = 45_000L
        private const val DEFAULT_FALLBACK_IDLE_DELETE_MS = 90_000L
        private const val DEFAULT_POLL_STEP_MS = 5_000L
    }
}
