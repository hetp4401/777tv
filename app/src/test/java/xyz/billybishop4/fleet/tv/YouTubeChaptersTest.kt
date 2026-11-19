package xyz.billybishop4.fleet.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeChaptersTest {
    private val id="abcdefghijk"
    private fun chapter(title:String,start:Any)=JSONObject().put("chapterRenderer",JSONObject()
        .put("title",JSONObject().put("simpleText",title)).put("timeRangeStartMillis",start))
    private fun legacy(vararg values:JSONObject)=JSONObject().put("playerOverlays",JSONObject()
        .put("multiMarkersPlayerBarRenderer",JSONObject().put("markersMap",JSONArray().put(JSONObject()
            .put("value",JSONObject().put("chapters",JSONArray(values.toList())))))))
    private fun item(title:String,start:Any,videoId:String=id)=JSONObject().put("macroMarkersListItemRenderer",JSONObject()
        .put("title",JSONObject().put("runs",JSONArray().put(JSONObject().put("text",title))))
        .put("onTap",JSONObject().put("watchEndpoint",JSONObject().put("videoId",videoId).put("startTimeSeconds",start))))
    private fun panel(vararg values:JSONObject,panelId:String="engagement-panel-macro-markers-description-chapters")=JSONObject()
        .put("engagementPanels",JSONArray().put(JSONObject().put("engagementPanelSectionListRenderer",JSONObject()
            .put("panelIdentifier",panelId).put("content",JSONObject().put("macroMarkersListRenderer",JSONObject()
                .put("contents",JSONArray(values.toList())))))))

    @Test fun tvPlayerMarkersKeepRealTimestampsAndReadableTitlesWithoutArtwork() {
        val page=legacy(chapter("  Intro\n and specs ",0),chapter("Final thoughts",125_000),chapter("Gameplay",50_000))
        assertEquals(listOf(YouTubeChapter("Intro and specs",0),YouTubeChapter("Gameplay",50_000),YouTubeChapter("Final thoughts",125_000)),
            YouTubeWatchDecode.details(page,id).chapters)
    }

    @Test fun tvMacroChapterPanelConvertsSecondsAndRejectsAnotherVideoOrPanel() {
        val page=panel(item("Overview",0),item("Main section","66"),item("Wrong video",20,"12345678901"))
        assertEquals(listOf(YouTubeChapter("Overview",0),YouTubeChapter("Main section",66_000)),YouTubeWatchDecode.details(page,id).chapters)
        assertTrue(YouTubeWatchDecode.details(panel(item("Other",3),panelId="comment-item-section"),id).chapters.isEmpty())
    }

    @Test fun invalidOrDuplicateChaptersAreDroppedAndLargeListsStayBounded() {
        val values=mutableListOf(chapter("Negative",-1),chapter("Fraction",1.5),chapter("Too long",604_800_001),
            chapter("  ",0),chapter("First",1_000),chapter("Duplicate",1_000))
        values+=(2..250).map{chapter("Chapter $it",it*1000)}
        val chapters=YouTubeWatchDecode.details(legacy(*values.toTypedArray()),id).chapters
        assertEquals(80,chapters.size);assertEquals(YouTubeChapter("First",1_000),chapters.first())
        assertEquals(80_000,chapters.last().startMs);assertEquals(80,chapters.distinctBy{it.startMs}.size)
        assertTrue(YouTubeWatchDecode.details(JSONObject(),id).chapters.isEmpty())
    }

    @Test fun replayAndChapterMarkersStayIndependent() {
        val response=legacy(chapter("Intro",0))
        assertEquals(1,YouTubeWatchDecode.details(response,id).chapters.size)
        assertTrue(YouTubeWatchDecode.heatmap(response).isEmpty())
        assertTrue(YouTubeWatchDecode.details(JSONObject("""{"heatMarkerRenderer":{"timeRangeStartMillis":0,"markerDurationMillis":1000}}"""),id).chapters.isEmpty())
    }
}
