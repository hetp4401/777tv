package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live interoperability smoke test; ordinary regression suites use generated fixtures. */
class YouTubeLiveProbeTest {
    @Test fun liveUnsignedRecommendationsHavePlayableCardsArtworkAndNoAccount()=runBlocking {
        assumeTrue("Set YOUTUBE_LIVE_PROBE=1 for live YouTube verification",System.getenv("YOUTUBE_LIVE_PROBE")=="1")
        val store=MemoryYouTubeCredentials();val client=YouTubeClient(store)
        val home=client.page(YouTubeFeed.HOME)
        assertTrue("Expected real signed-out recommendations",home.videos.size>=10)
        assertTrue(home.shelves.isNotEmpty());assertTrue(home.shelves.all{it.videos.isNotEmpty()})
        assertTrue(home.videos.all{YouTubeDecode.validId(it.id) && it.name.isNotBlank() && isHttp(it.thumbnail)})
        assertNull(client.account());assertEquals(0,store.writes)
        println("Verified signed-out YouTube recommendations: ${home.videos.size} videos in ${home.shelves.size} native shelves, no account created")
    }
    @Test fun liveSearchShortsPairingAndPublicVideoResolve()=runBlocking {
        assumeTrue("Set YOUTUBE_LIVE_PROBE=1 for live YouTube verification",System.getenv("YOUTUBE_LIVE_PROBE")=="1")
        val store=MemoryYouTubeCredentials();val client=YouTubeClient(store)
        val search=client.page(YouTubeFeed.SEARCH,"NASA Artemis official")
        assertTrue("Expected real YouTube search results",search.videos.size>=3)
        val shorts=client.page(YouTubeFeed.SHORTS)
        assertTrue("Expected Shorts",shorts.videos.size>=2);assertTrue(shorts.videos.all{it.isShort})
        val code=client.beginSignIn();assertTrue(code.userCode.isNotBlank());assertTrue(code.expiresAtMs>System.currentTimeMillis())
        assertNull("Issuing a code must not create a signed-in session",store.value)
        val details=client.watchDetails("aqz-KE-bpKQ");assertTrue(details.related.videos.isNotEmpty());assertNotNull(details.commentsToken)
        val comments=client.comments(details.commentsToken!!);assertTrue(comments.comments.isNotEmpty())
        val graph=client.heatmap("aqz-KE-bpKQ");assertTrue(graph.isNotEmpty())
        val watch=YouTubeResolver().resolve(YouTubeVideo("aqz-KE-bpKQ","Big Buck Bunny"))
        assertTrue(isHttp(watch.stream.url))
        for(url in listOf(watch.stream.url,watch.audioUrl).filter(String::isNotBlank)) {
            val request=Request.Builder().url(url).header("Range","bytes=0-1023").apply{watch.headers.forEach{(k,v)->header(k,v)}}.build()
            YouTubeHttp.client.newCall(request).execute().use { response ->
                assertTrue("Resolved media must be accessible: HTTP ${response.code}",response.code in setOf(200,206))
                val buffer=ByteArray(32);assertTrue(response.body!!.byteStream().read(buffer)>0)
            }
        }
        println("Verified live YouTube: search=${search.videos.size}, Shorts=${shorts.videos.size}, device pairing=issued, media=accessible, separateAudio=${watch.audioUrl.isNotBlank()}")
    }
}
