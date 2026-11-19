@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** Full phone→native-player path, using only a generated BrowserChannel backend. */
class YouTubePhoneLinkTest:NativeTestBase() {
    companion object {
        @BeforeClass @JvmStatic fun defaults(){Store(InstrumentationRegistry.getInstrumentation().targetContext).youTubePhoneEnabled(false)}
    }
    private val base="http://10.0.2.2:8878"
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    private fun audit()=JSONObject(runBlocking{Http().text("$base/youtube/state")})
    private fun command(type:String,data:JSONObject=JSONObject())=runBlocking {
        YouTubeHttp().request("$base/youtube/phone/command",JSONObject().put("type",type).put("data",data).toString())
    }
    @Before fun setup() {
        control("reset=1")
        ui {
            a.model.server(base);a.model.store.youTubePhoneEnabled(false);a.model.store.youTubeScreenStore().write(null)
            a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.SEARCH)
        }
        await{ui{!a.model.busy}}
    }
    @After fun cleanup(){ui{a.disconnectYouTubePhones();a.model.store.youTubePhoneEnabled(false);a.model.top(Section.SEARCH)}}
    private fun connect() {
        ui{a.connectYouTubePhone()}
        assertTrue(device.wait(Until.hasObject(By.text("123 456 789 012")),10000))
        assertTrue(device.wait(Until.hasObject(By.text("Waiting for your phone. 777tv must stay open.")),10000))
        click("Done");await{!device.hasObject(By.text("123 456 789 012"))}
    }
    private fun attachedAndPlaying(id:String)=ui {
        val watch=(a.model.route as? Route.Player)?.watch
        watch?.title?.id==id && a.playback?.watch===watch && a.activePlayer?.isPlaying==true
    }
    private fun play(id:String="V0000000001",at:Double=0.0,ids:String=id) {
        command("setPlaylist",JSONObject().put("videoId",id).put("videoIds",ids).put("currentTime",at))
        await{attachedAndPlaying(id)}
    }
    private fun beginTvScrub(wasPlaying:Boolean):Long {
        // A positive-duration receiver status confirms the phone handoff has
        // completed; these cases exercise commands for the observed player.
        await{audit().getJSONArray("requests").objects().any {
            it.optString("phoneCommand")=="nowPlaying" && (it.optJSONObject("fields")?.optDouble("req0_duration",0.0)?:0.0)>0
        }}
        ui{a.activePlayer!!.pause();a.activePlayer!!.seekTo(20000)}
        await{ui{a.activePlayer!!.currentPosition in 19500L..20500L && a.activePlayer!!.playbackState==Player.STATE_READY}}
        if(wasPlaying){ui{a.activePlayer!!.play()};playing()}
        await{tagged("vod-progress")?.isShown==true && tagged("vod-progress")?.isEnabled==true}
        ui{tagged("vod-progress")!!.requestFocusFromTouch()}
        val down=SystemClock.uptimeMillis()
        InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,0))
        await{tagged("youtube-seek-preview")?.isShown==true && ui{a.activePlayer!!.playWhenReady==false}}
        return down
    }
    private fun releaseTvScrub(down:Long) {
        InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_LEFT,0))
        SystemClock.sleep(150)
    }
    @Test fun tvCodeIsReachableFromSettingsAndNoGoogleAccountIsRequired() {
        click("Settings");await{tagged("settings-list")?.isShown==true};click("YouTube settings");click("Link phone with TV code")
        assertTrue(device.wait(Until.hasObject(By.text("123 456 789 012")),10000))
        assertTrue(device.wait(Until.hasObject(By.text("Waiting for your phone. 777tv must stay open.")),10000))
        assertTrue(ui{a.model.store.youTubePhoneEnabled()});assertNull(ui{a.model.store.youTubeCredentials().read()})
        screenshot("youtube-phone-link-code");click("Done")
        assertEquals(0,audit().getJSONArray("requests").objects().count{it.optString("path").contains("oauth")})
    }
    @Test fun phoneControlsTheSameNativePlayerWithExplicitPositionPauseSeekAndVolume() {
        ui{a.model.updatePreferences(Preferences(resume=false))};connect();play(at=15.0)
        await{ui{a.activePlayer!!.currentPosition in 14_000..20_000}}
        val original=player();command("pause");await{ui{a.activePlayer?.playWhenReady==false}}
        command("seekTo",JSONObject().put("newTime",30));await{ui{kotlin.math.abs(a.activePlayer!!.currentPosition-30000)<1200}}
        command("setVolume",JSONObject().put("volume",35));await{ui{kotlin.math.abs(a.activePlayer!!.volume-.35f)<.01f}}
        command("play");playing();assertSame(original,player())
        await{audit().getJSONArray("requests").objects().any{it.optString("phoneCommand")=="nowPlaying"}}
        assertTrue(audit().getJSONArray("requests").objects().filter{it.optString("path").contains("/lounge/")}.none{it.optBoolean("authenticated")})
        screenshot("youtube-phone-native-playback")
    }
    @Test fun phonePauseDuringAPlayingTvScrubSurvivesTheArrowRelease() {
        ui{a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false))};connect();play()
        val original=player()!!;val down=beginTvScrub(wasPlaying=true)
        command("pause")
        await{tagged("youtube-seek-preview")?.isShown!=true && ui{!original.playWhenReady}}
        releaseTvScrub(down)
        assertFalse(ui{original.playWhenReady});assertTrue("The obsolete local cursor was committed",ui{original.currentPosition>19000})
        assertSame(original,player())
    }
    @Test fun phonePlayDuringAPausedTvScrubSurvivesTheArrowRelease() {
        ui{a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false))};connect();play()
        val original=player()!!;val down=beginTvScrub(wasPlaying=false)
        command("play")
        await{tagged("youtube-seek-preview")?.isShown!=true && ui{original.playWhenReady}}
        releaseTvScrub(down)
        assertTrue(ui{original.playWhenReady});assertTrue("The obsolete local cursor was committed",ui{original.currentPosition>19000})
        assertSame(original,player())
    }
    @Test fun aPhoneSeekDiscardsTheLocalCursorAndPreservesEitherPlayIntent() {
        ui{a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false))};connect();play()
        val original=player()!!
        for(wasPlaying in listOf(false,true)) {
            val down=beginTvScrub(wasPlaying)
            command("seekTo",JSONObject().put("newTime",35))
            await{tagged("youtube-seek-preview")?.isShown!=true && ui{original.currentPosition in 34000L..41000L && original.playWhenReady==wasPlaying}}
            releaseTvScrub(down)
            assertEquals(wasPlaying,ui{original.playWhenReady});assertTrue(ui{original.currentPosition in 34000L..42000L})
            assertSame(original,player())
        }
    }
    @Test fun phoneQueueNextPreviousAndBackNeverStackOldPlayers() {
        connect();play(ids="V0000000001,V0000000002")
        command("next");await{attachedAndPlaying("V0000000002")}
        command("previous");await{attachedAndPlaying("V0000000001")}
        ui{a.model.back()};await{ui{a.model.route==Route.Browse(Section.YOUTUBE) && a.activePlayer==null}}
        assertTrue(tagged("youtube-page")?.isShown==true)
    }
    @Test fun choosingALocalVideoRetiresThePreviousPhoneQueue() {
        connect();play(ids="V0000000001,V0000000002")
        ui {
            a.model.updatePreferences(a.model.preferences.copy(youtubeAutoplay=false))
            a.model.youtube.play(YouTubeVideo("V0000000003","Chosen on this TV"))
        }
        await{attachedAndPlaying("V0000000003")}
        val local=player()!!;command("pause");await{ui{!local.playWhenReady}}
        ui{local.seekTo((local.duration-250).coerceAtLeast(0));local.play()}
        await{ui{local.playbackState==androidx.media3.common.Player.STATE_ENDED}}
        SystemClock.sleep(800)
        assertSame("A stale phone queue replaced a local selection",local,player())
        assertEquals("V0000000003",ui{(a.model.route as Route.Player).watch.title.id})
        assertFalse(audit().getJSONArray("resolved").let{(0 until it.length()).any{n->it.optString(n)=="V0000000002"}})
    }
    @Test fun disconnectRejectsFurtherPhoneCommandsWithoutInterruptingTheCurrentVideo() {
        connect();play();val original=player()
        ui{a.disconnectYouTubePhones()};assertFalse(ui{a.model.store.youTubePhoneEnabled()})
        assertFalse(ui{a.model.youtube.phoneControlsQueue})
        await{tagged("youtube-next")?.isEnabled==true}
        command("pause");SystemClock.sleep(800)
        assertSame(original,player());assertTrue(ui{a.activePlayer!!.isPlaying})
        await{ui{a.model.store.youTubeScreenStore().read()==null}}
    }
    @Test fun pauseAndSeekDuringLoadingApplyOnlyToTheReplacementVideo() {
        connect();play();val previous=player()!!;control("slow_remote=1")
        command("setPlaylist",JSONObject().put("videoId","V0000000002"));await{ui{a.model.youtube.resolving}}
        command("pause");command("seekTo",JSONObject().put("newTime",30))
        assertTrue("Phone pause changed the previous video",ui{previous.playWhenReady})
        assertTrue("Phone seek changed the previous video",ui{previous.currentPosition<20000})
        await{ui{(a.model.route as? Route.Player)?.watch?.title?.id=="V0000000002" && a.activePlayer!==previous && a.activePlayer?.playWhenReady==false}}
        await{ui{kotlin.math.abs(a.activePlayer!!.currentPosition-30000)<1200 && a.activePlayer!!.videoSize.width>0}}
        SystemClock.sleep(300);assertFalse(ui{a.activePlayer!!.playWhenReady})
    }
    @Test fun disconnectCancelsAnInFlightPhoneSourceWithoutReplacingTheCurrentVideo() {
        connect();play();val previous=player()!!;control("slow_remote=1")
        command("setPlaylist",JSONObject().put("videoId","V0000000002"));await{ui{a.model.youtube.resolving}}
        ui{a.disconnectYouTubePhones()};await{ui{!a.model.youtube.resolving}}
        SystemClock.sleep(3300);assertSame(previous,player());assertEquals("V0000000001",ui{(a.model.route as Route.Player).watch.title.id})
        assertTrue(ui{previous.isPlaying})
    }
    @Test fun aFailedHandoffRestoresTheCurrentQueueAndDoesNotBlockAutomaticNext() {
        connect();play(ids="V0000000001,V0000000002");val previous=player()!!
        control("fail_playback=1");command("next")
        await{audit().getJSONArray("resolved").let{(0 until it.length()).any{n->it.optString(n)=="V0000000002"}}}
        await{ui{!a.model.youtube.resolving}};assertSame(previous,player())
        assertTrue("A failed remote replacement must leave the current phone queue in control",ui{a.model.youtube.phoneControlsQueue})
        control("fail_playback=0");ui{previous.seekTo((previous.duration-300).coerceAtLeast(0))}
        await{attachedAndPlaying("V0000000002")}
    }
    @Test fun cancellingCodeOrNavigatingAwayFromASlowRemotePlayCannotTakeOverLater() {
        val original=ui{a}
        control("slow_phone=1");ui{a.connectYouTubePhone()}
        // Dialog.show() returns before InputDispatcher transfers focus. Sending
        // Back immediately can instead exit the root Activity. Wait for the
        // actual sheet/button and loss of the Activity's window focus first.
        await{device.hasObject(By.text("Link your phone")) && device.hasObject(By.text("Done").focused(true)) &&
            ui{!a.hasWindowFocus() && !a.isFinishing && !a.isDestroyed}}
        sendRemoteKey(android.view.KeyEvent.KEYCODE_BACK)
        await{!device.hasObject(By.text("Link your phone")) && ui{a.hasWindowFocus()}}
        assertSame("Cancelling the sheet must retain its Activity",original,ui{a})
        assertTrue(ui{!a.isFinishing && !a.isDestroyed &&
            a.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)})
        SystemClock.sleep(1200);assertFalse(ui{a.model.store.youTubePhoneEnabled()})
        control("slow_phone=0");connect();control("slow=1")
        command("setPlaylist",JSONObject().put("videoId","V0000000001"))
        await{ui{a.model.youtube.resolving}};ui{a.model.open(Route.Settings)}
        await{tagged("settings-list")?.isShown==true};SystemClock.sleep(1400)
        assertEquals(Route.Settings,ui{a.model.route});assertNull(player());assertFalse(ui{a.model.youtube.resolving})
    }
}
