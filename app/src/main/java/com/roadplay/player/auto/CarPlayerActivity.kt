package com.roadplay.player.auto

import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.apps.auto.sdk.CarActivity
import com.roadplay.player.playback.MediaIds
import com.roadplay.player.playback.PlaybackService
import com.roadplay.player.playback.RoadPlayCommands

/**
 * RoadPlay's own screen on the Android Auto head unit, shown while the car is
 * parked. Follows the classic AA media-activity architecture (the same one
 * Fermata uses): browse the phone library through the media session, play
 * through a [MediaController], and hand the one shared ExoPlayer the car
 * screen's [SurfaceView] so video renders on the head unit. On leaving the car
 * screen the surface is released; the phone UI re-attaches its own on resume.
 *
 * Written fresh in Kotlin against Media3 — architecture inspired by Fermata
 * (GPLv3), no code copied.
 */
class CarPlayerActivity : CarActivity(), Player.Listener {

    private var controller: MediaBrowser? = null
    private var surface: Surface? = null
    private var surfaceSent = false

    private lateinit var titleView: TextView
    private lateinit var listHost: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var videoOverlay: FrameLayout
    private lateinit var videoSurfaceView: SurfaceView

    /** Back stack of (title, parentId); last = currently shown. */
    private val trail = ArrayDeque<Pair<String, String>>()
    private val rows = mutableListOf<Row>()
    private lateinit var adapter: RowsAdapter

    private class Row(val title: String, val item: MediaItem?, val isFolder: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        videoSurfaceView = SurfaceView(this)
        videoSurfaceView.holder.addCallback(SurfaceLifecycle())

        videoOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            addView(videoSurfaceView, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }

        val listPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(titleView)
            addView(listHost, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(emptyView, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(listPane, FrameLayout.LayoutParams(-1, -1))
            addView(videoOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        listHost.adapter = adapter
        trail.addLast("RoadPlay" to MediaIds.ROOT)
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
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

    // ---- Connection ---------------------------------------------------------------------

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

    // ---- Browsing -----------------------------------------------------------------------

    private fun show(parentId: String, title: String) {
        val c = controller ?: return
        val future = c.getChildren(parentId, 0, Int.MAX_VALUE, null)
        future.addListener({
            try {
                val children = future.get().value.orEmpty()
                rows.clear()
                if (parentId != MediaIds.ROOT) rows.add(Row("‹ Back", null, isFolder = true))
                for (item in children) {
                    rows.add(
                        Row(
                            title = item.mediaMetadata.title?.toString() ?: "Untitled",
                            item = item,
                            isFolder = item.mediaMetadata.isBrowsable == true,
                        ),
                    )
                }
                titleView.text = title
                // keep the (title, parentId) trail accurate
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
        if (row.isFolder) {
            row.item?.mediaId?.let { show(it, row.title) }
            return
        }
        if (row.title == "‹ Back") {
            if (trail.size > 1) {
                trail.removeLastOrNull()
                val (t, id) = trail.last()
                show(id, t)
            } else {
                onBackPressed()
            }
            return
        }
        controller?.let { c ->
            row.item?.let {
                c.setMediaItem(it)
                c.prepare()
                c.play()
            }
        }
    }

    // ---- Video surface handoff ----------------------------------------------------------

    private inner class SurfaceLifecycle : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val s = holder.surface ?: return
            surface = Surface(s)
            controller?.let { sendCarSurface(it) }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surface = null
            controller?.let { clearCarSurface(it) }
        }
    }

    private fun updateVideoOverlay(c: MediaController) {
        val showVideo = c.isPlaying && c.videoSize.width > 0
        videoOverlay.visibility = if (showVideo) View.VISIBLE else View.GONE
        if (!showVideo && surfaceSent) clearCarSurface(c)
    }

    private fun sendCarSurface(c: MediaController) {
        val s = surface ?: return
        val args = Bundle().apply { putParcelable(RoadPlayCommands.ARG_SURFACE, s) }
        c.sendCustomCommand(SessionCommand(RoadPlayCommands.SET_CAR_SURFACE, Bundle.EMPTY), args)
        surfaceSent = true
    }

    private fun clearCarSurface(c: MediaController) {
        if (!surfaceSent) return
        surfaceSent = false
        c.sendCustomCommand(SessionCommand(RoadPlayCommands.CLEAR_CAR_SURFACE, Bundle.EMPTY), Bundle.EMPTY)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.size() == 0) return
        updateVideoOverlay(player as MediaBrowser)
    }

    // ---- Keys ------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (videoOverlay.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    controller?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    onBackPressed()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        if (trail.size > 1) {
            trail.removeLastOrNull()
            val (t, id) = trail.last()
            show(id, t)
        } else {
            super.onBackPressed()
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
                if (r.isFolder) android.R.drawable.ic_menu_agenda else android.R.drawable.ic_media_play,
            )
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
