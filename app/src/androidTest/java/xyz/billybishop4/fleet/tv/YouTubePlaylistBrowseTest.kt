@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.widget.EditText
import android.view.KeyEvent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Generated account collections; never opens a real Google account or physical TV. */
class YouTubePlaylistBrowseTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private var actions:YouTubeVideoActions?=null
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    private fun state()=JSONObject(runBlocking{Http().text("$base/youtube/state")})
    @Before fun prepare() {
        control("reset=1")
        ui{a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null);a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)}
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup(){ui{actions?.close();a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null)};control("reset=1")}
    private fun signIn(){control("approve=1");ui{a.model.youtube.startSignIn()};await{ui{a.model.youtube.account!=null && !a.model.youtube.busy}}}
    private fun pressAction(tag:String) {
        await{ui{actions?.view?.findTag(tag)?.let{it.isShown && it.isEnabled}==true}}
        ui{actions!!.view.findTag(tag)!!.requestFocusFromTouch()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun collections(){click("YouTube library");click("YouTube playlists");await{ui{a.model.youtube.feed==YouTubeFeed.PLAYLISTS && !a.model.youtube.busy}}}
    @Test fun signedOutPlaylistLibraryOffersSignInWithoutReadingPrivateCollections() {
        collections();await{find("Sign in to YouTube")?.isShown==true}
        assertTrue(ui{a.model.youtube.playlistCards.isEmpty()});assertNull(ui{a.model.youtube.account});assertNull(player())
        assertFalse(state().getJSONArray("requests").objects().any{it.optString("browseId")=="FEplaylist_aggregation"})
        await{ui{a.currentFocus?.contentDescription=="Sign in to YouTube"}}
    }
    @Test fun aNewlyCreatedPlaylistIsReachableAndBackRestoresItsCollectionCard() {
        signIn()
        ui{actions=YouTubeVideoActions(a,YouTubeVideo("V0000000001","Generated video")).also{it.show()}}
        pressAction("youtube-actions-save");pressAction("youtube-actions-new-playlist")
        ui{(actions!!.view.findTag("field:Playlist name") as EditText).setText("My library list")}
        assertTrue(device.wait(Until.hasObject(By.text("Create private playlist").enabled(true)),5000));pressAction("youtube-actions-create")
        await{ui{actions!!.view.findTag("youtube-playlist:PLcreated2")?.isSelected==true}}
        ui{actions!!.close()};collections()
        await{tagged("youtube-playlist-card:PLcreated2")?.isShown==true}
        assertEquals(listOf("WL","PLfixture","PLcreated2"),ui{a.model.youtube.playlistCards.map{it.id}})
        remote("Open playlist: My library list")
        await{ui{a.model.youtube.feed==YouTubeFeed.PLAYLIST && a.model.youtube.selectedPlaylist?.name=="My library list" && !a.model.youtube.busy}}
        await{tagged("youtube-video:V0000000001")?.isShown==true};assertNull(player())
        assertEquals(listOf("V0000000001"),ui{a.model.youtube.videos.map{it.id}})
        ui{tagged("youtube-video:V0000000001")!!.requestFocus()};sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);playing()
        ui{a.playback!!.dismissControls();a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.player==null && a.model.youtube.feed==YouTubeFeed.PLAYLIST} && tagged("youtube-video:V0000000001")?.isShown==true}
        ui{a.onBackPressedDispatcher.onBackPressed()}
        await{ui{a.model.youtube.feed==YouTubeFeed.PLAYLISTS && a.currentFocus?.tag=="youtube-playlist-card:PLcreated2"}}
        assertTrue(state().getJSONArray("requests").objects().any{it.optString("browseId")=="VLPLcreated2" && it.optBoolean("authenticated")})
        screenshot("youtube-library-created-playlist")
    }
    @Test fun signOutRemovesPlaylistCoversAndTheirPrivateVideoPages() {
        signIn();collections();await{tagged("youtube-playlist-card:PLfixture")?.isShown==true}
        remote("Open playlist: Fixture playlist");await{ui{a.model.youtube.feed==YouTubeFeed.PLAYLIST && !a.model.youtube.busy}}
        await{find("No videos in this playlist")?.isShown==true}
        ui{a.model.youtube.signOut()};await{ui{a.model.youtube.account==null && a.model.youtube.playlistCards.isEmpty() && a.model.youtube.videos.isEmpty()}}
        await{find("Sign in to YouTube")?.isShown==true};assertNull(tagged("youtube-playlist-card:PLfixture")?.takeIf{it.isShown});assertNull(player())
    }
}
