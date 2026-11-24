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
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
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
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.apps.auto.sdk.CarActivity
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
    private lateinit var webViewPane: FrameLayout
    private lateinit var webView: WebView
    private lateinit var urlBar: EditText

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
            textSize = 20f
            setPadding(dp(20), dp(16), dp(20), dp(8))
            setSingleLine(true)
        }
        emptyView = TextView(this).apply {
            setTextColor(Color.GRAY)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), 0)
        }
        adapter = RowsAdapter()
        listHost = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CarPlayerActivity)
            setHasFixedSize(true)
        }
        val listPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(titleView)
            addView(listHost, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(emptyView, LinearLayout.LayoutParams(-1, 0, 1f))
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
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(0, dp(6), 0, dp(6))
            addView(videoTitle, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            addView(
                controlButton("✕") { onBackPressed() },
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
            maxHeight = dp(4)
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
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
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            setPadding(dp(16), dp(6), dp(16), dp(2))
            addView(seekBar, LinearLayout.LayoutParams(-1, -2))
            addView(posLabel, LinearLayout.LayoutParams(-1, -2))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(140, 0, 0, 0))
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
        controlsBar = bottomControls
        videoOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            // tap anywhere = show controls; second tap while visible = also refresh timer
            setOnClickListener { showControls() }
            addView(videoSurfaceView, FrameLayout.LayoutParams(-1, -1))
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottomControls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        // ---- web layer ------------------------------------------------------------------
        urlBar = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setHint("Search or type URL")
            imeOptions = EditorInfo.IME_ACTION_GO
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
            addView(urlBar, LinearLayout.LayoutParams(0, -2, 1f))
            addView(controlButton("⌂") { openUrl(browserHome) })
            addView(controlButton("⟳") { webView.reload() })
        }
        @SuppressLint("SetJavaScriptEnabled")
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { v, ev -> scheduleHide(); v.performClick(); false }
        }
        webViewPane = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            addView(urlPane, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(webView, FrameLayout.LayoutParams(-1, -1))
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(listPane, FrameLayout.LayoutParams(-1, -1))
            addView(webViewPane, FrameLayout.LayoutParams(-1, -1))
            addView(videoOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        listHost.adapter = adapter
        trail.addLast("RoadPlay" to MediaIds.ROOT)
    }

    /** Uniform control chip: fixed square, centred glyph, ripple-ish focus state. */
    private fun controlButton(glyph: String, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            minWidth = dp(52)
            minHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.argb(80, 255, 255, 255)))
                addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(Color.argb(60, 255, 255, 255)))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnClickListener(onClick)
        }

    private fun LinearLayout.addButton(glyph: String, onClick: () -> Unit) {
        addView(controlButton(glyph) { onClick(); scheduleHide() },
            LinearLayout.LayoutParams(0, -2, 1f))
    }

    // ---- Settings -------------------------------------------------------------------------

    private fun loadSettings() {
        (application as RoadPlayApp).appScope.launch {
            try {
                val s = (application as RoadPlayApp).container.settings.settings.first()
                hideSeconds = s.carControlsHideSeconds.coerceIn(1, 10)
                browserHome = s.browserHomeUrl
            } catch (_: Throwable) { /* defaults are fine */ }
        }
    }

    // ---- Lifecycle ------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        loadSettings()
        connect()
    }

    override fun onStop() {
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
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaBrowser.Builder(this, token).buildAsync()
        future.addListener({
            try {
                controller = future.get().also { c ->
                    c.addListener(this)
                    show(trail.last().second, trail.last().first)
                    updateVideoOverlay(c)
                }
            } catch (t: Throwable) {
                showEmpty("Could not reach the player")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Browsing -------------------------------------------------------------------------

    private fun show(parentId: String, title: String) {
        webViewPane.visibility = View.GONE
        listHost.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        val c = controller ?: return
        val future = c.getChildren(parentId, 0, Int.MAX_VALUE, null)
        future.addListener({
            try {
                val children = future.get().value.orEmpty()
                rows.clear()
                if (parentId != MediaIds.ROOT) rows.add(Row("‹ Back", null, Row.Kind.BACK))
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
                trail.removeLastOrNull()
                trail.addLast(title to parentId)
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                listHost.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                adapter.notifyDataSetChanged()
            } catch (t: Throwable) {
                showEmpty("Browse failed")
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
            row.kind == Row.Kind.FOLDER -> row.item?.mediaId?.let { show(it, row.title) }
            else -> controller?.let { c ->
                row.item?.let {
                    c.setMediaItem(it)
                    c.prepare()
                    c.play()
                }
            }
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
            surface = holder.surface ?: return
            controller?.let { sendCarSurface(it) }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surface = null
            controller?.let { clearCarSurface(it) }
        }
    }

    private fun updateVideoOverlay(c: MediaBrowser) {
        val showVideo = c.isPlaying && c.videoSize.width > 0
        videoOverlay.visibility = if (showVideo) View.VISIBLE else View.GONE
        if (showVideo) {
            showControls()
            surface?.let { if (!surfaceSent) sendCarSurface(c) }
        } else if (surfaceSent) {
            clearCarSurface(c)
        }
    }

    private fun sendCarSurface(c: MediaBrowser) {
        val s = surface ?: return
        val args = Bundle().apply { putParcelable(RoadPlayCommands.ARG_SURFACE, s) }
        c.sendCustomCommand(SessionCommand(RoadPlayCommands.SET_CAR_SURFACE, Bundle.EMPTY), args)
        surfaceSent = true
    }

    private fun clearCarSurface(c: MediaBrowser) {
        if (!surfaceSent) return
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
    private lateinit var controlsBar: LinearLayout
    private lateinit var topBar: FrameLayout

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
            if (controlsVisible && videoOverlay.visibility == View.VISIBLE) {
                val dur = c.duration.coerceAtLeast(0)
                if (dur > 0) seekBar.progress = ((c.currentPosition * 100) / dur).toInt()
                posLabel.text = "${fmt(c.currentPosition)} / ${fmt(dur)}"
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
            videoOverlay.visibility == View.VISIBLE -> {
                // hide controls first, then leave the video
                if (controlsVisible) showControls() else {
                    videoOverlay.visibility = View.GONE
                    clearCarSurface(controller ?: return)
                }
            }
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
        val icon = ImageView(this).apply {
            val lp = LinearLayout.LayoutParams(dp(24), dp(24))
            lp.gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp
        }
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(16), 0, 0, 0)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp
        }
        val highlight = ColorDrawable(Color.argb(60, 255, 255, 255))
        val transparent = ColorDrawable(Color.TRANSPARENT)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), 0, dp(20), 0)
            minimumHeight = dp(56)
            isClickable = true
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), highlight)
                addState(intArrayOf(), transparent)
            }
            addView(icon)
            addView(label)
        }
    }

    private inner class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        private val icon = row.getChildAt(0) as ImageView
        private val label = row.getChildAt(1) as TextView
        private lateinit var bound: Row

        init {
            row.setOnClickListener { open(bound) }
        }

        fun bind(r: Row) {
            bound = r
            label.text = r.title
            icon.setImageResource(
                when (r.kind) {
                    Row.Kind.BROWSER -> android.R.drawable.ic_menu_manage
                    Row.Kind.YOUTUBE -> android.R.drawable.ic_menu_play_clip
                    Row.Kind.FOLDER, Row.Kind.BACK -> android.R.drawable.ic_menu_agenda
                    else -> android.R.drawable.ic_media_play
                },
            )
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
