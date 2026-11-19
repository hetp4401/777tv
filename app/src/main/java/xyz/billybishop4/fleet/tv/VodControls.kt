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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Player

internal data class PlaybackTrack(val id: String, val label: String, val selected: Boolean,val language:String="")

/** A tap is one time step. A held cursor integrates a bounded time-based speed,
 * so remote repeat frequency and the length of the video cannot change travel. */
internal class RemoteSeekMotion(private val tapMs:Long) {
    var direction=0;private set
    var downTime=Long.MIN_VALUE;private set
    var targetMs=0L;private set
    private var originMs=0L
    private var beganAt=0L
    private var sampledAt=0L
    val active get()=direction!=0
    fun matches(direction:Int,downTime:Long)=this.direction==direction && this.downTime==downTime
    fun begin(direction:Int,downTime:Long,eventTime:Long,positionMs:Long,durationMs:Long):Long {
        require(direction==-1 || direction==1)
        this.direction=direction;this.downTime=downTime;originMs=positionMs
        beganAt=eventTime;sampledAt=eventTime
        return sample(eventTime,durationMs)
    }
    fun sample(time:Long,durationMs:Long,release:Boolean=false):Long {
        if(!active)return targetMs
        // Late repeats cannot rewind the preview. UP may be queued behind a
        // slow frame: its real event time still defines the end of that hold.
        sampledAt=if(release)maxOf(beganAt,time) else maxOf(sampledAt,time)
        val heldSeconds=((sampledAt-beganAt-300).coerceAtLeast(0))/1000.0
        val ramp=heldSeconds.coerceAtMost(5.0)
        // Start at 20 media seconds/second, ramp continuously to 120 over five
        // seconds, then keep that cap. There is no per-repeat jump or percentage.
        val distance=(tapMs+1000*(20*ramp+10*ramp*ramp+120*(heldSeconds-ramp))).toLong().coerceAtLeast(0)
        val end=durationMs.coerceAtLeast(0);val origin=originMs.coerceIn(0,end)
        targetMs=if(direction>0)origin+distance.coerceAtMost(end-origin) else origin-distance.coerceAtMost(origin)
        return targetMs
    }
    fun stop(){direction=0;downTime=Long.MIN_VALUE}
}

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
    private val youtubeDescription:(()->Unit)?=null,
) {
    private val isYouTube=watch.title.type=="youtube" && !watch.short
    val view = FrameLayout(a).apply { tag = "vod-controls"; visibility = View.GONE; isFocusable = false }
    private val main = Handler(Looper.getMainLooper())
    private val panel = a.column().apply {
        tag = "vod-panel"; setPadding(0, a.dp(2), 0, a.dp(2))
        isClickable = true; isFocusable = false
    }
    private val elapsed = a.text("0:00", if(isYouTube)12f else 10f).apply { maxLines=1;ellipsize=TextUtils.TruncateAt.END;if(isYouTube)setTextColor(Color.WHITE) }
    private val remaining = a.text("–:–", 10f, MUTED).apply{if(isYouTube)visibility=View.GONE}
    private val feedback = a.text("", 12f, MUTED).apply { visibility = View.GONE }
    private val bar:SeekBar = object:SeekBar(a) {
        override fun onKeyDown(code:Int,event:KeyEvent):Boolean = timelineCode(code)?.let{timelineKey(it,event)}?:super.onKeyDown(code,event)
        override fun onKeyUp(code:Int,event:KeyEvent):Boolean = timelineCode(code)?.let{timelineKey(it,event)}?:super.onKeyUp(code,event)
        override fun performAccessibilityAction(action:Int,args:android.os.Bundle?):Boolean {
            val direction=when(action){AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->1;AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->-1;else->0}
            if(direction!=0){if(!canSeek())return false;skip(direction);return true}
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id &&
                args?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)==true) {
                if(!canSeek())return false
                val progress=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE).toDouble()
                if(!progress.isFinite())return false
                seekAbsolute((progress.coerceIn(0.0,max.toDouble())/max*duration()).toLong());return true
            }
            return super.performAccessibilityAction(action,args)
        }
    }.apply {
        val accent=if(isYouTube)0xFFFF0033.toInt() else ACCENT
        tag = "vod-progress"; id = View.generateViewId(); max = 100000
        isFocusable = true; isFocusableInTouchMode = true
        progressTintList = ColorStateList.valueOf(accent)
        secondaryProgressTintList = ColorStateList.valueOf(if(isYouTube)0x99FFFFFF.toInt() else (accent and 0x00FFFFFF) or 0x66000000)
        progressBackgroundTintList = ColorStateList.valueOf(if(isYouTube)0x55FFFFFF else (accent and 0x00FFFFFF) or 0x33000000)
        thumbTintList = ColorStateList.valueOf(accent)
        minimumHeight = a.dp(44); contentDescription = "Playback position"
        background = null
        if(isYouTube && android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        fun line(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = a.dp(1).toFloat() }
        progressDrawable = LayerDrawable(arrayOf(line(if(isYouTube)0x55FFFFFF else (accent and 0x00FFFFFF) or 0x33000000),
            ClipDrawable(line(if(isYouTube)0x99FFFFFF.toInt() else (accent and 0x00FFFFFF) or 0x66000000), Gravity.LEFT, ClipDrawable.HORIZONTAL),
            ClipDrawable(line(accent), Gravity.LEFT, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.secondaryProgress); setId(2, android.R.id.progress)
            for (index in 0..2) { setLayerHeight(index, a.dp(2)); setLayerGravity(index, Gravity.CENTER_VERTICAL) }
        }
        fun dot(size: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent); setSize(a.dp(size), a.dp(size)) }
        thumb = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), dot(10))
            addState(intArrayOf(android.R.attr.state_pressed), dot(10))
            addState(intArrayOf(), InsetDrawable(dot(4), a.dp(3)))
        }
    }
    private val playIcon = ControlIcon(ControlIcon.PAUSE)
    private var lastCenterRelease=Long.MIN_VALUE
    private var lastRelatedDown=Long.MIN_VALUE
    private var lastRelatedRelease=Long.MIN_VALUE
    private var lastControlsUpDown=Long.MIN_VALUE
    private var lastControlsUpRelease=Long.MIN_VALUE
    private val heatmap=if(watch.playbackMode==PlaybackMode.YOUTUBE)YouTubeHeatmapView(a,bar) else null
    private val play = iconButton("Pause", "vod-play-pause", playIcon) { togglePlayback() }
    private val rewind = iconButton("Back $seekSeconds seconds", "vod-rewind", ControlIcon(ControlIcon.REWIND, seekSeconds)) { skip(-1) }
    private val forward = iconButton("Forward $seekSeconds seconds", "vod-forward", ControlIcon(ControlIcon.FORWARD, seekSeconds)) { skip(1) }
    private val previousVideo=if(isYouTube)iconButton("Previous video","youtube-previous",ControlIcon(ControlIcon.PREVIOUS)){stepVideo(-1)} else null
    private val nextVideo=if(isYouTube)iconButton("Next video","youtube-next",ControlIcon(ControlIcon.NEXT)){stepVideo(1)} else null
    private val trackButton = iconButton("Audio & subtitles", "vod-tracks", ControlIcon(ControlIcon.TRACKS)) { showTrackOptions() }
    private val quality=if(watch.playbackMode==PlaybackMode.YOUTUBE)iconButton("Video quality","youtube-quality",ControlIcon(ControlIcon.QUALITY)){chooseTrack(C.TRACK_TYPE_VIDEO)} else null
    private val chapterButton=if(isYouTube)iconButton("Chapters","youtube-chapters",ControlIcon(ControlIcon.CHAPTERS)){showChapters()}.apply{visibility=View.GONE} else null
    private val options = if(watch.title.type!="youtube")iconButton("Picture controls", "vod-options", ControlIcon(ControlIcon.SETTINGS)) { openPictureControls() } else null
    private val closeButton = iconButton(if(watch.title.type=="youtube")"Back to YouTube" else "Back to streams", "vod-close", ControlIcon(ControlIcon.CLOSE)) { exit() }
    private val relatedButton=if(youtubePanel!=null)a.action("Related","Related videos"){openYouTubePanel(false)}.quiet() else null
    private val commentsButton=if(youtubePanel!=null)a.action("Comments","Video comments"){openYouTubePanel(true)}.quiet() else null
    private val videoActionsButton=if(watch.title.type=="youtube" && !isYouTube)a.action("•••","Video actions"){openVideoActions()}.quiet() else null
    private var videoActions:YouTubeVideoActions?=null
    private var closed = false
    private var touchPosition: Long? = null
    private var queuedSeek: Long? = null
    private var seekAt = 0L
    private val remoteSeek=RemoteSeekMotion(seekSeconds*1000L)
    private var retiredRemoteDown=Long.MIN_VALUE
    private var remoteFrameScheduled=false
    private val remoteFrame:Runnable=object:Runnable {
        override fun run() {
            remoteFrameScheduled=false
            if(closed || !remoteSeek.active)return
            if(!view.isAttachedToWindow || !view.isShown || !view.hasWindowFocus()) {
                if(isYouTube)finishYouTubeSeek(false) else finishHold()
                return
            }
            applyRemotePosition(remoteSeek.sample(SystemClock.uptimeMillis(),duration()))
            scheduleRemoteFrame()
        }
    }
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
    private var chapterMarkers:List<YouTubeChapter> = emptyList()
    private var youtubeChrome:YouTubePlayerChrome?=null
    private var youtubeActions:YouTubePlayerActions?=null
    private var seekPreview:YouTubeSeekPreview?=null
    private var youtubeSeeking=false
    private var youtubeSeekResume=false
    private var youtubeActionBusy=false
    private var youtubeClockDuration=Long.MIN_VALUE
    private var lastCaptionId:String?=null
    internal val isScrubbing get()=youtubeSeeking
    private var focusRequest=0
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
        else { if(isYouTube)finishYouTubeSeek(false) else finishHold();main.removeCallbacks(hideLater) }
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
        override fun onTracksChanged(tracks:androidx.media3.common.Tracks) { if(!closed && isYouTube)updateCaptions() }
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
        require(!watch.live || isYouTube) { "Live TV does not have transport controls" }
        if(watch.short)buildShorts() else if(isYouTube)buildYouTube() else {
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
                videoActionsButton?.let { addView(it,LinearLayout.LayoutParams(a.dp(44),a.dp(40)).apply{marginStart=a.dp(6)}) }
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
                previousVideo?.let { addView(it,LinearLayout.LayoutParams(a.dp(44),a.dp(44))) }
                addView(rewind, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
                addView(play, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { marginStart = a.dp(2); marginEnd = a.dp(2) })
                addView(forward, LinearLayout.LayoutParams(a.dp(44), a.dp(44)))
                nextVideo?.let { addView(it,LinearLayout.LayoutParams(a.dp(44),a.dp(44))) }
            }
            addView(transport, LinearLayout.LayoutParams(-2, a.dp(44)))
            addView(FrameLayout(a).apply {
                heatmap?.let { addView(it,FrameLayout.LayoutParams(-1,a.dp(44),Gravity.TOP)) }
                addView(bar, FrameLayout.LayoutParams(-1, a.dp(44), Gravity.TOP))
                addView(a.row().apply {
                    addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f)); add(remaining, width = -2)
                }, FrameLayout.LayoutParams(-1, timeHeight, Gravity.BOTTOM).apply { leftMargin = a.dp(4); rightMargin = a.dp(4) })
            }, LinearLayout.LayoutParams(0, stripHeight, 1f).apply { marginStart = a.dp(16); marginEnd = a.dp(16) })
            chapterButton?.let { addView(it,LinearLayout.LayoutParams(a.dp(44),a.dp(44)).apply{marginEnd=a.dp(6)}) }
            quality?.let { addView(it,LinearLayout.LayoutParams(a.dp(44),a.dp(44)).apply{marginEnd=a.dp(6)}) }
            addView(trackButton, LinearLayout.LayoutParams(a.dp(44), a.dp(44)).apply { marginEnd = a.dp(8) })
            options?.let { addView(it, LinearLayout.LayoutParams(a.dp(44), a.dp(44))) }
        }, height = stripHeight)
        bar.nextFocusUpId = closeButton.id; bar.nextFocusDownId = play.id
        closeButton.nextFocusDownId = bar.id; closeButton.nextFocusLeftId = bar.id
        closeButton.nextFocusUpId = closeButton.id; closeButton.nextFocusRightId = closeButton.id
        if(relatedButton!=null && commentsButton!=null) {
            bar.nextFocusUpId=relatedButton.id;relatedButton.nextFocusLeftId=bar.id;relatedButton.nextFocusRightId=commentsButton.id
            commentsButton.nextFocusLeftId=relatedButton.id;commentsButton.nextFocusRightId=videoActionsButton?.id?:closeButton.id
            closeButton.nextFocusLeftId=videoActionsButton?.id?:commentsButton.id
            listOf(relatedButton,commentsButton).forEach { it.nextFocusDownId=bar.id;it.nextFocusUpId=it.id }
        }
        videoActionsButton?.let {
            it.nextFocusLeftId=commentsButton?.id?:bar.id;it.nextFocusRightId=closeButton.id
            it.nextFocusUpId=it.id;it.nextFocusDownId=bar.id;closeButton.nextFocusLeftId=it.id
        }
        wireTransportFocus()
        view.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(a.dp(40), 0, a.dp(40), a.dp(20)) })
        view.addOnLayoutChangeListener(insetLayout); panel.addOnLayoutChangeListener(insetLayout)
        view.setOnClickListener { dismiss() }
        panel.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) interacted(); false }
        }
        if(!watch.short)bindTimeline()
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (view.visibility == View.VISIBLE) { startTicker(); scheduleHide(); publishInset() } }
            override fun onViewDetachedFromWindow(v: View) {
                if(isYouTube)finishYouTubeSeek(false) else finishHold()
                main.removeCallbacks(ticker);main.removeCallbacks(hideLater);publishInset()
            }
        })
        view.viewTreeObserver.addOnWindowFocusChangeListener(windowFocus)
        player.addListener(listener)
    }
    private fun bindTimeline() {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && canSeek()) { touchPosition = (duration().toDouble() * progress / seekBar.max).toLong();if(isYouTube)showSeekPreview();interacted(); update() }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { stopRemoteSeek(retire=true);if(isYouTube)beginYouTubeSeek() else {finishHold();touchPosition=position()};interacted() }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if(isYouTube){finishYouTubeSeek(true);return}
                val at = touchPosition; touchPosition = null
                if (at != null) { queuedSeek = null; main.removeCallbacks(applySeek); submitSeek(at) }
                interacted(); update()
            }
        })
        bar.setOnFocusChangeListener { _, focused -> if (!focused) { if(isYouTube)finishYouTubeSeek(true) else touchPosition=null;finishHold() } }
        bar.setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_CANCEL) { if(isYouTube)finishYouTubeSeek(false) else touchPosition=null;update();scheduleHide() };false }
    }
    private fun buildYouTube() {
        feedback.setTextColor(Color.WHITE)
        fun action(block:()->Unit){finishHold();interacted();block()}
        youtubeChrome=YouTubePlayerChrome(a,watch,view,panel,bar,heatmap,elapsed,remaining,feedback,play,previousVideo,nextVideo,chapterButton,closeButton,relatedButton!!,
            channel={action{youtubeActions?.channel()}},description={action{openYouTubeDescription()}},
            subscribe={action{youtubeActions?.subscribe()}},like={action{youtubeActions?.rate(YouTubeRating.LIKE)}},
            dislike={action{youtubeActions?.rate(YouTubeRating.DISLIKE)}},comments={action{openYouTubePanel(true)}},
            save={action{youtubeActions?.save()}},captions={action{toggleCaptions()}},settings={action{showYouTubeSettings()}})
        youtubeActions=YouTubePlayerActions(a,YouTubeVideo(watch.title.id,watch.title.name,watch.title.poster,live=watch.live)) { state ->
            if(!closed) {
                val completed=youtubeActionBusy && !state.busy;youtubeActionBusy=state.busy
                youtubeChrome?.render(state);wireTransportFocus()
                feedback.textIfChanged(state.error?:state.pending.orEmpty());feedback.visibility=if(feedback.text.isNotBlank())View.VISIBLE else View.GONE
                if(completed)interacted() else scheduleHide()
            }
        }
        youtubeActions?.metadata(a.model.youtube.preparedDetails(watch.title.id))
        seekPreview=YouTubeSeekPreview(a,watch).also {
            view.addView(it.view,FrameLayout.LayoutParams(a.dp(250),a.dp(180),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=a.dp(154)})
        }
        bar.nextFocusUpId=closeButton.id;bar.nextFocusDownId=play.id
        closeButton.nextFocusDownId=bar.id
        wireTransportFocus();updateCaptions()
        view.addOnLayoutChangeListener(insetLayout);panel.addOnLayoutChangeListener(insetLayout)
        view.setOnClickListener{dismiss()}
        panel.setOnTouchListener{_,event->if(event.actionMasked==MotionEvent.ACTION_DOWN)interacted();false}
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
        val actions=listOfNotNull(play,commentsButton,videoActionsButton,options,closeButton)
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
        imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(FOCUS, 0xDDFFFFFF.toInt()))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), a.round((ACCENT and 0x00FFFFFF) or 0x33000000, radius = 12))
            addState(intArrayOf(android.R.attr.state_pressed), a.round((ACCENT and 0x00FFFFFF) or 0x55000000, radius = 12))
            addState(intArrayOf(), a.round(Color.TRANSPARENT, radius = 12))
        }
        stateListAnimator = null; setOnClickListener { interacted(); action() }
    }
    fun show(focus: Boolean = true) {
        if (closed) return
        val request=++focusRequest;val input=a.focusInputGeneration
        view.visibility = View.VISIBLE; interactedAt = SystemClock.uptimeMillis(); update(); startTicker(); scheduleHide()
        publishInset(); view.post { publishInset() }
        if (focus) view.post { if (!closed && request==focusRequest && input==a.focusInputGeneration && view.isShown && view.hasWindowFocus()) play.requestFocus() }
    }
    fun surfaceClick(){if(watch.short)togglePlayback() else show()}
    /** Back dismisses the overlay; a later Back may exit. */
    fun dismiss(): Boolean {
        if (closed || view.visibility != View.VISIBLE) return false
        if(youtubeSeeking){finishYouTubeSeek(false);return true}
        hide(); return true
    }
    private fun hide() {
        focusRequest++
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
    private fun mayHide() = player.playWhenReady && player.playbackState != Player.STATE_ENDED && touchPosition == null && !remoteSeek.active && !loadingTracks && !youtubeActionBusy && dialog?.isShowing != true && view.hasWindowFocus()
    private fun scheduleHide() {
        main.removeCallbacks(hideLater)
        if (!closed && view.visibility == View.VISIBLE && mayHide()) main.postDelayed(hideLater, (5000 - (SystemClock.uptimeMillis() - interactedAt)).coerceAtLeast(50))
    }
    private fun duration() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
    private fun position() = player.currentPosition.coerceAtLeast(0)
    private fun canSeek() = !watch.short && (!isYouTube || player.isCurrentMediaItemSeekable) && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && duration() > 0
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
    private fun cursorPosition()=touchPosition?:remoteSeek.targetMs.takeIf{remoteSeek.active}?:queuedSeek?:position()
    private fun stopRemoteSeek(retire:Boolean=false) {
        if(retire && remoteSeek.active)retiredRemoteDown=remoteSeek.downTime
        remoteSeek.stop();view.removeCallbacks(remoteFrame);remoteFrameScheduled=false
    }
    private fun scheduleRemoteFrame() {
        if(!closed && remoteSeek.active && !remoteFrameScheduled) {
            remoteFrameScheduled=true;view.postOnAnimation(remoteFrame)
        }
    }
    private fun finishHold() {
        if(youtubeSeeking)finishYouTubeSeek(true) else {stopRemoteSeek();flushSeek()}
    }
    private fun seekAbsolute(at:Long) {
        if(!canSeek())return
        if(youtubeSeeking){touchPosition=at.coerceIn(0,duration());finishYouTubeSeek(true)}
        else {
            stopRemoteSeek(retire=true);queuedSeek=null;touchPosition=null;main.removeCallbacks(applySeek)
            submitSeek(at)
        }
        show(false)
    }
    private fun skip(direction:Int) {
        if(canSeek())seekAbsolute((cursorPosition()+direction*seekSeconds*1000L).coerceIn(0,duration()))
        else {finishHold();show(false)}
    }
    private fun applyRemotePosition(at:Long,immediate:Boolean=false) {
        if(isYouTube){touchPosition=at;showSeekPreview()}
        else {
            queuedSeek=at;main.removeCallbacks(applySeek)
            val wait=150-(SystemClock.uptimeMillis()-seekAt)
            if(immediate || wait<=0)flushSeek() else main.postDelayed(applySeek,wait)
        }
        interactedAt=SystemClock.uptimeMillis();update()
    }
    private fun timelineCode(code:Int):Int?=when(code) {
        KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->code
        KeyEvent.KEYCODE_PLUS,KeyEvent.KEYCODE_EQUALS->KeyEvent.KEYCODE_DPAD_RIGHT
        KeyEvent.KEYCODE_MINUS->KeyEvent.KEYCODE_DPAD_LEFT
        else->null
    }
    private fun remoteTimelineKey(code:Int,event:KeyEvent):Boolean {
        val direction=if(code==KeyEvent.KEYCODE_DPAD_RIGHT)1 else -1
        if(event.action==KeyEvent.ACTION_DOWN && canSeek()) {
            // A new phone/media command retired the old gesture. Later repeats
            // and its UP must not resurrect that cursor or its pause intention.
            if(retiredRemoteDown==event.downTime && event.repeatCount>0)return true
            val fresh=!remoteSeek.matches(direction,event.downTime)
            val origin=if(fresh && remoteSeek.active)remoteSeek.sample(event.eventTime,duration(),release=true) else cursorPosition()
            show(false)
            if(isYouTube)beginYouTubeSeek()
            val at=if(fresh) {
                retiredRemoteDown=Long.MIN_VALUE
                remoteSeek.begin(direction,event.downTime,event.eventTime,origin,duration())
            } else remoteSeek.sample(event.eventTime,duration())
            applyRemotePosition(at,immediate=fresh && !isYouTube);scheduleRemoteFrame()
        }else if(event.action==KeyEvent.ACTION_UP && remoteSeek.matches(direction,event.downTime)) {
            if(!event.isCanceled)applyRemotePosition(remoteSeek.sample(event.eventTime,duration(),release=true))
            if(isYouTube)finishYouTubeSeek(!event.isCanceled)
            else {
                stopRemoteSeek(retire=true)
                if(event.isCanceled){queuedSeek=null;main.removeCallbacks(applySeek);update()}else flushSeek()
                interacted();scheduleHide()
            }
        }
        return true
    }
    /** Shared by focused timeline keys and the first Left/Right from the video surface. */
    private fun timelineKey(code:Int,event:KeyEvent):Boolean = when(code) {
        KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->remoteTimelineKey(code,event)
        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0){if(youtubeSeeking)finishYouTubeSeek(true) else {finishHold();togglePlayback()}};true
        }
        else->false
    }
    private fun beginYouTubeSeek() {
        if(youtubeSeeking || !canSeek())return
        youtubeSeeking=true;youtubeSeekResume=player.playWhenReady;touchPosition=position()
        player.pause()
    }
    private fun showSeekPreview() {
        val at=touchPosition?:return
        seekPreview?.show(at,duration(),chapterMarkers.lastOrNull{it.startMs<=at}?.title.orEmpty())
    }
    private fun finishYouTubeSeek(commit:Boolean) {
        if(!youtubeSeeking)return
        val at=touchPosition;val resume=youtubeSeekResume
        stopRemoteSeek(retire=true);youtubeSeeking=false;touchPosition=null;seekPreview?.hide()
        if(commit && at!=null && canSeek() && at!=position())submitSeek(at)
        player.playWhenReady=resume
        if(!closed){interacted();update();startTicker()}
    }
    /** A newer phone command replaces an unfinished local preview, without
     * committing its cursor or restoring an older play/pause intention. */
    fun reconcileExternalSeek(playIntent:Boolean?,atMs:Long?):Boolean {
        if(closed || !isYouTube || !youtubeSeeking)return false
        val resume=playIntent?:youtubeSeekResume
        stopRemoteSeek(retire=true);touchPosition=null;queuedSeek=null
        main.removeCallbacks(applySeek);seekPreview?.hide()
        // Apply a phone position before resuming: an old ended position must not
        // trigger autoplay while the replacement seek is still pending.
        if(atMs!=null && canSeek())player.seekTo(atMs.coerceIn(0,duration()))
        youtubeSeeking=false;player.playWhenReady=resume
        interacted();update();startTicker()
        return true
    }
    private fun togglePlayback() {
        if (player.playbackState == Player.STATE_ENDED) startPlayback()
        else if (player.playWhenReady) player.pause() else player.play()
        show(false)
    }
    private fun startPlayback() {
        if (player.playbackState == Player.STATE_ENDED && canSeek()) {
            stopRemoteSeek(retire=true);queuedSeek = null;main.removeCallbacks(applySeek); player.seekTo(0)
        }
        player.play()
    }
    private fun update() {
        if (closed || view.visibility != View.VISIBLE) return
        val total = duration(); val at = cursorPosition().let { if (total > 0) it.coerceAtMost(total) else it }
        heatmap?.duration(total)
        val seekable = canSeek();val seekabilityChanged=bar.isEnabled!=seekable;bar.isEnabled=seekable
        if(isYouTube && !seekable && bar.hasFocus())play.requestFocus()
        var transportChanged=updateChapterVisibility(total) || seekabilityChanged
        val previousAvailable=previousVideo!=null && a.canStepYouTube(watch,-1)
        transportChanged=updateTransport(previousVideo,previousAvailable,previousAvailable) || transportChanged
        transportChanged=updateTransport(nextVideo,nextVideo!=null && a.canStepYouTube(watch,1)) || transportChanged
        if(!watch.short) {
            transportChanged=updateTransport(rewind,seekable) || transportChanged
            transportChanged=updateTransport(forward,seekable) || transportChanged
        }
        if(transportChanged)wireTransportFocus()
        bar.progress = if (total > 0) (at.toDouble() / total * bar.max).toInt() else 0
        bar.secondaryProgress = if (total > 0) (player.bufferedPosition.coerceAtLeast(at).toDouble().coerceAtMost(total.toDouble()) / total * bar.max).toInt() else 0
        val second = at / 1000
        if(isYouTube && (elapsedSecond!=second || youtubeClockDuration!=total)) {
            elapsedSecond=second;youtubeClockDuration=total
            val chapter=chapterMarkers.lastOrNull{it.startMs<=at}?.title
            elapsed.text=if(watch.live) {
                if(seekable)"−${format((total-at).coerceAtLeast(0))} · LIVE" else "● LIVE"
            }else "${format(at)} / ${if(total>0)format(total) else "–:–"}"+(chapter?.let{" · $it"}?:"")
        } else if (!isYouTube && !watch.short && elapsedSecond != second) {
            elapsedSecond = second
            val chapter=chapterMarkers.lastOrNull{it.startMs<=at}
            elapsed.text=if(chapter==null)format(at) else "${format(at)} · ${chapter.title}"
        }
        val left = if (total > 0) (total - at).coerceAtLeast(0) / 1000 else -1
        if (!watch.short && remainingSecond != left) { remainingSecond = left; remaining.text = if (left < 0) "–:–" else "−${format(left * 1000)}" }
        val ended = player.playbackState == Player.STATE_ENDED
        val paused = !player.playWhenReady || ended
        playIcon.mode = if (paused) ControlIcon.PLAY else ControlIcon.PAUSE
        play.contentDescription = if (ended) "Replay" else if (paused) "Play" else "Pause"
        youtubeChrome?.playbackChanged()
    }
    fun metadataChanged(){if(!closed){if(isYouTube)youtubeActions?.metadata(a.model.youtube.preparedDetails(watch.title.id));if(view.isShown)update()}}
    fun accountChanged(){youtubeActions?.accountChanged()}
    private fun stepVideo(direction:Int){finishHold();a.stepYouTube(watch,direction)}
    private fun relatedFromVideo()=if(isYouTube)view.visibility!=View.VISIBLE || !view.hasFocus() || youtubeChrome?.actions?.any{it.hasFocus()}==true else view.visibility!=View.VISIBLE || !view.hasFocus() || play.hasFocus() ||
        rewind.hasFocus() || forward.hasFocus() || previousVideo?.hasFocus()==true || nextVideo?.hasFocus()==true ||
        chapterButton?.hasFocus()==true || quality?.hasFocus()==true || trackButton.hasFocus() || options?.hasFocus()==true
    /** Only global transport/reveal keys are intercepted; visible widgets receive normal D-pad events. */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (closed) return false
        val code = event.keyCode
        val center=code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER
        val relatedDown=!watch.short && youtubePanel!=null && code==KeyEvent.KEYCODE_DPAD_DOWN
        val controlsUp=!watch.short && code==KeyEvent.KEYCODE_DPAD_UP
        if(relatedDown && event.action==KeyEvent.ACTION_DOWN)lastRelatedDown=event.downTime
        if(controlsUp && event.action==KeyEvent.ACTION_DOWN)lastControlsUpDown=event.downTime
        if (event.action == KeyEvent.ACTION_UP) {
            val consumed=consumedKeys.remove(code)
            // An opening DOWN's UP can go to the panel instead of these controls.
            // Its old entry must not swallow a later press's orphan release.
            if(consumed!=null && (!(relatedDown || controlsUp) || consumed==event.downTime)) {
                if(center)lastCenterRelease=event.downTime
                if(relatedDown)lastRelatedRelease=event.downTime
                if(controlsUp)lastControlsUpRelease=event.downTime
                return true
            }
            // Android can consume the first DOWN when leaving touch/focus mode and
            // still deliver its UP. Activate the focused Shorts surface once in that case.
            if(watch.short && center && (view.visibility!=View.VISIBLE || !view.hasFocus())) {
                if(!event.isCanceled && lastCenterRelease!=event.downTime)togglePlayback()
                lastCenterRelease=event.downTime;return true
            }
            if(relatedDown && lastRelatedDown!=event.downTime && relatedFromVideo()) {
                val fresh=lastRelatedRelease!=event.downTime;lastRelatedRelease=event.downTime
                if(!event.isCanceled && fresh)openYouTubePanel(false)
                return true
            }
            if(controlsUp && lastControlsUpDown!=event.downTime && (view.visibility!=View.VISIBLE || !view.hasFocus())) {
                val fresh=lastControlsUpRelease!=event.downTime;lastControlsUpRelease=event.downTime
                if(!event.isCanceled && fresh)show(true)
                return true
            }
        }
        val horizontal=code==KeyEvent.KEYCODE_DPAD_LEFT || code==KeyEvent.KEYCODE_DPAD_RIGHT
        // Do not consume an entire held direction just to reveal the HUD. Route its
        // first DOWN and every repeat/UP through the same accelerated timeline handler.
        if(!watch.short && horizontal && !consumedKeys.containsKey(code) && canSeek() &&
            (bar.hasFocus() || view.visibility!=View.VISIBLE || !view.hasFocus()) &&
            (event.action==KeyEvent.ACTION_DOWN || event.action==KeyEvent.ACTION_UP && remoteSeek.active)) {
            if(event.action==KeyEvent.ACTION_DOWN && (view.visibility!=View.VISIBLE || !bar.hasFocus())){show(false);bar.requestFocus()}
            return timelineKey(code,event)
        }
        consumedKeys[code]?.let { down ->
            if (event.repeatCount > 0 || event.downTime == down) return true
            consumedKeys.remove(code) // The preceding UP may have gone to a dialog/window.
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        fun consume(action: () -> Unit): Boolean { consumedKeys[code] = event.downTime; if (event.repeatCount == 0) action(); return true }
        when (code) {
            KeyEvent.KEYCODE_MENU -> if(watch.title.type=="youtube")return consume { if(isYouTube)showYouTubeSettings() else openVideoActions() }
            KeyEvent.KEYCODE_MEDIA_PLAY -> return consume { if(youtubeSeeking){youtubeSeekResume=true;finishYouTubeSeek(true)}else startPlayback();show(false) }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return consume { if(youtubeSeeking){youtubeSeekResume=false;finishYouTubeSeek(true)}else player.pause();show(false) }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return consume { if(youtubeSeeking){youtubeSeekResume=!youtubeSeekResume;finishYouTubeSeek(true);show(false)}else togglePlayback() }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> return consume { skip(-1) }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> return consume { skip(1) }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return consume { if(isYouTube)stepVideo(if(code==KeyEvent.KEYCODE_MEDIA_NEXT)1 else -1) }
        }
        if(watch.short && center &&
            (view.visibility!=View.VISIBLE || !view.hasFocus() || play.hasFocus()))return consume{togglePlayback()}
        if(relatedDown && relatedFromVideo())
            return consume { openYouTubePanel(false) }
        val navigation = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (navigation && (view.visibility != View.VISIBLE || !view.hasFocus())) return consume { show(true) }
        if (view.visibility == View.VISIBLE) interacted()
        return false
    }
    private fun openPictureControls() { if (!closed && watch.title.type!="youtube") { finishHold(); invalidateTrackRequest(); interacted(); pictureControls() } }
    private fun openYouTubeDescription(){if(!closed){hide();youtubeDescription?.invoke()}}
    private fun openYouTubePanel(comments:Boolean) { if(!closed){hide();youtubePanel?.invoke(comments)} }
    private fun openVideoActions() {
        if(closed || videoActions?.visible==true)return
        finishHold();interacted()
        videoActions=YouTubeVideoActions(a,YouTubeVideo(watch.title.id,watch.title.name,watch.title.poster,isShort=watch.short,live=watch.live)).also{it.show()}
    }
    fun replayMarkers(markers:List<YouTubeHeatMarker>){heatmap?.markers(markers);heatmap?.duration(duration())}
    /** Already-reduced watch-next metadata; no new request, decoder or thumbnail cache. */
    fun chapters(value:List<YouTubeChapter>) {
        if(closed || chapterButton==null)return
        chapterMarkers=value.asSequence().filter{it.startMs>=0 && it.title.isNotBlank()}
            .distinctBy{it.startMs}.sortedBy{it.startMs}.take(80).toList()
        if(updateChapterVisibility(duration()))wireTransportFocus()
        elapsedSecond=Long.MIN_VALUE;update()
    }
    private fun updateChapterVisibility(total:Long):Boolean {
        val visible=chapterMarkers.any{total<=0 || it.startMs<total}
        return updateTransport(chapterButton,canSeek(),visible)
    }
    private fun updateTransport(button:View?,enabled:Boolean,visible:Boolean=true):Boolean {
        if(button==null)return false
        val visibility=if(visible)View.VISIBLE else View.GONE
        val changed=button.isEnabled!=enabled || button.visibility!=visibility
        if(changed) {
            if((!enabled || !visible) && button.hasFocus())play.requestFocus()
            button.isEnabled=enabled;button.visibility=visibility;button.alpha=if(enabled)1f else .4f
        }
        return changed
    }
    private fun wireTransportFocus() {
        if(isYouTube) {
            val all=youtubeChrome?.actions.orEmpty().filter{it.visibility==View.VISIBLE && it.isEnabled}
            all.forEachIndexed { index,button ->
                button.nextFocusLeftId=all[(index-1).coerceAtLeast(0)].id
                button.nextFocusRightId=all[(index+1).coerceAtMost(all.lastIndex)].id
                button.nextFocusUpId=if(bar.isEnabled)bar.id else closeButton.id;button.nextFocusDownId=button.id
            }
            bar.nextFocusDownId=play.id
            bar.nextFocusUpId=chapterButton?.takeIf{it.visibility==View.VISIBLE && it.isEnabled}?.id?:closeButton.id
            chapterButton?.let{it.nextFocusLeftId=bar.id;it.nextFocusRightId=bar.id;it.nextFocusUpId=closeButton.id;it.nextFocusDownId=bar.id}
            return
        }
        val bottom=listOfNotNull(previousVideo,rewind,play,forward,nextVideo,chapterButton,quality,trackButton,options)
            .filter{it.visibility==View.VISIBLE && it.isEnabled}
        bottom.forEachIndexed { index,button ->
            button.nextFocusLeftId=bottom[(index-1).coerceAtLeast(0)].id
            button.nextFocusRightId=bottom[(index+1).coerceAtMost(bottom.lastIndex)].id
            button.nextFocusUpId=bar.id;button.nextFocusDownId=button.id
        }
    }
    private fun showChapters() {
        if(closed || !canSeek())return
        finishHold();interacted()
        val available=chapterMarkers.filter{it.startMs<duration()}
        if(available.isEmpty())return
        val current=available.indexOfLast{it.startMs<=position()}
        dialog=a.showActions("Chapters",available.mapIndexed { index,chapter ->
            (if(index==current)"✓  " else "    ")+"${format(chapter.startMs)}  ${chapter.title}" to {
                if(!closed)submitSeek(chapter.startMs)
            }
        })
        dialog?.listView?.setSelection(current.coerceAtLeast(0))
        dialog?.listView?.post{dialog?.takeIf{it.isShowing}?.listView?.setSelection(current.coerceAtLeast(0))}
    }
    private fun updateCaptions(){youtubeChrome?.captions(player.currentTracks.groups.any{it.type==C.TRACK_TYPE_TEXT && it.isSelected})}
    private fun toggleCaptions() {
        if(closed)return
        finishHold();interacted();val request=++trackRequest
        tracks(C.TRACK_TYPE_TEXT) { available -> main.post {
            if(closed || request!=trackRequest || !view.isShown)return@post
            val selected=available.firstOrNull{it.id!="off" && it.selected}
            if(selected!=null){lastCaptionId=selected.id;selectTrack(C.TRACK_TYPE_TEXT,"off");a.model.updatePreferences(a.model.preferences.copy(youtubeCaptions=false))}
            else {
                val next=available.firstOrNull{it.id==lastCaptionId && it.id!="off"}?:available.firstOrNull{it.id!="off"}
                if(next!=null){selectTrack(C.TRACK_TYPE_TEXT,next.id);a.model.updatePreferences(a.model.preferences.copy(youtubeCaptions=true,youtubeSubtitleLanguage=next.language.ifBlank{a.model.preferences.youtubeSubtitleLanguage}))}
                else {feedback.text="No captions available for this video.";feedback.visibility=View.VISIBLE}
            }
            updateCaptions();interacted()
        } }
    }
    private fun showYouTubeSettings() {
        if(closed)return
        finishHold();interacted()
        dialog=a.showActions("Playback settings",listOf(
            "Video quality" to {chooseTrack(C.TRACK_TYPE_VIDEO)},
            "Audio track" to {chooseTrack(C.TRACK_TYPE_AUDIO)},
            "Subtitles" to {chooseTrack(C.TRACK_TYPE_TEXT)},
            "Playback speed" to {
                val speeds=listOf(.25f,.5f,.75f,1f,1.25f,1.5f,1.75f,2f)
                dialog=a.chooseOption("Playback speed",speeds.map{if(it==1f)"Normal" else "${it}×"},speeds.indexOf(player.playbackParameters.speed)) { index ->
                    if(!closed){player.setPlaybackSpeed(speeds[index]);a.model.updatePreferences(a.model.preferences.copy(youtubeSpeed=speeds[index]));interacted()}
                }
            },
            ("Autoplay: "+if(a.model.preferences.youtubeAutoplay)"On" else "Off") to {
                a.model.updatePreferences(a.model.preferences.copy(youtubeAutoplay=!a.model.preferences.youtubeAutoplay));interacted()
            },
        ))
    }
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
                if (!closed) {
                    val choice=available[index];selectTrack(type,choice.id)
                    if(isYouTube && type==C.TRACK_TYPE_TEXT)a.model.updatePreferences(a.model.preferences.copy(youtubeCaptions=choice.id!="off",youtubeSubtitleLanguage=choice.language.ifBlank{a.model.preferences.youtubeSubtitleLanguage}))
                    interacted()
                }
            }
        } }
    }
    fun close() {
        if (closed) return
        closed=true;finishYouTubeSeek(false);youtubeActions?.close();youtubeActions=null;seekPreview?.close();seekPreview=null;youtubeChrome?.close(view);youtubeChrome=null
        stopRemoteSeek(retire=true);trackRequest++;queuedSeek = null;touchPosition = null
        player.removeListener(listener); main.removeCallbacksAndMessages(null);chapterMarkers=emptyList()
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocus)
        dialog?.dismiss(); dialog = null;videoActions?.close();videoActions=null; consumedKeys.clear(); view.visibility = View.GONE
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
    companion object { const val PLAY=0; const val PAUSE=1; const val REWIND=2; const val FORWARD=3; const val SETTINGS=4; const val CLOSE=5; const val TRACKS=6; const val QUALITY=7; const val NEXT=8; const val PREVIOUS=9; const val CHAPTERS=10 }
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
            NEXT -> { paint.style=Paint.Style.FILL;path.reset();path.moveTo(8f,6f);path.lineTo(23f,16f);path.lineTo(8f,26f);path.close();canvas.drawPath(path,paint);canvas.drawRect(23f,6f,26f,26f,paint) }
            PREVIOUS -> { paint.style=Paint.Style.FILL;path.reset();path.moveTo(24f,6f);path.lineTo(9f,16f);path.lineTo(24f,26f);path.close();canvas.drawPath(path,paint);canvas.drawRect(6f,6f,9f,26f,paint) }
            CHAPTERS -> { for(index in 0..2){val y=8f+index*8f;canvas.drawCircle(6f,y,1.8f,paint);canvas.drawRoundRect(12f,y-1.2f,27f,y+1.2f,1f,1f,paint)} }
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
