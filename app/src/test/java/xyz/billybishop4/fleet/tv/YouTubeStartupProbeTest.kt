package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in paired source-resolution timings against public YouTube; no account or physical TV. */
class YouTubeStartupProbeTest {
    @Test fun publicPlaybackOnlyRequestsAvoidUnneededWebMetadataAndStillReturnAccessibleMedia()=runBlocking {
        assumeTrue("Set YOUTUBE_STARTUP_PROBE=1",System.getenv("YOUTUBE_STARTUP_PROBE")=="1")
        val short=YouTubeClient(MemoryYouTubeCredentials()).page(YouTubeFeed.SHORTS).videos.first()
        val videos=listOf(YouTubeVideo("aqz-KE-bpKQ","Public animation"),YouTubeVideo("jNQXAC9IVRw","Public short video"),short)
        val rows=JSONArray()
        for((index,video) in videos.withIndex())for(iteration in 0..1) {
            val order=if((index+iteration)%2==0)listOf(false,true)else listOf(true,false)
            for(quick in order) {
                val requests=JSONArray();val start=playbackNowMs()
                val watch=YouTubeResolver(playbackOnly=quick,trace={path,ms->requests.put(JSONObject().put("endpoint",path).put("ms",ms))}).resolve(video)
                val elapsed=playbackNowMs()-start
                val request=Request.Builder().url(watch.stream.url).apply{watch.headers.forEach{(k,v)->header(k,v)}}.build()
                YouTubeHttp.mediaClient.newCall(request).execute().use{response->
                    assertEquals(200,response.code);assertTrue(response.body!!.string().startsWith("#EXTM3U"))
                }
                if(quick){assertEquals("Only visitor and player RPCs belong before playback",2,requests.length())
                    assertEquals("visitor_id",requests.getJSONObject(0).getString("endpoint"));assertEquals("player",requests.getJSONObject(1).getString("endpoint"))}
                rows.put(JSONObject().put("video",video.id).put("short",video.isShort).put("iteration",iteration)
                    .put("path",if(quick)"playback-only" else "full-extractor").put("resolveMs",elapsed).put("requests",requests))
            }
        }
        println("YOUTUBE_STARTUP="+JSONObject().put("scope","Paired public source resolution and HLS access on the Mac; not TV first-frame timings").put("results",rows))
    }
}
