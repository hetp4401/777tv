@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.content.SharedPreferences
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Generated emulator-only checks for lazy UI, memory pressure and idle playback work. */
class FluidityRegressionTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    @Before fun prepare() {
        runBlocking{Http().text("$base/youtube/control?reset=1")}
        ui { a.model.top(Section.SEARCH);a.model.updatePreferences(Preferences());a.model.server(base)
            a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE) }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    private fun play() { ui{a.model.youtube.play(YouTubeVideo("V0000000001","Generated video"))};playing() }
    @Test fun hiddenVideoDetailsAreLazyAndCanBeFreedAndReopenedDuringPlayback() {
        play();val player=player()
        assertNull(ui{a.window.decorView.findTag("youtube-watch-panel")})
        click("Video comments");await{tagged("youtube-comment:comment-1")?.isShown==true}
        repeat(3) {
            click("Related videos tab");await{tagged("youtube-video:V0000000040")?.isShown==true}
            click("Comments tab");await{tagged("youtube-comment:comment-1")?.isShown==true}
            assertSame(player,player())
        }
        click("Close video panel");ui{a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)}
        assertNull(ui{a.window.decorView.findTag("youtube-watch-panel")});assertSame(player,player());assertTrue(ui{player!!.isPlaying})
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);await{tagged("vod-controls")?.isShown==true}
        click("Video comments");await{tagged("youtube-comment:comment-1")?.isShown==true}
        assertSame(player,player());assertTrue(ui{player!!.isPlaying})
    }
    @Test fun memoryPressurePreservesTheShortsQueueAndBackDestination() {
        click("YouTube Shorts");await{ui{!a.model.youtube.busy && a.model.youtube.videos.size>=7}}
        val videos=ui{a.model.youtube.videos};val first=videos[0];val next=videos[1]
        assertTrue(first.isShort);assertTrue(next.isShort)
        val observations=ArrayList<org.json.JSONObject>();val keys=ArrayList<org.json.JSONObject>()
        val output=java.io.File(ui{a.getExternalFilesDir(null)!!},"shorts-memory-pressure-navigation.json")
        fun state()=org.json.JSONObject().put("routeId",(a.model.route as? Route.Player)?.watch?.title?.id)
            .put("attachedId",a.playback?.watch?.title?.id).put("playing",a.player?.isPlaying==true)
            .put("firstFrameMs",a.playback?.snapshot?.firstFrameMs?:0)
            .put("focus",a.currentFocus?.tag?.toString()).put("windowFocus",a.hasWindowFocus())
            .put("touchMode",a.window.decorView.isInTouchMode).put("hudShown",tagged("shorts-actions")?.isShown==true)
            .put("feed",a.model.youtube.feed.name).put("resolving",a.model.youtube.resolving)
            .put("videos",org.json.JSONArray(a.model.youtube.videos.map{it.id}))
        fun record(phase:String) {
            val report=ui{observations.add(state().put("phase",phase));org.json.JSONObject()
                .put("observations",org.json.JSONArray(observations)).put("keys",org.json.JSONArray(keys)).toString(2)}
            output.writeText(report)
        }
        fun attached(id:String)=ui {
            val watch=(a.model.route as? Route.Player)?.watch
            watch!=null && watch.title.id==id && watch.short && a.playback?.watch===watch && a.player?.isPlaying==true &&
                (a.playback?.snapshot?.firstFrameMs?:0)>0
        }
        fun hidden(id:String)=attached(id) && ui{a.hasWindowFocus() && tagged("native-player")?.hasFocus()==true &&
            tagged("shorts-actions")?.isShown==false}
        val callback=ui{a.window.callback!!}
        try {
            ui{a.model.youtube.play(first)}
            // A model route can precede its rendered Playback by a frame. Do not
            // apply pressure or keys to the previous source during that handoff.
            await{attached(first.id) && tagged("shorts-actions")?.isShown==true}
            val original=player()!!;ui{a.playback!!.dismissControls()};await{hidden(first.id)}
            record("before-trim")
            ui{a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)}
            assertSame("Memory pressure replaced the active decoder",original,player())
            assertTrue(ui{a.model.youtube.videos.any{it.id==first.id}})
            await{hidden(first.id)};record("after-trim")
            ui {
                a.window.callback=object:android.view.Window.Callback by callback {
                    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
                        if(event.keyCode!=KeyEvent.KEYCODE_DPAD_DOWN)return callback.dispatchKeyEvent(event)
                        val key=org.json.JSONObject().put("action",event.action).put("downTime",event.downTime)
                            .put("repeat",event.repeatCount).put("before",state())
                        val handled=callback.dispatchKeyEvent(event)
                        keys.add(key.put("handled",handled).put("after",state()));return handled
                    }
                }
            }
            sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN);record("after-down")
            await{attached(next.id) && tagged("shorts-actions")?.isShown==true}
            assertNotSame("The next Short kept the previous decoder",original,player())
            ui{a.playback!!.dismissControls()};await{hidden(next.id)};record("before-back")
            ui{a.onBackPressedDispatcher.onBackPressed()}
            await{ui{a.player==null && a.model.route==Route.Browse(Section.YOUTUBE)} && tagged("youtube-page")?.isShown==true}
            assertTrue(ui{a.model.youtube.videos.any{it.id==first.id}})
        }finally{ui{a.window.callback=callback};record("final")}
    }
    @Test fun pausedPlaybackStopsRewritingHistoryAndSeekingAndResumeStillUpdateControls() {
        play();ui{a.player!!.pause()};await{ui{a.player?.let{!it.isLoading && !it.playWhenReady}==true}}
        SystemClock.sleep(4500)
        val writes=AtomicInteger();val prefs=ui{a.getSharedPreferences("fleet-tv",0)}
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key->if(key=="youtube-progress")writes.incrementAndGet() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            SystemClock.sleep(8500)
            assertEquals("An unchanged paused position should not produce more history writes",0,writes.get())
            assertEquals(View.VISIBLE,ui{tagged("vod-controls")!!.visibility})
            val progress=tagged("vod-progress") as SeekBar
            ui{a.player!!.seekTo(12000)}
            await{ui{
                val player=a.player!!;val expected=(12000.0/player.duration*progress.max).toInt()
                kotlin.math.abs(progress.progress-expected)<=2 && kotlin.math.abs(player.currentPosition-12000)<500 && !player.playWhenReady
            }}
            click("Play");playing();await(9000){tagged("vod-controls")?.visibility==View.GONE}
        } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
