package com.bartokplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var streamUrls: List<String> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val bufferingTimeoutMs = 8000L
    private val maxRetryDurationMs = 120_000L
    private var retryStartTime = 0L
    private var bufferingTimeoutRunnable: Runnable? = null
    private var raceJob: Job? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        streamUrls = loadStreamUrls()

        val player = ExoPlayer.Builder(this)
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
                if (!player.playWhenReady || streamUrls.isEmpty()) return
                restartWithRace(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        cancelBufferingTimeout()
                        bufferingTimeoutRunnable = Runnable {
                            if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                                restartWithRace(player)
                            }
                        }.also {
                            handler.postDelayed(it, bufferingTimeoutMs)
                        }
                    }
                    Player.STATE_READY -> {
                        cancelBufferingTimeout()
                        retryStartTime = 0L
                    }
                    else -> cancelBufferingTimeout()
                }
            }
        })

        if (streamUrls.isNotEmpty()) {
            player.setMediaItem(createMediaItem(streamUrls[0]))
        }

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        raceJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    /**
     * Race all stream URLs in parallel. The first reachable URL wins,
     * preferring earlier entries in the list (higher priority).
     * Returns the best available URL, or null if none respond.
     */
    private suspend fun raceStreamUrls(): String? {
        if (streamUrls.isEmpty()) return null

        // Probe all URLs in parallel
        val results = Array<Boolean>(streamUrls.size) { false }

        withContext(Dispatchers.IO) {
            val jobs = streamUrls.mapIndexed { index, url ->
                launch {
                    val reachable = probeStream(url)
                    results[index] = reachable
                }
            }
            // Wait for all probes (they each have their own timeout)
            jobs.forEach { it.join() }
        }

        // Return the first reachable URL (list order = priority)
        for (i in results.indices) {
            if (results[i]) return streamUrls[i]
        }
        return null
    }

    /**
     * Probe a stream URL with a short timeout to check if it's reachable.
     */
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
                // Read a tiny bit to confirm data flows
                val stream = connection.inputStream
                val byte = stream.read()
                stream.close()
                code in 200..399 && byte >= 0
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun restartWithRace(player: Player) {
        if (streamUrls.isEmpty()) return

        val now = System.currentTimeMillis()
        if (retryStartTime == 0L) {
            retryStartTime = now
        } else if (now - retryStartTime > maxRetryDurationMs) {
            cancelBufferingTimeout()
            retryStartTime = 0L
            player.stop()
            player.playWhenReady = false
            return
        }

        cancelBufferingTimeout()
        raceJob?.cancel()
        raceJob = serviceScope.launch {
            val bestUrl = withTimeoutOrNull(10_000L) { raceStreamUrls() }
            if (bestUrl != null && player.playWhenReady) {
                player.stop()
                player.setMediaItem(createMediaItem(bestUrl))
                player.prepare()
                player.play()
            } else if (player.playWhenReady) {
                // No URL responded — retry after a delay
                handler.postDelayed({
                    if (player.playWhenReady) restartWithRace(player)
                }, 3000L)
            }
        }
    }

    private fun cancelBufferingTimeout() {
        bufferingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
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
}
