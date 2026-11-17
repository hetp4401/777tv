@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.os.Build
import android.view.KeyEvent
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import org.junit.Test
import org.junit.Assert.*

/** Generated loopback media only. This benchmark never discovers or connects to a TV. */
class LocalPerformanceTest : NativeTestBase() {
    @Test fun nativeScrollingAndRepeatedTuningWithSixThousandChannels() {
        runBlocking { Http().text("http://10.0.2.2:8878/__control?reset=1&large_channels=1") }
        val provider = Provider("performance-fixture", "Performance fixture", "http://10.0.2.2:8878/stalker_portal/", mac = "00:1A:79:00:00:01")
        ui { a.model.showProfiles(); a.model.updatePreferences(Preferences()); a.model.server("http://10.0.2.2:8878"); a.model.saveProvider(provider); a.model.open(Route.Channels(provider)) }
        await { find("Performance fixture")!=null && ui { !a.model.busy && a.model.channels.size == 6000 } }
        val grid = tagged("channels") as RecyclerView
        await { ui { grid.childCount > 0 } }; ui { grid.getChildAt(0).apply { requestFocus();performClick() } }
        await(45000) { JSONObject(ui { a.playbackInfo }).optBoolean("playing") }
        val dir = ui { a.getExternalFilesDir(null)!! }
        File(dir, "player-memory-before.txt").writeText(device.executeShellCommand("dumpsys meminfo xyz.billybishop4.fleet.tv"))
        device.executeShellCommand("dumpsys gfxinfo xyz.billybishop4.fleet.tv reset")
        val started = SystemClock.elapsedRealtime()
        repeat(3) {
            for (key in listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP)) repeat(24) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); SystemClock.sleep(35)
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        File(dir, "player-scroll-gfx.txt").writeText(device.executeShellCommand("dumpsys gfxinfo xyz.billybishop4.fleet.tv framestats"))
        val views = ui { grid.childCount }; assertTrue(views < 20)
        val switches = JSONArray()
        repeat(8) { i ->
            val channel = ui { a.model.channels[i+1] }
            val begin = SystemClock.elapsedRealtime()
            ui { grid.scrollToPosition(i+1) }; await { tagged("channel:${channel.id}")?.isShown==true }
            ui { a.window.decorView.findTag("channel:${channel.id}")!!.apply { requestFocus();performClick() } }
            await(45000) { JSONObject(ui { a.playbackInfo }).optBoolean("playing") }
            switches.put(JSONObject(ui { a.playbackInfo }).put("tuneMs", SystemClock.elapsedRealtime() - begin))
            ui { a.window.decorView.findTag("channel:${channel.id}")!!.performClick() }
            await { tagged("channels")?.isShown==false }
            device.pressBack(); await { tagged("channels")?.isShown==true && tagged("channel:${channel.id}")?.hasFocus()==true }
        }
        File(dir, "player-memory-after.txt").writeText(device.executeShellCommand("dumpsys meminfo xyz.billybishop4.fleet.tv"))
        val report = JSONObject().put("device", Build.MODEL)
            .put("api", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("renderer",InstrumentationRegistry.getArguments().getString("renderer","unspecified"))
            .put("cpuCores",Runtime.getRuntime().availableProcessors())
            .put("media", "Generated 640x360 H264/AAC local HLS").put("channels", 6000).put("attachedChannelRows", views)
            .put("scrollEvents", 144).put("scrollElapsedMs", elapsed).put("switches", switches)
        ui { a.model.showProfiles() }; await { ui { a.activePlayer == null } }; SystemClock.sleep(1500)
        File(dir, "player-memory-stopped.txt").writeText(device.executeShellCommand("dumpsys meminfo xyz.billybishop4.fleet.tv"))
        File(dir, "native06-local-performance.json").writeText(report.toString(2)); screenshot("native06-final-local")
    }
}
