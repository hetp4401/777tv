@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** Same generated media, limited bandwidth and worker for the before/after comparison. */
class PlaybackStartupBenchmarkTest:NativeTestBase() {
    companion object {
        @BeforeClass @JvmStatic fun fixtureDefaults() {
            val store=Store(InstrumentationRegistry.getInstrumentation().targetContext)
            store.addons(defaultAddons().map{it.copy(manifestUrl="http://10.0.2.2:8878/addons/"+
                (if(it.id=="com.linvo.cinemeta")"cinemeta" else "gdrive")+"/manifest.json")})
        }
    }
    private val base="http://10.0.2.2:8878"
    @Before fun prepare() {
        runBlocking{Http().text("$base/startup/prepare",timeout=90000)}
        ui{a.model.top(Section.SEARCH);a.model.server(base);a.model.updatePreferences(Preferences(resume=false));a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)}
        await{ui{!a.model.youtube.busy} && tagged("youtube-page")?.isShown==true}
    }
    @Test fun fourSourcesMeasureActualRenderedFramesAndStablePlayback() {
        val results=JSONArray()
        val provider=Provider("startup","Generated portal","$base/startup/portal.php",mac="00:1A:79:00:00:01")
        val channel=runBlocking{a.model.repository.channels(provider)}.single()
        for(mode in PlaybackMode.entries)for(iteration in 0..3) {
            val requested=playbackNowMs()
            ui{
                when(mode) {
                    PlaybackMode.YOUTUBE,PlaybackMode.SHORTS->a.model.youtube.play(YouTubeVideo("B0000000009","Generated startup video",isShort=mode==PlaybackMode.SHORTS))
                    PlaybackMode.ON_DEMAND->a.model.open(Route.Player(Watch(Title("startup","Generated movie"),Stream("Generated","$base/startup/media/master.m3u8"),playRequestedAtMs=requested)))
                    PlaybackMode.LIVE->a.model.open(Route.Player(Watch(Title(channel.id,channel.name),Stream(channel.name,""),live=true,provider=provider,channel=channel,playRequestedAtMs=requested)))
                }
            }
            await{ui{a.playback?.snapshot?.let{it.firstFrameMs>0 && it.playbackMs>0 && it.playing}==true}}
            val player=player()!!;val snapshot=ui{a.playback!!.snapshot!!};var rebuffers=0
            val observedAt=SystemClock.elapsedRealtime();val samples=JSONArray();val transitions=JSONArray()
            fun sample()=JSONObject().put("sinceFrameMs",SystemClock.elapsedRealtime()-observedAt)
                .put("positionMs",player.currentPosition).put("bufferedMs",player.totalBufferedDuration)
                .put("state",player.playbackState).put("loading",player.isLoading).put("playing",player.isPlaying)
            val listener=object:Player.Listener{override fun onPlaybackStateChanged(state:Int){
                if(state==Player.STATE_BUFFERING)rebuffers++
                transitions.put(sample())
            }}
            ui{player.addListener(listener)}
            repeat(10){SystemClock.sleep(250);samples.put(ui{sample()})}
            val continuous=ui{player.isPlaying}
            ui{player.removeListener(listener)}
            results.put(JSONObject().put("mode",mode.name).put("iteration",iteration)
                .put("firstFrameMs",snapshot.firstFrameMs).put("sourceMs",snapshot.sourceMs)
                .put("playbackMs",snapshot.playbackMs).put("playableMs",maxOf(snapshot.firstFrameMs,snapshot.playbackMs))
                .put("pipelineMs",snapshot.firstFrameMs-snapshot.sourceMs).put("rebuffers",rebuffers).put("continuous",continuous)
                .put("samples",samples).put("transitions",transitions))
            File(ui{a.getExternalFilesDir(null)!!},"startup-latency.json").writeText(JSONObject()
                .put("version",ui{a.packageManager.getPackageInfo(a.packageName,0).versionName})
                .put("measure","Explicit Play to rendered frame AND active playback; resume disabled; 204800 bytes/s media; 160 ms portal RPC")
                .put("results",results).toString(2))
            assertTrue("$mode/$iteration must retain continuous playback",continuous)
            assertEquals("No rebuffering on the controlled sustainable connection",0,rebuffers)
            ui{a.model.back()};await{ui{a.player==null}}
        }
        File(ui{a.getExternalFilesDir(null)!!},"startup-latency.json").writeText(JSONObject()
            .put("version",ui{a.packageManager.getPackageInfo(a.packageName,0).versionName})
            .put("measure","Explicit Play to rendered frame AND active playback; resume disabled; 204800 bytes/s media; 160 ms portal RPC")
            .put("results",results).toString(2))
    }
}
