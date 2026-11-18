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
        ui{a.model.youtube.play(first)};playing()
        ui{a.playback!!.dismissControls();a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        await{ui{(a.model.route as? Route.Player)?.watch?.title?.id==next.id && a.player?.isPlaying==true}}
        ui{a.playback!!.dismissControls();a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.player==null && a.model.route==Route.Browse(Section.YOUTUBE)} && tagged("youtube-page")?.isShown==true}
        assertTrue(ui{a.model.youtube.videos.any{it.id==first.id}})
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
