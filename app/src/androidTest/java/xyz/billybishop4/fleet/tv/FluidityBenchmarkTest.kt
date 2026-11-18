@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.*
import android.view.*
import android.widget.ImageView
import androidx.media3.exoplayer.LoadControl
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** Identical generated artwork/media, input and measurement code in the before/after snapshots. */
class FluidityBenchmarkTest:NativeTestBase() {
    companion object {
        @BeforeClass @JvmStatic fun localDefaultsBeforeAnyActivityStarts() {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            // Keep cold startup deterministic too: no public catalog requests race fixture setup.
            Store(context).addons(defaultAddons().map { addon->addon.copy(manifestUrl="http://10.0.2.2:8878/addons/"+
                (if(addon.id=="com.linvo.cinemeta")"cinemeta" else "gdrive")+"/manifest.json") })
        }
    }
    private val base="http://10.0.2.2:8878"
    private fun request(path:String)=runBlocking { Http().text("$base/youtube/performance/$path",timeout=if(path=="prepare")120000 else 20000) }
    @Before fun setup() {
        request("prepare")
        ui { a.model.top(Section.SEARCH);a.model.updatePreferences(Preferences());a.model.server(base)
            a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE) }
        await { tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy} }
        ui{a.artwork.loader.memoryCache?.clear();a.posterPool.clear()}
    }
    private fun report(name:String,value:JSONObject) {
        value.put("device",Build.MODEL).put("api",Build.VERSION.SDK_INT).put("renderer",InstrumentationRegistry.getArguments().getString("renderer"))
        value.put("version",ui{a.packageManager.getPackageInfo(a.packageName,0).versionName})
        File(ui{a.getExternalFilesDir(null)!!},name).writeText(value.toString(2))
    }
    private fun memory():JSONObject {
        // These retained-memory samples explicitly use GC at the same phase boundaries in both versions.
        Runtime.getRuntime().gc();System.runFinalization();Runtime.getRuntime().gc();SystemClock.sleep(400)
        val data=Debug.MemoryInfo();Debug.getMemoryInfo(data)
        val cache=ui{a.artwork.loader.memoryCache}
        val bitmaps=cache?.keys.orEmpty().mapNotNull{cache?.get(it)?.bitmap}
        return JSONObject().put("pssKiB",data.totalPss).put("privateDirtyKiB",data.totalPrivateDirty)
            .put("nativeAllocatedBytes",Debug.getNativeHeapAllocatedSize())
            .put("managedUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            .put("artworkCacheBytes",cache?.size?:0L).put("artworkCacheMaxBytes",cache?.maxSize?:0L)
            .put("artworkEntries",bitmaps.size).put("artworkPixelBytes",bitmaps.sumOf{it.width.toLong()*it.height*4})
            .put("bitmapSample",JSONArray(bitmaps.take(3).map{JSONObject().put("width",it.width).put("height",it.height)
                .put("config",it.config.toString()).put("platformAllocationBytes",it.allocationByteCount)}))
    }
    private fun installFeed() {
        val videos=(1..120).map { n->YouTubeVideo("P"+n.toString().padStart(10,'0'),"A generated video title $n",
            "$base/youtube/performance/thumbnail/$n.jpg","Generated channel $n","120K views · 2 days ago","12:34") }
        ui{a.model.youtube.installFixturePage(YouTubeFeed.SEARCH,YouTubePage(videos))}
        await { (tagged("youtube-results") as? RecyclerView)?.let{ui{it.adapter?.itemCount==120 && it.childCount>=4}}==true }
    }
    @Test fun distinctArtworkScrollingInputAndRetainedMemory() {
        installFeed();val grid=tagged("youtube-results") as RecyclerView
        await { ui{descendants(grid).filterIsInstance<ImageView>().count{it.drawable!=null}>=4} }
        val initial=memory();val samples=JSONArray()
        repeat(2) { trial ->
            ui{grid.scrollToPosition(0)};SystemClock.sleep(350)
            val frames=Collections.synchronizedList(ArrayList<LongArray>(160))
            val thread=HandlerThread("777tv-frame-benchmark").apply{start()}
            val callback=Window.OnFrameMetricsAvailableListener { _,f,_->frames.add(longArrayOf(f.getMetric(FrameMetrics.TOTAL_DURATION),
                f.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),f.getMetric(FrameMetrics.DRAW_DURATION))) }
            val done=CountDownLatch(1);val gc=Debug.getRuntimeStats();val cpu=Process.getElapsedCpuTime();val start=SystemClock.elapsedRealtime()
            ui {
                a.window.addOnFrameMetricsAvailableListener(callback,Handler(thread.looper))
                Choreographer.getInstance().postFrameCallback(object:Choreographer.FrameCallback {
                    var step=0
                    override fun doFrame(time:Long) {
                        grid.scrollBy(0,a.dp(if(step<60)16 else -16))
                        if(++step<120)Choreographer.getInstance().postFrameCallback(this) else done.countDown()
                    }
                })
            }
            try { assertTrue("Benchmark frame loop stalled",done.await(90,TimeUnit.SECONDS));SystemClock.sleep(200) }
            finally { ui{a.window.removeOnFrameMetricsAvailableListener(callback)};thread.quitSafely();thread.join(5000) }
            val captured=synchronized(frames){frames.toList()}
            fun p(column:Int,fraction:Double):Double { val values=captured.map{it[column]}.sorted();return if(values.isEmpty())0.0 else values[((values.size-1)*fraction).toInt()]/1e6 }
            samples.put(JSONObject().put("trial",trial).put("scrollSteps",120).put("elapsedMs",SystemClock.elapsedRealtime()-start)
                .put("processCpuMs",Process.getElapsedCpuTime()-cpu).put("frames",captured.size).put("frameP50Ms",p(0,.5)).put("frameP95Ms",p(0,.95))
                .put("layoutP95Ms",p(1,.95)).put("drawP95Ms",p(2,.95)).put("gcBefore",JSONObject(gc)).put("gcAfter",JSONObject(Debug.getRuntimeStats())))
        }
        val afterScroll=memory();val input=JSONArray()
        for(held in listOf(false,true)) {
            ui{grid.scrollToPosition(0)}
            await{tagged("youtube-video:P0000000001")?.let{it.isShown && it.width>0}==true}
            ui{tagged("youtube-video:P0000000001")!!.requestFocus()};await{ui{a.currentFocus?.tag=="youtube-video:P0000000001"}}
            val started=SystemClock.elapsedRealtime();val down=SystemClock.uptimeMillis()
            repeat(18) { n->
                if(held)InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,n))
                else sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                SystemClock.sleep(if(held)45 else 30)
            }
            if(held)InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT,0))
            SystemClock.sleep(650)
            val focused=ui{a.currentFocus?.tag?.toString().orEmpty()}
            input.put(JSONObject().put("held",held).put("deliveredKeys",18).put("focused",focused).put("elapsedMs",SystemClock.elapsedRealtime()-started))
        }
        repeat(4) {
            ui{a.model.top(Section.SEARCH)};await{tagged("search-home")?.isShown==true && ui{!a.model.busy}}
            ui{a.model.top(Section.YOUTUBE)};await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
            installFeed()
        }
        val retained=memory()
        val result=JSONObject().put("catalogSize",120).put("artwork","Distinct URL 480x270 JPEGs, eight deterministic generated patterns")
            .put("initialMemory",initial).put("afterScrollMemory",afterScroll).put("afterNavigationMemory",retained)
            .put("scrollTrials",samples).put("input",input).put("fixture",JSONObject(request("stats")))
            .put("attachedCards",ui{(tagged("youtube-results") as RecyclerView).childCount})
        report("fluidity-artwork.json",result)
        assertNull(player());assertTrue(result.getInt("attachedCards")<20)
        for(i in 0 until input.length())assertEquals("No remote presses may be lost", "youtube-video:P0000000019",input.getJSONObject(i).getString("focused"))
    }
    private fun allocatedBytes():Int=ui {
        val p=a.player!!;val internal=p.javaClass.getDeclaredField("internalPlayer").apply{isAccessible=true}.get(p)
        val load=internal.javaClass.getDeclaredField("loadControl").apply{isAccessible=true}.get(internal) as LoadControl
        load.allocator.totalBytesAllocated
    }
    @Test fun pausedHighBitrateBufferAndYouTubeHiddenUi() {
        val watch=Watch(Title("memory-buffer","Generated 8 Mbps video","movie"),Stream("Generated HLS","$base/youtube/performance/buffer/index.m3u8"),videoMimeType="application/x-mpegURL")
        ui{a.model.open(Route.Player(watch))};playing(45000)
        ui{a.player!!.pause()};await(45000){ui{a.player?.let{!it.isLoading && it.totalBufferedDuration>=10000}==true}}
        val movie=JSONObject().put("allocatorBytes",allocatedBytes()).put("bufferedMs",ui{a.player!!.totalBufferedDuration})
            .put("memory",memory()).put("playback",JSONObject(ui{a.playbackInfo}))
        ui{a.player!!.seekTo(45000);a.player!!.play()};await(30000){ui{a.player?.let{it.isPlaying && it.currentPosition>=45000}==true}}
        ui{a.model.back()};await{ui{a.player==null} && tagged("youtube-page")?.isShown==true}
        val begin=SystemClock.elapsedRealtime();ui{a.model.youtube.play(YouTubeVideo("V0000000001","Generated YouTube video"))};playing()
        val youtube=JSONObject().put("startMs",SystemClock.elapsedRealtime()-begin)
            .put("descendantViews",ui{descendants(a.window.decorView).count()})
            .put("hiddenPanelCreated",ui{a.window.decorView.findTag("youtube-watch-panel")!=null}).put("memory",memory())
        click("Video comments");await{tagged("youtube-comment:comment-1")?.isShown==true}
        youtube.put("commentsOpenMemory",memory()).put("commentsRows",ui{(tagged("youtube-panel-list") as RecyclerView).childCount})
        click("Close video panel");await{tagged("youtube-watch-panel")?.visibility!=View.VISIBLE}
        youtube.put("commentsClosedMemory",memory())
        ui{a.model.back()};await{ui{a.player==null} && tagged("youtube-page")?.isShown==true}
        report("fluidity-playback.json",JSONObject().put("media","Generated 90 s H264/AAC HLS, 8 Mbps CBR, 640x360 at 24 fps")
            .put("movie",movie).put("youtube",youtube).put("stoppedMemory",memory()))
    }
}
