@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
    private fun resolvedIds():List<String> {
        val values=JSONObject(runBlocking{Http().text("$base/youtube/state")}).getJSONArray("resolved")
        return (0 until values.length()).map(values::getString)
    }
    @Before fun prepare() {
        control("reset=1")
        ui {
            a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null)
            a.model.store.youTubePhoneEnabled(false)
            a.model.updatePreferences(Preferences(resume=false,youtubeAutoplay=false))
            a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)
        }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
        showGeneratedShelves()
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
    private fun showGeneratedShelves() {
        ui{a.model.youtube.installFixturePage(YouTubeFeed.HOME,YouTubePage(videos,shelves=rows))}
        await{ui{tagged("youtube-video:${id(1)}")?.let{card->card.isShown && shelf(card)?.stateKey=="HOME/held-0"}==true}}
        ui{tagged("youtube-video:${id(1)}")!!.requestFocusFromTouch()}
        await{focused()=="youtube-video:${id(1)}"}
    }
    private fun playFreshFirstVideo() {
        // UI_HIDDEN trimming may legitimately reload guest recommendations on
        // return. Exercise the currently offered video, not a retained fixture
        // page that the product is free to release under memory pressure.
        await{ui{!a.model.youtube.busy && a.model.youtube.homeShelves.firstOrNull()?.videos?.isNotEmpty()==true}}
        val first=ui{a.model.youtube.homeShelves.first().videos.first()}
        val tag="youtube-video:${first.id}"
        ui{(tagged("youtube-home") as RecyclerView).scrollToPosition(0)}
        await{tagged(tag)?.isShown==true}
        ui{assertTrue(tagged(tag)!!.requestFocusFromTouch())}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui{(a.model.route as? Route.Player)?.watch?.title?.id==first.id}}
        playing();assertEquals(listOf(first.id),resolvedIds())
    }

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
    @Test fun backgroundingCancelsAReleasedPendingOkAndReturningStillAllowsExplicitPlay() {
        val home=tagged("youtube-home") as RecyclerView
        // Hold the next layout so the distant shelf cannot win a race against
        // ActivityScenario's actual onStop/onStart transition. No private state
        // or arbitrary timing sleep is needed to force a pending selection.
        ui{home.suppressLayout(true)}
        try {
            ui {
                assertNull(home.findViewHolderForAdapterPosition(8))
                downs(8);key(KeyEvent.KEYCODE_DPAD_CENTER)
            }
            assertNull(player());assertEquals(emptyList<String>(),resolvedIds())
            rule.scenario.moveToState(Lifecycle.State.CREATED)
            assertNull(player());assertFalse(ui{a.model.youtube.resolving})
            ui{home.suppressLayout(false)}
            rule.scenario.moveToState(Lifecycle.State.RESUMED)
            await{ui{a.hasWindowFocus() && a.model.route==Route.Browse(Section.YOUTUBE)} && anchorReleased()}
            assertNull(player());assertFalse(ui{a.model.youtube.resolving})
            assertEquals("Returning must not replay the old OK",emptyList<String>(),resolvedIds())
            playFreshFirstVideo()
        } finally {
            ui{home.suppressLayout(false)}
            rule.scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }
    @Test fun accountRevocationCancelsAPendingOkEvenWhenTheVisibleSourceIsUnchanged() {
        // Use the existing screen/model and the established generated OAuth
        // flow. Replacing its fixture model inside a coalesced Search→YouTube
        // UI turn would leave the old screen bound to the previous model.
        control("approve=1");ui{a.model.youtube.startSignIn()}
        await{ui{a.model.youtube.account!=null && !a.model.youtube.busy}}
        showGeneratedShelves()
        val client=ui{a.model.youtube.client}
        val before=client.historyAccountEpoch();assertNotNull(before)
        val home=tagged("youtube-home") as RecyclerView
        ui{home.suppressLayout(true)}
        try {
            ui{assertNull(home.findViewHolderForAdapterPosition(8));downs(8);key(KeyEvent.KEYCODE_DPAD_CENTER)}
            // Revoke only the fixture client's credentials, as an account/session
            // expiry would. Retain the same source so dataset removal cannot
            // conceal an absent account guard on the pending input.
            runBlocking{client.signOut()}
            assertNotEquals(before,client.historyAccountEpoch())
            assertEquals(rows.map{it.key},ui{a.model.youtube.homeShelves.map{it.key}})
            ui{home.suppressLayout(false)}
            await{anchorReleased()}
            assertEquals(Route.Browse(Section.YOUTUBE),ui{a.model.route})
            assertNull(player());assertFalse(ui{a.model.youtube.resolving})
            assertEquals("An old account's OK must not resolve a video",emptyList<String>(),resolvedIds())
            playFreshFirstVideo()
        } finally {ui{home.suppressLayout(false)}}
    }
}
