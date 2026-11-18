package xyz.billybishop4.fleet.tv

import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class YouTubeSuggestionsTest {
    private fun response(query:String,vararg items:Any):String=")]}'\n"+JSONArray().put(query).put(JSONArray(items.toList())).toString()

    @Test fun tvArraySuggestionsKeepUnicodeOrderAndAnEightItemDistinctBudget() {
        val rows=listOf(JSONArray().put("  Café\n music  "),JSONArray().put("café music"),JSONObjectMarker,
            JSONArray().put(""))+(1..20).map{JSONArray().put("café video $it")}
        val result=YouTubeSuggestions.decode(response("café",*rows.toTypedArray()),"café")
        assertEquals(8,result.size);assertEquals("Café music",result.first());assertEquals("café video 7",result.last())
        assertFalse(result.any{it.contains('\n')});assertEquals(result.size,result.map{it.lowercase()}.distinct().size)
    }

    @Test fun wrongQueryOversizeOrUnknownShapesNeverBecomeSelectableSuggestions() {
        assertTrue(YouTubeSuggestions.decode(response("old",JSONArray().put("irrelevant")),"new").isEmpty())
        assertTrue(YouTubeSuggestions.decode(" ".repeat(64*1024+1),"query").isEmpty())
        assertTrue(YouTubeSuggestions.decode("[\"query\", {}]","query").isEmpty())
    }

    @Test fun completionNeverReadsOrSendsAccountCredentialsOrBootstrapsTheTvSite()=runBlocking {
        var calls=0;var address=""
        val store=object:YouTubeCredentials {
            override fun read():YouTubeSession=error("Suggestions must not read the account")
            override fun write(session:YouTubeSession?)=error("Suggestions must not write the account")
        }
        val transport=object:YouTubeTransport {
            override suspend fun request(url:String,body:String?,headers:Map<String,String>):YouTubeHttpResponse {
                calls++;address=url;assertNull(body);assertTrue(headers.isEmpty())
                return YouTubeHttpResponse(200,response("café & music",JSONArray().put("café & music live")))
            }
        }
        assertEquals(listOf("café & music live"),YouTubeClient(store,transport).searchSuggestions("  café & music "))
        assertEquals(1,calls);assertEquals("clients1.google.com",URI(address).host)
        val url=address.toHttpUrlOrNull()!!
        assertEquals("café & music",url.queryParameter("q"));assertEquals("youtube-lr",url.queryParameter("client"))
    }

    @Test fun optionalMalformedAndOfflineCompletionsLeaveSearchUsable()=runBlocking {
        val transport=FixtureYouTubeTransport();val client=YouTubeClient(MemoryYouTubeCredentials(),transport)
        transport.respond={YouTubeHttpResponse(200,"window.callback('not JSON')")};assertTrue(client.searchSuggestions("video").isEmpty())
        transport.respond={YouTubeHttpResponse(503,"{}")};assertTrue(client.searchSuggestions("video").isEmpty())
        transport.respond={throw java.io.IOException("offline")};assertTrue(client.searchSuggestions("video").isEmpty())
        val before=transport.requests.size;assertTrue(client.searchSuggestions("\n ").isEmpty());assertEquals(before,transport.requests.size)
    }

    @Test fun leavingSearchCancelsAnInFlightCompletion()=runBlocking {
        val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val transport=object:YouTubeTransport {
            override suspend fun request(url:String,body:String?,headers:Map<String,String>):YouTubeHttpResponse {
                started.complete(Unit);try{awaitCancellation()}finally{cancelled.complete(Unit)}
            }
        }
        val request=async(start=CoroutineStart.UNDISPATCHED){YouTubeClient(MemoryYouTubeCredentials(),transport).searchSuggestions("video")}
        withTimeout(2000){started.await();request.cancelAndJoin();cancelled.await()};assertTrue(request.isCancelled)
    }

    @Test fun fixtureOriginStaysLocalAndSuggestionResponseHasASmallerWireLimit()=runBlocking {
        MockWebServer().use { server->
            server.enqueue(MockResponse().setBody(response("video",JSONArray().put("video music"))))
            server.enqueue(MockResponse().setBody("x".repeat(64*1024+1)))
            server.start()
            val client=YouTubeClient(MemoryYouTubeCredentials(),origin=server.url("/youtube").toString())
            assertEquals(listOf("video music"),client.searchSuggestions("video"))
            assertEquals("/youtube/complete/search",server.takeRequest(2,TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
            assertTrue(client.searchSuggestions("video").isEmpty())
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
        }
    }

    companion object { private val JSONObjectMarker=org.json.JSONObject().put("text","ignored object") }
}
