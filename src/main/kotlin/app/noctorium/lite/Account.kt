package app.noctorium.lite

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Last.fm's rule, which ListenBrainz follows: songs over half a minute, once half or four minutes are heard. */
fun scrobbleCounts(heardMs: Long, durationMs: Long): Boolean = durationMs > 30_000 && heardMs >= minOf(durationMs / 2, 240_000)

/** Whether a listen counts, as every Noctorium counts it: half a minute, or half the song if it is shorter. */
fun listenCounts(heardMs: Long, durationMs: Long): Boolean = heardMs >= (if (durationMs > 0) minOf(durationMs / 2, 30_000) else 30_000)

/**
 * The Noctorium account: signed in from the browser with a code -- Discord, Telegram or email, on the account
 * service's own page -- as every Noctorium signs in. Once signed in, what is played here is counted, and what is
 * playing is said, for the listener's Discord status and Telegram bio.
 */
object Account : Player.Listener {
    interface Watcher { fun accountChanged() }

    // Signing in and the account, and listening: two servers since 0.39, chosen by the path asked.
    private const val ACCOUNT = "https://account.noctorium.xyz"
    private const val STATS = "https://stats.noctorium.xyz"
    private fun serverFor(path: String) =
        if (listOf("/api/stats", "/api/plays", "/api/now-playing", "/api/scrobble").any { path == it || path.startsWith("$it?") }) STATS else ACCOUNT
    private const val SETTLE_MS = 3_000L
    private const val RENEW_MS = 5 * 60_000L

    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val watchers = mutableSetOf<Watcher>()

    val signedIn: Boolean get() = token != null
    val name: String get() = prefs.getString("name", null).orEmpty()
    private val token: String? get() = prefs.getString("token", null)
    val avatarUrl: String? get() = prefs.getString("avatar", null)
    var email = ""; private set
    // What the account screen asks for and offers: proofs for a change, and the pictures it can take.
    var hasPassword = false; private set
    var totp = false; private set
    var telegramCodes = false; private set
    var discordLinked = false; private set
    var telegramLinked = false; private set
    var streams = 0; private set
    var code: String? = null; private set
    var url: String? = null; private set
    var message: String? = null; private set
    private var attempt = 0

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)
        Player.listen(this)
        if (signedIn) refresh()
    }

    fun watch(w: Watcher) { watchers += w }
    fun forget(w: Watcher) { watchers -= w }
    private fun tell() = main.post { watchers.toList().forEach { it.accountChanged() } }

    /** One call to the service: its status and what it said, or null when it could not be reached. */
    private fun call(path: String, body: String? = null, jpeg: ByteArray? = null): Pair<Int, JSONObject>? = runCatching {
        val headers = mutableMapOf("Accept" to "application/json")
        token?.let { headers["Authorization"] = "Bearer $it" }
        if (jpeg != null) headers["Content-Type"] = "image/jpeg"
        Http.send("${serverFor(path)}$path", jpeg ?: body?.toByteArray(), headers)
    }.getOrNull()

    private fun refresh() = Thread {
        val me = call("/api/auth/me")
        if (me?.first == 401) {
            main.post { signOut("Your Noctorium sign-in ran out. Sign in again.") }
            return@Thread
        }
        me?.takeIf { it.first in 200..299 }?.second?.optJSONObject("user")?.let { user ->
            prefs.edit().putString("name", user.optString("displayName"))
                .putString("avatar", if (user.isNull("avatarUrl")) null else user.optString("avatarUrl")).apply()
            email = user.optString("email")
            hasPassword = user.optBoolean("hasPassword")
            totp = user.optJSONObject("twoStep")?.optBoolean("totp") == true
            telegramCodes = user.optJSONObject("twoStep")?.optBoolean("telegram") == true
            discordLinked = user.optJSONObject("linked")?.optBoolean("discord") == true
            telegramLinked = user.optJSONObject("linked")?.optBoolean("telegram") == true
        }
        call("/api/stats?range=all")?.takeIf { it.first in 200..299 }?.let { streams = it.second.optInt("streams") }
        tell()
    }.start()

    /** Signs in with a code: [open] shows the service's page, where the listener signs in and presses Allow. */
    fun signIn(open: (String) -> Unit) {
        if (signedIn || code != null) return
        val mine = ++attempt
        message = null
        Thread {
            val start = call("/api/auth/device", "{}")?.takeIf { it.first in 200..299 }?.second
            if (start == null || start.optString("code").isEmpty()) {
                message = "The Noctorium service could not be reached. Try again."
                tell()
                return@Thread
            }
            code = start.getString("code")
            url = start.getString("url")
            tell()
            main.post { url?.let(open) }
            val until = SystemClock.elapsedRealtime() + start.optLong("expiresIn", 600) * 1000
            val every = start.optLong("interval", 3).coerceIn(1, 30) * 1000
            val poll = "/api/auth/device/poll?token=" + URLEncoder.encode(start.getString("pollToken"), "UTF-8")
            while (SystemClock.elapsedRealtime() < until) {
                Thread.sleep(every)
                if (mine != attempt) return@Thread
                val (status, said) = call(poll) ?: continue
                if (status == 202 || status >= 500) continue
                code = null; url = null
                val user = said.optJSONObject("user")
                if (status in 200..299 && said.optString("token").isNotEmpty() && user != null) {
                    prefs.edit().putString("token", said.getString("token")).putString("name", user.optString("displayName")).apply()
                    tell()
                    refresh()
                    return@Thread
                }
                message = said.optString("error").ifEmpty { "That sign-in did not go through. Start again." }
                tell()
                return@Thread
            }
            if (mine == attempt) { code = null; url = null; message = "The code expired. Start again for a new one."; tell() }
        }.start()
    }

    fun cancel() {
        attempt++
        code = null; url = null
        tell()
    }

    fun signOut(why: String? = null) {
        val had = token
        cancel()
        if (had != null) Thread { runCatching { Http.status("$STATS/api/now-playing", """{"track":null}""", mapOf("Authorization" to "Bearer $had")) } }.start()
        prefs.edit().remove("token").remove("name").remove("avatar").apply()
        streams = 0; email = ""
        hasPassword = false; totp = false; telegramCodes = false; discordLinked = false; telegramLinked = false
        message = why
        reported = ""
        tell()
    }

    // --- Changing the account, as its page on the service does ---

    /**
     * One change of the profile -- `name`, `email`, `password`, `delete`, `avatar-discord`, `avatar-telegram` or
     * `avatar-remove` -- with its [fields]; [done] gets what went wrong, or null.
     */
    fun change(action: String, fields: Map<String, String> = emptyMap(), done: (String?) -> Unit) {
        val body = JSONObject().put("action", action)
        fields.forEach { (k, v) -> body.put(k, v) }
        send({ call("/api/account/profile", body.toString()) }, done) { said ->
            // A new email or password signs every other device out; this one is handed a fresh token to stay in.
            said.optString("token").takeIf { it.isNotEmpty() }?.let { prefs.edit().putString("token", it).apply() }
            if (action == "delete") main.post { signOut("Your Noctorium account is deleted.") } else refresh()
        }
    }

    /** A new picture: a small square JPEG. */
    fun upload(jpeg: ByteArray, done: (String?) -> Unit) = send({ call("/api/account/avatar", jpeg = jpeg) }, done) { refresh() }

    /** Asks the Telegram bot for a two-step code. */
    fun telegramCode(done: (String?) -> Unit) = send({ call("/api/account/security", """{"action":"telegram-send"}""") }, done) {}

    private fun send(request: () -> Pair<Int, JSONObject>?, done: (String?) -> Unit, then: (JSONObject) -> Unit) = Thread {
        val answer = request()
        val error = when {
            answer == null -> "The Noctorium service could not be reached. Try again."
            answer.first in 200..299 -> { then(answer.second); null }
            else -> answer.second.optString("error").ifEmpty { "That did not go through (${answer.first})." }
        }
        main.post { done(error) }
    }.start()

    // --- What is played: counted once it counts, and said while it plays ---

    private var heardTrack: Track? = null
    private var heardMs = 0L
    private var playingSince = 0L
    private var startedAt = 0L
    private var counted = false
    private var scrobbled = false
    private var reported = ""

    override fun changed() {
        val now = SystemClock.elapsedRealtime()
        if (playingSince > 0) heardMs += now - playingSince
        playingSince = 0
        val track = Player.current
        if (track?.key != heardTrack?.key) {
            countIfDue()
            scrobbleIfDue()
            heardTrack = track; heardMs = 0; counted = false; scrobbled = false; startedAt = System.currentTimeMillis()
        }
        if (Player.playing) playingSince = now
        countIfDue()
        scrobbleIfDue()
        nowPlaying()
    }

    // shortcut: a listen is counted when the player next changes (pause, skip, song end), not the moment it passes
    // half a minute; one in progress when the app is killed is lost. Upgrade with a timer if that matters.
    private fun countIfDue() {
        val track = heardTrack ?: return
        if (counted || !signedIn || !listenCounts(heardMs, track.durationMs)) return
        counted = true
        val play = JSONObject()
            .put("clientId", UUID.randomUUID().toString()).put("provider", track.provider).put("trackId", track.id)
            .put("title", track.title).put("artist", track.artist).put("msPlayed", heardMs)
            .put("playedAt", iso(startedAt))
        val body = JSONObject().put("plays", org.json.JSONArray().put(play)).toString()
        Thread { if ((call("/api/plays", body)?.first ?: 0) in 200..299) refresh() }.start()
    }

    /** A listen that meets Last.fm's rule, scrobbled by the service to the Last.fm and ListenBrainz linked to the account. */
    private fun scrobbleIfDue() {
        val track = heardTrack ?: return
        val duration = track.durationMs.takeIf { it > 0 } ?: Player.durationMs.toLong()
        if (scrobbled || !signedIn || !scrobbleCounts(heardMs, duration)) return
        scrobbled = true
        val body = JSONObject()
            .put("track", JSONObject().put("title", track.title).put("artist", track.artist).put("url", track.sourceUrl).put("durationMs", duration))
            .put("startedAt", startedAt / 1000).put("heardMs", heardMs).toString()
        Thread { call("/api/scrobble", body) }.start()
    }

    /** java.time is Android 8 and later; Lite runs from Android 5. */
    private fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private val sendNowPlaying = object : Runnable {
        override fun run() {
            val track = Player.current?.takeIf { Player.playing }
            reported = track?.key.orEmpty()
            // relay: the service also says it as "now playing" on the Last.fm and ListenBrainz linked to the account.
            val body = JSONObject().put("relay", true).put("track", track?.let {
                JSONObject().put("title", it.title).put("artist", it.artist).put("artworkUrl", it.artworkUrl).put("url", it.sourceUrl)
                    .put("provider", it.provider).put("durationMs", Player.durationMs.toLong()).put("positionMs", Player.positionMs.toLong())
            } ?: JSONObject.NULL).toString()
            if (signedIn) Thread { call("/api/now-playing", body) }.start()
            // Discord's status lasts only so long: said again every five minutes while the music plays.
            if (track != null) main.postDelayed(this, RENEW_MS)
        }
    }

    /** What is playing, once it has settled for a few seconds: skipping through songs is not worth a status each. */
    private fun nowPlaying() {
        if (!signedIn) return
        val key = Player.current?.takeIf { Player.playing }?.key.orEmpty()
        if (key == reported) return
        main.removeCallbacks(sendNowPlaying)
        main.postDelayed(sendNowPlaying, if (key.isEmpty()) 0 else SETTLE_MS)
    }
}
