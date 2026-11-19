package xyz.billybishop4.fleet.tv

import android.os.*
import android.view.*
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Collections

/** Matched production screens, unique generated artwork and real asynchronous
 * D-pad delivery. Never run this configuration-resetting suite on a user's TV. */
class NavigationBenchmarkTest:NativeTestBase() {
    companion object {
        private const val base="http://10.0.2.2:8878"
        @BeforeClass @JvmStatic fun defaults() {
            val store=Store(InstrumentationRegistry.getInstrumentation().targetContext)
            store.addons(defaultAddons().map{it.copy(manifestUrl="$base/addons/"+(if(it.id=="com.linvo.cinemeta")"cinemeta" else "gdrive")+"/manifest.json")})
            store.saveTitles((1..90).map{Title("navigation-$it","Generated movie $it",poster="$base/youtube/performance/thumbnail/$it.jpg")})
        }
    }
    private val result=JSONObject()
    private val profiles=JSONArray()
    private fun http(path:String)=runBlocking{Http().text(base+path,timeout=120000)}
    private fun portalLinks()=JSONObject(http("/__state")).getInt("portal_links")
    private fun write(){File(ui{a.getExternalFilesDir(null)!!},"navigation-performance.json").writeText(result.put("profiles",profiles).toString(2))}
    @Before fun prepare() {
        // reset=1 resets fixture behavior but deliberately preserves its lifetime
        // portal counter. Earlier tests may have explicitly played a channel.
        result.put("portalLinksBeforeSetup",portalLinks())
        http("/youtube/performance/prepare");http("/__control?reset=1&large_channels=1");http("/youtube/control?reset=1")
        ui{a.model.showProfiles();a.model.resetProviders();a.model.clearHistory();a.model.updatePreferences(Preferences())
            a.model.server(base);a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.SEARCH);a.model.search("")}
        await{ui{!a.model.busy && a.activePlayer==null} && tagged("search-home")?.isShown==true}
        result.put("version",ui{a.packageManager.getPackageInfo(a.packageName,0).versionName}).put("device",Build.MODEL)
            .put("api",Build.VERSION.SDK_INT).put("scope","Worker Android TV, generated HTTP artwork; frame deadlines are UI timings, not video FPS or physical-TV latency")
            .put("portalLinksAfterSetup",portalLinks())
        write()
    }
    private fun memory():JSONObject {
        val data=Debug.MemoryInfo();Debug.getMemoryInfo(data)
        return JSONObject().put("pssKiB",data.totalPss).put("nativeBytes",Debug.getNativeHeapAllocatedSize())
            .put("managedUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            .put("artworkBytes",ui{a.artwork.loader.memoryCache?.size?:0}).put("artworkMaxBytes",ui{a.artwork.loader.memoryCache?.maxSize?:0})
            .put("forcedGc",false)
    }
    private fun position(row:RecyclerView,view:View?)=view?.let{row.findContainingViewHolder(it)?.bindingAdapterPosition}?:-1
    private fun profile(row:RecyclerView,name:String,key:Int,count:Int=18,held:Boolean=false) {
        // Include the initial row focus in the no-tune invariant, while keeping
        // counter HTTP audits outside the timed input/frame/CPU window.
        val linksBefore=portalLinks()
        val itemCount=ui{row.adapter?.itemCount?:0}
        assertTrue("$name fixture has $itemCount items; $count moves require at least ${count+1}",itemCount>count)
        ui{row.stopScroll();row.scrollToPosition(0)}
        await{ui{row.findViewHolderForAdapterPosition(0)?.itemView?.let{it.isShown && it.width>0}==true && !row.hasPendingAdapterUpdates()}}
        ui{descendants(row.findViewHolderForAdapterPosition(0)!!.itemView).first{it.isShown && it.isFocusable}.requestFocusFromTouch()}
        await{ui{position(row,a.currentFocus)==0 && a.hasWindowFocus()}}
        val values=Collections.synchronizedList(ArrayList<LongArray>())
        val thread=HandlerThread("navigation-metrics").apply{start()}
        val metrics=Window.OnFrameMetricsAvailableListener{_,frame,dropped->values.add(longArrayOf(
            frame.getMetric(FrameMetrics.TOTAL_DURATION),frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
            if(Build.VERSION.SDK_INT>=31)frame.getMetric(FrameMetrics.DEADLINE) else -1L,
            frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME),dropped.toLong()))}
        val focus=ArrayList<JSONObject>();val deliveries=ArrayList<JSONObject>();var sentAt=0L;var delivered=0
        val screen=ui{MainActivity::class.java.getDeclaredField("screen").apply{isAccessible=true}.get(a)}
        val pendingField=screen?.javaClass?.declaredFields?.firstOrNull{it.name=="pendingShelfIndex"}?.apply{isAccessible=true}
        fun snapshot()=JSONObject().put("focus",a.currentFocus?.tag?.toString()).put("index",position(row,a.currentFocus))
            .put("generation",a.focusInputGeneration).put("pending",pendingField?.get(screen)?:JSONObject.NULL)
        val listener=ViewTreeObserver.OnGlobalFocusChangeListener{_,next->
            if(sentAt>0)focus.add(JSONObject().put("index",position(row,next)).put("delayMs",SystemClock.uptimeMillis()-sentAt)
                .put("tag",next?.tag?.toString().orEmpty()))
        }
        val original=ui{a.window.callback!!}
        ui{a.window.addOnFrameMetricsAvailableListener(metrics,Handler(thread.looper));a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
            a.window.callback=object:Window.Callback by original {
                override fun dispatchKeyEvent(event:KeyEvent):Boolean {
                    if(event.keyCode==key && event.action==KeyEvent.ACTION_DOWN) {
                        sentAt=event.eventTime;delivered++
                        val record=JSONObject().put("input",delivered-1).put("eventTime",event.eventTime).put("repeat",event.repeatCount)
                            .put("flags",event.flags).put("before",snapshot())
                        val handled=original.dispatchKeyEvent(event)
                        deliveries.add(record.put("handled",handled).put("after",snapshot()))
                        return handled
                    }
                    return original.dispatchKeyEvent(event)
                }
            }}
        val cpu=Process.getElapsedCpuTime();val gc=Debug.getRuntimeStats();val start=SystemClock.uptimeMillis()
        var lastInput=start;var accepted=0;var reachedExpected=false
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun inject(action:Int,down:Long,repeat:Int):Boolean=instrumentation.uiAutomation.injectInputEvent(
            KeyEvent(down,SystemClock.uptimeMillis(),action,key,repeat,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_DPAD),false)
        try {
            repeat(count){n->
                val remaining=start+n*(if(held)45 else 80)-SystemClock.uptimeMillis();if(remaining>0)SystemClock.sleep(remaining)
                lastInput=SystemClock.uptimeMillis();val down=if(held)start else lastInput
                if(inject(KeyEvent.ACTION_DOWN,down,if(held)n else 0))accepted++
                if(!held)inject(KeyEvent.ACTION_UP,down,0)
            }
            if(held)inject(KeyEvent.ACTION_UP,start,0)
            await(15000){ui{delivered==count}}
            // scrollToPosition/deferred focus can report IDLE before its next
            // layout. Confirm the logical target and completed focus handoff,
            // otherwise an early sample can misreport queued input as lost.
            val deadline=SystemClock.uptimeMillis()+5000
            while(SystemClock.uptimeMillis()<deadline) {
                reachedExpected=ui{position(row,a.currentFocus)==count && row.scrollState==RecyclerView.SCROLL_STATE_IDLE &&
                    !row.isComputingLayout && !row.isLayoutRequested && !row.hasPendingAdapterUpdates() &&
                    !a.window.decorView.isLayoutRequested && a.window.decorView.findTag("youtube-shelf-focus-anchor")?.isFocusable!=true}
                if(reachedExpected)break
                SystemClock.sleep(20)
            }
        }finally{
            val end=ui{a.window.callback=original;a.window.removeOnFrameMetricsAvailableListener(metrics)
                if(a.window.decorView.viewTreeObserver.isAlive)a.window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
                SystemClock.uptimeMillis()}
            thread.quitSafely();thread.join(3000)
            val frames=synchronized(values){values.toList()};val events=ui{focus.toList()}
            val cpuMs=Process.getElapsedCpuTime()-cpu
            val gcAfter=Debug.getRuntimeStats()
            val linksAfter=portalLinks()
            fun p(list:List<Long>,fraction:Double):Double=if(list.isEmpty())0.0 else list.sorted()[((list.size-1)*fraction).toInt()].toDouble()
            profiles.put(JSONObject().put("name",name).put("held",held).put("injectedDowns",accepted).put("deliveredDowns",delivered)
                .put("itemCount",itemCount)
                .put("achievedIndex",ui{position(row,a.currentFocus)}).put("expectedIndex",count).put("focusMoves",events.count{it.getInt("index")>=0})
                .put("focusEvents",JSONArray(events))
                .put("deliveries",JSONArray(ui{deliveries.toList()}))
                .put("focusP95Ms",p(events.filter{it.getInt("index")>=0}.map{it.getLong("delayMs")},.95))
                .put("reachedExpected",reachedExpected).put("confirmationWaitAfterLastInputMs",end-lastInput)
                .put("settleAfterLastInputMs",if(reachedExpected)end-lastInput else JSONObject.NULL).put("elapsedMs",end-start).put("cpuMs",cpuMs)
                .put("portalLinksBefore",linksBefore).put("portalLinksAfter",linksAfter).put("portalLinkDelta",linksAfter-linksBefore)
                .put("frames",frames.size).put("totalP95Ms",p(frames.map{it[0]},.95)/1e6).put("layoutP95Ms",p(frames.map{it[1]},.95)/1e6)
                .put("nonFirstDrawFrames",frames.count{it[3]==0L}).put("framesOverActualDeadline",frames.count{it[3]==0L && it[2]>0 && it[0]>it[2]})
                .put("metricEventsDropped",frames.sumOf{it[4]}).put("gcBefore",JSONObject(gc)).put("gcAfter",JSONObject(gcAfter)))
            write()
        }
        // Collect every profile (including memory) before the final destination
        // assertions, so a failing historical baseline remains comparable.
        assertEquals(count,accepted);assertEquals(count,ui{delivered})
        assertNull("Browsing started a decoder",player())
    }
    @Test fun remoteNavigationAcrossAllAreas() {
        await{ui{(tagged("search-home") as RecyclerView).getChildAt(0) is ShelfCard}}
        val shelf=ui{descendants(tagged("search-home")!!).filterIsInstance<ShelfCard>().first{it.shelfName=="My list"}}
        await{ui{shelf.list.adapter?.itemCount==90 && shelf.list.childCount>0}}
        val linksBeforeInput=portalLinks();result.put("portalLinksBeforeInput",linksBeforeInput)
        result.put("initialMemory",memory());profile(shelf.list,"on-demand posters",KeyEvent.KEYCODE_DPAD_RIGHT)
        profile(shelf.list,"on-demand posters",KeyEvent.KEYCODE_DPAD_RIGHT,held=true)
        ui{a.model.top(Section.YOUTUBE)};await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
        // The production Home model keeps at most 120 videos. Use wide shelves
        // for horizontal input, then ten shorter shelves for vertical input;
        // otherwise a five-row boundary looks like four lost Down presses.
        val videos=(1..120).map{YouTubeVideo("N"+it.toString().padStart(10,'0'),"Generated video $it","$base/youtube/performance/thumbnail/${it+1000}.jpg","Generated creator")}
        val shelves=(0..4).map{YouTubeShelf("navigation-$it","Generated shelf ${it+1}",videos.subList(it*24,(it+1)*24))}
        ui{a.model.youtube.installFixturePage(YouTubeFeed.HOME,YouTubePage(videos,shelves=shelves))}
        val home=tagged("youtube-home") as RecyclerView
        await{ui{(home.getChildAt(0) as? YouTubeShelfView)?.let{it.shelfKey=="navigation-0" && it.list.adapter?.itemCount==24 && it.firstVideo()!=null}==true}}
        val row=ui{(home.getChildAt(0) as YouTubeShelfView).list}
        profile(row,"YouTube shelf",KeyEvent.KEYCODE_DPAD_RIGHT);profile(row,"YouTube shelf",KeyEvent.KEYCODE_DPAD_RIGHT,held=true)
        val vertical=(0..9).map{YouTubeShelf("vertical-$it","Generated shelf ${it+1}",videos.subList(it*12,(it+1)*12))}
        ui{a.model.youtube.installFixturePage(YouTubeFeed.HOME,YouTubePage(videos,shelves=vertical))}
        await{ui{a.model.youtube.homeShelves.size==10 && home.adapter?.itemCount==10 && !home.hasPendingAdapterUpdates() &&
            (home.findViewHolderForAdapterPosition(0)?.itemView as? YouTubeShelfView)?.shelfKey=="vertical-0"}}
        profile(home,"YouTube vertical shelves",KeyEvent.KEYCODE_DPAD_DOWN,count=8)
        val linksBeforeLiveSetup=portalLinks();result.put("portalLinksBeforeLiveSetup",linksBeforeLiveSetup)
        val provider=Provider("navigation-benchmark","Generated navigation portal","$base/stalker_portal/",mac="00:1A:79:00:00:01")
        ui{a.model.showProfiles();a.model.saveProvider(provider);a.model.open(Route.Channels(provider))}
        await{ui{!a.model.busy && a.model.channels.size==6000 && a.activePlayer==null} && tagged("channels")?.isShown==true}
        val linksBeforeLiveInput=portalLinks();result.put("portalLinksBeforeLiveInput",linksBeforeLiveInput)
        val channels=tagged("channels") as RecyclerView
        profile(channels,"Live channels",KeyEvent.KEYCODE_DPAD_DOWN,count=24)
        profile(channels,"Live channels",KeyEvent.KEYCODE_DPAD_DOWN,count=24,held=true)
        val retained=memory();val linksAfterInput=portalLinks()
        // Keep absolute process totals for audit, but assert only requests made
        // by this journey. A previous test's intentional tune is not a regression.
        result.put("retainedMemory",retained).put("portalLinks",linksAfterInput).put("portalLinksAfterInput",linksAfterInput)
            .put("portalLinkDelta",linksAfterInput-linksBeforeInput).put("liveSetupPortalLinkDelta",linksBeforeLiveInput-linksBeforeLiveSetup)
            .put("liveInputPortalLinkDelta",linksAfterInput-linksBeforeLiveInput);write()
        assertEquals("Opening the generated Live list must not tune",0,result.getInt("liveSetupPortalLinkDelta"))
        assertEquals("Navigation must not tune a channel",0,result.getInt("portalLinkDelta"))
        for(index in 0 until profiles.length()) {
            val measured=profiles.getJSONObject(index)
            assertEquals("${measured.getString("name")} held=${measured.getBoolean("held")} tuned while focusing or scrolling",
                0,measured.getInt("portalLinkDelta"))
            assertTrue("${measured.getString("name")} held=${measured.getBoolean("held")}: "+
                "expected ${measured.getInt("expectedIndex")}, reached ${measured.getInt("achievedIndex")}",measured.getBoolean("reachedExpected"))
        }
    }
}
