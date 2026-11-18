@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real TV section shapes with generated cards; no external media, account or physical TV. */
class YouTubeGroupedBrowseTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    private fun state()=JSONObject(runBlocking{Http().text("$base/youtube/state")})
    @Before fun prepare() {
        control("reset=1&grouped_search=1")
        ui{a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)}
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup(){ui{a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null)};control("reset=1")}
    private fun search(query:String="fixture") {
        if(ui{a.model.youtube.feed!=YouTubeFeed.SEARCH})click("YouTube search")
        field("Search YouTube",query);click("Search YouTube videos")
        await{ui{!a.model.youtube.busy && a.model.youtube.searchShelves.size==3 && a.currentFocus?.tag=="youtube-video:V0000000001"}}
    }
    private fun outer()=tagged("youtube-search-results") as RecyclerView
    private fun row(index:Int):YouTubeShelfView {
        ui{outer().scrollToPosition(index)}
        var found:YouTubeShelfView?=null
        await{ui{found=outer().layoutManager?.findViewByPosition(index) as? YouTubeShelfView;found!=null && found!!.list.childCount>0}}
        return found!!
    }
    private fun focused()=ui{a.currentFocus?.tag?.toString()}
    private fun focusedRow()=ui{generateSequence(a.currentFocus){it.parent as? android.view.View}.filterIsInstance<YouTubeShelfView>().firstOrNull()?.shelfKey}
    private fun continuationRequests()=state().getJSONArray("requests").objects().count{it.optString("path").endsWith("/search") && it.optString("continuation").isNotBlank()}
    @Test fun fullVideosComeFirstAndCanonicalShortsHaveTheirOwnRecycledPortraitRow() {
        search()
        val rows=ui{a.model.youtube.searchShelves}
        assertEquals(listOf("Videos","Recently uploaded","Shorts"),rows.map{it.name})
        assertTrue(rows.dropLast(1).all{!it.portrait && it.videos.all{v->!v.isShort}})
        assertTrue(rows.last().portrait);assertEquals(6,rows.last().videos.size)
        assertEquals(3,ui{outer().adapter!!.itemCount})
        val regular=row(0);val regularImage=ui{descendants(regular.list.getChildAt(0)).filterIsInstance<ImageView>().single()}
        assertTrue(ui{regularImage.width>regularImage.height});assertTrue(ui{regular.list.childCount<8})
        val shorts=row(2);val shortImage=ui{descendants(shorts.list.getChildAt(0)).filterIsInstance<ImageView>().single()}
        assertTrue(ui{shortImage.height>shortImage.width});assertTrue(ui{shorts.list.childCount<10})
        SystemClock.sleep(350)
        assertEquals("Merely showing two or three shelves must not fetch every page",0,continuationRequests())
        assertEquals(0,state().getJSONArray("resolved").length());assertNull(player());screenshot("youtube-grouped-search")
    }
    @Test fun verticalMovementRestoresEachRowsOwnSelectedVideo() {
        search();repeat(2){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)};await{focused()=="youtube-video:V0000000003"}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN);await{focused()=="youtube-video:R0000000001"}
        repeat(3){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)};await{focused()=="youtube-video:R0000000004"}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN);await{focused()=="youtube-video:S0000000001"}
        repeat(3){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)};await{focused()=="youtube-video:S0000000004"}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{focused()=="youtube-video:R0000000004"}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{focused()=="youtube-video:V0000000003"}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN);await{focused()=="youtube-video:R0000000004"}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN);await{focused()=="youtube-video:S0000000004"}
        assertEquals("search/shorts",focusedRow());assertEquals(0,state().getJSONArray("resolved").length())
    }
    @Test fun approachingTheMainRowsEndAppendsRegularResultsBeforeShortsWithoutResettingSelection() {
        search();repeat(9){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        await{ui{!a.model.youtube.busy && a.model.youtube.searchShelves.first().videos.size==32}}
        val rows=ui{a.model.youtube.searchShelves}
        assertEquals(listOf("Videos","Recently uploaded","Shorts"),rows.map{it.name})
        assertEquals(12,rows.last().videos.size);assertEquals(50,ui{a.model.youtube.videos.size})
        assertEquals(1,continuationRequests());assertEquals(50,ui{a.model.youtube.videos.map{it.id}.distinct().size})
        await{focused()=="youtube-video:V0000000010"}
        assertTrue(rows.first().videos.all{!it.isShort});assertNull(player())
        val shorts=row(2);ui{shorts.focusVideo("youtube-video:S0000000004")?.requestFocus()}
        await{focused()=="youtube-video:S0000000004"};assertEquals("search/shorts",focusedRow())
    }
    @Test fun aNewQueryDoesNotInheritAnotherQueriesHorizontalCursorOrFocusedVideo() {
        search();repeat(6){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)};await{focused()=="youtube-video:V0000000007"}
        search("another fixture")
        assertEquals("youtube-video:V0000000001",focused())
        assertEquals("another fixture",ui{a.model.youtube.query})
        val first=row(0);assertTrue(ui{first.stateKey.startsWith("SEARCH/another fixture/")})
        assertEquals(0,ui{(first.list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()})
        assertNull(player())
    }
    @Test fun searchPlayActionsChannelAndBackReturnsToTheSameSearchShelfAndVideo() {
        search();repeat(6){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)};await{focused()=="youtube-video:V0000000007"}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);playing();click("Video actions")
        assertTrue(device.wait(Until.hasObject(By.text("Like").enabled(true)),10000))
        assertTrue(device.wait(Until.hasObject(By.text("Sign in for account actions").focused(true)),10000))
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(device.wait(Until.hasObject(By.text("Fixture creator").focused(true)),5000))
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui{a.model.youtube.feed==YouTubeFeed.CHANNEL && !a.model.youtube.busy} && tagged("youtube-channel-name")?.isShown==true}
        assertNull(player());ui{a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.model.youtube.feed==YouTubeFeed.SEARCH && a.model.youtube.query=="fixture" && !a.model.youtube.busy}}
        await{focused()=="youtube-video:V0000000007"};assertEquals("search/videos",focusedRow())
        assertEquals(1,state().getJSONArray("resolved").length());screenshot("youtube-search-play-channel-return")
    }
    @Test fun aShortsOnlySearchKeepsPortraitBrowsingAndNoRegularGridOrFocusPlayback() {
        val videos=(1..20).map{YouTubeVideo("S"+it.toString().padStart(10,'0'),"Fixture short $it",isShort=true)}
        ui{a.model.youtube.installFixturePage(YouTubeFeed.SEARCH,YouTubePage(videos))}
        await{ui{a.model.youtube.searchShelves.singleOrNull()?.portrait==true} && tagged("youtube-search-results")?.isShown==true}
        val shorts=row(0);ui{shorts.firstVideo()!!.requestFocus()};repeat(7){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        await{focused()=="youtube-video:S0000000008"};assertFalse(tagged("youtube-results")!!.isShown)
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{tagged("youtube-keyboard")?.hasFocus()==true}
        ui{a.onBackPressedDispatcher.onBackPressed()};await{focused()=="youtube-video:S0000000008"}
        assertEquals(0,state().getJSONArray("resolved").length());assertNull(player())
    }
    @Test fun recycledHomeShelvesReturnToTheirRememberedVideoInsteadOfTheirFirstCard() {
        val videos=(1..120).map{YouTubeVideo("H"+it.toString().padStart(10,'0'),"Fixture video $it")}
        val rows=(0 until 10).map{index->YouTubeShelf("fixture-$index","Shelf ${index+1}",videos.subList(index*12,(index+1)*12))}
        ui{a.model.youtube.installFixturePage(YouTubeFeed.HOME,YouTubePage(videos,shelves=rows))}
        await{tagged("youtube-video:H0000000001")?.isShown==true && (tagged("youtube-home") as? RecyclerView)?.let{ui{
            (it.getChildAt(0) as? YouTubeShelfView)?.let{row->row.shelfKey=="fixture-0" && row.list.adapter?.itemCount==12 && row.firstVideo()?.tag=="youtube-video:H0000000001"}==true
        }}==true}
        ui{tagged("youtube-video:H0000000001")!!.requestFocusFromTouch()};await{focused()=="youtube-video:H0000000001"}
        repeat(8){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)};await{focused()=="youtube-video:H0000000009"}
        repeat(9){sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)};await{focusedRow()=="fixture-9"}
        repeat(9){sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)};await{focused()=="youtube-video:H0000000009"}
        assertEquals("fixture-0",focusedRow());assertEquals(0,state().getJSONArray("resolved").length());assertNull(player())
        val outer=tagged("youtube-home") as RecyclerView
        assertTrue(ui{outer.childCount<=4});assertTrue(ui{(outer.layoutManager!!.findViewByPosition(0) as YouTubeShelfView).list.childCount<8})
    }
}
