@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Generated account/content fixtures only; never provisions or tests a real user's TV. */
class YouTubeAccountFeedsTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    private fun requests()=JSONObject(runBlocking{Http().text("$base/youtube/state")}).getJSONArray("requests").objects()
    @Before fun prepare() {
        control("reset=1")
        ui{a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null);a.model.clearHistory();a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)}
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy && a.model.youtube.videos.isNotEmpty()}}
    }
    @After fun cleanup(){ui{a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null);a.model.clearHistory()};control("reset=1")}
    private fun signIn(){control("approve=1");ui{a.model.youtube.startSignIn()};await{ui{a.model.youtube.account!=null && !a.model.youtube.busy}}}
    private fun ready(feed:YouTubeFeed)=await{ui{a.model.youtube.feed==feed && !a.model.youtube.busy && a.model.youtube.error==null}}
    private fun history()=Progress(Title("W0000000001","A watched fixture","youtube",poster="$base/logo.png"),null,45_000,120_000,System.currentTimeMillis())
    private fun guestRows()=ui{a.model.youtube.homeShelves.map{it.name to it.videos.map(YouTubeVideo::id)}}
    private fun expectedGuestRows(continued:Boolean)=listOf(Triple("Recommended",1,"V"),Triple("New to you",13,"V"),Triple("Shorts",25,"S")).map{(name,start,prefix)->
        val first=(start until start+12).map{prefix+it.toString().padStart(10,'0')}
        name to (first+if(continued)(start+36 until start+48).map{prefix+it.toString().padStart(10,'0')} else emptyList())
    }
    private fun assertGuestUpstreamPrefix() {
        val rows=guestRows();val full=expectedGuestRows(true)
        assertEquals(full.map{it.first},rows.map{it.first})
        for((index,row) in rows.withIndex()) {
            assertTrue("Unexpected upstream row size in ${row.first}: ${row.second.size}",row.second.size in listOf(12,24))
            assertEquals("The server's initial row order changed",full[index].second.take(12),row.second.take(12))
            assertEquals("Local history injected or reordered upstream cards",full[index].second.take(row.second.size),row.second)
        }
        assertEquals("The fixture appends all three shelves together",1,rows.map{it.second.size}.distinct().size)
    }
    private fun guestEvidence(name:String) {
        val upstream=JSONObject(runBlocking{Http().text("$base/youtube/state")})
        val model=ui{
            val youtube=a.model.youtube
            val home=tagged("youtube-home") as? RecyclerView
            val layout=home?.layoutManager as? LinearLayoutManager
            JSONObject().put("feed",youtube.feed.name).put("busy",youtube.busy).put("error",youtube.error)
                .put("continuation",youtube.continuation).put("accountPresent",youtube.account!=null)
                .put("videos",JSONArray(youtube.videos.map{it.id}))
                .put("shelves",JSONArray(youtube.homeShelves.map{row->JSONObject().put("name",row.name).put("key",row.key).put("ids",JSONArray(row.videos.map{it.id}))}))
                .put("localHistoryIds",JSONArray(a.model.store.youTubeHistory().map{it.title.id}))
                .put("windowFocus",a.hasWindowFocus()).put("focus",a.currentFocus?.tag?.toString())
                .put("playerPresent",a.activePlayer!=null).put("visibleFirst",layout?.findFirstVisibleItemPosition())
                .put("visibleLast",layout?.findLastVisibleItemPosition()).put("adapterRows",home?.adapter?.itemCount)
        }
        File(ui{a.getExternalFilesDir(null)!!},"youtube-account-$name.json")
            .writeText(JSONObject().put("model",model).put("fixture",upstream).toString(2))
    }
    private fun withGuestEvidence(name:String,action:()->Unit) {
        guestEvidence("$name-before")
        try { action();guestEvidence("$name-passed") }
        catch(error:Throwable) { runCatching{guestEvidence("$name-failed-before-cleanup")};throw error }
    }
    @Test fun freshHomeHasRealGuestCardsWithoutCreatingAnAccountOrPlayingOnFocus() {
        assertNull(ui{a.model.youtube.account});assertNull(ui{a.model.store.youTubeCredentials().read()})
        assertEquals(listOf("Recommended","New to you","Shorts"),ui{a.model.youtube.homeShelves.map{it.name}})
        assertTrue(ui{a.model.youtube.videos.any{it.isShort} && a.model.youtube.videos.any{!it.isShort}})
        assertTrue(requests().any{it.optString("browseId")=="default" && !it.optBoolean("authenticated")})
        assertFalse(requests().any{it.optString("browseId").startsWith("FEtopics_")})
        val first=ui{a.model.youtube.videos.first()};await{tagged("youtube-video:${first.id}")?.isShown==true}
        ui{tagged("youtube-video:${first.id}")!!.requestFocus()};repeat(5){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        assertNull(player());assertTrue(requests().all{!it.optBoolean("authenticated")})
        screenshot("youtube-guest-discover")
    }
    @Test fun anEmptyGuestFeedOffersARealRefreshActionAndRecoversWithoutSigningIn() {
        control("empty_guest=1");ui{a.model.youtube.refresh()}
        await{ui{!a.model.youtube.busy && a.model.youtube.videos.isEmpty()}}
        await{tagged("youtube-empty-refresh")?.isShown==true}
        assertFalse(tagged("field:Search YouTube")!!.isShown)
        control("empty_guest=0");click("Refresh YouTube")
        await{ui{!a.model.youtube.busy && a.model.youtube.videos.isNotEmpty() && a.currentFocus?.tag.toString().startsWith("youtube-video:")}}
        assertNull(player());assertNull(ui{a.model.youtube.account})
    }
    @Test fun localHistoryWorksSignedOutAndIsReachableWithOneLibraryAction() {
        ui{a.model.store.youTubeProgress(history())};click("YouTube library");ready(YouTubeFeed.HISTORY)
        await{tagged("youtube-video:W0000000001")?.isShown==true}
        assertEquals(listOf("W0000000001"),ui{a.model.youtube.videos.map{it.id}})
        assertFalse(requests().any{it.optString("browseId")=="FEhistory"});assertNull(ui{a.model.youtube.account})
        await{ui{a.currentFocus?.tag=="youtube-video:W0000000001"}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_UP);await{ui{a.currentFocus?.contentDescription=="YouTube history"}}
        screenshot("youtube-local-history")
    }
    @Test fun emptyLocalHistoryFocusesItsTabsAndAccountFeedsOfferDirectSignIn() {
        click("YouTube library");ready(YouTubeFeed.HISTORY)
        await{ui{a.currentFocus?.contentDescription=="YouTube history"}}
        click("YouTube Watch Later");ready(YouTubeFeed.WATCH_LATER)
        await{ui{a.currentFocus?.contentDescription=="Sign in to YouTube"}}
        assertTrue(find("Sign in to YouTube")!!.isShown)
        click("YouTube liked videos");ready(YouTubeFeed.LIKED);await{find("Sign in to YouTube")?.isShown==true}
        click("YouTube subscriptions");ready(YouTubeFeed.SUBSCRIPTIONS);await{find("Sign in to YouTube")?.isShown==true}
        assertFalse(requests().any{it.optString("browseId") in setOf("FEsubscriptions","FEhistory","FEmy_youtube","VLLL")})
        screenshot("youtube-subscriptions-signin")
    }
    @Test fun signedInSubscriptionsPageAndPaginateWithoutResolvingAnyMedia() {
        signIn();click("YouTube subscriptions");ready(YouTubeFeed.SUBSCRIPTIONS)
        await{ui{a.model.youtube.videos.size>=20}}
        assertTrue(ui{a.model.youtube.videos.all{it.id.startsWith("U")}})
        val grid=tagged("youtube-results") as RecyclerView
        await{ui{grid.adapter?.itemCount==20 && !grid.hasPendingAdapterUpdates() && !grid.isLayoutRequested && a.currentFocus?.tag=="youtube-video:U0000000001"}}
        // Native input retires initial focus restoration and exercises the
        // customer's actual path to the end, including intermediate focus.
        for(index in listOf(5,9,13,17)) {
            sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
            await{ui{a.currentFocus?.tag=="youtube-video:U"+index.toString().padStart(10,'0')}}
        }
        await{ui{a.model.youtube.videos.size>=40 && !a.model.youtube.busy}}
        assertTrue(requests().any{it.optString("browseId")=="FEsubscriptions" && it.optBoolean("authenticated")})
        assertTrue(requests().any{it.optString("continuation").isNotEmpty() && it.optBoolean("authenticated")})
        assertNull(player());screenshot("youtube-subscriptions")
    }
    @Test fun signedInLibrarySwitchesBetweenHistoryWatchLaterAndLikedVideos() {
        signIn();click("YouTube library");ready(YouTubeFeed.HISTORY);await{ui{a.model.youtube.videos.firstOrNull()?.id?.startsWith("H")==true}}
        click("YouTube Watch Later");ready(YouTubeFeed.WATCH_LATER);await{ui{a.model.youtube.videos.firstOrNull()?.id?.startsWith("L")==true}}
        click("YouTube liked videos");ready(YouTubeFeed.LIKED);await{ui{a.model.youtube.videos.firstOrNull()?.id?.startsWith("K")==true}}
        assertTrue(requests().filter{it.optString("browseId") in setOf("FEhistory","FEmy_youtube","VLLL")}.all{it.optBoolean("authenticated")})
        screenshot("youtube-account-library")
    }
    @Test fun signOutRemovesPrivateFeedCardsAndRestoresUnsignedRecommendations() {
        signIn();click("YouTube subscriptions");ready(YouTubeFeed.SUBSCRIPTIONS)
        ui{a.model.youtube.signOut()};await{ui{a.model.youtube.account==null && !a.model.youtube.busy && a.model.youtube.videos.isEmpty()}}
        await{find("Sign in to YouTube")?.isShown==true}
        click("YouTube home");ready(YouTubeFeed.HOME);await{ui{a.model.youtube.videos.isNotEmpty() && a.model.youtube.videos.none{it.id.startsWith("U")}}}
        assertNull(ui{a.model.store.youTubeCredentials().read()})
    }
    @Test fun localHistoryNeverSynthesizesOrReordersTheUpstreamGuestHome()=withGuestEvidence("history-prefix") {
        ui{a.model.store.youTubeProgress(history());a.model.youtube.refresh()}
        await{ui{!a.model.youtube.busy && a.model.youtube.homeShelves.firstOrNull()?.name=="Recommended"}}
        guestEvidence("history-prefix-before-clear");assertGuestUpstreamPrefix()
        val before=requests().count{it.optString("browseId")=="default" && !it.optBoolean("authenticated")}
        ui{a.model.clearHistory()}
        await{ui{!a.model.youtube.busy && a.model.youtube.homeShelves.isNotEmpty()} &&
            requests().count{it.optString("browseId")=="default" && !it.optBoolean("authenticated")}>before}
        guestEvidence("history-prefix-after-clear");assertGuestUpstreamPrefix()
        assertFalse(requests().any{it.optString("browseId").startsWith("FEtopics_") || it.optString("videoId")==history().title.id})
        assertTrue(ui{a.model.store.youTubeHistory().isEmpty()})
        click("YouTube library");ready(YouTubeFeed.HISTORY);assertTrue(ui{a.model.youtube.videos.isEmpty()})
    }
    @Test fun guestHomeContinuationKeepsServerShelvesAndNeverResolvesFocusedVideos()=withGuestEvidence("guest-continuation") {
        await{ui{!a.model.youtube.busy}}
        val token=ui{a.model.youtube.continuation}
        if(token==null) {
            // A tall viewport can legitimately prefetch page two during the
            // initial layout. Accept that only with real terminal data + RPC.
            assertEquals(expectedGuestRows(true),guestRows())
            assertEquals(72,ui{a.model.youtube.videos.size})
            assertTrue(requests().any{it.optString("continuation")=="fixture-guest-home-2" && !it.optBoolean("authenticated")})
        } else assertEquals("fixture-guest-home-2",token)
        ui{a.model.youtube.more()}
        await{ui{!a.model.youtube.busy && a.model.youtube.videos.size>=72}}
        guestEvidence("guest-continuation-terminal")
        assertEquals(expectedGuestRows(true),guestRows());assertEquals(72,ui{a.model.youtube.videos.size})
        assertEquals(72,ui{a.model.youtube.videos.map{it.id}.distinct().size});assertNull(ui{a.model.youtube.continuation})
        val continuations=requests().filter{it.optString("continuation")=="fixture-guest-home-2"}
        assertTrue("The actual upstream continuation was never requested",continuations.isNotEmpty())
        assertTrue("Guest continuation carried account authentication",continuations.all{!it.optBoolean("authenticated")})
        assertFalse(requests().any{it.optString("browseId").startsWith("FEtopics_")})
        val state=JSONObject(runBlocking{Http().text("$base/youtube/state")})
        assertEquals("Browsing resolved media",0,state.getJSONArray("resolved").length())
        assertNull(player())
    }
    @Test fun guestNetworkFailureRetriesWithoutRequiringGoogleSignIn() {
        control("guest_fail=1");ui{a.model.youtube.refresh()}
        await{find("Retry YouTube")?.isShown==true}
        assertNull(ui{a.model.youtube.account});assertFalse(find("Sign in to YouTube")?.isShown==true)
        control("guest_fail=0");click("Retry YouTube")
        await{ui{!a.model.youtube.busy && a.model.youtube.error==null && a.model.youtube.videos.isNotEmpty()}}
        await{ui{a.currentFocus?.tag?.toString()?.startsWith("youtube-video:")==true}}
    }
}
