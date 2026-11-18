@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
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
        control("slow_phone=1");ui{a.connectYouTubePhone()}
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(1200);assertFalse(ui{a.model.store.youTubePhoneEnabled()})
        control("slow_phone=0");connect();control("slow=1")
        command("setPlaylist",JSONObject().put("videoId","V0000000001"))
        await{ui{a.model.youtube.resolving}};ui{a.model.open(Route.Settings)}
        await{tagged("settings-list")?.isShown==true};SystemClock.sleep(1400)
        assertEquals(Route.Settings,ui{a.model.route});assertNull(player());assertFalse(ui{a.model.youtube.resolving})
    }
}
