package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YouTubePreloadTest {
    @Test fun shortsResolveOnlyOneClipAheadReuseItAndCancelPendingWarmup()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val resolved=mutableListOf<String>();var current:Watch?=null
        val resolver=object:YouTubeStreamResolver {
            override suspend fun resolve(video:YouTubeVideo):Watch {
                resolved+=video.id
                return Watch(video.title(),Stream("Fixture","https://example.test/${video.id}.m3u8"),short=video.isShort,resolvedAtMs=System.currentTimeMillis())
            }
        }
        try {
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {watch,_->current=watch},{},resolver)
            val videos=(1..4).map{YouTubeVideo("S000000000$it","Short $it",isShort=true)}
            model.installFixturePage(YouTubeFeed.SHORTS,YouTubePage(videos));model.play(videos.first());delay(20)
            model.preloadShort(current!!);delay(700)
            assertEquals(videos.take(2).map{it.id},resolved)
            model.step(current!!,1);delay(20);assertEquals(videos[1].id,current!!.title.id)
            assertEquals("A ready warmup must avoid a second resolution",2,resolved.size)
            model.preloadShort(current!!);model.cancelShortPreload();delay(700)
            assertEquals(2,resolved.size)
            model.preloadShort(current!!);model.trimMemory(keepPage=true);delay(700)
            assertEquals("Memory pressure releases pending work while preserving clip navigation",2,resolved.size)
        } finally{scope.cancel()}
    }
}
