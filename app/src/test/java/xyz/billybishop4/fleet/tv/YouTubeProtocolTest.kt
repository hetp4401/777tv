package xyz.billybishop4.fleet.tv

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal class MemoryYouTubeCredentials(var value:YouTubeSession?=null):YouTubeCredentials {
    var writes=0
    override fun read()=value
    override fun write(session:YouTubeSession?){value=session;writes++}
}
internal class FixtureYouTubeTransport:YouTubeTransport {
    data class Recorded(val path:String,val body:JSONObject?,val authorization:String?)
    val requests=CopyOnWriteArrayList<Recorded>()
    var respond:suspend (Recorded)->YouTubeHttpResponse = { YouTubeHttpResponse(200,"{}") }
    override suspend fun request(url:String,body:String?,headers:Map<String,String>):YouTubeHttpResponse {
        val path=java.net.URI(url).path
        val record=Recorded(path,body?.let(::JSONObject),headers["Authorization"]);requests.add(record)
        return when(path) {
            "/tv" -> YouTubeHttpResponse(200,"""{"INNERTUBE_CLIENT_VERSION":"7.fixture","visitorData":"fixture-visitor"}<script id="base-js" src="/base.js"></script>""")
            "/base.js" -> YouTubeHttpResponse(200,"""clientId:"fixture.apps.googleusercontent.com",clientSecret:"fixtureSecret"""")
            else -> respond(record)
        }
    }
}
class YouTubeProtocolTest {
    private fun card(id:String="abcdefghijk",short:Boolean=false):JSONObject {
        val parts=JSONArray().put(JSONObject().put("text",JSONObject().put("content","Fixture channel")))
        val rows=JSONArray().put(JSONObject().put("metadataParts",parts))
        val details=JSONObject().put("contentMetadataViewModel",JSONObject().put("metadataRows",rows))
        val metadata=JSONObject().put("lockupMetadataViewModel",JSONObject().put("title",JSONObject().put("content","Fixture video")).put("metadata",details))
        val images=JSONArray().put(JSONObject().put("url","https://i.ytimg.com/fixture.jpg").put("width",320))
            .put(JSONObject().put("url","https://i.ytimg.com/fixture-large.jpg").put("width",1920))
        val thumbnail=JSONObject().put("thumbnailViewModel",JSONObject().put("image",JSONObject().put("sources",images)))
        return JSONObject().put("lockupViewModel",JSONObject().put("contentId",id)
            .put("contentType",if(short)"LOCKUP_CONTENT_TYPE_SHORTS" else "LOCKUP_CONTENT_TYPE_VIDEO").put("metadata",metadata).put("contentImage",thumbnail))
    }
    private fun page(vararg cards:JSONObject)=JSONObject().put("contents",JSONArray(cards.toList()))
    @Test fun modernTvCardsHaveLightweightMetadataAndDisplaySizedArtwork() {
        val p=YouTubeDecode.page(page(card()))
        assertEquals(1,p.videos.size);val v=p.videos.single()
        assertEquals("Fixture video",v.name);assertEquals("Fixture channel",v.channel);assertEquals("https://i.ytimg.com/fixture.jpg",v.thumbnail)
        assertEquals("youtube",v.title().type);assertFalse(v.isShort)
    }
    @Test fun shortsRemainIdentifiableInSearchAndDedicatedFeeds() {
        val p=YouTubeDecode.page(page(card(),card("12345678901",true)),onlyShorts=true)
        assertEquals(listOf("12345678901"),p.videos.map{it.id});assertTrue(p.videos.single().isShort)
    }
    @Test fun channelsPromotionsMenusInvalidIdsAndDuplicateVideosDoNotBecomeCards() {
        val channel=card("12345678901").apply{getJSONObject("lockupViewModel").put("contentType","LOCKUP_CONTENT_TYPE_CHANNEL")}
        val p=YouTubeDecode.page(page(card(),card(),channel,card("invalid-id-too-long"),JSONObject().put("adSlotRenderer",card("22222222222")),JSONObject().put("menu",card("33333333333"))))
        assertEquals(listOf("abcdefghijk"),p.videos.map{it.id})
    }
    @Test fun legacyVideoRenderersRetainTitlesDurationsAndHttpsThumbnails() {
        val c=JSONObject("""{"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Old shape"}]},"ownerText":{"simpleText":"Channel"},"lengthText":{"simpleText":"12:34"},"thumbnail":{"thumbnails":[{"url":"//i.ytimg.com/fixture.jpg","width":320}]}}}""")
        val v=YouTubeDecode.page(page(c)).videos.single()
        assertEquals("Old shape",v.name);assertEquals("Channel",v.channel);assertEquals("12:34",v.duration);assertTrue(v.thumbnail.startsWith("https:"))
    }
    @Test fun signedOutHomeNudgeIsNotReplacedByFabricatedRecommendations() {
        val p=YouTubeDecode.page(JSONObject("""{"contents":{"feedNudgeRenderer":{"subtitle":{"runs":[{"text":"Sign in for recommendations"}]}}}}"""))
        assertTrue(p.videos.isEmpty());assertEquals("Sign in for recommendations",p.message)
    }
    @Test fun paginationReadsOnlyBoundedContinuationTokens() {
        val j=page(card()).put("continuationContents",JSONObject().put("nextContinuationData",JSONObject().put("continuation","next-page")))
        assertEquals("next-page",YouTubeDecode.page(j).continuation)
        j.getJSONObject("continuationContents").getJSONObject("nextContinuationData").put("continuation","x".repeat(20001))
        assertNull(YouTubeDecode.page(j).continuation)
    }
    @Test fun largeResponsesHaveABoundedCardCount() {
        val cards=(0..999).map{card(it.toString().padStart(11,'0'))}
        assertEquals(80,YouTubeDecode.page(JSONObject().put("contents",JSONArray(cards))).videos.size)
    }
    @Test fun tvBootstrapUsesItsPublishedVersionAndSameOriginScript() {
        val html="""{"INNERTUBE_CLIENT_VERSION":"7.test","visitorData":"v\\u003d"}<script id="base-js" src="/base.js"></script>"""
        val b=YouTubeDecode.bootstrap(html,"https://www.youtube.com")
        assertEquals("7.test",b.version);assertEquals("https://www.youtube.com/base.js",b.scriptUrl)
        assertThrows(IllegalArgumentException::class.java){YouTubeDecode.bootstrap(html.replace("/base.js","https://other.example/base.js"),"https://www.youtube.com")}
    }
    @Test fun dynamicDeviceClientParsingRejectsUnexpectedScripts() {
        val c=YouTubeDecode.deviceClient("""clientId:"fixture.apps.googleusercontent.com",secret:"fixtureSecret"""")
        assertEquals("fixture.apps.googleusercontent.com",c.first);assertEquals("fixtureSecret",c.second)
        assertThrows(UserMessage::class.java){YouTubeDecode.deviceClient("No client here")}
    }
    @Test fun authenticationObjectsNeverExposeTokensInDiagnosticStrings() {
        assertFalse(YouTubeSession("sensitive-access","sensitive-refresh",0).toString().contains("sensitive"))
        assertFalse(YouTubeDeviceCode("sensitive-device","sensitive-user","https://www.google.com/device",0,5).toString().contains("sensitive"))
        assertFalse(YouTubeHttpResponse(200,"sensitive-body").toString().contains("sensitive"))
    }
    @Test fun anonymousSearchSendsNoBearerAndKeepsItsOwnQuery()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={YouTubeHttpResponse(200,page(card()).toString())}
        val client=YouTubeClient(MemoryYouTubeCredentials(),transport)
        assertEquals(1,client.page(YouTubeFeed.SEARCH,"A movie & a show").videos.size)
        val request=transport.requests.last();assertEquals("A movie & a show",request.body!!.getString("query"));assertNull(request.authorization)
        assertEquals("7.fixture",request.body.getJSONObject("context").getJSONObject("client").getString("clientVersion"))
    }
    @Test fun homeUsesTheTvRecommendationFeed()=runBlocking {
        val t=FixtureYouTubeTransport();val client=YouTubeClient(MemoryYouTubeCredentials(),t)
        client.page(YouTubeFeed.HOME)
        assertEquals("default",t.requests.last().body!!.getString("browseId"));assertTrue(t.requests.last().path.endsWith("browse"))
    }
    @Test fun homePreservesTvShelfNamesOrderAndPortraitRows() {
        val j=JSONObject("""{"contents":{"sectionListRenderer":{"contents":[
            {"shelfRenderer":{"title":{"simpleText":"Recommended"},"content":{}}},
            {"richShelfRenderer":{"title":{"runs":[{"text":"Shorts"}]},"content":{}}}
        ]}}}""")
        val rows=j.getJSONObject("contents").getJSONObject("sectionListRenderer").getJSONArray("contents")
        rows.getJSONObject(0).getJSONObject("shelfRenderer").put("content",page(card(),card("22222222222")))
        rows.getJSONObject(1).getJSONObject("richShelfRenderer").put("content",page(card("33333333333",true)))
        val result=YouTubeDecode.home(j)
        assertEquals(listOf("Recommended","Shorts"),result.shelves.map{it.name})
        assertEquals(listOf(false,true),result.shelves.map{it.portrait})
        assertEquals(listOf("abcdefghijk","22222222222","33333333333"),result.videos.map{it.id})
    }
    @Test fun homeDoesNotUseAShelfsHorizontalCursorForVerticalPagination() {
        val shelf=JSONObject().put("shelfRenderer",page(card()).put("title","A row").put("continuations",
            JSONArray().put(JSONObject().put("nextContinuationData",JSONObject().put("continuation","wrong-row-token")))))
        val section=JSONObject().put("contents",JSONArray().put(shelf)).put("continuations",
            JSONArray().put(JSONObject().put("nextContinuationData",JSONObject().put("continuation","outer-feed-token"))))
        val j=JSONObject().put("contents",JSONObject().put("sectionListRenderer",section))
        assertEquals("outer-feed-token",YouTubeDecode.home(j).continuation)
        section.remove("continuations");assertNull(YouTubeDecode.home(j).continuation)
    }
    @Test fun homeBoundsShelfMetadataAndNeverFabricatesSignedOutRows() {
        val rows=(0..19).map { n->JSONObject().put("shelfRenderer",page(*(0..39).map{card((n*40+it).toString().padStart(11,'0'))}.toTypedArray()).put("title","Row $n")) }
        val result=YouTubeDecode.home(JSONObject().put("contents",JSONArray(rows)))
        assertEquals(120,result.shelves.sumOf{it.videos.size});assertTrue(result.shelves.size<=10)
        val empty=YouTubeDecode.home(JSONObject("""{"feedNudgeRenderer":{"subtitle":"Sign in"}}"""))
        assertTrue(empty.shelves.isEmpty());assertTrue(empty.videos.isEmpty());assertEquals("Sign in",empty.message)
        val flat=YouTubeDecode.home(page(card()));assertEquals(listOf("Recommended"),flat.shelves.map{it.name})
    }
    @Test fun expiredSessionsRefreshOnceForConcurrentRequests()=runBlocking {
        val store=MemoryYouTubeCredentials(YouTubeSession("expired-access","refresh",0));val t=FixtureYouTubeTransport()
        t.respond={r->if(r.path.endsWith("/token")){delay(30);YouTubeHttpResponse(200,"""{"access_token":"fresh-access","expires_in":3600}""")}else YouTubeHttpResponse(200,"{}")}
        val client=YouTubeClient(store,t,now={100000L})
        (1..5).map{async{client.page(YouTubeFeed.HOME)}}.awaitAll()
        assertEquals(1,t.requests.count{it.path.endsWith("/token")});assertEquals("fresh-access",store.value!!.accessToken)
        assertTrue(t.requests.filter{it.path.contains("/youtubei/")}.all{it.authorization=="Bearer fresh-access"})
    }
    @Test fun aRevokedRefreshTokenClearsOnlyYouTubeAuthentication()=runBlocking {
        val store=MemoryYouTubeCredentials(YouTubeSession("expired","revoked",0));val t=FixtureYouTubeTransport()
        t.respond={YouTubeHttpResponse(400,"""{"error":"invalid_grant"}""")}
        val client=YouTubeClient(store,t)
        try { client.page(YouTubeFeed.HOME);fail("Expected sign-in expiry") }catch(e:YouTubeAuthError){assertEquals("invalid_grant",e.reason)}
        assertNull(store.value);assertNull(client.account())
    }
    @Test fun networkFailureDoesNotEraseSavedAuthentication()=runBlocking {
        val store=MemoryYouTubeCredentials(YouTubeSession("expired","keep-refresh",0));val t=FixtureYouTubeTransport();t.respond={throw java.io.IOException("offline")}
        val client=YouTubeClient(store,t)
        try{client.page(YouTubeFeed.HOME);fail("Expected offline")}catch(_:java.io.IOException){}
        assertEquals("keep-refresh",store.value!!.refreshToken)
    }
    @Test fun devicePairingUsesReturnedUrlExpiryAndPollingInterval()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={YouTubeHttpResponse(200,"""{"device_code":"device","user_code":"User-Code","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}""")}
        val code=YouTubeClient(MemoryYouTubeCredentials(),t,now={1000}).beginSignIn()
        assertEquals("User-Code",code.userCode);assertEquals(1801000L,code.expiresAtMs);assertEquals(5,code.intervalSeconds)
        assertTrue(t.requests.last().body!!.getString("client_id").endsWith(".apps.googleusercontent.com"))
    }
    @Test fun devicePairingRejectsAnUntrustedVerificationSite()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={YouTubeHttpResponse(200,"""{"device_code":"d","user_code":"u","verification_url":"https://phishing.example/device"}""")}
        try{YouTubeClient(MemoryYouTubeCredentials(),t).beginSignIn();fail("Expected rejected URL")}catch(_:IllegalArgumentException){}
    }
    @Test fun pollingHandlesPendingSuccessAndPersistsTokens()=runBlocking {
        val t=FixtureYouTubeTransport();val store=MemoryYouTubeCredentials();var calls=0
        t.respond={if(++calls==1)YouTubeHttpResponse(400,"""{"error":"authorization_pending"}""") else YouTubeHttpResponse(200,"""{"access_token":"approved-access","refresh_token":"approved-refresh","expires_in":3600}""")}
        val client=YouTubeClient(store,t,now={1000});val code=YouTubeDeviceCode("device","user","https://www.google.com/device",100000,5)
        assertNull(client.pollSignIn(code));assertNull(store.value)
        assertEquals("approved-access",client.pollSignIn(code)!!.accessToken);assertEquals("approved-refresh",store.value!!.refreshToken)
    }
    @Test fun expiredDeviceCodeDoesNotPollTheNetwork()=runBlocking {
        val t=FixtureYouTubeTransport();val client=YouTubeClient(MemoryYouTubeCredentials(),t,now={10000})
        try{client.pollSignIn(YouTubeDeviceCode("d","u","https://www.google.com/device",9999,5));fail("Expected expiry")}catch(e:YouTubeAuthError){assertEquals("expired_token",e.reason)}
        assertTrue(t.requests.isEmpty())
    }
    @Test fun deniedAndSlowDownResponsesRetainTheirDistinctMeanings()=runBlocking {
        val t=FixtureYouTubeTransport();val client=YouTubeClient(MemoryYouTubeCredentials(),t,now={1000})
        for(reason in listOf("slow_down","access_denied")) {
            t.respond={YouTubeHttpResponse(400,JSONObject().put("error",reason).toString())}
            try{client.pollSignIn(YouTubeDeviceCode("d","u","https://www.google.com/device",100000,5));fail("Expected status")}catch(e:YouTubeAuthError){assertEquals(reason,e.reason)}
        }
    }
    @Test fun signOutRemovesSavedAndInMemoryCredentials()=runBlocking {
        val store=MemoryYouTubeCredentials(YouTubeSession("access","refresh",Long.MAX_VALUE));val client=YouTubeClient(store)
        assertNotNull(client.account());client.signOut();assertNull(store.value);assertNull(client.account())
    }
    @Test fun youtubeStartupPreferenceDoesNotChangeTheDefault() {
        assertEquals("On-Demand",Preferences.decode(JSONObject()).startSection)
        assertEquals("YouTube",Preferences.decode(JSONObject().put("startupScreen","YouTube")).startSection)
    }
}
