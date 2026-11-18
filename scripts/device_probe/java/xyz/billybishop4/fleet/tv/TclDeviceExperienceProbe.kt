@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.*
import android.view.*
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in physical-device measurements of the installed release. No fixture/config/account resets. */
class TclDeviceExperienceProbe {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(i)
    private lateinit var a:MainActivity
    private val report=JSONObject().put("usesFixtureBackend",false).put("dataCleared",false)
    private val stages=JSONArray()
    private fun <T> ui(block:()->T):T {if(Looper.myLooper()==Looper.getMainLooper())return block();var value:Any?=null;i.runOnMainSync{value=block()};@Suppress("UNCHECKED_CAST") return value as T}
    private fun await(ms:Long=45000,condition:()->Boolean){val end=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<end){if(condition())return;SystemClock.sleep(50)};check(condition()){"Device condition timed out"}}
    private fun tagged(tag:String)=ui{a.window.decorView.findTag(tag)}
    private fun descendants(v:View):Sequence<View> = sequence{yield(v);if(v is ViewGroup)for(n in 0 until v.childCount)yieldAll(descendants(v.getChildAt(n)))}
    private fun key(code:Int){val down=SystemClock.uptimeMillis();for(action in intArrayOf(0,1))i.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_DPAD))}
    private fun press(tag:String){await{tagged(tag)?.let{it.isShown && it.isEnabled && it.width>0}==true};ui{tagged(tag)!!.requestFocusFromTouch()};key(KeyEvent.KEYCODE_DPAD_CENTER)}
    private fun shot(name:String){device.executeShellCommand("screencap -p /sdcard/Download/777tv-tcl021-$name.png")}
    private fun write(){File(ui{a.getExternalFilesDir(null)!!},"tcl021-device-probe.json").writeText(report.put("stages",stages).toString(2))}
    private fun stage(name:String,block:(JSONObject)->Unit){val selected=InstrumentationRegistry.getArguments().getString("stage");if(selected!=null && !name.startsWith(selected,true))return;val r=JSONObject().put("name",name);val start=SystemClock.elapsedRealtime();stages.put(r);try{block(r);r.put("success",true)}catch(t:Throwable){r.put("success",false).put("errorType",t.javaClass.simpleName).put("error",t.message?.take(220)).put("youtubeError",ui{a.model.youtube.error}).put("catalogError",ui{a.model.error}).put("streamError",ui{a.model.streamsError});shot("failure-"+name.replace(' ','-'))}finally{r.put("elapsedMs",SystemClock.elapsedRealtime()-start);write()}}
    private fun memory():JSONObject {
        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info);val cache=ui{a.artwork.loader.memoryCache}
        return JSONObject().put("pssKiB",info.totalPss).put("privateDirtyKiB",info.totalPrivateDirty)
            .put("nativeAllocatedBytes",Debug.getNativeHeapAllocatedSize()).put("managedUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            .put("artworkBytes",cache?.size?:0).put("artworkMaxBytes",cache?.maxSize?:0).put("forcedGc",false)
    }
    private fun frameWork(name:String,block:()->Unit):JSONObject {
        val frames=Collections.synchronizedList(ArrayList<LongArray>());val thread=HandlerThread("tcl021-frame-metrics").apply{start()}
        val metricIds=intArrayOf(FrameMetrics.TOTAL_DURATION,FrameMetrics.LAYOUT_MEASURE_DURATION,FrameMetrics.DRAW_DURATION,
            FrameMetrics.DEADLINE,FrameMetrics.GPU_DURATION,FrameMetrics.SYNC_DURATION,FrameMetrics.COMMAND_ISSUE_DURATION,
            FrameMetrics.SWAP_BUFFERS_DURATION,FrameMetrics.UNKNOWN_DELAY_DURATION,FrameMetrics.INPUT_HANDLING_DURATION,
            FrameMetrics.ANIMATION_DURATION,FrameMetrics.FIRST_DRAW_FRAME)
        val listener=Window.OnFrameMetricsAvailableListener{_,f,dropped->frames.add(metricIds.map{f.getMetric(it)}.toLongArray()+dropped.toLong())}
        val hz=ui{a.window.decorView.display?.refreshRate?:60f};val begin=SystemClock.elapsedRealtime();val cpu=Process.getElapsedCpuTime()
        ui{a.window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper))}
        try{block();SystemClock.sleep(200)}finally{ui{a.window.removeOnFrameMetricsAvailableListener(listener)};thread.quitSafely();thread.join(5000)}
        val values=synchronized(frames){frames.toList()};fun percentile(column:Int,p:Double):Double{val x=values.map{it[column]}.sorted();return if(x.isEmpty())0.0 else x[((x.size-1)*p).toInt()]/1e6}
        return JSONObject().put("name",name).put("frames",values.size).put("displayHz",hz).put("elapsedMs",SystemClock.elapsedRealtime()-begin)
            .put("processCpuMs",Process.getElapsedCpuTime()-cpu).put("totalP50Ms",percentile(0,.5)).put("totalP95Ms",percentile(0,.95))
            .put("layoutP95Ms",percentile(1,.95)).put("drawP95Ms",percentile(2,.95)).put("framesOverDisplayPeriod",values.count{it[0]>1e9/hz})
            .put("deadlineP50Ms",percentile(3,.5)).put("gpuP95Ms",percentile(4,.95)).put("syncP95Ms",percentile(5,.95))
            .put("commandIssueP95Ms",percentile(6,.95)).put("swapP95Ms",percentile(7,.95)).put("unknownDelayP95Ms",percentile(8,.95))
            .put("inputHandlingP95Ms",percentile(9,.95)).put("animationP95Ms",percentile(10,.95))
            .put("nonFirstDrawFrames",values.count{it[11]==0L}).put("framesOverActualDeadline",values.count{it[11]==0L && it[3]>0 && it[0]>it[3]})
            .put("metricEventsDropped",values.sumOf{it[12]}).put("scope","UI window FrameMetrics CPU/GPU timings with per-frame deadline; not video presentation FPS")
    }
    private fun playerReady(id:String?=null){
        var error:String?=null;var observed:androidx.media3.exoplayer.ExoPlayer?=null
        val listener=object:Player.Listener{override fun onPlayerError(e:androidx.media3.common.PlaybackException){
            val http=generateSequence(e.cause){it.cause}.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            error=e.errorCodeName+(http?.let{" HTTP "+it.responseCode}?:"")
        }}
        try {await(60000){
            error?.let{throw IllegalStateException(it)}
            ui{a.player?.let{p->if(observed!==p){observed?.removeListener(listener);observed=p;p.addListener(listener)};p.playerError?.let{throw IllegalStateException(it.errorCodeName)}}
                if(tagged("button:Back to streams")?.isShown==true)error("Stream failed before first frame")
                val w=(a.model.route as? Route.Player)?.watch;w!=null && (id==null || w.title.id==id) && a.playback?.watch===w && a.player?.isPlaying==true && a.playback?.snapshot?.firstFrameMs?.let{it>0}==true}
        }}finally{ui{observed?.removeListener(listener)}}
    }
    private fun startup():JSONObject=ui{val s=a.playback!!.snapshot!!;JSONObject().put("firstFrameMs",s.firstFrameMs).put("sourceMs",s.sourceMs).put("activePlaybackMs",s.playbackMs)
        .put("width",s.width).put("height",s.height).put("decoder",s.decoder).put("audioSelected",s.audio).put("audibleAudioLatencyMeasured",false)
        .put("hardwareCodec",if(Build.VERSION.SDK_INT>=29)android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.firstOrNull{it.name==s.decoder}?.isHardwareAccelerated else null).put("sourceFps",a.player!!.videoFormat?.frameRate).put("mime",a.player!!.videoFormat?.sampleMimeType).put("speed",a.player!!.playbackParameters.speed)}
    private fun steady(seconds:Int):JSONObject {
        val p=ui{a.player!!};SystemClock.sleep(3000)
        val before=ui{p.videoDecoderCounters!!.apply{ensureUpdated()}.let{longArrayOf(it.renderedOutputBufferCount.toLong(),it.droppedBufferCount.toLong(),it.decoderInitCount.toLong(),p.currentPosition)}}
        val transitions=JSONArray();var rebuffers=0;val start=SystemClock.elapsedRealtime()
        val listener=object:Player.Listener{override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_BUFFERING)rebuffers++;transitions.put(JSONObject().put("atMs",SystemClock.elapsedRealtime()-start).put("state",state).put("bufferedMs",p.totalBufferedDuration))}}
        ui{p.addListener(listener)};val samples=JSONArray()
        repeat(seconds/5){SystemClock.sleep(5000);samples.put(ui{JSONObject().put("atMs",SystemClock.elapsedRealtime()-start).put("positionMs",p.currentPosition).put("bufferedMs",p.totalBufferedDuration).put("playing",p.isPlaying).put("state",p.playbackState).put("width",p.videoFormat?.width).put("height",p.videoFormat?.height).put("sourceFps",p.videoFormat?.frameRate).put("bitrate",p.videoFormat?.bitrate)})}
        val after=ui{p.removeListener(listener);p.videoDecoderCounters!!.apply{ensureUpdated()}.let{longArrayOf(it.renderedOutputBufferCount.toLong(),it.droppedBufferCount.toLong(),it.decoderInitCount.toLong(),p.currentPosition)}}
        return JSONObject().put("seconds",seconds).put("renderedFrames",after[0]-before[0]).put("droppedFrames",after[1]-before[1]).put("decoderReinitializations",after[2]-before[2])
            .put("positionAdvanceMs",after[3]-before[3]).put("rebuffers",rebuffers).put("samples",samples).put("transitions",transitions).put("memory",memory())
    }
    private fun leavePlayer(){ui{a.model.top(Section.SEARCH)};await{ui{a.player==null && a.model.route !is Route.Player}}}
    private fun searchShelf():YouTubeShelfView?=ui{(tagged("youtube-search-results") as? RecyclerView)?.getChildAt(0) as? YouTubeShelfView}
    private fun realSearch(text:String):Long {val start=SystemClock.elapsedRealtime();ui{a.model.top(Section.YOUTUBE);a.model.youtube.editQuery(text);a.model.youtube.submitSearch()};await(60000){ui{!a.model.youtube.busy && a.model.youtube.query==text && a.model.youtube.videos.isNotEmpty()} && searchShelf()?.firstVideo()?.isShown==true};return SystemClock.elapsedRealtime()-start}

    @Test fun physicalTclExperienceAndPerformance() {
        assumeTrue("Explicit physical TV opt-in required",InstrumentationRegistry.getArguments().getString("physicalTv")=="true")
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{a=it};val m=ui{a.model};val prefs=ui{m.preferences};val providers=ui{m.providers};val addons=ui{m.addons}
            report.put("version",i.targetContext.packageManager.getPackageInfo(i.targetContext.packageName,0).versionName).put("device",Build.MODEL).put("api",Build.VERSION.SDK_INT)
                .put("installedReleaseDebuggable",i.targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)
                .put("profilesCount",providers.size).put("addonsCount",addons.size).put("displayHz",ui{a.window.decorView.display.refreshRate}).put("initialMemory",memory())
            try {
                stage("catalog and settings"){r->
                    ui{m.top(Section.SEARCH)};await(65000){ui{!m.busy}}
                    r.put("initialCatalogError",ui{m.error}).put("shelves",ui{m.shelves.size}).put("addonHosts",ui{JSONArray(m.addons.map{JSONObject().put("host",runCatching{java.net.URI(it.manifestUrl).host}.getOrNull()).put("enabled",it.enabled)})})
                    if(ui{m.error!=null}){ui{m.search("")};await(65000){ui{!m.busy}}}
                    r.put("afterRetryError",ui{m.error}).put("shelvesAfterRetry",ui{m.shelves.size});shot("on-demand-home")
                    press("button:Settings");await{tagged("settings-list")?.isShown==true}
                    for(label in listOf("Playback","On demand","YouTube settings","Live TV settings","Appearance")) {
                        val view=ui{descendants(a.window.decorView).firstOrNull{it.contentDescription?.toString()==label && it.isShown && it.isFocusable}}
                        if(view!=null){ui{view.requestFocusFromTouch()};key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_LEFT)}
                    }
                    r.put("settingsNavigated",true);shot("settings");ui{m.top(Section.SEARCH)}
                }
                stage("remote keyboard and voice cancel"){r->
                    ui{m.top(Section.YOUTUBE);m.youtube.showSearch()};await{tagged("youtube-keyboard")?.isShown==true}
                    ui{tagged("youtube-keyboard")!!.requestFocusFromTouch()};key(KeyEvent.KEYCODE_DPAD_CENTER);key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await{ui{m.youtube.query=="ab"}};r.put("nativeKeysTyped",true);shot("youtube-keyboard")
                    await(60000){ui{!m.youtube.busy && m.youtube.videos.isNotEmpty()}};ui{a.onBackPressedDispatcher.onBackPressed()};await{tagged("youtube-keyboard")?.isShown!=true};r.put("backPreservesQuery",ui{m.youtube.query=="ab"})
                    val voice=android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    r.put("voiceProviderAvailable",a.packageManager.resolveActivity(voice,0)!=null)
                    if(a.packageManager.resolveActivity(voice,0)!=null){
                        ui{a.voiceSearchYouTube()};SystemClock.sleep(1200)
                        if(!ui{a.hasWindowFocus()}){repeat(3){if(!ui{a.hasWindowFocus()}){device.pressBack();SystemClock.sleep(250)}};await{ui{a.hasWindowFocus()}}}
                        r.put("voiceCanceledWithoutChangingQuery",ui{m.youtube.query=="ab"})
                    }
                    ui{m.top(Section.SEARCH)}
                }
                stage("YouTube home and search"){r->
                    val begin=SystemClock.elapsedRealtime();ui{m.top(Section.YOUTUBE);m.youtube.select(YouTubeFeed.HOME);m.youtube.refresh()}
                    await(65000){ui{!m.youtube.busy && (m.youtube.videos.isNotEmpty() || m.youtube.error!=null)}}
                    r.put("homeReadyMs",SystemClock.elapsedRealtime()-begin).put("homeVideos",ui{m.youtube.videos.size}).put("homeShelves",ui{m.youtube.homeShelves.size}).put("homeError",ui{m.youtube.error}).put("signedIn",ui{m.youtube.account!=null})
                    shot("youtube-home");r.put("searchReadyMs",realSearch("Big Buck Bunny"))
                    r.put("rows",ui{JSONArray(m.youtube.searchShelves.map{JSONObject().put("shorts",it.portrait).put("count",it.videos.size)})})
                    assertTrue(ui{m.youtube.searchShelves.dropLast(1).none{it.portrait}})
                    val row=searchShelf()!!;val count=ui{row.list.adapter!!.itemCount};val moves=minOf(12,count-1).coerceAtLeast(0)
                    ui{row.firstVideo()!!.requestFocusFromTouch()};await{ui{a.currentFocus?.tag==row.firstVideo()?.tag}}
                    val initialTag=ui{a.currentFocus?.tag?.toString()};val rightTarget=ui{m.youtube.searchShelves.first().videos.getOrNull(moves)?.id}
                    var reachedRight=false
                    val input=frameWork("search remote input") {repeat(moves){key(KeyEvent.KEYCODE_DPAD_RIGHT);SystemClock.sleep(70)};reachedRight=ui{a.currentFocus?.tag=="youtube-video:$rightTarget"};repeat(moves){key(KeyEvent.KEYCODE_DPAD_LEFT);SystemClock.sleep(70)}}
                    r.put("input",input).put("deliveredMoves",moves*2).put("reachedRightTarget",reachedRight).put("returnedToFirst",ui{a.currentFocus?.tag==initialTag})
                    r.put("attachedCards",ui{row.list.childCount}).put("memoryAfterNavigation",memory());shot("youtube-search")
                    r.put("warmInput",frameWork("warm search remote input"){repeat(moves){key(KeyEvent.KEYCODE_DPAD_RIGHT);SystemClock.sleep(70)};repeat(moves){key(KeyEvent.KEYCODE_DPAD_LEFT);SystemClock.sleep(70)}})
                    r.put("warmReturnedToFirst",ui{a.currentFocus?.tag==initialTag})
                }
                stage("regular YouTube playback"){r->
                    ui{m.youtube.play(YouTubeVideo("aqz-KE-bpKQ","Big Buck Bunny"))};playerReady("aqz-KE-bpKQ")
                    r.put("startup",startup());shot("youtube-video");r.put("steady",steady(30))
                    val p=ui{a.player!!};val decoderCount=ui{p.videoDecoderCounters!!.decoderInitCount}
                    key(KeyEvent.KEYCODE_DPAD_UP);await{tagged("vod-controls")?.isShown==true};press("button:Video comments")
                    await(45000){tagged("youtube-watch-panel")?.isShown==true};SystemClock.sleep(1500);shot("youtube-comments")
                    r.put("commentsSameDecoder",ui{a.player===p && p.videoDecoderCounters!!.decoderInitCount==decoderCount})
                    await{ui{(tagged("youtube-panel-list") as? RecyclerView)?.adapter?.itemCount?.let{it>0}==true}}
                    val comments=ui{((tagged("youtube-panel-list") as RecyclerView).adapter as NativeAdapter<*>).currentList.filterIsInstance<YouTubeComment>()}
                    r.put("commentsCount",comments.size).put("unlabeledReplyCounts",comments.count{it.repliesLabel.matches(Regex("[0-9, .KMBkmb]+"))})
                    val thread=comments.firstOrNull{it.repliesToken!=null}
                    if(thread!=null){
                        press("youtube-comment:"+thread.id);val button=device.wait(androidx.test.uiautomator.Until.findObject(By.res("android","button1")),5000)?:error("Missing reply action")
                        r.put("replyActionLabel",button.text);shot("comment-reply-dialog");button.click()
                        await(45000){ui{((tagged("youtube-panel-list") as? RecyclerView)?.adapter as? NativeAdapter<*>)?.currentList?.filterIsInstance<YouTubeComment>()?.any{reply->comments.none{it.id==reply.id}}==true}}
                        r.put("repliesLoaded",true);shot("replies");ui{a.onBackPressedDispatcher.onBackPressed()}
                    }
                    ui{a.playback!!.dismissControls()};key(KeyEvent.KEYCODE_DPAD_UP);press("button:Related videos");await{tagged("youtube-watch-panel")?.isShown==true}
                    await(45000){ui{(tagged("youtube-panel-list") as? RecyclerView)?.adapter?.itemCount?.let{it>0}==true || tagged("youtube-panel-status")?.isShown==true}}
                    r.put("relatedCount",ui{(tagged("youtube-panel-list") as RecyclerView).adapter?.itemCount?:0}).put("relatedStatus",ui{(tagged("youtube-panel-status") as TextView).text.toString()})
                    r.put("nextAvailableAfterMetadata",ui{(m.route as? Route.Player)?.watch?.let{a.canStepYouTube(it,1)}==true});shot("youtube-related")
                    ui{a.playback!!.dismissControls();p.pause();p.seekTo(30000)};await{ui{p.playbackState==Player.STATE_READY && kotlin.math.abs(p.currentPosition-30000)<1000}}
                    ui{a.playback!!.dismissControls()};val from=ui{p.currentPosition};key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    await{ui{p.currentPosition>=from+prefs.seekSeconds*1000-500}};r.put("hiddenSingleSeekMs",ui{p.currentPosition-from})
                    val holdFrom=ui{p.currentPosition};val down=SystemClock.uptimeMillis()
                    for((n,offset) in listOf(0L,500L,650L).withIndex()) {val wait=down+offset-SystemClock.uptimeMillis();if(wait>0)SystemClock.sleep(wait);i.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,n,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_DPAD))}
                    i.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT,0));SystemClock.sleep(400)
                    val heldTo=ui{p.currentPosition};r.put("holdAdvanceMs",heldTo-holdFrom)
                    key(KeyEvent.KEYCODE_DPAD_LEFT);await{ui{p.currentPosition<=heldTo-prefs.seekSeconds*1000+500}}
                    r.put("releaseResetStepMs",heldTo-ui{p.currentPosition}).put("seekPreservedPause",ui{!p.playWhenReady})
                    ui{p.play()};SystemClock.sleep(6500);r.put("hudAutoHidden",tagged("vod-controls")?.isShown!=true).put("pictureControlAbsent",tagged("vod-options")==null)
                    shot("youtube-hidden-controls");leavePlayer();r.put("memoryAfterBack",memory())
                }
                stage("Shorts"){r->
                    ui{m.top(Section.YOUTUBE);m.youtube.select(YouTubeFeed.SHORTS)};await(65000){ui{!m.youtube.busy && m.youtube.videos.size>=2}}
                    val clips=ui{m.youtube.videos.take(3)};ui{m.youtube.play(clips.first())};playerReady(clips.first().id)
                    r.put("startup",startup()).put("steady",steady(20));shot("shorts")
                    r.put("noSeekBar",tagged("vod-progress")==null).put("pictureControlAbsent",tagged("vod-options")==null)
                    ui{a.playback!!.dismissControls()};val begin=SystemClock.elapsedRealtime();key(KeyEvent.KEYCODE_DPAD_DOWN);playerReady(clips[1].id)
                    r.put("nextClipMs",SystemClock.elapsedRealtime()-begin).put("nextStartup",startup());SystemClock.sleep(2000)
                    key(KeyEvent.KEYCODE_DPAD_UP);playerReady(clips.first().id);r.put("previousWorks",true);leavePlayer();r.put("memoryAfterBack",memory())
                }
                stage("on demand streams and episodes"){r->
                    ui{m.top(Section.SEARCH);m.search("Cars")};await(65000){ui{!m.busy && (m.results.isNotEmpty() || m.error!=null)}}
                    val title=ui{m.results.firstOrNull{it.id=="tt0317219"}?:m.results.firstOrNull{it.type=="movie"}}?:error("No on-demand movie result")
                    val old=ui{m.progress.firstOrNull{it.title.key==title.key && it.episode==null}}
                    val begin=SystemClock.elapsedRealtime();ui{m.open(Route.Detail(title))};await(65000){ui{!m.streamsBusy && (m.streams.isNotEmpty() || m.streamsError!=null)}}
                    r.put("streamsReadyMs",SystemClock.elapsedRealtime()-begin).put("streamCount",ui{m.streams.size}).put("streamError",ui{m.streamsError});shot("movie-streams")
                    await{tagged("streams") is RecyclerView && ui{(tagged("streams") as RecyclerView).adapter!!.itemCount==m.streams.size}}
                    val candidates=ui{((tagged("streams") as RecyclerView).adapter as NativeAdapter<Stream>).currentList.toList()}.let{all->(all.take(2)+all.filter{Regex("1080|720",RegexOption.IGNORE_CASE).containsMatchIn(it.name+it.description) && !Regex("2160|4K|REMUX|HEVC|x265",RegexOption.IGNORE_CASE).containsMatchIn(it.name+it.description)}.sortedBy{it.sizeBytes?:Double.MAX_VALUE}).distinctBy{it.identity}.take(4)}
                    val attempts=JSONArray();r.put("sourceAttempts",attempts);var played=false
                    try {
                        for((index,stream) in candidates.withIndex()) {
                            val attempt=JSONObject().put("index",index).put("headerCount",stream.headers.size);attempts.put(attempt)
                            ui{m.open(Route.Detail(title))};await(65000){ui{!m.streamsBusy && m.streams.isNotEmpty()}}
                            val list=tagged("streams") as RecyclerView
                            await{ui{(list.adapter as NativeAdapter<Stream>).currentList.any{it.name==stream.name && it.description==stream.description && it.sizeBytes==stream.sizeBytes && it.addonName==stream.addonName}}}
                            val position=ui{(list.adapter as NativeAdapter<Stream>).currentList.indexOfFirst{it.name==stream.name && it.description==stream.description && it.sizeBytes==stream.sizeBytes && it.addonName==stream.addonName}};check(position>=0)
                            val chosen=ui{(list.adapter as NativeAdapter<Stream>).currentList[position]}
                            attempt.put("visibleCardIndex",position).put("sourceIdentityHash",chosen.identity)
                            ui{list.scrollToPosition(position)}
                            await{ui{list.findViewHolderForAdapterPosition(position)?.itemView?.isShown==true}}
                            ui{descendants(list.findViewHolderForAdapterPosition(position)!!.itemView).first{it.isFocusable}.requestFocusFromTouch()}
                            key(KeyEvent.KEYCODE_DPAD_CENTER)
                            await{ui{m.route is Route.Player}}
                            attempt.put("normalRouteHeadersMatched",ui{(m.route as Route.Player).watch.headers==chosen.headers}).put("normalRouteStreamMatched",ui{(m.route as Route.Player).watch.stream.identity==chosen.identity})
                            try {playerReady(title.id);attempt.put("success",true).put("startup",startup());r.put("startup",startup()).put("steady",steady(30));shot("on-demand-player");played=true;break}
                            catch(e:IllegalStateException){attempt.put("success",false).put("error",e.message?.take(160));shot("on-demand-source-error-"+index)}
                        }
                        check(played){"No selected on-demand sources played"}
                    } finally {leavePlayer();if(old!=null)ui{m.store.progress(old)}}
                }
                stage("episodes navigation"){r->
                    ui{m.top(Section.SEARCH)}
                    ui{m.search("Silicon Valley")};await(65000){ui{!m.busy && m.results.isNotEmpty()}}
                    val series=ui{m.results.firstOrNull{it.type=="series"}}?:error("No series result")
                    ui{m.open(Route.Detail(series))};await(65000){ui{!m.busy && m.detail?.episodes?.isNotEmpty()==true}}
                    r.put("episodes",ui{m.detail!!.episodes.size});await{(tagged("episodes") as? RecyclerView)?.let{ui{it.childCount>0}}==true}
                    val episodes=tagged("episodes") as RecyclerView
                    await{ui{!m.streamsBusy && tagged("season-selector")?.isShown==true}}
                    key(KeyEvent.KEYCODE_DPAD_UP)
                    ui{check(tagged("season-selector")!!.requestFocusFromTouch())};await{ui{a.currentFocus?.tag=="season-selector"}}
                    key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    await{ui{
                        val child=a.currentFocus
                        child?.tag?.toString()?.startsWith("episode:")==true && a.hasWindowFocus() &&
                            episodes.findContainingViewHolder(child)!=null && episodes.scrollState==RecyclerView.SCROLL_STATE_IDLE &&
                            !episodes.hasPendingAdapterUpdates() && !episodes.isLayoutRequested && !episodes.isComputingLayout &&
                            child.left>=episodes.paddingLeft && child.right<=episodes.width-episodes.paddingRight
                    }}
                    val first=ui{a.currentFocus?.tag?.toString()}
                    val known=ui{m.detail!!.episodes.sortedWith(compareBy({it.season},{it.number})).map{"episode:"+it.id}};val start=known.indexOf(first);check(start>=0){"Episode rail must own focus before measurement"};r.put("startingSortedIndex",start);val originalStreams=ui{m.streams};val moves=minOf(18,(known.lastIndex-start).coerceAtLeast(0))
                    r.put("episodeFrames",frameWork("episode remote input"){repeat(moves){key(KeyEvent.KEYCODE_DPAD_RIGHT);SystemClock.sleep(60)}})
                        .put("episodeMovesDelivered",moves).put("episodeIndexAdvance",known.indexOf(ui{a.currentFocus?.tag?.toString()})-start)
                    await{ui{a.currentFocus?.tag==known[start+moves] && episodes.scrollState==RecyclerView.SCROLL_STATE_IDLE}}
                    assertEquals("Every episode move should advance the real sorted rail",start+moves,known.indexOf(ui{a.currentFocus?.tag?.toString()}))
                    r.put("finalSortedIndex",known.indexOf(ui{a.currentFocus?.tag?.toString()})).put("streamsUnchangedWhileBrowsing",ui{m.streams===originalStreams})
                    val targetId=ui{a.currentFocus!!.tag.toString().removePrefix("episode:")};key(KeyEvent.KEYCODE_DPAD_CENTER)
                    await(65000){ui{m.selectedEpisode?.id==targetId && !m.streamsBusy}}
                    val selected=ui{m.selectedEpisode!!}
                    await{ui{(tagged("source-context") as? TextView)?.text?.contains("Episode ${selected.number}")==true}}
                    r.put("selectedEpisodeContextUpdated",true).put("renderedSeasonAfterSelection",ui{(tagged("season-selector") as TextView).text.toString()})
                    shot("episodes-selected")
                    ui{m.top(Section.SEARCH)}
                }
                stage("memory cycles"){r->
                    val cycles=JSONArray()
                    repeat(3){n->
                        ui{m.youtube.play(YouTubeVideo("aqz-KE-bpKQ","Big Buck Bunny"))};playerReady("aqz-KE-bpKQ")
                        SystemClock.sleep(5000);val during=memory();val start=startup();leavePlayer();SystemClock.sleep(2000)
                        cycles.put(JSONObject().put("cycle",n+1).put("startup",start).put("during",during).put("afterBack",memory()))
                    }
                    r.put("cycles",cycles);Runtime.getRuntime().gc();System.runFinalization();SystemClock.sleep(600)
                    r.put("afterExplicitGc",memory().put("forcedGc",true))
                }
                stage("controlled live playback"){r->
                    val watch=Watch(Title("local-live-diagnostic","Live playback check"),Stream("Generated HLS","http://127.0.0.1:8893/live.m3u8"),live=true,playRequestedAtMs=playbackNowMs())
                    ui{m.open(Route.Player(watch))};playerReady(watch.title.id)
                    r.put("fixtureMedia",true).put("profileProvisioned",false).put("startup",startup()).put("steady",steady(20))
                    r.put("noSeekBar",tagged("vod-progress")==null).put("noMovieControls",tagged("vod-controls")==null)
                    val p=ui{a.player!!};val before=ui{p.currentPosition}
                    key(KeyEvent.KEYCODE_MEDIA_PAUSE);key(KeyEvent.KEYCODE_DPAD_RIGHT);SystemClock.sleep(500)
                    r.put("pauseAndSeekIgnored",ui{p.playWhenReady && p.currentPosition>=before && p.currentPosition<before+3000})
                    key(KeyEvent.KEYCODE_DPAD_CENTER);shot("controlled-live");leavePlayer()
                }
                stage("Live TV availability"){r->
                    ui{m.top(Section.LIVE)};SystemClock.sleep(1000);r.put("existingProfiles",providers.size)
                    if(providers.isEmpty())r.put("playbackTested",false).put("reason","No IPTV profile configured; none added")
                    else r.put("playbackTested",false).put("reason","Existing profiles preserved; manual channel validation pending")
                    shot("live");ui{m.top(Section.SEARCH)}
                }
            }finally {
                ui{m.top(Section.SEARCH)};await{ui{a.player==null}}
                report.put("preferencesPreserved",ui{m.preferences==prefs}).put("profilesPreserved",ui{m.providers==providers}).put("addonsPreserved",ui{m.addons==addons})
                    .put("finalMemory",memory()).put("explicitGoogleMutations",false).put("signOutPerformed",false)
                shot("final-on-demand");write()
            }
        }
        assertTrue("One or more physical-device stages failed; inspect the saved report",(0 until stages.length()).all{stages.getJSONObject(it).optBoolean("success")})
    }
}
