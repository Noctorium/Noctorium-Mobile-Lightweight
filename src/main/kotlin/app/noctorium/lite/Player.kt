package app.noctorium.lite

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executors

/**
 * The one player: a queue, the song playing in it, and Android's own MediaPlayer.
 *
 * Kept here rather than in the service so the screens can ask it things at any time; [PlayerService] is only what
 * keeps the app alive in the background and shows the notification while something plays. Everything here runs
 * on the main thread except finding an address, which is done on [work].
 */
object Player {
    interface Listener { fun changed() }

    var queue: MutableList<Track> = mutableListOf(); private set
    var index = -1; private set
    var playing = false; private set
    var loading = false; private set
    var error: String? = null; private set
    /** When the queue runs out, more like the last song, from its own service's radio. */
    var autoplay = true

    val current: Track? get() = queue.getOrNull(index)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    val work = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<Listener>()
    private var media: MediaPlayer? = null
    private var prepared = false
    /** Which load is current, so an address found for a song somebody has already skipped is dropped. */
    private var generation = 0

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun listen(l: Listener) { listeners += l }
    fun forget(l: Listener) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it.changed() }

    val positionMs: Int get() = if (prepared) runCatching { media?.currentPosition ?: 0 }.getOrDefault(0) else 0
    val durationMs: Int get() = if (prepared) runCatching { media?.duration ?: 0 }.getOrDefault(0) else (current?.durationMs?.toInt() ?: 0)

    fun play(tracks: List<Track>, start: Int) {
        if (tracks.isEmpty()) return
        queue = tracks.toMutableList()
        index = start.coerceIn(0, tracks.size - 1)
        load()
    }

    fun playNext(track: Track) {
        if (current == null) return play(listOf(track), 0)
        queue.add(index + 1, track)
        changed()
    }

    fun addToQueue(track: Track) {
        if (current == null) return play(listOf(track), 0)
        queue.add(track)
        changed()
    }

    fun jump(to: Int) {
        if (to !in queue.indices) return
        index = to
        load()
    }

    fun toggle() {
        val m = media
        when {
            current == null -> return
            !prepared -> if (!loading) load()
            m != null && m.isPlaying -> { m.pause(); playing = false }
            m != null -> { if (focus()) { m.start(); playing = true } }
        }
        changed()
    }

    fun pause() {
        media?.takeIf { prepared && it.isPlaying }?.pause()
        playing = false
        changed()
    }

    fun next() {
        if (index + 1 < queue.size) { index++; load() } else extendThen { if (index + 1 < queue.size) { index++; load() } }
    }

    /** Back to the start of the song once it is a few seconds in, as every player does; before that, the song before. */
    fun previous() {
        if (positionMs > 5_000 || index == 0) seek(0) else { index--; load() }
    }

    fun seek(ms: Int) {
        if (prepared) media?.seekTo(ms)
        changed()
    }

    fun stop() {
        generation++
        media?.release(); media = null; prepared = false
        playing = false; loading = false
        changed()
    }

    private fun load() {
        val track = current ?: return
        val mine = ++generation
        media?.release(); media = null; prepared = false
        loading = true; playing = false; error = null
        changed()
        startService()
        Store.played(track)
        work.execute {
            val found = runCatching { address(track) }
            main.post {
                if (mine != generation) return@post
                found.onSuccess { (url, headers) -> android.util.Log.i("NoctoriumLite", "Playing ${track.key} from ${url.take(90)}"); start(url, headers, mine) }
                    .onFailure { failed(it.message ?: "This song would not play") }
            }
        }
    }

    /** Where [track]'s audio is, and the headers to fetch it with. Called off the main thread. */
    private fun address(track: Track): Pair<String, Map<String, String>> {
        if (track.isYouTube) return YouTube.stream(track.id).let { it.url to mapOf("User-Agent" to it.userAgent) }
        // SoundCloud's plain MP3 when it has one. Many tracks now come only as an AAC stream Android's own player
        // cannot read, and some are locked to SoundCloud's app: those play as the same recording on YouTube Music,
        // as the full Noctorium does, rather than being skipped.
        val soundCloud = runCatching { Api.soundCloudStream(track.id) }.getOrNull()
        if (soundCloud != null && !soundCloud.contains(".m3u8")) return soundCloud to emptyMap()
        // The closest few, tried in turn: one may be a video YouTube will not play here.
        for (match in sameOnYouTubeMusic(track)) {
            runCatching { YouTube.stream(match.id) }.getOrNull()?.let { return it.url to mapOf("User-Agent" to it.userAgent) }
        }
        throw java.io.IOException("SoundCloud would not play this, and YouTube Music does not have it")
    }

    /** The same recording on YouTube Music: its title and artist searched for, the closest in length first. */
    private fun sameOnYouTubeMusic(track: Track): List<Track> {
        val words = (track.title + " " + track.artist).replace(Regex("[\\[\\](){}]"), " ").trim()
        val found = Api.search(words, "YOUTUBE_MUSIC").first.filter { it.isYouTube }
        if (track.durationMs <= 0) return found.take(3)
        return found.filter { kotlin.math.abs(it.durationMs - track.durationMs) < 10_000 }
            .sortedBy { kotlin.math.abs(it.durationMs - track.durationMs) }.take(3)
    }

    private fun start(url: String, headers: Map<String, String>, mine: Int) {
        val m = MediaPlayer()
        media = m
        m.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
        if (Build.VERSION.SDK_INT >= 21) {
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        }
        m.setOnPreparedListener {
            if (mine != generation) return@setOnPreparedListener
            prepared = true; loading = false
            if (focus()) { it.start(); playing = true }
            changed()
        }
        m.setOnCompletionListener { if (mine == generation) next() }
        m.setOnErrorListener { _, what, extra ->
            if (mine == generation) failed("This song would not play ($what, $extra)")
            true
        }
        try {
            m.setDataSource(app, Uri.parse(url), headers)
            m.prepareAsync()
        } catch (e: Exception) {
            failed(e.message ?: "This song would not play")
        }
    }

    /** A song that will not play is said so and skipped, rather than stopping the music. */
    private fun failed(why: String) {
        android.util.Log.w("NoctoriumLite", "Could not play ${current?.key}: $why")
        loading = false; playing = false; error = why
        changed()
        if (index + 1 < queue.size) main.postDelayed({ if (error == why) next() }, 2_500)
    }

    /** Lines up more songs like the last one, then runs [then]. */
    private fun extendThen(then: () -> Unit) {
        val last = queue.lastOrNull() ?: return
        if (!autoplay) { pause(); return }
        loading = true; changed()
        work.execute {
            val more = runCatching { Api.radio(last) }.getOrDefault(emptyList())
            main.post {
                loading = false
                val known = queue.map { it.key }.toSet()
                queue.addAll(more.filter { it.key !in known }.take(25))
                changed()
                then()
            }
        }
    }

    // --- Sharing the speaker with other apps ---

    private val audio by lazy { app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
    }

    @Suppress("DEPRECATION")
    private fun focus(): Boolean =
        audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun startService() {
        val intent = Intent(app, PlayerService::class.java)
        runCatching { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent) }
    }
}
