package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeSearchShelvesTest {
    private fun card(n:Int,short:Boolean=false,type:String=if(short)"SHORT" else "VIDEO")=JSONObject().put("lockupViewModel",JSONObject()
        .put("contentId",n.toString().padStart(11,'0')).put("contentType","LOCKUP_CONTENT_TYPE_$type")
        .put("metadata",JSONObject().put("lockupMetadataViewModel",JSONObject().put("title",JSONObject().put("content","Video $n")))))
    private fun shelf(name:String,vararg cards:JSONObject,cursor:String?=null,shorts:Boolean=false):JSONObject {
        val list=JSONObject().put("items",JSONArray(cards.toList()))
        if(cursor!=null)list.put("continuations",JSONArray().put(JSONObject().put("nextContinuationData",JSONObject().put("continuation",cursor))))
        return JSONObject().put("shelfRenderer",JSONObject().put("headerRenderer",JSONObject().put("shelfHeaderRenderer",JSONObject()
            .put("avatarLockup",JSONObject().put("avatarLockupRenderer",JSONObject().put("title",JSONObject().put("simpleText",name))))))
            .put("content",JSONObject().put("horizontalListRenderer",list)).apply{if(shorts)put("tvhtml5ShelfRendererType","TVHTML5_SHELF_RENDERER_TYPE_SHORTS")})
    }
    private fun root(vararg rows:JSONObject)=JSONObject().put("contents",JSONObject().put("sectionListRenderer",JSONObject().put("contents",JSONArray(rows.toList()))))
    private fun id(n:Int)=n.toString().padStart(11,'0')
    private suspend fun ready(model:YouTubeModel){withTimeout(3000){while(model.busy)delay(1)}}
    @Test fun actualTvMixedResultsKeepVideoSectionsBeforeOneCanonicalShortsRow() {
        val result=YouTubeSearchPages.decode(root(
            shelf("Search results for cars",card(1,true),card(2),card(3,true),card(4),cursor="main-results"),
            shelf("Recently uploaded",card(5)),shelf("Shorts",card(6),shorts=true,cursor="wrong-shorts-cursor")))
        assertEquals(listOf("Search results for cars","Recently uploaded","Shorts"),result.shelves.map{it.name})
        assertEquals(listOf(false,false,true),result.shelves.map{it.portrait})
        assertEquals(listOf(id(2),id(4),id(5),id(1),id(3),id(6)),result.videos.map{it.id})
        assertEquals("main-results",result.continuation);assertTrue(result.shelves.last().videos.all{it.isShort})
    }
    @Test fun canonicalShortsWinDuplicateIdentityAcrossRegularAndShortsSections() {
        val result=YouTubeSearchPages.decode(root(shelf("Results",card(1),card(2)),shelf("Shorts",card(1,true),card(3,true))))
        assertEquals(listOf(id(2),id(1),id(3)),result.videos.map{it.id})
        assertEquals(result.videos.size,result.videos.distinctBy{it.id}.size);assertTrue(result.videos.first{it.id==id(1)}.isShort)
    }
    @Test fun portraitShapeDurationAndWordShortsNeverClassifyAnOrdinaryVideo() {
        val ordinary=card(1).getJSONObject("lockupViewModel").put("styling",JSONObject().put("contentImageAspectRatio","9_BY_16"))
            .put("lengthText","0:20").put("title","Shorts documentary")
        val result=YouTubeSearchPages.decode(JSONObject().put("lockupViewModel",ordinary))
        assertFalse(result.videos.single().isShort);assertFalse(result.shelves.single().portrait)
        val reel=JSONObject().put("reelShelfRenderer",JSONObject().put("items",JSONArray().put(card(2))))
        assertTrue(YouTubeDecode.page(reel).videos.single().isShort)
    }
    @Test fun musicAndFreeMoviesNeedRealWatchEndpointsRatherThanBrowseDestinations() {
        fun playable(n:Int,type:String)=card(n,type=type).apply { getJSONObject("lockupViewModel").put("rendererContext",JSONObject()
            .put("commandContext",JSONObject().put("onTap",JSONObject().put("innertubeCommand",JSONObject().put("watchEndpoint",JSONObject().put("videoId",id(n))))))) }
        val paid=card(3,type="MOVIE").apply{getJSONObject("lockupViewModel").put("onTap",JSONObject().put("browseEndpoint",JSONObject().put("browseId","MV${id(3)}")))}
        val playlist=playable(4,"PLAYLIST")
        val result=YouTubeSearchPages.decode(JSONObject().put("contents",JSONArray().put(playable(1,"MUSIC")).put(playable(2,"MOVIE")).put(paid).put(playlist)))
        assertEquals(listOf(id(1),id(2)),result.videos.map{it.id});assertTrue(result.videos.none{it.isShort})
    }
    @Test fun bareContinuationsAppendToPrimaryVideoRowWithoutRenamingOrDuplicatingShorts() {
        val first=YouTubeSearchPages.decode(root(shelf("Search results for NASA",card(1),card(2,true),cursor="next"),shelf("Recently uploaded",card(3))))
        val next=YouTubeSearchPages.decode(JSONObject().put("continuationContents",JSONObject().put("horizontalListContinuation",JSONObject()
            .put("items",JSONArray().put(card(1)).put(card(4)).put(card(2,true)).put(card(5,true))))))
        val merged=YouTubeSearchPages.merge(first.shelves,next.shelves)
        assertEquals(listOf("Search results for NASA","Recently uploaded","Shorts"),merged.map{it.name})
        assertEquals(listOf(id(1),id(4)),merged.first().videos.map{it.id});assertEquals(listOf(id(2),id(5)),merged.last().videos.map{it.id})
        assertEquals(listOf("search/videos","search/section/Recently uploaded/0","search/shorts"),merged.map{it.key})
    }
    @Test fun mixedSearchBudgetsLeaveRoomForRegularPaginationAndKeepShortsStable() {
        val regular=(1..130).map{YouTubeVideo(id(it),"Video $it")}
        val shorts=(1001..1130).map{YouTubeVideo(id(it),"Clip $it",isShort=true)}
        val initial=YouTubeSearchPages.fromVideos(regular.take(20)+shorts)
        assertEquals(40,initial.sumOf{it.videos.size});assertEquals(20,initial.last().videos.size)
        val merged=YouTubeSearchPages.merge(initial,YouTubeSearchPages.fromVideos(regular.drop(20)+shorts.asReversed()))
        assertEquals(120,merged.sumOf{it.videos.size});assertEquals(100,merged.first().videos.size)
        assertEquals(initial.last().videos,merged.last().videos)
        assertEquals(120,YouTubeSearchPages.fromVideos(regular).sumOf{it.videos.size})
        assertEquals(120,YouTubeSearchPages.fromVideos(shorts).sumOf{it.videos.size})
    }
    @Test fun clientAndModelPreserveGroupedPaginationAndCachedReturnWithoutMediaResolution()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob());val t=FixtureYouTubeTransport();var resolutions=0
        t.respond={r->YouTubeHttpResponse(200,if(r.body!!.has("continuation"))JSONObject().put("contents",JSONArray().put(card(1)).put(card(3)).put(card(4,true))).toString()
            else root(shelf("Search results for NASA",card(1),card(2,true),cursor="next-results")).toString())}
        try {
            val resolver=object:YouTubeStreamResolver{override suspend fun resolve(video:YouTubeVideo):Watch{resolutions++;error("Browsing must not resolve")}}
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),t),scope,{}, {_,_->},{},resolver)
            model.showSearch();model.editQuery("NASA");model.submitSearch();ready(model);model.more();ready(model)
            assertEquals(listOf(id(1),id(3),id(2),id(4)),model.videos.map{it.id});assertEquals(2,model.searchShelves.size)
            assertEquals("Search results for NASA",model.searchShelves.first().name)
            val before=t.requests.size;val rows=model.searchShelves;model.deactivate();model.activate();ready(model)
            assertEquals(rows,model.searchShelves);assertEquals(before,t.requests.size);assertEquals(0,resolutions)
            assertEquals("next-results",t.requests.last().body!!.getString("continuation"))
        }finally{scope.cancel()}
    }
    @Test fun directFixturePagesAlsoSplitShortsAndKeepThe120CardLimit()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val model=YouTubeModel(YouTubeClient(MemoryYouTubeCredentials(),FixtureYouTubeTransport()),scope,{}, {_,_->},{})
            model.installFixturePage(YouTubeFeed.SEARCH,YouTubePage((1..130).map{YouTubeVideo(id(it),"Video $it",isShort=it%2==0)}))
            assertEquals(listOf(false,true),model.searchShelves.map{it.portrait});assertTrue(model.videos.size<=120)
        }finally{scope.cancel()}
    }
}
