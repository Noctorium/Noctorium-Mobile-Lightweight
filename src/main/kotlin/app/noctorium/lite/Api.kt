package app.noctorium.lite

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A song, as the hosted API describes one. */
data class Track(
    val provider: String,
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val durationMs: Long,
    val sourceUrl: String,
) {
    val key: String get() = "$provider:$id"
    val isYouTube: Boolean get() = provider.startsWith("YOUTUBE")

    fun toJson(): JSONObject = JSONObject()
        .put("provider", provider).put("id", id).put("title", title).put("artistLine", artist)
        .put("artworkUrl", artworkUrl).put("durationMs", durationMs).put("sourceUrl", sourceUrl)

    companion object {
        fun from(o: JSONObject): Track = Track(
            provider = o.optString("provider"),
            id = o.optString("id"),
            title = o.optString("title"),
            artist = o.optString("artistLine").ifEmpty {
                o.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString { a.getJSONObject(it).optString("name") } }.orEmpty()
            },
            artworkUrl = o.optString("artworkUrl").ifEmpty { null },
            durationMs = o.optLong("durationMs"),
            sourceUrl = o.optString("sourceUrl"),
        )
    }
}

/** A playlist or an album: opened by its key to read its songs. */
data class Playlist(val key: String, val title: String, val owner: String, val artworkUrl: String?, val count: Int) {
    companion object {
        fun from(o: JSONObject): Playlist = Playlist(
            key = o.optString("key"),
            title = o.optString("title"),
            owner = o.optString("ownerName"),
            artworkUrl = o.optString("artworkUrl").ifEmpty { null },
            count = o.optInt("trackCount"),
        )
    }
}

class Section(val title: String, val tracks: List<Track>, val playlists: List<Playlist>)

/** What a link opened: a song to play or a playlist to show. */
class Opened(val track: Track?, val playlistKey: String?)

/**
 * The hosted web player's API, which reads YouTube Music and SoundCloud for the browser player and answers in
 * small JSON lists. Everything here blocks: call it off the main thread.
 */
object Api {
    private const val BASE = "https://noctorium-music.vercel.app/api/music"

    private fun get(query: String): JSONObject {
        val reply = Http.text("$BASE?$query&hl=en&gl=US")
        val json = JSONObject(reply)
        json.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException(it) }
        return json
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun tracks(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> Track.from(it.getJSONObject(i)) } }.orEmpty()
    private fun playlists(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> Playlist.from(it.getJSONObject(i)) } }.orEmpty()

    /** [mode] is HYBRID, YOUTUBE_MUSIC or SOUNDCLOUD. Songs, then playlists and albums. */
    fun search(query: String, mode: String): Pair<List<Track>, List<Playlist>> {
        val json = get("op=search&q=${enc(query)}&mode=$mode")
        val albums = json.optJSONArray("albums")?.let { a ->
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Playlist(o.optString("id"), o.optString("title"), Track.from(JSONObject().put("artists", o.optJSONArray("artists"))).artist, o.optString("artworkUrl").ifEmpty { null }, 0)
            }
        }.orEmpty()
        return tracks(json.optJSONArray("tracks")) to (playlists(json.optJSONArray("playlists")) + albums)
    }

    fun home(): List<Section> {
        val a = get("op=home").optJSONArray("sections") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Section(o.optString("title"), tracks(o.optJSONArray("tracks")), playlists(o.optJSONArray("playlists")))
        }.filter { it.tracks.isNotEmpty() || it.playlists.isNotEmpty() }
    }

    fun playlist(key: String): Pair<String, List<Track>> {
        val json = get("op=playlist&key=${enc(key)}")
        return json.optString("title") to tracks(json.optJSONArray("tracks"))
    }

    /** Songs like [track], for autoplay once the queue runs out. */
    fun radio(track: Track): List<Track> = tracks(get("op=radio&key=${enc("${track.provider}:${track.id}")}").optJSONArray("tracks"))

    fun link(url: String): Opened {
        val json = get("op=link&url=${enc(url)}")
        return Opened(json.optJSONObject("track")?.let(Track::from), json.optJSONObject("playlist")?.optString("key"))
    }

    /**
     * A SoundCloud track's audio, as a plain MP3: Android's own player cannot read the AAC streams SoundCloud uses
     * for long tracks, and reads one MP3 from start to end over a single connection anyway.
     */
    fun soundCloudStream(id: String): String = get("op=stream&id=${enc(id)}&progressive=1").getString("url")
}

/** The one HTTP helper: Android's own HttpURLConnection, with timeouts. */
object Http {
    fun text(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && !text.trimStart().startsWith("{")) throw IOException("The service answered $code")
            return text
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * A YouTube song's audio address, asked for on this phone: YouTube hands out addresses for the device that asks,
 * so the hosted API cannot ask for it.
 *
 * It asks as the clients yt-dlp uses for the same reason -- visionOS first, then Android VR -- which answer with
 * plain addresses: no JavaScript to run and no token to make. The address is fetched later with [userAgent].
 */
object YouTube {
    class Stream(val url: String, val userAgent: String)

    private class Client(val name: String, val number: Int, val version: String, val userAgent: String, val extra: Map<String, Any>)

    private val clients = listOf(
        Client(
            "VISIONOS", 101, "1.02",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1", "osName" to "visionOS", "osVersion" to "26.5.23O471"),
        ),
        Client(
            "ANDROID_VR", 28, "1.65.10",
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L"),
        ),
    )

    /**
     * Who this phone is to YouTube, as a browser's first visit would be told: without it YouTube takes the request for
     * a bot. Read once from the same small file YouTube's own pages read it from, and kept while the app runs.
     */
    @Volatile private var visitor: String? = null

    private fun visitorData(): String? {
        visitor?.let { return it }
        val text = runCatching {
            Http.text("https://www.youtube.com/sw.js_data", headers = mapOf("User-Agent" to clients[0].userAgent))
        }.getOrNull() ?: return null
        // A JSON array behind a guard line; the visitor data is the one long string that begins "Cg".
        return Regex("\"(Cg[A-Za-z0-9_%=-]{20,})\"").find(text)?.groupValues?.get(1)?.also { visitor = it }
    }

    fun stream(videoId: String): Stream {
        var why = "YouTube would not play this song"
        val visitorData = visitorData()
        for (client in clients) {
            try {
                val context = JSONObject().put("clientName", client.name).put("clientVersion", client.version)
                    .put("hl", "en").put("gl", "US").put("userAgent", client.userAgent)
                visitorData?.let { context.put("visitorData", it) }
                client.extra.forEach { (k, v) -> context.put(k, v) }
                val body = JSONObject()
                    .put("context", JSONObject().put("client", context))
                    .put("videoId", videoId)
                    .put("contentCheckOk", true).put("racyCheckOk", true)
                    .toString()
                val reply = JSONObject(
                    Http.text(
                        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body,
                        mapOf(
                            "User-Agent" to client.userAgent,
                            "X-YouTube-Client-Name" to client.number.toString(),
                            "X-YouTube-Client-Version" to client.version,
                            "Origin" to "https://www.youtube.com",
                        ) + (visitorData?.let { mapOf("X-Goog-Visitor-Id" to it) } ?: emptyMap()),
                    ),
                )
                val status = reply.optJSONObject("playabilityStatus")
                if (status != null && status.optString("status") != "OK") {
                    why = status.optString("reason").ifEmpty { why }
                    continue
                }
                val formats = reply.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: continue
                pick(formats)?.let { return Stream(it, client.userAgent) }
            } catch (e: Exception) {
                why = e.message ?: why
            }
        }
        throw IOException(why)
    }

    /** The best audio-only format with a plain address: AAC first, which every Android plays, then Opus. */
    private fun pick(formats: JSONArray): String? {
        val audio = (0 until formats.length()).map { formats.getJSONObject(it) }
            .filter { it.optString("mimeType").startsWith("audio/") && it.has("url") }
        val ranked = audio.sortedWith(
            compareBy<JSONObject> { if (it.optString("mimeType").startsWith("audio/mp4")) 0 else 1 }
                .thenByDescending { it.optInt("bitrate") },
        )
        return ranked.firstOrNull()?.getString("url")
    }
}
