@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

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
    @Test fun freshHomeHasRealGuestCardsWithoutCreatingAnAccountOrPlayingOnFocus() {
        assertNull(ui{a.model.youtube.account});assertNull(ui{a.model.store.youTubeCredentials().read()})
        assertEquals(listOf("Music","Gaming","News"),ui{a.model.youtube.homeShelves.map{it.name}})
        assertTrue(ui{a.model.youtube.videos.all{it.id.startsWith("G")}})
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
        ui{(tagged("youtube-results") as RecyclerView).scrollToPosition(18)}
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
        click("YouTube home");ready(YouTubeFeed.HOME);await{ui{a.model.youtube.videos.isNotEmpty() && a.model.youtube.videos.all{it.id.startsWith("G")}}}
        assertNull(ui{a.model.store.youTubeCredentials().read()})
    }
    @Test fun clearingLocalHistoryAlsoClearsItsRecommendationSeedsAndCachedCards() {
        ui{a.model.store.youTubeProgress(history());a.model.youtube.refresh()}
        await{ui{!a.model.youtube.busy && a.model.youtube.homeShelves.firstOrNull()?.name=="Because you watched A watched fixture"}}
        ui{a.model.clearHistory()}
        await{ui{!a.model.youtube.busy && a.model.youtube.homeShelves.firstOrNull()?.name=="Music"}}
        assertTrue(ui{a.model.store.youTubeHistory().isEmpty()})
        click("YouTube library");ready(YouTubeFeed.HISTORY);assertTrue(ui{a.model.youtube.videos.isEmpty()})
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
