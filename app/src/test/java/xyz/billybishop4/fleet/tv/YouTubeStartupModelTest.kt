package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class YouTubeStartupModelTest {
    private fun video(n:Int,short:Boolean=false)=YouTubeVideo("V000000000$n","Video $n",isShort=short)
    private fun watch(video:YouTubeVideo,at:Long=System.currentTimeMillis(),sequence:Int=0)=Watch(video.title(),
        Stream("Generated","https://media.example.test/${video.id}/$sequence.m3u8"),short=video.isShort,resolvedAtMs=at)
    private suspend fun settled(model:YouTubeModel){withTimeout(3000){while(model.resolving)delay(1)}}
    @Test fun recentExplicitPlaysReuseOnlyThreeSourcesAndMemoryPressureReleasesThem()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val calls=mutableListOf<String>();val opened=mutableListOf<Watch>()
        try{
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch{calls+=video.id;return watch(video)}}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {w,_->opened+=w},{},resolver)
            model.play(video(1));settled(model);delay(2);model.play(video(1));settled(model)
            assertEquals(1,calls.size);assertEquals(2,opened.size);assertTrue(opened[1].playRequestedAtMs>opened[0].playRequestedAtMs)
            for(n in 2..4){model.play(video(n));settled(model)}
            model.play(video(1));settled(model);assertEquals(5,calls.size)
            model.play(video(4));settled(model);assertEquals(5,calls.size)
            model.trimMemory(keepPage=true);model.play(video(4));settled(model);assertEquals(6,calls.size)
        }finally{scope.cancel()}
    }
    @Test fun expiredSourcesAreNeverReusedAndRetryBypassesARecentSource()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());var calls=0;var opened:Watch?=null;var stale=true
        try{
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch{
                calls++;return watch(video,at=System.currentTimeMillis()-if(stale)61_000 else 0,sequence=calls)}}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {w,_->opened=w},{},resolver)
            model.play(video(1));settled(model);stale=false;model.play(video(1));settled(model);assertEquals(2,calls)
            val refreshed=model.refreshWatch(opened!!);assertEquals(3,calls)
            model.play(video(1));settled(model);assertEquals(3,calls);assertEquals(refreshed.stream,opened!!.stream)
        }finally{scope.cancel()}
    }
    @Test fun nextShortAdoptsItsRunningRequestWithoutRepeatingResolution()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val calls=mutableListOf<String>();var opened:Watch?=null
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var cancelled=false
        try{
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch{
                calls+=video.id
                if(video.id==video(2).id){started.complete(Unit);try{release.await()}finally{cancelled=!currentCoroutineContext().isActive}}
                return watch(video)
            }}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {w,_->opened=w},{},resolver)
            val clips=(1..3).map{video(it,true)};model.installFixturePage(YouTubeFeed.SHORTS,YouTubePage(clips))
            model.play(clips[0]);settled(model);model.preloadShort(opened!!);withTimeout(2000){started.await()}
            model.step(opened!!,1);delay(10);assertEquals(clips.take(2).map{it.id},calls)
            release.complete(Unit);settled(model);assertEquals(clips[1].id,opened!!.title.id);assertFalse(cancelled)
            // A different explicit selection must not wait behind another speculative request.
            model.trimMemory(keepPage=true);model.play(clips[0]);settled(model)
            model.preloadShort(opened!!);model.cancelPlayback();delay(10)
            assertEquals(3,calls.size)
        }finally{scope.cancel()}
    }
    @Test fun anotherExplicitSelectionCancelsAnAdoptedShortRequestWithoutOpeningItsLateResult()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());var opened:Watch?=null
        val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();val calls=mutableListOf<String>()
        try{
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch{
                calls+=video.id
                if(video.id=="V0000000002")try{started.complete(Unit);awaitCancellation()}finally{cancelled.complete(Unit)}
                return watch(video)
            }}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {w,_->opened=w},{},resolver)
            val clips=(1..3).map{video(it,true)};model.installFixturePage(YouTubeFeed.SHORTS,YouTubePage(clips))
            model.play(clips[0]);settled(model);model.preloadShort(opened!!);withTimeout(2000){started.await()}
            model.step(opened!!,1);delay(10);model.play(clips[2]);settled(model);withTimeout(1000){cancelled.await()}
            assertEquals(clips[2].id,opened!!.title.id);assertEquals(clips.map{it.id},calls)
        }finally{scope.cancel()}
    }
}
