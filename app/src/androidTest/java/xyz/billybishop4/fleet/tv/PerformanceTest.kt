package xyz.billybishop4.fleet.tv

import android.os.Build
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PerformanceTest:NativeTestBase(){
    @Test fun scrollConfiguredLiveListAndRecordFramesAndMemory(){
        assumeTrue(ui{a.model.defaultProfile!=null})
        ui{a.model.top(Section.LIVE)}
        await(60000){ui{!a.model.busy&&a.model.channels.isNotEmpty()}}
        await{tagged("channels") is RecyclerView&&ui{(a.window.decorView.findTag("channels")as RecyclerView).childCount>0}}
        val grid=tagged("channels")as RecyclerView
        device.pressDPadDown();ui{grid.getChildAt(0).requestFocus()}
        device.executeShellCommand("dumpsys gfxinfo xyz.billybishop4.fleet.tv reset")
        repeat(24){device.pressDPadDown()};repeat(24){device.pressDPadUp()}
        val dir=ui{a.getExternalFilesDir(null)!!}
        File(dir,"native-gfx.txt").writeText(device.executeShellCommand("dumpsys gfxinfo xyz.billybishop4.fleet.tv framestats"))
        File(dir,"native-memory.txt").writeText(device.executeShellCommand("dumpsys meminfo xyz.billybishop4.fleet.tv"))
        val report=ui{JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("androidApi",Build.VERSION.SDK_INT)
            .put("channels",a.model.channels.size).put("attachedChannelViews",grid.childCount)
            .put("artworkCacheLimitMiB",(a.artwork.loader.memoryCache?.maxSize?:0)/1024.0/1024.0).put("dpadEvents",48)}
        assertTrue(ui{grid.childCount<40});File(dir,"native-performance.json").writeText(report.toString(2))
    }
}
