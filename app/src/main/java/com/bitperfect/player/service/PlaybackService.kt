package com.bitperfect.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.bitperfect.player.MainActivity
import com.bitperfect.player.audio.BitPerfectPlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "bitperfect_playback_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY_PAUSE = "com.bitperfect.player.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.bitperfect.player.ACTION_NEXT"
        const val ACTION_PREV = "com.bitperfect.player.ACTION_PREV"
    }

    inner class LocalBinder : Binder() {
        fun getEngine(): BitPerfectPlayerEngine = engine
    }

    private val binder = LocalBinder()
    lateinit var engine: BitPerfectPlayerEngine
        private set

    private lateinit var mediaSession: MediaSessionCompat
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        engine = BitPerfectPlayerEngine(this)
        engine.initialize()

        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "BitPerfectMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = engine.togglePlayPause()
                override fun onPause() = engine.togglePlayPause()
                override fun onSkipToNext() = engine.nextTrack()
                override fun onSkipToPrevious() = engine.previousTrack()
                override fun onSeekTo(pos: Long) = engine.seekTo(pos)
            })
            isActive = true
        }

        serviceScope.launch {
            combine(
                engine.currentTrack,
                engine.isPlaying,
                engine.signalPath
            ) { track, playing, signal ->
                Triple(track, playing, signal)
            }.collect { (track, playing, signal) ->
                if (track != null) {
                    updateMediaSession(track.title, track.artist, track.album, track.durationMs, playing)
                    val notification = buildNotification(
                        title = track.title,
                        subtitle = "${track.artist} • ${signal.status.badgeText}",
                        isPlaying = playing
                    )
                    if (playing) {
                        startForeground(NOTIFICATION_ID, notification)
                    } else {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, notification)
                        stopForeground(STOP_FOREGROUND_DETACH)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> engine.togglePlayPause()
            ACTION_NEXT -> engine.nextTrack()
            ACTION_PREV -> engine.previousTrack()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.release()
        engine.release()
        super.onDestroy()
    }

    private fun updateMediaSession(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                engine.positionMs.value,
                1.0f
            )
            .build()
        mediaSession.setPlaybackState(state)

        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            .build()
        mediaSession.setMetadata(meta)
    }

    private fun buildNotification(title: String, subtitle: String, isPlaying: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = serviceActionIntent(ACTION_PREV, 1)
        val playPauseIntent = serviceActionIntent(ACTION_PLAY_PAUSE, 2)
        val nextIntent = serviceActionIntent(ACTION_NEXT, 3)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openAppIntent)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pausar" else "Reproducir",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Siguiente", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reproducción Bit-Perfect",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles de reproducción de audio Hi-Res"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
