@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Player

internal data class PlaybackTrack(val id: String, val label: String, val selected: Boolean)

/** Source-specific controls: portrait Shorts, YouTube transport, and movie/episode transport. */
internal class VodControls(
    private val a: MainActivity,
    private val watch: Watch,
    private val player: Player,
    private val seekSeconds: Int,
    private val exit: () -> Unit,
    private val focusVideo: () -> Unit,
    private val tracks: (Int, (List<PlaybackTrack>) -> Unit) -> Unit,
    private val selectTrack: (Int, String) -> Unit,
    private val controlsInset: (Int) -> Unit = {},
    private val pictureControls: () -> Unit = {},
    private val youtubePanel: ((Boolean) -> Unit)? = null,
) {
    val view = FrameLayout(a).apply { tag = "vod-controls"; visibility = View.GONE; isFocusable = false }
    private val main = Handler(Looper.getMainLooper())
    private val panel = a.column().apply {
        tag = "vod-panel"; setPadding(0, a.dp(2), 0, a.dp(2))
        isClickable = true; isFocusable = false
    }
    private val elapsed = a.text("0:00", 10f)
    private val remaining = a.text("–:–", 10f, MUTED)
    private val feedback = a.text("", 12f, MUTED).apply { visibility = View.GONE }
    private val bar = SeekBar(a).apply {
        tag = "vod-progress"; id = View.generateViewId(); max = 100000
        isFocusable = true; isFocusableInTouchMode = true
        progressTintList = ColorStateList.valueOf(WHITE)
        secondaryProgressTintList = ColorStateList.valueOf(0x66FFFFFF)
        progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        thumbTintList = ColorStateList.valueOf(WHITE)
        minimumHeight = a.dp(44); contentDescription = "Playback position"
        background = null
        fun line(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = a.dp(1).toFloat() }
        progressDrawable = LayerDrawable(arrayOf(line(0x33FFFFFF),
            ClipDrawable(line(0x66FFFFFF), Gravity.LEFT, ClipDrawable.HORIZONTAL),
            ClipDrawable(line(WHITE), Gravity.LEFT, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.secondaryProgress); setId(2, android.R.id.progress)
            for (index in 0..2) { setLayerHeight(index, a.dp(2)); setLayerGravity(index, Gravity.CENTER_VERTICAL) }
        }
        fun dot(size: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(WHITE); setSize(a.dp(size), a.dp(size)) }
        thumb = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), dot(10))
            addState(intArrayOf(android.R.attr.state_pressed), dot(10))
            addState(intArrayOf(), InsetDrawable(dot(4), a.dp(3)))
        }
    }
    private val playIcon = ControlIcon(ControlIcon.PAUSE)
    private var lastCenterRelease=Long.MIN_VALUE
    private val heatmap=if(watch.playbackMode==PlaybackMode.YOUTUBE)YouTubeHeatmapView(a,bar) else null
    private val play = iconButton("Pause", "vod-play-pause", playIcon) { togglePlayback() }
    private val rewind = iconButton("Back $seekSeconds seconds", "vod-rewind", ControlIcon(ControlIcon.REWIND, seekSeconds)) { skip(-1) }
    private val forward = iconButton("Forward $seekSeconds seconds", "vod-forward", ControlIcon(ControlIcon.FORWARD, seekSeconds)) { skip(1) }
    private val trackButton = iconButton("Audio & subtitles", "vod-tracks", ControlIcon(ControlIcon.TRACKS)) { showTrackOptions() }
    private val quality=if(watch.playbackMode==PlaybackMode.YOUTUBE)iconButton("Video quality","youtube-quality",ControlIcon(ControlIcon.QUALITY)){chooseTrack(C.TRACK_TYPE_VIDEO)} else null
    private val options = iconButton("Picture controls", "vod-options", ControlIcon(ControlIcon.SETTINGS)) { openPictureControls() }
    private val closeButton = iconButton(if(watch.title.type=="youtube")"Back to YouTube" else "Back to streams", "vod-close", ControlIcon(ControlIcon.CLOSE)) { exit() }
    private val relatedButton=if(youtubePanel!=null)a.action("Related","Related videos"){openYouTubePanel(false)}.quiet() else null
    private val commentsButton=if(youtubePanel!=null)a.action("Comments","Video comments"){openYouTubePanel(true)}.quiet() else null
    private var closed = false
    private var touchPosition: Long? = null
    private var queuedSeek: Long? = null
    private var seekAt = 0L
    private var heldDirection = 0
    private var heldSince = 0L
    private val applySeek = Runnable { flushSeek() }
    private var interactedAt = SystemClock.uptimeMillis()
    private var dialog: AlertDialog? = null
    private var loadingTracks = false
    private var trackRequest = 0
    private val consumedKeys = HashMap<Int, Long>()
    private var priorPlayIntent = player.playWhenReady
    private var priorState = player.playbackState
    private var elapsedSecond = Long.MIN_VALUE
    private var remainingSecond = Long.MIN_VALUE
    private var reportedInset = 0
    private val insetLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishInset() }
    private val ticker = object : Runnable {
        override fun run() {
            if (closed || view.visibility != View.VISIBLE || !view.isAttachedToWindow) return
            update()
            if(player.isPlaying || (player.playWhenReady && player.playbackState==Player.STATE_BUFFERING) ||
                player.isLoading || touchPosition!=null || queuedSeek!=null)main.postDelayed(this,250)
        }
    }
    private val hideLater = Runnable {
        if (!closed && view.visibility == View.VISIBLE) {
            if (mayHide() && SystemClock.uptimeMillis() - interactedAt >= 5000) hide()
            else scheduleHide()
        }
    }
    private val windowFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
        if (focused && !closed && view.visibility == View.VISIBLE) { interactedAt = SystemClock.uptimeMillis(); scheduleHide() }
        else { finishHold(); main.removeCallbacks(hideLater) }
    }
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = changed()
        override fun onPlaybackStateChanged(playbackState: Int) = changed()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = changed()
        override fun onIsLoadingChanged(isLoading:Boolean) { if(!closed && view.isShown){update();startTicker()} }
        override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
            if(!closed && view.isShown)update()
        }
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) { if (!closed && view.isShown) update() }
        private fun changed() {
            if (closed) return
            val paused = priorPlayIntent && !player.playWhenReady && player.playbackState != Player.STATE_IDLE
            val ended = priorState != Player.STATE_ENDED && player.playbackState == Player.STATE_ENDED
            priorPlayIntent = player.playWhenReady; priorState = player.playbackState
            if ((paused || ended) && view.visibility != View.VISIBLE) show(false)
            else if (view.visibility == View.VISIBLE) { update();startTicker(); scheduleHide() }
        }
    }
    init {
        require(!watch.live) { "Live TV does not have transport controls" }
        if(watch.short)buildShorts() else {
        view.addView(View(a).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xAA000000.toInt(), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(-1, a.dp(112), Gravity.TOP))
        view.addView(View(a).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xB3000000.toInt()))
        }, FrameLayout.LayoutParams(-1, a.dp(132), Gravity.BOTTOM))
        val title = a.column().apply {
            add(a.text(watch.title.name, 17f, bold = true).apply { tag = "vod-title"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setShadowLayer(a.dp(2).toFloat(), 0f, a.dp(1).toFloat(), Color.BLACK) })
            watch.episode?.let { episode -> gap(3); add(a.text(episode.label, 11f, MUTED).apply { tag = "vod-episode"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setShadowLayer(a.dp(2).toFloat(), 0f, a.dp(1).toFloat(), Color.BLACK) }) }
            if(watch.title.type=="youtube") {
                gap(3);add(a.text(if(watch.short)"Shorts · ↑ Previous · ↓ Next" else watch.title.description,11f,MUTED).apply {
                    maxLines=1;ellipsize=TextUtils.TruncateAt.END;setShadowLayer(a.dp(2).toFloat(),0f,a.dp(1).toFloat(),Color.BLACK)
                })
            }
        }
        feedback.setShadowLayer(a.dp(2).toFloat(), 0f, a.dp(1).toFloat(), Color.BLACK)
        val header = a.column().apply {
            tag = "vod-header"
            add(a.row().apply {
                addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                relatedButton?.let { addView(it,LinearLayout.LayoutParams(a.dp(88),a.dp(40))) }
                commentsButton?.let { addView(it,LinearLayout.LayoutParams(a.dp(106),a.dp(40)).apply{marginStart=a.dp(6)}) }
                addView(closeButton, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { marginStart = a.dp(12) })
            })
            add(feedback)
        }
        view.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(a.dp(40), a.dp(20), a.dp(40), 0) })
        val timeHeight = maxOf(a.dp(14), elapsed.lineHeight, remaining.lineHeight)
        val stripHeight = a.dp(44)
        bar.setPadding(a.dp(6), 0, a.dp(6), timeHeight)
        panel.add(a.row().apply {
            val transport = a.row().apply {
                addView(rewind, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
                addView(play, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { marginStart = a.dp(2); marginEnd = a.dp(2) })
                addView(forward, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
            }
            addView(transport, LinearLayout.LayoutParams(-2, a.dp(44)))
            addView(FrameLayout(a).apply {
                heatmap?.let { addView(it,FrameLayout.LayoutParams(-1,a.dp(44),Gravity.TOP)) }
                addView(bar, FrameLayout.LayoutParams(-1, a.dp(44), Gravity.TOP))
                addView(a.row().apply {
                    addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f)); add(remaining, width = -2)
                }, FrameLayout.LayoutParams(-1, timeHeight, Gravity.BOTTOM).apply { leftMargin = a.dp(4); rightMargin = a.dp(4) })
            }, LinearLayout.LayoutParams(0, stripHeight, 1f).apply { marginStart = a.dp(16); marginEnd = a.dp(16) })
            quality?.let { addView(it,LinearLayout.LayoutParams(a.dp(44),a.dp(44)).apply{marginEnd=a.dp(6)}) }
            addView(trackButton, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { marginEnd = a.dp(8) })
            addView(options, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
        }, height = stripHeight)
        bar.nextFocusUpId = closeButton.id; bar.nextFocusDownId = play.id
        closeButton.nextFocusDownId = bar.id; closeButton.nextFocusLeftId = bar.id
        closeButton.nextFocusUpId = closeButton.id; closeButton.nextFocusRightId = closeButton.id
        if(relatedButton!=null && commentsButton!=null) {
            bar.nextFocusUpId=relatedButton.id;relatedButton.nextFocusLeftId=bar.id;relatedButton.nextFocusRightId=commentsButton.id
            commentsButton.nextFocusLeftId=relatedButton.id;commentsButton.nextFocusRightId=closeButton.id;closeButton.nextFocusLeftId=commentsButton.id
            listOf(relatedButton,commentsButton).forEach { it.nextFocusDownId=bar.id;it.nextFocusUpId=it.id }
        }
        val bottom = listOfNotNull(rewind, play, forward, quality, trackButton, options)
        bottom.forEachIndexed { index, button ->
            button.nextFocusLeftId = bottom[(index - 1).coerceAtLeast(0)].id
            button.nextFocusRightId = bottom[(index + 1).coerceAtMost(bottom.lastIndex)].id
            button.nextFocusUpId = bar.id; button.nextFocusDownId = button.id
        }
        view.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(a.dp(40), 0, a.dp(40), a.dp(20)) })
        view.addOnLayoutChangeListener(insetLayout); panel.addOnLayoutChangeListener(insetLayout)
        view.setOnClickListener { dismiss() }
        panel.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) interacted(); false }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && canSeek()) { touchPosition = (duration().toDouble() * progress / seekBar.max).toLong(); interacted(); update() }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { finishHold(); touchPosition = position(); interacted() }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val at = touchPosition; touchPosition = null
                if (at != null) { queuedSeek = null; main.removeCallbacks(applySeek); submitSeek(at) }
                interacted(); update()
            }
        })
        bar.setOnKeyListener { _, code, event ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val direction = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount == 0 || heldDirection != direction) {
                            finishHold(); heldDirection = direction; heldSince = event.eventTime
                        }
                        val elapsed = (event.eventTime - heldSince).coerceAtLeast(0)
                        val multiplier = when { elapsed < 500 -> 1; elapsed < 1250 -> 2; elapsed < 2500 -> 4; else -> 8 }
                        seekRelative(direction, event.repeatCount > 0, multiplier)
                    } else if (event.action == KeyEvent.ACTION_UP && heldDirection == direction) finishHold()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { finishHold(); togglePlayback() }; true }
                else -> false
            }
        }
        bar.setOnFocusChangeListener { _, focused -> if (!focused) { touchPosition = null; finishHold() } }
        bar.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_CANCEL) { touchPosition = null; update(); scheduleHide() }; false }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (view.visibility == View.VISIBLE) { startTicker(); scheduleHide(); publishInset() } }
            override fun onViewDetachedFromWindow(v: View) { main.removeCallbacks(ticker); main.removeCallbacks(hideLater); publishInset() }
        })
        view.viewTreeObserver.addOnWindowFocusChangeListener(windowFocus)
        player.addListener(listener)
    }
    private fun buildShorts() {
        val videoWidth=(a.resources.displayMetrics.heightPixels*9/16).coerceAtMost(a.resources.displayMetrics.widthPixels)
        view.addView(View(a).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xAA000000.toInt()))
        },FrameLayout.LayoutParams(videoWidth,a.dp(116),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        view.addView(a.column().apply {
            tag="shorts-title"
            add(a.text(watch.title.name,15f,bold=true).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END })
            gap(5);add(a.text(watch.title.description,11f,MUTED).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END })
        },FrameLayout.LayoutParams((videoWidth-a.dp(32)).coerceAtLeast(a.dp(80)),-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=a.dp(30) })
        bar.apply { tag="shorts-progress";isFocusable=false;isClickable=false;isEnabled=false;minimumHeight=0;thumb=null;setPadding(0,0,0,0);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        view.addView(bar,FrameLayout.LayoutParams(videoWidth,a.dp(2),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=a.dp(12) })
        panel.tag="shorts-actions";panel.isClickable=false;panel.gravity=Gravity.END
        panel.add(a.text("↑ Previous    ↓ Next",11f,MUTED).apply { gravity=Gravity.END },height=a.dp(24))
        val actions=listOfNotNull(play,commentsButton,options,closeButton)
        panel.add(a.row().apply {
            for(button in actions)addView(button,LinearLayout.LayoutParams(if(button===commentsButton)a.dp(106) else a.dp(44),a.dp(44)).apply { marginStart=a.dp(4) })
        },width=-2,height=a.dp(44))
        for((index,button) in actions.withIndex()) {
            button.nextFocusLeftId=actions[(index-1).coerceAtLeast(0)].id
            button.nextFocusRightId=actions[(index+1).coerceAtMost(actions.lastIndex)].id
        }
        view.addView(panel,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply { rightMargin=a.dp(24);bottomMargin=a.dp(24) })
        view.addOnLayoutChangeListener(insetLayout);panel.addOnLayoutChangeListener(insetLayout)
        view.setOnClickListener{dismiss()}
    }
    private fun iconButton(label: String, tag: String, drawable: ControlIcon, action: () -> Unit) = ImageButton(a).apply {
        contentDescription = label; this.tag = tag; id = View.generateViewId()
        isFocusable = true; isFocusableInTouchMode = true; scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(drawable)
        val inset = a.dp(11)
        setPadding(inset, inset, inset, inset)
        imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(WHITE, 0xDDFFFFFF.toInt()))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), a.round(0x33FFFFFF, radius = 12))
            addState(intArrayOf(android.R.attr.state_pressed), a.round(0x55FFFFFF, radius = 12))
            addState(intArrayOf(), a.round(Color.TRANSPARENT, radius = 12))
        }
        stateListAnimator = null; setOnClickListener { interacted(); action() }
    }
    fun show(focus: Boolean = true) {
        if (closed) return
        view.visibility = View.VISIBLE; interactedAt = SystemClock.uptimeMillis(); update(); startTicker(); scheduleHide()
        publishInset(); view.post { publishInset() }
        if (focus) view.post { if (!closed && view.isShown && view.hasWindowFocus()) play.requestFocus() }
    }
    fun surfaceClick(){if(watch.short)togglePlayback() else show()}
    /** Back dismisses the overlay; a later Back may exit. */
    fun dismiss(): Boolean {
        if (closed || view.visibility != View.VISIBLE) return false
        hide(); return true
    }
    private fun hide() {
        touchPosition = null; finishHold(); invalidateTrackRequest(); view.visibility = View.GONE
        main.removeCallbacks(ticker); main.removeCallbacks(hideLater); publishInset(); focusVideo()
    }
    private fun publishInset() {
        val inset = if (!closed && view.visibility == View.VISIBLE && view.isAttachedToWindow && view.height > 0 && panel.height > 0)
            (view.height - panel.top).coerceIn(0, view.height) else 0
        if (inset != reportedInset) { reportedInset = inset; controlsInset(inset) }
    }
    private fun interacted() { interactedAt = SystemClock.uptimeMillis(); scheduleHide() }
    private fun startTicker() { main.removeCallbacks(ticker); main.post(ticker) }
    private fun mayHide() = player.playWhenReady && player.playbackState != Player.STATE_ENDED && touchPosition == null && !loadingTracks && dialog?.isShowing != true && view.hasWindowFocus()
    private fun scheduleHide() {
        main.removeCallbacks(hideLater)
        if (!closed && view.visibility == View.VISIBLE && mayHide()) main.postDelayed(hideLater, (5000 - (SystemClock.uptimeMillis() - interactedAt)).coerceAtLeast(50))
    }
    private fun duration() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
    private fun position() = player.currentPosition.coerceAtLeast(0)
    private fun canSeek() = !watch.short && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && duration() > 0
    private fun submitSeek(at: Long) {
        if (closed || !canSeek()) return
        val target = at.coerceIn(0, duration())
        seekAt = SystemClock.uptimeMillis()
        if (target != position()) player.seekTo(target)
        interacted(); update()
    }
    private fun flushSeek() {
        main.removeCallbacks(applySeek)
        val at = queuedSeek ?: return
        queuedSeek = null; submitSeek(at)
    }
    private fun finishHold() { flushSeek(); heldDirection = 0; heldSince = 0 }
    private fun skip(direction: Int) { finishHold(); seekRelative(direction) }
    private fun seekRelative(direction: Int, repeat: Boolean = false, multiplier: Int = 1) {
        if (canSeek()) {
            val now = SystemClock.uptimeMillis()
            val actual = position()
            val target = ((queuedSeek ?: actual) + direction * seekSeconds * 1000L * multiplier).coerceIn(0, duration())
            main.removeCallbacks(applySeek); queuedSeek = null
            if (repeat && now - seekAt < 150) { queuedSeek = target; main.postDelayed(applySeek, 150 - (now - seekAt)) }
            else submitSeek(target)
        }
        show(false)
    }
    private fun togglePlayback() {
        if (player.playbackState == Player.STATE_ENDED) startPlayback()
        else if (player.playWhenReady) player.pause() else player.play()
        show(false)
    }
    private fun startPlayback() {
        if (player.playbackState == Player.STATE_ENDED && canSeek()) {
            queuedSeek = null; heldDirection = 0; heldSince = 0; main.removeCallbacks(applySeek); player.seekTo(0)
        }
        player.play()
    }
    private fun update() {
        if (closed || view.visibility != View.VISIBLE) return
        val total = duration(); val at = (touchPosition ?: queuedSeek ?: position()).let { if (total > 0) it.coerceAtMost(total) else it }
        heatmap?.duration(total)
        val seekable = canSeek();bar.isEnabled=seekable
        if(!watch.short){rewind.isEnabled=seekable;forward.isEnabled=seekable;rewind.alpha=if(seekable)1f else .4f;forward.alpha=rewind.alpha}
        bar.progress = if (total > 0) (at.toDouble() / total * bar.max).toInt() else 0
        bar.secondaryProgress = if (total > 0) (player.bufferedPosition.coerceAtLeast(at).toDouble().coerceAtMost(total.toDouble()) / total * bar.max).toInt() else 0
        val second = at / 1000
        if (!watch.short && elapsedSecond != second) { elapsedSecond = second; elapsed.text = format(at) }
        val left = if (total > 0) (total - at).coerceAtLeast(0) / 1000 else -1
        if (!watch.short && remainingSecond != left) { remainingSecond = left; remaining.text = if (left < 0) "–:–" else "−${format(left * 1000)}" }
        val ended = player.playbackState == Player.STATE_ENDED
        val paused = !player.playWhenReady || ended
        playIcon.mode = if (paused) ControlIcon.PLAY else ControlIcon.PAUSE
        play.contentDescription = if (ended) "Replay" else if (paused) "Play" else "Pause"
    }
    /** Only global transport/reveal keys are intercepted; visible widgets receive normal D-pad events. */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (closed) return false
        val code = event.keyCode
        val center=code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER
        if (event.action == KeyEvent.ACTION_UP) {
            if(consumedKeys.remove(code)!=null){if(center)lastCenterRelease=event.downTime;return true}
            // Android can consume the first DOWN when leaving touch/focus mode and
            // still deliver its UP. Activate the focused Shorts surface once in that case.
            if(watch.short && center && (view.visibility!=View.VISIBLE || !view.hasFocus())) {
                if(!event.isCanceled && lastCenterRelease!=event.downTime)togglePlayback()
                lastCenterRelease=event.downTime;return true
            }
        }
        consumedKeys[code]?.let { down ->
            if (event.repeatCount > 0 || event.downTime == down) return true
            consumedKeys.remove(code) // The preceding UP may have gone to a dialog/window.
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        fun consume(action: () -> Unit): Boolean { consumedKeys[code] = event.downTime; if (event.repeatCount == 0) action(); return true }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> return consume { startPlayback(); show(false) }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return consume { player.pause(); show(false) }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return consume { togglePlayback() }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> return consume { skip(-1) }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> return consume { skip(1) }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return consume { }
        }
        if(watch.short && code in setOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER) &&
            (view.visibility!=View.VISIBLE || !view.hasFocus() || play.hasFocus()))return consume{togglePlayback()}
        if(!watch.short && code==KeyEvent.KEYCODE_DPAD_DOWN && youtubePanel!=null &&
            (view.visibility!=View.VISIBLE || listOf(play,rewind,forward,trackButton,options).any { it.hasFocus() }))
            return consume { openYouTubePanel(false) }
        val navigation = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (navigation && (view.visibility != View.VISIBLE || !view.hasFocus())) return consume { show(true) }
        if (view.visibility == View.VISIBLE) interacted()
        return false
    }
    private fun openPictureControls() { if (!closed) { finishHold(); invalidateTrackRequest(); interacted(); pictureControls() } }
    private fun openYouTubePanel(comments:Boolean) { if(!closed){hide();youtubePanel?.invoke(comments)} }
    fun replayMarkers(markers:List<YouTubeHeatMarker>){heatmap?.markers(markers);heatmap?.duration(duration())}
    private fun showTrackOptions() {
        finishHold(); invalidateTrackRequest(); interacted()
        dialog = a.showActions("Audio & subtitles", listOf(
            "Audio track" to { chooseTrack(C.TRACK_TYPE_AUDIO) },
            "Subtitles" to { chooseTrack(C.TRACK_TYPE_TEXT) }))
    }
    private fun invalidateTrackRequest() { trackRequest++; loadingTracks = false; feedback.visibility = View.GONE }
    private fun chooseTrack(type: Int) {
        if (closed) return
        loadingTracks = true; val token = ++trackRequest; feedback.text = "Loading tracks…"; feedback.visibility = View.VISIBLE
        main.removeCallbacks(hideLater)
        tracks(type) { available -> main.post {
            if (closed || token != trackRequest || view.visibility != View.VISIBLE) return@post
            loadingTracks = false; feedback.visibility = View.GONE
            if (available.isEmpty()) {
                feedback.text = if (type == C.TRACK_TYPE_AUDIO) "No audio tracks available." else if(type==C.TRACK_TYPE_VIDEO)"No video qualities available." else "No subtitle tracks available."
                feedback.visibility = View.VISIBLE; interacted()
            } else dialog = a.chooseOption(if (type == C.TRACK_TYPE_AUDIO) "Audio track" else if(type==C.TRACK_TYPE_VIDEO)"Video quality" else "Subtitles", available.map { it.label }, available.indexOfFirst { it.selected }) { index ->
                if (!closed) { selectTrack(type, available[index].id); interacted() }
            }
        } }
    }
    fun close() {
        if (closed) return
        closed = true; trackRequest++; queuedSeek = null; touchPosition = null; heldDirection = 0; heldSince = 0
        player.removeListener(listener); main.removeCallbacksAndMessages(null)
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocus)
        dialog?.dismiss(); dialog = null; consumedKeys.clear(); view.visibility = View.GONE
        view.removeOnLayoutChangeListener(insetLayout); panel.removeOnLayoutChangeListener(insetLayout); publishInset()
    }
    private fun format(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        fun two(value: Long) = if (value < 10) "0$value" else value.toString()
        return if (seconds >= 3600) "${seconds / 3600}:${two(seconds / 60 % 60)}:${two(seconds % 60)}"
        else "${seconds / 60}:${two(seconds % 60)}"
    }
}

/** Small monochrome vector controls; no bitmap assets or per-frame path allocation. */
private class ControlIcon(initial: Int, private val seconds: Int = 10) : Drawable() {
    companion object { const val PLAY=0; const val PAUSE=1; const val REWIND=2; const val FORWARD=3; const val SETTINGS=4; const val CLOSE=5; const val TRACKS=6; const val QUALITY=7 }
    var mode = initial
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val skipLabel = seconds.toString()
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width()/32f, bounds.height()/32f)
        paint.style = Paint.Style.FILL
        when (mode) {
            PLAY -> { path.reset();path.moveTo(10f,5f);path.lineTo(27f,16f);path.lineTo(10f,27f);path.close();canvas.drawPath(path,paint) }
            PAUSE -> { canvas.drawRoundRect(7f,5f,13f,27f,1f,1f,paint);canvas.drawRoundRect(20f,5f,26f,27f,1f,1f,paint) }
            REWIND, FORWARD -> {
                paint.style=Paint.Style.STROKE;canvas.drawArc(5f,5f,27f,27f,if(mode==REWIND)205f else -25f,310f,false,paint)
                path.reset();if(mode==REWIND){path.moveTo(3f,7f);path.lineTo(4f,15f);path.lineTo(11f,11f)}else{path.moveTo(29f,7f);path.lineTo(28f,15f);path.lineTo(21f,11f)}
                canvas.drawPath(path,paint);paint.style=Paint.Style.FILL;paint.textSize=11f;paint.typeface=Typeface.DEFAULT_BOLD;paint.textAlign=Paint.Align.CENTER
                canvas.drawText(skipLabel,16f,20f,paint)
            }
            SETTINGS -> { paint.style=Paint.Style.STROKE;canvas.drawLine(5f,8f,27f,8f,paint);canvas.drawLine(5f,16f,27f,16f,paint);canvas.drawLine(5f,24f,27f,24f,paint);paint.style=Paint.Style.FILL;canvas.drawCircle(12f,8f,3f,paint);canvas.drawCircle(22f,16f,3f,paint);canvas.drawCircle(10f,24f,3f,paint) }
            CLOSE -> { paint.style=Paint.Style.STROKE;canvas.drawLine(8f,8f,24f,24f,paint);canvas.drawLine(24f,8f,8f,24f,paint) }
            QUALITY -> { paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=15f;paint.textAlign=Paint.Align.CENTER;canvas.drawText("HD",16f,22f,paint) }
            TRACKS -> { paint.style=Paint.Style.STROKE;canvas.drawRoundRect(3f,5f,29f,27f,4f,4f,paint);canvas.drawLine(8f,13f,14f,13f,paint);canvas.drawLine(18f,13f,24f,13f,paint);canvas.drawLine(8f,20f,24f,20f,paint) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter=colorFilter;invalidateSelf() }
    override fun getIntrinsicWidth()=32
    override fun getIntrinsicHeight()=32
    @Suppress("DEPRECATION") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
