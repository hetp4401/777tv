@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.ui.*
import kotlinx.coroutines.*


internal data class PlaybackSnapshot(val engine:String, val playing:Boolean, val positionMs:Long,
    val width:Int,val height:Int,val audio:Boolean,val dropped:Long=0,val decoder:String="",
    val audioTrack:String="",val subtitleText:String="",val frameRateHint:Float=0f,val forwardCapMiB:Int=0,
    val displayFps:Double=0.0,val effectsActive:Boolean=false,val firstFrameMs:Long=0,val sourceMs:Long=0,val playbackMs:Long=0)

/** Explicit commands win, including zero. Account resume is a supplied hint, never a late seek. */
internal fun playbackStartPosition(watch:Watch,resumeEnabled:Boolean,localPositionMs:Long):Long = when {
    watch.live->0L
    watch.requestedPositionMs!=null->watch.requestedPositionMs.coerceAtLeast(0L)
    watch.short || !resumeEnabled->0L
    localPositionMs>0L->localPositionMs
    else->watch.remoteResumePositionMs?.coerceAtLeast(0L)?:0L
}

/** One hardware-decoded player per visible attachment; direct add-on URLs. */
internal class NativePlayback(private val a: MainActivity, private val m: FleetModel, private val original: Watch, private var fullscreen: Boolean = true, private val exit: () -> Unit) {
    internal val watch:Watch get()=original
    // Video and letterbox areas remain neutral; themes only color interface surfaces.
    val view = FrameLayout(a).apply { setBackgroundColor(0xFF080808.toInt()) }
    var player: ExoPlayer? = null; private set
    var mediaPlayer: Player? = null; private set
    var picture=VideoPicture(); private set
    private var pictureEffect:VideoPictureEffect?=null
    private var pictureDialog:AlertDialog?=null
    private var pictureResult:((String?)->Unit)?=null
    private var pendingPictureFrame=false
    private var savedTrackParameters:TrackSelectionParameters?=null
    private var savedPlaybackParameters:PlaybackParameters?=null
    private var pictureUnsupported:String?=null
    private val pictureMain=Handler(Looper.getMainLooper())
    private var pausedPictureAnchor:Long?=null
    private var pausedPictureRefresh:Runnable?=null
    private var controls: VodControls? = null
    private var liveInfo:LiveInfoOverlay?=null
    private var liveAnnounced=false
    private var youtubePanel:YouTubeWatchPanel?=null
    private var liveYouTubeActions:YouTubeVideoActions?=null
    private var replayRequest:Job?=null
    private var replayRequested=false
    private var bufferBudgetBytes=0
    private var buffering: View? = null
    private var exoDecoder=""
    private val requestedAtMs=original.playRequestedAtMs.takeIf{it>0}?:playbackNowMs()
    private var firstFrameMs=0L
    private var sourceMs=0L
    private var playbackMs=0L
    val snapshot: PlaybackSnapshot? get() = player?.let { exo ->
        val audio=exo.currentTracks.groups.firstOrNull { it.type==C.TRACK_TYPE_AUDIO && it.isSelected }
        val language=audio?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it).language } }.orEmpty()
        PlaybackSnapshot("ExoPlayer",exo.isPlaying,exo.currentPosition,exo.videoSize.width,exo.videoSize.height,audio!=null,
            exo.videoDecoderCounters?.droppedBufferCount?.toLong()?:0,decoder=exoDecoder,audioTrack=language,
            subtitleText=exo.currentCues.cues.mapNotNull{it.text}.joinToString("\n"),effectsActive=pictureEffect!=null,forwardCapMiB=bufferBudgetBytes/(1024*1024),firstFrameMs=firstFrameMs,sourceMs=sourceMs,playbackMs=playbackMs)
    }
    fun setFullscreen(value:Boolean) {
        fullscreen=value
        view.descendantFocusability=if(value)ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.background=a.round(0xFF080808.toInt(),radius=if(value)0 else 12);view.clipToOutline=!value
        if(value)(playerView?:view.findTag("button:Retry"))?.requestFocus()
        if(value)liveInfo?.show() else liveInfo?.hide()
    }
    private var playerView: PlayerView? = null
    private var session: MediaSession? = null
    private var attached: Watch? = null
    private var resolution: Job? = null
    private var progressJob: Job? = null
    private var historyPlayback:YouTubeHistoryPlayback?=null
    private var historyAccountLoad:Job?=null
    private var historyAccountLoadAttempted=false
    private var historyLoadGeneration=0
    private var historyFrameReady=false
    private var nextPrepared=false
    private var nextAccountEpoch:Int?=null
    private var running = false
    private var closed = false
    private var transitioning = false
    private var retries = 0
    private var startMs = playbackStartPosition(original,m.preferences.resume,m.resume(original.title,original.episode))
    private var playIntent=true
    fun start() { if (running || closed) return; if(original.live)playIntent=true;running=true; connect(false) }
    fun stop() { running=false; resolution?.cancel(); resolution=null; detach() }
    fun close() { stop(); closed=true;view.removeAllViews() }
    private fun message(text:String) { view.removeAllViews();view.addView(a.text(text,20f),FrameLayout.LayoutParams(-2,-2,Gravity.CENTER)) }
    private fun connect(refresh:Boolean) {
        resolution?.cancel();detach();message("Connecting…")
        resolution=a.lifecycleScope.launch {
            try {
                if(retries>0)delay((retries*1000L).coerceAtMost(3000))
                val w=if(original.title.type=="youtube" && (refresh || System.currentTimeMillis()-original.resolvedAtMs !in 0..300_000L))m.youtube.refreshWatch(original)
                    else if(original.live&&original.provider!=null&&original.channel!=null)m.repository.liveWatch(original.provider,original.channel,refresh) else original
                ensureActive();if(running&&!closed)attach(w)
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(running){transitioning=false;if(original.live&&retries<m.preferences.liveRetries){retries++;connect(true)}else fail((e as? UserMessage)?.message?:if(original.title.type=="youtube")"YouTube couldn't play this video. Try again." else "Couldn't connect to this channel. Check the provider or try again.")}}
        }
    }
    private fun attach(w:Watch) {
        if(firstFrameMs==0L)sourceMs=playbackNowMs()-requestedAtMs
        attached=w;transitioning=false;historyFrameReady=false
        val prefs=m.preferences
        val url=w.stream.url
        val p: Player
        view.removeAllViews()
            val source=OkHttpDataSource.Factory(YouTubeHttp.mediaClient).setUserAgent(w.headers["User-Agent"]?:"777tv/0.18 (Android TV)")
                .setDefaultRequestProperties(w.headers)
            val tracks=DefaultTrackSelector(a).apply{parameters=buildUponParameters().setPreferredAudioLanguage(prefs.audioLanguage.takeIf{it.isNotBlank()})
                .setPreferredTextLanguage(prefs.subtitleLanguage.takeIf{it.isNotBlank()}).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,!prefs.subtitles).setSelectUndeterminedTextLanguage(prefs.subtitles).build()}
            if(w.title.type=="youtube")tracks.parameters=tracks.buildUponParameters().setMaxVideoSize(1920,1080).setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264).build()
            val builder=ExoPlayer.Builder(a).setMediaSourceFactory(DefaultMediaSourceFactory(source)).setTrackSelector(tracks)
                .setSeekBackIncrementMs(prefs.seekSeconds*1000L).setSeekForwardIncrementMs(prefs.seekSeconds*1000L)
            val lowRam=(a.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
            bufferBudgetBytes=(if(w.playbackMode==PlaybackMode.SHORTS){if(lowRam)6 else 8}else if(lowRam)16 else 24)*1024*1024
            // Bound encoded samples for every source, including high-bitrate on-demand files.
            // Decoder surfaces are separate; the byte target can overshoot by a media chunk.
            val load=DefaultLoadControl.Builder().setTargetBufferBytes(bufferBudgetBytes).setPrioritizeTimeOverSizeThresholds(false)
            // Start with a short playable window, then retain the existing steady-state
            // and rebuffer thresholds. Faster startup must not shrink ongoing protection.
            if(w.live){val min=prefs.liveBufferSeconds*1000;load.setBufferDurationsMs(min,maxOf(15000,min*2),350,1000)}
            else if(w.short)load.setBufferDurationsMs(5000,15000,250,1000)
            else load.setBufferDurationsMs(10000,30000,500,2000)
            builder.setLoadControl(load.build())
            val exo=builder.build();player=exo
            if(w.playbackMode==PlaybackMode.SHORTS)exo.repeatMode=Player.REPEAT_MODE_ONE
            // Do not even set an empty effect list for neutral playback: doing
            // so creates a GPU sink instead of the direct MediaCodec Surface.
            if(!picture.isNeutral) {
                pictureEffect=VideoPictureEffect(picture)
                exo.setVideoEffects(listOf(pictureEffect!!))
            }
            savedTrackParameters?.let { exo.trackSelectionParameters=it }
            exo.addAnalyticsListener(object:AnalyticsListener {
                override fun onVideoDecoderInitialized(eventTime:AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long) {
                    if(player===exo) {
                        exoDecoder=decoderName
                        android.util.Log.i("FleetPlayback","ExoPlayer sourceId=${w.stream.identity} decoder=$decoderName")
                    }
                }
                override fun onVideoInputFormatChanged(eventTime:AnalyticsListener.EventTime,format:Format,decoderReuseEvaluation:DecoderReuseEvaluation?) {
                    val reason=pictureReason(format)
                    if(player===exo && pictureEffect!=null && reason!=null)view.post { rejectPicture(exo,reason) }
                }
                override fun onRenderedFirstFrame(eventTime:AnalyticsListener.EventTime,output:Any,renderTimeMs:Long) {
                    if(player===exo && firstFrameMs==0L)firstFrameMs=(playbackNowMs()-requestedAtMs).coerceAtLeast(1L)
                    if(player===exo && w.title.type=="youtube") {
                        // Optional account work begins after a real frame, then waits
                        // for watched time. Nothing is requested by focus or source loading.
                        historyFrameReady=true;mediaPlayer?.let{sampleHistory(w,it)}
                        if(w.playbackMode==PlaybackMode.YOUTUBE)refreshYouTubeNext()
                    }
                    if(player===exo && pendingPictureFrame) { pendingPictureFrame=false;pictureResult?.invoke(null) }
                }
            })
            p=if(w.live)LivePlayer(exo)else exo
            p.setAudioAttributes(AudioAttributes.DEFAULT,true)
            val pv=(LayoutInflater.from(a).inflate(R.layout.live_player,null) as PlayerView).apply{
                player=p;useController=false;resizeMode=if(w.short)AspectRatioFrameLayout.RESIZE_MODE_FIT else when(prefs.videoFit){"Fill"->AspectRatioFrameLayout.RESIZE_MODE_FILL;"Zoom"->AspectRatioFrameLayout.RESIZE_MODE_ZOOM;else->AspectRatioFrameLayout.RESIZE_MODE_FIT}
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                isFocusable=true;isFocusableInTouchMode=true;tag="native-player"
            };playerView=pv
            view.removeAllViews();view.addView(pv,FrameLayout.LayoutParams(-1,-1));if(fullscreen)pv.requestFocus()
        mediaPlayer=p
        a.youTubePhonePlaybackChanged()
        // Start source loading while the main thread builds controls and the media session.
        val item=MediaItem.Builder().setUri(url).apply { if(w.videoMimeType.isNotBlank())setMimeType(w.videoMimeType) }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(w.title.name).setSubtitle(w.episode?.label?:w.title.description.takeIf{w.title.type=="youtube"}).build())
            .setSubtitleConfigurations(w.captions.map { caption->MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(caption.url))
                .setMimeType(caption.mimeType).setLanguage(caption.language).build() }).build()
        if(w.audioUrl.isNotBlank()) {
            val factory=DefaultMediaSourceFactory(source)
            val audio=MediaItem.Builder().setUri(w.audioUrl).apply { if(w.audioMimeType.isNotBlank())setMimeType(w.audioMimeType) }.build()
            exo.setMediaSource(MergingMediaSource(factory.createMediaSource(item),factory.createMediaSource(audio)),startMs)
        } else p.setMediaItem(item,if(w.live)C.TIME_UNSET else startMs)
        if(w.short)p.repeatMode=Player.REPEAT_MODE_ONE
        p.setPlaybackParameters(savedPlaybackParameters?:PlaybackParameters(if(w.live)1f else prefs.speed));p.prepare();p.playWhenReady=playIntent
        view.tag="playback-mode:${w.playbackMode.name.lowercase()}"
        buffering=a.column().apply {
            tag="playback-buffering";gravity=Gravity.CENTER;pad(12);background=a.round(a.panelOverlay(176))
            add(ProgressBar(a).themeTint(),width=a.dp(32),height=a.dp(32));gap(8)
            add(a.text("Buffering…",13f),width=-2)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="Buffering video"
            visibility=if(p.playbackState==Player.STATE_READY || p.playbackState==Player.STATE_ENDED)View.GONE else View.VISIBLE
        }.also { view.addView(it,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER)) }
        if(!w.live) {
            controls=VodControls(a,w,p,prefs.seekSeconds,exit,
                focusVideo={ playerView?.requestFocus() },
                tracks={ type,done -> if(mediaPlayer===p)done(exoTracks(p,type)) },
                selectTrack={ type,id -> if(mediaPlayer===p)selectExoTrack(p,type,id) },
                controlsInset={ bottom ->
                    playerView?.subtitleView?.setPadding(0,0,0,if(bottom>0)bottom+a.dp(8) else 0)
                },pictureControls={ showPictureControls() },
                youtubePanel=if(w.title.type=="youtube")({comments:Boolean->showYouTubePanel(w,comments)}) else null).also { c ->
                view.addView(c.view,FrameLayout.LayoutParams(-1,-1))
                playerView?.setOnClickListener { c.surfaceClick() }
                c.show()
            }
        }else {
            liveInfo=LiveInfoOverlay(a,w).also { info->
                view.addView(info.view,FrameLayout.LayoutParams(-1,a.dp(150),Gravity.BOTTOM))
                playerView?.setOnClickListener { info.show() };if(fullscreen)info.show()
            }
        }
        session=MediaSession.Builder(a,p).build()
        p.addListener(object:Player.Listener{
            override fun onPlaybackStateChanged(state:Int){
                if(mediaPlayer!==p)return
                buffering?.visibility=if(state==Player.STATE_BUFFERING)View.VISIBLE else View.GONE
                keepAwake(p)
                if(state==Player.STATE_READY && w.live && !liveAnnounced){liveAnnounced=true;if(fullscreen)liveInfo?.show()}
                if(state==Player.STATE_READY && w.playbackMode==PlaybackMode.SHORTS)m.youtube.preloadShort(w)
                if(state==Player.STATE_READY && w.playbackMode==PlaybackMode.YOUTUBE && !replayRequested) {
                    replayRequested=true
                    replayRequest=a.lifecycleScope.launch {
                        // No readiness polling, and fast clip changes cancel before optional metadata starts.
                        delay(750)
                        try { val markers=m.youtube.client.heatmap(w.title.id);if(mediaPlayer===p)controls?.replayMarkers(markers) }
                        catch(e:CancellationException){throw e}
                        catch(_:Exception){ /* Replay metadata never interrupts playback. */ }
                    }
                }
                if(state==Player.STATE_ENDED){
                    historyPlayback?.sample(p.currentPosition,p.duration,false,ended=true)
                    m.saveProgress(w,p.duration.coerceAtLeast(0),p.duration);if(w.live)recover()
                    else if(w.playbackMode==PlaybackMode.YOUTUBE && p.playWhenReady)m.youtube.onRegularEnded(w,m.preferences.youtubeAutoplay)
                }
            }
            override fun onIsPlayingChanged(isPlaying:Boolean){if(mediaPlayer===p){
                if(isPlaying && playbackMs==0L)playbackMs=(playbackNowMs()-requestedAtMs).coerceAtLeast(1L)
                historyPlayback?.sample(p.currentPosition,p.duration,isPlaying);keepAwake(p)
            }}
            override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
                if(mediaPlayer===p)historyPlayback?.discontinuity(oldPosition.positionMs,newPosition.positionMs,p.duration,p.isPlaying)
            }
            override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) {
                if(mediaPlayer===p && playWhenReady)cancelPictureRefresh()
            }
            override fun onPlayerError(error:PlaybackException) {
                if(mediaPlayer!==p)return
                if(pictureEffect!=null && error.errorCode in listOf(PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
                        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)) {
                    // A bounded return to the known direct hardware path if a
                    // device cannot run the optional effects pipeline.
                    view.post { rejectPicture(exo,"Picture controls aren't supported for this video on this device.") }
                } else recover()
            }

        })
        keepAwake(p)
        progressJob=a.lifecycleScope.launch{
            var lastPosition=p.currentPosition;var movedAt=SystemClock.elapsedRealtime()
            var savedPosition=Long.MIN_VALUE;var savedDuration=Long.MIN_VALUE
            var diagnosticAt=0L
            val diagnostics=a.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0
            while(isActive&&mediaPlayer===p){
                delay(4000);val position=p.currentPosition
                sampleHistory(w,p)
                if(position>lastPosition+100||!p.playWhenReady)movedAt=SystemClock.elapsedRealtime()
                lastPosition=position
                if(position!=savedPosition || p.duration!=savedDuration) {
                    m.saveProgress(w,position,p.duration);savedPosition=position;savedDuration=p.duration
                }
                player?.takeIf{diagnostics && it.playWhenReady && SystemClock.elapsedRealtime()-diagnosticAt>=15000}?.let { exo ->
                    diagnosticAt=SystemClock.elapsedRealtime()
                    android.util.Log.i("FleetPlayback","ExoPlayer sourceId=${w.stream.identity} decoder=$exoDecoder video=${exo.videoSize.width}x${exo.videoSize.height} fps=${exo.videoFormat?.frameRate} bufferedMs=${exo.totalBufferedDuration} buffering=${exo.playbackState==Player.STATE_BUFFERING} dropped=${exo.videoDecoderCounters?.droppedBufferCount}")
                }
                if(p.playWhenReady&&p.playbackState!=Player.STATE_ENDED&&SystemClock.elapsedRealtime()-movedAt>30000){recover();return@launch}
            }
        }
    }
    private fun sampleHistory(watch:Watch,p:Player) {
        if(watch.title.type!="youtube" || mediaPlayer!==p || !historyFrameReady)return
        youTubeAccountChanged()
        if(historyPlayback?.matchesCurrentAccount()==false){historyPlayback?.finish();historyPlayback=null}
        if(historyPlayback==null) {
            historyPlayback=m.youtube.history.begin(watch.title.id)
            if(historyPlayback==null && !historyAccountLoadAttempted) {
                // Phone playback can bypass YouTubeScreen entirely. Load saved
                // credentials once, after the frame, without polling signed-out disk.
                historyAccountLoadAttempted=true;val generation=++historyLoadGeneration
                historyAccountLoad=a.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO){m.youtube.client.account()}
                        if(generation==historyLoadGeneration && mediaPlayer===p && running && !closed)sampleHistory(watch,p)
                    }catch(e:CancellationException){throw e}
                    catch(_:Exception){ /* Optional history never interrupts playback. */ }
                    finally{if(generation==historyLoadGeneration)historyAccountLoad=null}
                }
            }
        }
        historyPlayback?.sample(p.currentPosition,p.duration,p.isPlaying)
    }
    fun showPictureControls() {
        if(closed || original.title.type=="youtube" || player==null || pictureDialog?.isShowing==true)return
        controls?.dismiss()
        pictureDialog=showPictureControls(a,picture,::setPicture,actualPicture={picture},restoreAnchor=false) {
            pictureDialog=null
            if(!closed && fullscreen) {
                controls?.show(false)
                view.post { (view.findTag("vod-options")?:playerView)?.requestFocus() }
            }
        }
    }
    internal fun refreshYouTubeNext() {
        val p=mediaPlayer?:return
        if(closed || !historyFrameReady || original.playbackMode!=PlaybackMode.YOUTUBE || (m.route as? Route.Player)?.watch!==original)return
        nextPrepared=true;nextAccountEpoch=m.youtube.client.historyAccountEpoch()
        m.youtube.prepareNext(original) {
            if(!closed && running && mediaPlayer===p && (m.route as? Route.Player)?.watch===original) {
                nextAccountEpoch=m.youtube.client.historyAccountEpoch()
                controls?.metadataChanged()
                // A short video may finish while its optional metadata is loading.
                if(p.playbackState==Player.STATE_ENDED && p.playWhenReady)m.youtube.onRegularEnded(original,m.preferences.youtubeAutoplay)
            }
        }
    }
    internal fun youTubeAccountChanged() {
        if(nextPrepared && m.youtube.client.historyAccountEpoch()!=nextAccountEpoch)refreshYouTubeNext()
    }
    internal fun setPicture(requested:VideoPicture,done:(String?)->Unit) {
        val exo=player
        if(closed || exo==null) { done("Start a video before adjusting its picture.");return }
        val next=requested.normalized()
        if(next==picture) { done(null);return }
        if(!next.isNeutral) {
            pictureUnsupported?.let { done(it);return }
            val format=exo.videoFormat
            // The replacement player has not rediscovered the same proven
            // SDR stream yet. Continue accepting held-key updates into its
            // pending matrix instead of rolling the controls back.
            if(format==null && !(pendingPictureFrame && pictureEffect!=null)) {
                done("Wait for the video to start, then adjust its picture.");return
            }
            format?.let { pictureReason(it) }?.let { done(it);return }
        }
        val rebuild=picture.isNeutral!=next.isNeutral
        picture=next;pictureResult=done
        if(rebuild) {
            pendingPictureFrame=true
            rebuildForPicture()
        } else {
            pictureEffect?.update(next)
            if(!pendingPictureFrame) {
                if(!exo.playWhenReady)schedulePausedPictureRefresh(exo)
                done(null)
            }
        }
    }
    private fun cancelPictureRefresh() {
        pausedPictureRefresh?.let(pictureMain::removeCallbacks)
        pausedPictureRefresh=null;pausedPictureAnchor=null
    }
    private fun schedulePausedPictureRefresh(expected:ExoPlayer) {
        pausedPictureRefresh?.let(pictureMain::removeCallbacks)
        if(expected.playbackState==Player.STATE_ENDED)return
        val current=expected.currentPosition
        val anchor=pausedPictureAnchor?.takeIf{kotlin.math.abs(current-it)<=1}?:current.also{pausedPictureAnchor=it}
        val end=expected.duration.takeIf{it>1}?.minus(1)?:Long.MAX_VALUE
        val adjacent=if(anchor<end)anchor+1 else (anchor-1).coerceAtLeast(0)
        val target=if(current==anchor)adjacent else anchor
        pausedPictureRefresh=Runnable {
            pausedPictureRefresh=null
            if(player===expected && !expected.playWhenReady && pictureEffect!=null && expected.playbackState!=Player.STATE_ENDED) {
                // A same-position seek is a Media3 no-op. Alternating these
                // exact targets refreshes the paused frame without drift or
                // retaining full-resolution textures during normal playback.
                if(kotlin.math.abs(expected.currentPosition-anchor)>1) {
                    pausedPictureAnchor=null // A newer user seek already refreshed the frame.
                    return@Runnable
                }
                // Re-register a fresh matrix effect for the paused input;
                // a decoded frame alone can otherwise reuse the old graph.
                val refreshed=VideoPictureEffect(picture)
                pictureEffect=refreshed
                expected.setVideoEffects(listOf(refreshed))
                expected.setSeekParameters(SeekParameters.EXACT)
                expected.seekTo(target)
            }
        }.also{pictureMain.postDelayed(it,150)}
    }
    private fun pictureReason(format:Format):String? = when {
        ColorInfo.isTransferHdr(format.colorInfo) -> "Picture controls are available for SDR video. Use the TV's picture settings for HDR."
        format.drmInitData!=null -> "Picture controls aren't available for this protected video."
        else -> null
    }
    private fun rejectPicture(expected:ExoPlayer,reason:String) {
        if(player!==expected || pictureEffect==null)return
        pictureUnsupported=reason;picture=VideoPicture();pendingPictureFrame=false
        val result=pictureResult
        rebuildForPicture()
        result?.invoke(reason)
    }
    private fun rebuildForPicture() {
        val watch=attached?:return
        val exo=player?:return
        savedTrackParameters=exo.trackSelectionParameters
        savedPlaybackParameters=exo.playbackParameters
        detach(keepPictureControls=true)
        attach(watch)
    }
    private fun keepAwake(p:Player){if(p.isPlaying||(p.playWhenReady&&p.playbackState==Player.STATE_BUFFERING))a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    private fun detach(keepPictureControls:Boolean=false){
        historyFrameReady=false;historyLoadGeneration++
        if(historyAccountLoad?.isActive==true)historyAccountLoadAttempted=false
        historyAccountLoad?.cancel();historyAccountLoad=null
        if(original.short)m.youtube.cancelShortPreload()
        replayRequest?.cancel();replayRequest=null;replayRequested=false;youtubePanel?.close();youtubePanel=null
        liveYouTubeActions?.close();liveYouTubeActions=null
        cancelPictureRefresh()
        if(!keepPictureControls) { pictureDialog?.dismiss();pictureDialog=null;pictureResult=null;pendingPictureFrame=false }
        progressJob?.cancel();progressJob=null
        controls?.close();controls=null
        liveInfo?.close();liveInfo=null;liveAnnounced=false
        buffering=null
        val p=mediaPlayer;mediaPlayer=null;player=null;exoDecoder=""
        a.youTubePhonePlaybackChanged()
        if(p!=null)historyPlayback?.sample(p.currentPosition,p.duration,false)
        if(!keepPictureControls){historyPlayback?.finish();historyPlayback=null}
        if(p!=null)playIntent=p.playWhenReady
        if(p!=null){attached?.let{m.saveProgress(it,p.currentPosition,p.duration)};if(!original.live)startMs=p.currentPosition.coerceAtLeast(0)}
        playerView?.player=null;playerView=null;session?.release();session=null;p?.release();pictureEffect=null;attached=null
        a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun recover(){
        if(!running||transitioning)return
        transitioning=true
        if(original.live){if(retries<m.preferences.liveRetries){retries++;connect(true)}else fail("This channel stopped responding. Retry or choose another channel.");return}
        fail("This stream couldn't play. Try again or choose another stream.")
    }
    private fun fail(text:String){
        resolution?.cancel();resolution=null;detach();view.removeAllViews()
        val box=a.column().apply{pad(24);gravity=Gravity.CENTER;add(a.text(text,19f));gap(20)}
        val retry=a.action("Retry") {retries=0;transitioning=false;connect(original.live || original.title.type=="youtube")}
        box.add(a.row().apply{button(retry);button(a.action(if(original.title.type=="youtube")"Back to YouTube" else if(original.live)"Back to channels" else "Back to streams"){exit()})})
        view.addView(box,FrameLayout.LayoutParams(a.dp(640).coerceAtMost((view.width.takeIf{it>0}?:a.resources.displayMetrics.widthPixels)-a.dp(24)),-2,Gravity.CENTER))
        if(!fullscreen)return
        view.descendantFocusability=android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        box.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                if(retry.width>0&&retry.height>0){
                    view.clearFocus()
                    if(retry.requestFocusFromTouch())box.viewTreeObserver.removeOnPreDrawListener(this)
                }
                return true
            }
        })
    }
    fun dispatchKey(event:KeyEvent):Boolean {
        if(!fullscreen)return false
        if(youtubePanel?.visible==true) {
            // Native panel descendants handle navigation; global media buttons still reach the same player.
            return if(event.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND))controls?.dispatchKey(event)?:false else false
        }
        if(original.short &&
            event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS)) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)m.youtube.step(original,if(event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MEDIA_NEXT))1 else -1)
            return true
        }
        if(!original.live)return controls?.dispatchKey(event)?:false
        if(mediaPlayer==null)return false
        if(event.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))return true
        if(event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_INFO)) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 || event.action==KeyEvent.ACTION_UP && !event.isCanceled)liveInfo?.show()
            return true
        }
        if(event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND,KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER))return true
        if(event.keyCode==KeyEvent.KEYCODE_MENU) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
                if(original.title.type=="youtube")a.showActions("Live YouTube",listOf(
                    "Video actions" to {liveYouTubeActions?.close();liveYouTubeActions=YouTubeVideoActions(a,YouTubeVideo(original.title.id,original.title.name,original.title.poster,live=true)).also{it.show()}},
                    "Related videos" to {showYouTubePanel(original,false)},"Comments" to {showYouTubePanel(original,true)}))
                else showPictureControls()
            }
            return true
        }
        return false
    }
    fun dismissControls():Boolean = youtubePanel?.dismiss()==true || controls?.dismiss()==true
    private fun showYouTubePanel(watch:Watch,comments:Boolean) {
        if(closed || mediaPlayer==null)return
        val panel=youtubePanel?:YouTubeWatchPanel(a,watch,resized={width->playerView?.let {
            if(it.paddingRight!=width)it.setPadding(0,0,width,0)
        }}){playerView?.requestFocus()}.also {
            youtubePanel=it
            view.addView(it.view,FrameLayout.LayoutParams(-1,minOf(a.dp(320),(a.resources.displayMetrics.heightPixels*.6f).toInt()),Gravity.BOTTOM))
        }
        panel.show(comments)
    }
    fun trimMemory() {
        youtubePanel?.takeUnless{it.visible}?.let { it.close();view.removeView(it.view);youtubePanel=null }
    }
}

private fun exoTracks(p:Player,type:Int):List<PlaybackTrack> {
    val result=ArrayList<PlaybackTrack>()
    val automatic=type==C.TRACK_TYPE_VIDEO && p.trackSelectionParameters.overrides.values.none{it.mediaTrackGroup.type==type}
    if(type==C.TRACK_TYPE_VIDEO)result+=PlaybackTrack("auto","Auto",automatic)
    if(type==C.TRACK_TYPE_TEXT)result+=PlaybackTrack("off","Off",p.currentTracks.groups.none{it.type==type && it.isSelected})
    p.currentTracks.groups.forEachIndexed { groupIndex,group -> if(group.type==type) {
        for(i in 0 until group.length)if(group.isTrackSupported(i)) {
            val format=group.getTrackFormat(i)
            val label=if(type==C.TRACK_TYPE_VIDEO)listOfNotNull(format.height.takeIf{it>0}?.let{"${it}p"},format.frameRate.takeIf{it>0}?.toInt()?.let{"$it fps"}).joinToString(" · ").ifBlank{"Video ${i+1}"} else listOfNotNull(format.label?.takeIf{it.isNotBlank()},format.language?.takeIf{it!="und" && it.isNotBlank()},format.codecs)
                .distinct().joinToString(" · ").ifEmpty { (if(type==C.TRACK_TYPE_AUDIO)"Audio" else "Subtitles")+" ${i+1}" }
            result+=PlaybackTrack("$groupIndex:$i",label,group.isTrackSelected(i) && !automatic)
        }
    } }
    return result
}
private fun selectExoTrack(p:Player,type:Int,id:String) {
    val builder=p.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,id=="off")
    if(id!="off" && id!="auto") {
        val parts=id.split(':');if(parts.size!=2)return
        val group=p.currentTracks.groups.getOrNull(parts[0].toIntOrNull()?:return)?:return
        val track=parts[1].toIntOrNull()?:return
        if(group.type!=type || track !in 0 until group.length || !group.isTrackSupported(track))return
        builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup,listOf(track)))
    }
    p.trackSelectionParameters=builder.build()
}

/** Live-facing controls cannot pause or seek. Lifecycle/audio focus still owns the raw player. */
private class LivePlayer(player:Player):ForwardingPlayer(player) {
    override fun getAvailableCommands() = super.getAvailableCommands().buildUpon().removeAll(
        Player.COMMAND_PLAY_PAUSE,
        Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD,Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,Player.COMMAND_SEEK_TO_MEDIA_ITEM,Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS,Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
    override fun isCommandAvailable(command:Int)=availableCommands.contains(command)
    override fun pause() {}
    override fun setPlayWhenReady(playWhenReady:Boolean) { if(playWhenReady)super.setPlayWhenReady(true) }
    override fun seekTo(positionMs:Long) {}
    override fun seekTo(mediaItemIndex:Int,positionMs:Long) {}
    override fun seekBack() {}
    override fun seekForward() {}
    override fun seekToDefaultPosition() {}
    override fun seekToDefaultPosition(mediaItemIndex:Int) {}
    override fun seekToNext() {}
    override fun seekToNextMediaItem() {}
    override fun seekToPrevious() {}
    override fun seekToPreviousMediaItem() {}
}
