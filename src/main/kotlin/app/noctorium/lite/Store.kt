package app.noctorium.lite

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Liked songs and what was played lately, kept on the phone only. */
object Store {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.getSharedPreferences("noctorium", Context.MODE_PRIVATE)
    }

    private fun read(name: String): MutableList<Track> = runCatching {
        val a = JSONArray(prefs.getString(name, "[]"))
        (0 until a.length()).mapTo(mutableListOf()) { Track.from(a.getJSONObject(it)) }
    }.getOrDefault(mutableListOf())

    private fun write(name: String, tracks: List<Track>) =
        prefs.edit().putString(name, JSONArray().apply { tracks.forEach { put(it.toJson()) } }.toString()).apply()

    val liked: List<Track> get() = read("liked")
    val recent: List<Track> get() = read("recent")

    fun isLiked(track: Track) = liked.any { it.key == track.key }

    fun toggleLike(track: Track): Boolean {
        val all = read("liked")
        val was = all.removeAll { it.key == track.key }
        if (!was) all.add(0, track)
        write("liked", all)
        return !was
    }

    fun played(track: Track) {
        val all = read("recent")
        all.removeAll { it.key == track.key }
        all.add(0, track)
        write("recent", all.take(30))
    }

    var searchMode: String
        get() = prefs.getString("searchMode", "HYBRID") ?: "HYBRID"
        set(value) = prefs.edit().putString("searchMode", value).apply()
}

/**
 * Covers: fetched on two threads, shrunk as they are decoded to the size they are shown at, and kept in a small
 * memory cache. Nothing is written to storage, so the app never grows.
 */
object Images {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val work = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String?, sizePx: Int, done: (Bitmap?) -> Unit) {
        if (url == null) return done(null)
        val key = "$sizePx|$url"
        cache.get(key)?.let { return done(it) }
        work.execute {
            val bitmap = runCatching { fetch(url, sizePx) }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            main.post { done(bitmap) }
        }
    }

    fun into(view: ImageView, url: String?, sizePx: Int) {
        view.tag = url
        view.setImageDrawable(null)
        load(url, sizePx) { if (view.tag == url) view.setImageBitmap(it) }
    }

    private fun fetch(url: String, sizePx: Int): Bitmap? {
        val bytes = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8_000; readTimeout = 10_000
            try { inputStream.use { it.readBytes() } } finally { disconnect() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}
