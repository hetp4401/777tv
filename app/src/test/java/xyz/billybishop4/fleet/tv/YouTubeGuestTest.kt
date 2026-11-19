package xyz.billybishop4.fleet.tv

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeGuestTest {
    private fun card(id:String)=JSONObject().put("videoRenderer",JSONObject().put("videoId",id)
        .put("title",JSONObject().put("simpleText","Video $id")).put("thumbnail",JSONObject().put("thumbnails",
            JSONArray().put(JSONObject().put("url","https://i.ytimg.com/$id.jpg").put("width",320)))))
    private fun page(vararg ids:String)=YouTubeHttpResponse(200,JSONObject().put("contents",JSONArray(ids.map(::card))).toString())
    private fun nudge()=YouTubeHttpResponse(200,"""{"feedNudgeRenderer":{"title":"Make YouTube your own","subtitle":{"runs":[{"text":"Search while signed-out, or sign in for recommendations."}]}}}""")
    @Test fun actualGuestHomeUsesOnlyTheTvFeedWithoutLocalSeedsOrTopicSubstitution()=runBlocking {
        val watched=Progress(Title("W0000000001","A watched video","youtube"),null,45000,120000,10)
        val transport=FixtureYouTubeTransport();transport.respond={page(watched.title.id,"R0000000001")}
        val store=MemoryYouTubeCredentials()
        val home=YouTubeClient(store,transport,localHistory={listOf(watched)}).page(YouTubeFeed.HOME)
        assertEquals(listOf(watched.title.id,"R0000000001"),home.videos.map{it.id})
        assertTrue(home.shelves.none{it.name.startsWith("Because you watched")});assertNull(home.continuation)
        val calls=transport.requests.filter{it.body!=null};assertEquals(1,calls.size)
        assertEquals("default",calls.single().body!!.getString("browseId"));assertNull(calls.single().authorization)
        assertEquals(0,store.writes);assertFalse(transport.requests.any{it.path.endsWith("/next") || it.path.endsWith("/token")})
    }
    @Test fun emptyHomeKeepsTheUpstreamNudgeWithoutInventingRecommendations()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={nudge()}
        val home=YouTubeClient(MemoryYouTubeCredentials(),transport).page(YouTubeFeed.HOME)
        assertTrue(home.videos.isEmpty());assertTrue(home.shelves.isEmpty());assertNull(home.continuation)
        assertEquals("Search while signed-out, or sign in for recommendations.",home.message)
        assertEquals(1,transport.requests.count{it.path.endsWith("/browse")})
    }
    @Test fun refreshCanReceiveRealHomeImmediatelyAfterAnEmptySession()=runBlocking {
        val transport=FixtureYouTubeTransport();var calls=0
        transport.respond={if(calls++==0)nudge() else page("R0000000001")}
        val client=YouTubeClient(MemoryYouTubeCredentials(),transport)
        assertTrue(client.page(YouTubeFeed.HOME).videos.isEmpty())
        assertEquals(listOf("R0000000001"),client.page(YouTubeFeed.HOME).videos.map{it.id})
        assertEquals(2,transport.requests.count{it.body?.optString("browseId")=="default"})
    }
    @Test fun upstreamFailuresRemainRetryableRatherThanBecomingTopicRows()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={YouTubeHttpResponse(503,"{}")}
        val client=YouTubeClient(MemoryYouTubeCredentials(),transport)
        try{client.page(YouTubeFeed.HOME);fail("Expected the actual Home failure")}
        catch(error:HttpFailure){assertEquals(503,error.status)}
        transport.respond={page("R0000000001")}
        assertEquals(1,client.page(YouTubeFeed.HOME).videos.size)
        assertTrue(transport.requests.filter{it.body!=null}.all{it.body!!.optString("browseId")=="default"})
    }
    @Test fun apiErrorBodiesAreNotMisreportedAsAnEmptySuccessfulHome()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={YouTubeHttpResponse(200,"""{"error":{"code":503,"message":"Generated upstream failure"}}""")}
        try{YouTubeClient(MemoryYouTubeCredentials(),transport).page(YouTubeFeed.HOME);fail("Expected error-body failure")}
        catch(error:HttpFailure){assertEquals(503,error.status)}
        assertEquals(1,transport.requests.count{it.path.endsWith("/browse")})
    }
    @Test fun homeDeadlineCancelsItsUnderlyingRequestAndRemainsRetryable()=runBlocking {
        val active=AtomicInteger();val transport=FixtureYouTubeTransport()
        transport.respond={active.incrementAndGet();try{delay(60000);page("R0000000001")}finally{active.decrementAndGet()}}
        val client=YouTubeClient(MemoryYouTubeCredentials(),transport,guestRequestTimeoutMs=100)
        try{withTimeout(2000){client.page(YouTubeFeed.HOME)};fail("Expected bounded Home timeout")}
        catch(error:UserMessage){assertTrue(error.message!!.contains("Recommendations"))}
        assertEquals(0,active.get());transport.respond={page("R0000000001")}
        assertEquals(1,client.page(YouTubeFeed.HOME).videos.size)
    }
    @Test fun leavingHomeCancelsWithoutPublishingAFabricatedSuccess()=runBlocking {
        val active=AtomicInteger();val transport=FixtureYouTubeTransport()
        transport.respond={active.incrementAndGet();try{delay(60000);nudge()}finally{active.decrementAndGet()}}
        val pending=async(start=CoroutineStart.UNDISPATCHED){YouTubeClient(MemoryYouTubeCredentials(),transport).page(YouTubeFeed.HOME)}
        withTimeout(2000){while(active.get()==0)delay(1);pending.cancelAndJoin()}
        assertTrue(pending.isCancelled);assertEquals(0,active.get())
    }
    @Test fun unsignedHistoryIsLocalAndAccountOnlyFeedsNeverMakeAnonymousPrivateRequests()=runBlocking {
        val transport=FixtureYouTubeTransport()
        val old=Progress(Title("W0000000001","Older","youtube"),null,30_000,120_000,1)
        val recent=Progress(Title("W0000000002","Recent","youtube"),null,35_000,120_000,2)
        val client=YouTubeClient(MemoryYouTubeCredentials(),transport,localHistory={listOf(old,recent)})
        assertEquals(listOf(recent.title.id,old.title.id),client.page(YouTubeFeed.HISTORY).videos.map{it.id})
        for(feed in listOf(YouTubeFeed.SUBSCRIPTIONS,YouTubeFeed.WATCH_LATER,YouTubeFeed.LIKED)) {
            val p=client.page(feed);assertTrue(p.videos.isEmpty());assertTrue(p.message.contains("Sign in"))
        }
        assertTrue(transport.requests.isEmpty())
    }
    @Test fun signedInLibraryAndSubscriptionsUseTheirOwnFeedsAndBearer()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={page("A0000000001")}
        val client=YouTubeClient(MemoryYouTubeCredentials(YouTubeSession("fixture-access","fixture-refresh",Long.MAX_VALUE)),transport)
        for((feed,id) in listOf(YouTubeFeed.SUBSCRIPTIONS to "FEsubscriptions",YouTubeFeed.HISTORY to "FEhistory",YouTubeFeed.WATCH_LATER to "FEmy_youtube",YouTubeFeed.LIKED to "VLLL")) {
            assertEquals(1,client.page(feed).videos.size)
            val r=transport.requests.last();assertEquals(id,r.body!!.getString("browseId"));assertEquals("Bearer fixture-access",r.authorization)
            if(feed==YouTubeFeed.WATCH_LATER)assertEquals("cAc%3D",r.body.getString("params"))
        }
        client.page(YouTubeFeed.SUBSCRIPTIONS,continuation="next-subscriptions")
        assertEquals("next-subscriptions",transport.requests.last().body!!.getString("continuation"))
        assertFalse(transport.requests.last().body!!.has("browseId"))
    }
}
