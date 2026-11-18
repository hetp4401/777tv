package xyz.billybishop4.fleet.tv

import com.grack.nanojson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class YouTubePlaybackResponseTest {
    private val video=YouTubeVideo("V0000000001","Selected title",isShort=true)
    private fun response(extra:String="",status:String="OK",id:String=video.id,url:String="https://media.example.test/master.m3u8")=
        JsonParser.`object`().from("""{"playabilityStatus":{"status":"$status"$extra},"videoDetails":{"videoId":"$id"},
            "streamingData":{"hlsManifestUrl":"$url"},"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
            {"baseUrl":"https://captions.example.test/text?lang=en","languageCode":"en"},
            {"baseUrl":"file:///invalid","languageCode":"fr"}]}}}""")
    @Test fun matchingPlaybackResponseKeepsIdentityCaptionsAndShortFramingWithoutPageMetadata() {
        val watch=playerWatch(video,response())!!
        assertEquals(video.title(),watch.title);assertTrue(watch.short);assertFalse(watch.live)
        assertEquals("application/x-mpegURL",watch.videoMimeType)
        assertEquals(listOf(VideoCaption("https://captions.example.test/text?lang=en&fmt=vtt","en","text/vtt")),watch.captions)
        assertFalse(watch.headers.keys.any{it.equals("Authorization",true)||it.equals("Cookie",true)})
    }
    @Test fun onlyAnActualLiveResponseDisablesTransportAndACompletedLiveVideoRemainsSeekable() {
        assertTrue(playerWatch(video,response(extra=",\"liveStreamability\":{}"))!!.live)
        val ended=response();ended.getObject("videoDetails")["isPostLiveDvr"]=true
        assertFalse(playerWatch(video,ended)!!.live)
    }
    @Test fun unavailableMismatchedOrUnsupportedResponsesUseTheMaintainedExtractorFallback() {
        assertNull(playerWatch(video,response(status="LOGIN_REQUIRED")))
        assertNull(playerWatch(video,response(id="V0000000002")))
        assertNull(playerWatch(video,response(url="file:///bad")))
        assertNull(playerWatch(video,JsonParser.`object`().from("{}")))
    }
    @Test fun castPlaceholderReusesTheMatchingPlayerMetadataWhileBrowseTitlesRemainUnchanged() {
        val response=response();response.getObject("videoDetails")["title"]="The real title";response.getObject("videoDetails")["author"]="The real creator"
        val cast=playerWatch(video.copy(name="YouTube video"),response)!!
        assertEquals("The real title",cast.title.name);assertEquals("The real creator",cast.title.description)
        assertEquals(video.title(),playerWatch(video,response)!!.title)
        assertEquals(video.title(),playbackTitle(video,"Other title","Other creator"))
    }
}
