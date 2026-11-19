@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ImageView
import android.widget.ImageButton
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/** The native YouTube watch page, using generated media/accounts on worker emulators only. */
class YouTubePlayerUiTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private val video=YouTubeVideo("V0000000001","Generated YouTube watch page",channel="Fixture creator",channelId="UC"+"1".repeat(22))
    private fun control(query:String)=runBlocking{Http().text("$base/youtube/control?$query")}
    private fun state()=JSONObject(runBlocking{Http().text("$base/youtube/state")})
    private fun info()=JSONObject(ui{a.playbackInfo})
    @Before fun prepare() {
        control("reset=1")
        ui {
            a.model.top(Section.SEARCH);a.model.server(base);a.model.clearHistory()
            a.model.store.youTubeCredentials().write(null);a.model.store.youTubePhoneEnabled(false)
            a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false))
            a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)
        }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup() {
        ui{a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null);a.model.updatePreferences(Preferences())}
        control("reset=1")
    }
    private fun play(choice:YouTubeVideo=video) {
        ui{a.model.youtube.play(choice)};playing()
        await{tagged("youtube-controls-row")?.isShown==true && tagged("vod-play-pause")?.hasFocus()==true}
    }
    private fun press(tag:String) {
        await{tagged(tag)?.let{it.isShown && it.isEnabled}==true}
        ui{tagged(tag)!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun bounds(tag:String)=ui{Rect().apply{assertTrue("Missing visible $tag",tagged(tag)?.getGlobalVisibleRect(this)==true)}}
    private fun holdEvent(code:Int,action:Int,down:Long,offset:Long=0,repeats:Int=0) {
        val at=down+offset;val delay=at-SystemClock.uptimeMillis()
        if(delay>0)SystemClock.sleep(delay)
        InstrumentationRegistry.getInstrumentation().sendKeySync(KeyEvent(down,maxOf(at,SystemClock.uptimeMillis()),action,code,repeats))
    }
    private fun signIn() {
        control("approve=1");ui{a.model.youtube.startSignIn()}
        await{ui{a.model.youtube.account!=null && !a.model.youtube.busy}}
    }
    @Test fun theWatchPageHasAFullWidthTimelineAndThreeUnboxedControlGroups() {
        play()
        val timeline=bounds("vod-progress");val row=bounds("youtube-controls-row")
        val left=bounds("youtube-channel-controls");val transport=bounds("youtube-transport-controls");val right=bounds("youtube-engagement-controls")
        val root=bounds("vod-controls")
        assertTrue("The timeline is compressed beside buttons",timeline.width()>=root.width()*.85)
        assertTrue("Transport must sit below the timeline",timeline.bottom<=row.top)
        assertTrue(left.right<=transport.left);assertTrue(transport.right<=right.left)
        assertTrue(kotlin.math.abs(transport.centerX()-root.centerX())<=ui{a.dp(28)})
        assertTrue(ui{tagged("vod-panel")!!.height<=a.dp(50)})
        assertNull(ui{tagged("vod-panel")!!.background});assertNull(ui{tagged("youtube-controls-row")!!.background})
        assertFalse(ui{tagged("vod-title")!!.isClickable || tagged("vod-title")!!.isFocusable})
        assertTrue(bounds("vod-title").bottom<timeline.top)
        for(tag in listOf("youtube-channel","youtube-description","youtube-subscribe","youtube-like","youtube-dislike","youtube-save","youtube-captions","youtube-settings"))
            assertTrue("Missing watch-page action $tag",ui{tagged(tag)?.isShown==true})
        for(tag in listOf("vod-rewind","vod-forward","vod-options"))assertFalse("Movie control $tag leaked into YouTube",ui{tagged(tag)?.isShown==true})
        assertFalse(ui{descendants(a.window.decorView).any{it is android.webkit.WebView}})
        assertTrue("The watch HUD unexpectedly expanded its native hierarchy",ui{descendants(tagged("vod-controls")!!).count()<=150})
        screenshot("youtube-tv-watch-page")
    }
    @Test fun remoteNavigationFollowsTheVisualGroupsTimelineAndRelatedShelf() {
        play();val original=player()
        ui{tagged("youtube-like")!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        await{tagged("youtube-dislike")?.hasFocus()==true}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{tagged("vod-progress")?.hasFocus()==true}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN);await{tagged("vod-play-pause")?.hasFocus()==true}
        ui{tagged("youtube-settings")!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        await{tagged("youtube-watch-panel")?.isShown==true && tagged("youtube-video:V0000000040")?.isShown==true}
        assertSame(original,player());assertTrue(ui{original!!.playWhenReady})
        sendRemoteKey(KeyEvent.KEYCODE_BACK);await{tagged("youtube-watch-panel")?.isShown!=true}
        assertSame(original,player());assertTrue(ui{a.model.route is Route.Player})
    }
    @Test fun focusedTransportAndActionIconsRemainLegibleOnTheirWhiteSelection() {
        play()
        for(tag in listOf("vod-play-pause","youtube-description","youtube-like","youtube-captions","youtube-settings")) {
            ui{tagged(tag)!!.requestFocusFromTouch()};await{tagged(tag)?.hasFocus()==true}
            val darkPixels=ui {
                val button=tagged(tag) as ImageButton
                val bitmap=Bitmap.createBitmap(button.width,button.height,Bitmap.Config.ARGB_8888)
                try {
                    button.draw(Canvas(bitmap))
                    var dark=0
                    for(y in button.paddingTop until button.height-button.paddingBottom)
                        for(x in button.paddingLeft until button.width-button.paddingRight) {
                            val pixel=bitmap.getPixel(x,y)
                            if(Color.alpha(pixel)>200 && Color.red(pixel)<48 && Color.green(pixel)<48 && Color.blue(pixel)<48)dark++
                        }
                    dark
                } finally { bitmap.recycle() }
            }
            assertTrue("Focused $tag disappeared into the white selection",darkPixels>20)
        }
        screenshot("youtube-focused-settings")
    }
    @Test fun descriptionUsesPreparedMetadataAndBackReturnsToTheSameVideo() {
        play();val original=player()!!
        await{ui{a.model.youtube.preparedDetails(video.id)?.description?.isNotBlank()==true}}
        fun nextRequests()=state().getJSONArray("requests").objects().count {
            it.optString("path").endsWith("/next") && it.optString("client")!="WEB" && it.optString("videoId")==video.id
        }
        val before=nextRequests();val playIntent=ui{original.playWhenReady}
        press("youtube-description")
        await{tagged("youtube-description-panel")?.isShown==true && ui{(tagged("youtube-description-text") as? TextView)?.text?.isNotBlank()==true}}
        assertSame(original,player());assertEquals(playIntent,ui{original.playWhenReady});assertEquals(before,nextRequests())
        screenshot("youtube-video-description")
        sendRemoteKey(KeyEvent.KEYCODE_BACK);await{tagged("youtube-watch-panel")?.isShown!=true}
        assertSame(original,player());assertEquals(playIntent,ui{original.playWhenReady});assertTrue(ui{a.model.route is Route.Player})
        if(tagged("vod-controls")?.isShown!=true)sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{tagged("youtube-controls-row")?.isShown==true};assertSame(original,player())
    }
    @Test fun heldSeekingPreviewsWithoutDecoderSeeksAndCommitsOnceOnRelease() {
        play();val original=player()!!
        val seeks=AtomicInteger()
        val listener=object:Player.Listener {
            override fun onPositionDiscontinuity(old:Player.PositionInfo,new:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)seeks.incrementAndGet()}
        }
        try {
            for(wasPlaying in listOf(false,true)) {
                ui{original.pause();original.seekTo(20000)}
                await{ui{original.currentPosition in 19500L..20500L && original.playbackState==Player.STATE_READY}}
                if(wasPlaying){ui{original.play()};playing()}
                ui{original.addListener(listener);tagged("vod-progress")!!.requestFocusFromTouch()};seeks.set(0)
                val down=SystemClock.uptimeMillis()
                holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_DOWN,down)
                holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_DOWN,down,450,1)
                await{tagged("youtube-seek-preview")?.isShown==true}
                assertEquals("Preview issued a decoder seek",0,seeks.get())
                assertTrue(ui{original.currentPosition in 19000L..23000L})
                assertTrue(ui{(tagged("youtube-seek-preview-time") as TextView).text.toString().contains("0:00")})
                holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_UP,down,500)
                await{seeks.get()==1 && ui{original.currentPosition<6000 && original.playWhenReady==wasPlaying} && tagged("youtube-seek-preview")?.isShown!=true}
                assertSame(original,player());ui{original.removeListener(listener)}
            }
        }finally{ui{original.removeListener(listener)}}
    }
    @Test fun backCancelsASeekPreviewBeforeHidingTheHudAndLeavingPlayback() {
        play();val original=player()!!;ui{original.seekTo(20000)}
        await{ui{original.currentPosition in 19500L..22500L && original.isPlaying}}
        val seeks=AtomicInteger();val listener=object:Player.Listener {
            override fun onPositionDiscontinuity(old:Player.PositionInfo,new:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)seeks.incrementAndGet()}
        }
        ui{original.addListener(listener);tagged("vod-progress")!!.requestFocusFromTouch()}
        try {
            val down=SystemClock.uptimeMillis();holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_DOWN,down)
            await{tagged("youtube-seek-preview")?.isShown==true}
            sendRemoteKey(KeyEvent.KEYCODE_BACK)
            holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_UP,down)
            await{tagged("youtube-seek-preview")?.isShown!=true && ui{original.playWhenReady}}
            assertEquals(0,seeks.get());assertTrue(ui{original.currentPosition>19000})
            assertTrue(ui{tagged("vod-controls")!!.isShown});assertSame(original,player())
            sendRemoteKey(KeyEvent.KEYCODE_BACK);await{tagged("vod-controls")?.isShown!=true}
            assertTrue(ui{a.model.route is Route.Player})
            sendRemoteKey(KeyEvent.KEYCODE_BACK);await{ui{a.model.route==Route.Browse(Section.YOUTUBE) && a.player==null}}
        }finally{ui{original.removeListener(listener)}}
    }
    @Test fun explicitMediaIntentDuringPreviewCommitsOnceAndSurvivesTheArrowRelease() {
        play();val original=player()!!
        val seeks=AtomicInteger();val listener=object:Player.Listener {
            override fun onPositionDiscontinuity(old:Player.PositionInfo,new:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)seeks.incrementAndGet()}
        }
        val cases=listOf(Triple(true,KeyEvent.KEYCODE_MEDIA_PAUSE,false),Triple(false,KeyEvent.KEYCODE_MEDIA_PLAY,true),
            Triple(true,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,false),Triple(false,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,true))
        try {
            for((wasPlaying,command,expectedPlay) in cases) {
                ui{original.pause();original.seekTo(20000)}
                await{ui{original.currentPosition in 19500L..20500L && original.playbackState==Player.STATE_READY}}
                if(wasPlaying){ui{original.play()};playing()}
                ui{original.addListener(listener);tagged("vod-progress")!!.requestFocusFromTouch()};seeks.set(0)
                val down=SystemClock.uptimeMillis();holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_DOWN,down)
                await{tagged("youtube-seek-preview")?.isShown==true};assertEquals(0,seeks.get())
                sendRemoteKey(command)
                await{seeks.get()==1 && tagged("youtube-seek-preview")?.isShown!=true && ui{original.playWhenReady==expectedPlay}}
                holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_UP,down)
                SystemClock.sleep(100)
                assertEquals("The arrow release repeated a committed media command",1,seeks.get())
                assertEquals(expectedPlay,ui{original.playWhenReady});assertSame(original,player())
                ui{original.removeListener(listener)}
            }
        }finally{ui{original.removeListener(listener)}}
    }
    @Test fun storyboardArtworkLoadsOnlyDuringScrubbingAndMissingDataKeepsTextPreview() {
        // Earlier player cases may have warmed the shared sprite cache. This
        // case measures first-load timing as well as the cache-free fallback.
        ui{a.artwork.loader.memoryCache?.clear()}
        for(textOnly in listOf(false,true)) {
            control("no_storyboard="+if(textOnly)"1" else "0")
            val before=state().getJSONArray("storyboardRequests").length()
            play(video.copy(id=if(textOnly)"V0000000002" else video.id))
            assertEquals("Playback startup fetched a seek thumbnail",before,state().getJSONArray("storyboardRequests").length())
            ui{a.player!!.pause();a.player!!.seekTo(20000)}
            await{ui{a.player!!.currentPosition in 19500L..20500L}}
            ui{tagged("vod-progress")!!.requestFocusFromTouch()}
            val down=SystemClock.uptimeMillis();holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_DOWN,down)
            await{tagged("youtube-seek-preview")?.isShown==true}
            val image=tagged("youtube-seek-preview-image") as ImageView
            if(textOnly) {
                SystemClock.sleep(250)
                assertEquals(before,state().getJSONArray("storyboardRequests").length())
                assertNull(ui{image.drawable})
                assertTrue(ui{(tagged("youtube-seek-preview-time") as TextView).text.isNotBlank()})
            } else {
                await{ui{image.isShown && image.drawable!=null}}
                assertTrue(state().getJSONArray("storyboardRequests").length()>before)
                screenshot("youtube-native-seek-preview")
            }
            sendRemoteKey(KeyEvent.KEYCODE_BACK);holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_UP,down)
            await{tagged("youtube-seek-preview")?.isShown!=true}
            assertNull("Closing preview retained its image",ui{image.drawable})
            ui{a.model.top(Section.YOUTUBE)};await{ui{a.player==null}}
        }
    }
    @Test fun closingAnInFlightStoryboardPreviewRejectsItsLateImage() {
        // Force an actual pending fetch; a cache hit cannot exercise cancellation.
        ui{a.artwork.loader.memoryCache?.clear()}
        control("slow_storyboard=1");play();ui{a.player!!.pause();a.player!!.seekTo(20000)}
        await{ui{a.player!!.currentPosition in 19500L..20500L}}
        val before=state().getJSONArray("storyboardRequests").length()
        ui{tagged("vod-progress")!!.requestFocusFromTouch()}
        val down=SystemClock.uptimeMillis();holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_DOWN,down)
        await{state().getJSONArray("storyboardRequests").length()>before}
        val image=tagged("youtube-seek-preview-image") as ImageView
        sendRemoteKey(KeyEvent.KEYCODE_BACK);holdEvent(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.ACTION_UP,down)
        SystemClock.sleep(1000)
        assertFalse(ui{tagged("youtube-seek-preview")!!.isShown});assertNull(ui{image.drawable})
        assertFalse(ui{a.player!!.playWhenReady})
    }
    @Test fun theHudAutoHidesDuringPlaybackAndHiddenOkRevealsWithoutPausing() {
        play();val original=player()!!
        await(9000){tagged("vod-controls")?.isShown!=true}
        assertTrue(ui{original.isPlaying});sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{tagged("vod-play-pause")?.hasFocus()==true && tagged("vod-controls")?.isShown==true}
        assertTrue(ui{original.playWhenReady});sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui{!original.playWhenReady}}
        assertEquals("Play",ui{(tagged("youtube-focused-action") as TextView).text.toString()})
        SystemClock.sleep(5500);assertTrue(ui{tagged("vod-controls")!!.isShown})
        assertSame(original,player())
    }
    @Test fun captionsToggleTheActualSubtitleTrackWithoutUnpausing() {
        ui{a.model.open(Route.Player(Watch(video.title(),Stream("Generated captions","$base/media/multitrack.mkv"),resolvedAtMs=System.currentTimeMillis())))}
        playing();val original=player()!!
        ui {
            original.pause();original.seekTo(1500)
            original.trackSelectionParameters=original.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
        }
        fun selected()=ui{original.currentTracks.groups.any{it.type==C.TRACK_TYPE_TEXT && it.isSelected}}
        await{ui{original.playbackState==Player.STATE_READY && !original.playWhenReady} && !selected()}
        press("youtube-captions");await{selected()}
        assertFalse(ui{original.playWhenReady});assertSame(original,player())
        ui{original.play()};await{info().optString("subtitleText").contains("FLEET SUBTITLE FIXTURE")}
        ui{original.pause()};press("youtube-captions");await{!selected() && info().optString("subtitleText").isBlank()}
        assertFalse(ui{original.playWhenReady});assertSame(original,player())
    }
    @Test fun settingsExposeActualPlaybackOptionsWithoutRecreatingThePlayer() {
        play();val original=player()!!;ui{original.pause()};press("youtube-settings")
        for(label in listOf("Video quality","Audio track","Subtitles","Playback speed"))
            assertTrue("Missing YouTube playback setting $label",device.wait(Until.hasObject(By.text(label)),5000))
        assertFalse(device.hasObject(By.text("Picture controls")))
        click("Video quality");assertTrue(device.wait(Until.hasObject(By.text("Auto")),5000));click("Auto")
        await{ui{a.hasWindowFocus()}};assertSame(original,player());assertFalse(ui{original.playWhenReady})
    }
    @SdkSuppress(minSdkVersion=29)
    @Test fun visibleAccountActionsWaitForAcknowledgmentAndSaveOpensPlaylistsDirectly() {
        signIn();play();val original=player()!!;control("slow_actions=1")
        press("youtube-like");assertFalse(ui{tagged("youtube-like")!!.isSelected})
        await{ui{tagged("youtube-like")!!.isSelected && tagged("youtube-like")!!.isEnabled}}
        assertEquals("LIKE",state().getJSONObject("ratings").getString(video.id));control("slow_actions=0")
        press("youtube-subscribe");await{state().getBoolean("subscribed") && ui{tagged("youtube-subscribe")!!.isSelected}}
        press("youtube-save")
        assertTrue(device.wait(Until.hasObject(By.text("Save video")),5000))
        assertFalse(device.hasObject(By.text("Video actions")))
        val watchLater=By.text(Pattern.compile(".*Watch later"))
        assertTrue(device.wait(Until.hasObject(watchLater),5000))
        val savedRow=ui{WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull{it.findTag("youtube-playlist:WL")}}
        assertNotNull(savedRow);ui{savedRow!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{state().getJSONObject("playlists").getJSONObject("WL").getJSONArray("videos").length()==1}
        click("Done");await{ui{a.hasWindowFocus()}}
        assertSame(original,player());assertTrue(ui{original.playWhenReady})
    }
    @Test fun aYouTubeLiveDvrStreamUsesYouTubeActionsAndActualSeekability() {
        ui{a.model.open(Route.Player(Watch(video.copy(live=true).title(),Stream("Generated live DVR","$base/live/demo/index.m3u8"),live=true,resolvedAtMs=System.currentTimeMillis())))}
        playing();val original=player()!!
        await{tagged("youtube-controls-row")?.isShown==true}
        assertTrue(ui{tagged("youtube-like")!!.isShown && tagged("youtube-captions")!!.isShown && tagged("youtube-settings")!!.isShown})
        assertNull(tagged("live-info"));assertFalse(ui{tagged("vod-options")?.isShown==true})
        val seekable=ui{original.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && original.isCurrentMediaItemSeekable && original.duration>0}
        assertEquals(seekable,ui{(tagged("vod-progress") as SeekBar).isEnabled})
        sendRemoteKey(KeyEvent.KEYCODE_MEDIA_PAUSE);await{ui{!original.playWhenReady}}
        assertSame(original,player());sendRemoteKey(KeyEvent.KEYCODE_MEDIA_PLAY);playing()
    }
}
