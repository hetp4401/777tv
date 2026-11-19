package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeChannelTest {
    private val id="UC1111111111111111111111"
    private val other="UC2222222222222222222222"
    private fun video(n:Int)=JSONObject().put("videoRenderer",JSONObject().put("videoId","V000000000$n").put("title","Video $n"))
    private fun grid(n:Int,cursor:String?=null)=JSONObject().put("contents",JSONArray().put(video(n))).apply {
        if(cursor!=null)put("continuations",JSONArray().put(JSONObject().put("nextContinuationData",JSONObject().put("continuation",cursor))))
    }
    private fun tab(label:String,params:String,selected:Boolean,content:JSONObject)=JSONObject().put("tabRenderer",JSONObject()
        .put("title",label).put("selected",selected).put("endpoint",JSONObject().put("browseEndpoint",JSONObject().put("browseId",id).put("params",params))).put("content",content))
    private fun channel(n:Int=1,cursor:String?=null)=JSONObject().put("header",JSONObject().put("channelHeaderRenderer",JSONObject()
        .put("title",JSONObject().put("simpleText","A creator")).put("subscriberCountText",JSONObject().put("simpleText","10K subscribers"))))
        .put("tabs",JSONArray().put(tab("Home","",true,grid(n,cursor))).put(tab("Videos","videos-param",false,JSONObject())))
    private suspend fun settled(model:YouTubeModel){withTimeout(3000){while(model.busy || model.resolving)delay(1)}}

    @Test fun currentTvWormholeDestinationsBecomeRealChannelTabs() {
        val tiles=listOf("Videos","Shorts","Live","Courses","Playlists").map { name->JSONObject().put("tileRenderer",JSONObject()
            .put("style","TILE_STYLE_YTLR_WORMHOLE_RECTANGULAR").put("contentType","TILE_CONTENT_TYPE_CHANNEL").put("contentId",id)
            .put("header",JSONObject().put("tileHeaderRenderer",JSONObject().put("thumbnailOverlays",JSONArray().put(JSONObject().put("tileMetadataRenderer",JSONObject().put("title",JSONObject().put("simpleText",name)))))))
            .put("onSelectCommand",JSONObject().put("browseEndpoint",JSONObject().put("browseId",id).put("params","actual-$name")))) }
        val header=JSONObject("""{"title":{"simpleText":"Real TV creator"},"subtitle":{"lineRenderer":{"items":[{"lineItemRenderer":{"text":{"simpleText":"@creator"}}},{"lineItemRenderer":{"text":{"simpleText":"2.69M subscribers"}}}]}},"selectableDescription":{"selectableTextRenderer":{"compactDescription":{"simpleText":"About this creator"}}}}""")
        val root=JSONObject().put("header",JSONObject().put("channelHeaderRenderer",header))
            .put("contents",JSONArray().put(video(1)).put(JSONObject().put("shelfRenderer",JSONObject().put("content",JSONArray(tiles)))))
        val result=YouTubeSocialDecode.channel(root,id,null)
        assertEquals("Real TV creator",result.channel.name);assertEquals("2.69M subscribers",result.channel.subscriberText)
        assertEquals("About this creator",result.channel.description);assertEquals(listOf("Home","Videos","Shorts","Live"),result.tabs.map{it.title})
        assertEquals(listOf("","actual-Videos","actual-Shorts","actual-Live"),result.tabs.map{it.params})
        assertEquals("home",result.selectedTab);assertEquals(listOf("V0000000001"),result.page.videos.map{it.id})
    }
    @Test fun unselectedTvSortingReloadIsNeverTheSelectedGridContinuation() {
        val selected=tab("Latest","latest-sort",true,grid(1))
        val unselected=tab("Popular","popular-sort",false,JSONObject().put("continuation",JSONObject().put("reloadContinuationData",JSONObject().put("continuation","wrong-sort-reload"))))
        val result=YouTubeSocialDecode.channel(JSONObject().put("tabs",JSONArray().put(selected).put(unselected)),id,YouTubeChannelTab("actual-videos","Videos","actual-videos"))
        assertTrue(result.tabs.isEmpty());assertNull(result.page.continuation);assertEquals(listOf("V0000000001"),result.page.videos.map{it.id})
    }
    @Test fun channelReadUsesValidatedIdActualTabParamsAndContinuationWithoutMutations()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={YouTubeHttpResponse(200,channel().toString())}
        val client=YouTubeClient(MemoryYouTubeCredentials(),transport)
        client.channel(id);client.channel(id,YouTubeChannelTab("tab","Videos","server%3D"));client.channel(id,continuation="server-continuation")
        val calls=transport.requests.filter{it.path.contains("youtubei")};assertEquals(3,calls.size)
        assertTrue(calls.all{it.path.endsWith("/browse") && it.authorization==null})
        assertEquals(id,calls[0].body!!.getString("browseId"));assertEquals("server%3D",calls[1].body!!.getString("params"))
        assertFalse(calls[2].body!!.has("browseId"));assertEquals("server-continuation",calls[2].body!!.getString("continuation"))
        try{client.channel("../../account");fail("Invalid channel must not be requested")}catch(_:IllegalArgumentException){}
        assertEquals(3,transport.requests.count{it.path.contains("youtubei")})
    }
    @Test fun channelBackRestoresTheExactSearchAndUsesItsCachedResults()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport()
        t.respond={r->YouTubeHttpResponse(200,if(r.path.endsWith("/search"))grid(1).toString() else channel().toString())}
        try {
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {_,_->},{})
            model.showSearch();model.editQuery("space station");model.submitSearch();settled(model)
            model.openChannel(id);settled(model);assertEquals(YouTubeFeed.CHANNEL,model.feed);assertEquals("A creator",model.channelPage!!.channel.name)
            assertTrue(model.backChannel());settled(model);assertEquals(YouTubeFeed.SEARCH,model.feed);assertEquals("space station",model.query)
            assertEquals(listOf("V0000000001"),model.videos.map{it.id});assertEquals(1,t.requests.count{it.path.endsWith("/search")});assertFalse(model.backChannel())
        }finally{scope.cancel()}
    }
    @Test fun channelTabAndPaginationKeepIdentityAndNeverResolveBrowsingMedia()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();var resolves=0
        t.respond={r->val body=r.body!!;YouTubeHttpResponse(200,when {
            body.has("continuation")->grid(3).toString()
            body.optString("params")=="videos-param"->JSONObject().put("tabs",JSONArray().put(tab("Latest","sort-latest",true,grid(2,"next-upload")))).toString()
            else->channel().toString()
        })}
        try {
            val resolver=object:YouTubeStreamResolver { override suspend fun resolve(video:YouTubeVideo):Watch{resolves++;error("Browsing must not resolve media")} }
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {_,_->},{},resolver)
            model.openChannel(id);settled(model);model.selectChannelTab("videos-param");settled(model)
            assertEquals("A creator",model.channelPage!!.channel.name);assertEquals("videos-param",model.channelPage!!.selectedTab)
            assertEquals(listOf("Home","Videos"),model.channelPage!!.tabs.map{it.title});assertEquals("next-upload",model.continuation)
            model.more();settled(model);assertEquals(listOf("V0000000002","V0000000003"),model.videos.map{it.id})
            assertEquals("A creator",model.channelPage!!.channel.name);assertEquals(0,resolves)
        }finally{scope.cancel()}
    }
    @Test fun creatorAndTabCacheKeysNeverReuseAnotherCreatorsResponse()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport()
        t.respond={r->YouTubeHttpResponse(200,channel(if(r.body!!.optString("browseId")==other)2 else 1).toString())}
        try {
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {_,_->},{})
            model.openChannel(id);settled(model);model.openChannel(other);settled(model)
            assertEquals(listOf("V0000000002"),model.videos.map{it.id});model.openChannel(id);settled(model)
            assertEquals(listOf("V0000000001"),model.videos.map{it.id});assertEquals(2,t.requests.count{it.path.endsWith("/browse")})
        }finally{scope.cancel()}
    }
    @Test fun remotePositionAndServerResumeAreOnlyAttachedToTheExplicitOpen()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());var resolves=0;val opened=mutableListOf<Pair<Watch,Boolean>>()
        try {
            val resolver=object:YouTubeStreamResolver { override suspend fun resolve(video:YouTubeVideo):Watch{resolves++
                return Watch(video.title(),Stream("Generated","https://media.example.test/clip.m3u8"),resolvedAtMs=System.currentTimeMillis())} }
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {w,replace->opened+=w to replace},{},resolver)
            val video=YouTubeVideo("abcdefghijk","Video",resumePositionMs=73000)
            model.playRemote(video,0);settled(model);assertTrue(opened[0].second);assertEquals(0L,opened[0].first.requestedPositionMs)
            model.play(video.copy(resumePositionMs=null));settled(model)
            assertEquals(1,resolves);assertNull(opened[1].first.requestedPositionMs);assertNull(opened[1].first.remoteResumePositionMs);assertFalse(opened[1].second)
        }finally{scope.cancel()}
    }
    @Test fun remotePlaceholderKeepsMetadataAlreadySuppliedByItsResolver()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());var opened:Watch?=null
        try {
            val resolver=object:YouTubeStreamResolver { override suspend fun resolve(video:YouTubeVideo)=Watch(
                video.copy(name="Actual cast title",channel="Actual creator").title(),Stream("Generated","https://media.example.test/cast.m3u8"),resolvedAtMs=System.currentTimeMillis()) }
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {w,_->opened=w},{},resolver)
            model.playRemote(YouTubeVideo("abcdefghijk","YouTube video"),12500);settled(model)
            assertEquals("Actual cast title",opened!!.title.name);assertEquals("Actual creator",opened!!.title.description);assertEquals(12500L,opened!!.requestedPositionMs)
        }finally{scope.cancel()}
    }
    @Test fun remoteDisconnectCancelsOnlyItsPendingSourceRequest()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val started=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        var opens=0
        try {
            val resolver=object:YouTubeStreamResolver { override suspend fun resolve(video:YouTubeVideo):Watch {
                try{started.complete(Unit);awaitCancellation()}finally{cancelled.complete(Unit)}
            } }
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {_,_->opens++},{},resolver)
            model.playRemote(YouTubeVideo("abcdefghijk","Phone"));withTimeout(3000){started.await()}
            model.cancelRemotePlayback();withTimeout(3000){cancelled.await()}
            assertFalse(model.resolving);assertEquals(0,opens)
        }finally{scope.cancel()}
    }
    @Test fun remoteDisconnectPreservesANewerLocalResolveAndAlreadyOpenedVideo()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val remoteStarted=CompletableDeferred<Unit>()
        val localStarted=CompletableDeferred<Unit>();val releaseLocal=CompletableDeferred<Unit>();val opened=mutableListOf<Watch>()
        try {
            val resolver=object:YouTubeStreamResolver { override suspend fun resolve(video:YouTubeVideo):Watch {
                if(video.id=="abcdefghijk"){remoteStarted.complete(Unit);awaitCancellation()}
                localStarted.complete(Unit);releaseLocal.await()
                return Watch(video.title(),Stream("Generated","https://media.example.test/local.m3u8"),resolvedAtMs=System.currentTimeMillis())
            } }
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {w,_->opened+=w},{},resolver)
            model.playRemote(YouTubeVideo("abcdefghijk","Phone"));withTimeout(3000){remoteStarted.await()}
            model.play(YouTubeVideo("V0000000001","Local"));withTimeout(3000){localStarted.await()}
            model.cancelRemotePlayback();assertTrue(model.resolving)
            releaseLocal.complete(Unit);settled(model);assertEquals("Local",opened.single().title.name)
            model.cancelRemotePlayback();assertEquals("Local",opened.single().title.name);assertFalse(model.resolving)
        }finally{scope.cancel()}
    }
}
