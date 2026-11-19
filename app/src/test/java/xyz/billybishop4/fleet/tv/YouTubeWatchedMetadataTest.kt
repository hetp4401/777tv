package xyz.billybishop4.fleet.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubeWatchedMetadataTest {
    private fun progress(value:Any)=JSONObject().put("thumbnailOverlayResumePlaybackRenderer",JSONObject().put("percentDurationWatched",value))
    private fun legacy(value:Any)=JSONObject().put("tileRenderer",JSONObject().put("videoId","abcdefghijk")
        .put("title","A watched tile").put("header",JSONObject().put("tileHeaderRenderer",JSONObject()
            .put("thumbnailOverlays",JSONArray().put(progress(value))))))
    private fun modern(value:Any)=JSONObject().put("lockupViewModel",JSONObject().put("contentId","abcdefghijk")
        .put("contentType","LOCKUP_CONTENT_TYPE_VIDEO").put("title","A modern watched tile")
        .put("contentImage",JSONObject().put("thumbnailViewModel",JSONObject().put("overlays",JSONArray().put(progress(value))))))

    @Test fun legacyAndModernWatchedPercentagesAreOnlyDisplayMetadata() {
        for(card in listOf(legacy(100),modern("25"))) {
            val video=YouTubeDecode.page(card).videos.single()
            assertNotNull(video.watchedPercent);assertNull("Rounded percentages must never become an exact playback seek",video.resumePositionMs)
        }
        assertEquals(100,YouTubeDecode.page(legacy(100)).videos.single().watchedPercent)
        assertEquals(25,YouTubeDecode.page(modern("25")).videos.single().watchedPercent)
    }

    @Test fun explicitServerSeekRemainsIndependentAndMalformedPercentagesAreRejected() {
        val card=modern(90);card.getJSONObject("lockupViewModel").put("onTap",JSONObject().put("watchEndpoint",JSONObject()
            .put("videoId","abcdefghijk").put("startTimeSeconds",0)))
        val video=YouTubeDecode.page(card).videos.single();assertEquals(0L,video.resumePositionMs);assertEquals(90,video.watchedPercent)
        for(value in listOf(-1,101,20.5,"NaN","no progress",true))assertNull(YouTubeDecode.page(legacy(value)).videos.single().watchedPercent)
        assertEquals(0,YouTubeDecode.page(legacy(0)).videos.single().watchedPercent)
    }
}
