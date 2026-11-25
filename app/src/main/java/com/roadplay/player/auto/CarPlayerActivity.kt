package com.roadplay.player.auto

import android.annotation.SuppressLint
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.apps.auto.sdk.CarActivity
import com.roadplay.player.R
import com.roadplay.player.RoadPlayApp
import com.roadplay.player.playback.MediaIds
import com.roadplay.player.playback.PlaybackService
import com.roadplay.player.playback.RoadPlayCommands
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * RoadPlay's own screen on the Android Auto head unit. Fermata-style:
 *
 *  - browse the phone library through the media session, play via MediaController;
 *  - one shared ExoPlayer, whose video output is handed to the car screen's
 *    SurfaceView with the SET/CLEAR_CAR_SURFACE session commands;
 *  - tap the video → full control overlay (seek, ±10 s, prev/play/next, stop);
 *    the overlay hides itself after [AppSettings.carControlsHideSeconds] of no touch;
 *  - a built-in browser with an embedded YouTube home page, like Fermata's web tab;
 *  - video keeps playing whether parked or driving (subject to what the AA host
 *    itself allows — see the "Keep video while driving" setting on the phone).
 *
 * Written fresh in Kotlin against Media3 — architecture inspired by Fermata
 * (GPLv3), no code copied.
 */
class CarPlayerActivity : CarActivity(), Player.Listener {

    companion object {
        private const val TAG = "RoadPlayCar"
        private const val NAV_BROWSER = "__browser__"
        private const val NAV_YOUTUBE = "__youtube__"
    }

    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaBrowser? = null
    private var surface: Surface? = null
    private var surfaceSent = false

    // settings (loaded in onStart)
    private var hideSeconds = 2
    private var browserHome = "https://m.youtube.com"

    // views
    private lateinit var titleView: TextView
    private lateinit var listHost: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var videoOverlay: FrameLayout
    private lateinit var videoSurfaceView: SurfaceView
    private lateinit var videoTitle: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var posLabel: TextView
    private lateinit var controlsBar: LinearLayout
    private lateinit var topBar: FrameLayout
    private lateinit var webViewPane: LinearLayout
    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var webStatus: TextView
    private lateinit var miniBar: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniPos: TextView
    private lateinit var navRail: LinearLayout
    private lateinit var heroCard: LinearLayout
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView

    /** False = browse the library (mini player); true = fullscreen video overlay. */
    private var videoExpanded = false
    private var homeNav = false
    private val navButtons = mutableListOf<Pair<String, TextView>>()
    private var heroRow: Row? = null
    private val thumbPool: java.util.concurrent.Executor = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** Back stack of (title, parentId); last = currently shown. */
    private val trail = ArrayDeque<Pair<String, String>>()
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: RowsAdapter

    private class Row(val title: String, val item: MediaItem?, val kind: Kind) {
        enum class Kind { FOLDER, VIDEO, BACK, BROWSER, YOUTUBE }
    }

    private var controlsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- browse layer ---------------------------------------------------------------
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            text = try {
                "RoadPlay v" + packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Throwable) { "RoadPlay" }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setSingleLine(true)
        }
        emptyView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA5B1"))
            textSize = 15f
            gravity = Gravity.CENTER
            text = "Starting…"
            setPadding(dp(24), dp(48), dp(24), 0)
        }
        adapter = RowsAdapter()
        listHost = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CarPlayerActivity)
            setHasFixedSize(true)
            clipToPadding = false
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        miniTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
        }
        miniPos = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA5B1"))
            textSize = 11f
            setPadding(dp(6), 0, 0, 0)
        }
        miniBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_mini)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            addView(miniTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(miniPos)
            addView(controlButton("⏯") { controller?.let { if (it.isPlaying) it.pause() else it.play() } })
            addView(
                controlButton("↗") { _ ->
                    controller?.let { m ->
                        if (MediaIds.parseVideo(m.currentMediaItem?.mediaId ?: "") != null) {
                            videoExpanded = true
                            updateVideoOverlay(m)
                        }
                    }
                },
            )
            addView(controlButton("✕") { controller?.stop() })
        }
        // ---- Home hero (Continue Watching) ------------------------------------------------
        heroTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        heroMeta = TextView(this).apply {
            setTextColor(Color.parseColor("#4FC3F7"))
            textSize = 11f
            text = "CONTINUE WATCHING"
            setPadding(0, dp(2), 0, dp(6))
        }
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_hero)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            addView(heroMeta)
            addView(heroTitle)
            addView(
                TextView(this@CarPlayerActivity).apply {
                    text = "▶ Resume"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    background = ContextCompat.getDrawable(context, R.drawable.bg_nav_selected)
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) },
            )
            setOnClickListener { heroRow?.let { r -> videoExpanded = true; playRow(r) } }
        }

        // ---- Sidebar navigation rail -------------------------------------------------------
        navRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0C0F16"))
            setPadding(dp(10), dp(14), dp(10), dp(10))
            addView(
                TextView(this@CarPlayerActivity).apply {
                    text = "▶ RoadPlay"
                    setTextColor(Color.parseColor("#4FC3F7"))
                    textSize = 17f
                    setPadding(dp(8), 0, 0, dp(2))
                },
            )
            addView(
                TextView(this@CarPlayerActivity).apply {
                    text = "Your Media, On the Road"
                    setTextColor(Color.parseColor("#9AA5B1"))
                    textSize = 10f
                    setPadding(dp(8), 0, 0, dp(6))
                },
            )
            addView(
                View(this@CarPlayerActivity).apply { setBackgroundColor(Color.parseColor("#262B36")) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(10) },
            )
        }
        fun navItem(label: String, id: String, selected: Boolean = false) {
            val item = TextView(this).apply {
                text = label
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#C7CDD8"))
                textSize = 15f
                setPadding(dp(12), dp(14), dp(12), dp(14))
                if (selected) background = ContextCompat.getDrawable(context, R.drawable.bg_nav_selected)
                setOnClickListener { navTo(id, label) }
            }
            navRail.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            navButtons.add(label to item)
        }
        navItem("Home", MediaIds.RECENT_ROOT, selected = true)
        navItem("All Videos", MediaIds.ALL)
        navItem("Favourites", MediaIds.FAVOURITES)
        navItem("Playlists", MediaIds.PLAYLISTS)
        navItem("🌐 Browser", NAV_BROWSER)
        navItem("▶ YouTube", NAV_YOUTUBE)

        val contentPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                FrameLayout(this@CarPlayerActivity).apply {
                    background = ContextCompat.getDrawable(this@CarPlayerActivity, R.drawable.bg_glass_panel)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    addView(titleView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
                },
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(10)
                    marginStart = dp(12)
                    marginEnd = dp(12)
                },
            )
            addView(
                heroCard,
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(8)
                    marginStart = dp(8)
                    marginEnd = dp(8)
                },
            )
            addView(listHost, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(emptyView, LinearLayout.LayoutParams(-1, 0, 1f))
            // Mini player floats above the AA host's own media pill.
            addView(
                miniBar,
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(72)
                    marginStart = dp(12)
                    marginEnd = dp(12)
                },
            )
        }
        val listPane = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Slightly tinted (not pure black): if the unit shows exactly this shade,
            // the activity renders but its content failed; pitch black means no draw at all.
            setBackgroundColor(Color.parseColor("#12121A"))
            addView(navRail, LinearLayout.LayoutParams(dp(190), -1))
            addView(contentPane, LinearLayout.LayoutParams(0, -1, 1f))
        }

        // ---- video layer ----------------------------------------------------------------
        videoSurfaceView = SurfaceView(this)
        videoSurfaceView.holder.addCallback(SurfaceLifecycle())

        videoTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        topBar = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@CarPlayerActivity, R.drawable.bg_glass_panel)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(videoTitle, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            addView(
                controlButton("‹") { collapseVideo() },
                FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    marginStart = dp(10)
                },
            )
            addView(
                controlButton("✕") { collapseVideo() },
                FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    marginEnd = dp(10)
                },
            )
        }

        posLabel = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        seekBar = SeekBar(this).apply {
            maxHeight = dp(8)
            progressDrawable = ContextCompat.getDrawable(context, R.drawable.bg_seek)
            thumb = ContextCompat.getDrawable(context, R.drawable.seek_thumb)
            thumbOffset = dp(-9)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        controller?.let { c ->
                            val dur = c.duration.coerceAtLeast(0)
                            if (dur > 0) c.seekTo(progress.toLong() * dur / 100)
                        }
                    }
                    scheduleHide()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = scheduleHide()
                override fun onStopTrackingTouch(sb: SeekBar?) = scheduleHide()
            })
        }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(2))
            addView(seekBar, LinearLayout.LayoutParams(-1, -2))
            addView(posLabel, LinearLayout.LayoutParams(-1, -2))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(10))
            gravity = Gravity.CENTER
            // equal-weight buttons so nothing ever overlaps
            addButton("⏮") { controller?.seekToPreviousMediaItem() }
            addButton("⏪") { controller?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } }
            addButton("⏯") { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
            addButton("⏩") { controller?.let { it.seekTo(it.currentPosition + 10_000) } }
            addButton("⏭") { controller?.seekToNextMediaItem() }
        }

        val bottomControls = LinearLayout(this@CarPlayerActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(seekRow, LinearLayout.LayoutParams(-1, -2))
            addView(btnRow, LinearLayout.LayoutParams(-1, -2))
        }
        bottomControls.background =
            ContextCompat.getDrawable(this@CarPlayerActivity, R.drawable.bg_glass_panel)
        controlsBar = bottomControls
        videoOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            // tap anywhere = show controls; second tap while visible = also refresh timer
            setOnClickListener { showControls() }
            addView(videoSurfaceView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(
                topBar,
                FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                    topMargin = dp(8)
                },
            )
            addView(
                bottomControls,
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    marginStart = dp(10)
                    marginEnd = dp(10)
                    bottomMargin = dp(14)
                },
            )
        }

        // ---- web layer ------------------------------------------------------------------
        urlBar = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#9AA5B1"))
            setHint("Search or type URL")
            imeOptions = EditorInfo.IME_ACTION_GO
            background = ContextCompat.getDrawable(this@CarPlayerActivity, R.drawable.bg_url_pill)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextSize(13f)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { openUrl(text.toString()); true } else false
            }
        }
        val urlPane = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            addView(controlButton("‹") { if (webView.canGoBack()) webView.goBack() else closeBrowser() })
            addView(urlBar, LinearLayout.LayoutParams(0, -2, 1f))
            addView(controlButton("⌂") { openUrl(browserHome) })
            addView(controlButton("⟳") { webView.reload() })
            addView(controlButton("✕") { closeBrowser() })
        }
        webStatus = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(dp(12), dp(2), dp(12), dp(2))
            visibility = View.GONE
        }
        @SuppressLint("SetJavaScriptEnabled")
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        Log.e(TAG, "web error: ${error.description}")
                        webStatus.text = "Load error: ${error.description}"
                        webStatus.visibility = View.VISIBLE
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    webStatus.visibility = View.GONE
                    urlBar.setText(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        webStatus.text = "Loading $newProgress%…"
                        webStatus.visibility = View.VISIBLE
                    } else webStatus.visibility = View.GONE
                }

                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    Log.i(TAG, "web console: ${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
                    return true
                }
            }
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { v, ev -> scheduleHide(); v.performClick(); false }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webViewPane = LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            addView(urlPane, LinearLayout.LayoutParams(-1, -2))
            addView(webStatus, LinearLayout.LayoutParams(-1, -2))
            addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(listPane, FrameLayout.LayoutParams(-1, -1))
            addView(webViewPane, FrameLayout.LayoutParams(-1, -1))
            addView(videoOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        listHost.adapter = adapter
        trail.addLast(rootTitle() to MediaIds.ROOT)
    }

    private fun rootTitle(): String = try {
        "RoadPlay v" + packageManager.getPackageInfo(packageName, 0).versionName
    } catch (_: Throwable) { "RoadPlay" }

    /** Uniform control chip: circular glass button with a blue focus/press glow. */
    private fun controlButton(glyph: String, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            minWidth = dp(56)
            minHeight = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            isFocusable = true
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    ContextCompat.getDrawable(context, R.drawable.bg_glass_btn_focus),
                )
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    ContextCompat.getDrawable(context, R.drawable.bg_glass_btn_focus),
                )
                addState(intArrayOf(), ContextCompat.getDrawable(context, R.drawable.bg_glass_btn))
            }
            setOnClickListener(onClick)
        }

    private fun LinearLayout.addButton(glyph: String, onClick: () -> Unit) {
        addView(controlButton(glyph) { onClick(); scheduleHide() },
            LinearLayout.LayoutParams(0, -2, 1f))
    }

    // ---- Settings -------------------------------------------------------------------------

    private fun loadSettings() {
        RoadPlayApp.instance.container.appScope.launch {
            try {
                val s = RoadPlayApp.instance.container.settings.settings.first()
                hideSeconds = s.carControlsHideSeconds.coerceIn(1, 10)
                browserHome = s.browserHomeUrl
                Log.i(TAG, "settings: hide=${hideSeconds}s home=$browserHome")
            } catch (t: Throwable) {
                Log.e(TAG, "settings load failed", t)
            }
        }
    }

    // ---- Lifecycle ------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart")
        // Library-first: every entry opens at the root, even if a movie auto-resumes.
        videoExpanded = false
        trail.clear()
        trail.addLast(rootTitle() to MediaIds.ROOT)
        loadSettings()
        connect()
    }

    override fun onStop() {
        Log.i(TAG, "onStop")
        main.removeCallbacks(hideRun)
        main.removeCallbacks(positionRun)
        webView.onPause()
        controller?.let { c ->
            c.removeListener(this)
            clearCarSurface(c)
            c.release()
        }
        controller = null
        surfaceSent = false
        surface = null
        super.onStop()
    }

    // ---- Connection -----------------------------------------------------------------------

    private fun connect() {
        Log.i(TAG, "connect: building MediaBrowser")
        emptyView.text = "Connecting to player…"
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaBrowser.Builder(this, token).buildAsync()
        future.addListener({
            try {
                val c: MediaBrowser = future.get()
                controller = c
                Log.i(TAG, "connect: connected")
                emptyView.text = "Connected — loading library…"
                c.addListener(this)
                navTo(MediaIds.RECENT_ROOT, "Home")
                updateVideoOverlay(c)
                main.post(positionRun)
            } catch (t: Throwable) {
                Log.e(TAG, "connect: FAILED", t)
                showEmpty("Player connect failed: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Browsing -------------------------------------------------------------------------

    private var browsePending = false

    private fun show(parentId: String, title: String) {
        webViewPane.visibility = View.GONE
        listHost.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        val c = controller ?: return
        Log.i(TAG, "show: requesting children of $parentId")
        highlightNav(title)
        browsePending = true
        // Watchdog: if the service never answers, say so on the head unit.
        main.postDelayed({
            if (browsePending) {
                Log.w(TAG, "show($parentId) TIMED OUT — getChildren never answered")
                showEmpty("Library did not respond (5s timeout)")
            }
        }, 5000)
        val future = c.getChildren(parentId, 0, Int.MAX_VALUE, null)
        future.addListener({
            try {
                val children = future.get().value.orEmpty()
                browsePending = false
                Log.i(TAG, "show($parentId): ${children.size} children")
                rows.clear()
                if (parentId != MediaIds.ROOT && !homeNav) rows.add(Row("‹ Back", null, Row.Kind.BACK))
                for (item in children) {
                    rows.add(
                        Row(
                            title = item.mediaMetadata.title?.toString() ?: "Untitled",
                            item = item,
                            kind = if (item.mediaMetadata.isBrowsable == true) Row.Kind.FOLDER else Row.Kind.VIDEO,
                        ),
                    )
                }
                if (parentId == MediaIds.ROOT) {
                    rows.add(Row("🌐 Browser", null, Row.Kind.BROWSER))
                    rows.add(Row("▶ YouTube", null, Row.Kind.YOUTUBE))
                }
                titleView.text = title
                if (homeNav) {
                    val hr = rows.firstOrNull { it.kind == Row.Kind.VIDEO }
                    heroRow = hr
                    if (hr != null) {
                        heroTitle.text = hr.title
                        heroCard.visibility = View.VISIBLE
                    } else heroCard.visibility = View.GONE
                    if (rows.isEmpty()) emptyView.text = "Nothing watched yet — open All Videos"
                } else heroCard.visibility = View.GONE
                if (rows.isEmpty() && !homeNav) emptyView.text = "No items in $title"
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                listHost.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                adapter.notifyDataSetChanged()
            } catch (t: Throwable) {
                Log.e(TAG, "show($parentId) FAILED", t)
                browsePending = false
                showEmpty("Browse failed: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun open(row: Row) {
        when {
            row.kind == Row.Kind.BACK -> {
                if (trail.size > 1) {
                    trail.removeLastOrNull()
                    val (t, id) = trail.last()
                    show(id, t)
                } else onBackPressed()
            }
            row.kind == Row.Kind.BROWSER -> openBrowser(browserHome)
            row.kind == Row.Kind.YOUTUBE -> openBrowser("https://m.youtube.com")
            row.kind == Row.Kind.FOLDER -> row.item?.mediaId?.let {
                trail.addLast(row.title to it)
                show(it, row.title)
            }
            else -> controller?.let { playRow(row) }
        }
    }

    // ---- Built-in browser -----------------------------------------------------------------

    private fun openBrowser(url: String) {
        webViewPane.visibility = View.VISIBLE
        listHost.visibility = View.GONE
        emptyView.visibility = View.GONE
        titleView.visibility = View.GONE
        urlBar.setText(url)
        webView.loadUrl(url)
        scheduleHide()
    }

    private fun openUrl(raw: String) {
        val typed = raw.trim()
        if (typed.isEmpty()) return
        val url = when {
            typed.startsWith("http://") || typed.startsWith("https://") -> typed
            typed.contains(" ") || !typed.contains(".") ->
                "https://www.google.com/search?q=" + java.net.URLEncoder.encode(typed, "UTF-8")
            else -> "https://$typed"
        }
        urlBar.setText(url)
        webView.loadUrl(url)
    }

    private fun closeBrowser() {
        webView.onPause()
        webViewPane.visibility = View.GONE
        titleView.visibility = View.VISIBLE
        listHost.visibility = View.VISIBLE
    }

    // ---- Video surface handoff ------------------------------------------------------------

    private inner class SurfaceLifecycle : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceCreated")
            surface = holder.surface ?: return
            controller?.let { sendCarSurface(it) }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(TAG, "surfaceChanged ${width}x$height")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceDestroyed")
            surface = null
            controller?.let { clearCarSurface(it) }
        }
    }

    private fun updateVideoOverlay(c: MediaBrowser) {
        val video = c.videoSize.width > 0
        val mime = c.currentMediaItem?.localConfiguration?.mimeType
        // Detect our library videos by mediaId scheme — mimeType may be null until
        // the item is re-resolved, so it cannot be the only signal.
        val ourVideo = c.currentMediaItem?.mediaId?.let { MediaIds.parseVideo(it) != null } == true
        val videoItem = video || mime?.startsWith("video") == true || ourVideo
        Log.i(
            TAG,
            "updateVideoOverlay: playing=${c.isPlaying} video=$video mime=$mime ourVideo=$ourVideo expanded=$videoExpanded",
        )
        val wasVisible = videoOverlay.visibility == View.VISIBLE
        val expanded = videoExpanded && videoItem
        videoOverlay.visibility = if (expanded) View.VISIBLE else View.GONE
        // Library-first: while browsing, a playing video lives in the mini bar.
        miniBar.visibility = if (videoItem && !expanded) View.VISIBLE else View.GONE
        miniTitle.text = c.currentMediaItem?.mediaMetadata?.title ?: ""
        if (expanded) {
            // Attach the car surface FIRST — ExoPlayer only decodes/reports video
            // once it has an output surface. Waiting for videoSize deadlocks.
            surface?.let { if (!surfaceSent) sendCarSurface(c) }
            if (!wasVisible) showControls()
        } else {
            if (surfaceSent) clearCarSurface(c)
        }
    }

    /** Collapse the fullscreen video back to the library; playback keeps running. */
    private fun collapseVideo() {
        Log.i(TAG, "collapseVideo → library (playback continues)")
        videoExpanded = false
        main.removeCallbacks(hideRun)
        controller?.let { updateVideoOverlay(it) }
    }

    /** Sidebar navigation: switch the content pane and highlight the rail. */
    private fun navTo(id: String, label: String) {
        Log.i(TAG, "navTo $label ($id)")
        homeNav = id == MediaIds.RECENT_ROOT
        highlightNav(label)
        trail.clear()
        trail.addLast(label to id)
        when (id) {
            NAV_BROWSER -> openBrowser(browserHome)
            NAV_YOUTUBE -> openBrowser("https://m.youtube.com")
            else -> show(id, label)
        }
    }

    private fun highlightNav(label: String) {
        // Unknown labels (folder titles) keep the current selection instead of clearing it.
        if (navButtons.none { it.first == label }) return
        for ((name, view) in navButtons) {
            val selected = name == label
            view.background =
                if (selected) ContextCompat.getDrawable(this, R.drawable.bg_nav_selected) else null
            view.setTextColor(if (selected) Color.WHITE else Color.parseColor("#C7CDD8"))
        }
    }

    private fun playRow(r: Row) {
        controller?.let { c ->
            r.item?.let {
                videoExpanded = true
                c.setMediaItem(it)
                c.prepare()
                c.play()
            }
        }
    }

    private fun sendCarSurface(c: MediaBrowser) {
        val s = surface ?: return
        val args = Bundle().apply { putParcelable(RoadPlayCommands.ARG_SURFACE, s) }
        Log.i(TAG, "sendCarSurface")
        c.sendCustomCommand(SessionCommand(RoadPlayCommands.SET_CAR_SURFACE, Bundle.EMPTY), args)
        surfaceSent = true
    }

    private fun clearCarSurface(c: MediaBrowser) {
        if (!surfaceSent) return
        Log.i(TAG, "clearCarSurface")
        surfaceSent = false
        c.sendCustomCommand(SessionCommand(RoadPlayCommands.CLEAR_CAR_SURFACE, Bundle.EMPTY), Bundle.EMPTY)
    }

    // ---- Controls auto-hide ----------------------------------------------------------------

    private val hideRun = Runnable {
        if (controlsVisible) {
            controlsVisible = false
            controlsBar.visibility = View.GONE
            seekBar.visibility = View.GONE
            posLabel.visibility = View.GONE
            topBar.visibility = View.GONE
        }
    }

    private fun showControls() {
        controlsVisible = true
        controlsBar.visibility = View.VISIBLE
        seekBar.visibility = View.VISIBLE
        posLabel.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
        videoTitle.text = controller?.currentMediaItem?.mediaMetadata?.title ?: ""
        scheduleHide()
        tick()
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideRun)
        main.postDelayed(hideRun, hideSeconds * 1000L)
    }

    private val positionRun = object : Runnable {
        override fun run() {
            val c = controller ?: return
            val dur = c.duration.coerceAtLeast(0)
            if (controlsVisible && videoOverlay.visibility == View.VISIBLE) {
                if (dur > 0) seekBar.progress = ((c.currentPosition * 100) / dur).toInt()
                posLabel.text = "${fmt(c.currentPosition)} / ${fmt(dur)}"
            }
            if (miniBar.visibility == View.VISIBLE) {
                miniPos.text = "${fmt(c.currentPosition)} / ${fmt(dur)}"
            }
            main.postDelayed(this, 500)
        }
    }

    private fun tick() {
        main.removeCallbacks(positionRun)
        main.postDelayed(positionRun, 300)
    }

    private fun fmt(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.size() == 0) return
        updateVideoOverlay(player as MediaBrowser)
        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            videoTitle.text = player.currentMediaItem?.mediaMetadata?.title ?: ""
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        main.post { fitSurface(videoSize) }
    }

    /** Keep the movie's aspect ratio on the ultrawide head unit — no stretch, no crop. */
    private fun fitSurface(vs: VideoSize) {
        if (vs.width == 0 || vs.height == 0) return
        val w = videoOverlay.width
        val h = videoOverlay.height
        if (w == 0 || h == 0) {
            main.postDelayed({ fitSurface(vs) }, 100)
            return
        }
        val scale = minOf(w.toFloat() / vs.width, h.toFloat() / vs.height)
        videoSurfaceView.layoutParams = videoSurfaceView.layoutParams.apply {
            width = (vs.width * scale).toInt().coerceAtLeast(1)
            height = (vs.height * scale).toInt().coerceAtLeast(1)
        }
        Log.i(
            TAG,
            "fitSurface ${vs.width}x${vs.height} -> ${videoSurfaceView.layoutParams.width}x${videoSurfaceView.layoutParams.height}",
        )
    }

    // ---- Keys ------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (videoOverlay.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    onBackPressed()
                    return true
                }
            }
        }
        if (webViewPane.visibility == View.VISIBLE && keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) webView.goBack() else closeBrowser()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        when {
            videoOverlay.visibility == View.VISIBLE -> collapseVideo()
            webViewPane.visibility == View.VISIBLE -> {
                if (webView.canGoBack()) webView.goBack() else closeBrowser()
            }
            trail.size > 1 -> {
                trail.removeLastOrNull()
                val (t, id) = trail.last()
                show(id, t)
            }
            else -> super.onBackPressed()
        }
    }

    // ---- List UI ---------------------------------------------------------------------------

    private fun showEmpty(message: String) {
        rows.clear()
        titleView.text = "RoadPlay"
        emptyView.text = message
        emptyView.visibility = View.VISIBLE
        listHost.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RowHolder(makeRow())
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(rows[position])
    }

    private fun makeRow(): LinearLayout {
        val thumb = ImageView(this).apply {
            val lp = LinearLayout.LayoutParams(dp(112), dp(63))
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.marginEnd = dp(12)
            layoutParams = lp
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ContextCompat.getDrawable(context, R.drawable.bg_card)
            clipToOutline = true
            visibility = View.GONE
        }
        val icon = ImageView(this).apply {
            val lp = LinearLayout.LayoutParams(dp(24), dp(24))
            lp.gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp
        }
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(12), 0, 0, 0)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp
        }
        val highlight = ColorDrawable(Color.argb(60, 255, 255, 255))
        val transparent = ColorDrawable(Color.TRANSPARENT)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Card-style rows: rounded card + inner padding, spaced by RecyclerView margins.
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ContextCompat.getDrawable(context, R.drawable.bg_nav_selected_focus))
                addState(intArrayOf(), ContextCompat.getDrawable(context, R.drawable.bg_row_card))
            }
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                setMargins(dp(0), 0, dp(0), dp(8))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minimumHeight = dp(80)
            isClickable = true
            isFocusable = true
            addView(thumb)
            addView(icon)
            addView(label)
        }
    }

    private inner class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val thumb = row.getChildAt(0) as ImageView
        private val icon = row.getChildAt(1) as ImageView
        private val label = row.getChildAt(2) as TextView
        private lateinit var bound: Row

        init {
            row.setOnClickListener { open(bound) }
        }

        fun bind(r: Row) {
            bound = r
            label.text = r.title
            val videoId = r.item?.mediaId?.let { MediaIds.parseVideo(it) }
            if (videoId != null) {
                icon.visibility = View.GONE
                thumb.visibility = View.VISIBLE
                thumb.tag = videoId
                thumbPool.execute {
                    val bmp = loadThumb(videoId, r.item)
                    main.post {
                        if (thumb.tag != videoId) return@post
                        thumb.setImageBitmap(bmp)
                        thumb.visibility = if (bmp == null) View.GONE else View.VISIBLE
                        icon.visibility = if (bmp == null) View.VISIBLE else View.GONE
                        icon.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
            } else {
                thumb.tag = null
                thumb.visibility = View.GONE
                icon.visibility = View.VISIBLE
                icon.setImageResource(
                    when (r.kind) {
                        Row.Kind.BROWSER -> android.R.drawable.ic_menu_compass
                        Row.Kind.YOUTUBE -> android.R.drawable.ic_media_play
                        Row.Kind.FOLDER, Row.Kind.BACK -> android.R.drawable.ic_menu_agenda
                        else -> android.R.drawable.ic_media_play
                    },
                )
            }
        }
    }

    /** Frame extraction for list thumbnails, cached on disk as thumbs/<videoId>.jpg. */
    private fun loadThumb(videoId: Long, item: MediaItem?): android.graphics.Bitmap? {
        val cache = java.io.File(filesDir, "thumbs/$videoId.jpg")
        if (cache.exists()) return android.graphics.BitmapFactory.decodeFile(cache.absolutePath)
        val uri = item?.localConfiguration?.uri ?: return null
        return try {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            val bmp = mmr.getFrameAtTime(1_000_000L)
            mmr.release()
            if (bmp != null) {
                cache.parentFile?.mkdirs()
                java.io.FileOutputStream(cache).use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, it)
                }
            }
            bmp
        } catch (t: Throwable) {
            Log.w(TAG, "thumb $videoId failed: ${t.message}")
            null
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
