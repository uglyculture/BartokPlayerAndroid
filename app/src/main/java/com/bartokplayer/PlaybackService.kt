package com.bartokplayer

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var streamUrls: List<String> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val bufferingProgressCheckMs = 8000L      // re-check buffering progress this often
    private val minBufferProgressMs = 1000L           // need >=1s of new data per window, else treat as stalled
    private val maxRetryDurationMs = 60_000L          // stop the restart loop once recovery gap exceeds 60s
    private val offlineFallbackMs = 5 * 60_000L       // single fresh re-attempt long after giving up
    private val stabilityResetMs = 30_000L            // clear adaptive buffer escalation after this much stable playback
    private var retryStartTime = 0L
    private var bufferingTimeoutRunnable: Runnable? = null
    private var stabilityResetRunnable: Runnable? = null
    private var raceJob: Job? = null
    @Volatile private var inRetryState = false
    private var consecutiveRetryFailures = 0

    private lateinit var adaptiveLoadControl: AdaptiveLoadControl

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastPlaybackReadyTime = 0L
    private var playbackStartCount = 0

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        DebugLogger.init(this)
        DebugLogger.i("=== PlaybackService.onCreate ===")

        streamUrls = loadStreamUrls()
        DebugLogger.i("Loaded ${streamUrls.size} stream URLs:")
        streamUrls.forEachIndexed { i, url -> DebugLogger.i("  [$i] $url") }

        adaptiveLoadControl = AdaptiveLoadControl()

        val player = ExoPlayer.Builder(this)
            .setLoadControl(adaptiveLoadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                DebugLogger.e("onPlayerError: code=${error.errorCode}, " +
                        "codeName=${error.errorCodeName}, msg=${error.message}", error)
                error.cause?.let {
                    DebugLogger.e("  cause: ${it.javaClass.simpleName}: ${it.message}")
                }
                if (!player.playWhenReady || streamUrls.isEmpty()) {
                    DebugLogger.w("  Ignoring error: playWhenReady=${player.playWhenReady}, " +
                            "streams=${streamUrls.size}")
                    return
                }
                DebugLogger.i("  Triggering restartWithRace due to player error")
                restartWithRace(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = playbackStateName(playbackState)
                val bufferedMs = player.bufferedPosition - player.currentPosition
                DebugLogger.i("onPlaybackStateChanged: $stateName " +
                        "(buffered=${bufferedMs}ms, position=${player.currentPosition}ms, " +
                        "playWhenReady=${player.playWhenReady})")

                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        cancelStabilityReset()
                        scheduleBufferingCheck(player)
                    }
                    Player.STATE_READY -> {
                        cancelBufferingTimeout()
                        val now = System.currentTimeMillis()
                        if (lastPlaybackReadyTime > 0) {
                            val gap = now - lastPlaybackReadyTime
                            DebugLogger.i("  Time since last READY: ${gap}ms")
                        }
                        lastPlaybackReadyTime = now
                        playbackStartCount++
                        DebugLogger.i("  Playback ready (#$playbackStartCount). Retry timer reset.")
                        retryStartTime = 0L
                        inRetryState = false
                        consecutiveRetryFailures = 0
                        scheduleStabilityReset()
                    }
                    Player.STATE_ENDED -> {
                        DebugLogger.w("  Playback ENDED (live stream should not end)")
                    }
                    Player.STATE_IDLE -> {
                        DebugLogger.i("  Player IDLE")
                        cancelBufferingTimeout()
                    }
                    else -> cancelBufferingTimeout()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val reasonName = playWhenReadyReasonName(reason)
                DebugLogger.i("onPlayWhenReadyChanged: $playWhenReady, reason=$reasonName")
                if (!playWhenReady) {
                    adaptiveLoadControl.resetBuffer()
                    cancelBufferingTimeout()
                    cancelStabilityReset()
                    inRetryState = false
                    consecutiveRetryFailures = 0
                    retryStartTime = 0L
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                DebugLogger.i("onIsPlayingChanged: $isPlaying " +
                        "(state=${playbackStateName(player.playbackState)}, " +
                        "playWhenReady=${player.playWhenReady})")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                DebugLogger.i("onMediaItemTransition: uri=${mediaItem?.localConfiguration?.uri}, " +
                        "reason=$reason")
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                DebugLogger.d("onPositionDiscontinuity: reason=$reason, " +
                        "old=${oldPosition.positionMs}ms, new=${newPosition.positionMs}ms")
            }
        })

        if (streamUrls.isNotEmpty()) {
            player.setMediaItem(createMediaItem(streamUrls[0]))
            DebugLogger.i("Initial media item set: ${streamUrls[0]}")
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()

        registerNetworkCallback()
        logAudioFocusState()
        logBatteryOptimization()
        DebugLogger.i("=== PlaybackService.onCreate complete ===")
        DebugLogger.i("Log file: ${DebugLogger.getLogFilePath()}")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        DebugLogger.i("=== PlaybackService.onDestroy ===")
        DebugLogger.i("Total playback-ready count this session: $playbackStartCount")
        handler.removeCallbacksAndMessages(null)
        raceJob?.cancel()
        serviceScope.cancel()
        unregisterNetworkCallback()
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    private suspend fun raceStreamUrls(): String? {
        if (streamUrls.isEmpty()) return null
        DebugLogger.i("raceStreamUrls: probing ${streamUrls.size} URLs in parallel")

        val results = Array<Boolean>(streamUrls.size) { false }
        val timings = LongArray(streamUrls.size)

        withContext(Dispatchers.IO) {
            val jobs = streamUrls.mapIndexed { index, url ->
                launch {
                    val start = System.currentTimeMillis()
                    val reachable = probeStream(url)
                    val elapsed = System.currentTimeMillis() - start
                    results[index] = reachable
                    timings[index] = elapsed
                    DebugLogger.i("  probe[$index] ${if (reachable) "OK" else "FAIL"} " +
                            "in ${elapsed}ms: $url")
                }
            }
            jobs.forEach { it.join() }
        }

        for (i in results.indices) {
            if (results[i]) {
                DebugLogger.i("raceStreamUrls: winner = [$i] ${streamUrls[i]}")
                return streamUrls[i]
            }
        }
        DebugLogger.w("raceStreamUrls: ALL probes failed!")
        return null
    }

    private fun probeStream(url: String): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Range", "bytes=0-1")
            try {
                connection.connect()
                val code = connection.responseCode
                val stream = connection.inputStream
                val byte = stream.read()
                stream.close()
                val ok = code in 200..399 && byte >= 0
                if (!ok) {
                    DebugLogger.d("  probe detail: code=$code, byte=$byte for $url")
                }
                ok
            } finally {
                connection.disconnect()
            }
        } catch (ex: Exception) {
            DebugLogger.d("  probe exception for $url: ${ex.javaClass.simpleName}: ${ex.message}")
            false
        }
    }

    private fun restartWithRace(player: Player) {
        if (streamUrls.isEmpty()) return

        inRetryState = true
        cancelStabilityReset()

        val now = System.currentTimeMillis()
        if (retryStartTime == 0L) {
            retryStartTime = now
            DebugLogger.i("restartWithRace: first retry attempt started")
        } else {
            val elapsed = now - retryStartTime
            DebugLogger.i("restartWithRace: retry elapsed=${elapsed}ms / max=${maxRetryDurationMs}ms")
            if (elapsed > maxRetryDurationMs) {
                // Recovery gap exceeded 60s: stop hammering the stream with restarts. Halt playback
                // but keep playWhenReady=true so onAvailable() resumes the moment the network returns;
                // also schedule one delayed fresh attempt as a fallback.
                DebugLogger.w("restartWithRace: recovery gap exceeded ${maxRetryDurationMs}ms — " +
                        "stopping restart loop. Will resume on network return or in ${offlineFallbackMs}ms.")
                cancelBufferingTimeout()
                retryStartTime = 0L
                player.stop()
                handler.postDelayed({
                    if (player.playWhenReady && inRetryState) restartWithRace(player)
                }, offlineFallbackMs)
                return
            }
        }

        adaptiveLoadControl.onStreamBreak()

        cancelBufferingTimeout()
        raceJob?.cancel()
        raceJob = serviceScope.launch {
            DebugLogger.i("restartWithRace: launching race with 10s timeout")
            val raceStart = System.currentTimeMillis()
            val bestUrl = withTimeoutOrNull(10_000L) { raceStreamUrls() }
            val raceElapsed = System.currentTimeMillis() - raceStart
            DebugLogger.i("restartWithRace: race completed in ${raceElapsed}ms, " +
                    "result=${bestUrl ?: "NONE"}")

            if (bestUrl != null && player.playWhenReady) {
                consecutiveRetryFailures = 0
                DebugLogger.i("restartWithRace: switching to $bestUrl")
                player.stop()
                player.setMediaItem(createMediaItem(bestUrl))
                player.prepare()
                player.play()
            } else if (player.playWhenReady) {
                consecutiveRetryFailures++
                val backoff = min(3000L * consecutiveRetryFailures, 15_000L)
                DebugLogger.w("restartWithRace: no URL available (failure #$consecutiveRetryFailures), " +
                        "scheduling retry in ${backoff}ms")
                handler.postDelayed({
                    if (player.playWhenReady) restartWithRace(player)
                }, backoff)
            }
        }
    }

    private fun cancelBufferingTimeout() {
        if (bufferingTimeoutRunnable != null) {
            DebugLogger.d("cancelBufferingTimeout: clearing pending timeout")
        }
        bufferingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
    }

    /**
     * Detects a genuinely stalled download instead of blindly restarting after a fixed timeout.
     * While buffering, if the buffer is still growing (data is arriving) we keep waiting — slow
     * networks recover on their own. We only restart when the buffer fails to advance, which means
     * the connection is actually dead. This avoids tearing down a healthy-but-slow stream.
     */
    private fun scheduleBufferingCheck(player: Player) {
        cancelBufferingTimeout()
        val startBuffered = player.totalBufferedDuration
        bufferingTimeoutRunnable = Runnable {
            if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                val nowBuffered = player.totalBufferedDuration
                val progress = nowBuffered - startBuffered
                if (progress >= minBufferProgressMs) {
                    DebugLogger.i("  Still buffering but data is arriving (+${progress}ms, " +
                            "buffered=${nowBuffered}ms) — continuing to wait.")
                    scheduleBufferingCheck(player)
                } else {
                    DebugLogger.w("  Buffering stalled: only +${progress}ms over " +
                            "${bufferingProgressCheckMs}ms (buffered=${nowBuffered}ms). " +
                            "Triggering restartWithRace.")
                    restartWithRace(player)
                }
            } else {
                DebugLogger.d("  Buffering check fired but state resolved: " +
                        "${playbackStateName(player.playbackState)}")
            }
        }.also { handler.postDelayed(it, bufferingProgressCheckMs) }
    }

    /**
     * After playback has been stable for [stabilityResetMs], clear the adaptive buffer escalation so
     * a few isolated dropouts hours apart don't permanently inflate the required start buffer.
     */
    private fun scheduleStabilityReset() {
        cancelStabilityReset()
        stabilityResetRunnable = Runnable {
            adaptiveLoadControl.resetBuffer()
        }.also { handler.postDelayed(it, stabilityResetMs) }
    }

    private fun cancelStabilityReset() {
        stabilityResetRunnable?.let { handler.removeCallbacks(it) }
        stabilityResetRunnable = null
    }

    private fun createMediaItem(url: String): MediaItem {
        return MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Bartók Rádió")
                    .setArtist("Magyar Rádió")
                    .build()
            )
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .build()
            )
            .build()
    }

    private fun loadStreamUrls(): List<String> {
        return try {
            assets.open("streams.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.trimStart().startsWith('#') }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    DebugLogger.i("NETWORK: available (${describeNetwork(cm, network)})")
                    if (inRetryState) {
                        handler.post {
                            val p = mediaSession?.player ?: return@post
                            if (inRetryState && p.playWhenReady) {
                                DebugLogger.i("NETWORK: restored during retry — triggering fresh restartWithRace")
                                retryStartTime = 0L
                                consecutiveRetryFailures = 0
                                restartWithRace(p)
                            }
                        }
                    }
                }
                override fun onLost(network: Network) {
                    DebugLogger.w("NETWORK: lost")
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val downKbps = caps.linkDownstreamBandwidthKbps
                    val upKbps = caps.linkUpstreamBandwidthKbps
                    DebugLogger.d("NETWORK: capabilities changed — down=${downKbps}kbps, up=${upKbps}kbps, " +
                            "wifi=${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}, " +
                            "cell=${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}")
                }
            }.also { cm.registerNetworkCallback(request, it) }
            // Log current state
            val active = cm.activeNetwork
            if (active != null) {
                DebugLogger.i("NETWORK: current = ${describeNetwork(cm, active)}")
            } else {
                DebugLogger.w("NETWORK: no active network!")
            }
        } catch (ex: Exception) {
            DebugLogger.e("Failed to register network callback", ex)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
    }

    private fun describeNetwork(cm: ConnectivityManager, network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        return "$type (down=${caps.linkDownstreamBandwidthKbps}kbps)"
    }

    @Suppress("DEPRECATION")
    private fun logAudioFocusState() {
        try {
            val am = getSystemService(AudioManager::class.java)
            DebugLogger.i("AUDIO: mode=${am.mode}, " +
                    "musicActive=${am.isMusicActive}, " +
                    "speakerOn=${am.isSpeakerphoneOn}, " +
                    "btScoOn=${am.isBluetoothScoOn}")
        } catch (ex: Exception) {
            DebugLogger.e("Failed to log audio state", ex)
        }
    }

    private fun logBatteryOptimization() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            val ignoringOptimizations = pm.isIgnoringBatteryOptimizations(packageName)
            DebugLogger.i("BATTERY: ignoringOptimizations=$ignoringOptimizations")
            if (!ignoringOptimizations) {
                DebugLogger.w("BATTERY: App is NOT exempt from battery optimization — " +
                        "this may cause playback stops in background!")
            }
        } catch (ex: Exception) {
            DebugLogger.e("Failed to check battery optimization", ex)
        }
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private fun playWhenReadyReasonName(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
        else -> "UNKNOWN($reason)"
    }

    /**
     * Extends DefaultLoadControl to make the playback start buffer adaptive.
     * Each stream break increases the required buffer before playback starts by 2 seconds (so the
     * player builds a larger cushion after repeated interruptions), capped at [MAX_START_BUFFER_US]
     * so the requirement always stays reachable. Resets on manual stop and after a stretch of stable
     * playback (see scheduleStabilityReset), so isolated dropouts don't permanently inflate it.
     */
    @UnstableApi
    private class AdaptiveLoadControl : DefaultLoadControl(
        defaultAllocator,
        DEFAULT_MIN_BUFFER_MS,
        DEFAULT_MAX_BUFFER_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        DEFAULT_TARGET_BUFFER_BYTES,
        DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS,
        DEFAULT_BACK_BUFFER_DURATION_MS,
        DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME
    ) {
        companion object {
            private val defaultAllocator =
                androidx.media3.exoplayer.upstream.DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
            private const val STEP_US = 2_000_000L // +2 seconds per break
            private val INITIAL_PLAYBACK_US = Util.msToUs(DEFAULT_BUFFER_FOR_PLAYBACK_MS.toLong())
            private val INITIAL_REBUFFER_US = Util.msToUs(DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS.toLong())
            // Cap the adaptive *start* buffer low enough that it can always be filled within a couple
            // of buffering-progress windows. The old cap (the 50s min-buffer) let the required start
            // buffer climb past what could ever be buffered before the stall check fired, producing an
            // unrecoverable restart loop.
            private val MAX_START_BUFFER_US = Util.msToUs(10_000L)
        }

        @Volatile
        private var breakCount = 0

        fun onStreamBreak() {
            breakCount++
            val cappedUs = min(INITIAL_PLAYBACK_US + breakCount * STEP_US, MAX_START_BUFFER_US)
            DebugLogger.i("ADAPTIVE: Stream break #$breakCount — " +
                    "playback start buffer now: ${cappedUs / 1_000_000.0}s " +
                    "(cap ${MAX_START_BUFFER_US / 1_000_000}s)")
        }

        fun resetBuffer() {
            if (breakCount > 0) {
                DebugLogger.i("ADAPTIVE: buffer reset (was break #$breakCount)")
                breakCount = 0
            }
        }

        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
            val bufferedDurationUs = Util.getPlayoutDurationForMediaDuration(
                parameters.bufferedDurationUs, parameters.playbackSpeed
            )
            val adaptivePlaybackUs = min(INITIAL_PLAYBACK_US + breakCount * STEP_US, MAX_START_BUFFER_US)
            val adaptiveRebufferUs = min(INITIAL_REBUFFER_US + breakCount * STEP_US, MAX_START_BUFFER_US)
            var minBufferDurationUs = if (parameters.rebuffering) adaptiveRebufferUs else adaptivePlaybackUs
            if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
                minBufferDurationUs = min(parameters.targetLiveOffsetUs / 2, minBufferDurationUs)
            }
            val shouldStart = minBufferDurationUs <= 0 || bufferedDurationUs >= minBufferDurationUs
            if (parameters.rebuffering || !shouldStart) {
                DebugLogger.d("shouldStartPlayback: buffered=${bufferedDurationUs / 1000}ms, " +
                        "required=${minBufferDurationUs / 1000}ms, rebuffering=${parameters.rebuffering}, " +
                        "start=$shouldStart, breaks=$breakCount")
            }
            return shouldStart
        }
    }
}
