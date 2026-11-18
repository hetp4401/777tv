package xyz.billybishop4.fleet.tv

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeWatchTest {
    private fun markers(vararg values:JSONObject)=JSONObject().put("frameworkUpdates",JSONObject().put("entityBatchUpdate",JSONObject()
        .put("mutations",JSONArray().put(JSONObject().put("payload",JSONObject().put("macroMarkersListEntity",JSONObject()
            .put("markersList",JSONObject().put("markerType","MARKER_TYPE_HEATMAP").put("markers",JSONArray(values.toList())))))))))
    private fun marker(start:Long,score:Double,duration:Long=1000)=JSONObject().put("startMillis",start.toString()).put("durationMillis",duration.toString()).put("intensityScoreNormalized",score)
    @Test fun replayGraphReadsCurrentEntityMutationsAndOrdersActualTimeRanges() {
        val result=YouTubeWatchDecode.heatmap(markers(marker(2000,.9),marker(0,.2)))
        assertEquals(listOf(0L,2000L),result.map{it.startMs});assertEquals(1000,result[0].durationMs);assertEquals(.9f,result[1].intensity)
    }
    @Test fun replayGraphRejectsInvalidRangesScoresAndDuplicatesAndHasBoundedMemory() {
        val values=mutableListOf(marker(-1,.5),marker(0,1.5),marker(0,.5,0),marker(1000,.5),marker(1000,.2))
        values.addAll((2..800).map{marker(it*1000L,.4)})
        val result=YouTubeWatchDecode.heatmap(markers(*values.toTypedArray()))
        assertEquals(256,result.size);assertEquals(1000,result.first().startMs);assertEquals(.5f,result.first().intensity)
    }
    @Test fun legacyReplayMarkersWorkButAbsentReplayDataStaysEmpty() {
        val j=JSONObject("""{"playerOverlays":{"heatMarkerRenderer":{"timeRangeStartMillis":1200,"markerDurationMillis":300,"heatMarkerIntensityScoreNormalized":0.75}}}""")
        assertEquals(listOf(YouTubeHeatMarker(1200,300,.75f)),YouTubeWatchDecode.heatmap(j))
        assertTrue(YouTubeWatchDecode.heatmap(JSONObject("""{"chapters":[{"startMillis":"0"}]}""")).isEmpty())
    }
    @Test fun chapterMarkersNeverBecomeAReplayGraph() {
        val j=markers(marker(0,.9));j.getJSONObject("frameworkUpdates").getJSONObject("entityBatchUpdate").getJSONArray("mutations")
            .getJSONObject(0).getJSONObject("payload").getJSONObject("macroMarkersListEntity").getJSONObject("markersList").put("markerType","MARKER_TYPE_CHAPTERS")
        assertTrue(YouTubeWatchDecode.heatmap(j).isEmpty())
    }
    @Test fun commentPagingUsesTheSectionCursorAndPreservesReplyCursorsSeparately() {
        val j=JSONObject("""{"continuationContents":{"itemSectionContinuation":{"contents":[{"commentThreadRenderer":{"comment":{"commentRenderer":{"commentId":"one","authorText":{"simpleText":"Viewer"},"contentText":{"runs":[{"text":"Hello "},{"text":"there"}]},"publishedTimeText":{"simpleText":"Yesterday"},"voteCount":{"simpleText":"12"},"repliesCount":{"simpleText":"3 replies"},"detailViewEndpoint":{"reloadContinuationData":{"continuation":"replies"}}}}}}],"continuations":[{"nextContinuationData":{"continuation":"page-2"}}]}}}""")
        val page=YouTubeWatchDecode.comments(j);assertEquals("page-2",page.continuation)
        val comment=page.comments.single();assertEquals("replies",comment.repliesToken);assertEquals("Hello there",comment.body);assertEquals("Yesterday · 12 likes",comment.metadata)
    }
    @Test fun disabledCommentsHaveAnHonestEmptyState() {
        val page=YouTubeWatchDecode.comments(JSONObject("""{"messageRenderer":{"text":{"simpleText":"Comments are turned off."}}}"""))
        assertEquals("Comments are turned off.",page.message);assertTrue(page.comments.isEmpty());assertNull(page.continuation)
        assertNull(YouTubeWatchDecode.details(JSONObject(),"abcdefghijk").commentsToken)
    }
    @Test fun watchDetailsExcludeCurrentVideoAndFindTheCommentsPanel() {
        val j=JSONObject("""{"contents":{"singleColumnWatchNextResults":{"pivot":{"contents":[{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Current"}}},{"videoRenderer":{"videoId":"12345678901","title":{"simpleText":"Related"}}}]}}},"engagementPanels":[{"engagementPanelSectionListRenderer":{"panelIdentifier":"comment-item-section","header":{"engagementPanelTitleHeaderRenderer":{"title":{"simpleText":"Comments"}}},"content":{"reloadContinuationData":{"continuation":"comments"}}}}]}""")
        val result=YouTubeWatchDecode.details(j,"abcdefghijk");assertEquals(listOf("12345678901"),result.related.videos.map{it.id});assertEquals("comments",result.commentsToken)
    }
    @Test fun replayRequestsUsePublishedWebVersionWithoutSendingTheTvBearer()=runBlocking {
        val transport=FixtureYouTubeTransport();transport.respond={r->if(r.path=="/")YouTubeHttpResponse(200,"""{"INNERTUBE_CLIENT_VERSION":"2.web-fixture","VISITOR_DATA":"visitor"}""")else YouTubeHttpResponse(200,markers(marker(0,.5)).toString())}
        val client=YouTubeClient(MemoryYouTubeCredentials(YouTubeSession("private-access","private-refresh",Long.MAX_VALUE)),transport)
        assertEquals(1,client.heatmap("abcdefghijk").size);client.heatmap("12345678901")
        assertEquals(1,transport.requests.count{it.path=="/"})
        assertTrue(transport.requests.all{it.authorization==null})
        val body=transport.requests.last().body!!;assertEquals("WEB",body.getJSONObject("context").getJSONObject("client").getString("clientName"))
        assertEquals("2.web-fixture",body.getJSONObject("context").getJSONObject("client").getString("clientVersion"))
    }
    @Test fun liveCapturedResponseShapesParseWithoutKeepingPayloads() {
        // This runs only when local research captures exist; the fixture cases above run everywhere.
        val root=java.io.File("../analysis/tizentube")
        val next=java.io.File(root,"youtube-next-probe.json");val comments=java.io.File(root,"youtube-comments-probe.json");val heatmap=java.io.File(root,"youtube-web-next-probe.json")
        if(!next.isFile || !comments.isFile || !heatmap.isFile)return
        assertTrue(YouTubeWatchDecode.details(JSONObject(next.readText()),"aqz-KE-bpKQ").related.videos.isNotEmpty())
        assertEquals(20,YouTubeWatchDecode.comments(JSONObject(comments.readText())).comments.size)
        assertEquals(100,YouTubeWatchDecode.heatmap(JSONObject(heatmap.readText())).size)
    }
}
