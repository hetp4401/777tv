@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.app.AlertDialog
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.ImageView
import androidx.media3.common.C
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in for live, non-destructive checks. Never provisions profiles or supplies account credentials. */
class YouTubeProductionProbeTest {
    @Test fun liveNativePlaybackShortsAndPairing() {
        assumeTrue("Explicit live TV opt-in required",InstrumentationRegistry.getArguments().getString("youtubeLive")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        lateinit var activity:MainActivity
        fun <T> ui(block:()->T):T {
            var result:Any?=null
            instrumentation.runOnMainSync{result=block()}
            @Suppress("UNCHECKED_CAST") return result as T
        }
        fun await(seconds:Int=60,condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+seconds*1000
            while(SystemClock.elapsedRealtime()<until){if(condition())return;SystemClock.sleep(100)}
            assertTrue("Live YouTube condition timed out",condition())
        }
        fun youtube():YouTubeModel = activity.model.javaClass.methods.first { it.name.startsWith("getYoutube") && it.parameterCount==0 }.invoke(activity.model) as YouTubeModel
        val report=JSONObject().put("version","0.15.0").put("usesFixtureBackend",false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity=it }
            try {
                report.put("stage","search")
                ui{activity.model.top(Section.YOUTUBE);youtube().editQuery("Big Buck Bunny");youtube().submitSearch()}
                await{ui{!youtube().busy && youtube().videos.isNotEmpty()}}
                report.put("searchResults",ui{youtube().videos.size})
                val video=ui{youtube().videos.first{!it.isShort && !it.live}}
                report.put("stage","movie playback")
                ui{youtube().play(video)}
                await(90){ui{activity.player?.let{it.isPlaying && (it.videoDecoderCounters?.renderedOutputBufferCount?:0)>=120}==true}}
                report.put("playback",ui{val p=activity.player!!;JSONObject().put("width",p.videoSize.width).put("height",p.videoSize.height)
                    .put("fps",p.videoFormat?.frameRate).put("renderedFrames",p.videoDecoderCounters!!.renderedOutputBufferCount)
                    .put("droppedFrames",p.videoDecoderCounters!!.droppedBufferCount).put("audioSelected",p.currentTracks.groups.any{it.type==C.TRACK_TYPE_AUDIO && it.isSelected})
                    .put("diagnostics",JSONObject(activity.playbackInfo))})
                ui{activity.player!!.pause()};await{ui{activity.player?.isPlaying==false}}
                val from=ui{activity.player!!.currentPosition}
                ui{activity.player!!.seekTo(from+10000);activity.player!!.play()}
                await{ui{activity.player?.let{it.isPlaying && it.currentPosition>=from+9000}==true}}
                report.put("pauseResumeSeek",true)
                ui{activity.model.back()}
                await{ui{activity.model.route==Route.Browse(Section.YOUTUBE) && activity.player==null}}
                report.put("stage","Shorts")
                ui{youtube().select(YouTubeFeed.SHORTS)}
                await{ui{!youtube().busy && youtube().videos.size>=2 && youtube().feed==YouTubeFeed.SHORTS}}
                report.put("shorts",ui{youtube().videos.size})
                val short=ui{youtube().videos.first()}
                ui{youtube().play(short)}
                await(90){ui{activity.player?.let{it.isPlaying && (it.videoDecoderCounters?.renderedOutputBufferCount?:0)>=48}==true}}
                report.put("shortPlayback",ui{JSONObject().put("width",activity.player!!.videoSize.width).put("height",activity.player!!.videoSize.height)
                    .put("droppedFrames",activity.player!!.videoDecoderCounters!!.droppedBufferCount)})
                ui{activity.model.back()}
                await{ui{activity.model.route==Route.Browse(Section.YOUTUBE) && activity.player==null && activity.hasWindowFocus()}}
                report.put("stage","Google pairing")
                if(ui{youtube().account==null}) {
                    await{ui{activity.window.decorView.findTag("button:YouTube account")?.isShown==true}}
                    ui{activity.window.decorView.findTag("button:YouTube account")!!.performClick()}
                    await{ui{youtube().signInCode!=null}}
                    // Only booleans are recorded; codes, OAuth tokens and pairing screenshots stay private.
                    report.put("googleDeviceCodeIssued",true)
                    report.put("realAccountApproved",ui{youtube().account!=null})
                    UiDevice.getInstance(instrumentation).pressBack()
                    await{ui{youtube().signInCode==null && activity.hasWindowFocus()}}
                    report.put("pairingCanceledCleanly",true)
                } else report.put("existingAccountPreserved",true)
                report.put("success",true)
            } finally {
                report.put("lastError",ui{youtube().error}).put("signInError",ui{youtube().signInError})
                ui{youtube().cancelSignIn();activity.model.top(Section.SEARCH)}
                val dir=ui{activity.getExternalFilesDir(null)!!}
                File(dir,"youtube-production-probe.json").writeText(report.toString(2))
            }
        }
    }
}
