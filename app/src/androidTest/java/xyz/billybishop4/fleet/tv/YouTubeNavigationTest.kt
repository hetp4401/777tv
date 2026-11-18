@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Generated local protocol/media only. Never run this fixture suite on a user's physical TV. */
class YouTubeNavigationTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    private fun audit()=JSONObject(runBlocking{Http().text("$base/youtube/state")})
    @Before fun prepare() {
        control("reset=1")
        ui {
            a.model.top(Section.SEARCH);a.model.server(base);a.model.updatePreferences(Preferences())
            a.model.store.youTubeCredentials().write(null);a.model.clearHistory();a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)
        }
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
        await{ui{a.model.youtube.videos.isNotEmpty() && a.model.youtube.account==null}}
    }
    @After fun cleanup() {
        ui { a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null);a.model.updatePreferences(Preferences()) }
        control("reset=1")
    }
    private fun videos()=ui{a.model.youtube.videos}
    private fun search(query:String) {
        if(ui{a.model.youtube.feed!=YouTubeFeed.SEARCH})click("YouTube search")
        field("Search YouTube",query);click("Search YouTube videos")
        await{ui{!a.model.youtube.busy && a.model.youtube.feed==YouTubeFeed.SEARCH}}
        await {
            val first=videos().firstOrNull()
            if(first!=null)tagged("youtube-video:${first.id}")?.let{it.isShown && it.width>0 && it.height>0}==true
            else if(ui{a.model.youtube.error!=null})find("Retry YouTube")!=null else find("No videos found")!=null
        }
    }
    private fun returnFromPlayer() {
        ui{a.playback?.dismissControls();a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.model.route==Route.Browse(Section.YOUTUBE)} && tagged("youtube-page")?.isShown==true}
    }
    private fun signIn() {
        click("YouTube account")
        assertTrue(device.wait(Until.hasObject(By.text("TV-FIXTURE")),10000)==true)
        control("approve=1")
        await(20000){ui{a.model.youtube.account!=null} && device.findObject(By.text("TV-FIXTURE"))==null && ui{a.hasWindowFocus()}}
        await{ui{!a.model.youtube.busy}}
    }
    @Test fun youtubeIsBesideOnDemandAndRecommendationsWorkWithoutAnAccount() {
        val bounds=ui{listOf("Live TV","On-Demand","YouTube").map{a.window.decorView.findTag("navigation:$it")!!.let{v->android.graphics.Rect().apply{v.getGlobalVisibleRect(this)}}}}
        assertTrue(bounds[0].right<=bounds[1].left);assertTrue(bounds[1].right<=bounds[2].left)
        assertEquals("On-Demand",ui{Preferences().startSection});assertTrue(videos().isNotEmpty())
        assertNull(ui{a.model.youtube.account});assertEquals("Music",ui{a.model.youtube.homeShelves.first().name})
        assertNotNull(tagged("field:Search YouTube"));assertNull(player())
        assertFalse(ui{descendants(a.window.decorView).any{it is android.webkit.WebView}})
        assertTrue(audit().getJSONArray("resolved").length()==0)
        screenshot("youtube-signed-out-home")
    }
    @Test fun searchUsesNativeInputAndFocusMovementNeverResolvesAStream() {
        search("fixture");assertEquals(32,videos().size)
        val first=videos().first();await{tagged("youtube-video:${first.id}")?.isShown==true}
        ui{tagged("youtube-video:${first.id}")!!.requestFocus()}
        assertTrue(ui{descendants(tagged("youtube-video:${first.id}")!!).filterIsInstance<android.widget.ImageView>()
            .first().drawableState.contains(android.R.attr.state_focused)})
        assertNull(ui{tagged("youtube-video:${first.id}")!!.background})
        repeat(7){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        assertEquals(0,audit().getJSONArray("resolved").length());assertNull(player())
        assertTrue(ui{a.currentFocus?.tag.toString().startsWith("youtube-video:")})
        val outer=tagged("youtube-search-results") as RecyclerView
        val list=ui{(outer.getChildAt(0) as YouTubeShelfView).list}
        assertEquals(1,ui{outer.adapter!!.itemCount});assertTrue(ui{list.childCount<=8});assertEquals(32,ui{list.adapter!!.itemCount})
        screenshot("youtube-search")
    }
    @Test fun oneOkPlaysWithExoAndBackRestoresQueryAndSelectedVideo() {
        search("fixture");val selected=videos()[2]
        await{tagged("youtube-video:${selected.id}")?.isShown==true}
        ui{tagged("youtube-video:${selected.id}")!!.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        playing();assertEquals("ExoPlayer",JSONObject(ui{a.playbackInfo}).getString("engine"))
        assertEquals(1,audit().getJSONArray("resolved").length())
        val before=ui{a.player!!.currentPosition};ui{a.player!!.seekTo(before+10000)}
        await{ui{a.player?.currentPosition?:0L}>before+9000}
        returnFromPlayer()
        await{ui{a.currentFocus?.tag=="youtube-video:${selected.id}"}}
        assertEquals("fixture",ui{(tagged("field:Search YouTube") as EditText).text.toString()})
        assertNull(player());assertTrue(ui{a.model.progress.none{it.title.type=="youtube"}})
    }
    @Test fun shortsUsePortraitCardsAndOnePlayerAcrossUpDownNavigation() {
        click("YouTube Shorts");await{ui{!a.model.youtube.busy && a.model.youtube.feed==YouTubeFeed.SHORTS && a.model.youtube.videos.size>=7}}
        val rows=videos();val first=rows.first();val next=rows[1]
        await{tagged("youtube-video:${first.id}")?.isShown==true}
        assertTrue(rows.all{it.isShort})
        ui{tagged("youtube-video:${first.id}")!!.performClick()};playing()
        assertTrue(ui{a.player!!.videoSize.height>a.player!!.videoSize.width})
        ui{a.playback!!.dismissControls()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        await(30000){ui{(a.model.route as? Route.Player)?.watch?.title?.id==next.id && a.player?.isPlaying==true}}
        val resolved=audit().getJSONArray("resolved").let{j->(0 until j.length()).map{j.getString(it)}}
        assertTrue(resolved.contains(first.id) && resolved.contains(next.id))
        assertTrue("Only the active and one upcoming clip may resolve",resolved.all{it in rows.take(3).map{v->v.id}})
        returnFromPlayer();assertTrue(ui{a.model.route==Route.Browse(Section.YOUTUBE)})
        screenshot("youtube-shorts")
    }
    @Test fun paginationCannotCancelAnExplicitVideoSelection() {
        search("fixture");val selected=videos().first();control("slow=1")
        ui { a.model.youtube.play(selected);a.model.youtube.more() }
        playing()
        assertEquals(selected.id,ui{(a.model.route as Route.Player).watch.title.id})
        assertEquals(1,audit().getJSONArray("resolved").length());assertTrue(ui{a.player!!.isPlaying})
        returnFromPlayer()
    }
    @Test fun separateVideoAndAudioPlayTogetherAndSeekingKeepsBothTracks() {
        control("split_media=1")
        ui{a.model.youtube.play(YouTubeVideo("B0000000001","Generated split-track video"))}
        playing()
        await{ui{a.player?.currentTracks?.groups?.any{it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected}==true}}
        await{ui{(a.player?.videoDecoderCounters?.renderedOutputBufferCount?:0)>12}}
        ui{a.player!!.seekTo(8000)}
        await{ui{(a.player?.currentPosition?:0)>7500 && a.player?.isPlaying==true}}
        assertTrue(ui{(a.model.route as Route.Player).watch.audioUrl.isNotBlank()})
        assertTrue(ui{a.player!!.currentTracks.groups.any{it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected}})
        returnFromPlayer()
    }
    @Test fun memoryPressureReleasesArtworkWithoutLosingThePlayerOrReturnPosition() {
        search("fixture");val selected=videos()[1];val count=videos().size
        ui{tagged("youtube-video:${selected.id}")!!.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);playing()
        val player=player()
        ui{a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)}
        assertEquals(count,videos().size);assertSame(player,player());assertTrue(ui{a.player!!.isPlaying})
        returnFromPlayer();await{ui{a.currentFocus?.tag=="youtube-video:${selected.id}"}}
    }
    @Test fun pairingPersistsEncryptedAuthenticationAndLoadsRecommendations() {
        signIn();assertTrue(videos().isNotEmpty());assertNotNull(find("Account"))
        val encrypted=ui{a.getSharedPreferences("fleet-tv",0).getString("youtube-session-encrypted",null)}
        assertNotNull(encrypted);assertFalse(encrypted!!.contains("fixture-youtube-access"));assertFalse(encrypted.contains("fixture-youtube-refresh"))
        assertTrue(audit().getJSONArray("requests").objects().any{it.optString("path").endsWith("/browse") && it.optBoolean("authenticated")})
        rule.scenario.recreate();await{ui{a.hasWindowFocus()}}
        ui{a.model.top(Section.YOUTUBE)};await{ui{!a.model.youtube.busy && a.model.youtube.account!=null}}
        assertTrue(videos().isNotEmpty());screenshot("youtube-signed-in-home")
    }
    @Test fun cancelPairingStopsPollingAndNeverCreatesAnAccount() {
        click("YouTube account");assertTrue(device.wait(Until.hasObject(By.text("TV-FIXTURE")),10000)==true)
        click("Cancel");await{ui{a.hasWindowFocus() && a.model.youtube.signInCode==null}}
        val polls=audit().getJSONArray("requests").objects().count{it.optString("path").endsWith("/token")}
        control("approve=1");android.os.SystemClock.sleep(5500)
        assertNull(ui{a.model.youtube.account});assertNull(ui{a.model.store.youTubeCredentials().read()})
        assertEquals(polls,audit().getJSONArray("requests").objects().count{it.optString("path").endsWith("/token")})
    }
    @Test fun declinedPairingOffersANewCodeAndCanBeCanceled() {
        click("YouTube account");assertTrue(device.wait(Until.hasObject(By.text("TV-FIXTURE")),10000)==true)
        control("deny=1");assertTrue(device.wait(Until.hasObject(By.text("New code")),15000)==true)
        assertNull(ui{a.model.youtube.account});click("Cancel");await{ui{a.hasWindowFocus()}}
    }
    @Test fun signOutKeepsOnDemandDataAndClearsPersonalRecommendations() {
        signIn();val title=Title("fixture-saved-title","Saved fixture");ui{a.model.toggle(title)}
        click("YouTube account");click("Sign out");click("Sign out")
        await{ui{a.model.youtube.account==null && !a.model.youtube.busy && a.model.youtube.videos.isNotEmpty() && a.model.youtube.videos.all{it.id.startsWith("G")}}}
        assertTrue(ui{a.model.saved.any{it.id==title.id}});assertNull(ui{a.model.store.youTubeCredentials().read()})
        ui{a.model.toggle(title)}
    }
    @Test fun olderSearchResponsesCannotReplaceTheLatestQuery() {
        click("YouTube search");control("slow=1");field("Search YouTube","slow");click("Search YouTube videos")
        await{audit().getJSONArray("requests").objects().any{it.optString("query")=="slow"}}
        field("Search YouTube","new query");click("Search YouTube videos")
        await{ui{!a.model.youtube.busy && a.model.youtube.query=="new query" && a.model.youtube.videos.isNotEmpty()}}
        android.os.SystemClock.sleep(1200);assertEquals("new query",ui{a.model.youtube.query});assertNull(ui{a.model.youtube.error})
    }
    @Test fun emptyAndOfflineResultsHaveRetryAndPreserveTheInput() {
        search("nothing");assertTrue(videos().isEmpty());await{find("No videos found")!=null}
        control("fail=1");search("fixture");await{find("Retry YouTube")!=null};assertEquals("fixture",ui{a.model.youtube.query})
        control("fail=0");click("Retry YouTube");await{ui{!a.model.youtube.busy && a.model.youtube.videos.isNotEmpty()}}
    }
    @Test fun failedPlaybackDoesNotLoseSearchAndExplicitRetryRecovers() {
        search("fixture");control("fail_playback=1")
        val first=videos().first();ui{tagged("youtube-video:${first.id}")!!.performClick()}
        await{ui{!a.model.youtube.resolving && a.model.youtube.error!=null}}
        assertNull(player());assertEquals("fixture",ui{a.model.youtube.query})
        control("fail_playback=0");ui{tagged("youtube-video:${first.id}")!!.performClick()};playing();returnFromPlayer()
    }
    @Test fun aRejectedYouTubeMediaUrlUsesOneFallbackAndKeepsTheReturnSelection() {
        search("fixture");val selected=videos()[2];control("rejected_media=1")
        await{tagged("youtube-video:${selected.id}")?.isShown==true}
        ui{tagged("youtube-video:${selected.id}")!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        playing()
        val resolved=audit().getJSONArray("resolved").let{items->(0 until items.length()).map{items.getString(it)}}
        assertEquals(listOf(selected.id,selected.id),resolved)
        assertEquals(listOf(selected.id),audit().getJSONArray("fallbackResolved").let{items->(0 until items.length()).map{items.getString(it)}})
        assertEquals("ExoPlayer",JSONObject(ui{a.playbackInfo}).getString("engine"))
        assertNull(find("Retry"));assertTrue(ui{a.player!!.playWhenReady})
        rule.scenario.moveToState(Lifecycle.State.CREATED);await{ui{a.activePlayer==null}}
        rule.scenario.moveToState(Lifecycle.State.RESUMED);playing()
        assertEquals("Returning from background must retain the recovered source",2,audit().getJSONArray("resolved").length())
        returnFromPlayer();await{ui{a.currentFocus?.tag=="youtube-video:${selected.id}"}}
        assertEquals("fixture",ui{a.model.youtube.query});assertNull(player())
    }
    @Test fun aRejectedFallbackStopsAfterOneAttemptAndAnExplicitRetryCanRecover() {
        search("fixture");val selected=videos().first();control("rejected_media=1&rejected_fallback=1")
        ui{tagged("youtube-video:${selected.id}")!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{find("Retry")?.isShown==true && ui{a.model.route is Route.Player}}
        android.os.SystemClock.sleep(600)
        assertEquals(2,audit().getJSONArray("resolved").length())
        assertEquals(1,audit().getJSONArray("fallbackResolved").length())
        assertFalse(ui{a.player?.isPlaying==true});assertEquals("fixture",ui{a.model.youtube.query})
        control("rejected_media=0&rejected_fallback=0");click("Retry");playing()
        assertEquals(3,audit().getJSONArray("resolved").length())
        returnFromPlayer();await{ui{a.currentFocus?.tag=="youtube-video:${selected.id}"}}
    }
    @Test fun switchingSectionsKeepsSeparateSearchesAndReleasesPlayerAndViews() {
        search("youtube query");nav("On-Demand")
        field("Search movies and shows","demand query");await{ui{!a.model.busy}}
        repeat(6){nav("YouTube");await{tagged("youtube-page")?.isShown==true};nav("On-Demand")}
        assertEquals("demand query",ui{a.model.searchText});nav("YouTube")
        assertEquals("youtube query",ui{a.model.youtube.query});assertNull(player())
        assertTrue(ui{descendants(a.window.decorView).filterIsInstance<YouTubeCard>().count()<=16})
    }
}
