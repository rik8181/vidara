package com.rik.vidaraplus

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private enum class Tab { HOME, SHORTS, MUSIC, YOU }

    private lateinit var api: Api
    private val pool = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val player get() = App.player

    // fő felépítés
    private lateinit var root: FrameLayout
    private lateinit var pages: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var miniBar: LinearLayout
    private lateinit var miniThumb: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageView
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()

    // oldalak
    private lateinit var homePage: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var homeAdapter: FeedAdapter
    private lateinit var homeStatus: TextView
    private lateinit var musicPage: LinearLayout
    private lateinit var musicAdapter: FeedAdapter
    private lateinit var musicStatus: TextView
    private lateinit var searchPage: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var searchAdapter: FeedAdapter
    private lateinit var searchStatus: TextView
    private lateinit var shortsPage: FrameLayout
    private lateinit var shortsList: RecyclerView
    private lateinit var shortsAdapter: ShortsAdapter
    private lateinit var shortsStatus: TextView
    private lateinit var shortsPlayerView: PlayerView
    private lateinit var youPage: LinearLayout
    private lateinit var youServer: TextView

    // lejátszó oldal
    private lateinit var watch: LinearLayout
    private lateinit var webLayer: WebLayer
    private lateinit var shortsWeb: WebLayer
    private lateinit var homeConcat: ConcatAdapter
    private lateinit var homeHeader: HeaderAdapter
    private lateinit var homeRecycler: RecyclerView
    private var headerAttached = true
    private lateinit var playerPanel: PlayerPanel
    private lateinit var watchPlayerView: PlayerView
    private lateinit var cover: ImageView
    private lateinit var minimizeBtn: ImageView
    private lateinit var watchScroll: RecyclerView
    private lateinit var wTitle: TextView
    private lateinit var wStats: TextView
    private lateinit var wDesc: TextView
    private lateinit var wChannelRow: LinearLayout
    private lateinit var wAvatar: AvatarView
    private lateinit var wChannelName: TextView
    private lateinit var wChannelSubs: TextView
    private lateinit var wActions: HorizontalScrollView
    private lateinit var wLikes: TextView
    private lateinit var wCommentsCard: LinearLayout
    private lateinit var wCommentsHead: TextView
    private lateinit var wCommentPreview: TextView
    private lateinit var wCommentsList: LinearLayout
    private lateinit var wRelatedTitle: TextView
    private lateinit var relatedAdapter: FeedAdapter
    private lateinit var wStatus: TextView

    // állapot
    private val categories = listOf(
        "all" to R.string.cat_all, "music" to R.string.cat_music, "games" to R.string.cat_games,
        "news" to R.string.cat_news, "sport" to R.string.cat_sport, "cooking" to R.string.cat_cooking,
        "tech" to R.string.cat_tech, "movies" to R.string.cat_movies, "comedy" to R.string.cat_comedy,
    )
    private var category = "all"
    private val failedHeights = HashSet<Int>()
    private var usingMuxed = false
    private val homeCache = HashMap<String, List<Item>>()
    private var musicLoaded = false
    private var tab = Tab.HOME
    private var searchOpen = false
    private var searchMusic = false
    private var searchResults: List<Item> = emptyList()
    private var watchOpen = false
    private var current: Item? = null
    private var currentMusic = false
    private var queue: List<Item> = emptyList()
    private var related: List<Item> = emptyList()
    private var detail: VideoDetail? = null
    private var fullscreen = false
    private var inPip = false
    private var floating = false
    private lateinit var floatOverlay: View
    private lateinit var floatClose: ImageView
    private lateinit var floatPlay: ImageView
    private var playToken = 0
    private var homeToken = 0
    private var searchToken = 0
    private var shortsLoadToken = 0
    private var retried = false
    private var shortsLoaded = false
    private var shortPos = -1
    private var shortToken = 0
    private var shortsActive = false
    private var commentsShown = false
    private var qualityCap = 1080
    private lateinit var lastCard: LinearLayout
    private lateinit var lastThumb: ImageView
    private lateinit var lastTitle: TextView
    private lateinit var lastSub: TextView
    private lateinit var lastProgress: View
    private var channelNew: List<Item> = emptyList()
    private var resumeMs = 0L
    private var currentHeight = 0
    private lateinit var qualityBtn: TextView
    private var commentsData: List<Comment> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation()
        api = Api(this)
        qualityCap = api.quality
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        bindService()
        player.addListener(playerListener)
        watchPlayerView.player = player
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 12)
        }
        showTab(Tab.HOME)
        loadHome()
        primeCaches()
        // önfrissítés: 6 óránként egyszer, csendben (ha van újabb kiadás a GitHubon, felajánlja)
        runCatching { Updater.check(this, false) }
        // Induláskor a valódi YouTube-felület tölt be (reklám nélkül) – ez az app fő képernyője.
        handler.postDelayed({ if (!webLayer.isOpen) webLayer.open(WebTube.YT) }, 150)
        updateLastCard()
        runCatching { WebTube.logFile = java.io.File(filesDir, "vidara_log.txt").also { it.writeText("") } }
        startLogDump()
    }

    /// A lejátszó eseménynaplója 4 mp-enként a logcatbe is kikerül (fagyás esetén visszanézhető).
    private fun startLogDump() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val s = WebTube.drainNew()
                // soronként (és hosszú sornál darabolva), hogy a logcat ne vágja le
                if (s.isNotEmpty()) runCatching { s.split('\n').forEach { line -> line.chunked(3000).forEach { android.util.Log.w("VidaraPlus", it) } } }
                handler.postDelayed(this, 4000)
            }
        }, 4000)
    }

    /// Háttérben előtöltjük a Shorts-ot és a zenét is, hogy a fülekre váltva azonnal ott legyenek.
    /// A lista első elemeit a szerver előre feloldja, így a rákoppintás után azonnal indul a videó.
    private fun prefetch(items: List<Item>) {
        items.take(4).forEach { pool.execute { runCatching { api.prefetch(it.id) } } }
    }

    private fun primeCaches() {
        pool.execute { runCatching { api.shorts() } }
        pool.execute { runCatching { api.musicHome() } }
        loadChannelNew()
    }

    private fun bindService() {
        try {
            val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(this, token).buildAsync()
        } catch (_: Throwable) {
        }
    }

    // ================================================================== felépítés

    private fun recycler(adapter: RecyclerView.Adapter<*>): RecyclerView =
        RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = adapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            overScrollMode = View.OVER_SCROLL_NEVER
        }

    private fun statusView(): TextView =
        label("", 14f, Palette.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), 0)
            visibility = View.GONE
        }

    private fun topBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            setBackgroundColor(Color.BLACK)
        }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0D0720.toInt()) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages = FrameLayout(this)
        content.addView(pages, LinearLayout.LayoutParams(MATCH, 0, 1f))

        buildHome()
        buildMusic()
        buildSearch()
        buildShorts()
        buildYou()

        miniBar = buildMiniBar()
        content.addView(miniBar, LinearLayout.LayoutParams(MATCH, dp(56)))
        navBar = buildNav()
        content.addView(navBar, LinearLayout.LayoutParams(MATCH, dp(56)))
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        shortsWeb = WebLayer(this, true)
        shortsWeb.onEmbeddedBack = { showTab(Tab.HOME) }
        root.addView(shortsWeb, FrameLayout.LayoutParams(MATCH, MATCH))

        buildWatch()
        root.addView(watch, FrameLayout.LayoutParams(MATCH, MATCH))
        webLayer = WebLayer(this)
        // az egész app a YouTube-felület: teljes képernyőn tölt (nincs app alsó menü)
        webLayer.bottomGapPx = 0
        webLayer.onState = { updatePipParams() }
        webLayer.onPipRequest = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canPip()) {
                runCatching { webLayer.prePip(); enterPictureInPictureMode(pipParams()) }
            }
        }
        shortsWeb.onState = { updatePipParams() }
        if (webPlay()) {
            handler.postDelayed({ webLayer.warmUp() }, 1200)
            // a Shorts-oldalt még a fül megnyitása előtt betöltjük (így nem kell 5-8 mp-et várni)
            handler.postDelayed({ shortsWeb.warmUp(SHORTS_URL) }, 2500)
        }
        root.addView(webLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // alul nincs belső margó: a YouTube alsó menüje a képernyő legaljára kerül
            v.setPadding(bars.left, bars.top, bars.right, 0)
            // a YouTube-felület sávja a státuszsáv ALATT legyen (ne lógjon felülre)
            if (::webLayer.isInitialized) webLayer.setPadding(0, bars.top, 0, 0)
            if (::shortsWeb.isInitialized) shortsWeb.setPadding(0, bars.top, 0, 0)
            insets
        }
        layoutPlayer()
    }

    // ---------------------------------------------------------------- kezdőlap
    private fun buildHome() {
        homePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = topBar()
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_vidara_logo) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        bar.addView(
            label("Vidara", 20f, bold = true).apply { setPadding(dp(8), 0, 0, 0); letterSpacing = -0.03f },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        bar.addView(iconButton(R.drawable.ic_search, 44) { openSearch(false) })
        homePage.addView(bar, LinearLayout.LayoutParams(MATCH, dp(52)))

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.BLACK)
        }
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        categories.forEach { (name, resId) ->
            val chip = label(getString(resId), 14f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                isClickable = true
                tag = name
                setOnClickListener { selectCategory(name) }
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(WRAP, dp(32)).apply { rightMargin = dp(8) })
        }
        scroll.addView(chipRow)
        homePage.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))
        styleChips()

        homeAdapter = FeedAdapter(api) { item, _ -> openWatch(item, false, emptyList()) }
        val frame = FrameLayout(this)
        // nagy „folytatás” kártya a lista tetején (mint a YouTube-on)
        lastCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            isClickable = true
            setOnClickListener { resumeLast() }
        }
        val lastThumbH = (screenWidth * 9f / 16f).toInt()
        val lastBox = FrameLayout(this)
        lastThumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Palette.CARD) }
        lastBox.addView(lastThumb, FrameLayout.LayoutParams(MATCH, lastThumbH))
        lastBox.addView(
            label(getString(R.string.continue_watching), 12f, Color.WHITE, bold = true).apply {
                background = rounded(0xCC000000.toInt(), 4f)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) }
        )
        lastProgress = View(this).apply { setBackgroundColor(0xFFFF0000.toInt()) }
        lastBox.addView(lastProgress, FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM or Gravity.START))
        lastCard.addView(lastBox, LinearLayout.LayoutParams(MATCH, lastThumbH))
        val lastInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(14)) }
        lastTitle = label("", 15f, Color.WHITE, bold = true).apply { maxLines = 2 }
        lastSub = label("", 12.5f, Palette.DIM).apply { maxLines = 1; setPadding(0, dp(3), 0, 0) }
        lastInfo.addView(lastTitle)
        lastInfo.addView(lastSub)
        lastCard.addView(lastInfo, LinearLayout.LayoutParams(MATCH, WRAP))
        homeHeader = HeaderAdapter(lastCard)
        homeConcat = ConcatAdapter(homeHeader, homeAdapter)
        headerAttached = true
        homeRecycler = recycler(homeConcat)
        frame.addView(homeRecycler, FrameLayout.LayoutParams(MATCH, MATCH))
        homeStatus = statusView()
        frame.addView(homeStatus, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        homePage.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        pages.addView(homePage, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun styleChips() {
        for (i in 0 until chipRow.childCount) {
            val chip = chipRow.getChildAt(i) as TextView
            val on = chip.tag == category
            chip.background = rounded(if (on) Color.WHITE else 0xFF272727.toInt(), 8f)
            chip.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
    }

    private fun selectCategory(name: String) {
        if (category == name) return
        category = name
        styleChips()
        loadHome()
        homeRecycler.scrollToPosition(0)
    }

    /// Szerver nélküli mód: a keresés a beépített (reklámmentes) webes lejátszóban történik.
    private fun webSearch(query: String, music: Boolean) {
        if (searchOpen) closeSearch()
        val base = if (music) "https://music.youtube.com/results?search_query=" else "https://m.youtube.com/results?search_query="
        webLayer.open(base + Uri.encode(query))
    }

    private fun categoryQuery(name: String): String {
        val hu = java.util.Locale.getDefault().language == "hu"
        return when (name) {
            "music" -> if (hu) "népszerű zene" else "popular music"
            "games" -> if (hu) "játék végigjátszás" else "gameplay"
            "news" -> if (hu) "hírek" else "news"
            "sport" -> if (hu) "sport összefoglaló" else "sports highlights"
            "cooking" -> if (hu) "főzés receptek" else "cooking recipes"
            "tech" -> if (hu) "technológia" else "technology"
            "movies" -> if (hu) "teljes film magyarul" else "full movie"
            "comedy" -> if (hu) "vicces videók" else "funny videos"
            else -> if (hu) "népszerű videók" else "popular videos"
        }
    }

    // ---------------------------------------------------------------- zene
    private fun buildMusic() {
        musicPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = topBar()
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_music) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        bar.addView(
            label(getString(R.string.tab_music), 20f, bold = true).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        bar.addView(iconButton(R.drawable.ic_search, 44) { openSearch(true) })
        musicPage.addView(bar, LinearLayout.LayoutParams(MATCH, dp(52)))
        musicAdapter = FeedAdapter(api) { item, pos -> openWatch(item, true, musicQueue(musicAdapterItems, pos)) }
        val frame = FrameLayout(this)
        frame.addView(recycler(musicAdapter), FrameLayout.LayoutParams(MATCH, MATCH))
        musicStatus = statusView()
        frame.addView(musicStatus, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        musicPage.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        pages.addView(musicPage, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private var musicAdapterItems: List<Item> = emptyList()

    private fun musicQueue(list: List<Item>, pos: Int): List<Item> =
        if (pos + 1 < list.size) list.subList(pos + 1, list.size) else emptyList()

    // ---------------------------------------------------------------- keresés
    private fun buildSearch() {
        searchPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        val bar = topBar()
        bar.addView(iconButton(R.drawable.ic_back, 44) { closeSearch() })
        searchField = EditText(this).apply {
            hint = getString(R.string.search)
            setHintTextColor(Palette.DIM)
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(0xFF272727.toInt(), 20f)
            setPadding(dp(16), 0, dp(16), 0)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
        }
        bar.addView(searchField, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        searchPage.addView(bar, LinearLayout.LayoutParams(MATCH, dp(52)))
        searchAdapter = FeedAdapter(api) { item, pos ->
            openWatch(item, searchMusic, if (searchMusic) musicQueue(searchResults, pos) else emptyList())
        }
        val frame = FrameLayout(this)
        frame.addView(recycler(searchAdapter), FrameLayout.LayoutParams(MATCH, MATCH))
        searchStatus = statusView()
        frame.addView(searchStatus, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        searchPage.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        pages.addView(searchPage, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun openSearch(music: Boolean) {
        searchMusic = music
        searchOpen = true
        searchPage.visibility = View.VISIBLE
        searchField.hint = getString(if (music) R.string.search_music else R.string.search)
        searchField.requestFocus()
        val input = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        searchField.postDelayed({ input.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT) }, 120)
    }

    private fun closeSearch() {
        searchOpen = false
        searchPage.visibility = View.GONE
        val input = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    private fun runSearch() {
        val query = searchField.text.toString().trim()
        if (query.isEmpty()) return
        val input = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        input.hideSoftInputFromWindow(searchField.windowToken, 0)
        val music = searchMusic
        val token = ++searchToken
        status(searchStatus, getString(R.string.searching))
        pool.execute {
            val r = runCatching { api.search(query, music) }
            handler.post {
                if (token != searchToken) return@post
                r.onSuccess { found ->
                    searchResults = found
                    searchAdapter.submit(found, music)
                    status(searchStatus, if (found.isEmpty()) getString(R.string.no_results) else "")
                }.onFailure { e ->
                    searchAdapter.submit(emptyList(), music)
                    status(searchStatus, describe(e))
                    webSearch(query, music)   // tartalék: ha a szerver nem elérhető/nem ad találatot
                }
            }
        }
    }

    // ---------------------------------------------------------------- Shorts
    private fun buildShorts() {
        shortsPage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        shortsAdapter = ShortsAdapter()
        shortsList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = shortsAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        PagerSnapHelper().attachToRecyclerView(shortsList)
        shortsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    prefetchShort(shortPos + 1)
                    prefetchShort(shortPos - 1)
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val lm = rv.layoutManager as LinearLayoutManager
                val pos = lm.findFirstCompletelyVisibleItemPosition()
                if (pos != RecyclerView.NO_POSITION && pos != shortPos) playShort(pos)
            }
        })
        shortsPage.addView(shortsList, FrameLayout.LayoutParams(MATCH, MATCH))
        shortsPlayerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { this@MainActivity.player.playWhenReady = !this@MainActivity.player.playWhenReady }
        }
        shortsStatus = statusView()
        shortsPage.addView(shortsStatus, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        pages.addView(shortsPage, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun enterShorts() {
        // a Shorts külön lejátszófelületet használ: a korábbi videót leállítjuk
        closeWatchPlayback()
        shortsActive = true
        watchPlayerView.player = null
        shortsPlayerView.player = player
        player.repeatMode = Player.REPEAT_MODE_ONE
        val oldShorts = if (!shortsLoaded) api.shortsCached() else emptyList()
        if (oldShorts.isNotEmpty()) {
            shortsAdapter.submit(oldShorts)
            shortsLoaded = true
            status(shortsStatus, "")
            shortsList.post { playShort(0) }
        } else if (!shortsLoaded) {
            status(shortsStatus, getString(R.string.loading))
            val token = ++shortsLoadToken
            pool.execute {
                val r = runCatching { api.shorts() }
                handler.post {
                    if (token != shortsLoadToken) return@post
                    r.onSuccess { found ->
                        shortsAdapter.submit(found)
                        shortsLoaded = found.isNotEmpty()
                        status(shortsStatus, if (found.isEmpty()) getString(R.string.no_shorts) else "")
                        if (found.isNotEmpty()) shortsList.post { playShort(0) }
                        else {
                            // nincs natív lista: tartalékként a webes Shorts
                            shortsActive = false
                            shortsPage.visibility = View.GONE
                            shortsWeb.openEmbedded(SHORTS_URL)
                        }
                    }.onFailure { e -> status(shortsStatus, describe(e)) }
                }
            }
        } else if (shortPos >= 0) {
            val pos = shortPos
            shortPos = -1
            shortsList.post { playShort(pos) }
        }
    }

    private fun leaveShorts() {
        if (!shortsActive) return
        shortsActive = false
        shortToken++
        player.pause()
        player.stop()
        player.clearMediaItems()
        player.repeatMode = Player.REPEAT_MODE_OFF
        (shortsPlayerView.parent as? ViewGroup)?.removeView(shortsPlayerView)
        shortsPlayerView.player = null
        watchPlayerView.player = player
    }

    /// A lejátszó nézetét az aktuális oldalra tesszük; ha az oldal még nem készült el, újrapróbáljuk.
    private fun attachShortPlayer(pos: Int, tries: Int) {
        if (!shortsActive || pos != shortPos) return
        val holder = shortsList.findViewHolderForAdapterPosition(pos) as? ShortsAdapter.Holder
        if (holder == null) {
            if (tries < 15) handler.postDelayed({ attachShortPlayer(pos, tries + 1) }, 100)
            return
        }
        if (shortsPlayerView.parent !== holder.slot) {
            (shortsPlayerView.parent as? ViewGroup)?.removeView(shortsPlayerView)
            holder.slot.addView(shortsPlayerView, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    // Shorts-előtöltés: a szomszédos videók adatait előre lekérjük, hogy lapozáskor azonnal induljanak
    private val shortDetails = java.util.concurrent.ConcurrentHashMap<String, VideoDetail>()
    private val shortBusy = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private fun prefetchShort(pos: Int) {
        if (pos < 0 || pos >= shortsAdapter.itemCount) return
        val item = shortsAdapter.itemAt(pos) ?: return
        if (item.id.isEmpty() || shortDetails.containsKey(item.id)) return
        if (!shortBusy.add(item.id)) return
        pool.execute {
            val r = runCatching { api.video(item.id) }
            r.onSuccess { d ->
                if (shortDetails.size < 40) shortDetails[item.id] = d
            }
            shortBusy.remove(item.id)
        }
    }

    private fun playShort(pos: Int) {
        val item = shortsAdapter.itemAt(pos) ?: return
        shortPos = pos
        val token = ++shortToken
        // a lejátszót NEM állítjuk le és nem vesszük le azonnal: a régi kép addig látszik,
        // amíg az új videó készen nem áll (nem lesz fekete/üres a váltás)
        // a szomszédokat azonnal előtöltjük (a következő lapozás így azonnal indul)
        prefetchShort(pos + 1)
        prefetchShort(pos - 1)
        val cached = shortDetails[item.id]
        if (cached != null) {
            val built = buildSource(cached, false, item)
            if (built != null) {
                player.setMediaSource(built.first)
                player.prepare()
                player.playWhenReady = true
                attachShortPlayer(pos, 0)
            } else skipShort(pos)
            return
        }
        pool.execute {
            val r = runCatching { api.video(item.id) }
            handler.post {
                if (token != shortToken || !shortsActive) return@post
                r.onSuccess { d ->
                    if (shortDetails.size < 40) shortDetails[item.id] = d
                    val built = buildSource(d, false, item)
                    if (built != null) {
                        player.setMediaSource(built.first)
                        player.prepare()
                        player.playWhenReady = true
                        attachShortPlayer(pos, 0)   // csak most kerül a kép az új oldalra
                    } else skipShort(pos)
                }.onFailure { skipShort(pos) }
            }
        }
    }

    /// Nem elérhető rövid videó: automatikusan a következőre lépünk.
    private fun skipShort(pos: Int) {
        if (pos == shortPos && pos + 1 < shortsAdapter.itemCount) shortsList.smoothScrollToPosition(pos + 1)
    }

    // ---------------------------------------------------------------- Te
    private fun buildYou() {
        youPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val bar = topBar()
        bar.addView(label(getString(R.string.tab_you), 20f, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        youPage.addView(bar, LinearLayout.LayoutParams(MATCH, dp(52)))
        youServer = label("", 13f, Palette.DIM)
        youPage.addView(
            settingsRow(R.drawable.ic_settings, getString(R.string.settings_server), youServer) { showSettings(false) },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        // Szerver nélküli mód: minden (keresés, kategóriák, lejátszás) a beépített webes lejátszóban
        val noServerSub = label("", 13f, Palette.DIM)
        fun refreshNoServer() {
            noServerSub.text = getString(
                if (api.configured) R.string.no_server_off else R.string.no_server_on
            )
        }
        refreshNoServer()
        youPage.addView(
            settingsRow(R.drawable.ic_test, getString(R.string.no_server), noServerSub) {
                if (api.configured) {
                    // bekapcsolás: töröljük a szerver címét, onnantól a webes lejátszó dolgozik
                    api.server = ""
                    homeCache.clear()
                    shortsLoaded = false
                    musicLoaded = false
                    youServer.text = getString(R.string.not_set)
                    refreshNoServer()
                    showTab(Tab.HOME)
                    webLayer.open(WebTube.YT)
                } else {
                    refreshNoServer()
                    showSettings(false)   // itt lehet újra szervert beállítani
                }
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        youPage.addView(
            settingsRow(R.drawable.ic_test, getString(R.string.server_test), label(getString(R.string.server_test_sub), 13f, Palette.DIM)) {
                Toast.makeText(this, getString(R.string.testing), Toast.LENGTH_SHORT).show()
                pool.execute {
                    val text = try { api.diag() } catch (e: Exception) { describe(e) }
                    handler.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
                }
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        youPage.addView(
            settingsRow(R.drawable.ic_play, getString(R.string.web_youtube), label(getString(R.string.web_youtube_sub), 13f, Palette.DIM)) {
                webLayer.open(WebTube.YT)
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        youPage.addView(
            settingsRow(R.drawable.ic_music, getString(R.string.web_music), label(getString(R.string.web_music_sub), 13f, Palette.DIM)) {
                webLayer.open(WebTube.MUSIC)
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        youPage.addView(
            settingsRow(
                R.drawable.ic_settings,
                getString(R.string.update_check),
                label(getString(R.string.update_version, Updater.currentVersion(this)), 13f, Palette.DIM)
            ) { Updater.check(this, true) },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        youPage.addView(
            settingsRow(R.drawable.ic_test, getString(R.string.debug_title), label(getString(R.string.debug_sub), 13f, Palette.DIM)) {
                val tv = TextView(this).apply {
                    text = WebTube.logText().ifEmpty { getString(R.string.debug_empty) }
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                val sv = ScrollView(this).apply { addView(tv); post { fullScroll(View.FOCUS_DOWN) } }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.debug_title))
                    .setView(sv)
                    .setPositiveButton(getString(R.string.close), null)
                    .setNeutralButton(getString(R.string.clear)) { _, _ -> WebTube.clearLog() }
                    .show()
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        val webSub = label("", 13f, Palette.DIM)
        fun refreshWebSub() { webSub.text = getString(if (webPlay()) R.string.web_play_on else R.string.web_play_off) }
        refreshWebSub()
        youPage.addView(
            settingsRow(R.drawable.ic_settings, getString(R.string.web_play), webSub) {
                getSharedPreferences("vidara", MODE_PRIVATE).edit().putBoolean("webplay", !webPlay()).apply()
                refreshWebSub()
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )
        pages.addView(youPage, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun settingsRow(icon: Int, title: String, sub: TextView, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(simpleIcon(icon, 40, Color.WHITE, 8))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 16f, Color.WHITE))
        sub.setPadding(0, dp(3), 0, 0)
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
        return row
    }

    // ---------------------------------------------------------------- alsó sáv + mini lejátszó
    private fun buildNav(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        val entries = listOf(
            Triple(Tab.HOME, R.drawable.ic_home, getString(R.string.tab_home)),
            Triple(Tab.SHORTS, R.drawable.ic_shorts, getString(R.string.tab_shorts)),
            Triple(Tab.MUSIC, R.drawable.ic_music, getString(R.string.tab_music)),
            Triple(Tab.YOU, R.drawable.ic_person, getString(R.string.tab_you)),
        )
        entries.forEach { (t, icon, name) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener {
                    // a kezdőlap maga a YouTube-felület: ha az van nyitva, nem zárjuk be
                    if (!(t == Tab.HOME && this@MainActivity.webLayer.isOpen && !this@MainActivity.webLayer.isMini)) showTab(t)
                }
            }
            val image = ImageView(this).apply { setImageResource(icon) }
            val text = label(name, 10f, Color.WHITE)
            cell.addView(image, LinearLayout.LayoutParams(dp(24), dp(24)))
            cell.addView(text, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(3) })
            navIcons.add(image)
            navLabels.add(text)
            bar.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        return bar
    }

    private fun buildMiniBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF212121.toInt())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { restoreWatch() }
        }
        miniThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.CARD)
        }
        bar.addView(miniThumb, LinearLayout.LayoutParams(dp(100), MATCH))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        miniTitle = label("", 13.5f, Color.WHITE, bold = true).apply { maxLines = 1 }
        miniSub = label("", 12f, Palette.DIM).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) }
        col.addView(miniTitle)
        col.addView(miniSub)
        bar.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(10) })
        miniPlay = iconButton(R.drawable.ic_pause, 44) {
            player.playWhenReady = !player.playWhenReady
        }
        bar.addView(miniPlay)
        bar.addView(iconButton(R.drawable.ic_close, 44) { closeMini() })
        return bar
    }

    private fun showTab(t: Tab) {
        // ha épp a YouTube-felület van nyitva, a fülre koppintás visszahozza az app saját nézetét
        if (webLayer.isOpen && !webLayer.isMini) webLayer.close()
        if (searchOpen) closeSearch()
        if (tab == Tab.SHORTS && t != Tab.SHORTS) leaveShorts()
        tab = t
        if (t != Tab.SHORTS) shortsWeb.closeEmbedded()
        // Shorts alatt nincs alsó menüsor: így a videó teljes képen látszik (vissza: bal felső nyíl)
        navBar.visibility = if (t == Tab.SHORTS && webPlay()) View.GONE else View.VISIBLE
        homePage.visibility = if (t == Tab.HOME) View.VISIBLE else View.GONE
        shortsPage.visibility = if (t == Tab.SHORTS) View.VISIBLE else View.GONE
        musicPage.visibility = if (t == Tab.MUSIC) View.VISIBLE else View.GONE
        youPage.visibility = if (t == Tab.YOU) View.VISIBLE else View.GONE
        val order = listOf(Tab.HOME, Tab.SHORTS, Tab.MUSIC, Tab.YOU)
        for (i in order.indices) {
            val on = order[i] == t
            navIcons[i].setColorFilter(if (on) Color.WHITE else Palette.DIM)
            navLabels[i].setTextColor(if (on) Color.WHITE else Palette.DIM)
        }
        when (t) {
            Tab.SHORTS -> {
                // mindig a saját (natív) Shorts-lapozó: ez folyékony, akárcsak a Home lista
                if (webPlay()) closeWatchPlayback()
                if (webLayer.isOpen) webLayer.close()
                enterShorts()
            }
            Tab.MUSIC -> if (!musicLoaded) loadMusic()
            Tab.YOU -> youServer.text = if (api.configured) api.server else getString(R.string.not_set)
            else -> {}
        }
        updateMini()
    }

    // ---------------------------------------------------------------- lejátszó oldal
    private fun pill(icon: Int?, text: String, onClick: (() -> Unit)?): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(16), 0)
            background = rounded(0xFF272727.toInt(), 18f)
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        if (icon != null) box.addView(simpleIcon(icon, 24, Color.WHITE, 3))
        box.addView(label(text, 13.5f, Color.WHITE, bold = true).apply { setPadding(dp(6), 0, 0, 0) })
        return box
    }

    private fun buildWatch() {
        watch = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        playerPanel = PlayerPanel(this).apply { setBackgroundColor(Color.BLACK) }
        watchPlayerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3000
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowNextButton(false)
            setShowPreviousButton(false)
            setFullscreenButtonClickListener { setFullscreen(!fullscreen) }
        }
        playerPanel.addView(watchPlayerView, FrameLayout.LayoutParams(MATCH, MATCH))
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        watchPlayerView.overlayFrameLayout?.addView(cover, FrameLayout.LayoutParams(MATCH, MATCH))
        minimizeBtn = iconButton(R.drawable.ic_expand, 44) { minimizeWatch() }
        playerPanel.addView(minimizeBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START))
        qualityBtn = label("", 12.5f, Color.WHITE, bold = true).apply {
            background = rounded(0x99000000.toInt(), 6f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.GONE
            setOnClickListener { showQualityDialog() }
        }
        playerPanel.addView(
            qualityBtn,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) }
        )
        // a gomb a lejátszó vezérlőivel együtt jelenik meg és tűnik el
        watchPlayerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            qualityBtn.visibility = View.GONE
        })
        playerPanel.onSwipeDown = { if (!fullscreen && !inPip && watchOpen) minimizeWatch() }
        floatOverlay = View(this).apply {
            visibility = View.GONE
            isClickable = true
            setOnClickListener { restoreWatch() }
        }
        playerPanel.addView(floatOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        floatClose = iconButton(R.drawable.ic_close, 36) { closeWatchPlayback() }.apply { visibility = View.GONE }
        playerPanel.addView(floatClose, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END))
        floatPlay = iconButton(R.drawable.ic_pause, 36) { player.playWhenReady = !player.playWhenReady }.apply { visibility = View.GONE }
        playerPanel.addView(floatPlay, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.START))
        // a fogaskerék: minőség + lejátszási sebesség egy menüben
        watchPlayerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.apply {
            visibility = View.VISIBLE
            setOnClickListener { showPlayerSettings() }
        }
        watch.addView(playerPanel, LinearLayout.LayoutParams(MATCH, WRAP))

        watchScroll = RecyclerView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        wStatus = statusView().apply { setPadding(dp(16), dp(16), dp(16), dp(8)) }
        col.addView(wStatus, LinearLayout.LayoutParams(MATCH, WRAP))

        val infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        wTitle = label("", 17f, Color.WHITE, bold = true)
        wStats = label("", 13f, Palette.DIM).apply { setPadding(0, dp(6), 0, 0) }
        wDesc = label("", 13.5f, Color.WHITE).apply {
            setPadding(0, dp(10), 0, dp(4))
            visibility = View.GONE
        }
        infoBox.addView(wTitle)
        infoBox.addView(wStats)
        infoBox.addView(wDesc)
        infoBox.isClickable = true
        infoBox.setOnClickListener {
            wDesc.visibility = if (wDesc.visibility == View.VISIBLE || wDesc.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        col.addView(infoBox, LinearLayout.LayoutParams(MATCH, WRAP))

        wChannelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(10))
        }
        wAvatar = AvatarView(this, 40)
        wChannelRow.addView(wAvatar, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wChannelName = label("", 14.5f, Color.WHITE, bold = true).apply { maxLines = 1 }
        wChannelSubs = label("", 12f, Palette.DIM).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) }
        names.addView(wChannelName)
        names.addView(wChannelSubs)
        wChannelRow.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(wChannelRow, LinearLayout.LayoutParams(MATCH, WRAP))

        wActions = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(2), dp(14), dp(10))
        }
        val likesPill = pill(R.drawable.ic_like, "", null)
        wLikes = likesPill.getChildAt(1) as TextView
        actionRow.addView(likesPill, LinearLayout.LayoutParams(WRAP, dp(36)).apply { rightMargin = dp(8) })
        actionRow.addView(pill(R.drawable.ic_share, getString(R.string.share)) { shareCurrent() }, LinearLayout.LayoutParams(WRAP, dp(36)))
        wActions.addView(actionRow)
        col.addView(wActions, LinearLayout.LayoutParams(MATCH, WRAP))

        wCommentsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF1A1A1A.toInt(), 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { toggleComments() }
        }
        wCommentsHead = label(getString(R.string.comments), 14f, Color.WHITE, bold = true)
        wCommentPreview = label(getString(R.string.loading), 13f, Palette.DIM).apply { maxLines = 2; setPadding(0, dp(8), 0, 0) }
        wCommentsCard.addView(wCommentsHead)
        wCommentsCard.addView(wCommentPreview)
        col.addView(wCommentsCard, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(2), dp(12), dp(8))
        })
        wCommentsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(8))
            visibility = View.GONE
        }
        col.addView(wCommentsList, LinearLayout.LayoutParams(MATCH, WRAP))

        wRelatedTitle = label(getString(R.string.up_next), 14f, Palette.DIM, bold = true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(4))
            visibility = View.GONE
        }
        col.addView(wRelatedTitle, LinearLayout.LayoutParams(MATCH, WRAP))
        relatedAdapter = FeedAdapter(api) { item, pos ->
            val music = currentMusic
            openWatch(item, music, if (music) musicQueue(relatedItems(), pos) else emptyList())
        }
        watchScroll.setBackgroundColor(Color.BLACK)
        col.setBackgroundColor(Color.BLACK)
        watchScroll.adapter = ConcatAdapter(HeaderAdapter(col), relatedAdapter)
        watch.addView(watchScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun relatedItems(): List<Item> = if (currentMusic) queue else related

    private fun shareCurrent() {
        val item = current ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${item.id}")
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun toggleComments() {
        if (commentsData.isEmpty()) return
        commentsShown = !commentsShown
        wCommentsList.visibility = if (commentsShown) View.VISIBLE else View.GONE
        wCommentPreview.visibility = if (commentsShown) View.GONE else View.VISIBLE
    }

    private fun commentRow(c: Comment): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(6))
        }
        val avatar = AvatarView(this, 32)
        avatar.bind(c.author.removePrefix("@").ifEmpty { "?" })
        if (c.avatar.isNotEmpty()) Images.load(avatar.image, c.avatar)
        row.addView(avatar, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(12) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = listOf(c.author, c.time).filter { it.isNotEmpty() }.joinToString(" · ")
        col.addView(label(head, 12f, Palette.DIM).apply { maxLines = 1 })
        col.addView(label(c.text, 14f, Color.WHITE).apply { setPadding(0, dp(3), 0, 0) })
        if (c.likes > 0) col.addView(label("👍 " + Fmt.count(c.likes), 12f, Palette.DIM).apply { setPadding(0, dp(5), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return row
    }

    private val screenWidth get() = resources.displayMetrics.widthPixels

    /// A lejátszó mérete: normálban 16:9, teljes képernyőn / kép-a-képben a teljes nézet.
    private fun layoutPlayer() {
        // a lebegő kis ablak (kicsinyített lejátszó): a főoldalon böngészhetsz, közben megy a videó
        val small = floating && !fullscreen && !inPip
        val wlp = watch.layoutParams as FrameLayout.LayoutParams
        if (small) {
            val w = (screenWidth * 0.56f).toInt()
            wlp.width = w
            wlp.height = (w * 9f / 16f).toInt()
            wlp.gravity = Gravity.BOTTOM or Gravity.END
            wlp.setMargins(0, 0, dp(10), dp(64))
        } else {
            wlp.width = MATCH
            wlp.height = MATCH
            wlp.gravity = Gravity.NO_GRAVITY
            wlp.setMargins(0, 0, 0, 0)
        }
        watch.layoutParams = wlp
        floatOverlay.visibility = if (small) View.VISIBLE else View.GONE
        floatClose.visibility = if (small) View.VISIBLE else View.GONE
        floatPlay.visibility = if (small) View.VISIBLE else View.GONE
        floatPlay.setImageResource(if (player.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        watchPlayerView.useController = !small && !inPip
        if (small) watchPlayerView.hideController()
        playerPanel.swipeEnabled = !small && !fullscreen && !inPip
        val lp = playerPanel.layoutParams as LinearLayout.LayoutParams
        val big = (fullscreen || inPip) && !small
        playerPanel.fixedRatio = !big
        lp.height = if (big) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        playerPanel.layoutParams = lp
        watchScroll.visibility = if (big || small) View.GONE else View.VISIBLE
        minimizeBtn.visibility = if (big || small) View.GONE else View.VISIBLE
    }

    private fun updateLastCard() {
        val last = api.last()
        val showCard = last != null && category == "all"
        // a fejléc a listából is kikerül, ha nem látszik: így nincs üres hely a videók fölött
        if (showCard && !headerAttached) { homeConcat.addAdapter(0, homeHeader); headerAttached = true }
        else if (!showCard && headerAttached) { homeConcat.removeAdapter(homeHeader); headerAttached = false }
        if (last == null || !showCard) { lastCard.visibility = View.GONE; return }   // csak az „All” alatt látszik
        val (item, _, pos) = last
        lastCard.visibility = View.VISIBLE
        lastTitle.text = item.title
        lastSub.text = Fmt.subtitle(item)
        Images.load(lastThumb, item.thumbnail)
        val frac = if (item.duration > 0) (pos / (item.duration * 1000f)).coerceIn(0f, 1f) else 0f
        val lp = lastProgress.layoutParams as FrameLayout.LayoutParams
        lp.width = (screenWidth * frac).toInt()
        lastProgress.layoutParams = lp
    }

    /// Kategória-lista + elöl a követett csatornák friss, még nem látott videói.
    private fun homeItems(found: List<Item>): List<Item> {
        if (category != "all") return found   // a követett csatornák friss videói csak az „All” alatt
        val seen = api.seenIds()
        val fresh = channelNew.filter { it.id !in seen }
        val ids = fresh.map { it.id }.toSet()
        return fresh + found.filter { it.id !in ids }
    }

    private fun refreshHomeList() {
        val base = homeCache[category] ?: api.homeCached(category)
        homeAdapter.submit(homeItems(base), false)
    }

    private fun loadChannelNew() {
        val chans = api.topChannels(4)
        if (chans.isEmpty()) return
        fun merge(lists: List<List<Item>>): List<Item> {
            val seen = api.seenIds()
            val out = ArrayList<Item>()
            for (i in 0 until 2) lists.forEach { l -> l.filter { it.id !in seen }.getOrNull(i)?.let { out.add(it) } }
            return out
        }
        channelNew = merge(chans.map { api.channelVideosCached(it.first, it.second) })
        refreshHomeList()
        pool.execute {
            val lists = chans.map { runCatching { api.channelVideos(it.first, it.second) }.getOrDefault(emptyList()) }
            handler.post {
                if (lists.all { it.isEmpty() }) return@post
                channelNew = merge(lists)
                refreshHomeList()
            }
        }
    }

    private fun resumeLast() {
        val (item, music, pos) = api.last() ?: return
        openWatch(item, music, emptyList())
        resumeMs = if (pos > 5000) pos - 2000 else 0L
    }

    /// Elmentjük, mit nézünk és hol tartunk (a kezdőlapon innen folytatható).
    private fun saveLast() {
        val item = current ?: return
        val pos = if (player.currentPosition > 0) player.currentPosition else 0L
        api.saveLast(item, currentMusic, pos)
    }

    private fun webPlay() = getSharedPreferences("vidara", MODE_PRIVATE).getBoolean("webplay", true)

    private fun openWatch(item: Item, music: Boolean, queueList: List<Item>) {
        if (webPlay()) {
            // a videót a valódi YouTube-lejátszó játssza le (reklámmentesen) – gyors és megbízható
            if (shortsActive) leaveShorts()
            runCatching { player.pause() }
            api.saveLast(item, music, 0L)
            api.markSeen(item.id)
            updateLastCard()
            webLayer.open(WebTube.watchUrl(item.id, music))
            return
        }
        if (shortsActive) leaveShorts()
        if (tab == Tab.SHORTS) showTab(Tab.HOME)
        if (searchOpen) closeSearch()
        current = item
        currentMusic = music
        queue = queueList
        resumeMs = 0L
        api.saveLast(item, music, 0L)
        api.markSeen(item.id)
        updateLastCard()
        related = emptyList()
        detail = null
        retried = false
        failedHeights.clear()
        usingMuxed = false
        commentsShown = false
        commentsData = emptyList()
        watchOpen = true
        floating = false
        watch.visibility = View.VISIBLE
        watchScroll.scrollToPosition(0)
        resetWatchUi(item)
        layoutPlayer()
        updateMini()
        startVideo(item)
    }

    private fun resetWatchUi(item: Item) {
        wTitle.text = item.title
        wStats.text = if (currentMusic) item.channel else listOf(Fmt.views(item.views), Fmt.age(item.date)).filter { it.isNotEmpty() }.joinToString(" · ")
        wDesc.text = ""
        wDesc.visibility = View.GONE
        wChannelName.text = item.channel
        wChannelSubs.text = ""
        wAvatar.bind(item.channel.ifEmpty { "?" })
        wLikes.text = ""
        wCommentPreview.text = getString(R.string.loading)
        wCommentPreview.visibility = View.VISIBLE
        wCommentsHead.text = getString(R.string.comments)
        wCommentsList.removeAllViews()
        wCommentsList.visibility = View.GONE
        val video = !currentMusic
        wChannelRow.visibility = if (video) View.VISIBLE else View.GONE
        wActions.visibility = if (video) View.VISIBLE else View.GONE
        wCommentsCard.visibility = if (video) View.VISIBLE else View.GONE
        wRelatedTitle.visibility = View.GONE
        wRelatedTitle.text = getString(if (video) R.string.related else R.string.up_next)
        status(wStatus, getString(R.string.loading))
        cover.visibility = if (currentMusic) View.VISIBLE else View.GONE
        if (currentMusic) Images.load(cover, item.thumbnail)
        relatedAdapter.submit(emptyList(), currentMusic)
        if (currentMusic) {
            relatedAdapter.submit(queue, true)
            wRelatedTitle.visibility = if (queue.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun startVideo(item: Item) {
        val token = ++playToken
        pool.execute {
            val r = runCatching { api.video(item.id) }
            handler.post {
                if (token != playToken) return@post
                r.onSuccess { d -> onDetail(d, item, token) }
                    .onFailure { e -> status(wStatus, describe(e)) }
            }
        }
    }

    private fun onDetail(d: VideoDetail, item: Item, token: Int) {
        detail = d
        val music = currentMusic
        val built = buildSource(d, music, item)
        if (built == null) {
            status(wStatus, getString(R.string.no_stream))
            return
        }
        status(wStatus, "")
        cover.visibility = if (music || built.second) View.VISIBLE else View.GONE
        if (cover.visibility == View.VISIBLE) Images.load(cover, item.thumbnail)
        if (resumeMs > 0) { player.setMediaSource(built.first, resumeMs); resumeMs = 0L } else player.setMediaSource(built.first)
        player.prepare()
        player.playWhenReady = true
        updateMini()
        if (music) return
        api.noteChannel(d.channelId.ifEmpty { item.channelId }, d.channel.ifEmpty { item.channel })
        wTitle.text = d.title.ifEmpty { item.title }
        wStats.text = listOf(Fmt.views(d.views), Fmt.age(d.date)).filter { it.isNotEmpty() }.joinToString(" · ") +
            if (d.description.isNotEmpty()) getString(R.string.more) else ""
        wDesc.text = d.description
        wChannelName.text = d.channel.ifEmpty { item.channel }
        wLikes.text = if (d.likes > 0) Fmt.count(d.likes) else getString(R.string.like)
        val chId = d.channelId.ifEmpty { item.channelId }
        Channels.get(api, chId) { info ->
            if (token != playToken || info == null) return@get
            if (info.subs > 0) wChannelSubs.text = getString(R.string.subscribers, Fmt.count(info.subs))
            if (info.avatar != null) Images.load(wAvatar.image, info.avatar)
        }
        // ajánlottak és hozzászólások a háttérben
        pool.execute {
            val rel = runCatching { api.related(d.id) }
            handler.post {
                if (token != playToken) return@post
                rel.onSuccess {
                    related = it
                    prefetch(it)
                    relatedAdapter.submit(it, false)
                    wRelatedTitle.text = getString(if (it.isEmpty()) R.string.related_empty else R.string.related)
                    wRelatedTitle.visibility = View.VISIBLE
                }.onFailure { e ->
                    wRelatedTitle.text = getString(R.string.related_fail, describe(e))
                    wRelatedTitle.visibility = View.VISIBLE
                }
            }
        }
        pool.execute {
            val com = runCatching { api.comments(d.id) }
            handler.post {
                if (token != playToken) return@post
                com.onSuccess { (count, list) ->
                    commentsData = list
                    val n = if (count > 0) count else list.size.toLong()
                    wCommentsHead.text = if (n > 0) getString(R.string.comments_n, Fmt.count(n)) else getString(R.string.comments)
                    wCommentPreview.text = if (list.isEmpty()) getString(R.string.no_comments) else list[0].text
                    wCommentsList.removeAllViews()
                    list.forEach { wCommentsList.addView(commentRow(it)) }
                }.onFailure {
                    wCommentPreview.text = getString(R.string.comments_fail)
                }
            }
        }
    }

    private fun minimizeWatch() {
        saveLast()
        updateLastCard()
        loadChannelNew()
        if (fullscreen) setFullscreen(false)
        watchOpen = false
        if (!currentMusic && current != null) {
            floating = true            // videó: lebegő kis ablak
            watch.visibility = View.VISIBLE
            layoutPlayer()
        } else {
            watch.visibility = View.GONE
        }
        updateMini()
    }

    private fun restoreWatch() {
        if (current == null) return
        watchOpen = true
        floating = false
        watch.visibility = View.VISIBLE
        layoutPlayer()
        updateMini()
    }

    private fun closeWatchPlayback() {
        playToken++
        current = null
        watchOpen = false
        floating = false
        watch.visibility = View.GONE
        if (fullscreen) setFullscreen(false)
        player.pause()
        player.stop()
        player.clearMediaItems()
        updateMini()
    }

    private fun closeMini() {
        closeWatchPlayback()
    }

    private fun updateMini() {
        val item = current
        val show = item != null && !watchOpen && !floating && tab != Tab.SHORTS
        miniBar.visibility = if (show) View.VISIBLE else View.GONE
        if (item != null) {
            miniTitle.text = item.title
            miniSub.text = item.channel
            Images.load(miniThumb, item.thumbnail)
        }
        miniPlay.setImageResource(if (player.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ================================================================== adatok

    private fun status(view: TextView, text: String) {
        view.text = text
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadHome() {
        updateLastCard()
        val cat = category
        homeCache[cat]?.let {
            homeAdapter.submit(homeItems(it), false)
            status(homeStatus, "")
            return
        }
        val token = ++homeToken
        val old = api.homeCached(cat)
        if (old.isNotEmpty()) {
            homeAdapter.submit(homeItems(old), false)   // a legutóbbi lista azonnal látszik, közben frissítünk
            status(homeStatus, "")
        } else {
            homeAdapter.submit(emptyList(), false)
            status(homeStatus, getString(R.string.loading))
        }
        pool.execute {
            val r = runCatching { api.home(cat) }
            handler.post {
                if (token != homeToken || cat != category) return@post
                r.onSuccess { found ->
                    homeCache[cat] = found
                    prefetch(found)
                    homeAdapter.submit(homeItems(found), false)
                    status(homeStatus, if (found.isEmpty()) getString(R.string.no_results) else "")
                }.onFailure { e ->
                    if (old.isEmpty()) {
                        homeAdapter.submit(emptyList(), false)
                        status(homeStatus, describe(e))
                    }
                }
            }
        }
    }

    private fun loadMusic() {
        val oldMusic = api.musicHomeCached()
        if (oldMusic.isNotEmpty()) {
            musicAdapterItems = oldMusic
            musicAdapter.submit(oldMusic, true)
            status(musicStatus, "")
        } else {
            status(musicStatus, getString(R.string.loading))
        }
        pool.execute {
            val r = runCatching { api.musicHome() }
            handler.post {
                if (r.isFailure && oldMusic.isNotEmpty()) return@post
                r.onSuccess { found ->
                    musicAdapterItems = found
                    musicLoaded = found.isNotEmpty()
                    musicAdapter.submit(found, true)
                    status(musicStatus, if (found.isEmpty()) getString(R.string.no_music) else "")
                }.onFailure { e -> status(musicStatus, describe(e)) }
            }
        }
    }

    private fun describe(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            e is java.net.UnknownHostException || e is java.net.ConnectException ||
                e is java.net.SocketTimeoutException ->
                getString(R.string.err_unreachable)
            m.isNotEmpty() -> m
            else -> getString(R.string.err_unknown)
        }
    }

    // ================================================================== lejátszás

    private fun buildSource(info: VideoDetail, asMusic: Boolean, item: Item): Pair<MediaSource, Boolean>? {
        val factory = ProgressiveMediaSource.Factory(
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        )
        val meta = MediaMetadata.Builder()
            .setTitle(item.title.ifEmpty { info.title })
            .setArtist(item.channel.ifEmpty { info.channel })
            .setArtworkUri(Uri.parse(item.thumbnail.ifEmpty { info.thumbnail }))
            .build()
        fun source(url: String): MediaSource =
            factory.createMediaSource(MediaItem.Builder().setUri(url).setMediaMetadata(meta).build())

        val audios = info.streams.filter { it.kind == "audio" }
        val bestAudio = audios.filter { it.ext == "m4a" || it.ext == "mp4" }.maxByOrNull { it.bitrate }
            ?: audios.maxByOrNull { it.bitrate }
        val muxed = info.streams.filter { it.kind == "muxed" }.maxByOrNull { it.height }

        if (asMusic) {
            setHeight(0)
            if (bestAudio != null) return Pair(source(bestAudio.url), true)
            if (muxed != null) return Pair(source(muxed.url), false)
            return null
        }
        val bestVideo = pickVideo(info, qualityCap)
        if (bestVideo != null && bestAudio != null) {
            usingMuxed = false
            setHeight(bestVideo.height)
            return Pair(MergingMediaSource(source(bestVideo.url), source(bestAudio.url)), false)
        }
        if (muxed != null) { usingMuxed = true; setHeight(muxed.height); return Pair(source(muxed.url), false) }
        if (bestAudio != null) { setHeight(0); return Pair(source(bestAudio.url), true) }
        return null
    }

    private fun setHeight(h: Int) {
        currentHeight = h
        if (h > 0 && !currentMusic) {
            qualityBtn.text = heightLabel(h)
            qualityBtn.visibility = View.GONE
        } else {
            qualityBtn.visibility = View.GONE
        }
    }

    private fun heightLabel(h: Int): String = when {
        h >= 4320 -> "${h}p (8K)"
        h >= 2160 -> "${h}p (4K)"
        h >= 1440 -> "${h}p (2K)"
        else -> "${h}p"
    }

    private fun availableHeights(info: VideoDetail): List<Int> =
        info.streams.filter { it.kind == "video" && it.height > 0 }.map { it.height }.distinct().sortedDescending()

    /// A kért felbontásig a legjobb videófolyam. Azonos felbontásnál: H.264 (mindenhol megy),
    /// aztán VP9, végül AV1 (nem minden telefon játssza le).
    private fun pickVideo(info: VideoDetail, maxHeight: Int): Stream? {
        val all = info.streams.filter { it.kind == "video" && it.height in 1..maxHeight && it.height !in failedHeights }
        if (all.isEmpty()) return null
        val top = all.maxOf { it.height }
        fun rank(s: Stream): Int {
            val c = s.vcodec ?: ""
            return when {
                c.startsWith("avc1") -> 0
                c.startsWith("vp09") || c.startsWith("vp9") -> 1
                c.startsWith("av01") -> 3
                else -> 2
            }
        }
        return all.filter { it.height == top }.sortedWith(compareBy<Stream>({ rank(it) }, { -it.bitrate })).first()
    }

    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    private fun speedLabel(v: Float): String =
        if (v == 1f) getString(R.string.speed_normal) else (if (v % 1f == 0f) v.toInt().toString() else v.toString()) + "x"

    private fun showPlayerSettings() {
        val rows = ArrayList<Pair<String, () -> Unit>>()
        if (!currentMusic && detail != null && currentHeight > 0) {
            rows.add(Pair<String, () -> Unit>(getString(R.string.quality) + "  ·  " + heightLabel(currentHeight), { showQualityDialog() }))
        }
        rows.add(Pair<String, () -> Unit>(getString(R.string.speed) + "  ·  " + speedLabel(player.playbackParameters.speed), { showSpeedDialog() }))
        AlertDialog.Builder(this)
            .setItems(rows.map { it.first }.toTypedArray()) { _, which -> rows[which].second() }
            .show()
    }

    private fun showSpeedDialog() {
        val cur = speeds.indexOfFirst { it == player.playbackParameters.speed }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.speed))
            .setSingleChoiceItems(speeds.map { speedLabel(it) }.toTypedArray(), cur) { d, which ->
                player.setPlaybackSpeed(speeds[which])
                d.dismiss()
            }
            .show()
    }

    private fun showQualityDialog() {
        val d = detail ?: return
        val heights = availableHeights(d)
        if (heights.isEmpty()) return
        val labels = ArrayList<String>()
        val values = ArrayList<Int>()
        labels.add(getString(R.string.quality_auto))
        values.add(1080)
        heights.forEach { labels.add(heightLabel(it)); values.add(it) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.quality))
            .setItems(labels.toTypedArray()) { _, which ->
                api.quality = values[which]
                if (which > 0) failedHeights.remove(values[which])
                qualityCap = values[which]
                rebuildCurrent()
            }
            .show()
    }

    /// Új minőségnél ugyanonnan folytatjuk a lejátszást.
    private fun rebuildCurrent(): Boolean {
        val d = detail ?: return false
        val item = current ?: return false
        val pos = player.currentPosition
        val built = buildSource(d, currentMusic, item) ?: return false
        player.setMediaSource(built.first, pos)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED || shortsActive) return
            val list = relatedItems()
            if (current != null && list.isNotEmpty()) {
                openWatch(list[0], currentMusic, if (currentMusic) musicQueue(list, 0) else emptyList())
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePipParams()
            updateMini()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateMini()
            if (floating) floatPlay.setImageResource(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        }

        override fun onPlayerError(error: PlaybackException) {
            // A YouTube-link lejárhat: egyszer újrapróbáljuk (a szerver újra feloldja).
            if (shortsActive) {
                if (shortPos >= 0) { val p = shortPos; shortPos = -1; playShort(p) }
                return
            }
            val d = detail
            if (d != null && !currentMusic && !usingMuxed && currentHeight > 0) {
                // ez a felbontás nem játszható le (a YouTube elutasította, vagy a telefon nem bírja):
                // eggyel alacsonyabbra lépünk, végül az egybeépített formátumra
                failedHeights.add(currentHeight)
                if (rebuildCurrent()) return
            }
            val item = current
            if (!retried && item != null) {
                retried = true
                startVideo(item)
            } else {
                status(wStatus, getString(R.string.play_error, error.errorCodeName))
            }
        }
    }

    // ================================================================== teljes képernyő

    /**
     * Kijelző-orientáció: telefonon (kis legkisebb szélesség) álló marad, nagy kijelzőn
     * (tablet, hajlított telefon belső képernyője, 600 dp felett) szabadon forgatható.
     */
    private fun applyOrientation() {
        if (fullscreen) return   // teljes képernyős videónál a lejátszó dönt
        if (fsActive()) return   // a webes lejátszó saját teljes képernyője: ne kényszerítsük állóra
        val wide = resources.configuration.smallestScreenWidthDp >= 600
        val want = if (wide) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != want) runCatching { requestedOrientation = want }
        if (!wide) {
            // nagy kijelzőről összecsukva (vagy ablak módban) állóra vissza
            val p = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !fullscreen
            if (p) runCatching { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }

    /** A webes lejátszó (Vidara / VidaraMusic / Shorts) éppen teljes képernyőn van-e. */
    private fun fsActive(): Boolean =
        (::webLayer.isInitialized && webLayer.inFullscreen) ||
        (::shortsWeb.isInitialized && shortsWeb.inFullscreen)

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val controller = WindowCompat.getInsetsController(window, root)
        if (on) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            applyOrientation()
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        layoutPlayer()
    }

    @Deprecated("A vissza gomb kezelése")
    override fun onBackPressed() {
        when {
            webLayer.isFull -> webLayer.back()
            fullscreen -> setFullscreen(false)
            watchOpen -> minimizeWatch()
            searchOpen -> closeSearch()
            tab != Tab.HOME -> showTab(Tab.HOME)
            else -> moveTaskToBack(true)
        }
    }

    // ================================================================== kép a képben

    private fun canPip() = webLayer.canPip() || shortsWeb.canPip() ||
        (watchOpen || floating) && !currentMusic && player.isPlaying && !shortsActive && cover.visibility != View.VISIBLE

    private fun pipParams(): PictureInPictureParams {
        val reel = tab == Tab.SHORTS && shortsWeb.isOpen
        val size = player.videoSize
        // Shorts: álló videó -> álló kis ablak, hogy a videó kitöltse
        var ratio = if (reel) Rational(9, 16) else Rational(16, 9)
        if (!reel && !webLayer.isOpen && size.width > 0 && size.height > 0) {
            val value = size.width.toFloat() / size.height
            if (value in 0.45f..2.3f) ratio = Rational(size.width, size.height)
        }
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(webLayer.canPip() || shortsWeb.canPip() || canPip())
        return builder.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try { setPictureInPictureParams(pipParams()) } catch (_: Throwable) {}
    }

    override fun onStart() {
        super.onStart()
        webLayer.setAppBackground(false)
        shortsWeb.setAppBackground(false)
        // háttérből / PiP-ből visszatérve azonnal folytatjuk (nem kell a videóra koppintani)
        webLayer.resume()
        shortsWeb.resume()
        updatePipParams()
    }

    override fun onPause() {
        // indulás előtt frissítjük: így a Home-ra lépéskor automatikusan PiP lesz
        updatePipParams()
        if (canPip()) runCatching { webLayer.prePip() }   // automatikus PiP: a lap mérete rögzül, mielőtt az ablak összemegy
        super.onPause()
    }

    override fun onStop() {
        saveLast()
        webLayer.setAppBackground(true)
        shortsWeb.setAppBackground(true)
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canPip()) {
            try { webLayer.prePip(); enterPictureInPictureMode(pipParams()) } catch (_: Throwable) {}
        }
    }

    /// Ha a rendszer elforgatja a képet (pl. megszűnik a teljes képernyő), ne maradjon beragadva a fullscreen réteg.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
        val portrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        webLayer.syncOrientation(portrait)
        shortsWeb.syncOrientation(portrait)
        if (portrait && fullscreen) { fullscreen = false; layoutPlayer() }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        webLayer.setPip(isInPictureInPictureMode)
        shortsWeb.setPip(isInPictureInPictureMode)
        if (inPip) watchPlayerView.hideController()
        else {
            // PiP-ből vissza a rendes lejátszóra: azonnal megy tovább a videó
            webLayer.resume()
            shortsWeb.resume()
        }
        layoutPlayer()
    }

    // ================================================================== beállítások

    private fun showSettings(first: Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val serverField = EditText(this).apply {
            hint = getString(R.string.server_hint)
            setText(api.server)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        val keyField = EditText(this).apply {
            hint = getString(R.string.key_hint)
            setText(api.key)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        box.addView(serverField)
        box.addView(keyField)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_server))
            .setView(box)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                api.server = serverField.text.toString()
                api.key = keyField.text.toString()
                homeCache.clear()
                musicLoaded = false
                shortsLoaded = false
                youServer.text = api.server
                loadHome()
                if (tab == Tab.MUSIC) loadMusic()
            }
            .setNeutralButton(getString(R.string.test), null)
            .setNegativeButton(getString(if (first) R.string.later else R.string.cancel), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            api.server = serverField.text.toString()
            api.key = keyField.text.toString()
            Toast.makeText(this, getString(R.string.testing), Toast.LENGTH_SHORT).show()
            pool.execute {
                val text = try { api.diag() } catch (e: Exception) { describe(e) }
                handler.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ================================================================== életciklus

    override fun onDestroy() {
        runCatching { webLayer.destroyWeb() }
        runCatching { shortsWeb.destroyWeb() }
        handler.removeCallbacksAndMessages(null)
        player.removeListener(playerListener)
        watchPlayerView.player = null
        shortsPlayerView.player = null
        controllerFuture?.let { try { MediaController.releaseFuture(it) } catch (_: Throwable) {} }
        super.onDestroy()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val SHORTS_URL = "https://m.youtube.com/shorts"
    }
}
