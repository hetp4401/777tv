package xyz.billybishop4.fleet.tv

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Generated account/clock/HTTP fixtures only; never writes to a Google account. */
class YouTubeHistoryTest {
    private val video="abcdefghijk"
    private val nonce="abcdefghijklmnop"
    private val origin="https://www.youtube.com"
    private fun player(base:String=origin,id:String=video)=JSONObject()
        .put("videoDetails",JSONObject().put("videoId",id))
        .put("playabilityStatus",JSONObject().put("status","OK"))
        .put("playbackTracking",JSONObject()
            .put("videostatsPlaybackUrl",JSONObject().put("baseUrl","$base/api/stats/playback?docid=$id&ei=fixture-ei&vm=fixture-vm&of=fixture-of"))
            .put("videostatsWatchtimeUrl",JSONObject().put("baseUrl","$base/api/stats/watchtime?docid=$id&ei=fixture-ei&vm=fixture-vm&of=fixture-of")))
    private fun tracked(session:YouTubeHistorySession,base:String=session.playbackUrl)=base.toHttpUrl().newBuilder()
        .setQueryParameter("docid",session.videoId).setQueryParameter("cpn",session.nonce).build().toString()
    private suspend fun drain(){repeat(12){yield()}}
    private class Backend:YouTubeHistoryBackend {
        var epoch:Int?=1
        var paused=false
        val setups=ArrayList<Int>()
        val writes=ArrayList<String>()
        var onSetup:suspend ()->Unit={}
        var onSend:suspend (String)->Boolean={true}
        override fun historyAccountEpoch()=epoch
        override suspend fun historySession(videoId:String,nonce:String,expectedEpoch:Int):YouTubeHistorySession? {
            setups+=expectedEpoch;onSetup()
            if(epoch!=expectedEpoch || paused)return null
            return YouTubeHistorySession(expectedEpoch,videoId,nonce,"https://www.youtube.com/api/stats/playback?ei=fixture-ei&vm=fixture-vm&of=fixture-of",
                "https://www.youtube.com/api/stats/watchtime?ei=fixture-ei&vm=fixture-vm&of=fixture-of")
        }
        override suspend fun sendHistory(session:YouTubeHistorySession,url:String):Boolean {
            if(!historySessionCurrent(session))return false
            if(!onSend(url))return false
            if(!historySessionCurrent(session))return false
            writes+=url;return true
        }
        override suspend fun historySessionCurrent(session:YouTubeHistorySession)=epoch==session.epoch
    }
    private class Harness(parent:CoroutineScope,flushMs:Long=4000L) {
        val job=SupervisorJob(parent.coroutineContext[Job])
        val scope=CoroutineScope(parent.coroutineContext+job)
        val backend=Backend()
        var time=0L
        var nonces=0
        val sync=YouTubeHistorySync(backend,scope,now={time},nonce={nonces++;"abcdefghijklmnop"},finalFlushMs=flushMs)
        fun start()=sync.begin("abcdefghijk")!!
        fun close(){sync.cancel();scope.cancel()}
        fun watchtime()=backend.writes.filter{URI(it).path.endsWith("/watchtime")}.map{it.toHttpUrl()}
    }
    @Test fun onlyExactGoogleStatsOrLoopbackFixtureEndpointsReceiveTracking() {
        for(path in listOf("playback","watchtime"))assertTrue(validHistoryTrackingUrl("$origin/api/stats/$path?ei=opaque",origin))
        assertTrue(validHistoryTrackingUrl("http://10.0.2.2:8878/youtube/api/stats/watchtime","http://10.0.2.2:8878/youtube"))
        assertTrue(validHistoryTrackingUrl("http://127.0.0.1:1234/api/stats/playback","http://127.0.0.1:1234"))
        for(bad in listOf("http://www.youtube.com/api/stats/playback","https://youtube.com/api/stats/playback",
            "https://www.youtube.com.other.example/api/stats/playback","https://other.example/api/stats/watchtime",
            "https://www.youtube.com:8443/api/stats/watchtime","$origin/api/stats/watchtime/","$origin/api/stats/%77atchtime",
            "$origin/youtubei/v1/like/like","https://user@www.youtube.com/api/stats/watchtime","$origin/api/stats/watchtime#fragment"))
            assertFalse(bad,validHistoryTrackingUrl(bad,origin))
        assertFalse(validHistoryTrackingUrl("http://10.0.2.2:8879/youtube/api/stats/playback","http://10.0.2.2:8878/youtube"))
        assertFalse(validHistoryTrackingUrl("https://other.example/api/stats/playback","https://other.example"))
    }
    @Test fun responseMustMatchVideoPlayabilityEndpointTypeAndAccountPolicy() {
        assertNotNull(decodeHistorySession(player(),1,video,nonce,origin))
        assertNull(decodeHistorySession(player(id="12345678901"),1,video,nonce,origin))
        assertNull(decodeHistorySession(player(),1,video,"invalid",origin))
        assertNull(decodeHistorySession(player().put("playabilityStatus",JSONObject().put("status","LOGIN_REQUIRED")),1,video,nonce,origin))
        for(flag in listOf("historyPaused","isHistoryPaused","watchHistoryPaused","historyTrackingDisabled"))
            assertNull(flag,decodeHistorySession(player().put("policy",JSONObject().put(flag,true)),1,video,nonce,origin))
        assertNull(decodeHistorySession(player().put("policy",JSONObject().put("watchHistoryEnabled",false)),1,video,nonce,origin))
        assertNull(decodeHistorySession(player().put("historyState","PAUSED"),1,video,nonce,origin))
        val swapped=player();swapped.getJSONObject("playbackTracking").getJSONObject("videostatsPlaybackUrl").put("baseUrl","$origin/api/stats/watchtime")
        assertNull(decodeHistorySession(swapped,1,video,nonce,origin))
    }
    @Test fun opaqueResponseTokensAreRedactedAndAccountResumeIsBounded() {
        val response=player().put("playerConfig",JSONObject().put("playbackStartConfig",JSONObject().put("startSeconds",12.5)))
        val session=decodeHistorySession(response,4,video,nonce,origin)!!
        assertEquals(12500L,session.resumePositionMs)
        assertEquals("YouTubeHistorySession(epoch=4)",session.toString())
        response.getJSONObject("playerConfig").getJSONObject("playbackStartConfig").put("startSeconds",Double.MAX_VALUE)
        assertNull(decodeHistorySession(response,4,video,nonce,origin)!!.resumePositionMs)
        var nested=JSONObject().put("historyPaused",true)
        repeat(18){nested=JSONObject().put("nested",nested)}
        assertNull(decodeHistorySession(player().put("policy",nested),1,video,nonce,origin))
    }
    @Test fun largeUnrelatedMediaAndAdPayloadsDoNotBecomeHistoryPolicy() {
        var deep=JSONObject().put("media","fixture")
        repeat(40){deep=JSONObject().put("nested",deep)}
        val large=JSONArray();repeat(3500){large.put(JSONObject().put("format",it))}
        val response=player().put("responseContext",JSONObject().put("historyPaused",false))
            .put("playerConfig",JSONObject().put("watchHistoryEnabled",true))
        for(key in listOf("streamingData","adPlacements","playerAds","adSlots","captions","microformat","storyboards","assets"))
            response.put(key,JSONObject().put("deep",deep).put("large",large))
        assertNotNull(decodeHistorySession(response,1,video,nonce,origin))
        response.getJSONObject("responseContext").put("historyPaused",true)
        assertNull(decodeHistorySession(response,1,video,nonce,origin))
        response.getJSONObject("responseContext").put("historyPaused",false)
        response.getJSONObject("playerConfig").put("policy",deep)
        assertNull("Malformed policy stays fail-closed",decodeHistorySession(response,1,video,nonce,origin))
    }
    @Test fun realStatsMetadataCanonicalizesToFixedWwwEndpointsAndPreservesOpaqueQuery() {
        val response=player("https://s.youtube.com")
        val playback=response.getJSONObject("playbackTracking").getJSONObject("videostatsPlaybackUrl")
        val raw=playback.getString("baseUrl")+"&el=detailpage&plid=opaque%2Fsession%3D&cl=fixture-build"
        playback.put("baseUrl",raw)
        val session=decodeHistorySession(response,1,video,nonce,origin)!!
        val canonical=session.playbackUrl.toHttpUrl()
        assertEquals("https",canonical.scheme);assertEquals("www.youtube.com",canonical.host)
        assertEquals("/api/stats/playback",canonical.encodedPath)
        assertEquals(raw.toHttpUrl().encodedQuery,canonical.encodedQuery)
        assertEquals("opaque/session=",canonical.queryParameter("plid"))
        assertEquals("www.youtube.com",session.watchtimeUrl.toHttpUrl().host)
        assertFalse("The bearer bridge must still reject the metadata host",validHistoryTrackingUrl(raw,origin))
        assertTrue(validHistoryTrackingUrl(session.playbackUrl,origin))
        assertNull("Fixture origins never expand their trust to Google",decodeHistorySession(response,1,video,nonce,"http://127.0.0.1:1234"))
    }
    @Test fun statsMetadataRejectsSpoofedHostsPortsSchemesPathsAndUserinfo() {
        val badUrls=listOf("http://s.youtube.com/api/stats/playback","https://s.youtube.com.other.example/api/stats/playback",
            "https://other.example/api/stats/playback","https://s.youtube.com:8443/api/stats/playback",
            "https://user@s.youtube.com/api/stats/playback","https://s.youtube.com/api/stats/playback#fragment",
            "https://s.youtube.com/api/stats/playback/","https://s.youtube.com/api/stats/%70layback",
            "https://s.youtube.com/api/other/playback","https://s.youtube.com/youtubei/v1/like/like",
            "https://s.youtube.com/api/stats/watchtime")
        for(url in badUrls) {
            val response=player("https://s.youtube.com")
            response.getJSONObject("playbackTracking").getJSONObject("videostatsPlaybackUrl").put("baseUrl",url)
            assertNull(url,decodeHistorySession(response,1,video,nonce,origin))
        }
        assertNotNull(decodeHistorySession(player("https://s.youtube.com:443"),1,video,nonce,origin))
    }
    @Test fun deepAnnotationAndCardUiDoesNotDisableTrackingButDeepAccountPolicyDoes() {
        var deep=JSONObject().put("buttonRenderer",JSONObject().put("text","Fixture"))
        repeat(40){deep=JSONObject().put("subscribeButton",deep)}
        val response=player("https://s.youtube.com").put("annotations",JSONArray().put(deep)).put("cards",deep)
            .put("endscreen",deep).put("responseContext",JSONObject().put("historyPaused",false))
        assertNotNull(decodeHistorySession(response,1,video,nonce,origin))
        for(container in listOf("responseContext","playerConfig","playbackTracking","historyPolicy")) {
            val malformed=player().put("annotations",deep)
            if(container=="playbackTracking")malformed.getJSONObject(container).put("policy",deep)else malformed.put(container,deep)
            assertNull(container,decodeHistorySession(malformed,1,video,nonce,origin))
        }
        response.getJSONObject("responseContext").put("historyPaused",true)
        assertNull(decodeHistorySession(response,1,video,nonce,origin))
    }
    @Test fun unsignedFocusAndBriefPlaybackDoNotCreateRequestsOrWorkers()=runBlocking {
        val h=Harness(this)
        try {
            h.backend.epoch=null;assertNull(h.sync.begin(video));assertEquals(0,h.nonces);assertFalse(h.job.children.any())
            h.backend.epoch=1;val run=h.start();drain();assertTrue(h.backend.setups.isEmpty())
            run.sample(0,120000,false);h.time=10000;run.sample(0,120000,false);drain();assertTrue(h.backend.setups.isEmpty())
            run.sample(0,120000,true);h.time=11999;run.sample(1999,120000,true);drain();assertTrue(h.backend.setups.isEmpty())
            run.finish();drain();assertTrue(h.backend.writes.isEmpty())
        }finally{h.close()}
    }
    @Test fun watchedPlaybackCreatesOneRecordAndThrottlesProgressWithoutLosingPause()=runBlocking {
        val h=Harness(this)
        try {
            val run=h.start();run.sample(0,120000,true);h.time=4000;run.sample(4000,120000,true);drain()
            assertEquals(listOf(1),h.backend.setups);assertEquals(2,h.backend.writes.size)
            val record=h.backend.writes.first().toHttpUrl();assertEquals("/api/stats/playback",record.encodedPath)
            assertEquals(video,record.queryParameter("docid"));assertEquals(nonce,record.queryParameter("cpn"))
            assertEquals("fixture-vm",record.queryParameter("vm"));assertEquals("fixture-of",record.queryParameter("of"))
            assertEquals("0.000",h.watchtime().first().queryParameter("st"));assertEquals("4.000",h.watchtime().first().queryParameter("et"))
            h.time=14000;run.sample(14000,120000,true);drain();assertEquals(1,h.watchtime().size)
            h.time=15000;run.sample(15000,120000,false);drain();assertEquals(2,h.watchtime().size)
            assertEquals("15.000",h.watchtime().last().queryParameter("cmt"));assertNull(h.watchtime().last().queryParameter("final"))
            run.finish();drain();assertEquals("1",h.watchtime().last().queryParameter("final"))
            assertEquals(1,h.backend.writes.count{URI(it).path.endsWith("/playback")})
        }finally{h.close()}
    }
    @Test fun seekGapsAreNeverClaimedAsWatchedAndEndKeepsActualPosition()=runBlocking {
        val h=Harness(this)
        try {
            val run=h.start();run.sample(0,200000,true);h.time=4000;run.sample(4000,200000,true);drain()
            h.time=6000;run.discontinuity(6000,120000,200000,true)
            h.time=10000;run.sample(124000,200000,true);h.time=11000;run.sample(125000,200000,false,ended=true);drain()
            val update=h.watchtime().last()
            assertEquals("4.000,120.000",update.queryParameter("st"));assertEquals("6.000,125.000",update.queryParameter("et"))
            assertEquals("125.000",update.queryParameter("cmt"));assertEquals("200.000",update.queryParameter("len"))
        }finally{h.close()}
    }
    @Test fun anUnobservedLargeSeekDoesNotCreateAWatchRecord()=runBlocking {
        val h=Harness(this)
        try {
            val run=h.start();run.sample(0,300000,true);h.time=1000;run.sample(200000,300000,false);run.finish();drain()
            assertTrue(h.backend.setups.isEmpty());assertTrue(h.backend.writes.isEmpty())
        }finally{h.close()}
    }
    @Test fun offlineWritesBackOffAndPreserveActualUnsentRanges()=runBlocking {
        val h=Harness(this)
        try {
            var attempts=0;h.backend.onSend={if(++attempts==1)throw IOException("offline") else true}
            val run=h.start();run.sample(0,120000,true);h.time=4000;run.sample(4000,120000,true);drain()
            h.time=8000;run.sample(8000,120000,true);drain();assertEquals(1,attempts);assertTrue(h.backend.writes.isEmpty())
            h.time=20000;run.sample(20000,120000,true);drain()
            assertEquals(1,h.backend.setups.size);assertEquals("0.000",h.watchtime().single().queryParameter("st"))
            assertEquals("20.000",h.watchtime().single().queryParameter("et"))
        }finally{h.close()}
    }
    @Test fun anAccountSwitchBeforeSetupCannotAttributeOldPlaybackToTheNewAccount()=runBlocking {
        val h=Harness(this)
        try {
            val run=h.start();run.sample(0,120000,true);h.backend.epoch=2
            h.time=4000;run.sample(4000,120000,true);drain()
            assertEquals(listOf(1),h.backend.setups);assertTrue(h.backend.writes.isEmpty())
        }finally{h.close()}
    }
    @Test fun anAccountSwitchAfterSetupStopsFurtherOldSessionWrites()=runBlocking {
        val h=Harness(this)
        try {
            val run=h.start();run.sample(0,120000,true);h.time=4000;run.sample(4000,120000,true);drain()
            h.backend.epoch=2;h.time=35000;run.sample(35000,120000,true);run.finish();drain()
            assertEquals(2,h.backend.writes.size);assertEquals(listOf(1),h.backend.setups)
        }finally{h.close()}
    }
    @Test fun pausedHistoryNeverSendsARecordOrEnablesHistory()=runBlocking {
        val h=Harness(this)
        try {
            h.backend.paused=true;val run=h.start();run.sample(0,120000,true);h.time=4000;run.sample(4000,120000,true);drain()
            h.time=40000;run.sample(40000,120000,true);run.finish();drain()
            assertEquals(1,h.backend.setups.size);assertTrue(h.backend.writes.isEmpty())
        }finally{h.close()}
    }
    @Test fun signOutCancelsSetupAndAStalledFinalFlushHasABoundedLifetime()=runBlocking {
        val h=Harness(this,flushMs=30)
        try {
            val started=CompletableDeferred<Unit>();var cancelled=0
            h.backend.onSetup={started.complete(Unit);try{awaitCancellation()}finally{cancelled++}}
            val run=h.start();run.sample(0,120000,true);h.time=4000;run.sample(4000,120000,true)
            withTimeout(1000){started.await()};run.finish()
            withTimeout(1000){while(cancelled==0)delay(5)}
            assertTrue(h.backend.writes.isEmpty());drain();assertFalse(h.job.children.any{it.isActive})
            val another=h.start();another.sample(0,120000,true);h.time=8000;another.sample(4000,120000,true);drain()
            h.sync.cancel();drain();assertEquals(2,cancelled);assertFalse(h.job.children.any{it.isActive})
        }finally{h.close()}
    }
    @Test fun rapidShortChangesRetainAtMostOneActiveAndOneFinalNetworkWorker()=runBlocking {
        val h=Harness(this)
        try {
            var active=0;var peak=0
            h.backend.onSetup={active++;peak=maxOf(peak,active);try{awaitCancellation()}finally{active--}}
            repeat(15){val run=h.start();run.sample(0,10000,true);h.time+=4000;run.sample(4000,10000,true);drain()}
            assertTrue("Only active and newest final session may remain",active<=2)
            assertTrue("Cancelled workers yield before new work starts",peak<=2)
            h.sync.cancel();drain();assertEquals(0,active)
        }finally{h.close()}
    }
    @Test fun clientAttachesBearerOnlyToValidatedCurrentEpochTracking()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={r->if(r.path.endsWith("/player"))YouTubeHttpResponse(200,player().toString())else YouTubeHttpResponse(204,"")}
        val client=YouTubeClient(MemoryYouTubeCredentials(YouTubeSession("fixture-access","fixture-refresh",Long.MAX_VALUE)),t)
        client.account();val epoch=client.historyAccountEpoch()!!
        val session=client.historySession(video,nonce,epoch)!!
        assertEquals(nonce,t.requests.first{it.path.endsWith("/player")}.body!!.getString("cpn"))
        assertTrue(client.sendHistory(session,tracked(session)))
        assertEquals("Bearer fixture-access",t.requests.last().authorization)
        val count=t.requests.size
        assertFalse(client.sendHistory(session,"https://other.example/api/stats/playback"));assertEquals(count,t.requests.size)
        assertFalse(client.sendHistory(session,tracked(session).toHttpUrl().newBuilder().setQueryParameter("docid","12345678901").build().toString()))
        assertFalse(client.sendHistory(session,tracked(session).toHttpUrl().newBuilder().addQueryParameter("cpn",nonce).build().toString()))
        assertEquals(count,t.requests.size)
        client.signOut();assertNull(client.historyAccountEpoch())
        assertNull(client.historySession(video,nonce,epoch));assertFalse(client.sendHistory(session,tracked(session)));assertEquals(count,t.requests.size)
    }
    @Test fun clientSignOutCancelsAnInflightStatsWriteInsteadOfLettingItFinish()=runBlocking {
        val started=CompletableDeferred<Unit>()
        val t=FixtureYouTubeTransport();t.respond={r->if(r.path.contains("/api/stats/")){started.complete(Unit);awaitCancellation()}else YouTubeHttpResponse(200,player().toString())}
        val client=YouTubeClient(MemoryYouTubeCredentials(YouTubeSession("fixture-access","fixture-refresh",Long.MAX_VALUE)),t)
        client.account();val session=client.historySession(video,nonce,client.historyAccountEpoch()!!)!!
        val write=async{client.sendHistory(session,tracked(session,session.watchtimeUrl))}
        withTimeout(1000){started.await()};client.signOut();withTimeout(1000){write.join()}
        assertTrue(write.isCancelled);assertNull(client.account())
    }
    @Test fun trackingRedirectIsRejectedWithoutContactingItsTarget()=runBlocking {
        MockWebServer().use { server->MockWebServer().use { target->
            server.start();target.start()
            val base=server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"INNERTUBE_CLIENT_VERSION":"7.fixture","visitorData":"fixture"}<script id="base-js" src="/base.js"></script>"""))
            server.enqueue(MockResponse().setBody(player(base).toString()))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",target.url("/leaked")))
            val client=YouTubeClient(MemoryYouTubeCredentials(YouTubeSession("fixture-access","fixture-refresh",Long.MAX_VALUE)),origin=base)
            client.account();val session=client.historySession(video,nonce,client.historyAccountEpoch()!!)!!
            assertFalse(client.sendHistory(session,tracked(session)))
            assertNull(target.takeRequest(100,TimeUnit.MILLISECONDS))
            assertEquals(3,server.requestCount)
        } }
    }
    @Test fun successfulNewSignInInvalidatesOldPlaybackBeforeItCanAcquireNewCredentials()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={r->when {
            r.path.endsWith("/token")->YouTubeHttpResponse(200,"""{"access_token":"new-fixture-access","refresh_token":"new-fixture-refresh","expires_in":3600}""")
            r.path.endsWith("/player")->YouTubeHttpResponse(200,player().toString())
            else->YouTubeHttpResponse(204,"")
        }}
        val client=YouTubeClient(MemoryYouTubeCredentials(YouTubeSession("old-fixture-access","old-fixture-refresh",Long.MAX_VALUE)),t)
        client.account();val oldEpoch=client.historyAccountEpoch()!!;val old=client.historySession(video,nonce,oldEpoch)!!
        client.pollSignIn(YouTubeDeviceCode("fixture-device","fixture-user","https://www.google.com/device",Long.MAX_VALUE,5))
        assertNotEquals(oldEpoch,client.historyAccountEpoch());val count=t.requests.size
        assertNull(client.historySession(video,nonce,oldEpoch));assertFalse(client.sendHistory(old,tracked(old)))
        assertEquals(count,t.requests.size)
        val fresh=client.historySession(video,nonce,client.historyAccountEpoch()!!)!!
        assertTrue(client.sendHistory(fresh,tracked(fresh)))
        assertEquals("Bearer new-fixture-access",t.requests.last().authorization)
    }
}
