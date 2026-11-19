@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Native account/creator flows use generated JSON and media; never a real account or physical TV. */
class YouTubeActionsUiTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private var sheet:YouTubeVideoActions?=null
    private val video=YouTubeVideo("V0000000001","Generated video")
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    private fun state()=JSONObject(runBlocking{Http().text("$base/youtube/state")})
    @Before fun prepare() {
        control("reset=1")
        ui{a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)}
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup(){ui{sheet?.close();sheet=null;a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null)};control("reset=1")}
    private fun signIn(){control("approve=1");ui{a.model.youtube.startSignIn()};await{ui{a.model.youtube.account!=null && !a.model.youtube.busy}}}
    private fun open(){ui{sheet=YouTubeVideoActions(a,video).also{it.show()}};await{ui{row("youtube-actions-like")?.isEnabled==true && status().isBlank()}}}
    private fun row(tag:String):View?=sheet?.view?.findTag(tag)
    private fun status()=(row("youtube-actions-status") as? TextView)?.text?.toString().orEmpty()
    private fun label(tag:String)=(row(tag) as? TextView)?.text?.toString()
    private fun press(tag:String) {
        await{ui{row(tag)?.let{it.isShown && it.isEnabled}==true}}
        ui{row(tag)!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun completed(label:String,tag:String)=await{ui{this.label(tag)==label && row(tag)?.isEnabled==true}}
    private fun paths()=state().getJSONArray("requests").objects().map{it.optString("path")}
    private fun play(){ui{a.model.youtube.play(video)};playing();await{tagged("vod-controls")?.isShown==true}}

    @Test fun liveYouTubeOffersDirectAccountActionsWithoutPausingTheStream() {
        ui{a.model.open(Route.Player(Watch(video.title(),Stream("Fixture live","$base/hls/fixture/orig.m3u8"),live=true)))}
        playing();val original=player()
        await{tagged("youtube-like")?.isShown==true && tagged("vod-play-pause")?.hasFocus()==true}
        // Accessibility-idle waits can exceed the playing HUD's auto-hide timer.
        // Inspect native views here so OK targets the visible action, not a hidden
        // child whose first Center would only reveal the controls.
        assertNull(find("Picture controls"))
        ui{tagged("youtube-like")!!.requestFocusFromTouch()}
        await{ui{tagged("youtube-like")?.let{it.isShown && it.hasFocus()}==true && a.hasWindowFocus()}}
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        val pairing=device.wait(Until.hasObject(By.text("TV-FIXTURE")),10000)
        if(!pairing)screenshot("youtube-live-action-before-cleanup")
        assertTrue("Pairing missing: "+ui{JSONObject().put("account",a.model.youtube.account!=null)
            .put("epoch",a.model.youtube.client.historyAccountEpoch()).put("busy",a.model.youtube.signInBusy)
            .put("code",a.model.youtube.signInCode?.userCode).put("error",a.model.youtube.signInError)
            .put("focus",a.currentFocus?.tag?.toString()).put("windowFocus",a.hasWindowFocus())
            .put("text",descendants(a.window.decorView).filterIsInstance<TextView>().map{it.text.toString()}.filter{it.isNotBlank()}.joinToString(" | ")).toString()}+"; requests="+paths(),pairing)
        assertSame(original,player());assertTrue(ui{a.player!!.playWhenReady})
        assertFalse(paths().any{it.contains("/like/")})
    }

    @Test fun signedOutActionsOfferRealSignInWithoutChangingTheAccountOrPausingPlayback() {
        play();val player=player();open();press("youtube-actions-like")
        assertTrue(device.wait(Until.hasObject(By.text("TV-FIXTURE")),10000))
        assertNull(ui{a.model.youtube.account});assertFalse(paths().any{it.contains("/like/")})
        assertSame(player,player());assertTrue(ui{a.player!!.playWhenReady})
        click("Cancel");await{ui{sheet?.visible==true}}
        assertEquals("Like",ui{label("youtube-actions-like")});assertFalse(ui{row("youtube-actions-like")!!.isSelected})
    }
    @Test fun aDirectPlayerPublishesSavedAccountForActionsWithoutAnotherSignIn() {
        ui {
            a.model.top(Section.SEARCH)
            a.model.store.youTubeCredentials().write(YouTubeSession("fixture-saved-access","fixture-saved-refresh",Long.MAX_VALUE))
            a.model.youtubeFixture(base)
            a.model.open(Route.Player(Watch(video.title(),Stream("Generated","$base/hls/fixture/orig.m3u8"))))
        }
        playing();val original=player();open()
        assertNotNull(ui{a.model.youtube.account});assertNull(ui{row("youtube-actions-signin")})
        press("youtube-actions-like");completed("Remove like","youtube-actions-like")
        assertEquals("LIKE",state().getJSONObject("ratings").getString(video.id))
        assertFalse(paths().any{it.contains("/device/code")});assertSame(original,player())
    }
    @Test fun ratingChangesWaitForConfirmationAndBothRemoveCommandsWorkWithTheSamePlayer() {
        signIn();play();val player=player();open();control("slow_actions=1");press("youtube-actions-like")
        assertEquals("Like",ui{label("youtube-actions-like")});assertFalse(ui{row("youtube-actions-like")!!.isSelected})
        assertTrue(ui{status().contains("Updating")})
        completed("Remove like","youtube-actions-like");assertTrue(ui{row("youtube-actions-like")!!.isSelected})
        press("youtube-actions-like");completed("Like","youtube-actions-like")
        press("youtube-actions-dislike");completed("Remove dislike","youtube-actions-dislike")
        press("youtube-actions-dislike");completed("Dislike","youtube-actions-dislike")
        assertTrue(paths().any{it.endsWith("/like/removelike")});assertTrue(paths().any{it.endsWith("/like/removedislike")})
        assertEquals("INDIFFERENT",state().getJSONObject("ratings").getString(video.id))
        assertSame(player,player());assertTrue(ui{a.player!!.playWhenReady});screenshot("youtube-video-actions")
    }
    @Test fun aRejectedMutationKeepsItsOldStateAndTheSameButtonCanRetry() {
        signIn();open();control("fail_actions=1");press("youtube-actions-like")
        await{ui{status().contains("couldn't complete") && row("youtube-actions-like")!!.isEnabled}}
        assertEquals("Like",ui{label("youtube-actions-like")});assertFalse(ui{row("youtube-actions-like")!!.isSelected})
        assertFalse(state().getJSONObject("ratings").has(video.id))
        control("fail_actions=0");press("youtube-actions-like");completed("Remove like","youtube-actions-like")
        assertEquals("LIKE",state().getJSONObject("ratings").getString(video.id));assertNull(player())
    }
    @Test fun subscriptionUsesItsConfirmedStateAndCanBeUndone() {
        signIn();open();press("youtube-actions-subscribe");completed("Unsubscribe","youtube-actions-subscribe")
        assertTrue(state().getBoolean("subscribed"));assertTrue(ui{row("youtube-actions-subscribe")!!.isSelected})
        press("youtube-actions-subscribe");completed("Subscribe","youtube-actions-subscribe")
        assertFalse(state().getBoolean("subscribed"));assertFalse(ui{row("youtube-actions-subscribe")!!.isSelected})
        assertTrue(paths().any{it.endsWith("/subscription/subscribe")});assertTrue(paths().any{it.endsWith("/subscription/unsubscribe")})
    }
    @Test fun saveOffersWatchLaterAndPlaylistsAndCreatesOnlyAfterAnExplicitNameAndConfirmation() {
        signIn();open();press("youtube-actions-save")
        await{ui{row("youtube-playlist:WL")?.isEnabled==true}}
        assertFalse(ui{row("youtube-playlist:WL")!!.isSelected});press("youtube-playlist:WL")
        await{ui{row("youtube-playlist:WL")!!.isSelected && row("youtube-playlist:WL")!!.isEnabled}}
        assertEquals(video.id,state().getJSONObject("playlists").getJSONObject("WL").getJSONArray("videos").getString(0))
        press("youtube-playlist:WL");await{ui{!row("youtube-playlist:WL")!!.isSelected && row("youtube-playlist:WL")!!.isEnabled}}
        assertEquals(0,state().getJSONObject("playlists").getJSONObject("WL").getJSONArray("videos").length())
        press("youtube-actions-new-playlist");press("youtube-actions-create")
        await{ui{status()=="Enter a playlist name."}};assertEquals(2,state().getJSONObject("playlists").length())
        ui{(row("field:Playlist name") as EditText).setText("My fixture list")};press("youtube-actions-create")
        await{ui{row("youtube-playlist:PLcreated2")?.isSelected==true}}
        val created=state().getJSONObject("playlists").getJSONObject("PLcreated2")
        assertEquals("My fixture list",created.getString("name"));assertEquals(video.id,created.getJSONArray("videos").getString(0))
        assertNull(player());screenshot("youtube-save-playlists")
    }
    @Test fun menuOnACardBrowsesTheCreatorAndTabsWithoutResolvingMediaAndBackRestoresTheSearchCard() {
        ui{a.model.youtube.editQuery("fixture");a.model.youtube.submitSearch()}
        await{ui{a.model.youtube.feed==YouTubeFeed.SEARCH && a.model.youtube.query=="fixture" && !a.model.youtube.busy && a.model.youtube.videos.size==32}}
        val selected=ui{a.model.youtube.videos[5]}
        await{ui{((tagged("youtube-search-results") as? RecyclerView)?.getChildAt(0) as? YouTubeShelfView)?.let {
            it.stateKey=="SEARCH/fixture/search/videos" && it.list.adapter?.itemCount==32 && it.firstVideo()?.tag=="youtube-video:V0000000001"
        }==true}}
        ui{tagged("youtube-video:V0000000001")!!.requestFocusFromTouch()}
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000001" && a.hasWindowFocus()}}
        repeat(5){sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)}
        await{ui{a.currentFocus?.tag=="youtube-video:${selected.id}"} && tagged("youtube-video:${selected.id}")?.isShown==true}
        sendRemoteKey(KeyEvent.KEYCODE_MENU)
        assertTrue(device.wait(Until.hasObject(By.text("Video actions")),5000))
        assertTrue(device.wait(Until.hasObject(By.text("Like").enabled(true)),10000))
        assertTrue(device.wait(Until.hasObject(By.text("Sign in for account actions").focused(true)),10000))
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("Creator action must receive remote focus",device.wait(Until.hasObject(By.text("Fixture creator").focused(true)),5000))
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui{a.model.youtube.feed==YouTubeFeed.CHANNEL && !a.model.youtube.busy} && tagged("youtube-channel-name")?.isShown==true}
        assertEquals("Fixture creator",ui{(tagged("youtube-channel-name") as TextView).text.toString()})
        remote("Videos channel videos");await{ui{a.model.youtube.channelPage?.selectedTab=="videos" && !a.model.youtube.busy}}
        val list=tagged("youtube-results") as RecyclerView
        ui{list.scrollToPosition(18)};await{ui{a.model.youtube.videos.size>=40 && !a.model.youtube.busy}}
        remote("Shorts channel videos");await{ui{a.model.youtube.channelPage?.selectedTab=="shorts" && !a.model.youtube.busy}}
        assertTrue(ui{a.model.youtube.videos.all{it.isShort}})
        assertEquals(5,ui{(list.layoutManager as GridLayoutManager).spanCount})
        assertNull(player());assertEquals(0,state().getJSONArray("resolved").length())
        ui{a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.model.youtube.feed==YouTubeFeed.SEARCH && a.model.youtube.query=="fixture" && !a.model.youtube.busy}}
        await{ui{a.currentFocus?.tag=="youtube-video:${selected.id}"}}
        screenshot("youtube-channel-back")
    }
    @Test fun closingALoadingActionsSheetCancelsItsUiAndReopeningStillWorks() {
        play();val player=player();control("slow=1")
        ui{sheet=YouTubeVideoActions(a,video).also{it.show()}}
        await{ui{status().contains("Loading")}};ui{sheet!!.close()};control("slow=0")
        SystemClock.sleep(1000);assertFalse(ui{sheet!!.visible});assertSame(player,player());assertTrue(ui{a.player!!.playWhenReady})
        open();assertEquals("Like",ui{label("youtube-actions-like")});assertTrue(ui{row("youtube-actions-channel")!!.isEnabled})
    }
    @Test fun channelHeaderSubscriptionWaitsForConfirmationAndRemainsReachableWithTheRemote() {
        signIn();ui{a.model.youtube.openChannel("UC"+"1".repeat(22))}
        await{ui{!a.model.youtube.busy} && tagged("youtube-channel-subscribe")?.isEnabled==true}
        control("slow_actions=1");remote("Subscribe to channel")
        assertEquals("Updating…",ui{(tagged("youtube-channel-subscribe") as TextView).text.toString()})
        assertFalse(ui{tagged("youtube-channel-subscribe")!!.isSelected})
        await{ui{tagged("youtube-channel-subscribe")?.isSelected==true && tagged("youtube-channel-subscribe")?.isEnabled==true}}
        assertTrue(state().getBoolean("subscribed"));remote("Unsubscribe from channel")
        await{ui{tagged("youtube-channel-subscribe")?.isSelected==false && tagged("youtube-channel-subscribe")?.isEnabled==true}}
        assertFalse(state().getBoolean("subscribed"));assertNull(player())
    }
    @Test fun signingOutCancelsAnInFlightActionAndCannotDisplayItsResultForTheGuest() {
        signIn();open();control("slow_actions=1");press("youtube-actions-like")
        ui{a.model.youtube.signOut()}
        await{ui{a.model.youtube.account==null && status().contains("account changed",ignoreCase=true)}}
        assertEquals("Like",ui{label("youtube-actions-like")});assertFalse(ui{row("youtube-actions-like")!!.isSelected})
        assertTrue(ui{row("youtube-actions-signin")?.isShown==true});assertNull(player())
        SystemClock.sleep(1100)
        assertFalse("An old HTTP result must not select a guest's Like button",ui{row("youtube-actions-like")!!.isSelected})
    }
    @Test fun confirmedLikeAndWatchLaterChangesAreVisibleAfterBackEvenWhenBothLibrariesWereCachedEmpty() {
        signIn();control("stateful_account=1");click("YouTube library")
        click("YouTube Watch Later");await{ui{a.model.youtube.feed==YouTubeFeed.WATCH_LATER && !a.model.youtube.busy && a.model.youtube.videos.isEmpty()}}
        click("YouTube liked videos");await{ui{a.model.youtube.feed==YouTubeFeed.LIKED && !a.model.youtube.busy && a.model.youtube.videos.isEmpty()}}
        play();open();press("youtube-actions-like");completed("Remove like","youtube-actions-like")
        press("youtube-actions-save");await{ui{row("youtube-playlist:WL")?.isEnabled==true}}
        press("youtube-playlist:WL");await{ui{row("youtube-playlist:WL")?.isSelected==true && row("youtube-playlist:WL")?.isEnabled==true}}
        ui{sheet!!.close();a.playback!!.dismissControls();a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.model.route==Route.Browse(Section.YOUTUBE) && a.model.youtube.feed==YouTubeFeed.LIKED && !a.model.youtube.busy && a.model.youtube.videos.map{it.id}==listOf(video.id)}}
        await{tagged("youtube-video:${video.id}")?.isShown==true}
        click("YouTube Watch Later")
        await{ui{a.model.youtube.feed==YouTubeFeed.WATCH_LATER && !a.model.youtube.busy && a.model.youtube.videos.map{it.id}==listOf(video.id)}}
        await{tagged("youtube-video:${video.id}")?.isShown==true};assertNull(player())
        assertEquals("LIKE",state().getJSONObject("ratings").getString(video.id));screenshot("youtube-library-acknowledged-actions")
    }
}
