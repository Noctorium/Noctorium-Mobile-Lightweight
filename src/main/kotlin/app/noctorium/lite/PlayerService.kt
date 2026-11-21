package app.noctorium.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

/**
 * What keeps the music going with the screen off: a foreground service with the media notification, and a media
 * session for the lock screen, headphones, a watch or the car. Both are Android's own, with no library.
 */
class PlayerService : Service(), Player.Listener {
    private lateinit var session: MediaSession
    private var cover: Bitmap? = null
    private var coverFor: String? = null

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Player.pause()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Player.init(this)
        session = MediaSession(this, "Noctorium Lite").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Player.toggle()
                override fun onPause() = Player.pause()
                override fun onSkipToNext() = Player.next()
                override fun onSkipToPrevious() = Player.previous()
                override fun onSeekTo(pos: Long) = Player.seek(pos.toInt())
                override fun onStop() { Player.stop(); stopSelf() }
            })
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, "Playing", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        Player.listen(this)
        show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Player.toggle()
            ACTION_NEXT -> Player.next()
            ACTION_PREVIOUS -> Player.previous()
            ACTION_STOP -> { Player.stop(); stopSelf() }
        }
        show()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Player.forget(this)
        runCatching { unregisterReceiver(noisy) }
        session.release()
        super.onDestroy()
    }

    override fun changed() = show()

    private fun show() {
        val track = Player.current ?: return
        val state = when {
            Player.loading -> PlaybackState.STATE_BUFFERING
            Player.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, Player.positionMs.toLong(), if (Player.playing) 1f else 0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP,
                ).build(),
        )
        if (coverFor != track.artworkUrl) {
            coverFor = track.artworkUrl
            cover = null
            Images.load(track.artworkUrl, 512) { bitmap -> if (coverFor == track.artworkUrl) { cover = bitmap; show() } }
        }
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Player.durationMs.toLong())
                .apply { cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )

        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track.title)
            .setContentText(if (Player.error != null) Player.error else track.artist)
            .setLargeIcon(cover)
            .setContentIntent(open)
            .setDeleteIntent(action(ACTION_STOP))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(Player.playing || Player.loading)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", action(ACTION_PREVIOUS)).build())
            .addAction(
                Notification.Action.Builder(
                    if (Player.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (Player.playing) "Pause" else "Play", action(ACTION_TOGGLE),
                ).build(),
            )
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        val notification = builder.build()
        if (Player.playing || Player.loading) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, notification)
        } else {
            // Paused: the notification stays to pick it up again, and Android may let the service go.
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification)
        }
    }

    private fun action(name: String): PendingIntent =
        PendingIntent.getService(this, name.hashCode(), Intent(this, PlayerService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)

    private companion object {
        const val CHANNEL = "playing"
        const val ID = 1
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_NEXT = "next"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_STOP = "stop"
    }
}
