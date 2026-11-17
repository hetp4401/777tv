@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.*
import android.view.*
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Same generated catalog, worker and scrolling workload before and after changes. */
class UiPerformanceTest : NativeTestBase() {
    @Test fun catalogScrollingWithoutVideoAndNavigationMemory() {
        runBlocking { Http().text("http://10.0.2.2:8878/__control?reset=1&large_channels=1") }
        val p=Provider("ui-performance","UI performance","http://10.0.2.2:8878/stalker_portal/",mac="00:1A:79:00:00:01")
        ui { a.model.showProfiles();a.model.updatePreferences(Preferences());a.model.server("http://10.0.2.2:8878");a.model.saveProvider(p);a.model.open(Route.Channels(p)) }
        await { find(p.name)!=null && ui { !a.model.busy && a.model.channels.size==6000 } }
        val grid=tagged("channels") as RecyclerView
        await { ui { grid.adapter!!.itemCount==6000 && grid.childCount>0 } }
        // Browsing never starts playback. This workload measures the recycled
        // catalog without a video decoder, separately from the tuning workload.
        ui { a.window.decorView.findTag("button:Live TV options")!!.requestFocus() }
        assertNull(ui { a.activePlayer })
        val dir=ui { a.getExternalFilesDir(null)!! }
        val thread=HandlerThread("frame-metrics").apply { start() }
        val frames=java.util.Collections.synchronizedList(ArrayList<LongArray>())
        val listener=Window.OnFrameMetricsAvailableListener { _,f,_ -> frames.add(longArrayOf(
            f.getMetric(FrameMetrics.TOTAL_DURATION),f.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),f.getMetric(FrameMetrics.DRAW_DURATION))) }
        fun scroll(count:Int) {
            val done=CountDownLatch(1)
            ui { Choreographer.getInstance().postFrameCallback(object:Choreographer.FrameCallback {
                var step=0
                override fun doFrame(time:Long) {
                    grid.scrollBy(0,a.dp(if(step<count/2)12 else -12))
                    if(++step<count)Choreographer.getInstance().postFrameCallback(this) else done.countDown()
                }
            }) }
            assertTrue("Scrolling stalled",done.await(90,TimeUnit.SECONDS))
        }
        scroll(24);SystemClock.sleep(300)
        File(dir,"ui-memory-before.txt").writeText(device.executeShellCommand("dumpsys meminfo xyz.billybishop4.fleet.tv"))
        val gcBefore=Debug.getRuntimeStats();val cpu=Process.getElapsedCpuTime();val started=SystemClock.elapsedRealtime()
        ui { a.window.addOnFrameMetricsAvailableListener(listener,Handler(thread.looper)) }
        try { scroll(180);SystemClock.sleep(300) }
        finally { ui { a.window.removeOnFrameMetricsAvailableListener(listener) };thread.quitSafely();thread.join(5000) }
        val elapsed=SystemClock.elapsedRealtime()-started;val cpuMs=Process.getElapsedCpuTime()-cpu
        val samples=synchronized(frames){frames.toList()}
        val gcAfter=Debug.getRuntimeStats()
        val rows=ui { grid.childCount };val rowViews=ui { (0 until grid.childCount).sumOf { descendants(grid.getChildAt(it)).count() } }
        assertTrue(rows<20);assertNull(ui { a.activePlayer })
        repeat(6) {
            ui { a.model.top(Section.SEARCH) };await { tagged("search-home")?.isShown==true && ui{!a.model.busy} }
            ui { a.model.top(Section.LIVE) };await { find(p.name)!=null && tagged("channels")?.isShown==true && ui{!a.model.busy} }
        }
        File(dir,"ui-memory-after-navigation.txt").writeText(device.executeShellCommand("dumpsys meminfo xyz.billybishop4.fleet.tv"))
        fun percentile(column:Int,p:Double):Double {
            val values=samples.map{it[column]}.sorted();return if(values.isEmpty())0.0 else values[((values.size-1)*p).toInt()]/1e6
        }
        val report=JSONObject().put("device",Build.MODEL).put("renderer",InstrumentationRegistryCompat.renderer())
            .put("channels",6000).put("scrollSteps",180).put("elapsedMs",elapsed).put("processCpuMs",cpuMs)
            .put("frames",samples.size).put("frameP50Ms",percentile(0,.5)).put("frameP95Ms",percentile(0,.95))
            .put("layoutP95Ms",percentile(1,.95)).put("drawP95Ms",percentile(2,.95))
            .put("attachedRows",rows).put("viewsInAttachedRows",rowViews)
            .put("gcBefore",JSONObject(gcBefore)).put("gcAfter",JSONObject(gcAfter))
        File(dir,"ui-performance.json").writeText(report.toString(2))
    }
}

private object InstrumentationRegistryCompat {
    fun renderer()=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("renderer","unspecified")
}
