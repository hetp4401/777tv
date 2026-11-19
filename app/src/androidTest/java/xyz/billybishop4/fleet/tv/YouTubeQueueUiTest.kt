@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Generated watch-next metadata and real native playback; no media prefetch. */
class YouTubeQueueUiTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private fun state()=JSONObject(runBlocking{Http().text("$base/youtube/state")})
    private fun current()=ui{(a.model.route as? Route.Player)?.watch?.title?.id}
    private fun playingId(id:String)=ui {
        val watch=(a.model.route as? Route.Player)?.watch
        watch?.title?.id==id && a.playback?.watch===watch && a.activePlayer?.isPlaying==true
    }
    private fun resolved()=state().getJSONArray("resolved").let{array->(0 until array.length()).map{array.optString(it)}}
    @Before fun prepare() {
        runBlocking{Http().text("$base/youtube/control?reset=1")}
        ui {
            a.model.top(Section.SEARCH);a.model.server(base);a.model.clearHistory()
            a.model.store.youTubeCredentials().write(null);a.model.store.youTubePhoneEnabled(false)
            a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false));a.model.youtubeFixture(base)
            a.model.top(Section.YOUTUBE)
        }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy && a.model.youtube.videos.isNotEmpty()}}
    }
    @After fun cleanup(){ui{a.model.top(Section.SEARCH);a.model.updatePreferences(Preferences())}}
    private fun play(id:String="V0000000001") {
        ui{a.model.youtube.play(YouTubeVideo(id,"Generated "+id))}
        await{playingId(id)}
        await{tagged("youtube-next")?.isEnabled==true}
    }
    private fun end() {
        ui{a.activePlayer!!.seekTo((a.activePlayer!!.duration-250).coerceAtLeast(0));a.activePlayer!!.play()}
    }
    @Test fun nextAndPreviousUseWatchNextWithoutResolvingUnselectedVideos() {
        play();val first=player()
        assertFalse(ui{tagged("youtube-previous")?.isShown==true})
        assertEquals(listOf("V0000000001"),resolved())
        ui{tagged("youtube-next")!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{playingId("V0000000040")}
        assertNotSame(first,player());assertEquals(listOf("V0000000001","V0000000040"),resolved())
        await{tagged("youtube-previous")?.isShown==true && tagged("youtube-previous")?.isEnabled==true}
        ui{tagged("youtube-previous")!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{playingId("V0000000001")}
        assertFalse(ui{tagged("youtube-previous")!!.isShown})
        assertNull(tagged("vod-options"));screenshot("youtube-next-video-controls")
    }
    @Test fun explicitQueueWinsAndAutoplayCanBeTurnedOffWithoutBreakingNext() {
        play()
        ui {
            assertTrue(a.model.youtube.enqueue(YouTubeVideo("V0000000002","Second")))
            assertTrue(a.model.youtube.enqueue(YouTubeVideo("V0000000003","Third")))
            a.model.updatePreferences(a.model.preferences.copy(youtubeAutoplay=true))
        }
        assertEquals(listOf("V0000000001"),resolved());sendRemoteKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        await{playingId("V0000000002")}
        end();await{playingId("V0000000003")}
        ui{a.model.updatePreferences(a.model.preferences.copy(youtubeAutoplay=false))}
        val last=player()!!;end();await{ui{last.playbackState==Player.STATE_ENDED}}
        SystemClock.sleep(500);assertSame(last,player());assertEquals("V0000000003",current())
        sendRemoteKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        await{playingId("V0000000040")}
    }
    @Test fun aFreshLocalSelectionRetiresQueuedItemsAndThePreviousTrail() {
        play();ui{a.model.youtube.enqueue(YouTubeVideo("V0000000002","Old queue"))}
        play("V0000000003")
        sendRemoteKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);SystemClock.sleep(200)
        assertEquals("V0000000003",current())
        sendRemoteKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        await{playingId("V0000000040")}
        assertFalse(resolved().contains("V0000000002"))
    }
    @Test fun relatedPanelSharesThePreparedWatchNextMetadata() {
        play();val original=player()
        fun requests()=state().getJSONArray("requests").objects().count {
            it.optString("path").endsWith("/next") && it.optString("client")!="WEB" && it.optString("videoId")=="V0000000001"
        }
        assertEquals(1,requests());remote("Related videos")
        await{tagged("youtube-video:V0000000040")?.isShown==true}
        assertEquals(1,requests());assertSame(original,player());assertTrue(ui{a.activePlayer!!.playWhenReady})
    }
    @Test fun aRelatedCardCanBeQueuedWithTheRemoteWithoutStartingIt() {
        play();remote("Related videos");await{tagged("youtube-video:V0000000040")?.isShown==true}
        ui{tagged("youtube-video:V0000000040")!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_MENU)
        assertTrue(device.wait(Until.hasObject(By.text("Add to queue").enabled(true)),10000))
        assertTrue(device.wait(Until.hasObject(By.text("Like").enabled(true)),10000))
        assertTrue(device.wait(Until.hasObject(By.text("Sign in for account actions").focused(true)),10000))
        // Accessibility publishes focus after the native key dispatch. Wait for
        // the target highlight before deciding to send another menu direction.
        repeat(12){if(!device.wait(Until.hasObject(By.text("Add to queue").focused(true)),250))sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)}
        assertTrue(device.wait(Until.hasObject(By.text("Add to queue").focused(true)),1000));sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(device.wait(Until.hasObject(By.text("In queue").enabled(false)),5000))
        screenshot("youtube-queued-video-actions")
        assertEquals(listOf("V0000000001"),resolved());click("Done")
        await{ui{a.hasWindowFocus()}}
        ui{a.onBackPressedDispatcher.onBackPressed()}
        await{tagged("youtube-watch-panel")?.isShown!=true && tagged("native-player")?.hasFocus()==true && ui{a.hasWindowFocus()}}
        assertTrue(ui{a.model.youtube.canStepRegular((a.model.route as Route.Player).watch,1)})
        sendRemoteKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        await{playingId("V0000000040")}
    }
    @Test fun lateRecommendationsRespectAutoplayAndCannotLeaveEnabledAutoplayStuckAtTheEnd() {
        for(enabled in listOf(true,false)) {
            runBlocking{Http().text("$base/youtube/control?reset=1&slow_next=1")}
            ui {
                a.model.top(Section.SEARCH);a.model.clearHistory();a.model.youtubeFixture(base)
                a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=enabled))
                a.model.youtube.play(YouTubeVideo("V0000000001","Short regular video"))
            }
            await{playingId("V0000000001") && ui{a.playback?.snapshot?.firstFrameMs?.let{it>0}==true}}
            val first=player()!!;end()
            if(enabled) {
                await{playingId("V0000000040")}
                assertEquals(1,resolved().count{it=="V0000000040"})
            } else {
                await{ui{first.playbackState==Player.STATE_ENDED} && tagged("youtube-next")?.isEnabled==true}
                assertSame(first,player());assertEquals(listOf("V0000000001"),resolved())
            }
        }
    }
}
