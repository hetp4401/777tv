package xyz.billybishop4.fleet.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class YouTubePlayerPreferencesTest {
    @Test fun youtubePlaybackPreferencesAreIndependentOfMovieAndIptvPreferences() {
        val movie=Preferences(speed=1.5f,subtitles=true,subtitleLanguage="fr",liveBufferSeconds=6)
        val changed=movie.copy(youtubeSpeed=.5f,youtubeCaptions=true,youtubeSubtitleLanguage="es")
        val restored=Preferences.decode(changed.json())
        assertEquals(changed,restored)
        assertEquals(1.5f,restored.speed);assertTrue(restored.subtitles);assertEquals("fr",restored.subtitleLanguage)
        assertEquals(6,restored.liveBufferSeconds)
        val upgraded=Preferences.decode(movie.json().apply{remove("youtubeSpeed");remove("youtubeCaptions");remove("youtubeSubtitleLanguage")})
        assertEquals(1f,upgraded.youtubeSpeed);assertFalse(upgraded.youtubeCaptions);assertEquals("en",upgraded.youtubeSubtitleLanguage)
        assertEquals(movie.speed,upgraded.speed);assertEquals(movie.subtitleLanguage,upgraded.subtitleLanguage)
    }
    @Test fun invalidYouTubePlaybackValuesFallBackWithoutChangingTheMovieSettings() {
        val restored=Preferences.decode(JSONObject().put("speed",1.25).put("youtubeSpeed",99).put("youtubeSubtitleLanguage","https://invalid.test/"))
        assertEquals(1f,restored.youtubeSpeed);assertEquals("",restored.youtubeSubtitleLanguage);assertEquals(1.25f,restored.speed)
    }
    @Test fun descriptionIsReducedFromTheVideoBodyRatherThanTheCreatorBiography() {
        val json=JSONObject("""{"engagementPanels":[{"engagementPanelSectionListRenderer":{"content":{"videoDescriptionBodyRenderer":{"attributedDescriptionBodyText":{"content":"Actual video description"}}}}}],"channelMetadataRenderer":{"description":"Creator biography"}}""")
        assertEquals("Actual video description",YouTubeWatchDecode.details(json,"V0000000001").description)
        assertEquals("",YouTubeWatchDecode.details(JSONObject(),"V0000000001").description)
    }
}
