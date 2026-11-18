@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Four presentation modes, real generated media and remote events on worker emulators. */
class PlaybackModesTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    @Before fun prepare(){
        runBlocking{Http().text("$base/youtube/control?reset=1")}
        ui{a.model.top(Section.SEARCH);a.model.server(base);a.model.updatePreferences(Preferences());a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)}
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup(){ui{a.model.top(Section.SEARCH);a.model.updatePreferences(Preferences())}}
    @Test fun shortsRemoteDispatchRecordsOneActionPerPress() {
        click("YouTube Shorts");await{ui{!a.model.youtube.busy && a.model.youtube.videos.size>=2}}
        ui{a.model.youtube.play(a.model.youtube.videos.first())};playing();await{tagged("shorts-actions")?.isShown==true}
        val observations=JSONArray();val changes=JSONArray();val p=player()!!
        val listener=object:Player.Listener { override fun onPlayWhenReadyChanged(value:Boolean,reason:Int){changes.put(JSONObject().put("play",value).put("reason",reason).put("at",SystemClock.uptimeMillis()))} }
        fun record(label:String){observations.put(ui{JSONObject().put("phase",label).put("play",p.playWhenReady).put("focus",a.currentFocus?.tag.toString()).put("shown",tagged("shorts-actions")?.isShown)})}
        ui{p.addListener(listener);a.playback!!.dismissControls()};record("hidden")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val at=SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(at,at,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0));record("window-down")
        instrumentation.sendKeySync(KeyEvent(at,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0));record("window-up")
        SystemClock.sleep(150);record("settled")
        ui{p.play();a.playback!!.dismissControls()};record("reset")
        val direct=SystemClock.uptimeMillis()
        val down=ui{a.playback!!.dispatchKey(KeyEvent(direct,direct,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0))};record("direct-down")
        val up=ui{a.playback!!.dispatchKey(KeyEvent(direct,direct+1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0))};record("direct-up")
        ui{p.removeListener(listener)}
        val dir=ui{a.getExternalFilesDir(null)!!};File(dir,"shorts-key-dispatch.json").writeText(JSONObject().put("observations",observations).put("changes",changes).put("directDownHandled",down).put("directUpHandled",up).toString(2))
        assertFalse("One center press should pause a Short",observations.getJSONObject(3).getBoolean("play"))
        assertFalse(ui{p.playWhenReady})
    }
    @Test fun shortsHavePortraitLoopingPlaybackAReadOnlyLineAndDirectRemoteClipChanges() {
        ui{a.model.updatePreferences(Preferences(videoFit="Zoom"))}
        click("YouTube Shorts");await{ui{!a.model.youtube.busy && a.model.youtube.videos.size>=2}}
        val clips=ui{a.model.youtube.videos};ui{a.model.youtube.play(clips.first())};playing()
        await{tagged("shorts-actions")?.isShown==true}
        assertNotNull(tagged("playback-mode:shorts"));assertNull(tagged("vod-progress"));assertNull(tagged("vod-rewind"))
        assertFalse(ui{tagged("shorts-progress")!!.isFocusable});assertEquals(Player.REPEAT_MODE_ONE,ui{a.player!!.repeatMode})
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT,ui{(tagged("native-player") as PlayerView).resizeMode})
        ui{a.playback!!.dismissControls()}
        await{ui{a.hasWindowFocus() && tagged("native-player")!!.hasFocus() && tagged("shorts-actions")!!.isShown==false}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui{!a.player!!.playWhenReady}};assertTrue(tagged("shorts-actions")!!.isShown)
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);playing()
        ui{a.player!!.seekTo(a.player!!.duration-700)}
        await(10000){ui{a.player!!.isPlaying && a.player!!.currentPosition<3000}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);await{tagged("shorts-actions")?.isShown==true}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        await{ui{(a.model.route as? Route.Player)?.watch?.title?.id==clips[1].id && a.player?.isPlaying==true}}
        assertNotNull(tagged("shorts-title"));screenshot("player-mode-shorts")
    }
    @Test fun regularYouTubeHasQualityCommentsAndItsOwnThinSeekControls() {
        ui{a.model.youtube.play(YouTubeVideo("V0000000001","A YouTube fixture",channel="Fixture creator"))};playing()
        await{tagged("vod-controls")?.isShown==true && tagged("youtube-quality")?.isShown==true}
        assertNotNull(tagged("playback-mode:youtube"));assertTrue(ui{(tagged("vod-progress") as SeekBar).isFocusable})
        assertNotNull(find("Related videos"));assertNotNull(find("Video comments"));assertNull(tagged("shorts-actions"))
        val player=player();click("Video quality");assertTrue(device.wait(Until.hasObject(By.text("Auto")),5000))
        click("Auto");await{ui{a.hasWindowFocus()}};assertSame(player,player());assertTrue(ui{a.player!!.isPlaying})
        assertNull(ui{tagged("vod-panel")!!.background});screenshot("player-mode-youtube")
    }
    @Test fun onDemandKeepsEpisodeIdentityAudioSubtitlesAndTheUnboxedTransport() {
        val episode=Episode("fixture-episode",2,3,"An episode")
        ui{a.model.open(Route.Player(Watch(Title("fixture-series","A series","series"),Stream("Fixture","$base/hls/fixture/orig.m3u8"),episode=episode)))};playing()
        await{tagged("vod-controls")?.isShown==true}
        assertNotNull(tagged("playback-mode:on_demand"));assertEquals("A series",ui{(tagged("vod-title") as TextView).text.toString()})
        assertEquals(episode.label,ui{(tagged("vod-episode") as TextView).text.toString()})
        assertNotNull(find("Audio & subtitles"));assertNull(find("Video comments"));assertNull(tagged("youtube-quality"))
        assertTrue(ui{tagged("vod-progress")!!.isFocusable});assertNull(ui{tagged("vod-panel")!!.background})
        screenshot("player-mode-on-demand")
    }
    @Test fun liveHasTransientChannelInformationNoTransportAndNoExtraBackPress() {
        val channel=Channel("fixture-live","Fixture live channel","$base/hls/fixture/orig.m3u8")
        ui{a.model.open(Route.Player(Watch(Title(channel.id,channel.name),Stream("Fixture live",channel.url),live=true,channel=channel)))};playing()
        await{tagged("live-info")?.isShown==true};assertNotNull(tagged("playback-mode:live"))
        assertNull(tagged("vod-controls"));assertNull(tagged("vod-progress"));assertNull(find("Pause"))
        sendRemoteKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);assertTrue(ui{a.player!!.playWhenReady})
        await(8000){tagged("live-info")?.visibility==View.GONE}
        await{ui{a.hasWindowFocus() && tagged("native-player")!!.hasFocus()}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);await{tagged("live-info")?.isShown==true}
        assertEquals("Fixture live channel",ui{(tagged("live-channel-name") as TextView).text.toString()});screenshot("player-mode-live")
        sendRemoteKey(KeyEvent.KEYCODE_BACK);await{ui{a.model.route !is Route.Player && a.player==null}}
    }
}
