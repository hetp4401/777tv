package xyz.billybishop4.fleet.tv

import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RobustnessTest {
    @Test fun `prepared channel catalog preserves provider order across independent filters`() {
        val z=Channel("1","Zulu","https://provider.example/z","News")
        val a=Channel("2","Alpha","https://provider.example/a","News")
        val s=Channel("3","ગુજરાતી العربية","https://provider.example/s","Sports")
        val source=listOf(z,a,s,a);val index=ChannelIndex(source)
        assertEquals(listOf("News","Sports"),index.categories)
        assertEquals(listOf(a,z,s),index.select("p","All channels","",false,emptySet(),true,emptySet()))
        assertEquals(listOf(s),index.select("p","All channels","",false,emptySet(),false,setOf("News")))
        assertEquals(listOf(a),index.select("p","News","ALP",true,setOf(favoriteKey("p",a)),false,emptySet()))
        assertEquals(listOf(z,a,s),index.select("p","All channels","",false,emptySet(),false,emptySet()))
        assertEquals(listOf(z,a,s,a),source)
    }
    @Test fun `HTTP gzip is decoded and status errors retain their code`() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer(); server.start()
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write("{\"ok\":true}".toByteArray()) }
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(out.toByteArray())).addHeader("Content-Encoding", "gzip"))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
        try {
            assertTrue(JSONObject(Http().text(server.url("/").toString())).getBoolean("ok"))
            try { Http().text(server.url("/bad").toString()); fail("Expected HTTP failure") } catch (e: HttpFailure) { assertEquals(503, e.status) }
        } finally { server.shutdown() }
    }
    @Test fun `cross origin redirect does not forward bearer or cookie`() = runBlocking {
        val a = okhttp3.mockwebserver.MockWebServer(); val b = okhttp3.mockwebserver.MockWebServer(); a.start(); b.start()
        a.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).addHeader("Location", b.url("/")))
        b.enqueue(okhttp3.mockwebserver.MockResponse().setBody("ok"))
        try {
            assertEquals("ok", Http().text(a.url("/").toString(), headers = mapOf("Authorization" to "Bearer secret", "Cookie" to "account=private")))
            val seen = b.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(seen.getHeader("Authorization")); assertNull(seen.getHeader("Cookie"))
        } finally { a.shutdown(); b.shutdown() }
    }
    @Test fun `cancel interrupts an outstanding response without waiting for timeout`() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer(); server.start()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        try {
            val pending = async { Http().text(server.url("/").toString(), timeout = 15000) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            val start = System.nanoTime(); pending.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
        } finally { server.shutdown() }
    }
    @Test fun `invalid persisted settings are bounded and known settings survive reload`() {
        val prefs = Preferences.decode(JSONObject("""{"speed":"bad","seekSeconds":-1,"liveRetries":999,"liveBufferSeconds":-3,"streamMode":"bad","videoFit":"stretch","audioLanguage":"en;secret","largePosters":true}"""))
        assertEquals(1f, prefs.speed); assertEquals(10, prefs.seekSeconds); assertEquals(3, prefs.liveRetries)
        assertEquals(3, prefs.liveBufferSeconds); assertEquals("Fit", prefs.videoFit); assertEquals("", prefs.audioLanguage)
        assertEquals(prefs, Preferences.decode(prefs.json()))
    }
    @Test fun `legacy engine selection is ignored while playback appearance and profile preferences survive`() {
        val expected=Preferences(startSection="On-Demand",largePosters=true,showRecent=false,showContinue=false,
            showMyList=false,resume=false,largestFirst=false,speed=1.5f,seekSeconds=30,audioLanguage="hi",
            subtitleLanguage="fr",subtitles=true,videoFit="Zoom",liveBufferSeconds=6,liveRetries=1,
            liveSort="A–Z",defaultProfileId="living-room-default",youtubeAutoplay=false)
        for(legacy in listOf("mpv","ExoPlayer","unknown")) {
            val decoded=Preferences.decode(expected.json().put("videoEngine",legacy))
            assertEquals(expected,decoded)
            assertFalse(decoded.json().has("videoEngine"))
        }
    }
    @Test fun `fresh and legacy preferences start on demand while other saved choices survive`() {
        assertEquals("On-Demand",Preferences().startSection)
        for(legacy in listOf("Live TV","Search","unknown")) {
            val input=Preferences(largePosters=true,speed=1.5f,defaultProfileId="saved-profile").json()
            input.remove("startupScreen");input.put("startSection",legacy)
            assertEquals(Preferences(largePosters=true,speed=1.5f,defaultProfileId="saved-profile"),Preferences.decode(input))
        }
    }
    @Test fun `explicit startup choices survive reload and invalid choices fall back to on demand`() {
        for(choice in listOf("On-Demand","Live TV")) {
            val expected=Preferences(startSection=choice)
            val saved=expected.json().put("startSection","Search")
            assertEquals(expected,Preferences.decode(saved))
        }
        for(invalid in listOf("Search","unknown",""))assertEquals("On-Demand",Preferences.decode(JSONObject().put("startupScreen",invalid)).startSection)
    }
    @Test fun `channel filters combine group name favorites and alphabetical order`() {
        val z = Channel("1", "Zulu", "https://provider.example/z", "News")
        val a = Channel("2", "Alpha", "https://provider.example/a", "News")
        val s = Channel("3", "Sport", "https://provider.example/s", "Sports")
        val favorites = setOf(favoriteKey("p", z), favoriteKey("p", a))
        assertEquals(listOf(a, z), visibleChannels(listOf(z, s, a, a), "p", "News", "", true, favorites, true))
        assertEquals(listOf(z), visibleChannels(listOf(z, a), "p", "News", "zul", false, emptySet(), false))
    }
    @Test fun `hidden categories stay out of all channels search and favorites`() {
        val news = Channel("1", "Morning News", "https://provider.example/news", "News")
        val sports = Channel("2", "Sports News", "https://provider.example/sports", "Sports")
        val channels = listOf(news, sports)
        val favorites = channels.map { favoriteKey("p", it) }.toSet()
        assertEquals(listOf(sports), visibleChannels(channels,"p","All channels","",false,favorites,false,setOf("News")))
        assertEquals(listOf(sports), visibleChannels(channels,"p","All channels","News",true,favorites,true,setOf("News")))
        assertTrue(visibleChannels(channels,"p","News","",false,favorites,false,setOf("News")).isEmpty())
        assertTrue(visibleChannels(channels,"p","All channels","",false,favorites,false,setOf("News","Sports")).isEmpty())
        assertEquals(channels, visibleChannels(channels,"p","All channels","",false,favorites,false))
    }
    @Test fun `favorite identifiers survive query token renewal and contain no credentials`() {
        val a = Channel("news", "News", "https://provider.example/live/user/private.m3u8?secret=one")
        val b = a.copy(url = a.url.replace("secret=one", "secret=two"))
        assertEquals(favoriteKey("p", a), favoriteKey("p", b))
        assertFalse(favoriteKey("p", a).contains("private"))
        assertFalse(favoriteKey("p", a).contains("secret"))
    }
    @Test fun `duplicate episode and title identifiers cannot create duplicate UI keys`() {
        val j = JSONObject("""{"id":"tt1","name":"Series","videos":[{"id":"tt1:1:1","season":1,"episode":1},{"id":"tt1:1:1","season":1,"episode":1}]}""")
        assertEquals(1, Decode.title(j).episodes.size)
        assertEquals(1, Decode.titles(org.json.JSONArray().put(j).put(j)).size)
    }
}
