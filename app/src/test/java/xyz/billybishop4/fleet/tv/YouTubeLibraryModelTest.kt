package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeLibraryModelTest {
    private fun signed()=MemoryYouTubeCredentials(YouTubeSession("account-a","refresh-a",Long.MAX_VALUE))
    private fun reply(body:JSONObject)=YouTubeHttpResponse(200,body.put("responseContext",JSONObject()).toString())
    private fun video(n:Int)=JSONObject().put("videoRenderer",JSONObject().put("videoId","V000000000$n").put("title","Video $n"))
    private fun page(n:Int,cursor:String?=null)=JSONObject().put("contents",JSONArray().put(video(n))).apply {
        if(cursor!=null)put("continuations",JSONArray().put(JSONObject().put("nextContinuationData",JSONObject().put("continuation",cursor))))
    }
    private fun playlist(id:String,name:String)=JSONObject().put("tileRenderer",JSONObject().put("contentType","TILE_CONTENT_TYPE_PLAYLIST").put("contentId",id)
        .put("metadata",JSONObject().put("tileMetadataRenderer",JSONObject().put("title",JSONObject().put("simpleText",name))))
        .put("onSelectCommand",JSONObject().put("browseEndpoint",JSONObject().put("browseId","VL$id"))))
    private suspend fun ready(model:YouTubeModel){withTimeout(3000){while(model.busy)delay(1)}}
    @Test fun accountCollectionsRemainDistinctFromVideosAndBoundedTo60() {
        val root=JSONObject().put("contents",JSONArray((1..100).map{playlist("PL$it","List $it")}))
        val decoded=YouTubeLibraryDecode.collection(root)
        assertEquals(60,decoded.playlists.size);assertEquals("PL1",decoded.playlists.first().id);assertEquals("List 1",decoded.playlists.first().name)
        assertTrue(YouTubeDecode.page(root).videos.isEmpty())
        assertTrue(YouTubeLibraryDecode.collection(page(1)).playlists.isEmpty())
    }
    @Test fun collectionAndPrivatePlaylistBrowseAreAuthenticatedAndUseServerIdsAndCursors()=runBlocking {
        val t=FixtureYouTubeTransport();t.respond={r->reply(if(r.body!!.optString("browseId")=="FEplaylist_aggregation")
            JSONObject().put("contents",JSONArray().put(playlist("PLcreated","A created playlist"))) else page(1))}
        val client=YouTubeClient(signed(),t)
        assertEquals("PLcreated",client.playlistCollection().playlists.single().id);client.playlistVideos("PLcreated");client.playlistVideos("PLcreated","playlist-next")
        val calls=t.requests.filter{it.path.endsWith("/browse")};assertTrue(calls.all{it.authorization=="Bearer account-a"})
        assertEquals(listOf("FEplaylist_aggregation","VLPLcreated",""),calls.map{it.body!!.optString("browseId")})
        assertEquals("playlist-next",calls.last().body!!.getString("continuation"))
        val unsigned=FixtureYouTubeTransport();val anonymous=YouTubeClient(MemoryYouTubeCredentials(),unsigned)
        try{anonymous.playlistCollection();fail("Expected sign-in")}catch(_:YouTubeAuthError){}
        try{anonymous.playlistVideos("PLcreated");fail("Expected sign-in")}catch(_:YouTubeAuthError){}
        assertTrue(unsigned.requests.isEmpty())
    }
    @Test fun openingPaginatingAndReturningToCreatedPlaylistDoesNotRefetchTheCollection()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport()
        t.respond={r->reply(when {
            r.body!!.has("continuation")->page(2)
            r.body.optString("browseId")=="FEplaylist_aggregation"->JSONObject().put("contents",JSONArray().put(playlist("PLcreated","Saved for later")))
            else->page(1,"playlist-next")
        })}
        try {
            val model=YouTubeModel(YouTubeClient(signed(),t),scope,{}, {_,_->},{})
            model.select(YouTubeFeed.PLAYLISTS);ready(model);assertEquals("Saved for later",model.playlistCards.single().name)
            model.openPlaylist(model.playlistCards.single());ready(model);assertEquals(YouTubeFeed.PLAYLIST,model.feed);assertEquals("PLcreated",model.selectedPlaylist!!.id)
            model.more();ready(model);assertEquals(listOf("V0000000001","V0000000002"),model.videos.map{it.id})
            assertTrue(model.backPlaylist());ready(model);assertEquals(YouTubeFeed.PLAYLISTS,model.feed);assertEquals("PLcreated",model.playlistCards.single().id)
            assertEquals(1,t.requests.count{it.body?.optString("browseId")=="FEplaylist_aggregation"})
            model.signOut();withTimeout(3000){while(model.account!=null)delay(1)}
            assertTrue(model.playlistCards.isEmpty());assertNull(model.selectedPlaylist)
        }finally{scope.cancel()}
    }
    @Test fun playlistCachesAreSeparatedByPlaylistIdAndInvalidatedAfterAcknowledgedSaves()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();var generation=0
        t.respond={r->reply(page(if(r.body!!.optString("browseId")=="VLPLone")1+generation else 2))}
        try {
            val model=YouTubeModel(YouTubeClient(signed(),t),scope,{}, {_,_->},{})
            val one=YouTubePlaylist("PLone","One",false);val two=YouTubePlaylist("PLtwo","Two",false)
            model.openPlaylist(one);ready(model);model.openPlaylist(two);ready(model);assertEquals("V0000000002",model.videos.single().id)
            model.openPlaylist(one);ready(model);assertEquals(2,t.requests.count{it.path.endsWith("/browse")})
            generation=2;model.accountMutated(YouTubeAccountMutation.PLAYLIST);model.openPlaylist(one);ready(model)
            assertEquals("V0000000003",model.videos.single().id);assertEquals(3,t.requests.count{it.path.endsWith("/browse")})
        }finally{scope.cancel()}
    }
    @Test fun confirmedRatingRefreshesAnInactiveLikedPageOnReturn()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();var generation=1
        t.respond={reply(page(generation))}
        try {
            val model=YouTubeModel(YouTubeClient(signed(),t),scope,{}, {_,_->},{})
            model.select(YouTubeFeed.LIKED);ready(model);model.deactivate();generation=2
            model.accountMutated(YouTubeAccountMutation.RATING);assertEquals(1,t.requests.count{it.path.endsWith("/browse")})
            model.activate();ready(model);assertEquals("V0000000002",model.videos.single().id)
            assertEquals(2,t.requests.count{it.path.endsWith("/browse")})
        }finally{scope.cancel()}
    }
    @Test fun activeMutationRefreshAndPhoneFirstAccountPublicationPreservePendingPlayback()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();t.respond={reply(page(1))}
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var opened:Watch?=null
        try {
            val client=YouTubeClient(signed(),t)
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch {
                started.complete(Unit);release.await();return Watch(video.title(),Stream("Generated","https://media.example.test/video.m3u8"),resolvedAtMs=System.currentTimeMillis())
            }}
            val model=YouTubeModel(client,scope,{}, {w,_->opened=w},{},resolver)
            client.account();assertNull(model.account);val before=t.requests.size;model.refreshAccountState()
            assertNotNull(model.account);assertEquals(before,t.requests.size)
            model.activate();ready(model);model.play(model.videos.single());withTimeout(3000){started.await()}
            model.accountMutated(YouTubeAccountMutation.PLAYLIST);ready(model);assertTrue(model.resolving)
            release.complete(Unit);withTimeout(3000){while(model.resolving)delay(1)};assertEquals("V0000000001",opened!!.title.id)
        }finally{scope.cancel()}
    }
}
