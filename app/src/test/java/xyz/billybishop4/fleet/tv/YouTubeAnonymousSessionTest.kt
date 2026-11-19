package xyz.billybishop4.fleet.tv

import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeAnonymousSessionTest {
    private class TvTransport:YouTubeTransport {
        data class Request(val url:String,val body:JSONObject?,val headers:Map<String,String>)
        val requests=ArrayList<Request>()
        var rotate=false
        private val context=JSONObject("""{"client":{"clientName":"TVHTML5","clientVersion":"7.fixture","hl":"fr","gl":"CA","visitorData":"context-visitor","deviceMake":"Generated TV","configInfo":{"appInstallData":"generated-config"},"tvAppInfo":{"zylonLeftNav":false}},"user":{"lockedSafetyMode":true}}""")
        override suspend fun request(url:String,body:String?,headers:Map<String,String>):YouTubeHttpResponse {
            requests.add(Request(url,body?.let(::JSONObject),headers.toMap()))
            if(URI(url).path=="/tv")return YouTubeHttpResponse(200,JSONObject().put("INNERTUBE_CLIENT_VERSION","7.fixture")
                .put("VISITOR_DATA","initial-visitor").put("INNERTUBE_CONTEXT",context).toString()+"<script id=\"base-js\" src=\"/base.js\"></script>")
            if(URI(url).path=="/complete/search")return YouTubeHttpResponse(200,"[]")
            val result=JSONObject("""{"contents":[{"videoRenderer":{"videoId":"V0000000001","title":"Generated Home video"}}]}""")
            if(rotate)result.put("responseContext",JSONObject().put("visitorData","rotated-visitor"))
            return YouTubeHttpResponse(200,result.toString())
        }
    }
    @Test fun tvBootstrapContextLocaleAndVisitorHeaderArePreservedWithoutAnAccount()=runBlocking {
        val transport=TvTransport();val credentials=MemoryYouTubeCredentials()
        YouTubeClient(credentials,transport).page(YouTubeFeed.HOME)
        val call=transport.requests.single{it.body!=null};val context=call.body!!.getJSONObject("context");val client=context.getJSONObject("client")
        assertEquals("fr",client.getString("hl"));assertEquals("CA",client.getString("gl"))
        assertEquals("Generated TV",client.getString("deviceMake"));assertEquals("generated-config",client.getJSONObject("configInfo").getString("appInstallData"))
        assertFalse(client.getJSONObject("tvAppInfo").getBoolean("zylonLeftNav"));assertTrue(context.getJSONObject("user").getBoolean("lockedSafetyMode"))
        assertEquals("initial-visitor",client.getString("visitorData"));assertEquals("initial-visitor",call.headers["X-Goog-Visitor-Id"])
        assertNull(call.headers["Authorization"]);assertEquals(0,credentials.writes)
    }
    @Test fun anonymousVisitorRotationIsUsedByHomeContinuationAndSuggestionsReuseLocale()=runBlocking {
        val transport=TvTransport().apply{rotate=true};val client=YouTubeClient(MemoryYouTubeCredentials(),transport)
        client.page(YouTubeFeed.HOME);client.page(YouTubeFeed.HOME,continuation="next-home")
        val browse=transport.requests.filter{it.body!=null}
        assertEquals("initial-visitor",browse.first().headers["X-Goog-Visitor-Id"])
        assertEquals("rotated-visitor",browse.last().headers["X-Goog-Visitor-Id"])
        assertEquals("rotated-visitor",browse.last().body!!.getJSONObject("context").getJSONObject("client").getString("visitorData"))
        assertEquals("next-home",browse.last().body!!.getString("continuation"));assertFalse(browse.last().body!!.has("browseId"))
        client.searchSuggestions("news")
        val suggestions=transport.requests.single{URI(it.url).path=="/complete/search"}
        assertTrue(suggestions.url.contains("hl=fr"));assertTrue(suggestions.url.contains("gl=CA"))
        assertNull(suggestions.headers["Authorization"]);assertNull(suggestions.headers["X-Goog-Visitor-Id"])
        assertEquals(1,transport.requests.count{URI(it.url).path=="/tv"})
    }
    @Test fun anAccountResponseCannotOverwriteTheAnonymousVisitorIdentity()=runBlocking {
        val transport=TvTransport().apply{rotate=true}
        val client=YouTubeClient(MemoryYouTubeCredentials(YouTubeSession("fixture-access","fixture-refresh",Long.MAX_VALUE)),transport)
        client.page(YouTubeFeed.HOME);client.signOut();client.page(YouTubeFeed.HOME)
        val browse=transport.requests.filter{it.body!=null}
        assertEquals("Bearer fixture-access",browse.first().headers["Authorization"])
        assertNull(browse.last().headers["Authorization"]);assertEquals("initial-visitor",browse.last().headers["X-Goog-Visitor-Id"])
    }
    @Test fun aLateAnonymousResponseCannotUndoANewerVisitorRotation()=runBlocking {
        val bootstrap=TvTransport();val firstStarted=CompletableDeferred<Unit>();val releaseFirst=CompletableDeferred<Unit>()
        val visitors=ArrayList<String?>();var calls=0
        val transport=object:YouTubeTransport {
            override suspend fun request(url:String,body:String?,headers:Map<String,String>):YouTubeHttpResponse {
                if(body==null)return bootstrap.request(url,body,headers)
                visitors.add(headers["X-Goog-Visitor-Id"]);val index=++calls
                if(index==1){firstStarted.complete(Unit);releaseFirst.await()}
                return YouTubeHttpResponse(200,JSONObject().put("responseContext",JSONObject()
                    .put("visitorData",if(index==1)"older-rotation" else "newer-rotation")).put("contents",org.json.JSONArray()).toString())
            }
        }
        val client=YouTubeClient(MemoryYouTubeCredentials(),transport)
        val old=async{client.page(YouTubeFeed.HOME)}
        firstStarted.await();client.page(YouTubeFeed.HOME);releaseFirst.complete(Unit);old.await();client.page(YouTubeFeed.HOME)
        assertEquals(listOf("initial-visitor","initial-visitor","newer-rotation"),visitors)
    }
    @Test fun oversizedBootstrapContextIsNotRetained() {
        val context=JSONObject().put("client",JSONObject().put("unused", "x".repeat(40000)))
        val html=JSONObject().put("INNERTUBE_CLIENT_VERSION","7.fixture").put("visitorData","visitor").put("INNERTUBE_CONTEXT",context)
            .toString()+"<script id=\"base-js\" src=\"/base.js\"></script>"
        val parsed=YouTubeDecode.bootstrap(html,"https://www.youtube.com")
        assertNull(parsed.context);assertEquals("visitor",parsed.visitor)
    }
}
