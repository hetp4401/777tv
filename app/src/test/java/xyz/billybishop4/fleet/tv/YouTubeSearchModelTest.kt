package xyz.billybishop4.fleet.tv

import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Typing and navigation race the network on a TV. Optional suggestions may
 * fail or arrive late without replacing the latest query or its video results. */
class YouTubeSearchModelTest {
    private class Transport:YouTubeTransport {
        val queries=CopyOnWriteArrayList<String>()
        var suggestions:suspend (String)->List<String> = { listOf("$it videos","$it music") }
        override suspend fun request(url:String,body:String?,headers:Map<String,String>):YouTubeHttpResponse {
            val uri=URI(url)
            if(uri.path.endsWith("/complete/search")) {
                val query=uri.rawQuery.orEmpty().split('&').firstOrNull{it.startsWith("q=")}
                    ?.substringAfter('=')?.let{URLDecoder.decode(it,"UTF-8")}.orEmpty()
                queries+=query
                val entries=JSONArray(suggestions(query).map{JSONArray().put(it)})
                return YouTubeHttpResponse(200,")]}'\n"+JSONArray().put(query).put(entries).toString())
            }
            if(uri.path.endsWith("/tv"))return YouTubeHttpResponse(200,"""{"INNERTUBE_CLIENT_VERSION":"7.fixture","visitorData":"fixture"}<script id="base-js" src="/base.js"></script>""")
            return YouTubeHttpResponse(200,"""{"contents":[{"videoRenderer":{"videoId":"V0000000001","title":"Result"}}]}""")
        }
    }
    private suspend fun await(condition:()->Boolean){withTimeout(5000){while(!condition())delay(5)}}
    private fun model(scope:CoroutineScope,t:Transport)=YouTubeModel(
        YouTubeClient(MemoryYouTubeCredentials(),t,origin="http://127.0.0.1"),scope,{}, {_,_->},{})

    @Test fun typingDebouncesToLatestQueryWithoutWaitingForVideoResults()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=Transport();val m=model(scope,t)
        try {
            m.showSearch();m.editQuery("ca");delay(40);m.editQuery("cat");delay(40);m.editQuery("cats")
            await{m.searchSuggestions.isNotEmpty()}
            assertEquals(listOf("cats"),t.queries.toList())
            assertEquals(listOf("cats videos","cats music"),m.searchSuggestions)
            m.submitSearch();await{!m.busy}
            assertTrue(m.searchSuggestions.isEmpty());assertEquals("cats",m.query)
            assertEquals(listOf("V0000000001"),m.videos.map{it.id})
        }finally{scope.cancel()}
    }
    @Test fun lateSuggestionsCannotReplaceNewQueryAndLeavingSearchDiscardsThem()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=Transport();val m=model(scope,t)
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val finished=CompletableDeferred<Unit>()
        t.suggestions={q->if(q=="old")withContext(NonCancellable){started.complete(Unit);release.await();finished.complete(Unit);listOf("old late result")} else listOf("$q latest")}
        try {
            m.showSearch();m.editQuery("old");withTimeout(5000){started.await()}
            m.editQuery("new");await{m.searchSuggestions==listOf("new latest")}
            release.complete(Unit);withTimeout(5000){finished.await()};delay(25)
            assertEquals(listOf("new latest"),m.searchSuggestions)
            m.editQuery("another");m.select(YouTubeFeed.HOME);delay(260)
            assertTrue(m.searchSuggestions.isEmpty());assertFalse(t.queries.contains("another"))
            m.showSearch();m.editQuery("gone");m.deactivate();delay(260)
            assertTrue(m.searchSuggestions.isEmpty());assertFalse(t.queries.contains("gone"))
        }finally{release.complete(Unit);scope.cancel()}
    }
    @Test fun suggestionFailuresNeverBlockSearchAndMemoryPressureReleasesText()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=Transport();val m=model(scope,t)
        try {
            t.suggestions={throw java.io.IOException("Optional suggestion service unavailable")}
            m.showSearch();m.editQuery("offline suggestions");await{t.queries.isNotEmpty()};delay(25)
            assertNull(m.error);assertTrue(m.searchSuggestions.isEmpty())
            m.submitSearch();await{!m.busy};assertEquals(1,m.videos.size);assertNull(m.error)
            t.suggestions={(1..20).map{"$it choice"}}
            m.editQuery("many");await{m.searchSuggestions.isNotEmpty()};assertEquals(6,m.searchSuggestions.size)
            m.trimMemory(keepPage=true);assertTrue(m.searchSuggestions.isEmpty());assertEquals(1,m.videos.size)
        }finally{scope.cancel()}
    }
}
