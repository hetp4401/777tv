@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Worker emulator + generated media/JSON only. Never targets a physical user TV. */
class YouTubeWatchUiTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    private fun audit()=JSONObject(runBlocking{Http().text("$base/youtube/state")})
    @Before fun prepare() {
        control("reset=1")
        ui { a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE) }
        await{ui{!a.model.youtube.busy} && tagged("youtube-page")?.isShown==true}
    }
    @After fun cleanup() { ui { a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null) };control("reset=1") }
    private fun play() {
        ui{a.model.youtube.play(YouTubeVideo("V0000000001","Generated video"))};playing()
        await{tagged("vod-controls")?.isShown==true && (tagged("vod-panel")?.height?:0)>0}
    }
    private fun panel()=tagged("youtube-watch-panel")
    private fun list()=tagged("youtube-panel-list") as RecyclerView
    private fun openRelated() { click("Related videos");await { panel()?.isShown==true && tagged("youtube-video:V0000000040")?.isShown==true } }
    private fun openComments() { click("Video comments");await{tagged("youtube-comment:comment-1")?.isShown==true} }
    private fun topCommentRequests():Int {
        val rows=audit().getJSONArray("requests")
        return (0 until rows.length()).count { rows.getJSONObject(it).optString("continuation").startsWith("fixture-comments") }
    }
    @Test fun graphUsesTheSameThinStripWithoutAnExtraFocusTarget() {
        play();await{tagged("youtube-replay-heatmap")?.isShown==true}
        val graph=tagged("youtube-replay-heatmap")!!
        assertFalse(ui{graph.isFocusable});assertNull(ui{tagged("vod-panel")!!.background})
        assertTrue(ui{tagged("vod-panel")!!.height<=a.dp(50)})
        val pixels=ui { val bmp=Bitmap.createBitmap(graph.width,graph.height,Bitmap.Config.ARGB_8888);graph.draw(Canvas(bmp));IntArray(bmp.width*bmp.height).also{bmp.getPixels(it,0,bmp.width,0,0,bmp.width,bmp.height);bmp.recycle()} }
        assertTrue("Real marker samples should draw a nonempty graph",pixels.count{it ushr 24>0}>50)
        assertTrue("No graph may be drawn past the supplied time ranges",pixels.indices.none{it%graph.width>graph.width*3/4 && pixels[it] ushr 24>0})
        assertEquals(0,topCommentRequests());screenshot("youtube-replay-graph")
        await(9000){tagged("vod-controls")?.visibility==View.GONE}
        assertTrue(ui{a.player!!.isPlaying});assertFalse(graph.isShown)
    }
    @Test fun videosWithoutReplayDataKeepTheNormalControlsAndStillPlay() {
        control("no_heatmap=1");play()
        await { val rows=audit().getJSONArray("requests");(0 until rows.length()).any{rows.getJSONObject(it).optString("client")=="WEB"} }
        assertFalse(ui{tagged("youtube-replay-heatmap")?.isShown==true});assertTrue(ui{a.player!!.isPlaying});assertNull(panel()?.takeIf{it.isShown})
    }
    @Test fun downOpensRelatedAndBackReturnsDirectlyToVideo() {
        play();val player=player();ui{tagged("vod-play-pause")!!.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        await{panel()?.isShown==true && tagged("youtube-video:V0000000040")?.isShown==true}
        assertSame(player,player());assertTrue(ui{a.player!!.isPlaying});assertEquals(0,topCommentRequests())
        ui{a.onBackPressedDispatcher.onBackPressed()};await{panel()?.visibility==View.GONE}
        assertEquals(View.GONE,ui{tagged("vod-controls")!!.visibility});assertSame(player,player())
        ui{a.onBackPressedDispatcher.onBackPressed()};await{ui{a.model.route==Route.Browse(Section.YOUTUBE)}}
    }
    @Test fun commentsLoadOnOpenAndPagingDoesNotFollowAReplyCursor() {
        play();assertEquals(0,topCommentRequests());openComments();val player=player()
        assertEquals(20,ui{list().adapter!!.itemCount});assertEquals(1,topCommentRequests())
        click("Load more comments");await{ui{list().adapter!!.itemCount==40}}
        assertEquals(2,topCommentRequests());assertSame(player,player());assertTrue(ui{a.player!!.isPlaying})
        assertNull(find("Load more comments"));screenshot("youtube-native-comments")
    }
    @Test fun commentsOpenForReadingAndRepliesReturnToTheirOriginalComments() {
        play();openComments();ui{tagged("youtube-comment:comment-1")!!.performClick()}
        assertTrue(device.wait(Until.hasObject(By.text("Generated comment 1")),5000)==true)
        click("3 replies");await{tagged("youtube-comment:reply-1")?.isShown==true}
        assertEquals(3,ui{list().adapter!!.itemCount});assertTrue(ui{a.player!!.isPlaying})
        ui{a.onBackPressedDispatcher.onBackPressed()};await{tagged("youtube-comment:comment-1")?.isShown==true}
        assertEquals(20,ui{list().adapter!!.itemCount});assertEquals(1,topCommentRequests())
        screenshot("youtube-replies-return")
    }
    @Test fun commentsDisabledIsAnEmptyStateWithoutBreakingThePlayer() {
        control("no_comments=1");play();click("Video comments")
        await{find("Comments unavailable for this video.")!=null}
        assertEquals(0,topCommentRequests());assertEquals(0,ui{list().adapter!!.itemCount});assertTrue(ui{a.player!!.isPlaying})
        click("Related videos tab");await{tagged("youtube-video:V0000000040")?.isShown==true}
    }
    @Test fun relatedVideoReplacesThePlayerAndBackDoesNotStackOldPlayers() {
        play();val previous=player();openRelated();ui{tagged("youtube-video:V0000000040")!!.performClick()}
        await{ui{(a.model.route as? Route.Player)?.watch?.title?.id=="V0000000040" && a.player!==previous && a.player?.isPlaying==true}}
        playing()
        assertNotSame(previous,player());assertEquals(2,audit().getJSONArray("resolved").length())
        ui{a.playback!!.dismissControls();a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.model.route==Route.Browse(Section.YOUTUBE) && a.player==null} && tagged("youtube-page")?.isShown==true}
        assertNull(player())
    }
    @Test fun detailsFailureCanRetryWithoutStoppingPlayback() {
        control("fail_details=1");play();val player=player();click("Related videos")
        await{find("Retry video details")!=null};assertSame(player,player());assertTrue(ui{a.player!!.isPlaying})
        control("fail_details=0");click("Retry video details");await{tagged("youtube-video:V0000000040")?.isShown==true}
        assertSame(player,player());assertNull(find("Retry video details"))
    }
    @Test fun closingALoadingPanelCancelsWorkAndReopeningIsUsable() {
        control("slow=1");play();click("Related videos")
        ui{a.onBackPressedDispatcher.onBackPressed()};await{panel()?.visibility==View.GONE}
        control("slow=0");sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        await{panel()?.isShown==true && tagged("youtube-video:V0000000040")?.isShown==true}
        ui{find("Related videos tab")!!.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(ui{generateSequence(a.currentFocus){it.parent as? View}.any{it===panel()}})
        assertTrue(ui{a.player!!.isPlaying})
    }
    @Test fun bothHeaderActionsCanBeReachedWithTheRemote() {
        play();ui{tagged("vod-progress")!!.requestFocus()}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{ui{a.currentFocus?.contentDescription=="Related videos"}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT);await{ui{a.currentFocus?.contentDescription=="Video comments"}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);await{tagged("youtube-comment:comment-1")?.isShown==true}
        assertTrue(ui{a.player!!.isPlaying})
    }
    @Test fun closeFromRepliesClosesTheWholePanelInOneAction() {
        play();openComments();ui{tagged("youtube-comment:comment-1")!!.performClick()}
        click("3 replies");await{tagged("youtube-comment:reply-1")?.isShown==true}
        click("Close video panel");await{panel()?.visibility==View.GONE}
        assertTrue(ui{a.player!!.isPlaying});assertEquals(View.GONE,ui{tagged("vod-controls")!!.visibility})
    }
}
