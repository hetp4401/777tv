package xyz.billybishop4.fleet.tv

import android.os.SystemClock
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

/** Controller lifecycle/account checks use generated accounts and no physical TV. */
class YouTubePlayerActionsUiTest:NativeTestBase() {
    private val base="http://10.0.2.2:8878"
    private val video=YouTubeVideo("V0000000001","Generated video")
    private val snapshot=YouTubeWatchDetails(YouTubePage(emptyList()),null,
        channel=YouTubeChannel("UC"+"1".repeat(22),"Generated channel",subscribed=false),rating=YouTubeRating.NONE)
    private var actions:YouTubePlayerActions?=null
    private var shown=YouTubePlayerActionState()
    private var publications=0
    private fun control(q:String)=runBlocking{Http().text("$base/youtube/control?$q")}
    private fun state()=JSONObject(runBlocking{Http().text("$base/youtube/state")})
    private fun paths()=state().getJSONArray("requests").objects().map{it.optString("path")}
    @Before fun prepare() {
        control("reset=1")
        ui{a.model.top(Section.SEARCH);a.model.server(base);a.model.store.youTubeCredentials().write(null)
            a.model.youtubeFixture(base);a.model.top(Section.YOUTUBE)}
        await{tagged("youtube-page")?.isShown==true && ui{!a.model.youtube.busy}}
    }
    @After fun cleanup() {
        ui{actions?.close();actions=null;a.model.top(Section.SEARCH);a.model.store.youTubeCredentials().write(null)}
        control("reset=1")
    }
    private fun signIn(){control("approve=1");ui{a.model.youtube.startSignIn()};await{ui{a.model.youtube.account!=null && !a.model.youtube.busy}}}
    private fun create()=ui{actions=YouTubePlayerActions(a,video){shown=it;publications++}.also{it.metadata(snapshot)}}

    @Test fun initialMetadataDoesNotRequestInteractionOrAllocateAnActionsWindow() {
        val before=state().getJSONArray("requests")
        var after:JSONArray?=null
        try {
            create()
            assertEquals("Generated channel",ui{shown.channel?.name});assertFalse(ui{shown.signedIn})
            after=state().getJSONArray("requests")
            val previous=before.objects();val current=after!!.objects()
            assertTrue("Metadata removed existing request records",current.size>=previous.size)
            assertEquals("Existing request records changed",previous.map{it.toString()},current.take(previous.size).map{it.toString()})
            val added=current.drop(previous.size)
            assertTrue("Metadata introduced unexpected network requests",added.size<=1)
            // The already-visible Home may finish its one legitimate prefetch.
            // No other browse, account, watch/player or action request is allowed.
            for(request in added) {
                assertEquals("/youtube/youtubei/v1/browse",request.getString("path"))
                assertFalse("Home continuation became authenticated",request.getBoolean("authenticated"))
                assertEquals("TVHTML5",request.getString("client"))
                assertEquals("fixture-guest-home-2",request.getString("continuation"))
                assertEquals("",request.getString("browseId"));assertEquals("",request.getString("videoId"));assertEquals("",request.getString("query"))
                val body=request.getJSONObject("body")
                assertEquals(setOf("context","continuation"),body.keys().asSequence().toSet())
                assertEquals("fixture-guest-home-2",body.getString("continuation"))
            }
            assertTrue("Home continuation was requested more than once",current.count{
                it.optString("path")=="/youtube/youtubei/v1/browse" && it.optString("continuation")=="fixture-guest-home-2"
            }<=1)
            assertFalse(device.hasObject(By.text("Video actions")));assertFalse(device.hasObject(By.text("Save video")))
            assertNull(player())
        } finally {
            val sampled=after?:runCatching{state().getJSONArray("requests")}.getOrNull()
            File(ui{a.getExternalFilesDir(null)!!},"youtube-player-initial-metadata-requests.json")
                .writeText(JSONObject().put("before",before).put("after",sampled).toString(2))
        }
    }
    @Test fun aSignedOutDirectActionOffersPairingWithoutSendingTheMutation() {
        create();ui{actions!!.rate(YouTubeRating.LIKE)}
        assertTrue("Direct guest action never opened its sign-in sheet; requests="+paths(),
            device.wait(Until.hasObject(By.text("TV-FIXTURE")),10000))
        assertNull(ui{a.model.youtube.account});assertFalse(paths().any{it.contains("/like/")})
        click("Cancel");await{ui{a.hasWindowFocus()}}
        assertFalse(ui{shown.busy});assertFalse(device.hasObject(By.text("Video actions")))
    }
    @Test fun acknowledgedRatingWinsOverOldWatchMetadataAndRejectedChangesCanRetry() {
        signIn();create();control("fail_actions=1");ui{actions!!.rate(YouTubeRating.LIKE)}
        assertTrue(ui{shown.busy});assertEquals(YouTubeRating.NONE,ui{shown.rating})
        await{ui{!shown.busy && shown.error!=null}}
        assertEquals(YouTubeRating.NONE,ui{shown.rating});assertFalse(state().getJSONObject("ratings").has(video.id))
        control("fail_actions=0");ui{actions!!.rate(YouTubeRating.LIKE)}
        await{ui{!shown.busy && shown.rating==YouTubeRating.LIKE && shown.error==null}}
        ui{actions!!.metadata(snapshot)}
        assertEquals("An old read-only snapshot cannot undo an acknowledged action",YouTubeRating.LIKE,ui{shown.rating})
        ui{actions!!.rate(YouTubeRating.LIKE)}
        await{ui{!shown.busy && shown.rating==YouTubeRating.NONE}}
        assertEquals("INDIFFERENT",state().getJSONObject("ratings").getString(video.id))
        assertFalse(device.hasObject(By.text("Video actions")))
    }
    @Test fun aRestoredAccountCanUseADirectActionWithoutOpeningPairingOrMore() {
        ui {
            a.model.top(Section.SEARCH)
            a.model.store.youTubeCredentials().write(YouTubeSession("fixture-saved-access","fixture-saved-refresh",Long.MAX_VALUE))
            a.model.youtubeFixture(base)
        }
        create();ui{actions!!.rate(YouTubeRating.LIKE)}
        await{ui{shown.signedIn && !shown.busy && shown.rating==YouTubeRating.LIKE}}
        assertFalse(paths().any{it.endsWith("/device/code")})
        assertFalse(device.hasObject(By.text("Video actions")));assertFalse(device.hasObject(By.text("TV-FIXTURE")))
    }
    @Test fun aLateNoticeForTheSameRestoredAccountCannotCancelAPendingAcknowledgment() {
        ui {
            a.model.top(Section.SEARCH)
            a.model.store.youTubeCredentials().write(YouTubeSession("fixture-saved-access","fixture-saved-refresh",Long.MAX_VALUE))
            a.model.youtubeFixture(base)
        }
        create()
        // Simulate the independent post-frame credential load, before watch-next has
        // published the first account notification to this already-created controller.
        val client=ui{a.model.youtube.client};runBlocking{client.account()}
        control("slow_actions=1");ui{actions!!.rate(YouTubeRating.LIKE)}
        await{paths().any{it.endsWith("/like/like")}}
        ui{actions!!.accountChanged();actions!!.metadata(snapshot)}
        assertTrue("The late notice describes the same account as the pending write",ui{shown.busy})
        assertEquals(YouTubeRating.NONE,ui{shown.rating})
        await{ui{!shown.busy && shown.rating==YouTubeRating.LIKE}}
        ui{actions!!.metadata(snapshot)}
        assertEquals(YouTubeRating.LIKE,ui{shown.rating})
        assertEquals(1,paths().count{it.endsWith("/like/like")})
        assertFalse(device.hasObject(By.text("Video actions")));assertFalse(device.hasObject(By.text("TV-FIXTURE")))
    }
    @Test fun anAccountChangeRejectsAnOldResultAndHidesItsSelectedState() {
        signIn();create();control("slow_actions=1");ui{actions!!.rate(YouTubeRating.LIKE)}
        await{paths().any{it.endsWith("/like/like")}}
        ui{a.model.youtube.signOut()};await{ui{a.model.youtube.account==null}}
        ui{actions!!.accountChanged()};await{ui{!shown.signedIn && !shown.busy}}
        ui{actions!!.metadata(snapshot.copy(rating=YouTubeRating.LIKE,channel=snapshot.channel!!.copy(subscribed=true)))}
        assertNull(ui{shown.rating});assertNull(ui{shown.channel?.subscribed})
        SystemClock.sleep(1100)
        assertFalse(ui{shown.signedIn});assertNull(ui{shown.rating})
    }
    @Test fun closingTheOwnerCancelsItsLatePublicationWithoutOpeningAWindow() {
        signIn();create();control("slow_actions=1");ui{actions!!.rate(YouTubeRating.LIKE)}
        await{paths().any{it.endsWith("/like/like")}}
        ui{actions!!.close()};val count=ui{publications}
        SystemClock.sleep(1100)
        assertEquals(count,ui{publications});assertFalse(device.hasObject(By.text("Video actions")))
    }
    @Test fun oneSessionCanOpenSaveAfterMoreWithoutKeepingAnOldDialogParent() {
        signIn();create();ui{actions!!.more()}
        assertTrue(device.wait(Until.hasObject(By.text("Video actions")),10000))
        assertTrue(device.wait(Until.hasObject(By.text("Like").enabled(true)),10000))
        click("Done");await{!device.hasObject(By.text("Video actions"))}
        ui{actions!!.save()}
        assertTrue(device.wait(Until.hasObject(By.text("Save video")),10000))
        assertTrue(device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile(".*Watch later"))),10000))
        assertFalse(device.hasObject(By.text("Video actions")))
        assertTrue(state().getJSONObject("playlists").getJSONObject("WL").getJSONArray("videos").length()==0)
    }
}
