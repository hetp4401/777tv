@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.media3.common.Player
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Global media keys remain usable while reading native watch panels.
 * Generated worker fixtures only: setup resets local configuration. */
class YouTubePanelMediaKeysTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private val first=YouTubeVideo("V0000000001","First generated video")
    private val second=YouTubeVideo("V0000000002","Explicit second video")
    private val third=YouTubeVideo("V0000000003","Explicit third video")
    private enum class Pane { RELATED,COMMENTS,DESCRIPTION }

    private fun control(query:String)=runBlocking{Http().text("$base/youtube/control?$query")}
    private fun resolved():List<String> {
        val data=JSONObject(runBlocking{Http().text("$base/youtube/state")}).getJSONArray("resolved")
        return (0 until data.length()).map{data.getString(it)}
    }
    @Before fun prepare() {
        control("reset=1")
        ui {
            a.model.top(Section.SEARCH);a.model.server(base);a.model.clearHistory()
            a.model.store.youTubeCredentials().write(null);a.model.store.youTubePhoneEnabled(false)
            a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false,seekSeconds=10))
            a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)
        }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup() {
        ui{a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null);a.model.updatePreferences(Preferences())}
        control("reset=1")
    }
    /** Activity's real Window callback enters the app root and its normal key
     * generation before reaching Playback; never call the controller directly. */
    private fun key(code:Int) {
        ui {
            assertTrue(a.hasWindowFocus())
            val down=SystemClock.uptimeMillis()
            for(action in intArrayOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
                a.window.callback!!.dispatchKeyEvent(KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_DPAD))
            }
        }
    }
    private fun current()=ui{(a.model.route as? Route.Player)?.watch?.title?.id}
    private fun watching(id:String)=ui {
        val watch=(a.model.route as? Route.Player)?.watch
        watch?.title?.id==id && a.playback?.watch===watch && a.activePlayer?.isPlaying==true
    }
    private fun playQueue() {
        ui{a.model.youtube.play(first)}
        await{watching(first.id)}
        ui{assertTrue(a.model.youtube.enqueue(second));assertTrue(a.model.youtube.enqueue(third))}
        await{ui{tagged("youtube-next")?.isEnabled==true}}
        assertEquals(listOf(first.id),resolved())
    }
    private fun panel()=tagged("youtube-watch-panel")
    private fun open(pane:Pane) {
        ui{tagged("native-player")!!.performClick()}
        val action=when(pane){Pane.RELATED->"youtube-settings";Pane.COMMENTS->"youtube-comments";Pane.DESCRIPTION->"youtube-description"}
        await{ui{tagged(action)?.let{it.isShown && it.isEnabled}==true && a.hasWindowFocus()}}
        ui{tagged(action)!!.requestFocusFromTouch()}
        key(if(pane==Pane.RELATED)KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui {
            panel()?.let{it.isShown && it.hasFocus()}==true && a.hasWindowFocus() && when(pane) {
                Pane.RELATED->tagged("youtube-video:V0000000040")?.isShown==true
                Pane.COMMENTS->tagged("youtube-comment:comment-1")?.isShown==true
                Pane.DESCRIPTION->tagged("youtube-description-panel")?.isShown==true
            }
        }}
    }
    private fun closePanel() {
        ui{a.onBackPressedDispatcher.onBackPressed()}
        await{panel()?.isShown!=true && ui{a.hasWindowFocus()}}
    }
    @Test fun nextWhileRelatedIsOpenUsesTheExplicitQueueExactlyOnce() {
        playQueue();val original=player()
        open(Pane.RELATED);assertSame(original,player());assertTrue(ui{original!!.playWhenReady})
        key(KeyEvent.KEYCODE_MEDIA_NEXT)
        await(15000){watching(second.id)}
        assertNotSame(original,player());assertEquals(second.id,current())
        assertTrue(ui{a.activePlayer!!.playWhenReady});assertEquals(listOf(first.id,second.id),resolved())
        assertTrue("Next left the previous video's panel attached",panel()?.isShown!=true)
    }
    private fun previousFrom(pane:Pane) {
        playQueue();key(KeyEvent.KEYCODE_MEDIA_NEXT)
        await{watching(second.id)}
        await{ui{tagged("youtube-previous")?.isEnabled==true}}
        val original=player();open(pane)
        assertSame(original,player());assertTrue(ui{original!!.playWhenReady})
        key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        await(15000){watching(first.id)}
        assertEquals(first.id,current());assertNotSame(original,player())
        assertTrue(ui{a.activePlayer!!.playWhenReady});assertFalse(resolved().contains(third.id))
        assertTrue("Previous left the previous video's panel attached",panel()?.isShown!=true)
    }
    @Test fun previousWhileCommentsAreOpenReturnsToThePreviouslyWatchedVideo()=previousFrom(Pane.COMMENTS)
    @Test fun previousWhileDescriptionIsOpenReturnsToThePreviouslyWatchedVideo()=previousFrom(Pane.DESCRIPTION)

    @Test fun skipKeysInEveryPanelKeepTheSourceAndPlayingOrPausedIntent() {
        playQueue();val original=player()!!
        for(pane in Pane.values())for(shouldPlay in listOf(false,true)) {
            ui{original.pause();original.seekTo(20000)}
            await{ui{original.playbackState==Player.STATE_READY && original.currentPosition in 19500L..20500L}}
            if(shouldPlay){ui{original.play()};playing()}
            open(pane)
            for((code,direction) in listOf(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD to 1,KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD to -1)) {
                val before=ui{original.currentPosition};val expected=before+direction*10000L
                key(code)
                // A five-second wait cannot mistake ten seconds of ordinary
                // playback progression for a successful skip-forward command.
                await(5000){ui{kotlin.math.abs(original.currentPosition-expected)<1500}}
                assertSame(original,player());assertEquals(first.id,current())
                assertEquals("Media skip changed play/pause intention",shouldPlay,ui{original.playWhenReady})
                assertTrue("Seeking dismissed the reading panel",panel()?.isShown==true)
            }
            closePanel()
        }
        assertEquals("Skipping unexpectedly resolved a different video",listOf(first.id),resolved())
    }
}
