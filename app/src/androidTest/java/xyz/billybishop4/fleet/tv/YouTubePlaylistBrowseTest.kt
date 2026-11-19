@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package xyz.billybishop4.fleet.tv

import android.widget.EditText
import android.view.KeyEvent
import android.view.Window
import android.os.SystemClock
import androidx.recyclerview.widget.RecyclerView
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Generated account collections; never opens a real Google account or physical TV. */
class YouTubePlaylistBrowseTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private var actions:YouTubeVideoActions?=null
    private val centerDispatch=mutableListOf<JSONObject>()
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
    private fun collectionReady(ids:List<String>)=ui {
        val youtube=a.model.youtube
        val list=a.window.decorView.findTag("youtube-results") as? RecyclerView?:return@ui false
        val committed=(list.adapter as? NativeAdapter<*>)?.currentList.orEmpty().filterIsInstance<YouTubePlaylist>()
        youtube.feed==YouTubeFeed.PLAYLISTS && !youtube.busy && youtube.error==null && a.hasWindowFocus() &&
            youtube.playlistCards.map{it.id}==ids && committed.map{it.id}==ids &&
            !list.hasPendingAdapterUpdates() && !list.isLayoutRequested && !list.isComputingLayout &&
            ids.withIndex().all{(index,id)->list.findViewHolderForAdapterPosition(index)?.itemView?.let{
                it.isAttachedToWindow && it.isShown && it.width>0 && it.height>0 && it.tag=="youtube-playlist-card:$id"
            }==true}
    }
    private fun focusState()=JSONObject().put("uptimeMs",SystemClock.uptimeMillis())
        .put("route",a.model.route.javaClass.simpleName).put("feed",a.model.youtube.feed.name)
        .put("busy",a.model.youtube.busy).put("selectedPlaylistId",a.model.youtube.selectedPlaylist?.id)
        .put("selectedPlaylistName",a.model.youtube.selectedPlaylist?.name)
        .put("focus",a.currentFocus?.tag?.toString()).put("focusDescription",a.currentFocus?.contentDescription?.toString())
        .put("windowFocus",a.hasWindowFocus())
    private fun playlistEvidence(phase:String) {
        val fixture=state()
        val model=ui {
            val youtube=a.model.youtube
            val list=a.window.decorView.findTag("youtube-results") as? RecyclerView
            val committed=(list?.adapter as? NativeAdapter<*>)?.currentList.orEmpty()
            focusState().put("phase",phase).put("error",youtube.error).put("accountPresent",youtube.account!=null)
                .put("playlistIds",JSONArray(youtube.playlistCards.map{it.id})).put("videoIds",JSONArray(youtube.videos.map{it.id}))
                .put("committedPlaylistIds",JSONArray(committed.filterIsInstance<YouTubePlaylist>().map{it.id}))
                .put("adapterCount",list?.adapter?.itemCount).put("pendingAdapterUpdates",list?.hasPendingAdapterUpdates())
                .put("layoutRequested",list?.isLayoutRequested).put("computingLayout",list?.isComputingLayout)
                .put("attached",JSONArray((0 until (list?.childCount?:0)).map{index->
                    val card=list!!.getChildAt(index)
                    JSONObject().put("position",list.getChildAdapterPosition(card)).put("tag",card.tag?.toString())
                        .put("description",card.contentDescription?.toString()).put("focused",card.hasFocus()).put("shown",card.isShown)
                })).put("playerPresent",a.activePlayer!=null).put("centerDispatch",JSONArray(centerDispatch.toList()))
        }
        File(ui{a.getExternalFilesDir(null)!!},"youtube-playlist-created-$phase.json")
            .writeText(JSONObject().put("model",model).put("fixture",fixture).toString(2))
    }
    private fun withCreatedPlaylistEvidence(action:()->Unit) {
        val original=ui{a.window.callback!!}
        ui {
            centerDispatch.clear()
            a.window.callback=object:Window.Callback by original {
                override fun dispatchKeyEvent(event:KeyEvent):Boolean {
                    if(event.keyCode!=KeyEvent.KEYCODE_DPAD_CENTER)return original.dispatchKeyEvent(event)
                    val record=JSONObject().put("action",event.action).put("downTime",event.downTime)
                        .put("eventTime",event.eventTime).put("repeat",event.repeatCount).put("before",focusState())
                    centerDispatch.add(record)
                    val handled=original.dispatchKeyEvent(event)
                    record.put("handled",handled).put("after",focusState())
                    return handled
                }
            }
        }
        try { playlistEvidence("before");action();playlistEvidence("passed") }
        catch(error:Throwable){runCatching{playlistEvidence("failed-before-cleanup")};throw error}
        finally{ui{a.window.callback=original}}
    }
    @Test fun signedOutPlaylistLibraryOffersSignInWithoutReadingPrivateCollections() {
        collections();await{find("Sign in to YouTube")?.isShown==true}
        assertTrue(ui{a.model.youtube.playlistCards.isEmpty()});assertNull(ui{a.model.youtube.account});assertNull(player())
        assertFalse(state().getJSONArray("requests").objects().any{it.optString("browseId")=="FEplaylist_aggregation"})
        await{ui{a.currentFocus?.contentDescription=="Sign in to YouTube"}}
    }
    @Test fun aNewlyCreatedPlaylistIsReachableAndBackRestoresItsCollectionCard()=withCreatedPlaylistEvidence {
        signIn()
        ui{actions=YouTubeVideoActions(a,YouTubeVideo("V0000000001","Generated video")).also{it.show()}}
        pressAction("youtube-actions-save");pressAction("youtube-actions-new-playlist")
        ui{(actions!!.view.findTag("field:Playlist name") as EditText).setText("My library list")}
        assertTrue(device.wait(Until.hasObject(By.text("Create private playlist").enabled(true)),5000));pressAction("youtube-actions-create")
        await{ui{actions!!.view.findTag("youtube-playlist:PLcreated2")?.isSelected==true}}
        ui{actions!!.close()};collections()
        val ids=listOf("WL","PLfixture","PLcreated2")
        // The initial collection restore owns WL until its committed layout is
        // focused. Follow the customer's remote path so a pending restore cannot
        // overwrite a synthetic requestFocus immediately before CENTER.
        await{collectionReady(ids) && ui{a.currentFocus?.tag=="youtube-playlist-card:WL"}}
        playlistEvidence("collection-ready");assertEquals(ids,ui{a.model.youtube.playlistCards.map{it.id}})
        for(id in ids.drop(1)) {
            sendRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            await{collectionReady(ids) && ui{a.currentFocus?.tag=="youtube-playlist-card:$id"}}
            playlistEvidence("focused-$id")
        }
        sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        await{ui{a.model.youtube.feed==YouTubeFeed.PLAYLIST && a.model.youtube.selectedPlaylist?.id=="PLcreated2" && a.model.youtube.selectedPlaylist?.name=="My library list" && !a.model.youtube.busy}}
        await{tagged("youtube-video:V0000000001")?.isShown==true};assertNull(player())
        assertEquals(listOf("V0000000001"),ui{a.model.youtube.videos.map{it.id}})
        await{ui{a.currentFocus?.tag=="youtube-video:V0000000001"}}
        playlistEvidence("video-ready");sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER);playing()
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
