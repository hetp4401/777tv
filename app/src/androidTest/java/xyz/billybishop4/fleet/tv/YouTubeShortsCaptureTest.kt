@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Generated emulator media only. Recording adds an encoder, so this artifact
 * is visual acceptance evidence, never a playback performance benchmark. */
class YouTubeShortsCaptureTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private val destination="/sdcard/Download/fleet-tv-youtube026-shorts.mp4"
    @Before fun prepare() {
        runBlocking{Http().text("$base/youtube/control?reset=1")}
        ui {
            a.model.top(Section.SEARCH);a.model.server(base);a.model.clearHistory()
            a.model.store.youTubeCredentials().write(null);a.model.store.youTubePhoneEnabled(false)
            a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false))
            a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)
        }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup(){ui{a.model.top(Section.SEARCH);a.model.updatePreferences(Preferences())}}
    private fun ready(id:String)=ui {
        val watch=(a.model.route as? Route.Player)?.watch
        watch?.title?.id==id && a.playback?.watch===watch && a.playback?.canRetireShortFrame==true && a.player?.isPlaying==true
    }
    @Test fun recordBothCommittedShortsDirectionsWithoutChangingTheProductAnimation() {
        click("YouTube Shorts");await{ui{!a.model.youtube.busy && a.model.youtube.videos.size>=3}}
        val clips=ui{a.model.youtube.videos.toList()}
        ui{a.model.youtube.play(clips[1])};await{ready(clips[1].id)}
        val started=SystemClock.uptimeMillis()
        val output=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screenrecord --size 640x360 --bit-rate 2000000 --time-limit 10 $destination")
        val changes=org.json.JSONArray()
        try {
            SystemClock.sleep(700)
            for((key,index) in listOf(KeyEvent.KEYCODE_DPAD_DOWN to 2,KeyEvent.KEYCODE_DPAD_UP to 1)) {
                val requested=SystemClock.uptimeMillis()
                sendRemoteKey(key);await{ready(clips[index].id)}
                changes.put(JSONObject().put("id",clips[index].id).put("direction",if(index==2)1 else -1)
                    .put("requestedOffsetMs",requested-started).put("readyOffsetMs",SystemClock.uptimeMillis()-started))
                SystemClock.sleep(450)
            }
            val remaining=11000-(SystemClock.uptimeMillis()-started)
            if(remaining>0)SystemClock.sleep(remaining)
        } finally { output.close() }
        val bytes=device.executeShellCommand("stat -c %s $destination").trim().toLongOrNull()?:0
        assertTrue("The native visual capture did not produce a video",bytes>4096)
        File(ui{a.getExternalFilesDir(null)!!},"youtube026-shorts-recording.json").writeText(JSONObject()
            .put("generatedFixtureOnly",true).put("physicalTv",false).put("performanceBenchmark",false)
            .put("recordingBytes",bytes).put("changes",changes).toString(2))
    }
}
