package app.noctorium.lite

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

private const val BG = 0xFF0B0A10.toInt()
private const val CARD = 0xFF17151F.toInt()
private const val TEXT = 0xFFF2EEFF.toInt()
private const val MUTED = 0xFFA39DB5.toInt()
private const val ACCENT = 0xFFB47CFF.toInt()

/**
 * Every screen of Noctorium Lite, drawn in code from Android's own views: Home, Search, Library, a playlist, and
 * Now playing over them, with the mini player and the three tabs along the bottom.
 */
class MainActivity : Activity(), Player.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private lateinit var mini: LinearLayout
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniProgress: ProgressBar
    private val tabs = mutableListOf<TextView>()

    /** The screens opened on top of a tab, for Back. */
    private val stack = mutableListOf<View>()
    private var nowPlaying: NowPlaying? = null
    private var searchState = SearchScreenState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.init(this)
        Store.init(this)
        window.statusBarColor = BG
        window.navigationBarColor = CARD

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        content = FrameLayout(this).apply { setBackgroundColor(BG) }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildMini(), LinearLayout.LayoutParams(-1, -2))
        root.addView(buildTabs(), LinearLayout.LayoutParams(-1, dp(56)))
        setContentView(root)
        // Android 15 draws every app under the status and navigation bars; keep the screens clear of them.
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        selectTab(0)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onStart() { super.onStart(); Player.listen(this); changed(); tick() }
    override fun onStop() { Player.forget(this); main.removeCallbacksAndMessages(null); super.onStop() }

    override fun changed() {
        val track = Player.current
        mini.visibility = if (track == null) View.GONE else View.VISIBLE
        if (track != null) {
            miniTitle.text = track.title
            miniArtist.text = Player.error ?: if (Player.loading) "Loading…" else track.artist
            if (miniCover.tag != track.artworkUrl) Images.into(miniCover, track.artworkUrl, dp(44))
            miniPlay.setImageResource(if (Player.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        }
        nowPlaying?.refresh()
    }

    /** The seek bars move twice a second while something plays. */
    private fun tick() {
        main.removeCallbacksAndMessages(null)
        main.postDelayed(object : Runnable {
            override fun run() {
                val d = Player.durationMs
                miniProgress.progress = if (d > 0) (Player.positionMs * 1000L / d).toInt() else 0
                nowPlaying?.tick()
                main.postDelayed(this, 500)
            }
        }, 500)
    }

    @Deprecated("Back, before predictive back")
    override fun onBackPressed() {
        when {
            nowPlaying != null -> closeNowPlaying()
            stack.isNotEmpty() -> { content.removeView(stack.removeAt(stack.size - 1)); stack.lastOrNull()?.visibility = View.VISIBLE }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // --- Bottom: the mini player and the tabs ---

    private fun buildMini(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(CARD) }
        miniProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(ACCENT); progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }
        column.addView(miniProgress, LinearLayout.LayoutParams(-1, dp(2)))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(4), dp(8)) }
        miniCover = cover(44)
        row.addView(miniCover, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        miniTitle = text("", 15, TEXT, bold = true); miniArtist = text("", 13, MUTED)
        texts.addView(miniTitle); texts.addView(miniArtist)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        miniPlay = iconButton(android.R.drawable.ic_media_play) { Player.toggle() }
        row.addView(miniPlay)
        row.addView(iconButton(android.R.drawable.ic_media_next) { Player.next() })
        row.setOnClickListener { openNowPlaying() }
        column.addView(row)
        // Shown, progress line and all, only once something is in the queue.
        mini = column
        mini.visibility = View.GONE
        return column
    }

    private fun buildTabs(): View {
        val bar = LinearLayout(this).apply { setBackgroundColor(CARD) }
        listOf("Home", "Search", "Library").forEachIndexed { i, name ->
            val tab = text(name, 14, MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { selectTab(i) }
            }
            tabs += tab
            bar.addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return bar
    }

    private fun selectTab(i: Int) {
        closeNowPlaying()
        stack.clear()
        content.removeAllViews()
        tabs.forEachIndexed { j, t -> t.setTextColor(if (i == j) ACCENT else MUTED) }
        content.addView(
            when (i) {
                0 -> homeScreen()
                1 -> searchScreen()
                else -> libraryScreen()
            },
        )
    }

    /** A screen on top of the tab, such as a playlist; Back takes it off again. */
    private fun push(view: View) {
        closeNowPlaying()
        content.getChildAt(content.childCount - 1)?.visibility = View.GONE
        stack += view
        content.addView(view)
    }

    // --- Home ---

    private fun homeScreen(): View {
        val column = column()
        column.addView(heading("Noctorium"))
        val recent = Store.recent
        if (recent.isNotEmpty()) {
            column.addView(subheading("Played lately"))
            recent.take(5).forEachIndexed { i, t -> column.addView(trackRow(t) { Player.play(recent, i) }) }
        }
        val loading = text("Loading what is on…", 14, MUTED).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        column.addView(loading)
        fun failed(why: String) {
            loading.text = "$why. Tap to try again."
            loading.setOnClickListener { selectTab(0) }
        }
        background({ Api.home() }, ::failed) { sections ->
            column.removeView(loading)
            sections.forEach { section ->
                column.addView(subheading(section.title))
                if (section.playlists.isNotEmpty()) column.addView(playlistShelf(section.playlists))
                section.tracks.take(6).forEachIndexed { i, t -> column.addView(trackRow(t) { Player.play(section.tracks, i) }) }
            }
        }
        return scroll(column)
    }

    // --- Search ---

    private class SearchScreenState(var query: String = "", var tracks: List<Track> = emptyList(), var playlists: List<Playlist> = emptyList())

    private fun searchScreen(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val box = EditText(this).apply {
            hint = "Songs, artists, or a link"
            setText(searchState.query)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(TEXT); setHintTextColor(MUTED)
            background = rounded(CARD, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(12), dp(12), dp(6)) })
        val modes = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(6)) }
        val list = ListView(this).apply { divider = null; selector = rounded(0x22FFFFFF, 8) }
        val status = text("", 14, MUTED).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val names = listOf("HYBRID" to "Both", "YOUTUBE_MUSIC" to "YouTube Music", "SOUNDCLOUD" to "SoundCloud")
        fun show() {
            val rows = searchState.playlists.map<Playlist, Any> { it } + searchState.tracks
            list.adapter = Rows(rows)
        }
        fun run() {
            val q = box.text.toString().trim()
            if (q.isEmpty()) return
            searchState.query = q
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(box.windowToken, 0)
            status.text = "Searching…"
            if (q.startsWith("http://") || q.startsWith("https://") || q.contains("youtu.be/") || q.contains("soundcloud.com/")) {
                return openLink(q) { status.text = it }
            }
            background({ Api.search(q, Store.searchMode) }, { status.text = it }) { (tracks, playlists) ->
                searchState.tracks = tracks; searchState.playlists = playlists.take(6)
                status.text = if (tracks.isEmpty() && playlists.isEmpty()) "Nothing found for “$q”." else ""
                show()
            }
        }
        names.forEach { (mode, label) ->
            val chip = text(label, 13, if (Store.searchMode == mode) BG else TEXT, bold = true).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rounded(if (Store.searchMode == mode) ACCENT else CARD, 14)
                setOnClickListener {
                    Store.searchMode = mode
                    for (k in 0 until modes.childCount) (modes.getChildAt(k) as TextView).let { c ->
                        val on = names[k].first == mode
                        c.setTextColor(if (on) BG else TEXT); c.background = rounded(if (on) ACCENT else CARD, 14)
                    }
                    run()
                }
            }
            modes.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        column.addView(modes)
        column.addView(status)
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        box.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) { run(); true } else false
        }
        if (searchState.tracks.isNotEmpty() || searchState.playlists.isNotEmpty()) show() else box.requestFocus()
        return column
    }

    /** A link typed or shared: a song plays at once, a playlist opens. */
    private fun openLink(url: String, failed: (String) -> Unit = { toast(it) }) {
        background({ Api.link(url) }, failed) { opened ->
            when {
                opened.track != null -> { Player.play(listOf(opened.track), 0); failed("") }
                opened.playlistKey != null -> { failed(""); openPlaylist(Playlist(opened.playlistKey, "Playlist", "", null, 0)) }
                else -> failed("That link has nothing to play")
            }
        }
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("""https?://\S+""").find(shared)?.value?.let { openLink(it) }
    }

    // --- Library ---

    private fun libraryScreen(): View {
        val column = column()
        column.addView(heading("Library"))
        val liked = Store.liked
        column.addView(subheading("Liked songs · ${liked.size}"))
        if (liked.isEmpty()) column.addView(text("Long-press a song and choose Like to keep it here.", 14, MUTED).apply { setPadding(dp(16), 0, dp(16), dp(12)) })
        else {
            column.addView(actionRow({ Player.play(liked, 0) }, { Player.play(liked.shuffled(), 0) }))
            liked.forEachIndexed { i, t -> column.addView(trackRow(t) { Player.play(liked, i) }) }
        }
        val recent = Store.recent
        if (recent.isNotEmpty()) {
            column.addView(subheading("Played lately"))
            recent.forEachIndexed { i, t -> column.addView(trackRow(t) { Player.play(recent, i) }) }
        }
        return scroll(column)
    }

    // --- A playlist ---

    private fun openPlaylist(playlist: Playlist) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = heading(playlist.title)
        column.addView(title)
        val status = text("Loading…", 14, MUTED).apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        column.addView(status)
        val list = ListView(this).apply { divider = null; selector = rounded(0x22FFFFFF, 8) }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        push(column)
        background({ Api.playlist(playlist.key) }, { status.text = it }) { (name, tracks) ->
            if (name.isNotEmpty()) title.text = name
            status.text = "${tracks.size} songs" + if (playlist.owner.isNotEmpty()) " · ${playlist.owner}" else ""
            column.addView(actionRow({ Player.play(tracks, 0) }, { Player.play(tracks.shuffled(), 0) }), 2)
            list.adapter = Rows(tracks)
        }
    }

    // --- Now playing ---

    private fun openNowPlaying() {
        if (nowPlaying != null || Player.current == null) return
        val screen = NowPlaying()
        nowPlaying = screen
        content.addView(screen.view)
    }

    private fun closeNowPlaying() {
        nowPlaying?.let { content.removeView(it.view) }
        nowPlaying = null
    }

    private inner class NowPlaying {
        val view: View
        private val art = cover(300)
        private val title = text("", 22, TEXT, bold = true).apply { gravity = Gravity.CENTER; maxLines = 2 }
        private val artist = text("", 15, MUTED).apply { gravity = Gravity.CENTER }
        private val seek = SeekBar(this@MainActivity).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT)
        }
        private val elapsed = text("0:00", 12, MUTED)
        private val total = text("0:00", 12, MUTED)
        private val play = iconButton(android.R.drawable.ic_media_play, 64) { Player.toggle() }
        private val like = text("♡", 26, TEXT).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        private val queue = ListView(this@MainActivity).apply { divider = null }
        private var dragging = false
        private var shownKey: String? = null

        init {
            val column = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(20), dp(12), dp(20), 0)
                isClickable = true
            }
            val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(text("⌄  Now playing", 14, MUTED, bold = true).apply { setOnClickListener { closeNowPlaying() }; setPadding(0, dp(8), 0, dp(8)) }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(like)
            column.addView(top, LinearLayout.LayoutParams(-1, -2))
            column.addView(art, LinearLayout.LayoutParams(dp(260), dp(260)).apply { topMargin = dp(8) })
            column.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            column.addView(artist, LinearLayout.LayoutParams(-1, -2))
            column.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            val times = LinearLayout(this@MainActivity).apply { setPadding(dp(16), 0, dp(16), 0) }
            times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(total)
            column.addView(times, LinearLayout.LayoutParams(-1, -2))
            val controls = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER }
            controls.addView(iconButton(android.R.drawable.ic_media_previous, 48) { Player.previous() })
            controls.addView(play, LinearLayout.LayoutParams(dp(80), dp(80)).apply { setMargins(dp(24), 0, dp(24), 0) })
            controls.addView(iconButton(android.R.drawable.ic_media_next, 48) { Player.next() })
            column.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            column.addView(text("Up next", 14, MUTED, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            column.addView(queue, LinearLayout.LayoutParams(-1, 0, 1f))
            like.setOnClickListener { Player.current?.let { t -> toast(if (Store.toggleLike(t)) "Liked" else "Not liked any more"); refresh() } }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) elapsed.text = time(value.toLong() * Player.durationMs / 1000)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    dragging = false
                    Player.seek((bar.progress.toLong() * Player.durationMs / 1000).toInt())
                }
            })
            view = column
            refresh()
        }

        fun refresh() {
            val track = Player.current ?: return closeNowPlaying()
            title.text = track.title
            artist.text = Player.error ?: if (Player.loading) "Loading…" else track.artist
            play.setImageResource(if (Player.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            like.text = if (Store.isLiked(track)) "♥" else "♡"
            like.setTextColor(if (Store.isLiked(track)) ACCENT else TEXT)
            if (shownKey != track.key) {
                shownKey = track.key
                Images.into(art, track.artworkUrl, dp(300))
            }
            val upNext = Player.queue.drop(Player.index + 1)
            queue.adapter = Rows(upNext) { i -> Player.jump(Player.index + 1 + i) }
            tick()
        }

        fun tick() {
            val d = Player.durationMs
            total.text = time(d.toLong())
            if (!dragging) {
                elapsed.text = time(Player.positionMs.toLong())
                seek.progress = if (d > 0) (Player.positionMs * 1000L / d).toInt() else 0
            }
        }
    }

    // --- Rows ---

    /** A list of songs and playlists, recycled as it scrolls. Songs play from the list they are in. */
    private inner class Rows(private val items: List<Any>, private val play: ((Int) -> Unit)? = null) : BaseAdapter() {
        private val tracks = items.filterIsInstance<Track>()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val item = items[position]) {
            is Track -> trackRow(item) {
                val i = tracks.indexOf(item)
                play?.invoke(i) ?: Player.play(tracks, i)
            }
            is Playlist -> playlistRow(item)
            else -> View(this@MainActivity)
        }
    }

    private fun trackRow(track: Track, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
            setOnLongClickListener { songMenu(it, track); true }
        }
        val art = cover(48)
        Images.into(art, track.artworkUrl, dp(48))
        row.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        texts.addView(text(track.title, 15, if (Player.current?.key == track.key) ACCENT else TEXT, bold = true))
        texts.addView(text(listOf(track.artist, service(track)).filter { it.isNotEmpty() }.joinToString(" · "), 13, MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (track.durationMs > 0) row.addView(text(time(track.durationMs), 12, MUTED))
        return row
    }

    private fun playlistRow(playlist: Playlist): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            setOnClickListener { openPlaylist(playlist) }
        }
        val art = cover(48)
        Images.into(art, playlist.artworkUrl, dp(48))
        row.addView(art, LinearLayout.LayoutParams(dp(48), dp(48)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        texts.addView(text(playlist.title, 15, TEXT, bold = true))
        texts.addView(text(listOf("Playlist", playlist.owner, if (playlist.count > 0) "${playlist.count} songs" else "").filter { it.isNotEmpty() }.joinToString(" · "), 13, MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /** Playlists side by side, as covers, along a row of Home. */
    private fun playlistShelf(playlists: List<Playlist>): View {
        val row = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), dp(8)) }
        playlists.take(10).forEach { p ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener { openPlaylist(p) }
            }
            val art = cover(132)
            Images.into(art, p.artworkUrl, dp(132))
            card.addView(art, LinearLayout.LayoutParams(dp(132), dp(132)))
            card.addView(text(p.title, 13, TEXT, bold = true).apply { maxWidth = dp(132) }, LinearLayout.LayoutParams(dp(132), -2).apply { topMargin = dp(6) })
            card.addView(text(p.owner, 12, MUTED), LinearLayout.LayoutParams(dp(132), -2))
            row.addView(card)
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun songMenu(anchor: View, track: Track) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Play next")
            menu.add(0, 2, 1, "Add to queue")
            menu.add(0, 3, 2, if (Store.isLiked(track)) "Unlike" else "Like")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { Player.playNext(track); toast("Playing next") }
                    2 -> { Player.addToQueue(track); toast("Added to the queue") }
                    3 -> toast(if (Store.toggleLike(track)) "Liked" else "Not liked any more")
                }
                true
            }
        }.show()
    }

    private fun actionRow(play: () -> Unit, shuffle: () -> Unit): View {
        val row = LinearLayout(this).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        fun button(label: String, filled: Boolean, onClick: () -> Unit) = text(label, 14, if (filled) BG else TEXT, bold = true).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(if (filled) ACCENT else CARD, 20)
            setOnClickListener { onClick() }
        }
        row.addView(button("▶  Play", true, play), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        row.addView(button("Shuffle", false, shuffle))
        return row
    }

    // --- Small things ---

    /** Runs [work] off the main thread and hands its answer back on it, or [failed] what went wrong. */
    private fun <T> background(work: () -> T, failed: (String) -> Unit = { toast(it) }, done: (T) -> Unit) {
        Thread {
            val result = runCatching(work)
            main.post {
                if (isDestroyed) return@post
                result.onSuccess(done).onFailure {
                    android.util.Log.w("NoctoriumLite", "A request failed", it)
                    failed(it.message ?: "The service could not be reached")
                }
            }
        }.start()
    }

    private fun service(track: Track) = when {
        track.provider == "SOUNDCLOUD" -> "SoundCloud"
        track.provider == "YOUTUBE_VIDEO" -> "YouTube"
        track.isYouTube -> "YouTube Music"
        else -> ""
    }

    private fun time(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
    private fun toast(message: String) { if (message.isNotEmpty()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun text(value: String, sp: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = sp.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun heading(value: String) = text(value, 26, TEXT, bold = true).apply { setPadding(dp(16), dp(20), dp(16), dp(8)) }
    private fun subheading(value: String) = text(value, 18, TEXT, bold = true).apply { setPadding(dp(16), dp(18), dp(16), dp(6)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
    private fun scroll(child: View) = ScrollView(this).apply { addView(child) }

    private fun cover(sizeDp: Int) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(CARD, if (sizeDp > 100) 12 else 6)
        clipToOutline = true
    }

    private fun iconButton(icon: Int, sizeDp: Int = 44, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(if (sizeDp >= 64) BG else TEXT)
        background = if (sizeDp >= 64) rounded(ACCENT, sizeDp / 2) else rounded(Color.TRANSPARENT, 0)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener { onClick() }
    }
}
