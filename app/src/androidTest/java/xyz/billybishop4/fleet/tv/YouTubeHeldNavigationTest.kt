@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** A burst can arrive before RecyclerView's next layout. Exercise the real root
 * dispatch/input generation without letting per-key idle waits conceal lost input. */
class YouTubeHeldNavigationTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private val videos=(1..120).map{YouTubeVideo(id(it),"Generated held-navigation video $it")}
    private val rows=(0 until 10).map{index->YouTubeShelf("held-$index","Shelf ${index+1}",videos.subList(index*12,(index+1)*12))}
    private var eventClock=0L
    private fun id(index:Int)="H"+index.toString().padStart(10,'0')
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    @Before fun prepare() {
        control("reset=1")
        ui {
            a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null)
            a.model.store.youTubePhoneEnabled(false)
            a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false))
            a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)
        }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
        ui{a.model.youtube.installFixturePage(YouTubeFeed.HOME,YouTubePage(videos,shelves=rows))}
        await{ui{tagged("youtube-video:${id(1)}")?.let{card->card.isShown && shelf(card)?.stateKey=="HOME/held-0"}==true}}
        ui{tagged("youtube-video:${id(1)}")!!.requestFocusFromTouch()}
        await{focused()=="youtube-video:${id(1)}"}
    }
    @After fun cleanup() {
        ui{a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null);a.model.updatePreferences(Preferences())}
        control("reset=1")
    }
    private fun shelf(view:View?)=generateSequence(view){it.parent as? View}.filterIsInstance<YouTubeShelfView>().firstOrNull()
    private fun focused()=ui{a.currentFocus?.tag?.toString()}
    private fun focusedRow()=ui{shelf(a.currentFocus)?.shelfKey}
    private fun event(code:Int,action:Int,down:Long,flags:Int=0) {
        a.dispatchKeyEvent(KeyEvent(down,maxOf(down,SystemClock.uptimeMillis()),action,code,0,0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,0,flags,InputDevice.SOURCE_DPAD))
    }
    private fun down(code:Int):Long {
        eventClock=maxOf(eventClock+1,SystemClock.uptimeMillis())
        event(code,KeyEvent.ACTION_DOWN,eventClock);return eventClock
    }
    private fun key(code:Int){val at=down(code);event(code,KeyEvent.ACTION_UP,at)}
    private fun downs(count:Int){repeat(count){key(KeyEvent.KEYCODE_DPAD_DOWN)}}
    private fun anchorReleased()=ui{tagged("youtube-shelf-focus-anchor")?.isFocusable!=true && focused()!="youtube-shelf-focus-anchor"}

    @Test fun eightDownEventsBeforeLayoutReachTheEighthFollowingShelf() {
        ui{downs(8)}
        await{focusedRow()=="held-8"}
        assertEquals("youtube-video:${id(97)}",focused());assertTrue(anchorReleased());assertNull(player())
    }
    @Test fun reversalAndBothBoundsUseTheLogicalCursorWithoutDroppingMoves() {
        ui{downs(8);repeat(3){key(KeyEvent.KEYCODE_DPAD_UP)}}
        await{focusedRow()=="held-5"};assertEquals("youtube-video:${id(61)}",focused())
        ui{downs(20)};await{focusedRow()=="held-9"}
        ui{repeat(9){key(KeyEvent.KEYCODE_DPAD_UP)}};await{focusedRow()=="held-0"}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{focused()=="youtube-home-search"}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN);await{focusedRow()=="held-0"}
        assertTrue(anchorReleased());assertNull(player())
    }
    @Test fun horizontalKeysAfterADeferredMoveStayInItsTargetShelf() {
        ui{downs(8);repeat(3){key(KeyEvent.KEYCODE_DPAD_RIGHT)};key(KeyEvent.KEYCODE_DPAD_LEFT)}
        await{focusedRow()=="held-8" && focused()=="youtube-video:${id(99)}"}
        assertTrue(anchorReleased());assertNull(player())
    }
    @Test fun pendingOkRequiresItsMatchingReleaseAndOpensOnlyTheTargetVideo() {
        val confirm=ui{downs(8);down(KeyEvent.KEYCODE_DPAD_CENTER)}
        await{focusedRow()=="held-8"};assertNull(player())
        ui{event(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.ACTION_UP,confirm)}
        await{ui{(a.model.route as? Route.Player)?.watch?.title?.id==id(97)}}
        playing();assertEquals(id(97),ui{(a.model.route as Route.Player).watch.title.id})
    }
    @Test fun canceledReleaseAndLaterInputCannotActivateAnOldPendingVideo() {
        ui {
            downs(8);val confirm=down(KeyEvent.KEYCODE_DPAD_CENTER)
            event(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.ACTION_UP,confirm,KeyEvent.FLAG_CANCELED)
        }
        await{focusedRow()=="held-8"};assertNull(player())
        ui {
            (tagged("youtube-home") as RecyclerView).scrollToPosition(0)
        }
        await{tagged("youtube-video:${id(1)}")?.isShown==true};ui{tagged("youtube-video:${id(1)}")!!.requestFocusFromTouch()}
        ui{downs(8);key(KeyEvent.KEYCODE_DPAD_CENTER);key(KeyEvent.KEYCODE_DPAD_UP)}
        await{focusedRow()=="held-7"};assertNull(player());assertTrue(anchorReleased())
    }
    @Test fun removedTargetsCannotPlayOrLeaveTheHiddenAnchorFocused() {
        ui {
            downs(8);key(KeyEvent.KEYCODE_DPAD_CENTER)
            val retained=videos.filterNot{it.id==id(97)}
            a.model.youtube.installFixturePage(YouTubeFeed.HOME,YouTubePage(retained,
                shelves=rows.map{it.copy(videos=it.videos.filterNot{video->video.id==id(97)})}))
        }
        await{anchorReleased()};assertNull(player())
        assertFalse(ui{a.model.youtube.resolving});assertFalse(focused()=="youtube-video:${id(97)}")
    }
    @Test fun backCancelsAReleasedPendingActivationBeforeLeavingThePage() {
        ui {
            // Keep a real prior route, so Back can leave without finishing the test activity.
            a.model.open(Route.Settings);a.model.open(Route.Browse(Section.YOUTUBE))
            downs(8);key(KeyEvent.KEYCODE_DPAD_CENTER);a.onBackPressedDispatcher.onBackPressed()
        }
        await{ui{a.model.route==Route.Settings} && tagged("youtube-page")?.isShown!=true}
        assertNull(player());assertFalse(ui{a.model.youtube.resolving})
    }
}
