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
    private fun topicReply(r:FixtureYouTubeTransport.Recorded)=when(r.body?.optString("browseId")) {
        "FEtopics_music"->page("M0000000001","M0000000002")
        "FEtopics_gaming"->page("G0000000001","G0000000002")
        "FEtopics_news"->page("N0000000001","N0000000002")
        "default"->YouTubeHttpResponse(200,"""{"feedNudgeRenderer":{"title":"Make YouTube your own","subtitle":"Sign in or search"}}""")
        else->page("R0000000001","R0000000002")
    }
    @Test fun emptyActualGuestHomeFallsBackToPublicShelvesWithoutCreatingAnAccount()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={topicReply(it)};val store=MemoryYouTubeCredentials()
        val client=YouTubeClient(store,transport);val home=client.page(YouTubeFeed.HOME)
        assertEquals(listOf("Music","Gaming","News"),home.shelves.map{it.name})
        assertEquals(listOf("M0000000001","M0000000002"),home.shelves.first().videos.map{it.id})
        assertEquals(6,home.videos.size);assertNull(home.continuation);assertNull(client.account());assertEquals(0,store.writes)
        assertTrue(transport.requests.all{it.authorization==null})
        assertEquals(4,transport.requests.count{it.path.endsWith("/browse")})
        assertEquals("default",transport.requests.first{it.path.endsWith("/browse")}.body!!.getString("browseId"))
        assertFalse(transport.requests.any{it.path.endsWith("/token")})
    }
    @Test fun guestSuggestionsUseARecentlyWatchedVideoAndExcludeAlreadyWatchedItems()=runBlocking {
        val seed=Progress(Title("W0000000001","A watched video","youtube"),null,45_000,120_000,10)
        val transport=FixtureYouTubeTransport();transport.respond={r->if(r.path.endsWith("/next"))page(seed.title.id,"R0000000001") else topicReply(r)}
        val home=YouTubeClient(MemoryYouTubeCredentials(),transport,localHistory={listOf(seed)}).page(YouTubeFeed.HOME)
        assertEquals("Because you watched A watched video",home.shelves.first().name)
        assertEquals(listOf("R0000000001"),home.shelves.first().videos.map{it.id})
        assertTrue(home.videos.none{it.id==seed.title.id})
        assertEquals(seed.title.id,transport.requests.single{it.path.endsWith("/next")}.body!!.getString("videoId"))
        assertTrue(transport.requests.all{it.authorization==null})
    }
    @Test fun briefPlaybackDoesNotBecomeARecommendationSeed()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={topicReply(it)}
        val brief=Progress(Title("W0000000001","Brief click","youtube"),null,2_000,120_000,1)
        YouTubeClient(MemoryYouTubeCredentials(),transport,localHistory={listOf(brief)}).page(YouTubeFeed.HOME)
        assertFalse(transport.requests.any{it.path.endsWith("/next")})
    }
    @Test fun oneUnavailableTopicDoesNotBlockTheOtherRecommendations()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={if(it.body?.optString("browseId")=="FEtopics_music")YouTubeHttpResponse(503,"{}") else topicReply(it)}
        val home=YouTubeClient(MemoryYouTubeCredentials(),transport).page(YouTubeFeed.HOME)
        assertEquals(listOf("Gaming","News"),home.shelves.map{it.name});assertEquals(4,home.videos.size)
    }
    @Test fun allFailedTopicsProduceARetryableFailureInsteadOfASignInRequirement()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={YouTubeHttpResponse(503,"{}")}
        try{YouTubeClient(MemoryYouTubeCredentials(),transport).page(YouTubeFeed.HOME);fail("Expected failure")}
        catch(e:UserMessage){assertTrue(e.message!!.contains("Recommendations"));assertFalse(e.message!!.contains("Sign in"))}
    }
    @Test fun publicRequestsAreBoundedAndCancelWhenTheViewerLeaves()=runBlocking {
        val active=AtomicInteger();val maximum=AtomicInteger();val transport=FixtureYouTubeTransport()
        transport.respond={val n=active.incrementAndGet();maximum.updateAndGet{maxOf(it,n)};try{delay(35);topicReply(it)}finally{active.decrementAndGet()}}
        YouTubeClient(MemoryYouTubeCredentials(),transport).page(YouTubeFeed.HOME)
        assertEquals(2,maximum.get());assertEquals(0,active.get())
        transport.respond={delay(60_000);topicReply(it)}
        val pending=async(start=CoroutineStart.UNDISPATCHED){YouTubeClient(MemoryYouTubeCredentials(),transport).page(YouTubeFeed.HOME)}
        withTimeout(2000){pending.cancelAndJoin()};assertTrue(pending.isCancelled)
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
    @Test fun oneStalledPublicTopicCannotHoldCompletedRecommendationShelves()=runBlocking {
        val transport=FixtureYouTubeTransport();val cancelled=AtomicInteger()
        transport.respond={r->if(r.body?.optString("browseId")=="FEtopics_music") {
            try{delay(60_000);topicReply(r)}finally{cancelled.incrementAndGet()}
        } else topicReply(r)}
        val home=withTimeout(2_000){YouTubeClient(MemoryYouTubeCredentials(),transport,guestRequestTimeoutMs=120).page(YouTubeFeed.HOME)}
        assertEquals(listOf("Gaming","News"),home.shelves.map{it.name});assertEquals(4,home.videos.size)
        assertEquals(1,cancelled.get())
    }
    @Test fun stalledActualGuestHomeIsNotCachedAndAllStalledTopicsCancelWithARetryableFailure()=runBlocking {
        val transport=FixtureYouTubeTransport();val active=AtomicInteger();var allTopicsStall=false
        transport.respond={r->if(r.body?.optString("browseId")=="default" || allTopicsStall) {
            active.incrementAndGet();try{delay(60_000);topicReply(r)}finally{active.decrementAndGet()}
        } else topicReply(r)}
        val client=YouTubeClient(MemoryYouTubeCredentials(),transport,guestRequestTimeoutMs=100)
        assertEquals(6,withTimeout(2_000){client.page(YouTubeFeed.HOME)}.videos.size)
        assertEquals(0,active.get());allTopicsStall=true
        try{withTimeout(2_000){client.page(YouTubeFeed.HOME)};fail("All timed-out topics must be retryable")}
        catch(e:UserMessage){assertTrue(e.message!!.contains("Recommendations"))}
        assertEquals(0,active.get());assertEquals(2,transport.requests.count{it.body?.optString("browseId")=="default"})
    }
}
