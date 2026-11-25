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
    private lateinit var miniThumb: ImageView
    private var playPauseButton: ImageView? = null
    private var lastMiniThumbId: Long = -1
    private var retryFired = false
    private lateinit var keyboardPane: LinearLayout
    private var keyboardToWebView = false

    /** False = browse the library (mini player); true = fullscreen video overlay. */
    private var videoExpanded = false
    private var homeNav = false
    private val navButtons = mutableListOf<Pair<String, LinearLayout>>()
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
            setPadding(dp(10), dp(10), dp(10), dp(10))
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(this@CarPlayerActivity).apply { miniThumb = this }.apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ContextCompat.getDrawable(context, R.drawable.bg_card)
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(36)).apply { marginEnd = dp(10) }
                },
            )
            addView(miniTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(miniPos)
            playPauseButton = iconButton(R.drawable.ic_pause, 24) { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
            addView(playPauseButton)
            addView(
                iconButton(R.drawable.ic_open_in_full, 18) { _ ->
                    videoExpanded = true
                    controller?.let { updateVideoOverlay(it) }
                },
            )
            addView(iconButton(R.drawable.ic_close, 20) { controller?.stop() })
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
        navRail = LinearLayout(this@CarPlayerActivity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070D18"))
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
        fun navItem(label: String, id: String, iconRes: Int, bgRes: Int, selected: Boolean = false) {
            val item = LinearLayout(this@CarPlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(14), dp(12), dp(14))
                // Every category carries its own colour card (per the mockup image);
                // selection adds a white stroke via the foreground layer.
                background = ContextCompat.getDrawable(context, bgRes)
                if (selected) foreground = ContextCompat.getDrawable(context, R.drawable.bg_nav_stroke)
                isClickable = true
                isFocusable = true
                tag = bgRes
                addView(
                    ImageView(this@CarPlayerActivity).apply {
                        setImageResource(iconRes)
                        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) }
                    },
                )
                addView(
                    TextView(this@CarPlayerActivity).apply {
                        text = label
                        setTextColor(Color.WHITE)
                        textSize = 15f
                    },
                )
                setOnClickListener { navTo(id, label) }
            }
            navRail.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
                marginStart = dp(2)
                marginEnd = dp(2)
            })
            navButtons.add(label to item)
        }
        navItem("Home", MediaIds.RECENT_ROOT, R.drawable.ic_home, R.drawable.bg_row_card, selected = true)
        navItem("All Videos", MediaIds.ALL, R.drawable.ic_movie, R.drawable.bg_nav_videos)
        navItem("Favourites", MediaIds.FAVOURITES, R.drawable.ic_star, R.drawable.bg_nav_fav)
        navItem("Playlists", MediaIds.PLAYLISTS, R.drawable.ic_playlist_play, R.drawable.bg_nav_playlists)
        navItem("🌐 Browser", NAV_BROWSER, R.drawable.ic_public, R.drawable.bg_nav_browser)
        navItem("▶ YouTube", NAV_YOUTUBE, R.drawable.ic_smart_display, R.drawable.bg_nav_youtube)

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
            // Sits low on the head unit; leaves room for the AA host's media pill.
            addView(
                miniBar,
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(56)
                    marginStart = dp(12)
                    marginEnd = dp(12)
                },
            )
        }
        val listPane = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Deep navy gradient from the mockup reference.
            background = ContextCompat.getDrawable(this@CarPlayerActivity, R.drawable.bg_root)
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
            addView(
                videoTitle,
                FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                    marginStart = dp(120)
                    marginEnd = dp(120)
                },
            )
            addView(
                iconButton(R.drawable.ic_arrow_back, 22) { collapseVideo() },
                FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    marginStart = dp(10)
                },
            )
            addView(
                iconButton(R.drawable.ic_close, 20) { collapseVideo() },
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
            addButton(R.drawable.ic_skip_previous) { controller?.seekToPreviousMediaItem() }
            addButton(R.drawable.ic_replay_10) { controller?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } }
            addButton(R.drawable.ic_play_arrow, emphasized = true) { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
            addButton(R.drawable.ic_forward_10) { controller?.let { it.seekTo(it.currentPosition + 10_000) } }
            addButton(R.drawable.ic_skip_next) { controller?.seekToNextMediaItem() }
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
            addView(iconButton(R.drawable.ic_arrow_back, 22) { if (webView.canGoBack()) webView.goBack() else closeBrowser() })
            addView(urlBar, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_home, 20) { openUrl(browserHome) })
            addView(iconButton(R.drawable.ic_refresh, 20) { webView.reload() })
            addView(iconButton(R.drawable.ic_close, 20) { closeBrowser() })
            addView(iconButton(R.drawable.ic_playlist_play, 20) { toggleKeyboard() })
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
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(
                object : Any() {
                    @android.webkit.JavascriptInterface
                    fun onFieldFocus() { main.post { keyboardToWebView = true } }

                    @android.webkit.JavascriptInterface
                    fun onFieldBlur() { main.post { keyboardToWebView = false } }
                },
                "rpkb",
            )
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        Log.e(TAG, "web error: ${error.description}")
                        webStatus.text = "Load error: ${error.description}"
                        webStatus.visibility = View.VISIBLE
                        // POST-replay quirk: one automatic retry usually clears it.
                        if ((error.description ?: "").contains("CACHE_MISS", ignoreCase = true) && !retryFired) {
                            retryFired = true
                            view.reload()
                        }
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    webStatus.visibility = View.GONE
                    urlBar.setText(url)
                    // Track in-page text fields so the car keyboard types into THEM
                    // (e.g. YouTube's own search box), not the URL bar.
                    view.evaluateJavascript(
                        "(function(){function r(e){var t=e.target;var tag=(t&&t.tagName||'').toUpperCase();if(tag==='INPUT'||tag==='TEXTAREA'||(t&&t.isContentEditable)){try{rpkb.onFieldFocus();}catch(x){}}else{try{rpkb.onFieldBlur();}catch(x){}}}document.addEventListener('focusin',r,true);document.addEventListener('focusout',r,true);})()",
                        null,
                    )
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
            addView(buildKeyboard(), LinearLayout.LayoutParams(-1, -2))
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

    /** Uniform icon button: Material vector glyph on a circular glass disc, blue focus glow. */
    private fun iconButton(iconRes: Int, size: Int = 24, onClick: (View) -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(48)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            setPadding(dp((52 - size) / 2 - 4), dp((48 - size) / 2 - 2), dp((52 - size) / 2 - 4), dp((48 - size) / 2 - 2))
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

    /** Big round accent play/pause button for the video controls row. */
    private fun playButton(iconRes: Int, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(60)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(10)
                marginEnd = dp(10)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
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
                addState(intArrayOf(), ContextCompat.getDrawable(context, R.drawable.bg_nav_selected))
            }
            setOnClickListener { onClick(); scheduleHide() }
        }

    private fun LinearLayout.addButton(iconRes: Int, emphasized: Boolean = false, onClick: () -> Unit) {
        val btn = if (emphasized) playButton(iconRes) { onClick() } else iconButton(iconRes, 26) { onClick(); scheduleHide() }
        if (emphasized) {
            playPauseButton = btn
        }
        addView(btn)
    }

    // ---- Settings -------------------------------------------------------------------------

    private fun loadSettings() {
        RoadPlayApp.instance.container.appScope.launch {
            try {
                val s = RoadPlayApp.instance.container.settings.settings.first()
                hideSeconds = s.carControlsHideSeconds.coerceIn(1, 10)
                browserHome = s.browserHomeUrl
                fontScale = s.carFontScale.coerceIn(0.8f, 1.6f)
                applyTextScale()
                Log.i(TAG, "settings: hide=${hideSeconds}s home=$browserHome font=$fontScale")
            } catch (t: Throwable) {
                Log.e(TAG, "settings load failed", t)
            }
        }
    }

    /** Car screen font scale (set from the phone app). */
    private var fontScale = 1.15f

    private fun applyTextScale() {
        titleView.textSize = 18f * fontScale
        videoTitle.textSize = 15f * fontScale
        miniTitle.textSize = 13f * fontScale
        miniPos.textSize = 11f * fontScale
        posLabel.textSize = 11f * fontScale
        heroTitle.textSize = 18f * fontScale
        emptyView.textSize = 15f * fontScale
        for ((_, view) in navButtons) {
            (view.getChildAt(1) as TextView).textSize = 15f * fontScale
        }
        adapter.notifyDataSetChanged()
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

    /** On-screen keyboard for the car screen (the AA host never shows the system IME). */
    private fun buildKeyboard(): LinearLayout {
        fun key(label: String, widthDp: Int = 50): TextView = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_row_card)
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(46)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { insertKeyboardText(label) }
        }
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@CarPlayerActivity, R.drawable.bg_mini)
            setPadding(dp(8), dp(6), dp(8), dp(10))
            visibility = View.GONE
        }
        val rows = listOf("1234567890", "qwertyuiop", "asdfghjkl:", "zxcvbnm.-/")
        for (r in rows) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            for (ch in r) row.addView(key(ch.toString()))
            pane.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        val lastRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val backKey = TextView(this).apply {
            text = "⌫"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_row_card)
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(46)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { backspaceKeyboard() }
        }
        val spaceKey = key("space", 150).apply { text = "" }
        val dotComKey = key(".com", 70)
        val goKey = TextView(this).apply {
            text = "GO"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_nav_selected)
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(46)).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { submitKeyboardGo() }
        }
        lastRow.addView(backKey)
        lastRow.addView(spaceKey)
        lastRow.addView(dotComKey)
        lastRow.addView(goKey)
        pane.addView(lastRow, LinearLayout.LayoutParams(-1, -2))
        keyboardPane = pane
        return pane
    }

    private fun toggleKeyboard() {
        val showing = keyboardPane.visibility == View.VISIBLE
        keyboardPane.visibility = if (showing) View.GONE else View.VISIBLE
        if (!showing) {
            // Also offer the phone's own keyboard — AA hosts don't show the system
            // IME on the car display automatically, so ask for it explicitly.
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                urlBar.requestFocus()
                imm.showSoftInput(urlBar, 0)
            } catch (t: Throwable) { /* IME unavailable on this display — the built-in keyboard covers it. */ }
        }
    }

    /** Route keyboard input: in-page field (YouTube search etc.) or the URL bar. */
    private fun insertKeyboardText(s: String) {
        if (keyboardToWebView) {
            val lit = org.json.JSONObject.quote(s)
            webView.evaluateJavascript(
                "(function(){var el=document.activeElement;if(!el||el.value===undefined)return;" +
                    "var st=(typeof el.selectionStart==='number')?el.selectionStart:el.value.length;" +
                    "var se=(typeof el.selectionEnd==='number')?el.selectionEnd:st;" +
                    "el.value=el.value.slice(0,st)+$lit+el.value.slice(se);" +
                    "try{el.selectionStart=el.selectionEnd=st+1;}catch(e){}" +
                    "el.dispatchEvent(new Event('input',{bubbles:true}));el.focus();})()",
                null,
            )
        } else {
            val start = urlBar.selectionStart.coerceIn(0, urlBar.text.length)
            urlBar.text.insert(start, s)
            urlBar.setSelection(start + s.length)
        }
        scheduleHide()
    }

    private fun backspaceKeyboard() {
        if (keyboardToWebView) {
            webView.evaluateJavascript(
                "(function(){var el=document.activeElement;if(!el||el.value===undefined)return;" +
                    "var st=(typeof el.selectionStart==='number')?el.selectionStart:el.value.length;" +
                    "var se=(typeof el.selectionEnd==='number')?el.selectionEnd:st;" +
                    "if(st>0&&st===se){st--;}" +
                    "el.value=el.value.slice(0,st)+el.value.slice(se);" +
                    "try{el.selectionStart=el.selectionEnd=st;}catch(e){}" +
                    "el.dispatchEvent(new Event('input',{bubbles:true}));el.focus();})()",
                null,
            )
        } else {
            val start = urlBar.selectionStart
            if (start > 0 && urlBar.text.isNotEmpty()) {
                urlBar.text.delete(start - 1, start)
                urlBar.setSelection(start - 1)
            }
        }
        scheduleHide()
    }

    private fun submitKeyboardGo() {
        if (keyboardToWebView) {
            webView.evaluateJavascript(
                "(function(){var el=document.activeElement;var q=(el&&el.value)?el.value:'';" +
                    "var isYT=location.host.indexOf('youtube')>=0;" +
                    "if(isYT&&q){window.location='/results?search_query='+encodeURIComponent(q);}" +
                    "else if(el&&el.form){try{el.form.submit();}catch(e){}}})()",
                null,
            )
            hideKeyboard()
        } else {
            openUrl(urlBar.text.toString())
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        keyboardPane.visibility = View.GONE
    }

    private fun openBrowser(url: String) {
        retryFired = false
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
        hideKeyboard()
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
            // Colour card stays; selection shows as a white stroke overlay.
            view.background = ContextCompat.getDrawable(this, view.tag as Int)
            view.foreground =
                if (selected) ContextCompat.getDrawable(this, R.drawable.bg_nav_stroke) else null
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
                val cur = c.currentMediaItem?.mediaId
                val vid = cur?.let { MediaIds.parseVideo(it) }
                if (vid != null && vid != lastMiniThumbId) {
                    lastMiniThumbId = vid
                    val item = c.currentMediaItem
                    thumbPool.execute {
                        val bmp = loadThumb(vid, item)
                        main.post { if (lastMiniThumbId == vid) miniThumb.setImageBitmap(bmp) }
                    }
                }
            }
            val pp = playPauseButton
            if (pp != null) {
                val want = if (c.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                if (pp.tag != want) { pp.tag = want; pp.setImageResource(want) }
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
            val lp = LinearLayout.LayoutParams(dp(72), dp(40))
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.marginEnd = dp(10)
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
                setMargins(dp(0), 0, dp(0), dp(6))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minimumHeight = dp(58)
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
            label.textSize = 15f * fontScale
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
                        Row.Kind.BROWSER -> R.drawable.ic_public
                        Row.Kind.YOUTUBE -> R.drawable.ic_smart_display
                        Row.Kind.FOLDER -> R.drawable.ic_folder
                        Row.Kind.BACK -> R.drawable.ic_arrow_back
                        else -> R.drawable.ic_play_arrow
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
