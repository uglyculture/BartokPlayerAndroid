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

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var streamUrls: List<String> = emptyList()
    private var currentStreamIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectDelayMs = 3000L

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
                // Try next stream URL after a short delay
                currentStreamIndex = (currentStreamIndex + 1) % streamUrls.size
                handler.postDelayed({
                    if (player.playWhenReady) {
                        player.setMediaItem(createMediaItem(currentStreamIndex))
                        player.prepare()
                    }
                }, reconnectDelayMs)
            }
        })

        if (streamUrls.isNotEmpty()) {
            player.setMediaItem(createMediaItem(0))
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
        mediaSession?.run {
            player.release()
            release()
        }
        super.onDestroy()
    }

    private fun createMediaItem(index: Int): MediaItem {
        return MediaItem.Builder()
            .setUri(streamUrls[index])
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
